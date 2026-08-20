package com.issam.apollo.agents

import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.tools.CharacterizationTool
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VerifierAgentTest {

    @Test
    fun `test VerifierAgent compiles migrated Kotlin and executes ground-truth tests`() {
        val analyzerAgent = AnalyzerAgent()
        val characterizationTool = CharacterizationTool()
        val migratorAgent = MigratorAgent()
        val verifierAgent = VerifierAgent()

        val sampleLegacyDir = File("sample-legacy")
        assertTrue(sampleLegacyDir.exists(), "sample-legacy directory must exist")

        val state0 = GraphState(targetProjectPath = "sample-legacy")
        val state1 = analyzerAgent.execute(state0)
        val charTests = characterizationTool.executeCharacterization(sampleLegacyDir)
        val state2 = state1.copy(characterizationTests = charTests)
        val state3 = migratorAgent.execute(state2)

        var verifiedState = verifierAgent.execute(state3)

        val fixerAgent = FixerAgent()
        var retryAttempt = 0
        while ((!verifiedState.verificationResult.compiledSuccessfully || verifiedState.verificationResult.testsFailed > 0) && retryAttempt < 10) {
            val fixedState = fixerAgent.execute(verifiedState)
            verifiedState = verifierAgent.execute(fixedState)
            retryAttempt++
        }

        if (!verifiedState.verificationResult.compiledSuccessfully) {
            println("COMPILER ERRORS IN VERIFIER AGENT TEST:")
            verifiedState.verificationResult.compilerErrors.forEach { println(" - $it") }
        }
        assertTrue(verifiedState.verificationResult.compiledSuccessfully, "Migrated Kotlin code should compile successfully. Errors: ${verifiedState.verificationResult.compilerErrors}")

        // Check test execution results
        println("VERIFICATION RESULT PASSED: ${verifiedState.verificationResult.testsPassed}")
        println("VERIFICATION RESULT FAILED: ${verifiedState.verificationResult.testsFailed}")
        verifiedState.verificationResult.testFailures.forEach { println("FAIL DETAILS: $it") }
        assertEquals(0, verifiedState.verificationResult.testsFailed, "Failed test details: ${verifiedState.verificationResult.testFailures}")

        // Check per-module status updates
        assertEquals(ModuleStatus.VERIFIED, verifiedState.moduleStatuses["User"])
        assertEquals(ModuleStatus.VERIFIED, verifiedState.moduleStatuses["UserService"])
        assertEquals(ModuleStatus.VERIFIED, verifiedState.moduleStatuses["StringUtils"])

        // Check verification report files created
        val verDir = File("reports/verification")
        assertTrue(verDir.exists(), "reports/verification directory must exist")
        assertTrue(File(verDir, "verification-summary.md").exists(), "verification-summary.md must exist")
    }
}
