package com.issam.apollo.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import com.issam.apollo.telemetry.ApolloEvent
import com.issam.apollo.telemetry.ApolloTelemetry
import com.issam.apollo.telemetry.CompileEvent
import com.issam.apollo.telemetry.FileEvent
import com.issam.apollo.telemetry.LlmCallEvent
import com.issam.apollo.telemetry.LlmResultEvent
import com.issam.apollo.telemetry.LogEvent
import com.issam.apollo.telemetry.ModuleActivityEvent
import com.issam.apollo.telemetry.ModuleResultEvent
import com.issam.apollo.telemetry.RunFinishedEvent
import com.issam.apollo.telemetry.StageEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One LLM exchange: the request, and the reply when it arrives. */
class LlmExchange(
    val callId: Long,
    val module: String,
    val provider: String,
    val model: String,
    val tier: String,
    val attempt: Int,
    val systemPrompt: String,
    val userPrompt: String,
    val startedAt: Long
) {
    var finished by mutableStateOf(false)
    var ok by mutableStateOf(false)
    var response by mutableStateOf("")
    var error by mutableStateOf("")
    var elapsedMs by mutableStateOf(0L)

    val promptChars: Int get() = systemPrompt.length + userPrompt.length
    val estimatedTokens: Int get() = promptChars / 4
}

/** What we know about one module right now. */
class ModuleView(val name: String) {
    var passed by mutableStateOf(0)
    var total by mutableStateOf(0)
    var status by mutableStateOf("PENDING")
    var activity by mutableStateOf("")
    var busy by mutableStateOf(false)
    var failures: List<String> by mutableStateOf(emptyList())
}

/**
 * Single observable snapshot of a run, fed by [ApolloTelemetry].
 * Everything the UI renders comes from here - no polling of the pipeline.
 */
class RunState(private val scope: CoroutineScope) {

    var stage by mutableStateOf("IDLE")
    var running by mutableStateOf(false)
    var finishedOk by mutableStateOf<Boolean?>(null)
    var finishedSummary by mutableStateOf("")
    var currentModel by mutableStateOf("-")
    var currentProvider by mutableStateOf("-")

    var compileOk by mutableStateOf<Boolean?>(null)
    var compileErrors: List<String> by mutableStateOf(emptyList())

    val modules: SnapshotStateList<ModuleView> = mutableStateListOf()
    val exchanges: SnapshotStateList<LlmExchange> = mutableStateListOf()
    val logLines: SnapshotStateList<String> = mutableStateListOf()
    val files: SnapshotStateList<FileEvent> = mutableStateListOf()

    val totalPassed: Int get() = modules.sumOf { it.passed }
    val totalTests: Int get() = modules.sumOf { it.total }
    val hardErrorCount: Int
        get() = compileErrors.count { !it.trimStart().startsWith("warning:") }

    private fun module(name: String): ModuleView =
        modules.firstOrNull { it.name == name } ?: ModuleView(name).also { modules.add(it) }

    fun start() {
        scope.launch {
            ApolloTelemetry.events.collect { event ->
                withContext(Dispatchers.Main) { apply(event) }
            }
        }
    }

    fun reset() {
        stage = "IDLE"; finishedOk = null; finishedSummary = ""
        compileOk = null; compileErrors = emptyList()
        modules.clear(); exchanges.clear(); logLines.clear(); files.clear()
    }

    private fun apply(event: ApolloEvent) {
        when (event) {
            is StageEvent -> stage = event.stage

            is ModuleActivityEvent -> module(event.module).let {
                it.activity = event.detail
                it.busy = event.active
            }

            is LlmCallEvent -> {
                currentModel = event.model
                currentProvider = event.provider
                exchanges.add(
                    LlmExchange(
                        event.callId, event.module, event.provider, event.model,
                        event.tier, event.attempt, event.systemPrompt, event.userPrompt, event.at
                    )
                )
                if (event.module.isNotBlank()) module(event.module).busy = true
            }

            is LlmResultEvent -> {
                // A failed stream can report twice (inner check + catch); keep the first verdict.
                exchanges.lastOrNull { it.callId == event.callId && !it.finished }?.apply {
                    finished = true
                    ok = event.ok
                    response = event.response
                    error = event.error
                    elapsedMs = event.elapsedMs
                }
                if (event.module.isNotBlank()) module(event.module).busy = false
            }

            is CompileEvent -> {
                compileOk = event.success
                compileErrors = event.errors
            }

            is ModuleResultEvent -> module(event.module).apply {
                passed = event.passed
                total = event.total
                status = event.status
                failures = event.failures
                busy = false
            }

            is FileEvent -> {
                files.removeAll { it.path == event.path }
                files.add(event)
            }

            is LogEvent -> {
                logLines.add(event.message)
                if (logLines.size > 5000) repeat(1000) { logLines.removeAt(0) }
            }

            is RunFinishedEvent -> {
                running = false
                finishedOk = event.ok
                finishedSummary = event.summary
                modules.forEach { it.busy = false }
            }
        }
    }
}
