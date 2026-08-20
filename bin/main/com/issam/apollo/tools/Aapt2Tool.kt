package com.issam.apollo.tools

import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * Result of an AAPT2 R.java generation run.
 */
data class Aapt2Result(
    val success: Boolean,
    val rJavaFiles: List<File> = emptyList(),
    val packageName: String? = null,
    val outputDir: File? = null,
    val errorMessage: String? = null
)

/**
 * Stage 0 / Resource Generation Tool — AAPT2
 *
 * Uses the Android SDK's AAPT2 binary to compile resources from res/ and link them
 * against AndroidManifest.xml and android.jar, generating the authentic R.java file.
 */
object Aapt2Tool {

    private val isWindows = System.getProperty("os.name").lowercase().contains("win")

    /**
     * Attempts to locate Android resources, manifest, and generate R.java for [targetDir].
     * Degrades gracefully if res/ or manifest is missing or if AAPT2 fails on malformed XML.
     */
    fun generateRForProject(
        targetDir: File,
        outputBaseDir: File = File("").absoluteFile.resolve("build/generated-r")
    ): Aapt2Result {
        val resDir = findResDirectory(targetDir)
        if (resDir == null) {
            println("[Aapt2Tool] No valid 'res/' directory found for target ${targetDir.path}. Skipping R.java generation.")
            return Aapt2Result(success = false, errorMessage = "No res/ directory found")
        }

        val manifestFile = findManifestFile(targetDir)
        if (manifestFile == null) {
            println("[Aapt2Tool] No 'AndroidManifest.xml' found for target ${targetDir.path}. Skipping R.java generation.")
            return Aapt2Result(success = false, errorMessage = "No AndroidManifest.xml found")
        }

        val aapt2Binary = try {
            AndroidSdkResolver.findAapt2Binary()
        } catch (e: Exception) {
            null
        }

        if (aapt2Binary == null || !aapt2Binary.exists()) {
            System.err.println("[Aapt2Tool] WARNING: AAPT2 binary not available. Skipping R.java generation.")
            return Aapt2Result(success = false, errorMessage = "AAPT2 binary not found")
        }

        val androidJar = try {
            AndroidSdkResolver.requireAndroidStubJar()
        } catch (e: Exception) {
            System.err.println("[Aapt2Tool] WARNING: android.jar not found at ${AndroidSdkResolver.defaultStubJarPath}. Skipping R.java generation.")
            return Aapt2Result(success = false, errorMessage = "android.jar not found: ${e.message}")
        }

        val packageName = extractPackageName(manifestFile, targetDir)
        println("[Aapt2Tool] Found resources at ${resDir.absolutePath} (package: $packageName)")

        val projectOutputDir = outputBaseDir.resolve("r-src")
        projectOutputDir.deleteRecursively()
        projectOutputDir.mkdirs()

        return invokeAapt2(aapt2Binary, androidJar, resDir, manifestFile, projectOutputDir, packageName)
    }

    /**
     * Executes AAPT2 compile and link steps to produce R.java.
     */
    fun invokeAapt2(
        aapt2Binary: File,
        androidJar: File,
        resDir: File,
        manifestFile: File,
        outputJavaDir: File,
        customPackage: String? = null
    ): Aapt2Result {
        val intermediateDir = outputJavaDir.parentFile.resolve("aapt2-intermediates")
        intermediateDir.deleteRecursively()
        intermediateDir.mkdirs()

        val intermediateZip = intermediateDir.resolve("compiled-resources.zip")
        val intermediateApk = intermediateDir.resolve("output.apk")

        // Step 1: aapt2 compile --dir <resDir> -o <intermediateZip>
        println("[Aapt2Tool] Compiling Android resources with AAPT2: ${aapt2Binary.name} compile...")
        val compileCommand = listOf(
            aapt2Binary.absolutePath,
            "compile",
            "--dir", resDir.absolutePath,
            "-o", intermediateZip.absolutePath
        )

        val compileResult = runProcess(compileCommand, intermediateDir, timeoutSeconds = 60)
        if (compileResult.exitCode != 0) {
            val errorMsg = "AAPT2 resource compile failed (exit code ${compileResult.exitCode}):\n${compileResult.stderr}"
            System.err.println("[Aapt2Tool] WARNING: $errorMsg")
            return Aapt2Result(success = false, errorMessage = errorMsg)
        }

        // Step 2: aapt2 link -I <androidJar> --manifest <manifestFile> -o <intermediateApk> --java <outputJavaDir> --auto-add-overlay --static-lib --merge-only <intermediateZip>
        println("[Aapt2Tool] Linking Android resources and generating R.java...")
        val linkCommand = mutableListOf(
            aapt2Binary.absolutePath,
            "link",
            "-I", androidJar.absolutePath,
            "--manifest", manifestFile.absolutePath,
            "-o", intermediateApk.absolutePath,
            "--java", outputJavaDir.absolutePath,
            "--auto-add-overlay",
            "--static-lib",
            "--merge-only"
        )

        if (!customPackage.isNullOrBlank()) {
            linkCommand.addAll(listOf("--custom-package", customPackage))
        }

        linkCommand.add(intermediateZip.absolutePath)

        val linkResult = runProcess(linkCommand, intermediateDir, timeoutSeconds = 60)
        if (linkResult.exitCode != 0) {
            val errorMsg = "AAPT2 resource link failed (exit code ${linkResult.exitCode}):\n${linkResult.stderr}"
            System.err.println("[Aapt2Tool] WARNING: $errorMsg")
            return Aapt2Result(success = false, errorMessage = errorMsg)
        }

        val generatedRFiles = outputJavaDir.walkTopDown()
            .filter { it.isFile && it.name == "R.java" }
            .toList()

        if (generatedRFiles.isEmpty()) {
            val errorMsg = "AAPT2 completed successfully but no R.java was generated under ${outputJavaDir.absolutePath}"
            System.err.println("[Aapt2Tool] WARNING: $errorMsg")
            return Aapt2Result(success = false, errorMessage = errorMsg)
        }

        println("[Aapt2Tool] Generated ${generatedRFiles.size} R.java files: ${generatedRFiles.map { it.name }.joinToString()}")
        return Aapt2Result(
            success = true,
            rJavaFiles = generatedRFiles,
            packageName = customPackage,
            outputDir = outputJavaDir
        )
    }

