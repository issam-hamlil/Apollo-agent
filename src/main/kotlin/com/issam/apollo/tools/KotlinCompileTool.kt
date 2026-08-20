package com.issam.apollo.tools

import com.issam.apollo.state.VerificationResult
import org.jetbrains.kotlin.cli.common.ExitCode
import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream

/**
 * Tool for compiling generated Kotlin source code using Kotlin's embedded compiler.
 */
class KotlinCompileTool {

    fun compileKotlinDirectory(
        sourceDir: File = File("").absoluteFile.resolve("migrated-src"),
        outputDir: File = File("").absoluteFile.resolve("build/sandbox-compiled-kotlin")
    ): VerificationResult {
        outputDir.deleteRecursively()
        outputDir.mkdirs()

        val kotlinFiles = sourceDir.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .toList()

        if (kotlinFiles.isEmpty()) {
            return VerificationResult(
                compiledSuccessfully = false,
                compilerErrors = listOf("No Kotlin source files found under ${sourceDir.path}")
            )
        }

        val outStream = ByteArrayOutputStream()
        val printStream = PrintStream(outStream)

        val currentClasspath = resolveRuntimeClasspath()

        // Include all jar stubs in libs/android-stubs/ (android.jar, androidx-stubs.jar, etc.)
        val androidStubDir = File("").absoluteFile.resolve("libs/android-stubs")
        val stubJars = if (androidStubDir.exists()) {
            androidStubDir.walkTopDown()
                .filter { it.isFile && it.extension == "jar" }
                .map { it.absolutePath }
                .toList()
        } else emptyList()

        val effectiveClasspath = if (stubJars.isNotEmpty()) {
            (listOf(currentClasspath) + stubJars).joinToString(File.pathSeparator)
        } else {
            System.err.println(
                "[KotlinCompileTool] WARNING: Android stub directory not found at ${androidStubDir.absolutePath}. " +
                "Android-framework classes will fail to compile."
            )
            currentClasspath
        }

        // 1. Resolve authentic AAPT2 R.java files if available, otherwise generate fallback stubs
        val generatedRDir = File("").absoluteFile.resolve("build/generated-r/r-src")
        val aapt2RFiles = if (generatedRDir.exists()) {
            generatedRDir.walkTopDown()
                .filter { it.isFile && it.extension == "java" && it.name == "R.java" }
                .toList()
        } else emptyList()

        val rStubFiles = if (aapt2RFiles.isNotEmpty()) {
            println("[KotlinCompileTool] Including ${aapt2RFiles.size} authentic AAPT2 R.java file(s) in compilation.")
            aapt2RFiles
        } else {
            generateRStubsIfNecessary(sourceDir)
        }

        val allSourcesToCompile = kotlinFiles + rStubFiles

        val compilerArgs = mutableListOf(
            "-d", outputDir.absolutePath,
            "-cp", effectiveClasspath,
            "-nowarn"
        )
        allSourcesToCompile.forEach { compilerArgs.add(it.absolutePath) }

        return try {
            val compiler = K2JVMCompiler()
            val exitCode = compiler.exec(printStream, *compilerArgs.toTypedArray())

            val compilerLogs = outStream.toString().lines().filter { it.isNotBlank() }

            if (exitCode == ExitCode.OK) {
                VerificationResult(
                    compiledSuccessfully = true,
                    compilerErrors = emptyList()
                )
            } else {
                VerificationResult(
                    compiledSuccessfully = false,
                    compilerErrors = compilerLogs.ifEmpty { listOf("Kotlin compilation failed with exit code $exitCode") }
                )
            }
        } catch (e: Exception) {
            println("[KotlinCompileTool] Embedded compilation exception: ${e.message}")
            VerificationResult(
                compiledSuccessfully = false,
                compilerErrors = listOf(e.message ?: "Unknown compilation exception")
            )
        }
    }

