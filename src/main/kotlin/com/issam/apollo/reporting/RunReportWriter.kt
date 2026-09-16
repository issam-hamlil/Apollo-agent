package com.issam.apollo.reporting

import com.issam.apollo.config.LlmConfig
import com.issam.apollo.config.MigrationPolicy
import com.issam.apollo.knowledge.DeprecationScanner
import com.issam.apollo.state.GraphState
import com.issam.apollo.state.ModuleStatus
import com.issam.apollo.telemetry.ApolloTelemetry
import com.issam.apollo.tools.GradleDependencyParser
import java.io.File
import java.time.Instant

/**
 * Writes the three artifacts a run produces, each with a distinct audience:
 *
 *  - `reports/logs/`    - the blow-by-blow timeline of every stage. Diagnostic detail.
 *  - `reports/success/` - what a completed migration actually delivered.
 *  - `reports/failed/`  - what did not make it, why, and what the model was asked.
 *
 * A run writes a log every time, plus exactly one of success/failed.
 */
object RunReportWriter {

    data class WrittenReports(val log: File, val outcome: File, val succeeded: Boolean)

    fun write(state: GraphState, reportsDir: File): WrittenReports {
        val stamp = System.currentTimeMillis()
        val succeeded = isSuccessful(state)

        val logsDir = File(reportsDir, "logs").apply { mkdirs() }
        val outcomeDir = File(reportsDir, if (succeeded) "success" else "failed").apply { mkdirs() }

        val log = File(logsDir, "run-log-$stamp.md")
        log.writeText(renderLog(state))

        val outcome = if (succeeded) {
            File(outcomeDir, "migration-success-$stamp.md").apply { writeText(renderSuccess(state)) }
        } else {
            File(outcomeDir, "migration-failure-$stamp.md").apply { writeText(renderFailure(state)) }
        }

        return WrittenReports(log, outcome, succeeded)
    }

    /** A run counts as successful only if it compiled AND every module verified. */
    fun isSuccessful(state: GraphState): Boolean {
        val v = state.verificationResult
        return v.compiledSuccessfully &&
            v.testsFailed == 0 &&
            state.moduleStatuses.isNotEmpty() &&
            state.moduleStatuses.values.all { it == ModuleStatus.VERIFIED } &&
            state.currentStage != "FATAL_WATCHDOG_ABORT" &&
            state.currentStage != "ENVIRONMENT_PREFLIGHT_FAILED"
    }

    // ── Log ────────────────────────────────────────────────────────────────────

    private fun renderLog(state: GraphState): String = buildString {
        appendLine("# Apollo Run Log")
        appendLine()
        appendLine("Chronological record of every stage this pipeline executed.")
        appendLine("For the outcome of the run, see `reports/success/` or `reports/failed/`.")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|-------|-------|")
        appendLine("| Target project | `${state.targetProjectPath}` |")
        appendLine("| Written at | `${Instant.now()}` |")
        appendLine("| Final stage | `${state.currentStage}` |")
        appendLine("| Pipeline passes | `${state.retryCount}` |")
        appendLine("| Stage entries | `${state.reports.size}` |")
        appendLine()

        appendLine("## Timeline")
        appendLine()
        appendLine("| # | Stage | Status | Timestamp | Details |")
        appendLine("|---|-------|--------|-----------|---------|")
        state.reports.forEachIndexed { i, r ->
            val details = r.details.replace("\n", " ").replace("|", "\\|").take(220)
            appendLine("| ${i + 1} | `${r.stageName}` | `${r.status}` | `${r.timestamp}` | $details |")
        }
        appendLine()

        appendLine("## Stage metrics")
        state.reports.forEachIndexed { i, r ->
            if (r.metrics.isEmpty()) return@forEachIndexed
            appendLine()
            appendLine("### ${i + 1}. ${r.stageName} (`${r.status}`)")
            r.metrics.toSortedMap().forEach { (k, v) -> appendLine("- **$k**: `$v`") }
        }
        appendLine()

        appendLine("## LLM traffic")
        val exchanges = ApolloTelemetry.allExchanges()
        if (exchanges.isEmpty()) {
            appendLine()
            appendLine("_No LLM requests were made in this run (all stages resumed from cache)._")
        } else {
            appendLine()
            appendLine("| Module | Provider | Model | Attempt | Prompt chars | Outcome |")
            appendLine("|--------|----------|-------|---------|--------------|---------|")
            exchanges.forEach { e ->
                val outcome = when {
                    !e.finished -> "in flight"
                    e.ok -> "replied"
                    else -> "failed: ${e.error.take(60)}"
                }
                appendLine(
                    "| `${e.module}` | ${e.provider} | `${e.model}` | ${e.attempt} | " +
                        "${e.systemPrompt.length + e.userPrompt.length} | $outcome |"
                )
            }
        }
    }

