package com.issam.apollo.orchestrator

import com.issam.apollo.agents.AnalyzerAgent
import com.issam.apollo.agents.FixerAgent
import com.issam.apollo.agents.MigratorAgent
import com.issam.apollo.agents.VerifierAgent
import com.issam.apollo.config.FatalWatchdogAbortException
import com.issam.apollo.telemetry.ApolloTelemetry
import com.issam.apollo.telemetry.RunFinishedEvent
import com.issam.apollo.telemetry.StageEvent
import com.issam.apollo.state.AgentReport
import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.state.StageStatus
import com.issam.apollo.reporting.RunReportWriter
import com.issam.apollo.tools.CharacterizationTool
import com.issam.apollo.tools.EnvironmentPreflight
import java.io.File
import java.time.Instant

/**
 * Apollo Workflow Nodes representing distinct stages in the graph pipeline.
 */
enum class PipelineNode {
    ANALYZER,
    CHARACTERIZATION,
    MIGRATOR,
    VERIFIER,
    FIXER,
    COMPLETED
}

/**
 * Stage 6 — Orchestrator (ModernizationGraph)
 *
 * Implements a stateful Workflow Graph that wires pipeline nodes:
 *   ANALYZER --> CHARACTERIZATION --> MIGRATOR --> VERIFIER --> [Success] --> COMPLETED
 *                                                       |
 *                                                   [Failure]
 *                                                       |
 *                                                       v
 *                                                    FIXER --> (retry capped)
 */
