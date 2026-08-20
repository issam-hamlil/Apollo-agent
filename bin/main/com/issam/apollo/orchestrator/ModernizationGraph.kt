package com.issam.apollo.orchestrator

import com.issam.apollo.agents.AnalyzerAgent
import com.issam.apollo.agents.FixerAgent
import com.issam.apollo.agents.MigratorAgent
import com.issam.apollo.agents.VerifierAgent
import com.issam.apollo.config.FatalWatchdogAbortException
import com.issam.apollo.state.AgentReport
import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.state.StageStatus
import com.issam.apollo.tools.CharacterizationTool
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
                        val fixedCount = updated.reports.lastOrNull()?.metrics?.get("repairedModules")?.toIntOrNull() ?: 0

                        if (fixedCount == 0) {
                            println("[GraphEngine] FixerAgent applied 0 repairs (all active error modules capped or no errors detected). Completing workflow.")
                            currentNode = PipelineNode.COMPLETED
                        } else {
                            // Loop back to VERIFIER to re-evaluate patches
                            currentNode = PipelineNode.VERIFIER
                        }
                        updated
                    }

                    PipelineNode.COMPLETED -> state
                }
            } catch (e: FatalWatchdogAbortException) {
                System.err.println("\n[GraphEngine] [FATAL] PIPELINE HALTED: ${e.message}")
                val report = AgentReport(
                    stageName = "STAGE_FATAL_WATCHDOG",
                    status = StageStatus.FAILED,
                    timestamp = Instant.now().toString(),
                    details = e.message ?: "Watchdog aborted a module 3 times.",
                    metrics = mapOf("fatalWatchdogAbort" to "true")
                )
                val updatedReports = state.reports.toMutableList().apply { add(report) }
                return state.copy(
                    currentStage = "FATAL_WATCHDOG_ABORT",
                    reports = updatedReports
                )
            }
        }

        println("\n+-------------------------------------------------------------+")
        println("|    Apollo Agent - Modernization Graph Execution Finished    |")
        println("+-------------------------------------------------------------+\n")

        return state
    }
}
