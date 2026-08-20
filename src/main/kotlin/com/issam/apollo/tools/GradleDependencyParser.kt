package com.issam.apollo.tools

import java.io.File

/**
 * Declared dependency coordinates.
 */
data class GradleDependency(
    val group: String,
    val name: String,
    val version: String,
    val notation: String = "$group:$name:$version"
)

/**
 * Parser for extracting declared dependencies from Gradle build files (build.gradle and build.gradle.kts).
 */
object GradleDependencyParser {

    private val dependencyConfigKeywords = listOf(
        "implementation", "api", "compile", "testImplementation", "testCompile",
        "androidTestImplementation", "debugImplementation", "releaseImplementation",
        "annotationProcessor", "kapt", "ksp"
    )

    private val configPattern = Regex(
        """\b(?:${dependencyConfigKeywords.joinToString("|")})\s*\(?\s*['"]([^'":\s]+):([^'":\s]+)(?::([^'":\s]+))?['"]\s*\)?""",
        RegexOption.IGNORE_CASE
    )

    // Match map-style dependencies, e.g. group: 'com.android.support', name: 'appcompat-v7', version: '24.2.0'
    private val mapStylePattern = Regex(
        """\b(?:${dependencyConfigKeywords.joinToString("|")})\s*\(?\s*group\s*:\s*['"]([^'"]+)['"]\s*,\s*name\s*:\s*['"]([^'"]+)['"]\s*,\s*version\s*:\s*['"]([^'"]+)['"]\s*\)?""",
        RegexOption.IGNORE_CASE
    )

    /**
     * Locates all build.gradle / build.gradle.kts files relevant to [targetPath]
     * and extracts all declared dependencies.
     */
    fun parseProjectDependencies(targetDir: File): List<GradleDependency> {
        val rootDir = findProjectRoot(targetDir)
        val buildFiles = findBuildFiles(rootDir)
        val variables = extractAllVariables(buildFiles, rootDir)

        val dependencies = mutableListOf<GradleDependency>()
        val seenNotations = mutableSetOf<String>()

        for (file in buildFiles) {
            val fileDeps = parseBuildFile(file, variables)
            for (dep in fileDeps) {
                if (seenNotations.add(dep.notation)) {
                    dependencies.add(dep)
                }
            }
        }

        // Also check version catalog (gradle/libs.versions.toml) if present
        val tomlFile = rootDir.resolve("gradle/libs.versions.toml")
        if (tomlFile.exists()) {
            val catalogDeps = parseVersionCatalog(tomlFile)
            for (dep in catalogDeps) {
                if (seenNotations.add(dep.notation)) {
                    dependencies.add(dep)
                }
            }
        }

        return dependencies
    }

    /**
     * Parses a single build.gradle or build.gradle.kts file.
     */
    fun parseBuildFile(file: File, variables: Map<String, String> = emptyMap()): List<GradleDependency> {
        if (!file.exists() || !file.isFile) return emptyList()
        val content = file.readText()
        val localVars = extractVariablesFromContent(content).toMutableMap().apply { putAll(variables) }

        val deps = mutableListOf<GradleDependency>()

        // 1. Standard string coordinate matching
        for (match in configPattern.findAll(content)) {
            val group = resolveVariable(match.groupValues[1].trim(), localVars)
            val name = resolveVariable(match.groupValues[2].trim(), localVars)
            val rawVersion = match.groupValues.getOrNull(3)?.trim() ?: ""
            val version = resolveVariable(rawVersion, localVars)

            if (group.isNotBlank() && name.isNotBlank() && version.isNotBlank() && !version.startsWith("$")) {
                deps.add(GradleDependency(group, name, version))
            } else if (group.isNotBlank() && name.isNotBlank() && version.isBlank()) {
                // Dependency without explicit version (e.g. BOM managed)
                println("[GradleDependencyParser] Note: Found unversioned dependency $group:$name in ${file.name}")
            } else if (version.startsWith("$")) {
                println("[GradleDependencyParser] Warning: Could not resolve version variable '$version' for $group:$name in ${file.name}")
            }
        }

        // 2. Map-style notation matching
        for (match in mapStylePattern.findAll(content)) {
            val group = resolveVariable(match.groupValues[1].trim(), localVars)
            val name = resolveVariable(match.groupValues[2].trim(), localVars)
            val version = resolveVariable(match.groupValues[3].trim(), localVars)

            if (group.isNotBlank() && name.isNotBlank() && version.isNotBlank() && !version.startsWith("$")) {
                deps.add(GradleDependency(group, name, version))
            }
        }

        return deps
    }

