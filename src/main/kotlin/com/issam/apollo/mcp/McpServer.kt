package com.issam.apollo.mcp

import com.issam.apollo.knowledge.MigrationPatterns
import com.issam.apollo.orchestrator.ModernizationGraph
import com.issam.apollo.state.GraphState
import com.issam.apollo.tools.JavaAstTool
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.engine.*
import io.ktor.server.netty.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread
import kotlinx.serialization.json.*

data class JobStateHolder(
    val jobId: String,
    val projectPath: String,
    var status: String,
    var currentStage: String,
    var state: GraphState? = null,
    var errorMessage: String? = null
)

class McpServer(private val port: Int = 8080) {
    private val orchestrator = ModernizationGraph()
    private val astTool = JavaAstTool()
    private val migrationPatterns = MigrationPatterns()

    private val jobs = ConcurrentHashMap<String, JobStateHolder>()

    fun start(wait: Boolean = true) {
        println("[Apollo-MCP] Starting Apollo Streamable HTTP MCP Server on port $port...")

        embeddedServer(Netty, port = port) {
            install(ContentNegotiation) {
                json(Json { prettyPrint = true; ignoreUnknownKeys = true })
            }

            routing {
                // Auth middleware route wrapper
                intercept(ApplicationCallPipeline.Call) {
                    val authHeader = call.request.headers["Authorization"]
                    val apiKeyHeader = call.request.headers["X-Api-Key"]
                    val expectedToken = System.getenv("APOLLO_API_KEY") ?: System.getenv("OLLAMA_API_KEY") ?: ""

                    if (expectedToken.isNotBlank()) {
                        val tokenMatch = (authHeader != null && authHeader == "Bearer $expectedToken") ||
                                         (apiKeyHeader != null && apiKeyHeader == expectedToken)
                        if (!tokenMatch) {
                            call.respond(HttpStatusCode.Unauthorized, mapOf("error" to "Unauthorized: Invalid API Key"))
                            return@intercept finish()
                        }
                    }
                }

                // Health check
                get("/health") {
                    call.respond(mapOf("status" to "UP", "service" to "Apollo-MCP", "port" to port.toString()))
                }

                // MCP Tools Directory
                get("/mcp/tools") {
                    val toolsList = listOf(
                        mapOf(
                            "name" to "analyze_repo",
                            "description" to "Performs AST parsing and produces dependency graph & module specs for a Java repo."
                        ),
                        mapOf(
                            "name" to "run_migration",
                            "description" to "Triggers full 5-stage Java-to-Kotlin migration pipeline asynchronously and returns a jobId immediately."
                        ),
                        mapOf(
                            "name" to "get_job_status",
                            "description" to "Polls live migration progress and detailed stage metrics for a given jobId."
                        ),
                        mapOf(
                            "name" to "get_module_report",
                            "description" to "Returns full before/after code, spec, test results, and retry history for a single module."
                        ),
                        mapOf(
                            "name" to "list_migration_patterns",
                            "description" to "Returns the curated Java-to-Kotlin Knowledge Base migration patterns."
                        )
                    )
                    call.respond(mapOf("tools" to toolsList))
                }

                // Generic HTTP MCP endpoint handler
                post("/mcp/execute") {
                    val jsonReq = call.receive<JsonObject>()
                    val toolName = jsonReq["name"]?.jsonPrimitive?.content ?: ""
                    val args = jsonReq["arguments"]?.jsonObject ?: buildJsonObject {}

                    when (toolName) {
                        "analyze_repo" -> {
                            val projectPath = args["projectPath"]?.jsonPrimitive?.content ?: "sample-legacy"
                            val repoAnalysis = astTool.analyzeRepo(File(projectPath))
                            call.respond(mapOf(
                                "status" to "SUCCESS",
                                "projectPath" to projectPath,
                                "totalFiles" to repoAnalysis.specs.size.toString(),
                                "topologicalOrder" to repoAnalysis.topologicalOrder.joinToString(" -> ")
                            ))
                        }
                        "run_migration" -> {
                            val projectPath = args["projectPath"]?.jsonPrimitive?.content ?: "sample-legacy"
                            val jobId = UUID.randomUUID().toString().take(8)
                            val job = JobStateHolder(
                                jobId = jobId,
                                projectPath = projectPath,
                                status = "RUNNING",
                                currentStage = "STAGE_1_ANALYZER"
                            )
                            jobs[jobId] = job

                            thread {
                                try {
                                    val finalState = orchestrator.runPipeline(projectPath)
                                    job.state = finalState
                                    job.status = if (finalState.verificationResult.compiledSuccessfully) "COMPLETED" else "FAILED"
                                    job.currentStage = finalState.currentStage
                                } catch (e: Exception) {
                                    job.status = "FAILED"
                                    job.errorMessage = e.message
                                }
                            }

                            call.respond(mapOf(
                                "status" to "ACCEPTED",
                                "jobId" to jobId,
                                "message" to "Migration pipeline started asynchronously",
                                "pollEndpoint" to "/mcp/execute with tool 'get_job_status'"
                            ))
                        }
                        "get_job_status" -> {
                            val jobId = args["jobId"]?.jsonPrimitive?.content ?: ""
                            val job = jobs[jobId]
                            if (job == null) {
                                call.respond(HttpStatusCode.NotFound, mapOf("error" to "Job ID '$jobId' not found"))
                            } else {
                                val state = job.state
                                val responseMap = mutableMapOf<String, String>(
                                    "jobId" to job.jobId,
                                    "status" to job.status,
                                    "currentStage" to job.currentStage,
                                    "errorMessage" to (job.errorMessage ?: "")
                                )
                                if (state != null) {
                                    responseMap["testsPassed"] = state.verificationResult.testsPassed.toString()
                                    responseMap["testsFailed"] = state.verificationResult.testsFailed.toString()
                                    responseMap["totalReports"] = state.reports.size.toString()
                                }
                                call.respond(responseMap)
                            }
                        }
                        "get_module_report" -> {
                            val moduleName = args["moduleName"]?.jsonPrimitive?.content ?: ""
                            val specFile = File("reports/specs/$moduleName/$moduleName-spec.md")
                            val ktFile = File("migrated-src/com/example/legacy/$moduleName.kt")
                            val verFile = File("reports/verification/$moduleName-verification.json")

                            call.respond(mapOf(
                                "moduleName" to moduleName,
                                "specExists" to specFile.exists().toString(),
                                "specContent" to if (specFile.exists()) specFile.readText() else "N/A",
                                "migratedKotlinExists" to ktFile.exists().toString(),
                                "migratedKotlinCode" to if (ktFile.exists()) ktFile.readText() else "N/A",
                                "verificationReportExists" to verFile.exists().toString(),
                                "verificationDetails" to if (verFile.exists()) verFile.readText() else "N/A"
                            ))
                        }
                        "list_migration_patterns" -> {
                            val allPatterns = migrationPatterns.getAllPatterns()
                            call.respond(mapOf(
                                "patternsCount" to allPatterns.size.toString(),
                                "patternsSummary" to allPatterns.joinToString("; ") { "${it.id}: ${it.name}" }
                            ))
                        }
                        else -> {
                            call.respond(HttpStatusCode.BadRequest, mapOf("error" to "Unknown tool '$toolName'"))
                        }
                    }
                }
            }
        }.start(wait = wait)
    }
}

fun main() {
    McpServer().start(wait = true)
}
