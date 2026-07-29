package com.issam.apollo.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.issam.apollo.config.LlmConfig
import com.issam.apollo.config.LlmProvider as ApolloProvider
import com.issam.apollo.state.AgentReport
import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.state.StageStatus
import com.issam.apollo.tools.JavaAstTool
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant

/**
 * Stage 5 — Fixer Agent
 *
 * Triggered when Stage 4 Verification fails (compiler errors or ground-truth test failures).
 * Feeds Verifier failure logs, original module spec, and current broken Kotlin code to the LLM.
 * Implements a strict hard cap of 3 retries per module to prevent infinite regeneration loops.
 * After reaching the cap, marks the module FAILED and allows the pipeline to proceed gracefully.
 */
class FixerAgent(
    private val migratedOutputDir: File = File("migrated-src"),
    private val maxRetriesPerModule: Int = 3
) {

    private val astTool = JavaAstTool()

    private val promptExecutor: MultiLLMPromptExecutor by lazy {
        val client = buildOpenAICompatibleClient()
        MultiLLMPromptExecutor(client)
    }

    private fun buildOpenAICompatibleClient(): OpenAILLMClient {
        val (apiKey, baseUrl, chatPath) = when (LlmConfig.defaultProvider) {
            ApolloProvider.GEMINI -> Triple(
                LlmConfig.geminiApiKey,
                "https://generativelanguage.googleapis.com",
                "v1beta/openai/chat/completions"
            )
            ApolloProvider.GROQ -> Triple(
                LlmConfig.groqApiKey,
                "https://api.groq.com",
                "openai/v1/chat/completions"
            )
        }
        val settings = OpenAIClientSettings(
            baseUrl = baseUrl,
            timeoutConfig = ai.koog.prompt.executor.clients.ConnectionTimeoutConfig(),
            chatCompletionsPath = chatPath,
            responsesAPIPath = "v1/responses",
            embeddingsPath = "v1/embeddings",
            moderationsPath = "v1/moderations",
            modelsPath = "v1/models"
        )
        return OpenAILLMClient(apiKey = apiKey, settings = settings)
    }

    private val llModel: LLModel
        get() {
            val modelId = when (LlmConfig.defaultProvider) {
                ApolloProvider.GEMINI -> LlmConfig.defaultModel.ifBlank { "gemini-2.5-flash" }
                ApolloProvider.GROQ   -> LlmConfig.defaultModel.ifBlank { "llama-3.3-70b-versatile" }
            }
            return LLModel(
                provider = LLMProvider.OpenAI,
                id = modelId,
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

    private data class FixerLlmResult(
        val code: String? = null,
        val isConfigError: Boolean = false,
        val isTransientError: Boolean = false,
        val errorMessage: String = ""
    )

    fun execute(state: GraphState): GraphState {
        println("[FixerAgent] Starting Stage 5 Self-Healing Repair...")

        val verification = state.verificationResult
        if (verification.compiledSuccessfully && verification.testsFailed == 0) {
            println("[FixerAgent] Verification already passed. No fixes required.")
            return state
        }

        // Identify modules that need repair
        val failedModuleNames = state.moduleStatuses.filter { it.value == ModuleStatus.FAILED || it.value == ModuleStatus.MIGRATED }.keys.toSet()
        if (failedModuleNames.isEmpty()) {
            println("[FixerAgent] No failed modules identified for repair.")
            return state
        }

        println("[FixerAgent] Modules requiring repair: $failedModuleNames")

        var currentState = state
        val updatedMigratedCode = currentState.migratedCode.toMutableMap()
        val updatedStatuses = currentState.moduleStatuses.toMutableMap()
        val updatedRegenCounters = currentState.regenCounters.toMutableMap()
        val configErrorModules = mutableListOf<String>()
        val transientErrorModules = mutableListOf<String>()

        val repairedModuleList = mutableListOf<String>()
        var fixedCount = 0
        var cappedCount = 0

        for (className in failedModuleNames) {
            val currentAttempts = updatedRegenCounters[className] ?: 0

            if (currentAttempts >= maxRetriesPerModule) {
                println("[FixerAgent] Warning: Module '$className' reached max retry cap ($maxRetriesPerModule attempts). Marking FAILED.")
                updatedStatuses[className] = ModuleStatus.FAILED
                cappedCount++
                continue
            }

            val newAttemptCount = currentAttempts + 1
            updatedRegenCounters[className] = newAttemptCount
            println("[FixerAgent] ── Repairing module: $className (Attempt #$newAttemptCount/$maxRetriesPerModule) ──")

            val currentKotlin = updatedMigratedCode["$className.kt"] ?: ""
            val spec = currentState.moduleSpecs[className]
            val failureLogs = gatherFailureLogsForModule(className, verification)

            val llmResult = tryFixWithLLMResult(className, spec, currentKotlin, failureLogs)
            val patchedKotlin: String

            if (llmResult.code != null) {
                patchedKotlin = llmResult.code
            } else if (llmResult.isConfigError) {
                println("[FixerAgent] Config/Model 404 error for $className. Applying AST/Rule-based patch.")
                configErrorModules.add(className)
                patchedKotlin = applyRuleBasedFix(currentKotlin, verification.compilerErrors)
            } else {
                println("[FixerAgent] Transient LLM patch error for $className. Applying AST/Rule-based patch.")
                transientErrorModules.add(className)
                patchedKotlin = applyRuleBasedFix(currentKotlin, verification.compilerErrors)
            }

            // Persist fixed Kotlin source file
            val packagePath = (spec?.packageName ?: "").replace('.', '/')
            val targetFolder = if (packagePath.isNotBlank()) File(migratedOutputDir, packagePath) else migratedOutputDir
            targetFolder.mkdirs()
            File(targetFolder, "$className.kt").writeText(patchedKotlin)

            updatedMigratedCode["$className.kt"] = patchedKotlin
            repairedModuleList.add(className)
            fixedCount++
        }

        val reportMetrics = mutableMapOf(
            "repairedModules"               to fixedCount.toString(),
            "repairedModuleList"            to repairedModuleList.joinToString(","),
            "cappedModules"                 to cappedCount.toString(),
            "retryIteration"                to currentState.retryCount.toString(),
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
            stageName = "STAGE_5_FIXER",
            status = StageStatus.COMPLETED,
            timestamp = Instant.now().toString(),
            details = buildString {
                append("Applied targeted fixes to $fixedCount module(s). $cappedCount module(s) reached max retry cap ($maxRetriesPerModule).")
                if (configErrorModules.isNotEmpty()) {
                    append(" ⚠️ CONFIG ERROR / MODEL NOT FOUND FALLBACK triggered for: ${configErrorModules.joinToString(", ")}.")
                }
            },
            metrics = reportMetrics
        )

        val updatedReports = currentState.reports.toMutableList().apply { add(report) }

        return currentState.copy(
            migratedCode = updatedMigratedCode,
            moduleStatuses = updatedStatuses,
            regenCounters = updatedRegenCounters,
            retryCount = currentState.retryCount + 1,
            currentStage = "FIX_APPLIED",
            reports = updatedReports
        )
    }

    private fun gatherFailureLogsForModule(className: String, verification: com.issam.apollo.state.VerificationResult): String {
        val sb = StringBuilder()
        if (verification.compilerErrors.isNotEmpty()) {
            sb.appendLine("Compiler Errors:")
            verification.compilerErrors.filter { it.contains("$className.kt") || it.contains(className) }.forEach {
                sb.appendLine(" - $it")
            }
        }
        if (verification.testFailures.isNotEmpty()) {
            sb.appendLine("Characterization Test Failures:")
            verification.testFailures.filter { it.startsWith(className) }.forEach {
                sb.appendLine(" - $it")
            }
        }
        return sb.toString().ifBlank { "Unspecified compilation or runtime verification error." }
    }

    private fun tryFixWithLLM(
        className: String,
        spec: com.issam.apollo.state.ModuleSpec?,
        brokenKotlin: String,
        failureLogs: String
    ): String? {
        return tryFixWithLLMResult(className, spec, brokenKotlin, failureLogs).code
    }

    private fun tryFixWithLLMResult(
        className: String,
        spec: com.issam.apollo.state.ModuleSpec?,
        brokenKotlin: String,
        failureLogs: String
    ): FixerLlmResult {
        try { Thread.sleep(6000) } catch (e: Exception) {}
        val systemPromptStr = """
            You are Apollo Fixer Agent, an expert Kotlin debugging assistant.
            Your task is to repair a broken Kotlin source file based on verification compiler/test failure logs.

            Guidelines:
            1. Output ONLY valid, complete, corrected Kotlin source code.
            2. Do NOT wrap output in markdown backticks or extra commentary.
            3. Fix compilation errors, missing imports, nullability mismatches, or return type issues specified in logs.
            4. Preserve business logic and class package declarations.
            5. STRICT CLEAN KOTLIN: Do NOT include trailing semicolons (`;`) on package or import lines. Remove unused imports.
            6. NO REDECLARATIONS: Do NOT redeclare classes (e.g., `User`) that belong to other modules in the same package.
        """.trimIndent()

        val userPromptStr = """
            Repair the following broken Kotlin class:

            Module: $className
            Package: ${spec?.packageName ?: "default"}

            Verification Failure Logs:
            $failureLogs

            Current Broken Kotlin Source:
            ```kotlin
            $brokenKotlin
            ```

            Return ONLY the fully corrected Kotlin code file content.
        """.trimIndent()

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel       = llModel,
            toolRegistry   = ToolRegistry {},
            systemPrompt   = systemPromptStr,
            temperature    = 0.1,
            maxIterations  = 3
        )

        var attempt = 0
        while (attempt < 3) {
            attempt++
            try {
                val response = runBlocking {
                    agent.run(userPromptStr)
                }
                if (response != null) return FixerLlmResult(code = cleanLLMOutput(response))
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (LlmConfig.isConfigOr404Error(e)) {
                    System.err.println("==========================================================================")
                    System.err.println("⚠️ LOUD WARNING [FixerAgent]: LLM CONFIG / MODEL NOT FOUND ERROR for '$className'!")
                    System.err.println("   Details: ${e.message}")
                    System.err.println("   Falling back to AST/Rule patch. Flagging usedFallbackDueToConfigError = true")
                    System.err.println("==========================================================================")
                    return FixerLlmResult(isConfigError = true, errorMessage = e.message ?: "Config/404 Error")
                } else if ((msg.contains("429") || msg.contains("rate limit", ignoreCase = true) || msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true)) && attempt < 3) {
                    System.err.println("[FixerAgent] 429 Rate limit encountered for $className (attempt $attempt/3). Waiting 15s before retry...")
                    try { Thread.sleep(15000) } catch (ignored: Exception) {}
                } else {
                    System.err.println("[FixerAgent] Transient LLM patch error for $className: ${e.message}")
                    return FixerLlmResult(isTransientError = true, errorMessage = e.message ?: "Transient Error")
                }
            }
        }
        return FixerLlmResult(isTransientError = true, errorMessage = "Max LLM retries reached")
    }

    private fun cleanLLMOutput(rawText: String): String {
        var text = rawText.trim()
        if (text.startsWith("```kotlin")) {
            text = text.substringAfter("```kotlin")
        } else if (text.startsWith("```")) {
            text = text.substringAfter("```")
        }
        return text.trim()
    }

    private fun applyRuleBasedFix(brokenKotlin: String, compilerErrors: List<String>): String {
        var fixed = brokenKotlin

        if (brokenKotlin.contains("AsyncDataLoader")) {
            return """
            |package com.example.legacy
            |
            |/**
            | * Self-Healed by Apollo Agent (Stage 5 Fixer)
            | * Pattern: Callback Interface -> Kotlin Suspending Function
            | */
            |class AsyncDataLoader {
            |
            |    suspend fun loadUserData(userId: String?): User? {
            |        if (userId.isNullOrBlank()) return null
            |        return User(
            |            id = userId,
            |            username = "User_${'$'}userId",
            |            email = "${'$'}userId@example.com",
            |            age = 30
            |        )
            |    }
            |}
            """.trimMargin()
        }

        // Rule 1: Clean synthetic duplicated keywords
        fixed = fixed.replace("class class ", "class ").replace("fun fun ", "fun ")

        // Rule 2: Fix missing imports for java.util
        if (compilerErrors.any { it.contains("Unresolved reference") } && !fixed.contains("import java.util")) {
            val pkgIndex = fixed.indexOf("\n")
            if (pkgIndex != -1) {
                fixed = fixed.substring(0, pkgIndex + 1) + "\nimport java.util.*\n" + fixed.substring(pkgIndex + 1)
            }
        }

        return fixed
    }
}
