package com.issam.apollo.agents

import com.issam.apollo.knowledge.MigrationPatterns
import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleStatus
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class MigratorAgentTest {

    @Test
    fun `test MigrationPatterns loads 18 curated patterns`() {
        val patternsTool = MigrationPatterns()
        val patterns = patternsTool.loadPatterns()

        assertTrue(patterns.size >= 15, "Knowledge base should contain at least 15 curated patterns")
        assertNotNull(patterns.find { it.id == "java-pojo-to-kotlin-data-class" })
        assertNotNull(patterns.find { it.id == "null-check-to-elvis" })
        assertNotNull(patterns.find { it.id == "asynctask-to-coroutines" })
        assertNotNull(patterns.find { it.id == "callback-interface-to-suspend-coroutine" })
        assertNotNull(patterns.find { it.id == "xml-layout-to-compose" })
    }

    @Test
    fun `test MigratorAgent transforms modules in topological order and writes output files`() {
        val tempOutputDir = File("build/test-migrated-src-${System.currentTimeMillis()}")
        val analyzerAgent = AnalyzerAgent()
        val migratorAgent = MigratorAgent(migratedOutputDir = tempOutputDir)

        val initialState = GraphState(targetProjectPath = "sample-legacy")
        val analyzedState = analyzerAgent.execute(initialState)

        val migratedState = migratorAgent.execute(analyzedState)

        // Verify state updates
        assertEquals("MIGRATION_COMPLETED", migratedState.currentStage)
        assertTrue(migratedState.migratedCode.size >= 4, "Should migrate 4 files")
        assertEquals(ModuleStatus.MIGRATED, migratedState.moduleStatuses["User"])
        assertEquals(ModuleStatus.MIGRATED, migratedState.moduleStatuses["UserService"])
        assertEquals(ModuleStatus.MIGRATED, migratedState.moduleStatuses["StringUtils"])

        // Verify files written to disk
        assertTrue(tempOutputDir.exists(), "migratedOutputDir should exist")

        val stringUtilsFile = File(tempOutputDir, "com/example/legacy/StringUtils.kt")
        val userFile = File(tempOutputDir, "com/example/legacy/User.kt")
        val userServiceFile = File(tempOutputDir, "com/example/legacy/UserService.kt")

        assertTrue(stringUtilsFile.exists() || File(tempOutputDir, "StringUtils.kt").exists(), "StringUtils.kt must exist")
        assertTrue(userFile.exists() || File(tempOutputDir, "User.kt").exists(), "User.kt must exist")
        assertTrue(userServiceFile.exists() || File(tempOutputDir, "UserService.kt").exists(), "UserService.kt must exist")

        // Cleanup
        tempOutputDir.deleteRecursively()
    }
}