    fun compileKotlinFiles(
        sourceFiles: Map<String, String>,
        tempDir: File = File("").absoluteFile.resolve("build/sandbox-compile-temp")
    ): VerificationResult {
        tempDir.deleteRecursively()
        tempDir.mkdirs()
        try {
            sourceFiles.forEach { (fileName, content) ->
                val file = File(tempDir, fileName)
                file.parentFile?.mkdirs()
                file.writeText(content)
            }
            return compileKotlinDirectory(sourceDir = tempDir)
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /**
     * Inspects [sourceDir] for references to Android R resources (e.g. `R.layout.activity_main`).
     * If found, generates a stub `R.java` class in [sourceDir] for each package referenced.
     */
    private fun generateRStubsIfNecessary(sourceDir: File): List<File> {
        val files = sourceDir.walkTopDown()
            .filter { it.isFile && (it.extension == "kt" || it.extension == "java") }
            .toList()
        if (files.isEmpty()) return emptyList()

        val rPackages = mutableSetOf<String>()
        val groupFieldsMap = mutableMapOf<String, MutableSet<String>>()

        val rUsageRegex = Regex("""\bR\.(layout|id|menu|string|drawable|color|attr|style|dimen)\.([a-zA-Z0-9_]+)\b""")
        val importRRegex = Regex("""import\s+([a-zA-Z0-9_.]+)\.R\b""")
        val packageRegex = Regex("""package\s+([a-zA-Z0-9_.]+)""")

        var hasRReferences = false

        files.forEach { file ->
            val text = file.readText()
            if (rUsageRegex.containsMatchIn(text) || importRRegex.containsMatchIn(text)) {
                hasRReferences = true
                packageRegex.findAll(text).forEach { m ->
                    val pkg = m.groupValues[1]
                    if (!pkg.startsWith("android") && !pkg.startsWith("androidx")) {
                        rPackages.add(pkg)
                    }
                }
                importRRegex.findAll(text).forEach { m ->
                    rPackages.add(m.groupValues[1])
                }
                rUsageRegex.findAll(text).forEach { m ->
                    val group = m.groupValues[1]
                    val field = m.groupValues[2]
                    groupFieldsMap.getOrPut(group) { mutableSetOf() }.add(field)
                }
            }
        }

        if (!hasRReferences || rPackages.isEmpty()) return emptyList()

        val generatedRFiles = mutableListOf<File>()
        rPackages.forEach { pkg ->
            val rJavaFile = File(sourceDir, "${pkg.replace('.', '/')}/R.java")
            if (!rJavaFile.exists()) {
                rJavaFile.parentFile?.mkdirs()
                var idCounter = 0x7f010001
                val javaContent = buildString {
                    appendLine("package $pkg;")
                    appendLine()
                    appendLine("public final class R {")
                    val groups = (groupFieldsMap.keys + setOf("layout", "id", "menu", "string", "drawable", "color", "attr", "style", "dimen")).distinct()
                    groups.forEach { group ->
                        appendLine("    public static final class $group {")
                        val fields = groupFieldsMap[group] ?: emptySet()
                        fields.forEach { fieldName ->
                            appendLine("        public static int $fieldName = ${idCounter++};")
                        }
                        appendLine("    }")
                    }
                    appendLine("}")
                }
                rJavaFile.writeText(javaContent)
                generatedRFiles.add(rJavaFile)
            }
        }
        return generatedRFiles
    }

    /**
     * Builds the compile classpath from the live JVM rather than trusting
     * `java.class.path` alone.
     *
     * Some launchers (Compose Desktop on Windows among them) start the app through a
     * "pathing jar" - a tiny jar whose manifest Class-Path holds the real entries - to stay
     * under the command-line length limit. In that case `java.class.path` is a single stub
     * jar, the Kotlin stdlib is invisible to the embedded compiler, and every migrated file
     * fails with "cannot access built-in declaration 'kotlin.Unit'".
     *
     * So: expand pathing-jar manifests, and additionally pin the stdlib by asking known
     * classes where they were actually loaded from.
     */
    internal fun resolveRuntimeClasspath(): String {
        val entries = LinkedHashSet<String>()

        fun add(path: File) {
            if (!entries.add(path.absolutePath)) return
            if (!path.isFile || path.extension != "jar") return
            runCatching {
                java.util.jar.JarFile(path).use { jar ->
                    val classPath = jar.manifest?.mainAttributes?.getValue("Class-Path") ?: return@use
                    classPath.split(" ").filter { it.isNotBlank() }.forEach { entry ->
                        val resolved = runCatching { File(java.net.URI(entry)) }.getOrNull()
                            ?: File(path.parentFile, entry)
                        if (resolved.exists()) entries.add(resolved.absolutePath)
                    }
                }
            }
        }

        System.getProperty("java.class.path").orEmpty()
            .split(File.pathSeparator)
            .filter { it.isNotBlank() }
            .forEach { add(File(it)) }

        // Guarantee the Kotlin stdlib is on the classpath even when the launcher hides it.
        listOf(
            "kotlin.Unit",
            "kotlin.jvm.internal.Intrinsics",
            "kotlin.collections.CollectionsKt"
        ).forEach { name ->
            runCatching {
                val location = Class.forName(name).protectionDomain?.codeSource?.location
                if (location != null) entries.add(File(location.toURI()).absolutePath)
            }
        }

        return entries.joinToString(File.pathSeparator)
    }
}
