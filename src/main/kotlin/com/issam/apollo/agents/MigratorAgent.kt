package com.issam.apollo.agents

import ai.koog.agents.core.agent.AIAgent
import ai.koog.agents.core.tools.ToolRegistry
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.executor.llms.MultiLLMPromptExecutor
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.issam.apollo.config.FatalWatchdogAbortException
import com.issam.apollo.config.LlmConfig
import com.issam.apollo.config.MigrationPolicy
import com.issam.apollo.config.LlmProvider as ApolloProvider
import com.issam.apollo.knowledge.MigrationPattern
import com.issam.apollo.knowledge.DeprecationScanner
import com.issam.apollo.knowledge.MigrationPatterns
import com.issam.apollo.telemetry.ApolloTelemetry
import com.issam.apollo.telemetry.ModuleActivityEvent
import com.issam.apollo.state.AgentReport
import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleSpec
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.state.StageStatus
import com.issam.apollo.tools.JavaAstTool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
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
    private val deprecationScanner: DeprecationScanner = DeprecationScanner(),
    private val migratedOutputDir: File = File("").absoluteFile.resolve("migrated-src")
) {

    private val astTool = JavaAstTool()

    private val promptExecutor: MultiLLMPromptExecutor by lazy {
        val client = buildOpenAICompatibleClient()
        MultiLLMPromptExecutor(client)
    }

    private fun buildOpenAICompatibleClient(): OpenAILLMClient {
        val settings = OpenAIClientSettings(
            baseUrl = LlmConfig.ollamaBaseUrl,
            timeoutConfig = ai.koog.prompt.executor.clients.ConnectionTimeoutConfig(),
            chatCompletionsPath = "chat/completions",
            responsesAPIPath = "v1/responses",
            embeddingsPath = "v1/embeddings",
            moderationsPath = "v1/moderations",
            modelsPath = "v1/models"
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
        val newMigratedCode = java.util.concurrent.ConcurrentHashMap<String, String>()
        val newModuleStatuses = java.util.concurrent.ConcurrentHashMap(currentState.moduleStatuses)
        val configErrorModules = java.util.Collections.synchronizedList(mutableListOf<String>())
        val transientErrorModules = java.util.Collections.synchronizedList(mutableListOf<String>())

        // Run all module migrations concurrently — allows small models (7b) and large models (14b)
        // to execute simultaneously without one blocking the others.
        runBlocking {
            moduleOrder.map { className ->
                async {
                    println("[MigratorAgent] ── Migrating module: $className ──")
                    ApolloTelemetry.emit(ModuleActivityEvent(className, "MIGRATING", "generating Kotlin", active = true))

                    // If module is already migrated (e.g. in resume mode), preserve existing code
                    val existingCode = currentState.migratedCode["$className.kt"]
                    if (!existingCode.isNullOrBlank()) {
                        println("[MigratorAgent] Module '$className' already migrated. Preserving existing Kotlin code.")
                        newMigratedCode["$className.kt"] = existingCode
                        newModuleStatuses[className] = currentState.moduleStatuses[className] ?: ModuleStatus.MIGRATED
                        return@async
                    }

                    val spec = currentState.moduleSpecs[className]
                    val javaFilePath = spec?.sourceFilePath
                        ?: currentState.javaFiles.firstOrNull { File(it).nameWithoutExtension == className }

                    if (javaFilePath == null || !File(javaFilePath).exists()) {
                        println("[MigratorAgent] Warning: Source file for $className not found ($javaFilePath). Skipping.")
                        return@async
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

                    // Gather context from dependency signatures if already available
                    val dependencyContext = buildDependencyContext(spec, currentState.migratedCode)

                    val timeoutMs = LlmConfig.moduleTimeoutMs
                    val kotlinCode: String = try {
                        withTimeoutOrNull(timeoutMs) {
                            val llmResult = tryMigrateWithLLMResult(className, spec, javaSource, matchingPatterns, dependencyContext)
                            if (llmResult.code != null) {
                                println("[MigratorAgent] Successfully generated Kotlin code via LLM for $className.")
                                llmResult.code
                            } else if (llmResult.isConfigError) {
                                println("[MigratorAgent] Config/Model 404 error for $className. Using AST/Rule-based engine.")
                                configErrorModules.add(className)
                                migrateWithRules(className, javaSource, spec)
                            } else {
                                println("[MigratorAgent] Transient LLM error for $className. Using AST/Rule-based engine.")
                                transientErrorModules.add(className)
                                migrateWithRules(className, javaSource, spec)
                            }
                        } ?: run {
                            val timeoutSeconds = timeoutMs / 1000
                            println("⚠️ [Timeout] Module '$className' migration exceeded $timeoutSeconds seconds ($timeoutMs ms). Aborting attempt and falling back to rule-based engine.")
                            transientErrorModules.add(className)
                            migrateWithRules(className, javaSource, spec)
                        }
                    } catch (e: FatalWatchdogAbortException) {
                        throw e
                    } catch (e: Exception) {
                        if (e.cause is FatalWatchdogAbortException) throw e.cause as FatalWatchdogAbortException
                        println("[MigratorAgent] Exception during migration for $className: ${e.message}. Using rule-based engine.")
                        transientErrorModules.add(className)
                        migrateWithRules(className, javaSource, spec)
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
            }.awaitAll()
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
        sb.appendLine("The following dependency signatures have already been migrated to Kotlin (for reference):")
        for (dep in spec.dependsOn) {
            val code = currentMigratedCode["$dep.kt"]
            if (code != null) {
                sb.appendLine("--- $dep.kt ---")
                val lines = code.lines()
                if (lines.size <= 40) {
                    sb.appendLine(code)
                } else {
                    // Extract package, imports, class/object/interface lines, val/var properties, and fun signatures
                    val signatureLines = lines.filter { line ->
                        val trimmed = line.trim()
                        trimmed.startsWith("package ") ||
                        trimmed.startsWith("import ") ||
                        trimmed.startsWith("class ") ||
                        trimmed.startsWith("data class ") ||
                        trimmed.startsWith("object ") ||
                        trimmed.startsWith("interface ") ||
                        trimmed.startsWith("fun ") ||
                        trimmed.startsWith("val ") ||
                        trimmed.startsWith("var ") ||
                        trimmed.startsWith("const val ") ||
                        trimmed.startsWith("companion object") ||
                        trimmed == "}"
                    }
                    if (signatureLines.isNotEmpty()) {
                        sb.appendLine(signatureLines.joinToString("\n"))
                    } else {
                        sb.appendLine(code.take(1500) + "\n// ... [truncated for brevity]")
                    }
                }
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
        val patternPrompt = migrationPatterns.formatPatternsForPrompt(patterns)
        // Scoped to this file: only deprecated APIs the source actually uses are described,
        // so the model is never told to modernise something that is not there.
        val deprecationFindings = deprecationScanner.scanSource(javaSource)
        val deprecationPrompt = deprecationScanner.formatForPrompt(deprecationFindings)
        if (deprecationFindings.isNotEmpty()) {
            println(
                "[MigratorAgent] $className: ${deprecationFindings.size} deprecated API(s) to modernise: " +
                    deprecationFindings.joinToString(", ") { it.migration.id }
            )
        }

        val systemPromptStr = """
            You are Apollo, an expert Java-to-Kotlin Migration AI Agent.
            Your task is to transform legacy Java classes into modern, idiomatic Kotlin code.

            Follow these strict guidelines:
            1. Produce ONLY valid, compilable Kotlin code. Do NOT wrap output in markdown backticks or explanations.
            2. Preserve exact package declaration, class hierarchy, method names, and business logic semantics.
            3. Apply Kotlin null safety (?. , ?: , requireNotNull), data classes for POJOs, object/extension functions for utilities, and collection functions (filter, map, find). Declare a return type nullable (e.g. `String?`) ONLY when the Java method has a path that actually returns null. A guard like `if (str.isEmpty()) return str;` returns the empty string, not null - keep that return type non-null and return the original value. Where Java dereferences a parameter without a null check, keep the Kotlin parameter non-nullable so the same NullPointerException still escapes; do not add safe calls that change observable behaviour.
            4. Ensure compatibility with previously migrated Kotlin dependency classes provided in context.
            5. STRICT CLEAN KOTLIN: Do NOT include trailing semicolons (`;`) on package statements, import lines, or code lines. Remove all unused imports (e.g., `import java.util.ArrayList`).
            6. NO REDECLARATIONS: Do NOT redeclare classes (e.g., `User`) that are already provided in dependency context or package scope.
            7. MODERNISE DEPRECATED APIs: This is legacy code. Where it uses an API or library that is deprecated, replace it with the current supported equivalent - do not carry the deprecated call forward. Any deprecated APIs actually present are listed in the user message with their replacements.
            8. ${MigrationPolicy.promptDirective()}
            9. LIBRARY CHOICE IS YOURS: You are NOT required to keep the libraries the Java used. Pick whatever is idiomatic and currently supported for Kotlin. The one constraint is behaviour: the migrated code must still do what the original did, so never swap a library in a way that changes observable behaviour.
        """.trimIndent()

        val userPromptStr = """
            Migrate the following Java class to Kotlin:

            Module: $className
            Package: ${spec?.packageName ?: "default"}
            Dependencies: ${spec?.dependsOn?.joinToString(", ") ?: "none"}

            Curated Migration Patterns to Apply:
            $patternPrompt

            $deprecationPrompt

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

        return try {
            val rawCode = LlmConfig.callLlmWithFallback(userPromptStr, systemPromptStr, temperature = 0.2, moduleName = className)
            MigrationLlmResult(code = cleanLLMOutput(rawCode, className, dependencyContext))
        } catch (e: FatalWatchdogAbortException) {
            throw e
        } catch (e: Exception) {
            if (e.cause is FatalWatchdogAbortException) throw e.cause as FatalWatchdogAbortException
            val msg = e.message ?: ""
            if (LlmConfig.isConfigOr404Error(e)) {
                System.err.println("==========================================================================")
                System.err.println("⚠️ LOUD WARNING [MigratorAgent]: LLM CONFIG / MODEL NOT FOUND ERROR for '$className'!")
                System.err.println("   Details: $msg")
                System.err.println("==========================================================================")
                MigrationLlmResult(isConfigError = true, errorMessage = msg)
            } else {
                System.err.println("[MigratorAgent] LLM call failed for $className: $msg.")
                MigrationLlmResult(isTransientError = true, errorMessage = msg)
            }
        }
    }

    private fun cleanLLMOutput(rawText: String, className: String = "", dependencyContext: String = ""): String {
        var text = rawText.trim()
        if (text.startsWith("```kotlin")) {
            text = text.substringAfter("```kotlin")
        } else if (text.startsWith("```")) {
            text = text.substringAfter("```")
        }
        if (text.endsWith("```")) {
            text = text.substringBeforeLast("```")
        }

        val seenImports = mutableSetOf<String>()
        val cleanedLines = mutableListOf<String>()

        for (line in text.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                cleanedLines.add(line.substringBeforeLast(";").trimEnd())
            } else if (trimmed.startsWith("import ")) {
                val cleanImport = if (trimmed.endsWith(";")) line.substringBeforeLast(";").trimEnd() else line
                val normalizedImport = cleanImport.trim()
                if (normalizedImport.startsWith("import kotlinx.android.synthetic")) continue
                if (normalizedImport == "import java.util.ArrayList" && !text.contains("ArrayList<") && !text.contains("ArrayList()")) continue
                if (normalizedImport == "import java.util.Objects" && !text.contains("Objects.")) continue
                if (seenImports.add(normalizedImport)) {
                    cleanedLines.add(cleanImport)
                }
            } else {
                cleanedLines.add(line)
            }
        }

        var result = cleanedLines.joinToString("\n").trim()

        // Clean duplicate keywords
        result = result
            .replace(Regex("""\bclass\s+class\b"""), "class")
            .replace(Regex("""\bfun\s+fun\b"""), "fun")
            .replace(Regex("""\bval\s+val\b"""), "val")
            .replace(Regex("""\bvar\s+var\b"""), "var")

        return result
    }

    private fun migrateWithRules(className: String, javaCode: String, spec: ModuleSpec? = null): String {
        val packageName = spec?.packageName?.ifBlank { null }
            ?: javaCode.lines().firstOrNull { it.trim().startsWith("package ") }
                ?.removePrefix("package ")?.removeSuffix(";")?.trim()
            ?: "com.issam.apollo.migrated"

        val sb = StringBuilder()
        sb.appendLine("package $packageName")
        sb.appendLine()

        val isActivity = className.endsWith("Activity") ||
            (spec?.imports?.any { it.contains("Activity") } == true) ||
            javaCode.contains("AppCompatActivity") ||
            javaCode.contains("extends Activity")

        val isDbHelper = className.endsWith("OpenHelper") ||
            className.endsWith("DatabaseHelper") ||
            javaCode.contains("SQLiteOpenHelper") ||
            (spec?.imports?.any { it.contains("SQLiteOpenHelper") } == true)

        val safeImports = mutableSetOf<String>()
        if (spec != null && spec.imports.isNotEmpty()) {
            spec.imports.forEach { imp ->
                val cleaned = imp.trim().removePrefix("import ").removeSuffix(";").trim()
                if (cleaned.isNotBlank() && !cleaned.startsWith("kotlinx.android.synthetic")) {
                    safeImports.add(cleaned)
                }
            }
        } else {
            javaCode.lines().filter { it.trim().startsWith("import ") }.forEach { line ->
                val cleaned = line.trim().removePrefix("import ").removeSuffix(";").trim()
                if (cleaned.isNotBlank() && !cleaned.startsWith("kotlinx.android.synthetic")) {
                    safeImports.add(cleaned)
                }
            }
        }

        if (isActivity) {
            safeImports.add("android.os.Bundle")
            if (javaCode.contains("AppCompatActivity") || (spec?.imports?.any { it.contains("AppCompatActivity") } == true)) {
                safeImports.add("androidx.appcompat.app.AppCompatActivity")
            } else {
                safeImports.add("android.app.Activity")
            }
        }

        if (isDbHelper) {
            safeImports.add("android.content.Context")
            safeImports.add("android.database.sqlite.SQLiteDatabase")
            safeImports.add("android.database.sqlite.SQLiteOpenHelper")
        }

        safeImports.filterNot { it.startsWith("kotlinx.android.synthetic") }
            .distinct()
            .forEach { sb.appendLine("import $it") }

        sb.appendLine()
        sb.appendLine("/**")
        sb.appendLine(" * Modernized by Apollo Agent (Stage 3 Migrator Fallback)")
        sb.appendLine(" * Class: $className")
        sb.appendLine(" */")

        val isAppCompat = javaCode.contains("AppCompatActivity") || (spec?.imports?.any { it.contains("AppCompatActivity") } == true)

        if (isActivity) {
            val superType = if (isAppCompat) "AppCompatActivity()" else "Activity()"
            sb.appendLine("open class $className : $superType {")
            sb.appendLine()

            val contentViewMatch = Regex("""setContentView\s*\(\s*(R\.layout\.[a-zA-Z0-9_]+)\s*\)""").find(javaCode)
            val layoutRes = contentViewMatch?.groupValues?.get(1)

            sb.appendLine("    override fun onCreate(savedInstanceState: Bundle?) {")
            sb.appendLine("        super.onCreate(savedInstanceState)")
            if (layoutRes != null) {
                sb.appendLine("        setContentView($layoutRes)")
            }
            sb.appendLine("        // TODO: Apollo fallback stub — initialize views using findViewById")
            sb.appendLine("    }")
            sb.appendLine()

            val nonLifecycleMethods = spec?.methods?.filterNot { it.contains("onCreate(") } ?: emptyList()
            for (rawMethod in nonLifecycleMethods) {
                val methodLines = parseMethodSpec(rawMethod, isActivity = true)
                methodLines.forEach { sb.appendLine("    $it") }
                sb.appendLine()
            }

            sb.appendLine("}")
        } else if (isDbHelper) {
            sb.appendLine("open class $className(context: Context) : SQLiteOpenHelper(context, \"app.db\", null, 1) {")
            sb.appendLine()
            sb.appendLine("    override fun onCreate(db: SQLiteDatabase) {")
            sb.appendLine("        // TODO: Apollo fallback stub — database initialization")
            sb.appendLine("    }")
            sb.appendLine()
            sb.appendLine("    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {")
            sb.appendLine("        // TODO: Apollo fallback stub — database upgrade")
            sb.appendLine("    }")
            sb.appendLine()

            val otherMethods = spec?.methods?.filterNot { it.contains("onCreate(") || it.contains("onUpgrade(") } ?: emptyList()
            for (rawMethod in otherMethods) {
                val methodLines = parseMethodSpec(rawMethod)
                methodLines.forEach { sb.appendLine("    $it") }
                sb.appendLine()
            }

            sb.appendLine("}")
        } else {
            sb.appendLine("open class $className {")
            sb.appendLine()

            if (spec?.fields?.isNotEmpty() == true) {
                for (f in spec.fields) {
                    val decl = parseFieldSpec(f)
                    sb.appendLine("    $decl")
                }
                sb.appendLine()
            }

            if (spec?.methods?.isNotEmpty() == true) {
                for (m in spec.methods) {
                    val methodLines = parseMethodSpec(m)
                    methodLines.forEach { sb.appendLine("    $it") }
                    sb.appendLine()
                }
            }

            sb.appendLine("}")
        }

        return sb.toString().trim()
    }

    private fun parseFieldSpec(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.matches(Regex("""[a-zA-Z_][a-zA-Z0-9_]*\s*:\s*.+"""))) {
            return "var $trimmed = TODO(\"stub\")"
        }
        val javaPattern = Regex("""^(?:(?:private|protected|public|static|final|transient|volatile)\s+)*([\w<>,?\[\]]+)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*$""")
        val match = javaPattern.matchEntire(trimmed)
        if (match != null) {
            val javaType = match.groupValues[1]
            val name = match.groupValues[2]
            val kotlinType = javaTypeToKotlin(javaType)
            return "var $name: $kotlinType? = null"
        }
        return "// $trimmed"
    }

    private fun parseMethodSpec(raw: String, isActivity: Boolean = false): List<String> {
        val trimmed = raw.trim()
        if (trimmed.startsWith("fun ") || trimmed.startsWith("override fun ")) {
            val sigOnly = trimmed.substringBefore("{").trim().substringBefore("=").trim()
            val returnType = if (sigOnly.contains(":")) sigOnly.substringAfterLast(":").trim() else "Unit"
            val isUnit = returnType == "Unit" || returnType.isBlank()
            return if (isUnit) {
                listOf("$sigOnly {", "    TODO(\"Apollo fallback stub — manual migration required\")", "}")
            } else {
                listOf("$sigOnly =", "    TODO(\"Apollo fallback stub — manual migration required\")")
            }
        }
        val javaMethod = Regex("""^(?:(?:private|protected|public|static|final|synchronized|abstract|native)\s+)*([\w<>,?\[\]]+)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*\(([^)]*)\)""")
        val match = javaMethod.find(trimmed)
        if (match != null) {
            val returnTypeJava = match.groupValues[1]
            val name = match.groupValues[2]
            val paramsRaw = match.groupValues[3].trim()
            val kotlinReturn = javaTypeToKotlin(returnTypeJava)
            val isVoid = returnTypeJava == "void" || returnTypeJava == "Void"

            val isOverride = isActivity && name in setOf(
                "onCreateOptionsMenu", "onOptionsItemSelected", "onActivityResult",
                "onResume", "onPause", "onDestroy", "onStart", "onStop", "onBackPressed"
            )
            val funPrefix = if (isOverride) "override fun" else "fun"

            val params = if (paramsRaw.isBlank()) "" else {
                paramsRaw.split(",").joinToString(", ") { param ->
                    val parts = param.trim().split(Regex("\\s+"))
                    if (parts.size >= 2) {
                        val pType = javaTypeToKotlin(parts.dropLast(1).joinToString(" "))
                        val pName = parts.last()
                        "$pName: $pType"
                    } else {
                        param.trim()
                    }
                }
            }

            if (isOverride) {
                return when (name) {
                    "onCreateOptionsMenu" -> listOf("override fun onCreateOptionsMenu(menu: android.view.Menu): Boolean = super.onCreateOptionsMenu(menu)")
                    "onOptionsItemSelected" -> listOf("override fun onOptionsItemSelected(item: android.view.MenuItem): Boolean = super.onOptionsItemSelected(item)")
                    "onActivityResult" -> listOf("override fun onActivityResult(requestCode: Int, resultCode: Int, data: android.content.Intent?) {", "    super.onActivityResult(requestCode, resultCode, data)", "}")
                    "onResume", "onPause", "onDestroy", "onStart", "onStop", "onBackPressed" -> listOf("override fun $name() {", "    super.$name()", "}")
                    else -> listOf("override fun $name($params) {", "    TODO(\"Apollo fallback stub — manual migration required\")", "}")
                }
            }

            return if (isVoid) {
                listOf(
                    "$funPrefix $name($params) {",
                    "    TODO(\"Apollo fallback stub — manual migration required\")",
                    "}"
                )
            } else {
                listOf(
                    "$funPrefix $name($params): $kotlinReturn =",
                    "    TODO(\"Apollo fallback stub — manual migration required\")"
                )
            }
        }
        return listOf("// $trimmed")
    }

    private fun javaTypeToKotlin(javaType: String): String {
        val stripped = javaType.trim().removeSuffix("[]")
        val isArray = javaType.trim().endsWith("[]")
        val kotlin = when (stripped.lowercase()) {
            "int", "integer"         -> "Int"
            "long"                   -> "Long"
            "double"                 -> "Double"
            "float"                  -> "Float"
            "boolean"                -> "Boolean"
            "char", "character"      -> "Char"
            "byte"                   -> "Byte"
            "short"                  -> "Short"
            "void"                   -> "Unit"
            "string"                 -> "String"
            "object"                 -> "Any"
            "list"                   -> "List<Any>"
            "arraylist"              -> "MutableList<Any>"
            "map"                    -> "Map<Any, Any>"
            "hashmap"                -> "HashMap<Any, Any>"
            "set"                    -> "Set<Any>"
            "hashset"                -> "HashSet<Any>"
            else                     -> stripped
        }
        return if (isArray) "Array<$kotlin>" else kotlin
    }
}
