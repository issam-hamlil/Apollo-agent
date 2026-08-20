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
        println("[Apollo CLI] Starting Model Context Protocol (MCP) Streamable HTTP Server on port 8080...")
        val server = McpServer()
        server.start()
        return
    }

    val isExplicitResume = args.contains("--resume")
    val isExplicitFresh = args.contains("--fresh")
    val nonFlagArgs = args.filterNot { it.startsWith("--") }

    // Clean input target path (strip trailing slashes if present, e.g. "sample-legacy/")
    val rawTarget = if (nonFlagArgs.isNotEmpty()) nonFlagArgs[0] else "sample-legacy"
    val targetPath = rawTarget.trimEnd('/', '\\')

    val targetDir = File(targetPath)
    if (!targetDir.exists()) {
        println("[Apollo CLI] Error: Target project path '$targetPath' does not exist!")
        kotlin.system.exitProcess(1)
    }

    println("Target Project: ${targetDir.absolutePath}")

    val reportsDir = File("").absoluteFile.resolve("reports")
    val isResume = when {
        isExplicitFresh -> {
            println("[Apollo CLI] --fresh specified: Starting fresh migration run.")
            false
        }
        isExplicitResume -> {
            println("[Apollo CLI] --resume specified: Resuming from previous run.")
            true
        }
        else -> {
            val latestReport = com.issam.apollo.orchestrator.ResumeManager.findLatestReportForProject(targetPath, reportsDir)
            if (latestReport != null) {
                println("[Apollo CLI] Previous migration run detected for '$targetPath' (${latestReport.name}). Defaulting to resume mode. (Pass --fresh to force full run)")
                true
            } else {
                false
            }
        }
    }

    // Android SDK / AAPT2 Environment Check
    val aapt2Binary = try { com.issam.apollo.tools.AndroidSdkResolver.findAapt2Binary() } catch (e: Exception) { null }
    val androidJar = try { com.issam.apollo.tools.AndroidSdkResolver.findAndroidJar() } catch (e: Exception) { null }

    if (aapt2Binary != null && androidJar != null) {
        println("[Apollo Environment] Android SDK ready (AAPT2: ${aapt2Binary.name}, android.jar: ${androidJar.name})")
    } else {
        println("[Apollo Environment] Standard JVM Mode (AAPT2 available: ${aapt2Binary != null}, android.jar available: ${androidJar != null})")
    }

    // ── LLM Config Diagnostic Banner ──────────────────────────────────────────
    // Printed before every pipeline run so provider/model/URL mismatches are
    // immediately visible without needing to inspect .env or environment vars manually.
    println()
    println("+----------------------------------+--------------------------------------+----------------------+")
    println("|  Apollo LLM Configuration        | Value                                | Source               |")
    println("+----------------------------------+--------------------------------------+----------------------+")
    val diagnostics = com.issam.apollo.config.LlmConfig.configDiagnostics()
    for ((key, pair) in diagnostics) {
        val (rawValue, source) = pair
        // Redact API keys — show only the last 4 chars so they're identifiable but not leaked
        val displayValue = if (key.endsWith("_KEY") && rawValue.length > 4 && rawValue != "(empty)") {
            "*".repeat(rawValue.length - 4) + rawValue.takeLast(4)
        } else {
            rawValue
        }
        println("| %-32s | %-36s | %-20s |".format(key, displayValue.take(36), source.take(20)))
    }
    println("+----------------------------------+--------------------------------------+----------------------+")
    println()

    val orchestrator = ModernizationGraph(reportsDir = reportsDir)
    val finalState = orchestrator.runPipeline(targetPath, isResume = isResume)

    // Format & Print Per-Module Status Table
    println("\n==================================================")
    println("             Module Status Summary                ")
    println("==================================================")
    println("%-25s %-15s %-10s".format("Module Name", "Status", "Retries"))
    println("--------------------------------------------------")
    finalState.moduleStatuses.forEach { (className, status) ->
        val attempts = finalState.regenCounters[className] ?: 0
        val statusSymbol = when (status) {
            ModuleStatus.VERIFIED -> "[PASS] VERIFIED"
            ModuleStatus.FAILED -> "[FAIL] FAILED"
            ModuleStatus.MIGRATED -> "[MIGRATED]"
            ModuleStatus.ANALYZED -> "[ANALYZED]"
            ModuleStatus.PENDING -> "[PENDING]"
        }
        println("%-25s %-15s %-10d".format(className, statusSymbol, attempts))
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

    // Export runtime reports to reports/ directory via ResumeManager
    val reportFile = com.issam.apollo.orchestrator.ResumeManager.saveMigrationReport(finalState, reportsDir)
    println("Reports generated successfully: ${reportFile.name} under ${reportsDir.absolutePath}")

    // Explicitly terminate the JVM process so background thread pools / connection pools
    // do not keep Gradle hanging at 83% EXECUTING.
    val isFatalAbort = finalState.currentStage == "FATAL_WATCHDOG_ABORT"
    val isAllVerified = finalState.verificationResult.compiledSuccessfully &&
            finalState.moduleStatuses.values.all { it == ModuleStatus.VERIFIED }

    val exitCode = if (isFatalAbort || !isAllVerified) 1 else 0
    kotlin.system.exitProcess(exitCode)
}
