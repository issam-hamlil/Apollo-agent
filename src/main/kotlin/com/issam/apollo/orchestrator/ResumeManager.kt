package com.issam.apollo.orchestrator

import com.issam.apollo.config.LlmConfig
import com.issam.apollo.state.AgentReport
import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleSpec
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.state.TestCase
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.time.Instant

@Serializable
data class MigrationReportEnvelope(
    val targetProjectPath: String,
    val timestamp: String,
    val currentStage: String,
    val compilationSuccess: Boolean,
    val moduleStatuses: Map<String, ModuleStatus> = emptyMap(),
    val regenCounters: Map<String, Int> = emptyMap(),
    val retryCount: Int = 0,
    val reports: List<AgentReport> = emptyList()
)

data class ResumeResult(
    val state: GraphState,
    val canSkipAnalyzerAndCharacterization: Boolean,
    val canSkipMigrator: Boolean,
    val reportFile: File?,
    val resumeMessages: List<String> = emptyList()
)

object ResumeManager {

    private val json = Json {
        ignoreUnknownKeys = true
        prettyPrint = true
        isLenient = true
    }

    /**
     * Finds the most recent migration-report-*.json in [reportsDir] that matches [targetProjectPath].
     */
    fun findLatestReportForProject(targetProjectPath: String, reportsDir: File = File("").absoluteFile.resolve("reports")): File? {
        if (!reportsDir.exists() || !reportsDir.isDirectory) return null

        val reportFiles = reportsDir.listFiles { file ->
            file.isFile && file.name.startsWith("migration-report-") && file.name.endsWith(".json")
        }?.sortedByDescending { it.lastModified() } ?: return null

        if (reportFiles.isEmpty()) return null

        val normalizedTarget = File(targetProjectPath).canonicalPath.replace('\\', '/').trimEnd('/')
        val targetDirName = File(targetProjectPath).name

        for (file in reportFiles) {
            if (isReportMatchingProject(file, normalizedTarget, targetDirName, reportsDir)) {
                return file
            }
        }

        return null
    }