class ModernizationGraph(
    private val analyzerAgent: AnalyzerAgent = AnalyzerAgent(),
    private val characterizationTool: CharacterizationTool = CharacterizationTool(),
    private val migratorAgent: MigratorAgent = MigratorAgent(),
    private val verifierAgent: VerifierAgent = VerifierAgent(),
    private val fixerAgent: FixerAgent = FixerAgent(),
    private val reportsDir: File = File("").absoluteFile.resolve("reports"),
    private val migratedOutputDir: File = File("").absoluteFile.resolve("migrated-src")
) {

    fun runPipeline(targetProjectPath: String, isResume: Boolean = false): GraphState {
        var state: GraphState
        var currentNode: PipelineNode
        val errorCountHistory = mutableListOf<Int>()

        FixerAgent.resetErrorHistories()

        // Verify the build environment BEFORE any LLM is invoked. A missing Kotlin stdlib or
        // android.jar produces compiler errors no prompt can fix; without this gate the repair
        // loop burns its whole budget (and the user's tokens) on them.
        val preflight = EnvironmentPreflight.check(targetProjectPath)
        print(preflight.render())
        if (!preflight.ok) {
            val summary = preflight.blockers.joinToString("; ") { it.title }
            ApolloTelemetry.emit(RunFinishedEvent(ok = false, summary = "Preflight failed: $summary"))
            System.err.println(
                "[GraphEngine] [FATAL] Halting before Stage 1: the generated Kotlin could not " +
                    "compile in this environment regardless of what the LLM produces."
            )
            return finishRun(
                GraphState(
                    targetProjectPath = targetProjectPath,
                    currentStage = "ENVIRONMENT_PREFLIGHT_FAILED"
                )
            )
        }

        println("\n+-------------------------------------------------------------+")
        println("|    Apollo Agent - Modernization Graph Engine Starting       |")
        println("+-------------------------------------------------------------+\n")

        if (isResume) {
            println("[Resume] Checking if project has previous modernization run for: $targetProjectPath")
            val resumeResult = ResumeManager.loadResumeState(
                targetProjectPath = targetProjectPath,
                reportsDir = reportsDir,
                migratedOutputDir = migratedOutputDir,
                maxRetriesPerModule = fixerAgent.maxRetriesPerModule
            )

            if (resumeResult != null && resumeResult.canSkipAnalyzerAndCharacterization) {
                state = resumeResult.state
                if (resumeResult.canSkipMigrator) {
                    println("[Resume] All modules have verified migrated Kotlin source. Transitioning directly to VERIFIER.")
                    currentNode = PipelineNode.VERIFIER
                } else {
                    println("[Resume] Verified Stage 1/2 artifacts. Transitioning to MIGRATOR for missing modules.")
                    currentNode = PipelineNode.MIGRATOR
                }
            } else {
                println("[Resume] Previous run artifacts missing, corrupt, or first-time run. Starting standard process from ANALYZER (Stage 1).")
                state = GraphState(targetProjectPath = targetProjectPath)
                currentNode = PipelineNode.ANALYZER
            }
        } else {
            println("[Pipeline] First-time / clean run requested. Starting standard process from ANALYZER (Stage 1).")
            state = GraphState(targetProjectPath = targetProjectPath)
            currentNode = PipelineNode.ANALYZER
        }

        while (currentNode != PipelineNode.COMPLETED) {
            println("[GraphEngine] Transitioning to Node: $currentNode")
            ApolloTelemetry.emit(StageEvent(currentNode.name))

            try {
                state = when (currentNode) {
                    PipelineNode.ANALYZER -> {
                        val updated = analyzerAgent.execute(state)
                        currentNode = PipelineNode.CHARACTERIZATION
                        updated
                    }

                    PipelineNode.CHARACTERIZATION -> {
                        println("[Stage 2] Running Characterization Ground-Truth Capture...")
                        val charTests = characterizationTool.executeCharacterization(File(targetProjectPath), reportsDir = reportsDir)
                        val report = AgentReport(
                            stageName = "STAGE_2_CHARACTERIZATION",
                            status = StageStatus.COMPLETED,
                            timestamp = Instant.now().toString(),
                            details = "Captured ${charTests.size} ground-truth test cases across legacy Java modules.",
                            metrics = mapOf("totalTestCases" to charTests.size.toString())
                        )
                        val updatedReports = state.reports.toMutableList().apply { add(report) }
                        currentNode = PipelineNode.MIGRATOR
                        state.copy(
                            characterizationTests = charTests,
                            currentStage = "CHARACTERIZATION_COMPLETED",
                            reports = updatedReports
                        )
                    }

                    PipelineNode.MIGRATOR -> {
                        val updated = migratorAgent.execute(state)
                        currentNode = PipelineNode.VERIFIER
                        updated
                    }

                    PipelineNode.VERIFIER -> {
                        val updated = verifierAgent.execute(state)

                        // Transition Decision Logic
                        val vRes = updated.verificationResult
                        val allPassed = vRes.compiledSuccessfully && vRes.testsFailed == 0
                        val currentErrorCount = vRes.compilerErrors.size + vRes.testsFailed

                        if (allPassed) {
                            println("[GraphEngine] Node VERIFIER succeeded! All modules compiled & verified.")
                            currentNode = PipelineNode.COMPLETED
                        } else {
                            // -- Environment guard --------------------------------------------
                            // If the build broke for classpath reasons, no prompt can repair it.
                            // Say so plainly instead of spending the retry budget on it.
                            val (envErrors, codeErrors) = EnvironmentPreflight.partition(vRes.compilerErrors)
                            if (envErrors.isNotEmpty() && codeErrors.isEmpty()) {
                                val msg = "[ENVIRONMENT] Compilation failed on ${envErrors.size} build-environment " +
                                    "error(s) that no LLM can fix (missing Kotlin stdlib, android.jar, or " +
                                    "dependency jars). First: ${envErrors.first().trim()}"
                                System.err.println("==========================================================================")
                                System.err.println(msg)
                                System.err.println("==========================================================================")
                                throw FatalWatchdogAbortException(msg)
                            }

                            // -- LLM Bottleneck Detection: Halt if same error count repeats 3 times consecutively --
                            if (currentErrorCount > 0) {
                                errorCountHistory.add(currentErrorCount)
                                if (errorCountHistory.size >= 3) {
                                    val last3 = errorCountHistory.takeLast(3)
                                    if (last3.all { it == currentErrorCount }) {
                                        val bottleneckMsg = "[BOTTLENECK] The exact same number of errors ($currentErrorCount) was identified for 3 consecutive verification passes in a row without progress. Halting execution to prevent infinite LLM loops (bottleneck in the LLMs flagged)."
                                        System.err.println("==========================================================================")
                                        System.err.println(bottleneckMsg)
                                        System.err.println("==========================================================================")
                                        throw FatalWatchdogAbortException(bottleneckMsg)
                                    }
                                }
                            }

                            val maxCap = fixerAgent.maxRetriesPerModule
                            val failingModules = updated.moduleSpecs.keys.filter { className ->
                                FixerAgent.hasDirectErrorsOrFailures(className, vRes)
                            }

                            val allFailingCapped = failingModules.isNotEmpty() && failingModules.all { className ->
                                (updated.regenCounters[className] ?: 0) >= maxCap
                            }

                            val totalRetryExceeded = updated.retryCount >= maxCap

                            if (allFailingCapped || totalRetryExceeded || (failingModules.isEmpty() && !vRes.compiledSuccessfully && updated.retryCount > 0)) {
                                println("[GraphEngine] Node VERIFIER: Max retry cap ($maxCap attempts) reached. Finishing workflow.")
                                currentNode = PipelineNode.COMPLETED
                            } else {
                                println("[GraphEngine] Node VERIFIER: Failure detected (${vRes.compilerErrors.size} compile errors across ${failingModules.size} module(s), ${vRes.testsFailed} test failures). Transitioning to FIXER (Retry #${updated.retryCount + 1}/$maxCap).")
                                currentNode = PipelineNode.FIXER
                            }
                        }
                        updated
                    }

                    PipelineNode.FIXER -> {
                        val updated = fixerAgent.execute(state)
                        val fixerMetrics = updated.reports.lastOrNull()?.metrics
                        val fixedCount = fixerMetrics?.get("repairedModules")?.toIntOrNull() ?: 0
                        val noopCount = fixerMetrics?.get("noopModules")?.toIntOrNull() ?: 0

                        currentNode = when (routeAfterFixer(fixedCount, noopCount, updated.retryCount, fixerAgent.maxRetriesPerModule)) {
                            FixerRoute.VERIFY -> PipelineNode.VERIFIER

                            FixerRoute.RETRY_FIXER -> {
                                println("[GraphEngine] FixerAgent produced no source changes ($noopCount module(s) byte-identical). Skipping redundant verification pass and retrying repair (Pass #${updated.retryCount + 1}/${fixerAgent.maxRetriesPerModule}).")
                                PipelineNode.FIXER
                            }

                            FixerRoute.COMPLETE_NO_PROGRESS -> {
                                println("[GraphEngine] FixerAgent produced no source changes and the global pass budget (${fixerAgent.maxRetriesPerModule}) is exhausted. Completing workflow. Re-run with --resume once the LLM provider is healthy.")
                                PipelineNode.COMPLETED
                            }

                            FixerRoute.COMPLETE_EXHAUSTED -> {
                                println("[GraphEngine] FixerAgent applied 0 repairs (all active error modules capped or no errors detected). Completing workflow.")
                                PipelineNode.COMPLETED
                            }
                        }
                        updated
                    }

                    PipelineNode.COMPLETED -> state
                }
            } catch (e: FatalWatchdogAbortException) {
                System.err.println("\n[GraphEngine] [FATAL] PIPELINE HALTED: ${e.message}")
                ApolloTelemetry.emit(RunFinishedEvent(ok = false, summary = e.message ?: "Pipeline halted"))
                val report = AgentReport(
                    stageName = "STAGE_FATAL_WATCHDOG",
                    status = StageStatus.FAILED,
                    timestamp = Instant.now().toString(),
                    details = e.message ?: "Watchdog aborted a module 3 times.",
                    metrics = mapOf("fatalWatchdogAbort" to "true")
                )
                val updatedReports = state.reports.toMutableList().apply { add(report) }
                return finishRun(
                    state.copy(
                        currentStage = "FATAL_WATCHDOG_ABORT",
                        reports = updatedReports
                    )
                )
            }
        }

        ApolloTelemetry.emit(RunFinishedEvent(ok = true, summary = "Pipeline completed"))
        println("\n+-------------------------------------------------------------+")
        println("|    Apollo Agent - Modernization Graph Execution Finished    |")
        println("+-------------------------------------------------------------+\n")

        return finishRun(state)
    }

    /**
     * Writes the run's log plus its success/failure report.
     *
     * This lives in the pipeline rather than in Main.kt so EVERY caller gets reports.
     * When it sat in the CLI entry point, runs started from the desktop console finished
     * without producing any report at all.
     */
    private fun finishRun(state: GraphState): GraphState {
        return try {
            // Machine-readable state envelope: this is what --resume reads back.
            ResumeManager.saveMigrationReport(state, reportsDir)
            val written = RunReportWriter.write(state, reportsDir)
            val kind = if (written.succeeded) "SUCCESS" else "FAILURE"
            println("[Reports] $kind report: ${written.outcome.absolutePath}")
            println("[Reports] Run log:        ${written.log.absolutePath}")
            state
        } catch (e: Exception) {
            System.err.println("[Reports] Could not write reports: ${e.message}")
            state
        }
    }

    /**
     * Decides where the graph goes after a Stage 5 repair pass.
     *
     * The critical case is [repairedModules] == 0 with [noopModules] > 0: every patch attempt
     * left its module byte-identical (LLM provider outage, unmatched SEARCH block, or a no-op
     * patch). Re-running Stage 4 over unchanged source is guaranteed to reproduce the previous
     * error count, and that duplicate sample is what falsely trips the 3-pass bottleneck
     * detector. Retry the repair instead so tier escalation still gets its chance.
     */
    internal fun routeAfterFixer(
        repairedModules: Int,
        noopModules: Int,
        retryCount: Int,
        maxPasses: Int
    ): FixerRoute = when {
        repairedModules > 0 -> FixerRoute.VERIFY
        noopModules > 0 && retryCount < maxPasses -> FixerRoute.RETRY_FIXER
        noopModules > 0 -> FixerRoute.COMPLETE_NO_PROGRESS
        else -> FixerRoute.COMPLETE_EXHAUSTED
    }
}

/** Outcome of a Stage 5 repair pass, as consumed by the graph's transition logic. */
internal enum class FixerRoute {
    /** At least one module's source actually changed - re-verify. */
    VERIFY,

    /** Nothing changed; skip the provably redundant verification pass and repair again. */
    RETRY_FIXER,

    /** Nothing changed and the global pass budget is spent. */
    COMPLETE_NO_PROGRESS,

    /** Nothing to repair - every failing module is capped, or there were no errors. */
    COMPLETE_EXHAUSTED
}