    /**
     * Parses gradle/libs.versions.toml version catalog.
     */
    fun parseVersionCatalog(tomlFile: File): List<GradleDependency> {
        if (!tomlFile.exists()) return emptyList()
        val content = tomlFile.readText()
        val versions = mutableMapOf<String, String>()
        val deps = mutableListOf<GradleDependency>()

        var currentSection = ""
        for (line in content.lines()) {
            val trimmed = line.trim()
            if (trimmed.startsWith("#") || trimmed.isBlank()) continue

            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                currentSection = trimmed.substring(1, trimmed.length - 1).trim().lowercase()
                continue
            }

            if (currentSection == "versions") {
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    val key = parts[0].trim()
                    val value = parts[1].trim().trim('"', '\'')
                    versions[key] = value
                }
            } else if (currentSection == "libraries") {
                // Matches: alias = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
                // or: alias = "androidx.appcompat:appcompat:1.7.0"
                if (trimmed.contains("group") && trimmed.contains("name")) {
                    val group = Regex("""group\s*=\s*['"]([^'"]+)['"]""").find(trimmed)?.groupValues?.get(1)
                    val name = Regex("""name\s*=\s*['"]([^'"]+)['"]""").find(trimmed)?.groupValues?.get(1)
                    val versionRef = Regex("""version\.ref\s*=\s*['"]([^'"]+)['"]""").find(trimmed)?.groupValues?.get(1)
                    val directVersion = Regex("""version\s*=\s*['"]([^'"]+)['"]""").find(trimmed)?.groupValues?.get(1)
                    val version = directVersion ?: versionRef?.let { versions[it] }

                    if (group != null && name != null && version != null) {
                        deps.add(GradleDependency(group, name, version))
                    }
                } else if (trimmed.contains(":")) {
                    val coordMatch = Regex("""['"]([^'":\s]+):([^'":\s]+):([^'":\s]+)['"]""").find(trimmed)
                    if (coordMatch != null) {
                        deps.add(
                            GradleDependency(
                                coordMatch.groupValues[1],
                                coordMatch.groupValues[2],
                                coordMatch.groupValues[3]
                            )
                        )
                    }
                }
            }
        }

        return deps
    }

    /**
     * Walks up the directory tree to find the project root.
     */
    fun findProjectRoot(startDir: File): File {
        val canonicalStart = startDir.canonicalFile
        val apolloRoot = File("").canonicalFile

        var current: File? = if (canonicalStart.isFile) canonicalStart.parentFile else canonicalStart
        var fallback = current ?: File(".")

        while (current != null) {
            // If we reached Apollo-agent's own workspace root and the target is an internal subdirectory (like sample-legacy),
            // do not treat Apollo-agent's build files as the target project's build files!
            if (current == apolloRoot && canonicalStart != apolloRoot && canonicalStart.startsWith(apolloRoot)) {
                return canonicalStart
            }

            if (File(current, "settings.gradle").exists() ||
                File(current, "settings.gradle.kts").exists() ||
                File(current, "build.gradle").exists() ||
                File(current, "build.gradle.kts").exists() ||
                File(current, "gradlew").exists()
            ) {
                return current
            }
            fallback = current
            current = current.parentFile
        }
        return fallback
    }

    /**
     * Finds all build.gradle and build.gradle.kts files in the project.
     */
    fun findBuildFiles(rootDir: File): List<File> {
        if (!rootDir.exists()) return emptyList()
        return rootDir.walkTopDown()
            .onEnter { dir -> !dir.name.equals("build", ignoreCase = true) && !dir.name.startsWith(".") }
            .maxDepth(4)
            .filter { it.isFile && (it.name == "build.gradle" || it.name == "build.gradle.kts") }
            .toList()
    }

    private fun extractAllVariables(buildFiles: List<File>, rootDir: File): Map<String, String> {
        val vars = mutableMapOf<String, String>()
        for (file in buildFiles) {
            vars.putAll(extractVariablesFromContent(file.readText()))
        }
        // Also check gradle.properties
        val propsFile = rootDir.resolve("gradle.properties")
        if (propsFile.exists()) {
            for (line in propsFile.readLines()) {
                val trimmed = line.trim()
                if (trimmed.startsWith("#") || !trimmed.contains("=")) continue
                val parts = trimmed.split("=", limit = 2)
                if (parts.size == 2) {
                    vars[parts[0].trim()] = parts[1].trim()
                }
            }
        }
        return vars
    }

    private fun extractVariablesFromContent(content: String): Map<String, String> {
        val vars = mutableMapOf<String, String>()
        // Match def varName = "...", val varName = "...", ext.varName = "...", varName = "..."
        val varRegex = Regex("""(?:def|val|var|ext\.)?\s*([a-zA-Z0-9_]+)\s*=\s*['"]([^'"]+)['"]""")
        for (match in varRegex.findAll(content)) {
            val key = match.groupValues[1].trim()
            val value = match.groupValues[2].trim()
            vars[key] = value
            vars["$$key"] = value
            vars["\${$key}"] = value
        }
        return vars
    }

    private fun resolveVariable(raw: String, variables: Map<String, String>): String {
        var resolved = raw
        // Handle "$varName" or "${varName}" or direct variable names
        for ((k, v) in variables) {
            if (resolved == k || resolved == "$$k" || resolved == "\${$k}") {
                return v
            }
            resolved = resolved.replace("$$k", v).replace("\${$k}", v)
        }
        return resolved
    }
}
