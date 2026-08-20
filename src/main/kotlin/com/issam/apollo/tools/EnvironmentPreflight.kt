package com.issam.apollo.tools

import java.io.File

/**
 * Severity of a preflight finding. BLOCKER means no amount of LLM work can succeed.
 */
enum class PreflightSeverity { BLOCKER, WARNING }

data class PreflightIssue(
    val severity: PreflightSeverity,
    val title: String,
    val detail: String,
    val remedy: String
)

data class PreflightReport(val issues: List<PreflightIssue>) {
    val blockers: List<PreflightIssue> get() = issues.filter { it.severity == PreflightSeverity.BLOCKER }
    val ok: Boolean get() = blockers.isEmpty()

    fun render(): String = buildString {
        appendLine("==========================================================================")
        appendLine("[Preflight] Build environment check")
        if (issues.isEmpty()) {
            appendLine("  All checks passed - the generated Kotlin has everything it needs to compile.")
        } else {
            issues.forEach { issue ->
                val tag = if (issue.severity == PreflightSeverity.BLOCKER) "[BLOCKER]" else "[WARN]   "
                appendLine("  $tag ${issue.title}")
                appendLine("            ${issue.detail}")
                appendLine("            Fix: ${issue.remedy}")
            }
        }
        appendLine("==========================================================================")
    }
}

/**
 * Verifies the compile environment BEFORE any LLM is invoked.
 *
 * Errors like "cannot access built-in declaration 'kotlin.Unit'" or
 * "unresolved reference 'android'" are classpath faults, not code faults. No prompt can
 * fix them, but the repair loop will happily spend its whole retry budget - and the user's
 * tokens - trying. Catching them up front, and keeping them out of LLM prompts entirely,
 * is the difference between a clear one-line diagnosis and an hour of futile repair passes.
 */
object EnvironmentPreflight {

    /**
     * True when a compiler message describes a broken build environment rather than
     * defective generated code.
     */
    fun isEnvironmentError(message: String): Boolean {
        val m = message.lowercase()
        if (m.trimStart().startsWith("warning:")) return false
        return ENVIRONMENT_SIGNATURES.any { it in m }
    }

    private val ENVIRONMENT_SIGNATURES = listOf(
        // Kotlin standard library missing from the compile classpath.
        "cannot access built-in declaration",
        "dependency on the kotlin standard library",
        "unresolved reference 'kotlin'",
        // Android framework / support libraries missing.
        "unresolved reference 'android'",
        "unresolved reference 'androidx'",
        // Classpath entries the compiler could not read at all.
        "cannot access class",
        "class file for",
        "no kotlin source files found"
    )

    /** Splits diagnostics into (environment faults, genuine code faults). */
    fun partition(errors: List<String>): Pair<List<String>, List<String>> {
        val env = mutableListOf<String>()
        val code = mutableListOf<String>()
        errors.forEach { if (isEnvironmentError(it)) env.add(it) else code.add(it) }
        return env to code
    }

    /**
     * Runs the checks. [targetProjectPath] is the project being migrated; it is scanned to
     * decide whether Android framework classes are required at all.
     */
    fun check(
        targetProjectPath: String,
        projectRoot: File = File("").absoluteFile,
        compileTool: KotlinCompileTool = KotlinCompileTool()
    ): PreflightReport {
        val issues = mutableListOf<PreflightIssue>()

        // 1. Kotlin standard library ------------------------------------------------
        // Authoritative test: actually compile something that uses it. This catches a
        // hidden classpath (e.g. a launcher that starts the app via a pathing jar) that
        // simple file checks would miss.
        val probe = runCatching {
            compileTool.compileKotlinFiles(
                mapOf(
                    "ApolloPreflightProbe.kt" to
                        "internal object ApolloPreflightProbe { " +
                        "fun unit(): Unit = Unit; " +
                        "val mapped = listOf(1).map { it + 1 }.apply { size } }"
                ),
                tempDir = File(projectRoot, "build/apollo-preflight-probe")
            )
        }.getOrNull()

        if (probe == null || !probe.compiledSuccessfully) {
            val detail = probe?.compilerErrors
                ?.firstOrNull { !it.trimStart().startsWith("warning:") }
                ?: "the embedded Kotlin compiler could not be invoked"
            issues.add(
                PreflightIssue(
                    PreflightSeverity.BLOCKER,
                    "Kotlin standard library is not on the compile classpath",
                    "A trivial file using Unit/listOf/apply failed to compile: $detail",
                    "The verifier compiles with the host JVM's classpath. Launch Apollo so that " +
                        "kotlin-stdlib is visible (./gradlew run, or ./gradlew :ui-desktop:run), " +
                        "and check KotlinCompileTool.resolveRuntimeClasspath()."
                )
            )
        }

        // 2. Android framework, only if the project actually needs it ----------------
        if (requiresAndroid(File(targetProjectPath))) {
            val stubDir = File(projectRoot, "libs/android-stubs")
            val jars = if (stubDir.exists()) {
                stubDir.walkTopDown().filter { it.isFile && it.extension == "jar" }.toList()
            } else emptyList()

            if (jars.none { it.name.equals("android.jar", ignoreCase = true) }) {
                issues.add(
                    PreflightIssue(
                        PreflightSeverity.BLOCKER,
                        "Android framework stubs are missing",
                        "The project imports android.* but no android.jar was found in ${stubDir.path}.",
                        "Place android.jar (API 26+) in libs/android-stubs/ so android.* resolves."
                    )
                )
            }

            if (jars.size <= 1) {
                issues.add(
                    PreflightIssue(
                        PreflightSeverity.WARNING,
                        "No resolved dependency jars",
                        "Only ${jars.size} jar(s) in ${stubDir.path}. AndroidX/support classes may not resolve.",
                        "Run once with a reachable network so GradleDependencyResolver can populate " +
                            "libs/android-stubs/androidx-cache/."
                    )
                )
            }
        }

        return PreflightReport(issues)
    }

    /** True when any Java source under [dir] imports the Android framework. */
    internal fun requiresAndroid(dir: File): Boolean {
        if (!dir.exists()) return false
        return dir.walkTopDown()
            .filter { it.isFile && it.extension == "java" }
            .take(400)
            .any { file ->
                runCatching { file.readText() }.getOrDefault("").contains("import android.")
            }
    }
}
