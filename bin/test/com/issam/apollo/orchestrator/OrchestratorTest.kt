package com.issam.apollo.orchestrator

import com.issam.apollo.state.ModuleStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OrchestratorTest {

    @Test
    fun `test ModernizationGraph pipeline executes all nodes end-to-end`() {
        val orchestrator = ModernizationGraph()
        val finalState = orchestrator.runPipeline("sample-legacy")

        // Verify stage completion
        assertTrue(finalState.verificationResult.compiledSuccessfully, "Pipeline compilation should succeed")
        assertTrue(finalState.verificationResult.testsPassed > 0, "Characterization tests should pass")
        assertEquals(0, finalState.verificationResult.testsFailed, "0 test failures expected")

        // Verify module statuses
        assertEquals(ModuleStatus.VERIFIED, finalState.moduleStatuses["StringUtils"])
        assertEquals(ModuleStatus.VERIFIED, finalState.moduleStatuses["User"])
        assertEquals(ModuleStatus.VERIFIED, finalState.moduleStatuses["UserService"])

        // Verify reports list includes reports from all stages
        val stageNames = finalState.reports.map { it.stageName }
        assertTrue(stageNames.contains("STAGE_1_ANALYZER"), "Should contain Stage 1 report")
        assertTrue(stageNames.contains("STAGE_2_CHARACTERIZATION"), "Should contain Stage 2 report")
        assertTrue(stageNames.contains("STAGE_3_MIGRATOR"), "Should contain Stage 3 report")
        assertTrue(stageNames.contains("STAGE_4_VERIFIER"), "Should contain Stage 4 report")
    }

    @Test
    fun `test ModernizationGraph resume mode skips stages 1-3 when complete specs, characterization and migrated files exist`() {
        val tempReportsDir = File.createTempFile("apollo-orchestrator-reports", "").apply {
            delete()
            mkdirs()
        }
        val tempMigratedDir = File.createTempFile("apollo-orchestrator-migrated", "").apply {
            delete()
            mkdirs()
        }

        try {
            // First run full pipeline to populate reports and migrated code
            val firstOrchestrator = ModernizationGraph(
                reportsDir = tempReportsDir,
                migratedOutputDir = tempMigratedDir
            )
            val firstState = firstOrchestrator.runPipeline("sample-legacy", isResume = false)
            ResumeManager.saveMigrationReport(firstState, tempReportsDir)

            // Now run in resume mode
            val resumeOrchestrator = ModernizationGraph(
                reportsDir = tempReportsDir,
                migratedOutputDir = tempMigratedDir
            )
            val resumedState = resumeOrchestrator.runPipeline("sample-legacy", isResume = true)

            assertTrue(resumedState.verificationResult.compiledSuccessfully, "Resumed pipeline should compile successfully")
            assertEquals(0, resumedState.verificationResult.testsFailed, "Resumed pipeline should have 0 test failures")
            assertEquals(ModuleStatus.VERIFIED, resumedState.moduleStatuses["User"])
        } finally {
            tempReportsDir.deleteRecursively()
            tempMigratedDir.deleteRecursively()
        }
    }
}
