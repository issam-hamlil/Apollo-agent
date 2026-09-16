package com.issam.apollo.telemetry

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.atomic.AtomicLong

/**
 * Structured events published by the pipeline so a UI can render a run without
 * anyone having to read terminal output.
 *
 * The pipeline never depends on a consumer existing - emitting is fire-and-forget and
 * never blocks, so running the CLI with no UI attached behaves exactly as before.
 */
sealed interface ApolloEvent {
    val at: Long
}

/** Pipeline moved to a new graph node (ANALYZER, VERIFIER, FIXER, ...). */
data class StageEvent(
    val stage: String,
    val detail: String = "",
    override val at: Long = System.currentTimeMillis()
) : ApolloEvent

/** A module started or finished a unit of work. [phase] is MIGRATING / FIXING / VERIFYING / DONE. */
data class ModuleActivityEvent(
    val module: String,
    val phase: String,
    val detail: String = "",
    val active: Boolean = true,
    override val at: Long = System.currentTimeMillis()
) : ApolloEvent

/** A request being dispatched to an LLM, including the full prompt text. */
data class LlmCallEvent(
    val callId: Long,
    val module: String,
    val provider: String,
    val model: String,
    val tier: String,
    val attempt: Int,
    val systemPrompt: String,
    val userPrompt: String,
    override val at: Long = System.currentTimeMillis()
) : ApolloEvent {
    val promptChars: Int get() = systemPrompt.length + userPrompt.length
    val estimatedTokens: Int get() = promptChars / 4
}

/** The outcome of a previously announced [LlmCallEvent]. */
data class LlmResultEvent(
    val callId: Long,
    val module: String,
    val ok: Boolean,
    val response: String = "",
    val error: String = "",
    val elapsedMs: Long = 0,
    override val at: Long = System.currentTimeMillis()
) : ApolloEvent

/** Result of a Kotlin compilation pass over the migrated sources. */
data class CompileEvent(
    val success: Boolean,
    val errors: List<String> = emptyList(),
    override val at: Long = System.currentTimeMillis()
) : ApolloEvent {
    /** Warnings are noise for this audience - only hard errors are counted. */
    val errorCount: Int get() = errors.count { !it.trimStart().startsWith("warning:") }
}

/** Per-module ground-truth verification outcome. */
data class ModuleResultEvent(
    val module: String,
    val passed: Int,
    val total: Int,
    val status: String,
    val failures: List<String> = emptyList(),
    override val at: Long = System.currentTimeMillis()
) : ApolloEvent

/** A generated artifact was written to disk. */
data class FileEvent(
    val path: String,
    val kind: String,
    val lines: Int = 0,
    override val at: Long = System.currentTimeMillis()
) : ApolloEvent

/** Mirror of a console line, so nothing shown in the terminal is missing from the UI. */
data class LogEvent(
    val level: String,
    val message: String,
    override val at: Long = System.currentTimeMillis()
) : ApolloEvent

/** Terminal state of a whole run. */
data class RunFinishedEvent(
    val ok: Boolean,
    val summary: String,
    override val at: Long = System.currentTimeMillis()
) : ApolloEvent

object ApolloTelemetry {

    private val _events = MutableSharedFlow<ApolloEvent>(
        replay = 4000,
        extraBufferCapacity = 8000,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    val events: SharedFlow<ApolloEvent> = _events.asSharedFlow()

    private val callIds = AtomicLong(0)

    /**
     * One LLM exchange kept in memory so a failure report can show exactly what the model
     * was asked for the modules that did not make it.
     */
    data class RecordedExchange(
        val callId: Long,
        val module: String,
        val provider: String,
        val model: String,
        val attempt: Int,
        val systemPrompt: String,
        val userPrompt: String,
        var ok: Boolean = false,
        var finished: Boolean = false,
        var response: String = "",
        var error: String = ""
    )

    private const val MAX_RECORDED_EXCHANGES = 200
    private val recorded = java.util.Collections.synchronizedList(mutableListOf<RecordedExchange>())

    /** Exchanges for [module], oldest first. Empty when the module never reached an LLM. */
    fun exchangesFor(module: String): List<RecordedExchange> =
        synchronized(recorded) { recorded.filter { it.module == module }.toList() }

    fun allExchanges(): List<RecordedExchange> = synchronized(recorded) { recorded.toList() }

    fun resetRecordedExchanges() = synchronized(recorded) { recorded.clear() }

    private fun record(event: ApolloEvent) {
        when (event) {
            is LlmCallEvent -> synchronized(recorded) {
                recorded.add(
                    RecordedExchange(
                        event.callId, event.module, event.provider, event.model,
                        event.attempt, event.systemPrompt, event.userPrompt
                    )
                )
                while (recorded.size > MAX_RECORDED_EXCHANGES) recorded.removeAt(0)
            }

            is LlmResultEvent -> synchronized(recorded) {
                // A failed stream can report twice; keep the first verdict.
                recorded.lastOrNull { it.callId == event.callId && !it.finished }?.apply {
                    finished = true
                    ok = event.ok
                    response = event.response
                    error = event.error
                }
            }

            else -> Unit
        }
    }

    fun nextCallId(): Long = callIds.incrementAndGet()

    /** Never blocks and never throws - telemetry must not be able to fail a migration. */
    fun emit(event: ApolloEvent) {
        try {
            record(event)
            _events.tryEmit(event)
        } catch (_: Throwable) {
            // Deliberately ignored.
        }
    }

    fun log(message: String, level: String = "INFO") = emit(LogEvent(level, message))
}