    // ── Success ────────────────────────────────────────────────────────────────

    private fun renderSuccess(state: GraphState): String = buildString {
        val v = state.verificationResult
        appendLine("# Migration Succeeded")
        appendLine()
        appendLine("Every module compiled and reproduced the behaviour recorded from the original Java.")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|-------|-------|")
        appendLine("| Target project | `${state.targetProjectPath}` |")
        appendLine("| Completed at | `${Instant.now()}` |")
        appendLine("| Modules migrated | `${state.moduleStatuses.size}` |")
        appendLine("| Behaviour tests passed | `${v.testsPassed} / ${v.testsPassed + v.testsFailed}` |")
        appendLine("| Pipeline passes needed | `${state.retryCount}` |")
        appendLine("| Minimum supported Android | `API ${MigrationPolicy.minSdk} (${MigrationPolicy.minSdkName})` |")
        appendLine("| Primary model | `${LlmConfig.ollamaModelPrimary}` |")
        appendLine("| Escalation model | `${LlmConfig.ollamaModelEscalation}` |")
        appendLine()

        appendLine("## Modules delivered")
        appendLine()
        appendLine("| Module | Package | Kotlin lines | Repair attempts | Source |")
        appendLine("|--------|---------|--------------|-----------------|--------|")
        state.moduleStatuses.keys.sorted().forEach { name ->
            val spec = state.moduleSpecs[name]
            val lines = state.migratedCode["$name.kt"]?.lines()?.size ?: 0
            val attempts = state.regenCounters[name] ?: 0
            val src = spec?.sourceFilePath?.let { File(it).name } ?: "-"
            appendLine("| `$name` | `${spec?.packageName ?: "-"}` | $lines | $attempts | `$src` |")
        }
        appendLine()

        appendLine("## Dependencies and libraries")
        appendLine()
        appendSection(this, state)

        appendLine("## Deprecated APIs modernised")
        appendLine()
        appendDeprecationOutcome(this, state)

        appendLine("## Generated artifacts")
        appendLine()
        appendLine("| Artifact | Location |")
        appendLine("|----------|----------|")
        appendLine("| Migrated Kotlin | `migrated-src/` |")
        appendLine("| Module specs (Stage 1) | `reports/specs/` |")
        appendLine("| Ground truth (Stage 2) | `reports/characterization/` |")
        appendLine("| Verification detail (Stage 4) | `reports/verification/` |")
        appendLine("| Full timeline | `reports/logs/` |")
    }

