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
        sourceDir: File = File("migrated-src"),
        outputDir: File = File("build/sandbox-compiled-kotlin")
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

        val currentClasspath = System.getProperty("java.class.path")
        val compilerArgs = mutableListOf(
            "-d", outputDir.absolutePath,
            "-cp", currentClasspath,
            "-nowarn"
        )
        kotlinFiles.forEach { compilerArgs.add(it.absolutePath) }

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
        tempDir: File = File("build/sandbox-compile-temp")
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
}
