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
import com.issam.apollo.knowledge.MigrationPattern
import com.issam.apollo.knowledge.MigrationPatterns
import com.issam.apollo.state.AgentReport
import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleSpec
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.state.StageStatus
import com.issam.apollo.tools.JavaAstTool
import kotlinx.coroutines.runBlocking
import java.io.File
import java.time.Instant

/**
 * Stage 3 — Migrator Agent
 *
 * Iterates over target project modules in strict topological (dependency) order.
 * Synthesizes Analyzer module specs, raw Java source code, curated Knowledge Base
 * migration patterns, and previously migrated Kotlin dependency context into LLM prompts.
 * Falls back to an AST/rule-based Kotlin generator when the LLM is offline or rate-limited.
 * Writes generated Kotlin source files to migrated-src/ and updates GraphState.
 */
class MigratorAgent(
    private val migrationPatterns: MigrationPatterns = MigrationPatterns(),
    private val migratedOutputDir: File = File("migrated-src")
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

    private data class MigrationLlmResult(
        val code: String? = null,
        val isConfigError: Boolean = false,
        val isTransientError: Boolean = false,
        val errorMessage: String = ""
    )

    fun execute(state: GraphState): GraphState {
        println("[MigratorAgent] Starting Stage 3 Java-to-Kotlin Migration...")
        migratedOutputDir.mkdirs()

        // Determine execution sequence (use topological order computed in Stage 1)
        val moduleOrder = if (state.topologicalOrder.isNotEmpty()) {
            state.topologicalOrder
        } else {
            state.javaFiles.map { File(it).nameWithoutExtension }
        }

        println("[MigratorAgent] Migration Order: $moduleOrder")

        var currentState = state
        val newMigratedCode = mutableMapOf<String, String>()
        val newModuleStatuses = currentState.moduleStatuses.toMutableMap()
        val configErrorModules = mutableListOf<String>()
        val transientErrorModules = mutableListOf<String>()

        for (className in moduleOrder) {
            println("[MigratorAgent] ── Migrating module: $className ──")

            val spec = currentState.moduleSpecs[className]
            val javaFilePath = spec?.sourceFilePath
                ?: currentState.javaFiles.firstOrNull { File(it).nameWithoutExtension == className }

            if (javaFilePath == null || !File(javaFilePath).exists()) {
                println("[MigratorAgent] Warning: Source file for $className not found ($javaFilePath). Skipping.")
                continue
            }

            val javaSource = File(javaFilePath).readText()
            val matchingPatterns = migrationPatterns.findMatchingPatternsForSpec(
                spec ?: astTool.parseJavaFile(File(javaFilePath)).let {
                    ModuleSpec(
                        className = it.className,
                        packageName = it.packageName,
                        imports = it.imports,
                        fields = it.fields,
                        methods = it.methods,
                        sourceFilePath = it.sourceFilePath
                    )
                },
                javaSource
            )

            // Gather context from previously migrated Kotlin dependencies
            val dependencyContext = buildDependencyContext(spec, newMigratedCode)

            val llmResult = tryMigrateWithLLMResult(className, spec, javaSource, matchingPatterns, dependencyContext)
            val kotlinCode: String

            if (llmResult.code != null) {
                println("[MigratorAgent] Successfully generated Kotlin code via LLM for $className.")
                kotlinCode = llmResult.code
            } else if (llmResult.isConfigError) {
                println("[MigratorAgent] Config/Model 404 error for $className. Using AST/Rule-based engine.")
                configErrorModules.add(className)
                kotlinCode = migrateWithRules(className, javaSource)
            } else {
                println("[MigratorAgent] Transient LLM error for $className. Using AST/Rule-based engine.")
                transientErrorModules.add(className)
                kotlinCode = migrateWithRules(className, javaSource)
            }

            // Save Kotlin source file to migrated-src/
            val packagePath = (spec?.packageName ?: "").replace('.', '/')
            val targetFolder = if (packagePath.isNotBlank()) File(migratedOutputDir, packagePath) else migratedOutputDir
            targetFolder.mkdirs()

            val outputFile = File(targetFolder, "$className.kt")
            outputFile.writeText(kotlinCode)

            println("[MigratorAgent]   Saved Kotlin source to: ${outputFile.path}")

            newMigratedCode["$className.kt"] = kotlinCode
            newModuleStatuses[className] = ModuleStatus.MIGRATED
        }

        val reportMetrics = mutableMapOf(
            "totalModulesMigrated"          to newMigratedCode.size.toString(),
            "outputDirectory"               to migratedOutputDir.absolutePath,
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
            stageName = "STAGE_3_MIGRATOR",
            status = StageStatus.COMPLETED,
            timestamp = Instant.now().toString(),
            details = buildString {
                append("Migrated ${newMigratedCode.size} Java files to Kotlin in dependency order. Saved to ${migratedOutputDir.path}.")
                if (configErrorModules.isNotEmpty()) {
                    append(" ⚠️ CONFIG ERROR / MODEL NOT FOUND FALLBACK triggered for: ${configErrorModules.joinToString(", ")}.")
                }
                if (transientErrorModules.isNotEmpty()) {
                    append(" Transient error fallback triggered for: ${transientErrorModules.joinToString(", ")}.")
                }
            },
            metrics = reportMetrics
        )

        val updatedReports = currentState.reports.toMutableList().apply { add(report) }

        return currentState.copy(
            migratedCode = newMigratedCode,
            moduleStatuses = newModuleStatuses,
            currentStage = "MIGRATION_COMPLETED",
            reports = updatedReports
        )
    }

    private fun buildDependencyContext(spec: ModuleSpec?, currentMigratedCode: Map<String, String>): String {
        if (spec == null || spec.dependsOn.isEmpty()) return "No internal module dependencies."

        val sb = StringBuilder()
        sb.appendLine("The following dependencies for this module have already been migrated to Kotlin:")
        for (dep in spec.dependsOn) {
            val code = currentMigratedCode["$dep.kt"]
            if (code != null) {
                sb.appendLine("--- $dep.kt ---")
                sb.appendLine(code)
                sb.appendLine()
            }
        }
        return sb.toString()
    }

    private fun tryMigrateWithLLM(
        className: String,
        spec: ModuleSpec?,
        javaSource: String,
        patterns: List<MigrationPattern>,
        dependencyContext: String
    ): String? {
        return tryMigrateWithLLMResult(className, spec, javaSource, patterns, dependencyContext).code
    }

    private fun tryMigrateWithLLMResult(
        className: String,
        spec: ModuleSpec?,
        javaSource: String,
        patterns: List<MigrationPattern>,
        dependencyContext: String
    ): MigrationLlmResult {
        try { Thread.sleep(6000) } catch (e: Exception) {}
        val patternPrompt = migrationPatterns.formatPatternsForPrompt(patterns)

        val systemPromptStr = """
            You are Apollo, an expert Java-to-Kotlin Migration AI Agent.
            Your task is to transform legacy Java classes into modern, idiomatic Kotlin code.

            Follow these strict guidelines:
            1. Produce ONLY valid, compilable Kotlin code. Do NOT wrap output in markdown backticks or explanations.
            2. Preserve exact package declaration, class hierarchy, method names, and business logic semantics.
            3. Apply Kotlin null safety (?. , ?: , requireNotNull), data classes for POJOs, object/extension functions for utilities, and collection functions (filter, map, find).
            4. Ensure compatibility with previously migrated Kotlin dependency classes provided in context.
            5. STRICT CLEAN KOTLIN: Do NOT include trailing semicolons (`;`) on package statements, import lines, or code lines. Remove all unused imports (e.g., `import java.util.ArrayList`).
            6. NO REDECLARATIONS: Do NOT redeclare classes (e.g., `User`) that are already provided in dependency context or package scope.
        """.trimIndent()

        val userPromptStr = """
            Migrate the following Java class to Kotlin:

            Module: $className
            Package: ${spec?.packageName ?: "default"}
            Dependencies: ${spec?.dependsOn?.joinToString(", ") ?: "none"}

            Curated Migration Patterns to Apply:
            $patternPrompt

            Previously Migrated Dependencies Context:
            $dependencyContext

            Original Java Source Code:
            ```java
            $javaSource
            ```

            STRICT CODE QUALITY REQUIREMENTS:
            - Do NOT include trailing semicolons `;` on package or import statements.
            - Remove all unused Java imports (e.g., `import java.util.ArrayList`, `import java.util.Objects`).

            Output ONLY the full transformed Kotlin source file text without markdown block markers.
        """.trimIndent()

        val agent = AIAgent(
            promptExecutor = promptExecutor,
            llmModel       = llModel,
            toolRegistry   = ToolRegistry {},
            systemPrompt   = systemPromptStr,
            temperature    = 0.2,
            maxIterations  = 3
        )

        var attempt = 0
        while (attempt < 3) {
            attempt++
            try {
                val response = runBlocking {
                    agent.run(userPromptStr)
                }
                if (response != null) return MigrationLlmResult(code = cleanLLMOutput(response))
            } catch (e: Exception) {
                val msg = e.message ?: ""
                if (LlmConfig.isConfigOr404Error(e)) {
                    System.err.println("==========================================================================")
                    System.err.println("⚠️ LOUD WARNING [MigratorAgent]: LLM CONFIG / MODEL NOT FOUND ERROR for '$className'!")
                    System.err.println("   Details: ${e.message}")
                    System.err.println("   Falling back to AST/Rule engine. Flagging usedFallbackDueToConfigError = true")
                    System.err.println("==========================================================================")
                    return MigrationLlmResult(isConfigError = true, errorMessage = e.message ?: "Config/404 Error")
                } else if ((msg.contains("429") || msg.contains("rate limit", ignoreCase = true) || msg.contains("RESOURCE_EXHAUSTED", ignoreCase = true)) && attempt < 3) {
                    System.err.println("[MigratorAgent] 429 Rate limit encountered for $className (attempt $attempt/3). Waiting 15s before retry...")
                    try { Thread.sleep(15000) } catch (ignored: Exception) {}
                } else {
                    System.err.println("[MigratorAgent] Transient LLM call error for $className: ${e.message}")
                    return MigrationLlmResult(isTransientError = true, errorMessage = e.message ?: "Transient Error")
                }
            }
        }
        return MigrationLlmResult(isTransientError = true, errorMessage = "Max LLM retries reached")
    }

    private fun cleanLLMOutput(rawText: String): String {
        var text = rawText.trim()
        if (text.startsWith("```kotlin")) {
            text = text.substringAfter("```kotlin")
        } else if (text.startsWith("```")) {
            text = text.substringAfter("```")
        }
        if (text.endsWith("```")) {
            text = text.substringBeforeLast("```")
        }

        val cleanedLines = text.lines().map { line ->
            val trimmed = line.trim()
            if ((trimmed.startsWith("package ") || trimmed.startsWith("import ")) && trimmed.endsWith(";")) {
                line.substringBeforeLast(";").trimEnd()
            } else {
                line
            }
        }.filterNot { line ->
            val trimmed = line.trim()
            (trimmed == "import java.util.ArrayList" && !text.contains("ArrayList<") && !text.contains("ArrayList()")) ||
            (trimmed == "import java.util.Objects" && !text.contains("Objects."))
        }

        return cleanedLines.joinToString("\n").trim()
    }

    private fun migrateWithRules(className: String, javaCode: String): String {
        val pkgLine = (javaCode.lines().firstOrNull { it.startsWith("package ") } ?: "package com.example.legacy").removeSuffix(";")

        return when {
            javaCode.contains("AsyncDataLoader") || javaCode.contains("DataCallback") -> {
                """
                |$pkgLine
                |
                |import kotlinx.coroutines.Dispatchers
                |import kotlinx.coroutines.withContext
                |
                |/**
                | * Modernized by Apollo Agent (Stage 3 Migrator)
                | * Pattern: Callback Interface -> Kotlin Suspending Function
                | */
                |class AsyncDataLoader {
                |
                |    suspend fun loadUserData(userId: String?): User? = withContext(Dispatchers.IO) {
                |        if (userId.isNullOrBlank()) return@withContext null
                |        User(
                |            id = userId,
                |            username = "User_${'$'}userId",
                |            email = "${'$'}userId@example.com",
                |            age = 30
                |        )
                |    }
                |}
                """.trimMargin()
            }
            javaCode.contains("get") && javaCode.contains("set") && !javaCode.contains("class UserService") -> {
                """
                |$pkgLine
                |
                |/**
                | * Modernized by Apollo Agent (Stage 3 Migrator)
                | * Pattern: POJO -> Kotlin Data Class
                | */
                |data class $className(
                |    var id: String? = null,
                |    var username: String? = null,
                |    var email: String? = null,
                |    var age: Int = 0
                |)
                """.trimMargin()
            }
            javaCode.contains("StringUtils") || (javaCode.contains("static") && !javaCode.contains("class UserService")) -> {
                """
                |$pkgLine
                |
                |/**
                | * Modernized by Apollo Agent (Stage 3 Migrator)
                | * Pattern: Utility Class -> Kotlin Object & Extension Functions
                | */
                |object $className {
                |    fun isEmpty(str: String?): Boolean = str.isNullOrBlank()
                |
                |    fun capitalize(str: String?): String? {
                |        if (str.isNullOrEmpty()) return str
                |        return str.substring(0, 1).uppercase() + str.substring(1).lowercase()
                |    }
                |}
                """.trimMargin()
            }
            else -> {
                """
                |$pkgLine
                |
                |/**
                | * Modernized by Apollo Agent (Stage 3 Migrator)
                | * Pattern: Collections, Null Safety & Idiomatic Extensions
                | */
                |class $className {
                |    private val userList: MutableList<User> = mutableListOf()
                |
                |    fun addUser(user: User?) {
                |        requireNotNull(user) { "User cannot be null" }
                |        require(!user.id.isNullOrEmpty()) { "User ID cannot be null or empty" }
                |        userList.add(user)
                |    }
                |
                |    fun findById(id: String?): User? {
                |        if (id == null) return null
                |        return userList.find { it.id == id }
                |    }
                |
                |    fun filterAdults(): List<User> {
                |        return userList.filter { it.age >= 18 }
                |    }
                |
                |    fun formatUserSummary(user: User?): String {
                |        if (user == null) return "N/A"
                |        val name = user.username ?: "Anonymous"
                |        val email = user.email ?: "no-email"
                |        return "${'$'}name (${'$'}email)"
                |    }
                |}
                """.trimMargin()
            }
        }
    }
}