    /** Dependency/library detail shared by the success report. */
    private fun appendSection(sb: StringBuilder, state: GraphState) = with(sb) {
        val projectRoot = File("").absoluteFile
        val declared = runCatching {
            GradleDependencyParser.parseProjectDependencies(File(state.targetProjectPath))
        }.getOrDefault(emptyList())

        if (declared.isEmpty()) {
            appendLine("_No Gradle dependency declarations were found for this project._")
        } else {
            appendLine("Declared by the original project:")
            appendLine()
            appendLine("| Group | Artifact | Version |")
            appendLine("|-------|----------|---------|")
            declared.forEach { appendLine("| `${it.group}` | `${it.name}` | `${it.version}` |") }
        }
        appendLine()

        val stubDir = File(projectRoot, "libs/android-stubs")
        val jars = if (stubDir.exists()) {
            stubDir.walkTopDown().filter { it.isFile && it.extension == "jar" }.toList()
        } else emptyList()

        appendLine("Resolved onto the verification classpath: **${jars.size} jar(s)**")
        if (jars.isNotEmpty()) {
            appendLine()
            appendLine("<details><summary>Jars used to compile the migrated Kotlin</summary>")
            appendLine()
            jars.sortedBy { it.name }.forEach { appendLine("- `${it.name}`") }
            appendLine()
            appendLine("</details>")
        }
        appendLine()

        val rFiles = File(projectRoot, "build/generated-r/r-src")
            .takeIf { it.exists() }
            ?.walkTopDown()?.filter { it.isFile && it.name == "R.java" }?.toList()
            .orEmpty()
        appendLine("Android resources compiled by AAPT2: **${rFiles.size} R.java file(s)**")
        appendLine()

        // Framework family changes matter: they force the host app to be migrated too.
        val migrated = state.migratedCode.values.joinToString("\n")
        val usesAndroidX = migrated.contains("import androidx.")
        val originalUsesSupport = runCatching {
            File(state.targetProjectPath).walkTopDown()
                .filter { it.isFile && it.extension == "java" }
                .any { it.readText().contains("import android.support.") }
        }.getOrDefault(false)

        if (usesAndroidX && originalUsesSupport) {
            appendLine("> **Framework change applied:** the original used `android.support.*` and the")
            appendLine("> migrated Kotlin uses `androidx.*`. The host application must be on AndroidX")
            appendLine("> (`android.useAndroidX=true`) and depend on `androidx.appcompat` for this to build.")
            appendLine()
        }
    }

    /**
     * What the legacy Java used that was deprecated, and whether the migrated Kotlin still
     * uses it. Reporting the misses matters as much as the wins - a deprecated call that
     * survived the migration is exactly the thing a reader needs to know about.
     */
    private fun appendDeprecationOutcome(sb: StringBuilder, state: GraphState) = with(sb) {
        val scanner = DeprecationScanner()
        val findings = runCatching {
            scanner.scanProject(File(state.targetProjectPath))
        }.getOrDefault(emptyList())

        if (findings.isEmpty()) {
            appendLine("_No deprecated APIs were detected in the original Java._")
            appendLine()
            return@with
        }

        val migratedText = state.migratedCode.values.joinToString(separator = " ")

        appendLine("| Deprecated API | Deprecated since | Replaced by | Outcome |")
        appendLine("|----------------|------------------|-------------|---------|")
        findings.forEach { f ->
            val stillPresent = f.matched.any { migratedText.contains(it) }
            val outcome = if (stillPresent) "still present - review" else "modernised"
            appendLine(
                "| `${f.migration.id}` (${f.matched.joinToString(", ")}) | ${f.migration.deprecatedSince} | " +
                    "${f.migration.modern} | **$outcome** |"
            )
        }
        appendLine()

        val remaining = findings.filter { f -> f.matched.any { migratedText.contains(it) } }
        if (remaining.isNotEmpty()) {
            appendLine("> ${remaining.size} deprecated API(s) survived the migration. They compile and pass")
            appendLine("> behaviour tests, but they are still deprecated upstream and worth a follow-up pass.")
            appendLine()
        }
    }

    // ── Failure ────────────────────────────────────────────────────────────────

