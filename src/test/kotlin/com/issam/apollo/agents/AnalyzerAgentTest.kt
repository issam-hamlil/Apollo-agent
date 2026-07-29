package com.issam.apollo.agents

import com.issam.apollo.state.GraphState
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnalyzerAgentTest {

    @Test
    fun `test AnalyzerAgent execution updates GraphState and writes report files`() {
        val tempReportsDir = File("build/test-reports-${System.currentTimeMillis()}")
        val agent = AnalyzerAgent(reportsDir = tempReportsDir)

        val initialState = GraphState(targetProjectPath = "sample-legacy")
        val updatedState = agent.execute(initialState)

        // Verify GraphState updates
        assertEquals("ANALYSIS_COMPLETED", updatedState.currentStage)
        assertTrue(updatedState.topologicalOrder.size >= 4)
        assertTrue(updatedState.moduleSpecs.size >= 4)

        // Verify topological order constraint
        val userIdx = updatedState.topologicalOrder.indexOf("User")
        val serviceIdx = updatedState.topologicalOrder.indexOf("UserService")
        assertTrue(userIdx < serviceIdx, "User must precede UserService in updatedState.topologicalOrder")

        // Verify per-module reports written
        val specsDir = File(tempReportsDir, "specs")
        assertTrue(specsDir.exists(), "specs directory should be created")

        val userSpecMd = File(specsDir, "User/User-spec.md")
        val userSpecJson = File(specsDir, "User/User-spec.json")
        assertTrue(userSpecMd.exists(), "User-spec.md must exist")
        assertTrue(userSpecJson.exists(), "User-spec.json must exist")

        val topoReport = File(tempReportsDir, "topo-order.md")
        assertTrue(topoReport.exists(), "topo-order.md report must exist")

        // Clean up test reports dir
        tempReportsDir.deleteRecursively()
    }
}
