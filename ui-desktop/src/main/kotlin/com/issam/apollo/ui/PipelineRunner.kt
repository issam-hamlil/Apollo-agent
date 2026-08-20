package com.issam.apollo.ui

import com.issam.apollo.orchestrator.ModernizationGraph
import com.issam.apollo.telemetry.ApolloTelemetry
import com.issam.apollo.telemetry.RunFinishedEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.io.PrintStream

/**
 * Runs the migration in this process so the UI sees events with no IPC, and mirrors
 * stdout/stderr into the log pane so nothing that appears in a terminal is missing here.
 */
object PipelineRunner {

    private var job: Job? = null
    private var installed = false

    /** Tees a stream so the real console keeps working while the UI also receives lines. */
    private class TeeStream(private val origin: PrintStream, private val level: String) : OutputStream() {
        private val buffer = StringBuilder()

        @Synchronized
        override fun write(b: Int) {
            origin.write(b)
            val ch = b.toChar()
            if (ch == '\n') {
                val line = buffer.toString().trimEnd('\r')
                buffer.setLength(0)
                if (line.isNotBlank()) ApolloTelemetry.log(line, level)
            } else {
                buffer.append(ch)
                if (buffer.length > 8000) {
                    ApolloTelemetry.log(buffer.toString(), level)
                    buffer.setLength(0)
                }
            }
        }

        @Synchronized
        override fun flush() = origin.flush()
    }

    @Synchronized
    fun installConsoleMirror() {
        if (installed) return
        installed = true
        System.setOut(PrintStream(TeeStream(System.out, "INFO"), true))
        System.setErr(PrintStream(TeeStream(System.err, "ERROR"), true))
    }

    fun isRunning(): Boolean = job?.isActive == true

    fun start(scope: CoroutineScope, state: RunState, projectPath: String, resume: Boolean) {
        if (isRunning()) return
        state.reset()
        state.running = true
        state.stage = "STARTING"

        job = scope.launch(Dispatchers.IO) {
            try {
                ApolloTelemetry.log("[Console] Starting migration for: $projectPath (resume=$resume)")
                ModernizationGraph().runPipeline(projectPath, isResume = resume)
            } catch (t: Throwable) {
                ApolloTelemetry.log("[Console] Run failed: ${t.message}", "ERROR")
                ApolloTelemetry.emit(
                    RunFinishedEvent(ok = false, summary = t.message ?: t.javaClass.simpleName)
                )
            } finally {
                if (state.running) {
                    ApolloTelemetry.emit(RunFinishedEvent(ok = false, summary = "Run ended"))
                }
            }
        }
    }

    /**
     * Cancels the coroutine. In-flight blocking work (an open LLM stream, a compile)
     * finishes on its own thread first - this is a request to stop, not a kill.
     */
    fun stop() {
        job?.cancel()
        job = null
    }
}