    /**
     * Locates the res/ directory by checking adjacent and standard Gradle paths.
     */
    fun findResDirectory(targetDir: File): File? {
        val candidates = mutableListOf<File>()

        // 1. Direct or adjacent
        candidates.add(targetDir.resolve("res"))
        candidates.add(targetDir.resolve("../res"))
        candidates.add(targetDir.resolve("../src/main/res"))

        // 2. Project root search
        val root = GradleDependencyParser.findProjectRoot(targetDir)
        candidates.add(root.resolve("app/src/main/res"))
        candidates.add(root.resolve("src/main/res"))
        candidates.add(root.resolve("res"))

        for (candidate in candidates) {
            val normalized = candidate.normalize()
            if (isValidResDir(normalized)) {
                return normalized
            }
        }

        // Walk project tree looking for any valid res folder, excluding build and cache directories
        if (root.exists()) {
            val found = root.walkTopDown()
                .onEnter { dir -> !dir.name.equals("build", ignoreCase = true) && !dir.name.startsWith(".") }
                .maxDepth(6)
                .filter { it.isDirectory && it.name == "res" && isValidResDir(it) }
                .firstOrNull()
            if (found != null) return found
        }

        return null
    }

    private fun isValidResDir(dir: File): Boolean {
        if (!dir.exists() || !dir.isDirectory) return false
        if (dir.absolutePath.contains("${File.separator}build${File.separator}") ||
            dir.absolutePath.contains("${File.separator}.gradle${File.separator}") ||
            dir.absolutePath.contains("${File.separator}.apollo-cache${File.separator}")
        ) {
            return false
        }
        val children = dir.listFiles() ?: return false
        // Must contain at least one standard resource folder
        val standardSubdirs = setOf("layout", "values", "drawable", "mipmap", "menu", "xml", "anim", "color", "raw")
        return children.any { it.isDirectory && standardSubdirs.any { sub -> it.name.startsWith(sub) } }
    }

    /**
     * Locates AndroidManifest.xml.
     */
    fun findManifestFile(targetDir: File): File? {
        val candidates = mutableListOf<File>()
        candidates.add(targetDir.resolve("AndroidManifest.xml"))
        candidates.add(targetDir.resolve("../AndroidManifest.xml"))
        candidates.add(targetDir.resolve("../src/main/AndroidManifest.xml"))

        val root = GradleDependencyParser.findProjectRoot(targetDir)
        candidates.add(root.resolve("app/src/main/AndroidManifest.xml"))
        candidates.add(root.resolve("src/main/AndroidManifest.xml"))
        candidates.add(root.resolve("AndroidManifest.xml"))

        for (candidate in candidates) {
            val normalized = candidate.normalize()
            if (normalized.exists() && normalized.isFile && !normalized.absolutePath.contains("${File.separator}build${File.separator}")) {
                return normalized
            }
        }

        if (root.exists()) {
            val found = root.walkTopDown()
                .onEnter { dir -> !dir.name.equals("build", ignoreCase = true) && !dir.name.startsWith(".") }
                .maxDepth(6)
                .filter { it.isFile && it.name == "AndroidManifest.xml" }
                .firstOrNull()
            if (found != null) return found
        }

        return null
    }

    /**
     * Extracts package name from AndroidManifest.xml or build.gradle.
     */
    fun extractPackageName(manifestFile: File?, targetDir: File): String? {
        if (manifestFile != null && manifestFile.exists()) {
            val manifestText = manifestFile.readText()
            val pkgMatch = Regex("""package\s*=\s*['"]([^'"]+)['"]""").find(manifestText)
            if (pkgMatch != null) {
                return pkgMatch.groupValues[1].trim()
            }
        }

        val root = GradleDependencyParser.findProjectRoot(targetDir)
        val buildFiles = GradleDependencyParser.findBuildFiles(root)
        for (buildFile in buildFiles) {
            val content = buildFile.readText()
            val nsMatch = Regex("""(?:namespace|applicationId)\s*(?:=|\s)\s*['"]([^'"]+)['"]""").find(content)
            if (nsMatch != null) {
                return nsMatch.groupValues[1].trim()
            }
        }

        return null
    }

    private data class ProcessResult(val exitCode: Int, val stdout: String, val stderr: String)

    private fun runProcess(command: List<String>, workingDir: File, timeoutSeconds: Long = 60): ProcessResult {
        val pb = ProcessBuilder(command).directory(workingDir)
        val process = pb.start()

        val stdoutFuture = CompletableFuture.supplyAsync {
            process.inputStream.bufferedReader().use { it.readText() }
        }
        val stderrFuture = CompletableFuture.supplyAsync {
            process.errorStream.bufferedReader().use { it.readText() }
        }

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            return ProcessResult(-1, stdoutFuture.getNow(""), "Process timed out after ${timeoutSeconds}s")
        }

        val stdout = stdoutFuture.get(5, TimeUnit.SECONDS)
        val stderr = stderrFuture.get(5, TimeUnit.SECONDS)
        return ProcessResult(process.exitValue(), stdout, stderr)
    }
}
