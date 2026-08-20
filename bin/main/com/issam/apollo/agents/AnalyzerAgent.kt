package com.issam.apollo.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.agents.core.tools.annotations.LLMDescription
import ai.koog.agents.core.tools.reflect.ToolSet
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.clients.openai.OpenAIModels
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.issam.apollo.config.LlmConfig
import com.issam.apollo.config.LlmProvider as ApolloProvider
import com.issam.apollo.state.AgentReport
import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleSpec
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.state.StageStatus
import com.issam.apollo.tools.JavaAstTool
import com.issam.apollo.tools.JavaClassSpec
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.Instant

// ─── Koog ToolSet exposed to the LLM ─────────────────────────────────────────

/**
 * Lightweight tool-set the analyzer LLM can invoke during its reasoning loop.
 * Primarily for inspection / re-analysis use-cases.
 */
@LLMDescription("Tools for inspecting Java source code structure in the repository being analyzed.")
class AnalyzerToolSet(private val astTool: JavaAstTool) : ToolSet {

    @ai.koog.agents.core.tools.annotations.Tool
    @LLMDescription("Lists all Java class names found in the repository under the given path.")
    fun listJavaClasses(repoPath: String): String {
        val dir = File(repoPath)
        if (!dir.exists()) return "Directory not found: $repoPath"
        val files = dir.walkTopDown().filter { it.isFile && it.extension == "java" }.toList()
        return if (files.isEmpty()) "No Java files found." else files.joinToString("\n") { it.name }
    }
}

// ─── Agent ────────────────────────────────────────────────────────────────────

/**
 * Stage 1 — Analyzer Agent
 *
 * Responsibilities:
 *  1. Invoke [JavaAstTool.analyzeRepo] → AST, dependency graph, topo sort.
 *  2. For each module (in topo order) call a Koog [AIAgent] to produce an LLM summary.
 *  3. Write per-module `.md` + `.json` spec files under `reports/specs/`.
 *  4. Write `reports/topo-order.md` (migration order + dependency table).
 *  5. Return updated [GraphState] with all Stage-1 fields populated.
 */
