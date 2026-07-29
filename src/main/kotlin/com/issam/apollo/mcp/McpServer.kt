package com.issam.apollo.mcp

import com.issam.apollo.orchestrator.ModernizationGraph
import io.modelcontextprotocol.kotlin.sdk.CallToolResult
import io.modelcontextprotocol.kotlin.sdk.Implementation
import io.modelcontextprotocol.kotlin.sdk.ServerCapabilities
import io.modelcontextprotocol.kotlin.sdk.TextContent
import io.modelcontextprotocol.kotlin.sdk.Tool
import io.modelcontextprotocol.kotlin.sdk.server.Server
import io.modelcontextprotocol.kotlin.sdk.server.ServerOptions
import io.modelcontextprotocol.kotlin.sdk.server.StdioServerTransport
import kotlinx.coroutines.runBlocking
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpServer {
    private val orchestrator = ModernizationGraph()

    fun start() {
        // Log to stderr so stdout remains clean for stdio JSON-RPC framing
        System.err.println("[Apollo-MCP] Starting Apollo MCP Server over Stdio transport...")

        val server = Server(
            serverInfo = Implementation(name = "apollo-agent", version = "1.0.0"),
            options = ServerOptions(
                capabilities = ServerCapabilities(
                    tools = ServerCapabilities.Tools(listChanged = true)
                )
            )
        )

        // Register tools using standard MCP Tool definition & handler
        server.addTool(
            name = "migrate_java_project",
            description = "Transforms a legacy Java codebase to modern Kotlin using Apollo agent stages",
            inputSchema = Tool.Input(
                properties = buildJsonObject {
                    put("projectPath", buildJsonObject {
                        put("type", "string")
                        put("description", "Path to the legacy Java project directory (defaults to 'sample-legacy')")
                    })
                }
            )
        ) { request ->
            val args = request.arguments
            val rawPath = args["projectPath"]
            val projectPath = when (rawPath) {
                is JsonPrimitive -> rawPath.content
                else -> rawPath?.toString()?.replace("\"", "") ?: "sample-legacy"
            }

            System.err.println("[Apollo-MCP] Tool 'migrate_java_project' triggered for path: $projectPath")

            try {
                val finalState = orchestrator.runPipeline(projectPath)
                val isSuccess = finalState.verificationResult.compiledSuccessfully
                val summaryText = buildString {
                    appendLine("Pipeline Execution Result: ${if (isSuccess) "SUCCESS" else "FAILED"}")
                    appendLine("Target Project: $projectPath")
                    appendLine("Migrated Files: ${finalState.migratedCode.size}")
                    appendLine("Total Reports Generated: ${finalState.reports.size}")
                    appendLine("Tests Passed: ${finalState.verificationResult.testsPassed}/${finalState.verificationResult.testsPassed + finalState.verificationResult.testsFailed}")
                    if (finalState.verificationResult.testFailures.isNotEmpty()) {
                        appendLine("Failures:")
                        finalState.verificationResult.testFailures.forEach { appendLine(" - $it") }
                    }
                }

                CallToolResult(
                    content = listOf(TextContent(text = summaryText)),
                    isError = !isSuccess
                )
            } catch (e: Exception) {
                System.err.println("[Apollo-MCP] Pipeline execution error: ${e.message}")
                CallToolResult(
                    content = listOf(TextContent(text = "Error executing migration pipeline: ${e.message}")),
                    isError = true
                )
            }
        }

        val transport = StdioServerTransport(
            inputStream = System.`in`.asSource().buffered(),
            outputStream = System.out.asSink().buffered()
        )

        runBlocking {
            server.connect(transport)
            System.err.println("[Apollo-MCP] Server connected via Stdio transport.")
        }
    }
}

fun main() {
    McpServer().start()
}