    private fun isReportMatchingProject(
        reportFile: File,
        normalizedTarget: String,
        targetDirName: String,
        reportsDir: File
    ): Boolean {
        try {
            val content = reportFile.readText().trim()
            if (content.isEmpty()) return false

            val jsonElement = json.parseToJsonElement(content)
            if (jsonElement is JsonObject) {
                val projectPathInJson = jsonElement["targetProjectPath"]?.toString()?.trim('"')?.replace('\\', '/')
                if (projectPathInJson != null) {
                    val normInJson = try { File(projectPathInJson).canonicalPath.replace('\\', '/').trimEnd('/') } catch (_: Exception) { projectPathInJson.trimEnd('/') }
                    if (projectPathInJson.trimEnd('/') == normalizedTarget ||
                        normInJson == normalizedTarget ||
                        projectPathInJson == targetDirName ||
                        projectPathInJson.endsWith("/$targetDirName") ||
                        normalizedTarget.endsWith("/$projectPathInJson")) {
                        return true
                    }
                }
            }

            // Check migration-summary.md if present
            val summaryFile = File(reportsDir, "migration-summary.md")
            if (summaryFile.exists()) {
                val summaryText = summaryFile.readText()
                val targetMatch = Regex("""\*\*Target Project\*\*\s*\|\s*`([^`]+)`""").find(summaryText)
                if (targetMatch != null) {
                    val summaryTarget = targetMatch.groupValues[1].replace('\\', '/').trimEnd('/')
                    if (summaryTarget == normalizedTarget || summaryTarget.endsWith("/$targetDirName")) {
                        return true
                    }
                }
            }

            // Check specs directory for sourceFilePath references
            val specsDir = File(reportsDir, "specs")
            if (specsDir.exists() && specsDir.isDirectory) {
                val specFiles = specsDir.walkTopDown().filter { it.isFile && it.name.endsWith("-spec.json") }.toList()
                for (specFile in specFiles) {
                    try {
                        val spec = json.decodeFromString<ModuleSpec>(specFile.readText())
                        val specSource = spec.sourceFilePath.replace('\\', '/')
                        if (specSource.startsWith(normalizedTarget) || specSource.contains("/$targetDirName/")) {
                            return true
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Loads the resume state for [targetProjectPath].
     *
     * 1. Reads the latest report, extracting module statuses and regenCounters.
     * 2. Checks if reports/specs/ and reports/characterization/ contain entries for every module in topo order.
     * 3. Checks migrated-src/ for existing Kotlin source files.
     * 4. Populates and returns a ready-to-verify [GraphState].
     */
    fun loadResumeState(
        targetProjectPath: String,
        reportsDir: File = File("").absoluteFile.resolve("reports"),
        migratedOutputDir: File = File("").absoluteFile.resolve("migrated-src"),
        maxRetriesPerModule: Int = LlmConfig.maxRetryCount
    ): ResumeResult? {
        val latestReportFile = findLatestReportForProject(targetProjectPath, reportsDir)
        val messages = mutableListOf<String>()

        val loadedStatuses = mutableMapOf<String, ModuleStatus>()
        val loadedRegenCounters = mutableMapOf<String, Int>()
        val priorReports = mutableListOf<AgentReport>()
        var priorRetryCount = 0

        // Parse previous report if available
        if (latestReportFile != null && latestReportFile.exists()) {
            try {
                val content = latestReportFile.readText().trim()
                val jsonElement = json.parseToJsonElement(content)
                if (jsonElement is JsonObject) {
                    val envelope = json.decodeFromString<MigrationReportEnvelope>(content)
                    loadedStatuses.putAll(envelope.moduleStatuses)
                    loadedRegenCounters.putAll(envelope.regenCounters)
                    priorReports.addAll(envelope.reports)
                    priorRetryCount = envelope.retryCount
                } else if (jsonElement is JsonArray) {
                    val reportsList = json.decodeFromString<List<AgentReport>>(content)
                    priorReports.addAll(reportsList)
                }
            } catch (e: Exception) {
                println("[Resume] Warning: Could not fully parse report JSON ${latestReportFile.name}: ${e.message}")
            }
        }

        // Also parse migration-summary.md for any missing statuses or counters
        val summaryFile = File(reportsDir, "migration-summary.md")
        if (summaryFile.exists()) {
            try {
                val summaryText = summaryFile.readText()
                val tableRowRegex = Regex("""\|\s*`?([a-zA-Z0-9_]+)`?\s*\|\s*`?([A-Z_]+)`?\s*\|\s*`?(\d+)`?\s*\|""")
                for (match in tableRowRegex.findAll(summaryText)) {
                    val modName = match.groupValues[1]
                    val statusStr = match.groupValues[2]
                    val attempts = match.groupValues[3].toIntOrNull() ?: 0
                    if (!loadedStatuses.containsKey(modName)) {
                        try {
                            loadedStatuses[modName] = ModuleStatus.valueOf(statusStr)
                        } catch (_: Exception) {}
                    }
                    if (!loadedRegenCounters.containsKey(modName)) {
                        loadedRegenCounters[modName] = attempts
                    }
                }
            } catch (_: Exception) {}
        }

        // ── 1. Check topological order and module specs ──────────────────────────
        val topoOrderFile = File(reportsDir, "topo-order.md")
        val topoOrder = mutableListOf<String>()
        val dependencyGraph = mutableMapOf<String, List<String>>()

        if (topoOrderFile.exists()) {
            val lines = topoOrderFile.readLines()
            var inTopoSection = false
            var inDepSection = false

            for (line in lines) {
                val trimmed = line.trim()
                if (trimmed.startsWith("## Topologically-Sorted Migration Order")) {
                    inTopoSection = true
                    inDepSection = false
                    continue
                } else if (trimmed.startsWith("## Dependency Graph")) {
                    inTopoSection = false
                    inDepSection = true
                    continue
                } else if (trimmed.startsWith("## ")) {
                    inTopoSection = false
                    inDepSection = false
                }

                if (inTopoSection) {
                    val match = Regex("""^\d+\.\s*`?([a-zA-Z0-9_]+)`?""").find(trimmed)
                    if (match != null) {
                        topoOrder.add(match.groupValues[1])
                    }
                } else if (inDepSection) {
                    val match = Regex("""^\|\s*`?([a-zA-Z0-9_]+)`?\s*\|\s*([^|]+)\|""").find(trimmed)
                    if (match != null) {
                        val mod = match.groupValues[1]
                        val depsRaw = match.groupValues[2].trim()
                        if (mod != "Module") {
                            val deps = if (depsRaw.contains("*(none)*") || depsRaw.isBlank()) {
                                emptyList()
                            } else {
                                depsRaw.split(",").map { it.replace("`", "").trim() }.filter { it.isNotBlank() }
                            }
                            dependencyGraph[mod] = deps
                        }
                    }
                }
            }
        }

        val specsDir = File(reportsDir, "specs")
        val loadedSpecs = mutableMapOf<String, ModuleSpec>()
        val astMetadata = mutableMapOf<String, String>()
        val normalizedTarget = try { File(targetProjectPath).canonicalPath.replace('\\', '/').trimEnd('/') } catch (_: Exception) { targetProjectPath.replace('\\', '/').trimEnd('/') }
        val targetDirName = File(targetProjectPath).name

        if (specsDir.exists() && specsDir.isDirectory) {
            val specFiles = specsDir.walkTopDown().filter { it.isFile && it.name.endsWith("-spec.json") }.toList()
            for (sf in specFiles) {
                try {
                    val spec = json.decodeFromString<ModuleSpec>(sf.readText())
                    val specSource = try { File(spec.sourceFilePath).canonicalPath.replace('\\', '/').trimEnd('/') } catch (_: Exception) { spec.sourceFilePath.replace('\\', '/').trimEnd('/') }
                    val belongsToTarget = specSource.startsWith(normalizedTarget) ||
                            specSource.startsWith(targetProjectPath.replace('\\', '/').trimEnd('/')) ||
                            (File(spec.sourceFilePath).exists() && specSource.contains("/$targetDirName/")) ||
                            (normalizedTarget.endsWith("/$targetDirName") && specSource.contains("/$targetDirName/")) ||
                            (spec.sourceFilePath.startsWith("$targetDirName/") || spec.sourceFilePath.contains("/$targetDirName/")) ||
                            (spec.sourceFilePath.startsWith("$targetProjectPath/") || spec.sourceFilePath.startsWith(targetProjectPath))

                    if (belongsToTarget) {
                        loadedSpecs[spec.className] = spec
                        astMetadata[spec.className] = "Package: ${spec.packageName}, Fields: ${spec.fields.size}, Methods: ${spec.methods.size}"
                        if (!topoOrder.contains(spec.className)) {
                            topoOrder.add(spec.className)
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        // Prune topoOrder, loadedStatuses and loadedRegenCounters to only contain modules from this target project
        topoOrder.retainAll(loadedSpecs.keys)
        loadedStatuses.keys.retainAll(loadedSpecs.keys)
        loadedRegenCounters.keys.retainAll(loadedSpecs.keys)

        if (loadedSpecs.isEmpty()) {
            println("[Resume] No specs for project '$targetProjectPath' loaded from previous run. Starting fresh from ANALYZER.")
            return null
        }

        // ── 1. Verify specs completeness and validity ────────────────────────────
        val specsComplete = topoOrder.isNotEmpty() && topoOrder.all { className ->
            val spec = loadedSpecs[className]
            spec != null && spec.className == className && spec.packageName.isNotBlank() &&
            spec.sourceFilePath.isNotBlank()
        }

        // ── 2. Verify characterization tests completeness and validity ───────────
        val charDir = File(reportsDir, "characterization")
        val loadedTests = mutableListOf<TestCase>()
        var characterizationComplete = false

        if (charDir.exists() && charDir.isDirectory && specsComplete) {
            var allModulesValid = true
            for (className in topoOrder) {
                val gtFile = File(charDir, "$className-ground-truth.json")
                if (gtFile.exists() && gtFile.length() > 0) {
                    try {
                        val tests = json.decodeFromString<List<TestCase>>(gtFile.readText())
                        loadedTests.addAll(tests)
                    } catch (_: Exception) {
                        allModulesValid = false
                    }
                } else {
                    allModulesValid = false
                }
            }
            characterizationComplete = allModulesValid
        }

        val canSkipAnalyzerAndChar = specsComplete && characterizationComplete

        if (canSkipAnalyzerAndChar) {
            val msg = "[Resume] Verified Stage 1 (Specs) & Stage 2 (Characterization) artifacts for ${topoOrder.size}/${topoOrder.size} modules."
            println(msg)
            messages.add(msg)
        } else {
            val msg = "[Resume] Verification of Stage 1/Stage 2 artifacts failed (missing/corrupted specs or characterization files). Starting over from ANALYZER."
            println(msg)
            messages.add(msg)
            return null
        }

        // ── 3. Check and verify migrated-src/ for existing Kotlin source files ──
        val loadedMigratedCode = mutableMapOf<String, String>()
        val existingFiles = if (migratedOutputDir.exists()) {
            migratedOutputDir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        } else emptyList()

        val adjustedRegenCounters = loadedRegenCounters.toMutableMap()

        for (className in topoOrder) {
            val matchingFile = existingFiles.firstOrNull { it.name == "$className.kt" }
            val status = loadedStatuses[className] ?: ModuleStatus.PENDING
            val priorAttempts = loadedRegenCounters[className] ?: 0

            // On resume, reset retry counters for all modules to 0 so every module needing
            // repair gets its full fresh budget of attempts starting with 3 attempts on qwen3-coder:30b (primary)
            val attempts = if (status != ModuleStatus.VERIFIED) {
                adjustedRegenCounters[className] = 0
                if (priorAttempts > 0) {
                    val resetMsg = "[Resume] $className (status=$status, prior attempts=$priorAttempts): Resetting retry counter to 0 on resume (fresh 3x 30b attempts budget)."
                    println(resetMsg)
                    messages.add(resetMsg)
                }
                0
            } else {
                adjustedRegenCounters[className] = 0
                0
            }

            if (matchingFile != null && matchingFile.exists() && matchingFile.length() > 10) {
                val code = matchingFile.readText().trim()
                if (code.isNotBlank() && (code.contains("class ") || code.contains("interface ") || code.contains("object ") || code.contains("package "))) {
                    loadedMigratedCode["$className.kt"] = code

                    val logMsg = when {
                        status == ModuleStatus.VERIFIED -> {
                            "[Resume] $className: verified existing migrated-src, status=VERIFIED, regenCounters=$attempts — skipping migration"
                        }
                        else -> {
                            val remaining = maxRetriesPerModule - attempts
                            "[Resume] $className: verified existing migrated-src, status=$status, regenCounters=$attempts, remaining attempts=$remaining"
                        }
                    }
                    println(logMsg)
                    messages.add(logMsg)
                    continue
                }
            }

            val logMsg = "[Resume] $className: missing or invalid migrated Kotlin source — will require migration"
            println(logMsg)
            messages.add(logMsg)
        }

        val canSkipMigrator = topoOrder.isNotEmpty() && topoOrder.all {
            val code = loadedMigratedCode["$it.kt"]
            !code.isNullOrBlank() && code.length > 10
        }

        val validKeys = loadedSpecs.keys
        val finalStatuses = loadedStatuses.filterKeys { it in validKeys }.toMutableMap()
        for (className in topoOrder) {
            if (!finalStatuses.containsKey(className)) {
                finalStatuses[className] = ModuleStatus.PENDING
            }
        }
        val finalRegenCounters = adjustedRegenCounters.filterKeys { it in validKeys }.toMutableMap()
        for (className in topoOrder) {
            if (!finalRegenCounters.containsKey(className)) {
                finalRegenCounters[className] = 0
            }
        }

        val initialState = GraphState(
            targetProjectPath = targetProjectPath,
            javaFiles = loadedSpecs.values.map { it.sourceFilePath },
            astMetadata = astMetadata,
            dependencyGraph = dependencyGraph,
            topologicalOrder = topoOrder,
            moduleSpecs = loadedSpecs,
            moduleStatuses = finalStatuses,
            regenCounters = finalRegenCounters,
            characterizationTests = loadedTests,
            migratedCode = loadedMigratedCode,
            retryCount = 0, // Reset to 0 on resume for a fresh pipeline-level pass budget
            maxRetries = maxRetriesPerModule,
            currentStage = "RESUMED",
            reports = priorReports
        )

        return ResumeResult(
            state = initialState,
            canSkipAnalyzerAndCharacterization = canSkipAnalyzerAndChar,
            canSkipMigrator = canSkipMigrator,
            reportFile = latestReportFile,
            resumeMessages = messages
        )
    }

    /**
     * Persists the final execution state as a structured [MigrationReportEnvelope] and summary Markdown.
     */
    fun saveMigrationReport(state: GraphState, reportsDir: File = File("").absoluteFile.resolve("reports")): File {
        reportsDir.mkdirs()

        val envelope = MigrationReportEnvelope(
            targetProjectPath = state.targetProjectPath,
            timestamp = Instant.now().toString(),
            currentStage = state.currentStage,
            compilationSuccess = state.verificationResult.compiledSuccessfully,
            moduleStatuses = state.moduleStatuses,
            regenCounters = state.regenCounters,
            retryCount = state.retryCount,
            reports = state.reports
        )

        val reportFile = File(reportsDir, "migration-report-${System.currentTimeMillis()}.json")
        reportFile.writeText(json.encodeToString(envelope))

        val summaryMarkdown = File(reportsDir, "migration-summary.md")
        val mdContent = buildString {
            appendLine("# Apollo Agent Modernization Report")
            appendLine()
            appendLine("## Run Provenance")
            appendLine("| Field | Value |")
            appendLine("|-------|-------|")
            appendLine("| **Target Project** | `${File(state.targetProjectPath).absolutePath}` |")
            appendLine("| **Run Timestamp** | `${envelope.timestamp}` |")
            appendLine("| **LLM Provider** | `${LlmConfig.defaultProvider}` |")
            appendLine("| **LLM Model** | `${LlmConfig.defaultModel}` |")
            appendLine("| **Ollama Model** | `${LlmConfig.ollamaModel}` |")
            appendLine()
            appendLine("## Pipeline Result")
            appendLine("- **Overall Status**: `${state.currentStage}`")
            appendLine("- **Compilation Success**: `${state.verificationResult.compiledSuccessfully}`")
            appendLine("- **Characterization Tests Passed**: `${state.verificationResult.testsPassed}`")
            appendLine("- **Characterization Tests Failed**: `${state.verificationResult.testsFailed}`")
            appendLine()
            appendLine("## Per-Module Statuses")
            appendLine("| Module Name | Status | Regeneration Attempts |")
            appendLine("|-------------|--------|-----------------------|")
            state.moduleStatuses.forEach { (mod, status) ->
                val attempts = state.regenCounters[mod] ?: 0
                appendLine("| `$mod` | `${status.name}` | `$attempts` |")
            }
            appendLine()
            appendLine("## Stage Reports")
            state.reports.forEach { r ->
                appendLine("### ${r.stageName}")
                appendLine("- **Status**: `${r.status}`")
                appendLine("- **Timestamp**: `${r.timestamp}`")
                appendLine("- **Details**: ${r.details}")
                if (r.metrics.isNotEmpty()) {
                    appendLine("- **Metrics**: `${r.metrics}`")
                }
                appendLine()
            }
            appendLine("## Migrated Kotlin Files")
            state.migratedCode.keys.forEach { name ->
                appendLine("- `$name` (`migrated-src/$name`)")
            }
        }
        summaryMarkdown.writeText(mdContent)

        return reportFile
    }
}
