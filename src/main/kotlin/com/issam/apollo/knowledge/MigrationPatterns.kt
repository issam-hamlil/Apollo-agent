package com.issam.apollo.knowledge

import com.issam.apollo.state.ModuleSpec
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class MigrationPattern(
    val id: String,
    val name: String,
    val javaPattern: String,
    val kotlinPattern: String,
    val category: String,
    val description: String
)

@Serializable
data class PatternDatabase(
    val patterns: List<MigrationPattern>
)

/**
 * Knowledge Base loader and manager for curated Java-to-Kotlin migration patterns.
 */
class MigrationPatterns(private val kbFile: File = File("knowledge-base/java-to-kotlin-patterns.json")) {

    private val jsonFormatter = Json { ignoreUnknownKeys = true }
    private var cachedPatterns: List<MigrationPattern> = emptyList()

    init {
        loadPatterns()
    }

    fun loadPatterns(): List<MigrationPattern> {
        if (!kbFile.exists()) {
            println("[MigrationPatterns] Warning: Knowledge base file ${kbFile.path} does not exist.")
            return emptyList()
        }
        return try {
            val content = kbFile.readText()
            val db = jsonFormatter.decodeFromString<PatternDatabase>(content)
            cachedPatterns = db.patterns
            cachedPatterns
        } catch (e: Exception) {
            println("[MigrationPatterns] Error reading knowledge base: ${e.message}")
            emptyList()
        }
    }

    fun getAllPatterns(): List<MigrationPattern> = cachedPatterns.ifEmpty { loadPatterns() }

    /**
     * Finds migration patterns relevant to a specific Java source code snippet.
     */
    fun findMatchingPatterns(javaCode: String): List<MigrationPattern> {
        val patterns = getAllPatterns()
        return patterns.filter { pattern ->
            when (pattern.category) {
                "STRUCTURE" -> javaCode.contains("class") || javaCode.contains("interface") || javaCode.contains("getInstance") || javaCode.contains("SQLite") || javaCode.contains("Database")
                "NULL_SAFETY" -> javaCode.contains("!= null") || javaCode.contains("== null") || javaCode.contains("Optional")
                "SYNTAX" -> javaCode.contains("StringBuilder") || javaCode.contains("switch") || javaCode.contains("System.out.println") || javaCode.contains("get")
                "COLLECTIONS" -> javaCode.contains("for (") || javaCode.contains("for(") || javaCode.contains("ArrayList") || javaCode.contains("List<") || javaCode.contains("Set<") || javaCode.contains("HashSet")
                "UTILITY" -> javaCode.contains("static") && javaCode.contains("class")
                "CONCURRENCY_EVENTS" -> javaCode.contains("Thread") || javaCode.contains("AsyncTask") || javaCode.contains("Callback") || javaCode.contains("Listener")
                "ASYNC/COROUTINES", "ASYNC_COROUTINES" -> javaCode.contains("Callback") || javaCode.contains("Listener") || javaCode.contains("Runnable") || javaCode.contains("Thread") || javaCode.contains("onSuccess") || javaCode.contains("onError")
                "UI" -> javaCode.contains("Layout") || javaCode.contains("View") || javaCode.contains("<") || javaCode.contains("Activity") || javaCode.contains("findViewById") || javaCode.contains("Menu")
                "RESOURCE_MANAGEMENT" -> javaCode.contains("try (") || javaCode.contains("try(") || javaCode.contains("Closeable") || javaCode.contains("File") || javaCode.contains("readLines") || javaCode.contains("writeLines")
                // Ground-truth equivalence rules apply to every module - they describe how to
                // preserve observable behaviour, not a syntactic construct to look for.
                "BEHAVIORAL_EQUIVALENCE" -> true
                "ANDROID_SQLITE" -> javaCode.contains("SQLite") || javaCode.contains("SQLiteOpenHelper") || javaCode.contains("Cursor") || javaCode.contains("ContentValues")
                "ANDROID_UI" -> javaCode.contains("Activity") || javaCode.contains("Menu") || javaCode.contains("View") || javaCode.contains("Bundle")
                else -> true
            }
        }
    }

    /**
     * Finds patterns matching a given ModuleSpec.
     */
    fun findMatchingPatternsForSpec(spec: ModuleSpec, javaCode: String): List<MigrationPattern> {
        val matches = findMatchingPatterns(javaCode).toMutableList()
        val all = getAllPatterns()

        // Ensure baseline structural & null safety patterns are included for every class
        all.firstOrNull { it.id == "java-pojo-to-kotlin-data-class" }?.let { if (!matches.contains(it)) matches.add(it) }
        all.firstOrNull { it.id == "null-check-to-elvis" }?.let { if (!matches.contains(it)) matches.add(it) }

        return matches
    }

    /**
     * Formats selected patterns into a clear, structured prompt context string for the LLM.
     */
    fun formatPatternsForPrompt(patterns: List<MigrationPattern> = getAllPatterns()): String {
        if (patterns.isEmpty()) return "No curated migration patterns provided."

        return patterns.joinToString("\n\n") { p ->
            """
            |### Pattern: ${p.name} [ID: ${p.id} | Category: ${p.category}]
            |Description: ${p.description}
            |Java Pattern:
            |```java
            |${p.javaPattern}
            |```
            |Modern Kotlin Equivalent:
            |```kotlin
            |${p.kotlinPattern}
            |```
            """.trimMargin()
        }
    }
}
