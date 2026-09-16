package com.issam.apollo.knowledge

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class DeprecatedApiMigration(
    val id: String,
    /** DEPENDENCY (a library coordinate) or API (a symbol in source). */
    val kind: String,
    /** Substrings that indicate this deprecated API is in use. */
    val match: List<String> = emptyList(),
    val deprecatedSince: String = "",
    val modern: String = "",
    val guidance: String = ""
)

@Serializable
private data class DeprecationDatabase(val migrations: List<DeprecatedApiMigration> = emptyList())

/** A deprecated API found in a specific piece of source. */
data class DeprecationFinding(
    val migration: DeprecatedApiMigration,
    /** The exact strings that matched, so a report can show the evidence. */
    val matched: List<String>
)

/**
 * Finds deprecated APIs and dependencies in legacy Java source.
 *
 * Only findings that are genuinely present get injected into a prompt. Handing a model a
 * catalogue of every possible modernisation invites it to "fix" code that does not exist,
 * and drowns the real instructions.
 *
 * Deliberately one-directional: this says what is deprecated and what replaced it. It never
 * tells the model to keep the original libraries - choosing a better library is allowed.
 */
class DeprecationScanner(
    private val kbFile: File = File("knowledge-base/deprecated-api-migrations.json")
) {

    private val json = Json { ignoreUnknownKeys = true }

    private val all: List<DeprecatedApiMigration> by lazy {
        if (!kbFile.exists()) {
            println("[DeprecationScanner] Warning: ${kbFile.path} not found - deprecation modernisation is disabled.")
            emptyList()
        } else {
            runCatching { json.decodeFromString<DeprecationDatabase>(kbFile.readText()).migrations }
                .getOrElse {
                    System.err.println("[DeprecationScanner] Could not parse ${kbFile.path}: ${it.message}")
                    emptyList()
                }
        }
    }

    fun allMigrations(): List<DeprecatedApiMigration> = all

    /** Deprecated APIs used by [source]. */
    fun scanSource(source: String): List<DeprecationFinding> {
        if (source.isBlank()) return emptyList()
        return all.mapNotNull { migration ->
            val hits = migration.match.filter { it.isNotBlank() && source.contains(it) }
            if (hits.isEmpty()) null else DeprecationFinding(migration, hits)
        }
    }

    /** Deprecated APIs across every .java file under [dir], plus declared dependency notations. */
    fun scanProject(dir: File, dependencyNotations: List<String> = emptyList()): List<DeprecationFinding> {
        val sources = if (!dir.exists()) "" else dir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .take(500)
            .joinToString("\n") { runCatching { it.readText() }.getOrDefault("") }

        return scanSource(sources + "\n" + dependencyNotations.joinToString("\n"))
    }

    /**
     * Renders findings as prompt guidance. Returns an empty string when nothing was found,
     * so callers can append it unconditionally without adding a useless heading.
     */
    fun formatForPrompt(findings: List<DeprecationFinding>): String {
        if (findings.isEmpty()) return ""
        return buildString {
            appendLine("DEPRECATED APIs DETECTED IN THIS SOURCE - modernise each one:")
            findings.forEachIndexed { i, f ->
                appendLine()
                appendLine("${i + 1}. ${f.migration.id}")
                appendLine("   Found: ${f.matched.joinToString(", ")}")
                if (f.migration.deprecatedSince.isNotBlank()) {
                    appendLine("   Deprecated: ${f.migration.deprecatedSince}")
                }
                if (f.migration.modern.isNotBlank()) {
                    appendLine("   Replace with: ${f.migration.modern}")
                }
                if (f.migration.guidance.isNotBlank()) {
                    appendLine("   How: ${f.migration.guidance}")
                }
            }
        }
    }
}
