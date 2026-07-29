package com.issam.apollo.orchestrator

import com.issam.apollo.agents.AnalyzerAgent
import com.issam.apollo.agents.FixerAgent
import com.issam.apollo.agents.MigratorAgent
import com.issam.apollo.agents.VerifierAgent
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
 *   ANALYZER ──> CHARACTERIZATION ──> MIGRATOR ──> VERIFIER ──> [Success] ──> COMPLETED
 *                                                       │
 *                                                   [Failure]
 *                                                       │
 *                                                       ▼
 *                                                    FIXER ──> (retry capped)
 */
class ModernizationGraph(
    private val analyzerAgent: AnalyzerAgent = AnalyzerAgent(),
    private val characterizationTool: CharacterizationTool = CharacterizationTool(),
    private val migratorAgent: MigratorAgent = MigratorAgent(),
    private val verifierAgent: VerifierAgent = VerifierAgent(),
    private val fixerAgent: FixerAgent = FixerAgent()
) {

    fun runPipeline(targetProjectPath: String): GraphState {
        var state = GraphState(targetProjectPath = targetProjectPath)
        var currentNode = PipelineNode.ANALYZER

        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│    Apollo Agent — Modernization Graph Engine Starting       │")
        println("└─────────────────────────────────────────────────────────────┘\n")

        while (currentNode != PipelineNode.COMPLETED) {
            println("[GraphEngine] Transitioning to Node: $currentNode")

            state = when (currentNode) {
                PipelineNode.ANALYZER -> {
                    val updated = analyzerAgent.execute(state)
                    currentNode = PipelineNode.CHARACTERIZATION
                    updated
                }

                PipelineNode.CHARACTERIZATION -> {
                    println("[Stage 2] Running Characterization Ground-Truth Capture...")
                    val charTests = characterizationTool.executeCharacterization(File(targetProjectPath))
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

                    if (allPassed) {
                        println("[GraphEngine] Node VERIFIER succeeded! All modules compiled & verified.")
                        currentNode = PipelineNode.COMPLETED
                    } else {
                        val unverifiedModules = updated.moduleStatuses.filter { it.value != ModuleStatus.VERIFIED }
                        val allCapped = unverifiedModules.isNotEmpty() && unverifiedModules.keys.all { className ->
                            (updated.regenCounters[className] ?: 0) >= 3
                        }

                        if (allCapped) {
                            println("[GraphEngine] Node VERIFIER: All failing modules reached max retry cap (3 attempts). Finishing workflow.")
                            currentNode = PipelineNode.COMPLETED
                        } else {
                            println("[GraphEngine] Node VERIFIER: Failure detected (${vRes.compilerErrors.size} compile errors, ${vRes.testsFailed} test failures). Transitioning to FIXER.")
                            currentNode = PipelineNode.FIXER
                        }
                    }
                    updated
                }

                PipelineNode.FIXER -> {
                    val updated = fixerAgent.execute(state)
                    // Loop back to VERIFIER to re-evaluate patches
                    currentNode = PipelineNode.VERIFIER
                    updated
                }

                PipelineNode.COMPLETED -> state
            }
        }

        println("\n┌─────────────────────────────────────────────────────────────┐")
        println("│    Apollo Agent — Modernization Graph Execution Finished    │")
        println("└─────────────────────────────────────────────────────────────┘\n")

        return state
    }
}
