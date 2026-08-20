package com.issam.apollo.orchestrator

import com.issam.apollo.state.AgentReport
import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleSpec
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.state.StageStatus
import com.issam.apollo.state.TestCase
import com.issam.apollo.state.VerificationResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ResumeManagerTest {

    private val json = Json { prettyPrint = true }

    @Test
    fun `test saveMigrationReport and findLatestReportForProject`() {
        val tempReportsDir = File.createTempFile("apollo-reports-test", "").apply {
            delete()
            mkdirs()
        }

        try {
            val state = GraphState(
                targetProjectPath = "sample-legacy",
                moduleStatuses = mapOf("User" to ModuleStatus.VERIFIED, "UserService" to ModuleStatus.FAILED),
                regenCounters = mapOf("User" to 0, "UserService" to 3),
                retryCount = 3,
                currentStage = "VERIFICATION_FAILED",
                verificationResult = VerificationResult(compiledSuccessfully = false, testsPassed = 5, testsFailed = 1),
                reports = mutableListOf(
                    AgentReport(
                        stageName = "STAGE_1_ANALYZER",
                        status = StageStatus.COMPLETED,
                        timestamp = "2026-08-17T10:00:00Z",
                        details = "Parsed 2 Java files."
                    )
                )
            )

            val reportFile = ResumeManager.saveMigrationReport(state, tempReportsDir)
            assertTrue(reportFile.exists(), "Report file should be created")
            assertTrue(reportFile.name.startsWith("migration-report-"), "Report file should start with migration-report-")

            val summaryFile = File(tempReportsDir, "migration-summary.md")
            assertTrue(summaryFile.exists(), "Summary markdown should be created")

            val foundReport = ResumeManager.findLatestReportForProject("sample-legacy", tempReportsDir)
            assertNotNull(foundReport, "Should find the latest report file")
            assertEquals(reportFile.name, foundReport.name)
        } finally {
            tempReportsDir.deleteRecursively()
        }
    }

    @Test
    fun `test loadResumeState loads specs, characterization, and existing migrated code`() {
        val tempReportsDir = File.createTempFile("apollo-reports-test2", "").apply {
            delete()
            mkdirs()
        }
        val tempMigratedDir = File.createTempFile("apollo-migrated-test2", "").apply {
            delete()
            mkdirs()
        }

        try {
            // 1. Create specs
            val specsDir = File(tempReportsDir, "specs")
            val userSpecDir = File(specsDir, "User").apply { mkdirs() }
            val userServiceSpecDir = File(specsDir, "UserService").apply { mkdirs() }

            val userSpec = ModuleSpec(
                className = "User",
                packageName = "com.example.legacy",
                fields = listOf("String id", "String username"),
                methods = listOf("getId()", "getUsername()"),
                sourceFilePath = "sample-legacy/src/main/java/com/example/legacy/User.java"
            )
            val userServiceSpec = ModuleSpec(
                className = "UserService",
                packageName = "com.example.legacy",
                fields = listOf("List<User> userList"),
                methods = listOf("addUser(User)"),
                dependsOn = listOf("User"),
                sourceFilePath = "sample-legacy/src/main/java/com/example/legacy/UserService.java"
            )

            File(userSpecDir, "User-spec.json").writeText(json.encodeToString(userSpec))
            File(userServiceSpecDir, "UserService-spec.json").writeText(json.encodeToString(userServiceSpec))

            // 2. Create topo order
            File(tempReportsDir, "topo-order.md").writeText("""
                ## Topologically-Sorted Migration Order
                1. `User`
                2. `UserService`

                ## Dependency Graph
                | Module | Depends On |
                | `User` | *(none)* |
                | `UserService` | `User` |
            """.trimIndent())

            // 3. Create characterization ground truth
            val charDir = File(tempReportsDir, "characterization").apply { mkdirs() }
            val userTests = listOf(
                TestCase("TC-1", "User", "getId", emptyList(), "null")
            )
            val userServiceTests = listOf(
                TestCase("TC-2", "UserService", "addUser", listOf("user"), "Unit")
            )
            File(charDir, "User-ground-truth.json").writeText(json.encodeToString(userTests))
            File(charDir, "UserService-ground-truth.json").writeText(json.encodeToString(userServiceTests))

            // 4. Create existing migrated Kotlin files
            val userKt = File(tempMigratedDir, "com/example/legacy/User.kt").apply { parentFile.mkdirs() }
            userKt.writeText("package com.example.legacy\n\ndata class User(val id: String?, val username: String?)")

            val userServiceKt = File(tempMigratedDir, "com/example/legacy/UserService.kt").apply { parentFile.mkdirs() }
            userServiceKt.writeText("package com.example.legacy\n\nclass UserService {\n    val userList = mutableListOf<User>()\n}")

            // 5. Save previous report envelope
            val envelope = MigrationReportEnvelope(
                targetProjectPath = "sample-legacy",
                timestamp = "2026-08-17T12:00:00Z",
                currentStage = "VERIFICATION_FAILED",
                compilationSuccess = false,
                moduleStatuses = mapOf("User" to ModuleStatus.VERIFIED, "UserService" to ModuleStatus.FAILED),
                regenCounters = mapOf("User" to 0, "UserService" to 3),
                retryCount = 3,
                reports = emptyList()
            )
            File(tempReportsDir, "migration-report-100.json").writeText(json.encodeToString(envelope))

            // Load resume state
            val resumeResult = ResumeManager.loadResumeState(
                targetProjectPath = "sample-legacy",
                reportsDir = tempReportsDir,
                migratedOutputDir = tempMigratedDir,
                maxRetriesPerModule = 5
            )

            assertNotNull(resumeResult, "ResumeResult should not be null")
            assertTrue(resumeResult.canSkipAnalyzerAndCharacterization, "Should skip Analyzer and Characterization")
            assertTrue(resumeResult.canSkipMigrator, "Should skip Migrator since all files exist")

            val state = resumeResult.state
            assertEquals(listOf("User", "UserService"), state.topologicalOrder)
            assertEquals(ModuleStatus.VERIFIED, state.moduleStatuses["User"])
            assertEquals(ModuleStatus.FAILED, state.moduleStatuses["UserService"])
            assertEquals(0, state.regenCounters["UserService"])
            assertEquals(2, state.characterizationTests.size)
            assertTrue(state.migratedCode.containsKey("User.kt"))
            assertTrue(state.migratedCode.containsKey("UserService.kt"))

            // Verify resume messages contain clear logging
            val messages = resumeResult.resumeMessages.joinToString("\n")
            assertTrue(messages.contains("Verified Stage 1 (Specs) & Stage 2 (Characterization) artifacts for 2/2 modules"))
            assertTrue(messages.contains("UserService (status=FAILED, prior attempts=3): Resetting retry counter to 0 on resume"))
            assertTrue(messages.contains("User: verified existing migrated-src, status=VERIFIED, regenCounters=0 — skipping migration"))
        } finally {
            tempReportsDir.deleteRecursively()
            tempMigratedDir.deleteRecursively()
        }
    }

    @Test
    fun `test loadResumeState resets regenCounters on resume for fresh attempts`() {
        val tempReportsDir = File.createTempFile("apollo-reports-test3", "").apply {
            delete()
            mkdirs()
        }
        val tempMigratedDir = File.createTempFile("apollo-migrated-test3", "").apply {
            delete()
            mkdirs()
        }

        try {
            val specsDir = File(tempReportsDir, "specs")
            val taskSpecDir = File(specsDir, "Task").apply { mkdirs() }
            val taskSpec = ModuleSpec(
                className = "Task",
                packageName = "com.example",
                sourceFilePath = "sample-legacy/Task.java"
            )
            File(taskSpecDir, "Task-spec.json").writeText(json.encodeToString(taskSpec))

            File(tempReportsDir, "topo-order.md").writeText("## Topologically-Sorted Migration Order\n1. `Task`\n")

            val charDir = File(tempReportsDir, "characterization").apply { mkdirs() }
            File(charDir, "Task-ground-truth.json").writeText(json.encodeToString(listOf(TestCase("TC-1", "Task", "getId", emptyList(), "0"))))

            val taskKt = File(tempMigratedDir, "Task.kt")
            taskKt.writeText("class Task")

            val envelope = MigrationReportEnvelope(
                targetProjectPath = "sample-legacy",
                timestamp = "2026-08-17T12:00:00Z",
                currentStage = "VERIFICATION_FAILED",
                compilationSuccess = false,
                moduleStatuses = mapOf("Task" to ModuleStatus.FAILED),
                regenCounters = mapOf("Task" to 5),
                retryCount = 5,
                reports = emptyList()
            )
            File(tempReportsDir, "migration-report-200.json").writeText(json.encodeToString(envelope))

            val resumeResult = ResumeManager.loadResumeState(
                targetProjectPath = "sample-legacy",
                reportsDir = tempReportsDir,
                migratedOutputDir = tempMigratedDir,
                maxRetriesPerModule = 5
            )

            assertNotNull(resumeResult)
            val messages = resumeResult.resumeMessages.joinToString("\n")
            assertTrue(messages.contains("Task (status=FAILED, prior attempts=5): Resetting retry counter to 0 on resume"))
            assertEquals(0, resumeResult.state.regenCounters["Task"])
        } finally {
            tempReportsDir.deleteRecursively()
            tempMigratedDir.deleteRecursively()
        }
    }
}
