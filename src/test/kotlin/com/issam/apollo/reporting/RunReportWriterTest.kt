package com.issam.apollo.reporting

import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleSpec
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.state.VerificationResult
import com.issam.apollo.telemetry.ApolloTelemetry
import com.issam.apollo.telemetry.LlmCallEvent
import com.issam.apollo.telemetry.LlmResultEvent
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RunReportWriterTest {

    private lateinit var reportsDir: File

    @BeforeTest
    fun setUp() {
        ApolloTelemetry.resetRecordedExchanges()
        reportsDir = File.createTempFile("apollo-reports", "").apply { delete(); mkdirs() }
    }

    @AfterTest
    fun tearDown() {
        ApolloTelemetry.resetRecordedExchanges()
        reportsDir.deleteRecursively()
    }

    private fun verifiedState() = GraphState(
        targetProjectPath = "sample-legacy",
        currentStage = "COMPLETED",
        moduleSpecs = mapOf(
            "Task" to ModuleSpec(className = "Task", packageName = "com.demo", sourceFilePath = "src/Task.java")
        ),
        migratedCode = mutableMapOf("Task.kt" to "package com.demo\n\nclass Task"),
        moduleStatuses = mapOf("Task" to ModuleStatus.VERIFIED),
        verificationResult = VerificationResult(compiledSuccessfully = true, testsPassed = 9, testsFailed = 0)
    )

    private fun failedState() = GraphState(
        targetProjectPath = "sample-legacy",
        currentStage = "FATAL_WATCHDOG_ABORT",
        moduleSpecs = mapOf(
            "Good" to ModuleSpec(className = "Good", packageName = "com.demo"),
            "Broken" to ModuleSpec(className = "Broken", packageName = "com.demo")
        ),
        migratedCode = mutableMapOf("Good.kt" to "ok", "Broken.kt" to "bad"),
        moduleStatuses = mapOf("Good" to ModuleStatus.VERIFIED, "Broken" to ModuleStatus.FAILED),
        regenCounters = mapOf("Broken" to 4),
        verificationResult = VerificationResult(
            compiledSuccessfully = false,
            compilerErrors = listOf(
                "warning: unable to find kotlin-stdlib.jar in the Kotlin home directory",
                "Broken.kt:12:5: error: unresolved reference 'nope'"
            ),
            testsPassed = 3,
            testsFailed = 2,
            testFailures = listOf("Broken: Test TC-1 (run): expected 'a', got 'b'")
        )
    )

    @Test
    fun `test a successful run writes into reports success and never into failed`() {
        val written = RunReportWriter.write(verifiedState(), reportsDir)

        assertTrue(written.succeeded, "all modules verified means success")
        assertTrue(written.outcome.exists())
        assertEquals("success", written.outcome.parentFile.name, "success reports belong in reports/success")
        assertEquals("logs", written.log.parentFile.name, "the timeline belongs in reports/logs")
        assertFalse(File(reportsDir, "failed").exists(), "no failure report should be produced")

        val body = written.outcome.readText()
        assertTrue(body.contains("Migration Succeeded"))
        assertTrue(body.contains("Dependencies and libraries"), "success report must describe dependencies")
        assertTrue(body.contains("Modules delivered"))
        assertTrue(body.contains("Task"))
    }

    @Test
    fun `test a failed run reports which modules failed, the errors, and nothing else`() {
        val written = RunReportWriter.write(failedState(), reportsDir)

        assertFalse(written.succeeded)
        assertEquals("failed", written.outcome.parentFile.name, "failure reports belong in reports/failed")
        assertFalse(File(reportsDir, "success").exists(), "no success report should be produced")

        val body = written.outcome.readText()
        assertTrue(body.contains("Modules that succeeded (1)"), "must list what did work")
        assertTrue(body.contains("Modules that failed (1)"), "must list what did not")
        assertTrue(body.contains("Good"))
        assertTrue(body.contains("Broken"))
        assertTrue(body.contains("unresolved reference 'nope'"), "the actual compiler error must appear")
        assertTrue(body.contains("expected 'a', got 'b'"), "behaviour mismatches must appear")
        // Warnings are excluded from the headline count - they never block a build.
        assertTrue(body.contains("| Compile errors | `1` |"), "warnings must not inflate the error count")
    }

    @Test
    fun `test the failure report carries prompts for failed modules only`() {
        // Two modules talked to the LLM; only one of them fails.
        listOf("Broken" to 1L, "Good" to 2L).forEach { (module, id) ->
            ApolloTelemetry.emit(
                LlmCallEvent(
                    callId = id, module = module, provider = "GROQ", model = "test-model",
                    tier = "cloud", attempt = 1,
                    systemPrompt = "SYSTEM-FOR-$module",
                    userPrompt = "USER-FOR-$module"
                )
            )
            ApolloTelemetry.emit(
                LlmResultEvent(callId = id, module = module, ok = true, response = "REPLY-FOR-$module")
            )
        }

        val body = RunReportWriter.write(failedState(), reportsDir).outcome.readText()

        assertTrue(body.contains("SYSTEM-FOR-Broken"), "the failed module's system prompt must be included")
        assertTrue(body.contains("USER-FOR-Broken"), "the failed module's user prompt must be included")
        assertTrue(body.contains("REPLY-FOR-Broken"), "the model's reply must be included")

        assertFalse(
            body.contains("SYSTEM-FOR-Good"),
            "prompts for modules that succeeded must NOT be in the failure report"
        )
        assertFalse(body.contains("USER-FOR-Good"))
    }

    @Test
    fun `test the log records the timeline for both outcomes`() {
        val log = RunReportWriter.write(failedState(), reportsDir).log.readText()
        assertTrue(log.contains("Apollo Run Log"))
        assertTrue(log.contains("Timeline"))
        assertTrue(log.contains("LLM traffic"), "the log is where full LLM traffic is summarised")
    }

    @Test
    fun `test a run that compiled but still has failing tests is not called a success`() {
        val partial = verifiedState().copy(
            moduleStatuses = mapOf("Task" to ModuleStatus.VERIFIED, "Other" to ModuleStatus.FAILED),
            verificationResult = VerificationResult(compiledSuccessfully = true, testsPassed = 5, testsFailed = 3)
        )
        assertFalse(RunReportWriter.isSuccessful(partial), "a failing module must never yield a success report")
    }
}
