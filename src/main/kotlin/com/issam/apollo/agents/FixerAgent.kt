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
import com.issam.apollo.config.LlmProvider as ApolloProvider
import com.issam.apollo.state.AgentReport
import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.state.StageStatus
import com.issam.apollo.telemetry.ApolloTelemetry
import com.issam.apollo.telemetry.FileEvent
import com.issam.apollo.telemetry.ModuleActivityEvent
import com.issam.apollo.tools.EnvironmentPreflight
import com.issam.apollo.tools.JavaAstTool
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.security.MessageDigest
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
    private val migratedOutputDir: File = File("").absoluteFile.resolve("migrated-src"),
    val maxRetriesPerModule: Int = LlmConfig.maxRetryCount,
    /**
     * Seam over [LlmConfig.callLlmWithFallback] so the repair loop can be exercised
     * deterministically in unit tests (including provider-outage behaviour) without
     * dispatching live inference.
     */
    private val llmCall: (String, String, Double, String, Int) -> String =
        { prompt, systemPrompt, temperature, moduleName, attemptCount ->
            LlmConfig.callLlmWithFallback(prompt, systemPrompt, temperature, moduleName, attemptCount)
        }
) {

    companion object {
        private val modulePersistentErrorHistories = java.util.concurrent.ConcurrentHashMap<String, MutableList<String>>()

        fun resetErrorHistories() {
            modulePersistentErrorHistories.clear()
        }

        fun hasDirectErrorsOrFailures(
            className: String,
            verification: com.issam.apollo.state.VerificationResult
        ): Boolean {
            val inCompilerErrors = verification.compilerErrors.any { err ->
                err.contains("$className.kt", ignoreCase = true) ||
                err.contains("$className.java", ignoreCase = true) ||
                err.contains("/$className.kt", ignoreCase = true) ||
                err.contains("\\$className.kt", ignoreCase = true) ||
                err.contains("/$className.java", ignoreCase = true) ||
                err.contains("\\$className.java", ignoreCase = true)
            }
            val inTestFailures = verification.testFailures.any { test ->
                test.startsWith("$className.") ||
                test.startsWith("$className#") ||
                test.startsWith("$className:") ||
                test.startsWith("$className ") ||
                test == className
            }
            return inCompilerErrors || inTestFailures
        }
    }

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

    private data class FixerLlmResult(
        val code: String? = null,
        val isConfigError: Boolean = false,
        val isTransientError: Boolean = false,
        val errorMessage: String = ""
    )

    data class CompilerDiagnostic(
        val filePath: String = "",
        val fileName: String = "",
        val lineNumber: Int? = null,
        val columnNumber: Int? = null,
        val severity: String = "error",
        val message: String = "",
        val rawLines: List<String> = emptyList()
    ) {
        fun formatForPrompt(index: Int, total: Int): String = buildString {
            appendLine("--- Error $index of $total ---")
            val loc = if (lineNumber != null) {
                if (columnNumber != null) "Line $lineNumber, Column $columnNumber" else "Line $lineNumber"
            } else "Location unspecified"
            val fileDesc = if (fileName.isNotBlank()) " in $fileName" else if (filePath.isNotBlank()) " in $filePath" else ""
            appendLine("Location: $loc$fileDesc")
            appendLine("Message: $message")
            if (rawLines.size > 1) {
                val details = rawLines.drop(1).joinToString("\n")
                appendLine("Code Snippet & Diagnostic Context:")
                appendLine(details)
            }
        }
    }

    fun execute(state: GraphState): GraphState {
        println("[FixerAgent] Starting Stage 5 Self-Healing Repair...")

        val verification = state.verificationResult
        if (verification.compiledSuccessfully && verification.testsFailed == 0) {
            println("[FixerAgent] Verification already passed. No fixes required.")
            return state
        }

        // Identify only modules that actually have errors or test failures
        val failedModuleNames = state.moduleSpecs.keys.filter { className ->
            hasDirectErrorsOrFailures(className, verification)
        }.toSet()

        if (failedModuleNames.isEmpty()) {
            println("[FixerAgent] No modules have active compiler errors or test failures. Skipping repair.")
            return state
        }

        println("[FixerAgent] Modules requiring repair (with active errors): $failedModuleNames")

        var currentState = state
        // Thread-safe aggregators (modules repair concurrently below)
        val updatedMigratedCode = java.util.concurrent.ConcurrentHashMap(currentState.migratedCode)
        val updatedStatuses = java.util.concurrent.ConcurrentHashMap(currentState.moduleStatuses)
        val updatedRegenCounters = java.util.concurrent.ConcurrentHashMap(currentState.regenCounters)
        val configErrorModules = java.util.Collections.synchronizedList(mutableListOf<String>())
        val transientErrorModules = java.util.Collections.synchronizedList(mutableListOf<String>())
        val repairedModuleList = java.util.Collections.synchronizedList(mutableListOf<String>())
        // Modules whose patch attempt produced byte-identical source (LLM outage, unmatched
        // SEARCH block, or a no-op patch). Re-verifying these is provably pointless.
        val noopModuleList = java.util.Collections.synchronizedList(mutableListOf<String>())
        val fixedCount = java.util.concurrent.atomic.AtomicInteger(0)
        val cappedCount = java.util.concurrent.atomic.AtomicInteger(0)

        // Run all module repairs concurrently — each module gets its own coroutine so one
        // slow/hung Ollama call cannot block the others.
        runBlocking {
            failedModuleNames.map { className ->
                async {
                    try {
                        val currentAttempts = updatedRegenCounters[className] ?: 0

                        if (currentAttempts >= maxRetriesPerModule) {
                            println("[FixerAgent] Warning: Module '$className' reached max retry cap ($maxRetriesPerModule attempts). Marking FAILED.")
                            updatedStatuses[className] = ModuleStatus.FAILED
                            cappedCount.incrementAndGet()
                            return@async
                        }

                        val (failureLogs, issueCount) = gatherFailureLogsForModule(className, verification)

                        // Strictly ignore modules with 0 direct errors or test failures
                        if (issueCount == 0 && !hasDirectErrorsOrFailures(className, verification)) {
                            println("[FixerAgent] Module '$className' reports 0 errors. Preserving existing code.")
                            return@async
                        }

                        // ── Persistent Error Watchdog (Stop if same error persists across 5 attempts) ──
                        val primaryError = extractPrimaryErrorSignature(className, verification)
                        if (primaryError.isNotBlank()) {
                            val errorHistory = modulePersistentErrorHistories.computeIfAbsent(className) {
                                java.util.Collections.synchronizedList(mutableListOf<String>())
                            }
                            errorHistory.add(primaryError)
                            if (errorHistory.size >= 5 && errorHistory.takeLast(5).all { err -> err == primaryError }) {
                                val fatalMsg = "[FATAL ERROR PERSISTENCE] The same error in module '$className' persisted across 5 consecutive repair attempts without resolution:\n   \"$primaryError\"\n   Halting execution to be looked at by the developer."
                                System.err.println("==========================================================================")
                                System.err.println(fatalMsg)
                                System.err.println("==========================================================================")
                                throw FatalWatchdogAbortException(fatalMsg)
                            }
                        }

                        val newAttemptCount = currentAttempts + 1
                        updatedRegenCounters[className] = newAttemptCount
                        println("[FixerAgent] -- Targeted Patching for module: $className (Attempt #$newAttemptCount/$maxRetriesPerModule) --")
                        ApolloTelemetry.emit(
                            ModuleActivityEvent(className, "FIXING", "attempt $newAttemptCount/$maxRetriesPerModule", active = true)
                        )

                        val currentKotlin = updatedMigratedCode["$className.kt"] ?: ""
                        val spec = currentState.moduleSpecs[className]
                        val otherModuleNames = (currentState.moduleSpecs.keys - className).filter { it.isNotBlank() }.toSet()
                        val timeoutMs = LlmConfig.moduleTimeoutMs

                        val patchedKotlin: String = withTimeoutOrNull(timeoutMs) {
                            val llmResult = tryFixWithLLMResult(className, spec, currentKotlin, failureLogs, otherModuleNames, attemptCount = currentAttempts)
                            if (llmResult.code != null) {
                                cleanLLMOutput(llmResult.code, className, otherModuleNames)
                            } else if (llmResult.isConfigError) {
                                println("[FixerAgent] Config/Model 404 error for $className. Cleaning and preserving code.")
                                configErrorModules.add(className)
                                cleanLLMOutput(currentKotlin, className, otherModuleNames)
                            } else {
                                println("[FixerAgent] Transient LLM patch error for $className. Cleaning and preserving code for next attempt.")
                                transientErrorModules.add(className)
                                cleanLLMOutput(currentKotlin, className, otherModuleNames)
                            }
                        } ?: run {
                            val timeoutSeconds = timeoutMs / 1000
                            println("[Timeout] Module '$className' repair exceeded $timeoutSeconds seconds ($timeoutMs ms). Aborting attempt and cleaning preserved code.")
                            transientErrorModules.add(className)
                            cleanLLMOutput(currentKotlin, className, otherModuleNames)
                        }

                        // ── Hash-before-write audit logging ──────────────────────────────────────
                        val patchedHash = sha256(patchedKotlin)
                        val oldHash = sha256(currentKotlin)
                        val patchApplied = patchedHash != oldHash
                        println("[FixerAgent] [Audit] '$className.kt': " +
                            "lines=${patchedKotlin.lines().size}, sha256=${patchedHash.take(12)}" +
                            (if (patchedHash == oldHash) " [UNCHANGED (patch had no effect)]" else " [CHANGED (prev sha=${oldHash.take(12)})]"))

                        // Persist fixed Kotlin source file (always overwrite target file)
                        val packagePath = (spec?.packageName ?: "").replace('.', '/')
                        val targetFolder = if (packagePath.isNotBlank()) File(migratedOutputDir, packagePath) else migratedOutputDir
                        targetFolder.mkdirs()
                        val targetFile = File(targetFolder, "$className.kt")
                        targetFile.writeText(patchedKotlin)
                        ApolloTelemetry.emit(FileEvent(targetFile.absolutePath, "migrated-kotlin", patchedKotlin.lines().size))
                        ApolloTelemetry.emit(ModuleActivityEvent(className, "FIXING", "patch written", active = false))

                        // Also clear any stale copy from sandbox-migrated-temp so the verifier
                        // always compiles from state.migratedCode (written by compileKotlinFiles),
                        // not from a leftover old file in the temp dir.
                        val sandboxTempDir = File("").absoluteFile.resolve("build/sandbox-migrated-temp")
                        val packageRelPath = if (packagePath.isNotBlank()) "$packagePath/$className.kt" else "$className.kt"
                        val staleTemp = File(sandboxTempDir, packageRelPath)
                        if (staleTemp.exists()) {
                            staleTemp.delete()
                            println("[FixerAgent] Deleted stale sandbox temp file: ${staleTemp.path}")
                        }

                        // Clean up stale duplicate file at the root if saved in a package subdirectory
                        if (packagePath.isNotBlank()) {
                            val staleRootFile = File(migratedOutputDir, "$className.kt")
                            if (staleRootFile.exists() && staleRootFile.absolutePath != targetFile.absolutePath) {
                                staleRootFile.delete()
                            }
                        }

                        updatedMigratedCode["$className.kt"] = patchedKotlin

                        // Only a patch that actually altered the source counts as a repair.
                        // Counting a preserved-as-is module as "repaired" makes the orchestrator
                        // re-run Stage 4 over byte-identical code, which is guaranteed to yield an
                        // identical error count and falsely trips the LLM bottleneck detector.
                        if (patchApplied) {
                            repairedModuleList.add(className)
                            fixedCount.incrementAndGet()
                        } else {
                            noopModuleList.add(className)
                        }
                    } catch (e: FatalWatchdogAbortException) {
                        throw e
                    } catch (e: Exception) {
                        if (e.cause is FatalWatchdogAbortException) throw e.cause as FatalWatchdogAbortException
                        println("[FixerAgent] Unexpected exception repairing $className: ${e.message}. Applying fallback.")
                        transientErrorModules.add(className)
                        val currentKotlin = updatedMigratedCode["$className.kt"] ?: ""
                        val spec = currentState.moduleSpecs[className]
                        val otherModuleNames = (currentState.moduleSpecs.keys - className).filter { it.isNotBlank() }.toSet()
                        val fallbackCode = applyRuleBasedFix(currentKotlin, verification.compilerErrors, className, otherModuleNames, spec)
                        updatedMigratedCode["$className.kt"] = fallbackCode
                        if (fallbackCode != currentKotlin) {
                            repairedModuleList.add(className)
                            fixedCount.incrementAndGet()
                        } else {
                            noopModuleList.add(className)
                        }
                    }
                }
            }.awaitAll()
        }

        val fixedCountInt = fixedCount.get()
        val cappedCountInt = cappedCount.get()
        val noopCountInt = noopModuleList.size

        if (noopCountInt > 0) {
            println("[FixerAgent] $noopCountInt module(s) produced no source change this pass: ${noopModuleList.joinToString(", ")}")
        }

        val reportMetrics = mutableMapOf(
            "repairedModules"               to fixedCountInt.toString(),
            "repairedModuleList"            to repairedModuleList.joinToString(","),
            "noopModules"                   to noopCountInt.toString(),
            "noopModuleList"                to noopModuleList.joinToString(","),
            "cappedModules"                 to cappedCountInt.toString(),
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
                append("Applied targeted fixes to $fixedCountInt module(s). $noopCountInt module(s) were left byte-identical (no effective patch). $cappedCountInt module(s) reached max retry cap ($maxRetriesPerModule).")
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

    /** Returns the first 12 hex chars of SHA-256 of [text] for audit logging. */
    private fun sha256(text: String): String {
        val bytes = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return bytes.joinToString("") { "%02x".format(it) }
    }

    internal fun parseCompilerDiagnostics(compilerLogs: List<String>): List<CompilerDiagnostic> {
        val diagnostics = mutableListOf<CompilerDiagnostic>()
        var currentDiagnostic: CompilerDiagnostic? = null
        val currentLines = mutableListOf<String>()

        fun flushCurrent() {
            if (currentDiagnostic != null) {
                diagnostics.add(currentDiagnostic!!.copy(rawLines = currentLines.toList()))
                currentDiagnostic = null
                currentLines.clear()
            }
        }

        // Regex matching standard Kotlin compiler errors: e.g. "build/sandbox-migrated-temp/MainActivity.kt:26:27: error: unresolved reference 'title'."
        val diagnosticRegex = Regex("""^(?:[a-zA-Z]:[\\/])?([^:]+):(\d+):(?:(\d+):)?\s*(error|warning):\s*(.*)$""", RegexOption.IGNORE_CASE)

        for (rawLine in compilerLogs) {
            val line = rawLine.trim()
            if (line.isBlank()) continue

            val match = diagnosticRegex.find(line)
            if (match != null) {
                flushCurrent()
                val fullPath = match.groupValues[1]
                val fileName = File(fullPath).name
                val lineNum = match.groupValues[2].toIntOrNull()
                val colNum = match.groupValues[3].toIntOrNull()
                val severity = match.groupValues[4].lowercase()
                val message = match.groupValues[5]

                currentDiagnostic = CompilerDiagnostic(
                    filePath = fullPath,
                    fileName = fileName,
                    lineNumber = lineNum,
                    columnNumber = colNum,
                    severity = severity,
                    message = message
                )
                currentLines.add(line)
            } else {
                if (currentDiagnostic != null) {
                    currentLines.add(line)
                }
            }
        }
        flushCurrent()
        return diagnostics
    }

    internal fun gatherFailureLogsForModule(
        className: String,
        verification: com.issam.apollo.state.VerificationResult
    ): Pair<String, Int> {
        // Environment faults (missing stdlib, missing android.jar) are not the model's to fix.
        // Feeding them to the LLM produces confident nonsense and wastes the retry budget.
        val actionableErrors = verification.compilerErrors.filterNot { EnvironmentPreflight.isEnvironmentError(it) }
        val diagnostics = parseCompilerDiagnostics(actionableErrors)
        val moduleDiagnostics = diagnostics.filter { diag ->
            diag.fileName.equals("$className.kt", ignoreCase = true) ||
            diag.fileName.equals("$className.java", ignoreCase = true) ||
            diag.filePath.endsWith("/$className.kt", ignoreCase = true) ||
            diag.filePath.endsWith("\\$className.kt", ignoreCase = true) ||
            diag.filePath.contains("/$className.kt") ||
            diag.filePath.contains("\\$className.kt") ||
            diag.rawLines.any { line ->
                line.contains("$className.kt", ignoreCase = true) ||
                line.contains("$className.java", ignoreCase = true)
            }
        }

        val testFailures = verification.testFailures.filter { test ->
            test.startsWith("$className.") ||
            test.startsWith("$className#") ||
            test.startsWith("$className:") ||
            test.startsWith("$className ") ||
            test == className
        }
        val totalIssues = moduleDiagnostics.size + testFailures.size

        val sb = StringBuilder()
        if (moduleDiagnostics.isNotEmpty()) {
            sb.appendLine("=== COMPILER ERRORS FOR $className (${moduleDiagnostics.size} error(s) detected) ===")
            sb.appendLine("CRITICAL INSTRUCTION: You must fix ALL ${moduleDiagnostics.size} error(s) below in this single pass. Do not stop after fixing only the first error.")
            sb.appendLine()
            moduleDiagnostics.forEachIndexed { index, diag ->
                sb.appendLine(diag.formatForPrompt(index + 1, moduleDiagnostics.size))
            }
        } else if (verification.compilerErrors.isNotEmpty()) {
            val generalErrors = diagnostics.filter { it.fileName.isBlank() }
            if (generalErrors.isNotEmpty()) {
                sb.appendLine("=== COMPILATION ERRORS (${generalErrors.size} error(s) detected) ===")
                generalErrors.forEachIndexed { index, diag ->
                    sb.appendLine(diag.formatForPrompt(index + 1, generalErrors.size))
                }
            }
            // Deliberately NOT dumping the whole project's compiler output here. Doing so fed
            // one module's errors into another module's prompt - which is how a patch aimed at
            // MainActivity ended up rewriting SplashActivity.
        }

        if (testFailures.isNotEmpty()) {
            sb.appendLine("=== CHARACTERIZATION TEST FAILURES FOR $className (${testFailures.size} failure(s)) ===")
            testFailures.forEach {
                sb.appendLine(" - $it")
            }
        }

        val logs = sb.toString().trim().ifBlank { "Unspecified compilation or runtime verification error." }
        return Pair(logs, totalIssues)
    }

    internal fun applySearchReplacePatch(originalCode: String, patchResponse: String): String {
        // Match <<<< SEARCH ... ==== ... >>>> REPLACE with 3 or more brackets/equals
        val blockRegex = Regex(
            """<{3,}\s*SEARCH\s*\r?\n([\s\S]*?)\r?\n?={3,}\s*\r?\n([\s\S]*?)\r?\n?>{3,}\s*REPLACE""",
            RegexOption.IGNORE_CASE
        )

        val matches = blockRegex.findAll(patchResponse).toList()
        if (matches.isEmpty()) {
            return originalCode
        }

        var result = originalCode

        for (match in matches) {
            val searchContent = match.groupValues[1]
            val replaceContent = match.groupValues[2]

            if (searchContent.isBlank() && replaceContent.isBlank()) continue

            // 1. Direct exact replacement
            if (result.contains(searchContent)) {
                result = result.replaceFirst(searchContent, replaceContent)
                continue
            }

            // 2. Normalized line endings replacement (CRLF / LF)
            val normalizedResult = result.replace("\r\n", "\n")
            val normalizedSearch = searchContent.replace("\r\n", "\n")
            val normalizedReplace = replaceContent.replace("\r\n", "\n")

            if (normalizedResult.contains(normalizedSearch)) {
                result = normalizedResult.replaceFirst(normalizedSearch, normalizedReplace)
                continue
            }

            // 3. Line-by-line whitespace-tolerant match
            val searchLines = normalizedSearch.lines().map { it.trimEnd() }
            val docLines = result.lines()
            var matchStartIdx = -1

            for (i in 0..docLines.size - searchLines.size) {
                val candidateSlice = docLines.subList(i, i + searchLines.size).map { it.trimEnd() }
                if (candidateSlice == searchLines) {
                    matchStartIdx = i
                    break
                }
            }

            if (matchStartIdx != -1) {
                val newLines = mutableListOf<String>()
                newLines.addAll(docLines.subList(0, matchStartIdx))
                newLines.addAll(normalizedReplace.lines())
                newLines.addAll(docLines.subList(matchStartIdx + searchLines.size, docLines.size))
                result = newLines.joinToString("\n")
                continue
            }

            // 4. Fuzzy trimmed line match
            val fuzzySearchLines = normalizedSearch.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (fuzzySearchLines.isNotEmpty()) {
                val trimmedDocLines = docLines.map { it.trim() }
                var fuzzyStart = -1
                var fuzzyEnd = -1

                for (i in docLines.indices) {
                    if (trimmedDocLines[i] == fuzzySearchLines.first()) {
                        var matchedCount = 0
                        var j = i
                        while (j < docLines.size && matchedCount < fuzzySearchLines.size) {
                            if (trimmedDocLines[j].isNotBlank()) {
                                if (trimmedDocLines[j] == fuzzySearchLines[matchedCount]) {
                                    matchedCount++
                                } else {
                                    break
                                }
                            }
                            j++
                        }
                        if (matchedCount == fuzzySearchLines.size) {
                            fuzzyStart = i
                            fuzzyEnd = j
                            break
                        }
                    }
                }

                if (fuzzyStart != -1 && fuzzyEnd != -1) {
                    val newLines = mutableListOf<String>()
                    newLines.addAll(docLines.subList(0, fuzzyStart))
                    newLines.addAll(normalizedReplace.lines())
                    newLines.addAll(docLines.subList(fuzzyEnd, docLines.size))
                    result = newLines.joinToString("\n")
                    continue
                }
            }

            // 5. Special case: If replaceContent is purely import statements, insert after package or first import
            val replaceLines = normalizedReplace.lines().map { it.trim() }.filter { it.isNotBlank() }
            if (replaceLines.isNotEmpty() && replaceLines.all { it.startsWith("import ") }) {
                val lines = result.lines().toMutableList()
                val pkgIdx = lines.indexOfLast { it.trim().startsWith("package ") }
                val lastImportIdx = lines.indexOfLast { it.trim().startsWith("import ") }
                val insertIdx = if (lastImportIdx != -1) lastImportIdx + 1 else if (pkgIdx != -1) pkgIdx + 1 else 0
                lines.addAll(insertIdx, replaceLines)
                result = lines.joinToString("\n")
            } else {
                println("[FixerAgent] Notice: Could not locate SEARCH block in source: '${searchContent.take(60).replace('\n', ' ')}...'")
            }
        }

        return result
    }

    private fun tryFixWithLLM(
        className: String,
        spec: com.issam.apollo.state.ModuleSpec?,
        brokenKotlin: String,
        failureLogs: String,
        attemptCount: Int = 0
    ): String? {
        return tryFixWithLLMResult(className, spec, brokenKotlin, failureLogs, emptySet(), attemptCount).code
    }

    private fun tryFixWithLLMResult(
        className: String,
        spec: com.issam.apollo.state.ModuleSpec?,
        brokenKotlin: String,
        failureLogs: String,
        otherModuleNames: Set<String>,
        attemptCount: Int = 0
    ): FixerLlmResult {
        val otherModulesDesc = if (otherModuleNames.isNotEmpty()) {
            "Note: The following classes exist in separate files in the same project/package: ${otherModuleNames.joinToString(", ")}. Do NOT declare or redefine them."
        } else ""

        val systemPromptStr = """
            You are Apollo Fixer Agent, an expert Kotlin compiler and refactoring assistant.
            Your task is to fix specific errors in the given Kotlin source file by providing targeted SEARCH/REPLACE patch blocks.

            STRICT INSTRUCTIONS:
            1. Output ONLY SEARCH/REPLACE blocks. DO NOT output the full file.
            2. Format each block exactly as:
               <<<<<<< SEARCH
               [exact lines to replace from the current file]
               =======
               [new replacement lines]
               >>>>>>> REPLACE

            3. Fix ALL reported errors in the failure logs for $className:
               - Fix compilation, syntax errors, and failing logic precisely according to the failure logs.
               - Add necessary missing imports.
               - Match the ground-truth expectation EXACTLY as reported. The expected value is the observed behaviour of the original Java method - never assume a convention.
               - Only widen a return type to nullable (e.g. `String?`) when the failure log actually shows an expected value of 'null'. Do NOT make a signature nullable to silence a type error.
               - Never invent a null return the Java did not have. If the log shows expected '' (empty string) or expected '   ' (blank) but you returned 'null', return the ORIGINAL argument unchanged instead of null - a Java guard like `if (s.isEmpty()) return s;` returns the empty string, not null.
               - If the log shows an expected NullPointerException but you returned a value, the original Java dereferenced that parameter with no null check. Preserve that: keep the parameter non-nullable, and do NOT add `?.`, `?: return null`, or a null guard that swallows the exception.
               - If this is an Android Activity/Service/View: ensure correct Android imports (e.g. `import android.view.WindowManager`, `import android.content.Intent`, `import androidx.appcompat.app.AppCompatActivity`), generic types for findViewById (e.g. `findViewById<EditText>(R.id.foo)`), and proper property access. Do NOT add Android imports or lifecycle methods to regular non-Android utility, model, or service classes.
               - Ensure variance and type parameters are valid Kotlin (e.g. avoid 'out' on method parameter types).
               - Remove semicolons (`;`) on package or import statements.
               - Ensure variable and method signatures match properly.
            4. Make minimal, targeted changes. Do NOT rewrite unaffected code.
            5. Do NOT include explanatory text or markdown backticks outside the SEARCH/REPLACE blocks.
            $otherModulesDesc
        """.trimIndent()

        val userPromptStr = """
            Apply targeted patches to resolve ALL errors for module $className:

            Module: $className
            Package: ${spec?.packageName ?: "default"}

            $failureLogs

            Current Kotlin Source Code:
            ```kotlin
            $brokenKotlin
            ```

            Return ONLY the <<<<<<< SEARCH ... ======= ... >>>>>>> REPLACE blocks to fix all reported errors.
        """.trimIndent()

        return try {
            val rawOutput = llmCall(userPromptStr, systemPromptStr, 0.1, className, attemptCount)
            val patched = applySearchReplacePatch(brokenKotlin, rawOutput)
            val isSearchReplaceOutput = rawOutput.contains("SEARCH") && rawOutput.contains("REPLACE")

            val finalCode = if (patched != brokenKotlin) {
                cleanLLMOutput(patched, className, otherModuleNames)
            } else if (!isSearchReplaceOutput && (rawOutput.contains("package ") || rawOutput.contains("class $className") || rawOutput.contains("object $className"))) {
                // LLM returned full source code
                cleanLLMOutput(rawOutput, className, otherModuleNames)
            } else {
                null
            }

            if (finalCode != null) {
                FixerLlmResult(code = finalCode)
            } else {
                FixerLlmResult(isTransientError = true, errorMessage = "Patch could not be matched or applied to current code")
            }
        } catch (e: FatalWatchdogAbortException) {
            throw e
        } catch (e: Exception) {
            if (e.cause is FatalWatchdogAbortException) throw e.cause as FatalWatchdogAbortException
            val msg = e.message ?: ""
            if (LlmConfig.isConfigOr404Error(e)) {
                System.err.println("==========================================================================")
                System.err.println("⚠️ LOUD WARNING [FixerAgent]: LLM CONFIG / MODEL NOT FOUND ERROR for '$className'!")
                System.err.println("   Details: $msg")
                System.err.println("==========================================================================")
                FixerLlmResult(isConfigError = true, errorMessage = msg)
            } else {
                System.err.println("[FixerAgent] LLM call failed for $className: $msg.")
                FixerLlmResult(isTransientError = true, errorMessage = msg)
            }
        }
    }

    internal fun cleanLLMOutput(
        rawText: String,
        className: String = "",
        otherModuleNames: Set<String> = emptySet()
    ): String {
        var text = rawText.trim()

        // 1. Extract Kotlin code from markdown code fences if present
        val codeFenceRegex = Regex("""```(?:kotlin)?\s*\n?([\s\S]*?)```""", RegexOption.IGNORE_CASE)
        val matches = codeFenceRegex.findAll(text).map { it.groupValues[1].trim() }.filter { it.isNotBlank() }.toList()

        text = when {
            matches.isEmpty() -> {
                var stripped = text
                if (stripped.startsWith("```kotlin", ignoreCase = true)) {
                    stripped = stripped.substringAfter("```kotlin", "").ifEmpty { stripped.substringAfter("```KOTLIN", "") }
                } else if (stripped.startsWith("```")) {
                    stripped = stripped.substringAfter("```")
                }
                if (stripped.endsWith("```")) {
                    stripped = stripped.substringBeforeLast("```")
                }
                val lines = stripped.lines()
                val firstCodeLineIdx = lines.indexOfFirst { line ->
                    val t = line.trim()
                    t.startsWith("package ") || t.startsWith("import ") ||
                    t.contains(Regex("""\b(?:class|interface|object|data class)\b"""))
                }
                if (firstCodeLineIdx > 0) {
                    lines.subList(firstCodeLineIdx, lines.size).joinToString("\n")
                } else {
                    stripped
                }
            }
            matches.size == 1 -> matches.first()
            else -> {
                // If multiple blocks, find the block containing the target class definition, or take the longest
                if (className.isNotBlank()) {
                    matches.firstOrNull { block ->
                        block.contains("class $className") ||
                        block.contains("object $className") ||
                        block.contains("interface $className")
                    } ?: matches.maxByOrNull { it.length } ?: matches.first()
                } else {
                    matches.maxByOrNull { it.length } ?: matches.first()
                }
            }
        }

        // 2. Post-process lines: remove semicolons on package/import, deduplicate imports
        val lines = text.lines()
        val cleanedLines = mutableListOf<String>()
        val seenImports = mutableSetOf<String>()

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("import kotlinx.android.synthetic")) {
                // Strip deprecated synthetic imports that cause compilation failures
                continue
            }
            if (trimmed.startsWith("package ") && trimmed.endsWith(";")) {
                cleanedLines.add(line.substringBeforeLast(";").trimEnd())
            } else if (trimmed.startsWith("import ")) {
                val cleanImport = if (trimmed.endsWith(";")) line.substringBeforeLast(";").trimEnd() else line
                val normalizedImport = cleanImport.trim()
                if (seenImports.add(normalizedImport)) {
                    cleanedLines.add(cleanImport)
                }
            } else {
                cleanedLines.add(line)
            }
        }

        var result = cleanedLines.joinToString("\n").trim()

        // Clean up duplicate keywords
        result = result
            .replace(Regex("""\bclass\s+class\b"""), "class")
            .replace(Regex("""\bfun\s+fun\b"""), "fun")
            .replace(Regex("""\bval\s+val\b"""), "val")
            .replace(Regex("""\bvar\s+var\b"""), "var")

        // Collapse annotations duplicated by an over-eager SEARCH/REPLACE patch
        result = deduplicateAnnotations(result)

        // 3. Remove accidental redeclarations of other top-level classes from other modules
        if (otherModuleNames.isNotEmpty()) {
            val forbidden = otherModuleNames.filter { it != className && it.isNotBlank() }.toSet()
            result = removeTopLevelDeclarations(result, forbidden)
        }

        return result
    }

    /**
     * Collapses annotations that a patch duplicated onto the same declaration.
     *
     * A REPLACE block that re-emits an annotation its SEARCH block never consumed leaves the
     * source with, for example, two consecutive `@JvmStatic` lines. Kotlin rejects that with
     * "this annotation is not repeatable" - a compile error the fixer introduced itself, which
     * then costs a full verification pass to discover.
     *
     * Duplicates are matched on exact text, so distinct annotations - and repeatable ones
     * carrying different arguments - are always preserved. A run of annotations ends at the
     * next non-blank, non-annotation line, so each declaration is deduplicated independently.
     */
    internal fun deduplicateAnnotations(source: String): String {
        val annotationLineRegex = Regex("""^@[\w:.]+(\s*\([^)]*\))?$""")
        val inlineRepeatRegex = Regex("""(@[\w:.]+)(\s+\1)+(?![\w(])""")

        val resultLines = mutableListOf<String>()
        val seenInRun = mutableSetOf<String>()

        for (rawLine in source.lines()) {
            // `@Foo @Foo fun x()` on a single line
            val line = inlineRepeatRegex.replace(rawLine) { it.groupValues[1] }
            val trimmed = line.trim()

            if (annotationLineRegex.matches(trimmed)) {
                if (seenInRun.add(trimmed)) resultLines.add(line)
            } else {
                if (trimmed.isNotEmpty()) seenInRun.clear()
                resultLines.add(line)
            }
        }

        return resultLines.joinToString("\n")
    }

    internal fun removeTopLevelDeclarations(source: String, forbiddenNames: Set<String>): String {
        if (forbiddenNames.isEmpty()) return source

        val lines = source.lines()
        val resultLines = mutableListOf<String>()
        var skipDeclaration = false
        var braceDepth = 0

        val declHeaderRegex = Regex("""^(?:@\w+(?:\([^)]*\))?\s*)*(?:public\s+|internal\s+|private\s+)?(?:data\s+|open\s+|abstract\s+|sealed\s+)?(?:class|interface|object)\s+(\w+)\b""")

        for (line in lines) {
            val trimmed = line.trim()

            if (!skipDeclaration) {
                val match = declHeaderRegex.find(trimmed)
                if (match != null) {
                    val declaredName = match.groupValues[1]
                    if (forbiddenNames.contains(declaredName)) {
                        skipDeclaration = true
                        braceDepth = 0
                        val opens = line.count { it == '{' }
                        val closes = line.count { it == '}' }
                        braceDepth += opens - closes
                        if (braceDepth <= 0 && opens > 0) {
                            skipDeclaration = false
                            braceDepth = 0
                        }
                        continue
                    }
                }
                resultLines.add(line)
            } else {
                val opens = line.count { it == '{' }
                val closes = line.count { it == '}' }
                braceDepth += opens - closes
                if (braceDepth <= 0) {
                    skipDeclaration = false
                    braceDepth = 0
                }
            }
        }

        return resultLines.joinToString("\n").trim()
    }

    /**
     * Spec-driven rule-based fallback — used only when all LLM providers fail.
     *
     * Strategy (in order of decreasing quality):
     *  1. If a [spec] is available, synthesise a minimal but structurally correct Kotlin file
     *     using the real package name, field names/types, method names/signatures from the spec.
     *     Method bodies are replaced with TODO stubs — they will always compile.
     *  2. If [brokenKotlin] exists and passes a basic sanity check (right package, right class name,
     *     no obvious duplicate-import/duplicate-class patterns that grew across retries), return it
     *     cleaned up (dedup imports, fix package semi-colons).
     *  3. Last resort: emit a minimal, explicitly-marked stub that compiles but is empty.
     *
     * This function NEVER references class names, field names, or packages that are not present
     * in the target module's own spec.  The old hardcoded sample-legacy templates have been removed.
     */
    internal fun applyRuleBasedFix(
        brokenKotlin: String,
        compilerErrors: List<String>,
        className: String = "",
        otherModuleNames: Set<String> = emptySet(),
        spec: com.issam.apollo.state.ModuleSpec? = null
    ): String {

        // ── Path 1: clean up the broken Kotlin if it looks recoverable ───────────
        if (brokenKotlin.isNotBlank()) {
            val classDeclarationCount = Regex("""\b(?:class|object|interface)\s+${Regex.escape(className)}\b""")
                .findAll(brokenKotlin).count()
            if (classDeclarationCount <= 1) {
                var fixed = brokenKotlin
                fixed = fixed.replace("class class ", "class ").replace("fun fun ", "fun ")
                return cleanLLMOutput(fixed, className, otherModuleNames)
            }
        }

        // ── Path 2: spec-driven synthesis ────────────────────────────────────────
        if (spec != null && spec.className.isNotBlank()) {
            return buildSpecDrivenStub(spec, otherModuleNames)
        }

        // ── Path 3: last-resort empty compiling stub ─────────────────────────────
        return buildEmptyStub(className, packageName = null)
    }

    /**
     * Derives a minimal, compilable Kotlin file from [spec].
     * Handles Android Activities, SQLite helpers, and general classes.
     * Never emits deprecated synthetic imports.
     */
    private fun buildSpecDrivenStub(
        spec: com.issam.apollo.state.ModuleSpec,
        otherModuleNames: Set<String>
    ): String {
        val sb = StringBuilder()

        // Package
        if (spec.packageName.isNotBlank()) {
            sb.appendLine("package ${spec.packageName}")
            sb.appendLine()
        }

        val isActivity = spec.className.endsWith("Activity") ||
            spec.imports.any { it.contains("Activity") }

        val isDbHelper = spec.className.endsWith("OpenHelper") ||
            spec.className.endsWith("DatabaseHelper") ||
            spec.imports.any { it.contains("SQLiteOpenHelper") }

        // Imports — only safe imports from spec, strictly filtering synthetic
        val safeImports = spec.imports
            .filter { it.isNotBlank() }
            .map { it.trim().removePrefix("import ").removeSuffix(";").trim() }
            .filter { imp ->
                !imp.startsWith("kotlinx.android.synthetic") &&
                !otherModuleNames.any { other -> imp.endsWith(".$other") }
            }
            .toMutableList()

        if (isActivity) {
            safeImports.add("android.os.Bundle")
            if (spec.imports.any { it.contains("AppCompatActivity") }) {
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

        val distinctImports = safeImports.distinct()
        if (distinctImports.isNotEmpty()) {
            distinctImports.forEach { sb.appendLine("import $it") }
            sb.appendLine()
        }

        sb.appendLine("/**")
        sb.appendLine(" * ⚠️  Apollo Fixer Fallback Stub — LLM providers were unavailable.")
        sb.appendLine(" * This file was auto-generated from the Analyzer spec for [${spec.className}].")
        sb.appendLine(" * Method bodies contain TODO stubs — manual migration review is required.")
        sb.appendLine(" */")

        val isAppCompat = spec.imports.any { it.contains("AppCompatActivity") }

        if (isActivity) {
            val superType = if (isAppCompat) "AppCompatActivity()" else "Activity()"
            sb.appendLine("open class ${spec.className} : $superType {")
            sb.appendLine()

            // Check if source file contains setContentView(R.layout.xxx)
            var layoutRes: String? = null
            if (spec.sourceFilePath.isNotBlank()) {
                val f = java.io.File(spec.sourceFilePath)
                if (f.exists()) {
                    val src = f.readText()
                    val match = Regex("""setContentView\s*\(\s*(R\.layout\.[a-zA-Z0-9_]+)\s*\)""").find(src)
                    layoutRes = match?.groupValues?.get(1)
                }
            }

            // Fields — parse "type name" from spec.fields
            if (spec.fields.isNotEmpty()) {
                sb.appendLine("    // ── Fields (from Analyzer spec) ──────────────────")
                for (rawField in spec.fields) {
                    val fieldDecl = parseFieldSpec(rawField)
                    sb.appendLine("    $fieldDecl")
                }
                sb.appendLine()
            }

            // onCreate
            sb.appendLine("    override fun onCreate(savedInstanceState: Bundle?) {")
            sb.appendLine("        super.onCreate(savedInstanceState)")
            if (layoutRes != null) {
                sb.appendLine("        setContentView($layoutRes)")
            }
            sb.appendLine("        // TODO: Apollo fallback stub — initialize views using findViewById")
            sb.appendLine("    }")
            sb.appendLine()

            // Other methods from spec (skip onCreate since generated above)
            val otherMethods = spec.methods.filterNot { it.contains("onCreate(") }
            if (otherMethods.isNotEmpty()) {
                sb.appendLine("    // ── Methods (from Analyzer spec) ─────────────────")
                for (rawMethod in otherMethods) {
                    val methodLines = parseMethodSpec(rawMethod, isActivity = true)
                    methodLines.forEach { sb.appendLine("    $it") }
                    sb.appendLine()
                }
            }

            sb.appendLine("}")
        } else if (isDbHelper) {
            sb.appendLine("open class ${spec.className}(context: Context) : SQLiteOpenHelper(context, \"app.db\", null, 1) {")
            sb.appendLine()

            if (spec.fields.isNotEmpty()) {
                sb.appendLine("    // ── Fields (from Analyzer spec) ──────────────────")
                for (rawField in spec.fields) {
                    val fieldDecl = parseFieldSpec(rawField)
                    sb.appendLine("    $fieldDecl")
                }
                sb.appendLine()
            }

            sb.appendLine("    override fun onCreate(db: SQLiteDatabase) {")
            sb.appendLine("        // TODO: Apollo fallback stub — database initialization")
            sb.appendLine("    }")
            sb.appendLine()
            sb.appendLine("    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {")
            sb.appendLine("        // TODO: Apollo fallback stub — database upgrade")
            sb.appendLine("    }")
            sb.appendLine()

            val otherMethods = spec.methods.filterNot { it.contains("onCreate(") || it.contains("onUpgrade(") }
            if (otherMethods.isNotEmpty()) {
                sb.appendLine("    // ── Methods (from Analyzer spec) ─────────────────")
                for (rawMethod in otherMethods) {
                    val methodLines = parseMethodSpec(rawMethod)
                    methodLines.forEach { sb.appendLine("    $it") }
                    sb.appendLine()
                }
            }

            sb.appendLine("}")
        } else {
            // General class
            sb.appendLine("open class ${spec.className} {")
            sb.appendLine()

            if (spec.fields.isNotEmpty()) {
                sb.appendLine("    // ── Fields (from Analyzer spec) ──────────────────")
                for (rawField in spec.fields) {
                    val fieldDecl = parseFieldSpec(rawField)
                    sb.appendLine("    $fieldDecl")
                }
                sb.appendLine()
            }

            if (spec.methods.isNotEmpty()) {
                sb.appendLine("    // ── Methods (from Analyzer spec) ─────────────────")
                for (rawMethod in spec.methods) {
                    val methodLines = parseMethodSpec(rawMethod)
                    methodLines.forEach { sb.appendLine("    $it") }
                    sb.appendLine()
                }
            }

            sb.appendLine("}")
        }

        return sb.toString().trim()
    }

    /**
     * Converts a raw field string from [ModuleSpec.fields] into a Kotlin property declaration.
     * Accepts forms like:
     *   - "int count"  / "String name"  (Java-style)
     *   - "count: Int" / "name: String" (Kotlin-style)
     *   - "private List<String> items"  (with modifiers)
     */
    private fun parseFieldSpec(raw: String): String {
        val trimmed = raw.trim()

        // Already Kotlin-style "name: Type"
        if (trimmed.matches(Regex("""[a-zA-Z_][a-zA-Z0-9_]*\s*:\s*.+"""))) {
            return "var $trimmed = TODO(\"stub\")"
        }

        // Java-style: optional modifier(s) + type + name
        val javaPattern = Regex("""^(?:(?:private|protected|public|static|final|transient|volatile)\s+)*([\w<>,?\[\]]+)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*$""")
        val match = javaPattern.matchEntire(trimmed)
        if (match != null) {
            val javaType = match.groupValues[1]
            val name = match.groupValues[2]
            val kotlinType = javaTypeToKotlin(javaType)
            return "var $name: $kotlinType? = null"
        }

        // Fallback: emit as a comment so nothing breaks
        return "// $trimmed"
    }

    /**
     * Converts a raw method string from [ModuleSpec.methods] into compilable Kotlin stub lines.
     */
    private fun parseMethodSpec(raw: String, isActivity: Boolean = false): List<String> {
        val trimmed = raw.trim()

        // Already Kotlin-style
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

        // Java-style: returnType methodName(params)
        val javaMethod = Regex("""^(?:(?:private|protected|public|static|final|synchronized|abstract|native)\s+)*([\w<>,?\[\]]+)\s+([a-zA-Z_][a-zA-Z0-9_]*)\s*\(([^)]*)\)""")
        val match = javaMethod.find(trimmed)
        if (match != null) {
            val returnTypeJava = match.groupValues[1]
            val name = match.groupValues[2]
            val paramsRaw = match.groupValues[3].trim()

            val kotlinReturn = javaTypeToKotlin(returnTypeJava)
            val isVoid = returnTypeJava == "void" || returnTypeJava == "Void"

            val isAnyOverride = name in setOf("equals", "hashCode", "toString")
            val isOverride = (isActivity && name in setOf(
                "onCreateOptionsMenu", "onOptionsItemSelected", "onActivityResult",
                "onResume", "onPause", "onDestroy", "onStart", "onStop", "onBackPressed"
            )) || isAnyOverride
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
                    "hashCode" -> listOf("override fun hashCode(): Int = super.hashCode()")
                    "toString" -> listOf("override fun toString(): String = super.toString()")
                    "equals" -> listOf("override fun equals(other: Any?): Boolean = super.equals(other)")
                    else -> if (isVoid) {
                        listOf("override fun $name($params) {", "    TODO(\"Apollo fallback stub — manual migration required\")", "}")
                    } else {
                        listOf("override fun $name($params): $kotlinReturn =", "    TODO(\"Apollo fallback stub — manual migration required\")")
                    }
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

        // Constructor or unparseable — emit as a comment
        return listOf("// $trimmed")
    }

    /**
     * Minimal Java-to-Kotlin type mapping for common primitives and collections.
     * Keeps unknown types as-is so they compile (the type may already be a Kotlin class).
     */
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
            else                     -> stripped  // keep as-is for project-specific or generic types
        }
        return if (isArray) "Array<$kotlin>" else kotlin
    }

    /** Minimum compilable stub when we have no spec and no usable broken source. */
    private fun buildEmptyStub(className: String, packageName: String?): String {
        val sb = StringBuilder()
        if (!packageName.isNullOrBlank()) {
            sb.appendLine("package $packageName")
            sb.appendLine()
        }
        sb.appendLine("/**")
        sb.appendLine(" * ⚠️  Apollo Fixer Fallback — no spec and no recoverable source available.")
        sb.appendLine(" * This is an empty compilable stub.  Manual migration of [$className] is required.")
        sb.appendLine(" */")
        sb.appendLine("open class $className")
        return sb.toString().trim()
    }

    internal fun extractPrimaryErrorSignature(
        className: String,
        verification: com.issam.apollo.state.VerificationResult
    ): String {
        val diagnostics = parseCompilerDiagnostics(verification.compilerErrors)
        val moduleDiag = diagnostics.firstOrNull { diag ->
            diag.fileName.equals("$className.kt", ignoreCase = true) ||
            diag.fileName.equals("$className.java", ignoreCase = true) ||
            diag.filePath.contains("/$className.kt") ||
            diag.filePath.contains("\\$className.kt") ||
            diag.rawLines.any { line -> line.contains("$className.kt", ignoreCase = true) }
        }
        if (moduleDiag != null && moduleDiag.message.isNotBlank()) {
            return moduleDiag.message.trim()
        }
        val testFailure = verification.testFailures.firstOrNull { it.contains(className) }
        if (testFailure != null && testFailure.isNotBlank()) {
            return testFailure.trim()
        }
        return ""
    }
}
