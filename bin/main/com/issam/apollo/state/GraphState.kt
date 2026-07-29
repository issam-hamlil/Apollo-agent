package com.issam.apollo.state

import kotlinx.serialization.Serializable

// ─── Stage / Module Status ────────────────────────────────────────────────────

@Serializable
enum class StageStatus {
    NOT_STARTED,
    IN_PROGRESS,
    COMPLETED,
    FAILED
}

/**
 * Per-module lifecycle state used by all agents.
 *
 * PENDING   → discovered, not yet touched
 * ANALYZED  → AST extracted + LLM spec written
 * MIGRATED  → Kotlin source generated
 * VERIFIED  → compiled & tests passed
 * FAILED    → last operation failed (see agent report for reason)
 */
@Serializable
enum class ModuleStatus {
    PENDING,
    ANALYZED,
    MIGRATED,
    VERIFIED,
    FAILED
}

// ─── Structured module specification (produced by AnalyzerAgent) ──────────────

@Serializable
data class ModuleSpec(
    /** Simple class name, e.g. "UserService" */
    val className: String,
    val packageName: String,
    val imports: List<String> = emptyList(),
    val fields: List<String> = emptyList(),
    val methods: List<String> = emptyList(),
    /** Classes/modules this module depends on (within the same repo) */
    val dependsOn: List<String> = emptyList(),
    /** LLM-generated business-logic summary */
    val businessLogicSummary: String = "",
    /** Absolute path to the raw .java source file */
    val sourceFilePath: String = ""
)

// ─── Verification / test tracking ────────────────────────────────────────────

@Serializable
data class TestCase(
    val testId: String,
    val className: String,
    val methodName: String,
    val inputs: List<String>,
    val expectedOutput: String
)

@Serializable
data class VerificationResult(
    val compiledSuccessfully: Boolean,
    val compilerErrors: List<String> = emptyList(),
    val testsPassed: Int = 0,
    val testsFailed: Int = 0,
    val testFailures: List<String> = emptyList()
)

// ─── Per-agent reporting ──────────────────────────────────────────────────────

@Serializable
data class AgentReport(
    val stageName: String,
    val status: StageStatus,
    val timestamp: String,
    val details: String,
    val metrics: Map<String, String> = emptyMap()
)

// ─── Shared pipeline state ────────────────────────────────────────────────────

/**
 * Immutable (copy-on-write) shared state object threaded through the pipeline.
 *
 * Key additions for Stage 1:
 *  - [dependencyGraph]   : className → list of className it depends on
 *  - [moduleStatuses]    : className → current [ModuleStatus]
 *  - [topologicalOrder]  : classes sorted so dependencies come first
 *  - [moduleSpecs]       : className → full [ModuleSpec] with LLM summary
 *  - [regenCounters]     : className → how many times it has been regenerated
 */
data class GraphState(
    // ── Project discovery ──────────────────────────────────────────────
    val targetProjectPath: String,
    val javaFiles: List<String> = emptyList(),

    // ── AST & dependency data (Stage 1) ───────────────────────────────
    /** Raw string metadata kept for backward-compat with CharacterizationTool */
    val astMetadata: Map<String, String> = emptyMap(),
    /** className → list of classNames it directly depends on */
    val dependencyGraph: Map<String, List<String>> = emptyMap(),
    /** Topologically sorted class names (dependencies first) */
    val topologicalOrder: List<String> = emptyList(),
    /** Full structured specs including LLM business-logic summaries */
    val moduleSpecs: Map<String, ModuleSpec> = emptyMap(),

    // ── Per-module status tracking ─────────────────────────────────────
    /** className → lifecycle status */
    val moduleStatuses: Map<String, ModuleStatus> = emptyMap(),
    /** className → number of times regeneration was attempted */
    val regenCounters: Map<String, Int> = emptyMap(),

    // ── Characterization & migration ───────────────────────────────────
    val characterizationTests: List<TestCase> = emptyList(),
    val migratedCode: MutableMap<String, String> = mutableMapOf(),

    // ── Verification ───────────────────────────────────────────────────
    var verificationResult: VerificationResult = VerificationResult(compiledSuccessfully = false),

    // ── Pipeline control ───────────────────────────────────────────────
    var retryCount: Int = 0,
    val maxRetries: Int = 3,
    var currentStage: String = "INITIALIZATION",
    val reports: MutableList<AgentReport> = mutableListOf()
) {
    fun isMaxRetriesReached(): Boolean = retryCount >= maxRetries

    /** Convenience: increment regen counter for a module. */
    fun incrementRegen(className: String): GraphState {
        val updated = regenCounters.toMutableMap()
        updated[className] = (updated[className] ?: 0) + 1
        return this.copy(regenCounters = updated)
    }

    /** Convenience: update a single module's status. */
    fun withModuleStatus(className: String, status: ModuleStatus): GraphState {
        val updated = moduleStatuses.toMutableMap()
        updated[className] = status
        return this.copy(moduleStatuses = updated)
    }
}
