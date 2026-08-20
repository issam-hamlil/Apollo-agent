package com.issam.apollo.tools

import java.io.File
import java.util.concurrent.TimeUnit

data class SandboxExecutionResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val isDockerExecuted: Boolean
)

/**
 * Sandboxed process runner supporting optional Docker container isolation or restricted subprocess execution.
 */
class SandboxRunner(private val enableDocker: Boolean = false) {

    fun executeInSandbox(workingDir: File, command: List<String>, timeoutSeconds: Long = 30): SandboxExecutionResult {
        if (enableDocker && isDockerAvailable()) {
            return runInDockerContainer(workingDir, command, timeoutSeconds)
        }
        return runInLocalSubprocess(workingDir, command, timeoutSeconds)
    }

    fun isDockerAvailable(): Boolean {
        return try {
            val process = ProcessBuilder("docker", "info")
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start()
            val finished = process.waitFor(3, TimeUnit.SECONDS)
            finished && process.exitValue() == 0
        } catch (e: Exception) {
            false
        }
    }

    private fun runInDockerContainer(workingDir: File, command: List<String>, timeoutSeconds: Long): SandboxExecutionResult {
        val dockerCmd = listOf(
            "docker", "run", "--rm",
            "-v", "${workingDir.absolutePath}:/app",
            "-w", "/app",
            "openjdk:17-alpine"
        ) + command

        return runInLocalSubprocess(workingDir, dockerCmd, timeoutSeconds, isDocker = true)
    }

    private fun runInLocalSubprocess(
        workingDir: File,
        command: List<String>,
        timeoutSeconds: Long,
        isDocker: Boolean = false
    ): SandboxExecutionResult {
        val processBuilder = ProcessBuilder(command).directory(workingDir)

        return try {
            val process = processBuilder.start()
            val stdoutFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.inputStream.bufferedReader().use { it.readText() }
            }
            val stderrFuture = java.util.concurrent.CompletableFuture.supplyAsync {
                process.errorStream.bufferedReader().use { it.readText() }
            }

            val completed = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)

            if (!completed) {
                process.destroyForcibly()
                return SandboxExecutionResult(-1, "", "Execution timed out after $timeoutSeconds seconds", isDocker)
            }

            val stdout = stdoutFuture.get(5, TimeUnit.SECONDS) ?: ""
            val stderr = stderrFuture.get(5, TimeUnit.SECONDS) ?: ""
            SandboxExecutionResult(process.exitValue(), stdout, stderr, isDocker)
        } catch (e: Exception) {
            SandboxExecutionResult(-1, "", e.message ?: "Execution error", isDocker)
        }
    }
}