class AnalyzerAgent(
    private val astTool: JavaAstTool = JavaAstTool(),
    private val reportsDir: File = File("").absoluteFile.resolve("reports")
) {

    // ── Prompt executor (lazy — avoids connecting at construction time) ────────

    private val promptExecutor: MultiLLMPromptExecutor by lazy {
        val client = buildOpenAICompatibleClient()
        MultiLLMPromptExecutor(client)
    }

    /**
     * Build an [OpenAILLMClient] pointing at the right base URL for the
     * configured provider.  Both Groq and Ollama expose OpenAI-compatible
     * Chat-Completions endpoints so we can reuse the same client class.
     */
    private fun buildOpenAICompatibleClient(): OpenAILLMClient {
        val settings = OpenAIClientSettings(
            /* baseUrl              */ LlmConfig.ollamaBaseUrl,
            /* timeoutConfig        */ ai.koog.prompt.executor.clients.ConnectionTimeoutConfig(),
            /* chatCompletionsPath  */ "chat/completions",
            /* responsesAPIPath     */ "v1/responses",
            /* embeddingsPath       */ "v1/embeddings",
            /* moderationsPath      */ "v1/moderations",
            /* modelsPath           */ "v1/models"
        )
        return OpenAILLMClient(apiKey = LlmConfig.ollamaApiKey, settings = settings)
    }

    private val llModel: LLModel
        get() {
            return LLModel(
                provider = LLMProvider.OpenAI,
                id = LlmConfig.ollamaModel,
                capabilities = listOf(
                    LLMCapability.Completion,
                    LLMCapability.Temperature,
                    LLMCapability.Tools,
                    LLMCapability.Schema.JSON.Basic,
                    LLMCapability.OpenAIEndpoint.Completions
                ),
                contextLength = 128_000L,
                maxOutputTokens = 8_192L
            )
        }

    // ── Public entry point ────────────────────────────────────────────────────

    /**
     * Execute Stage 1.
     *
     * @param state  Incoming [GraphState] (only `targetProjectPath` is required).
     * @return Updated state with dependency graph, module specs, topo order, and
     *         per-module statuses populated.
     */
    fun execute(state: GraphState): GraphState {
        println("[AnalyzerAgent] Analyzing: ${state.targetProjectPath}")

        val targetDir = File(state.targetProjectPath)

        // Step 1 — AST + dependency graph + topo sort
        val repoAnalysis = astTool.analyzeRepo(targetDir)
        println("[AnalyzerAgent] Parsed ${repoAnalysis.specs.size} Java files")
        println("[AnalyzerAgent] Topo order: ${repoAnalysis.topologicalOrder}")

        // Step 2 — LLM summarization per module
        val moduleSpecs           = mutableMapOf<String, ModuleSpec>()
        val moduleStatuses        = mutableMapOf<String, ModuleStatus>()
        val astMetadata           = mutableMapOf<String, String>()
        val configErrorModules    = mutableListOf<String>()
        val transientErrorModules = mutableListOf<String>()

        for (className in repoAnalysis.topologicalOrder) {
            val spec = repoAnalysis.specs[className] ?: continue
            val deps = repoAnalysis.dependencyGraph[className] ?: emptyList()

            println("[AnalyzerAgent]   → Summarizing: $className")

            var summary = ""
            try {
                summary = callLlmForSummary(spec, deps)
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (LlmConfig.isConfigOr404Error(e)) {
                    System.err.println("==========================================================================")
                    System.err.println("⚠️ LOUD WARNING [AnalyzerAgent]: LLM CONFIG / MODEL NOT FOUND ERROR for '$className'!")
                    System.err.println("   Details: ${e.message}")
                    System.err.println("   Falling back to AST summary. Flagging usedFallbackDueToConfigError = true")
                    System.err.println("==========================================================================")
                    configErrorModules.add(className)
                } else {
                    System.err.println("[AnalyzerAgent] LLM failed for $className across providers: $msg. Using AST summary fallback.")
                    transientErrorModules.add(className)
                }
                summary = buildFallbackSummary(spec, deps)
            }

            val moduleSpec = astTool.toModuleSpec(spec, deps, summary)
            moduleSpecs[className]    = moduleSpec
            moduleStatuses[className] = ModuleStatus.ANALYZED

            // Legacy string metadata (used by CharacterizationTool, FixerAgent, etc.)
            astMetadata[className] = "Package: ${spec.packageName}, " +
                "Fields: ${spec.fields.size}, " +
                "Methods: ${spec.methods.size}, " +
                "DependsOn: ${deps.joinToString()}"

            // Step 3 — Write per-module report files
            writeModuleReports(moduleSpec)
        }

        // Step 4 — Topo-order index
        writeTopoIndex(repoAnalysis.topologicalOrder, repoAnalysis.dependencyGraph)

        // Step 5 — Agent report
        val reportMetrics = mutableMapOf(
            "totalFiles"                   to repoAnalysis.specs.size.toString(),
            "totalDeps"                    to repoAnalysis.dependencyGraph.values.sumOf { it.size }.toString(),
            "topologicalSize"              to repoAnalysis.topologicalOrder.size.toString(),
            "usedFallbackDueToConfigError" to configErrorModules.isNotEmpty().toString(),
            "usedFallbackDueToTransientError" to transientErrorModules.isNotEmpty().toString()
        )
        if (configErrorModules.isNotEmpty()) {
            reportMetrics["configErrorModules"] = configErrorModules.joinToString(",")
        }
        if (transientErrorModules.isNotEmpty()) {
            reportMetrics["transientErrorModules"] = transientErrorModules.joinToString(",")
        }

        val report = AgentReport(
            stageName = "STAGE_1_ANALYZER",
            status    = StageStatus.COMPLETED,
            timestamp = Instant.now().toString(),
            details   = buildString {
                appendLine("Parsed ${repoAnalysis.specs.size} Java files.")
                appendLine("Topo order: ${repoAnalysis.topologicalOrder.joinToString(" → ")}")
                if (configErrorModules.isNotEmpty()) {
                    appendLine("⚠️ CONFIG ERROR / MODEL NOT FOUND FALLBACK triggered for modules: ${configErrorModules.joinToString(", ")}")
                }
                if (transientErrorModules.isNotEmpty()) {
                    appendLine("Transient error fallback triggered for modules: ${transientErrorModules.joinToString(", ")}")
                }
                appendLine("Specs written to: ${File(reportsDir, "specs").absolutePath}")
            },
            metrics = reportMetrics
        )
        state.reports.add(report)

        return state.copy(
            javaFiles        = repoAnalysis.specs.values.map { it.sourceFilePath },
            astMetadata      = astMetadata,
            dependencyGraph  = repoAnalysis.dependencyGraph,
            topologicalOrder = repoAnalysis.topologicalOrder,
            moduleSpecs      = moduleSpecs,
            moduleStatuses   = moduleStatuses,
            currentStage     = "ANALYSIS_COMPLETED"
        )
    }

    // ── LLM call via Koog AIAgent ─────────────────────────────────────────────

    private fun callLlmForSummary(spec: JavaClassSpec, deps: List<String>): String {
        val systemPrompt = """
            You are a senior Java architect helping with a Java-to-Kotlin migration.
            When given a Java class, produce a concise structured business-logic summary.
            Focus on WHAT the class does, not HOW. Be precise.
            Respond ONLY with the summary text, no markdown fences.
        """.trimIndent()

        val userPrompt = buildSummarizationPrompt(spec, deps)
        return try {
            LlmConfig.callLlmWithFallback(userPrompt, systemPrompt, temperature = 0.1, moduleName = spec.className)
        } catch (e: Exception) {
            println("[AnalyzerAgent] LLM call failed for ${spec.className}: ${e.message}. Using AST fallback.")
            buildFallbackSummary(spec, deps)
        }
    }

    // ── Fallback ──────────────────────────────────────────────────────────────

    private fun buildFallbackSummary(spec: JavaClassSpec, deps: List<String>): String = buildString {
        appendLine("**${spec.className}** — `${spec.packageName}`")
        appendLine()
        if (deps.isNotEmpty()) appendLine("**Depends on:** ${deps.joinToString()}")
        appendLine("**Fields (${spec.fields.size}):** ${spec.fields.joinToString()}")
        appendLine("**Methods (${spec.methods.size}):**")
        spec.methods.forEach { appendLine("  - `$it`") }
        appendLine()
        appendLine("*(AST-only summary — LLM unavailable)*")
    }

    // ── Prompt builder ────────────────────────────────────────────────────────

    private fun buildSummarizationPrompt(spec: JavaClassSpec, deps: List<String>): String = buildString {
        appendLine("Analyze this Java class and produce a structured business-logic summary.")
        appendLine()
        appendLine("## Class: ${spec.className}")
        appendLine("**Package:** `${spec.packageName}`")
        if (deps.isNotEmpty()) appendLine("**Repo Dependencies:** ${deps.joinToString()}")
        appendLine()
        appendLine("### Fields")
        if (spec.fields.isEmpty()) appendLine("*(none)*")
        else spec.fields.forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("### Methods")
        if (spec.methods.isEmpty()) appendLine("*(none)*")
        else spec.methods.forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("### Source")
        appendLine("```java")
        appendLine(spec.rawSource.take(4000))
        appendLine("```")
        appendLine()
        appendLine("""
            Provide a summary with these sections:
            1. **Purpose** — What this class is responsible for (1-2 sentences).
            2. **Core Logic** — Key algorithms or business rules.
            3. **Migration Notes** — Kotlin idioms that would improve this class.
            4. **Dependencies** — How it relies on other repo modules (if any).
        """.trimIndent())
    }

    // ── File I/O ──────────────────────────────────────────────────────────────

    private val jsonPretty = Json { prettyPrint = true }

    /** Write `.md` and `.json` spec files under `reports/specs/<ClassName>/`. */
    private fun writeModuleReports(spec: ModuleSpec) {
        val specsDir = File(reportsDir, "specs/${spec.className}")
        specsDir.mkdirs()

        File(specsDir, "${spec.className}-spec.md").writeText(buildMarkdownSpec(spec))
        File(specsDir, "${spec.className}-spec.json").writeText(jsonPretty.encodeToString(spec))

        println("[AnalyzerAgent]   → Wrote spec to: ${specsDir.path}")
    }

    private fun buildMarkdownSpec(spec: ModuleSpec): String = buildString {
        appendLine("# Module Spec: ${spec.className}")
        appendLine()
        appendLine("| Property | Value |")
        appendLine("|----------|-------|")
        appendLine("| **Package** | `${spec.packageName}` |")
        appendLine("| **Source** | `${spec.sourceFilePath}` |")
        val depsStr = if (spec.dependsOn.isEmpty()) "*(none)*" else spec.dependsOn.joinToString { "`$it`" }
        appendLine("| **Depends On** | $depsStr |")
        appendLine()
        appendLine("## Fields")
        if (spec.fields.isEmpty()) appendLine("*(none)*") else spec.fields.forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("## Methods")
        if (spec.methods.isEmpty()) appendLine("*(none)*") else spec.methods.forEach { appendLine("- `$it`") }
        appendLine()
        appendLine("## Business Logic Summary")
        appendLine()
        appendLine(spec.businessLogicSummary.ifBlank { "*(no summary generated)*" })
    }

    /** Write `reports/topo-order.md` with migration order and dependency table. */
    private fun writeTopoIndex(order: List<String>, graph: Map<String, List<String>>) {
        reportsDir.mkdirs()
        File(reportsDir, "topo-order.md").writeText(buildString {
            appendLine("# Apollo — Stage 1: Module Dependency Report")
            appendLine()
            appendLine("Generated: ${Instant.now()}")
            appendLine()
            appendLine("## Topologically-Sorted Migration Order")
            appendLine()
            appendLine("Every dependency appears **before** the classes that use it.")
            appendLine()
            order.forEachIndexed { i, cls -> appendLine("${i + 1}. `$cls`") }
            appendLine()
            appendLine("## Dependency Graph")
            appendLine()
            appendLine("| Module | Depends On |")
            appendLine("|--------|------------|")
            for (cls in order) {
                val deps = graph[cls]?.joinToString { "`$it`" } ?: "*(none)*"
                appendLine("| `$cls` | $deps |")
            }
        })
        println("[AnalyzerAgent] Wrote topo-order.md to: ${reportsDir.path}")
    }
}