    private fun renderFailure(state: GraphState): String = buildString {
        val v = state.verificationResult
        val failed = state.moduleStatuses.filterValues { it != ModuleStatus.VERIFIED }.keys.sorted()
        val passed = state.moduleStatuses.filterValues { it == ModuleStatus.VERIFIED }.keys.sorted()

        appendLine("# Migration Did Not Complete")
        appendLine()
        appendLine("| Field | Value |")
        appendLine("|-------|-------|")
        appendLine("| Target project | `${state.targetProjectPath}` |")
        appendLine("| Stopped at | `${Instant.now()}` |")
        appendLine("| Halt reason | `${state.currentStage}` |")
        appendLine("| Compiled | `${v.compiledSuccessfully}` |")
        appendLine("| Compile errors | `${v.compilerErrors.count { !it.trimStart().startsWith("warning:") }}` |")
        appendLine("| Behaviour tests | `${v.testsPassed} passed, ${v.testsFailed} failed` |")
        appendLine("| Modules failed | `${failed.size} of ${state.moduleStatuses.size}` |")
        appendLine()

        state.reports.lastOrNull { it.stageName == "STAGE_FATAL_WATCHDOG" }?.let {
            appendLine("## Why it stopped")
            appendLine()
            appendLine("```")
            appendLine(it.details)
            appendLine("```")
            appendLine()
        }

        appendLine("## Modules that succeeded (${passed.size})")
        appendLine()
        if (passed.isEmpty()) appendLine("_None._")
        else passed.forEach { appendLine("- `$it`") }
        appendLine()

        appendLine("## Modules that failed (${failed.size})")
        appendLine()
        if (failed.isEmpty()) {
            appendLine("_No module is individually marked failed - the run halted for a pipeline-level reason._")
        } else {
            appendLine("| Module | Status | Repair attempts |")
            appendLine("|--------|--------|-----------------|")
            failed.forEach {
                appendLine("| `$it` | `${state.moduleStatuses[it]}` | `${state.regenCounters[it] ?: 0}` |")
            }
        }
        appendLine()

        appendLine("## Errors")
        appendLine()
        val hardErrors = v.compilerErrors.filterNot { it.trimStart().startsWith("warning:") }
        if (hardErrors.isEmpty()) {
            appendLine("_No compiler errors - the failures are behavioural (see below)._")
        } else {
            appendLine("### Compiler errors (${hardErrors.size})")
            appendLine()
            appendLine("```")
            hardErrors.forEach { appendLine(it) }
            appendLine("```")
        }
        appendLine()

        if (v.testFailures.isNotEmpty()) {
            appendLine("### Behaviour mismatches (${v.testFailures.size})")
            appendLine()
            appendLine("```")
            v.testFailures.forEach { appendLine(it) }
            appendLine("```")
            appendLine()
        }

        appendLine("## Prompts sent for the failed modules")
        appendLine()
        appendLine("_Only the modules that failed are included, so this shows what the model was")
        appendLine("actually working from when it could not produce a fix._")
        appendLine()

        if (failed.isEmpty()) {
            appendLine("_No failed modules to report prompts for._")
        } else {
            failed.forEach { module ->
                val exchanges = ApolloTelemetry.exchangesFor(module)
                appendLine("### `$module` - ${exchanges.size} request(s)")
                appendLine()
                if (exchanges.isEmpty()) {
                    appendLine("_No LLM request was recorded for this module in this run._")
                    appendLine()
                    return@forEach
                }
                exchanges.forEach { e ->
                    val verdict = when {
                        !e.finished -> "never returned"
                        e.ok -> "replied"
                        else -> "failed: ${e.error}"
                    }
                    appendLine("<details><summary>Attempt ${e.attempt} - ${e.model} ($verdict)</summary>")
                    appendLine()
                    appendLine("**System prompt**")
                    appendLine()
                    appendLine("```")
                    appendLine(e.systemPrompt)
                    appendLine("```")
                    appendLine()
                    appendLine("**User prompt**")
                    appendLine()
                    appendLine("```")
                    appendLine(e.userPrompt)
                    appendLine("```")
                    appendLine()
                    appendLine("**Reply**")
                    appendLine()
                    appendLine("```")
                    appendLine(if (e.ok) e.response else e.error.ifBlank { "(no reply)" })
                    appendLine("```")
                    appendLine()
                    appendLine("</details>")
                    appendLine()
                }
            }
        }
    }
}
