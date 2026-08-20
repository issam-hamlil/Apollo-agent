package com.issam.apollo.orchestrator

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Fast, in-memory tests for the graph's post-Stage-5 transition logic.
 * No LLM inference, no compilation - pure decision-table coverage.
 */
class ModernizationGraphTest {

    private val graph = ModernizationGraph()

    @Test
    fun `test effective repairs route back to the verifier`() {
        assertEquals(
            FixerRoute.VERIFY,
            graph.routeAfterFixer(repairedModules = 1, noopModules = 2, retryCount = 3, maxPasses = 10),
            "Any module whose source actually changed must be re-verified"
        )
    }

    @Test
    fun `test an all-noop repair pass skips the redundant verification instead of re-verifying`() {
        // Regression: the fixer used to report preserved-as-is modules as repairs, so the graph
        // re-ran Stage 4 over byte-identical source. That produced a guaranteed-identical error
        // count, and three such passes falsely tripped the LLM bottleneck detector.
        assertEquals(
            FixerRoute.RETRY_FIXER,
            graph.routeAfterFixer(repairedModules = 0, noopModules = 3, retryCount = 3, maxPasses = 10),
            "A pass that changed nothing must not feed a duplicate sample to the bottleneck detector"
        )
    }

    @Test
    fun `test an all-noop repair pass completes once the global pass budget is exhausted`() {
        assertEquals(
            FixerRoute.COMPLETE_NO_PROGRESS,
            graph.routeAfterFixer(repairedModules = 0, noopModules = 3, retryCount = 10, maxPasses = 10),
            "Repair retries must stay bounded by the global pass budget"
        )
    }

    @Test
    fun `test a pass with nothing to repair completes the workflow`() {
        assertEquals(
            FixerRoute.COMPLETE_EXHAUSTED,
            graph.routeAfterFixer(repairedModules = 0, noopModules = 0, retryCount = 2, maxPasses = 10),
            "Every failing module capped (or no errors at all) must finish the workflow"
        )
    }
}
