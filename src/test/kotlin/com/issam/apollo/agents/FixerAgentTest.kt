package com.issam.apollo.agents

import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.state.VerificationResult
import kotlin.test.Test
import kotlin.test.assertEquals

class FixerAgentTest {

    @Test
    fun `test FixerAgent increments regenCounters and respects 3-attempt retry cap`() {
        val fixerAgent = FixerAgent(maxRetriesPerModule = 3)

        var state = GraphState(
            targetProjectPath = "sample-legacy",
            migratedCode = mutableMapOf("UserService.kt" to "broken code class class UserService {}"),
            moduleStatuses = mapOf("UserService" to ModuleStatus.FAILED),
            verificationResult = VerificationResult(
                compiledSuccessfully = false,
                compilerErrors = listOf("Syntax error: class class UserService")
            )
        )

        // Iteration 1
        state = fixerAgent.execute(state)
        assertEquals(1, state.regenCounters["UserService"])
        assertEquals("FIX_APPLIED", state.currentStage)

        // Iteration 2
        state = fixerAgent.execute(state)
        assertEquals(2, state.regenCounters["UserService"])

        // Iteration 3
        state = fixerAgent.execute(state)
        assertEquals(3, state.regenCounters["UserService"])

        // Iteration 4 (Cap reached at 3 attempts)
        state = fixerAgent.execute(state)
        assertEquals(3, state.regenCounters["UserService"], "Regen counter should cap at 3")
        assertEquals(ModuleStatus.FAILED, state.moduleStatuses["UserService"], "Module status should remain FAILED after cap")
    }
}
