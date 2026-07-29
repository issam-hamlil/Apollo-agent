package com.issam.apollo

import com.issam.apollo.mcp.McpServer
import com.issam.apollo.orchestrator.ModernizationGraph
import com.issam.apollo.state.ModuleStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

fun main(args: Array<String>) {
    println("==================================================")
    println("  Apollo Agent — Legacy Java to Kotlin Engine")
    println("==================================================")

    if (args.contains("--server")) {
        println("[Apollo CLI] Starting Model Context Protocol (MCP) Server over stdio transport...")
        val server = McpServer()
        server.start()
        return
    }

    // Clean input target path (strip trailing slashes if present, e.g. "sample-legacy/")
    val rawTarget = if (args.isNotEmpty()) args[0] else "sample-legacy"
    val targetPath = rawTarget.trimEnd('/', '\\')

    val targetDir = File(targetPath)
    if (!targetDir.exists()) {
        println("[Apollo CLI] Error: Target project path '$targetPath' does not exist!")
        return
    }

    println("Target Project: ${targetDir.absolutePath}")

    val orchestrator = ModernizationGraph()
    val finalState = orchestrator.runPipeline(targetPath)

    // Format & Print Per-Module Status Table
    println("\n==================================================")
    println("             Module Status Summary                ")
    println("==================================================")
    println("%-20s %-12s %-10s".format("Module Name", "Status", "Retries"))
    println("--------------------------------------------------")
    finalState.moduleStatuses.forEach { (className, status) ->
        val attempts = finalState.regenCounters[className] ?: 0
        val statusSymbol = when (status) {
            ModuleStatus.VERIFIED -> "✅ VERIFIED"
            ModuleStatus.FAILED -> "❌ FAILED"
            ModuleStatus.MIGRATED -> "⚙️ MIGRATED"
            ModuleStatus.ANALYZED -> "🔍 ANALYZED"
            ModuleStatus.PENDING -> "⏳ PENDING"
        }
        println("%-20s %-12s %-10d".format(className, statusSymbol, attempts))
    }
    println("--------------------------------------------------")

    println("\n--------------------------------------------------")
    println(" Pipeline Execution Summary:")
    println(" Current Stage: ${finalState.currentStage}")
    println(" Compilation Verified: ${finalState.verificationResult.compiledSuccessfully}")
    println(" Tests Passed: ${finalState.verificationResult.testsPassed} / ${finalState.verificationResult.testsPassed + finalState.verificationResult.testsFailed}")
    println(" Migrated Modules Count: ${finalState.migratedCode.size}")
    println(" Total Stage Reports: ${finalState.reports.size}")
    println("--------------------------------------------------\n")

    // Export runtime reports to reports/ directory
    val reportsDir = File("reports")
    reportsDir.mkdirs()

    val jsonPretty = Json { prettyPrint = true }
    val reportFile = File(reportsDir, "migration-report-${System.currentTimeMillis()}.json")
    val summaryMarkdown = File(reportsDir, "migration-summary.md")

    val jsonOutput = jsonPretty.encodeToString(finalState.reports)
    reportFile.writeText(jsonOutput)

    val mdContent = buildString {
        appendLine("# Apollo Agent Modernization Report")
        appendLine()
        appendLine("- **Target Project**: `${targetDir.name}`")
        appendLine("- **Overall Status**: `${finalState.currentStage}`")
        appendLine("- **Compilation Success**: `${finalState.verificationResult.compiledSuccessfully}`")
        appendLine("- **Characterization Tests Passed**: `${finalState.verificationResult.testsPassed}`")
        appendLine("- **Characterization Tests Failed**: `${finalState.verificationResult.testsFailed}`")
        appendLine()
        appendLine("## Per-Module Statuses")
        appendLine("| Module Name | Status | Regeneration Attempts |")
        appendLine("|-------------|--------|-----------------------|")
        finalState.moduleStatuses.forEach { (mod, status) ->
            val attempts = finalState.regenCounters[mod] ?: 0
            appendLine("| `$mod` | `${status.name}` | `$attempts` |")
        }
        appendLine()
        appendLine("## Stage Reports")
        finalState.reports.forEach { r ->
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
        finalState.migratedCode.keys.forEach { name ->
            appendLine("- `$name` (`migrated-src/$name`)")
        }
    }
    summaryMarkdown.writeText(mdContent)

    println("Reports generated successfully under: ${reportsDir.absolutePath}")
}
