package com.issam.apollo.tools

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.zip.ZipFile

/**
 * Resolves declared Gradle/AndroidX dependencies using an isolated Gradle execution
 * and extracts usable classes.jar files from AARs with disk caching.
 */
object GradleDependencyResolver {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")
    private val defaultCacheRoot = File("").absoluteFile.resolve("libs/android-stubs/androidx-cache")

    /**
     * Resolves dependencies for [targetDir], extracts AAR classes.jar files, and returns all resolved JAR files.
     */
    fun resolveProjectDependencies(
        targetDir: File,
        cacheRootDir: File = defaultCacheRoot
    ): List<File> {
        val declaredDeps = GradleDependencyParser.parseProjectDependencies(targetDir)
        if (declaredDeps.isEmpty()) {
            return emptyList()
        }
        return resolveAndExtract(declaredDeps, cacheRootDir)
    }

    /**
     * Resolves the given list of dependencies and extracts their classes.
     */
    fun resolveAndExtract(
        dependencies: List<GradleDependency>,
        cacheRootDir: File = defaultCacheRoot
    ): List<File> {
        if (dependencies.isEmpty()) return emptyList()

        val sortedNotations = dependencies.map { it.notation }.sorted()
        val cacheKey = hashString(sortedNotations.joinToString(";"))
        val targetCacheDir = cacheRootDir.resolve(cacheKey)
        val markerFile = targetCacheDir.resolve(".complete")

        // 1. Check cache
        if (markerFile.exists() && targetCacheDir.isDirectory) {
            val cachedJars = targetCacheDir.listFiles()
                ?.filter { it.isFile && it.extension == "jar" }
                ?: emptyList()
            if (cachedJars.isNotEmpty()) {
                println("[GradleDependencyResolver] Using ${cachedJars.size} cached dependency jars from ${targetCacheDir.name}")
                return cachedJars
            }
        }

        println("[GradleDependencyResolver] Resolving ${dependencies.size} dependencies: ${sortedNotations.joinToString(", ")}...")
        targetCacheDir.mkdirs()

        // 2. Prepare temporary Gradle resolver project
        val resolverWorkDir = File("").absoluteFile.resolve("build/tmp/gradle-resolver-$cacheKey")
        resolverWorkDir.deleteRecursively()
        resolverWorkDir.mkdirs()

        val downloadDir = resolverWorkDir.resolve("downloaded")
        downloadDir.mkdirs()

        val buildGradle = resolverWorkDir.resolve("build.gradle")
        val depStatements = sortedNotations.joinToString("\n") { notation ->
            "    resolvedDeps '$notation'"
        }

        buildGradle.writeText(
            """
            plugins {
                id 'java-library'
            }

            repositories {
                google()
                mavenCentral()
            }

            configurations {
                resolvedDeps {
                    transitive = true
                    canBeResolved = true
                }
            }

            dependencies {
            $depStatements
            }

            task downloadDeps(type: Copy) {
                from configurations.resolvedDeps
                into file('downloaded')
            }
            """.trimIndent()
        )

        resolverWorkDir.resolve("settings.gradle").writeText("rootProject.name = 'apollo-dep-resolver'\n")

        // 3. Locate gradlew executable
        val gradlewBinary = findGradleWrapper()
        if (gradlewBinary == null || !gradlewBinary.exists()) {
            System.err.println("[GradleDependencyResolver] ERROR: Gradle wrapper not found. Skipping dependency resolution.")
            return emptyList()
        }

        // 4. Execute Gradle download task
        val command = mutableListOf<String>()
        if (isWindows) {
            command.addAll(listOf("cmd.exe", "/c", gradlewBinary.absolutePath))
        } else {
            command.add(gradlewBinary.absolutePath)
        }
        command.addAll(listOf("-p", resolverWorkDir.absolutePath, "downloadDeps", "--no-daemon", "--quiet"))

        try {
            val process = ProcessBuilder(command)
                .directory(resolverWorkDir)
                .redirectErrorStream(true)
                .start()

            val outputFuture = CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }

            val completed = process.waitFor(120, TimeUnit.SECONDS)
            if (!completed) {
                process.destroyForcibly()
                System.err.println("[GradleDependencyResolver] WARNING: Gradle resolution timed out after 120s.")
                return emptyList()
            }

            val output = outputFuture.get(5, TimeUnit.SECONDS)
            if (process.exitValue() != 0) {
                System.err.println("[GradleDependencyResolver] WARNING: Gradle resolution failed (exit code ${process.exitValue()}):\n$output")
                return emptyList()
            }
        } catch (e: Exception) {
            System.err.println("[GradleDependencyResolver] WARNING: Failed to execute Gradle: ${e.message}")
            return emptyList()
        }

        // 5. Extract AARs and copy JARs to targetCacheDir
        val downloadedFiles = downloadDir.listFiles() ?: emptyArray()
        val extractedJars = mutableListOf<File>()

        for (file in downloadedFiles) {
            if (file.extension.equals("aar", ignoreCase = true)) {
                val jars = extractAar(file, targetCacheDir)
                extractedJars.addAll(jars)
            } else if (file.extension.equals("jar", ignoreCase = true)) {
                val dest = targetCacheDir.resolve(file.name)
                file.copyTo(dest, overwrite = true)
                extractedJars.add(dest)
            }
        }

        // Write marker
        markerFile.writeText("Resolved ${extractedJars.size} jars from ${dependencies.size} root dependencies.\n")
        println("[GradleDependencyResolver] Successfully resolved & extracted ${extractedJars.size} jars.")

        return extractedJars
    }

    /**
     * Extracts classes.jar and any nested jars under libs/ from an .aar archive.
     */
    fun extractAar(aarFile: File, outputDir: File): List<File> {
        val extracted = mutableListOf<File>()
        outputDir.mkdirs()
        val baseName = aarFile.nameWithoutExtension

        try {
            ZipFile(aarFile).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == "classes.jar") {
                        val dest = outputDir.resolve("$baseName-classes.jar")
                        zip.getInputStream(entry).use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                        extracted.add(dest)
                    } else if (entry.name.startsWith("libs/") && entry.name.endsWith(".jar") && !entry.isDirectory) {
                        val nestedName = File(entry.name).name
                        val dest = outputDir.resolve("$baseName-libs-$nestedName")
                        zip.getInputStream(entry).use { input ->
                            dest.outputStream().use { output -> input.copyTo(output) }
                        }
                        extracted.add(dest)
                    }
                }
            }
        } catch (e: Exception) {
            System.err.println("[GradleDependencyResolver] Error extracting AAR ${aarFile.name}: ${e.message}")
        }

        return extracted
    }

    private fun findGradleWrapper(): File? {
        val apolloRoot = File("").absoluteFile
        val candidate = if (isWindows) apolloRoot.resolve("gradlew.bat") else apolloRoot.resolve("gradlew")
        if (candidate.exists()) return candidate

        // Search PATH for gradle
        val pathDirs = (System.getenv("PATH") ?: "").split(File.pathSeparator)
        val gradleName = if (isWindows) "gradle.bat" else "gradle"
        for (dir in pathDirs) {
            val f = File(dir, gradleName)
            if (f.exists()) return f
        }
        return null
    }

    private fun hashString(input: String): String {
        val bytes = MessageDigest.getInstance("MD5").digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }
}
