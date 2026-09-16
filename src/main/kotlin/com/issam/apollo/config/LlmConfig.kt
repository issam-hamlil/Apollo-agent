package com.issam.apollo.config

import io.github.cdimascio.dotenv.dotenv
import com.issam.apollo.telemetry.ApolloTelemetry
import com.issam.apollo.telemetry.LlmCallEvent
import com.issam.apollo.telemetry.LlmResultEvent
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

enum class LlmProvider {
    GROQ,
    OLLAMA
}

class FatalWatchdogAbortException(message: String) : RuntimeException(message)
class OllamaWatchdogTimeoutException(val moduleName: String, message: String) : RuntimeException(message)

data class ModelProfile(
    val provider: LlmProvider,
    val modelName: String,
    val apiKey: String,
    val maxTokens: Int = 4096,
    val temperature: Double = 0.2
)

object LlmConfig {
    private val dotenv = try {
        dotenv {
            ignoreIfMissing = true
        }
    } catch (e: Exception) {
        null
    }

    // Groq configuration
    /** Raw .env lookup, so non-LLM settings (e.g. MigrationPolicy) can share the same file. */
    fun rawSetting(key: String): String? = dotenv?.get(key)

    val groqApiKey: String = dotenv?.get("GROQ_API_KEY") ?: System.getenv("GROQ_API_KEY") ?: ""

    // OLLAMA_BASE_URL  — base URL of the Ollama HTTP endpoint
    //                    Local dev:  http://localhost:11434/v1
    //                    OCI prod:   http://<PUBLIC_IP>:8080/v1 (or behind nginx)
    // OLLAMA_API_KEY   - empty string for local (no auth); set to nginx token in prod
    // OLLAMA_MODEL_PRIMARY    - primary model (e.g. qwen3-coder:30b) used for fresh starts, first try resume, and initial fixer attempts 1-3
    // OLLAMA_MODEL_ESCALATION - escalation model (e.g. qwen3.8:27b) used for 4th and subsequent runs (attemptCount >= 3)
    val ollamaBaseUrl: String = dotenv?.get("OLLAMA_BASE_URL") ?: System.getenv("OLLAMA_BASE_URL") ?: "http://localhost:11434/v1"
    val ollamaApiKey: String  = dotenv?.get("OLLAMA_API_KEY") ?: System.getenv("OLLAMA_API_KEY") ?: ""
    val ollamaModelPrimary: String = dotenv?.get("OLLAMA_MODEL_PRIMARY")
        ?: dotenv?.get("OLLAMA_MODEL")
        ?: System.getenv("OLLAMA_MODEL_PRIMARY")
        ?: System.getenv("OLLAMA_MODEL")
        ?: "qwen3-coder:30b"
    val ollamaModelEscalation: String = dotenv?.get("OLLAMA_MODEL_ESCALATION")
        ?: System.getenv("OLLAMA_MODEL_ESCALATION")
        ?: "qwen3.8:27b"

    // Backward-compatible aliases
    val ollamaModel: String get() = ollamaModelPrimary
    val ollamaModelSmall: String get() = ollamaModelPrimary

    /**
     * Select the appropriate Ollama model based on routing:
     * - Fresh starts, first try resume, and Fixer attempts 1–3 (attemptCount = 0, 1, 2): OLLAMA_MODEL_PRIMARY (qwen3-coder:30b)
     * - 4th and subsequent runs (attemptCount >= 3): OLLAMA_MODEL_ESCALATION (qwen3.8:27b)
     */
    fun selectOllamaModel(moduleName: String = "", attemptCount: Int = 0): String {
        if (attemptCount >= 3 && ollamaModelEscalation.isNotBlank()) {
            val modName = if (moduleName.isNotBlank()) moduleName else "module"
            println("[Ollama] Module '$modName' reached attempt #${attemptCount + 1} (4th+ run) - switching to $ollamaModelEscalation for remaining attempts.")
            return ollamaModelEscalation
        }
        return ollamaModelPrimary
    }

    fun selectOllamaModel(totalChars: Int, moduleName: String = "", attemptCount: Int = 0): String {
        return selectOllamaModel(moduleName, attemptCount)
    }

    val defaultProvider: LlmProvider = when ((dotenv?.get("LLM_PROVIDER") ?: System.getenv("LLM_PROVIDER") ?: "groq").lowercase()) {
        "ollama" -> LlmProvider.OLLAMA
        else     -> LlmProvider.GROQ
    }

    val defaultModel: String = dotenv?.get("LLM_MODEL") ?: System.getenv("LLM_MODEL") ?: "llama-3.3-70b-versatile"

    val ollamaWatchdogSilenceMs: Long = (
        dotenv?.get("OLLAMA_WATCHDOG_TIMEOUT_SECONDS")?.toLongOrNull()
            ?: System.getenv("OLLAMA_WATCHDOG_TIMEOUT_SECONDS")?.toLongOrNull()
            ?: 900L
    ) * 1000L

    val moduleTimeoutMs: Long = dotenv?.get("MODULE_TIMEOUT_MS")?.toLongOrNull()
        ?: System.getenv("MODULE_TIMEOUT_MS")?.toLongOrNull()
        ?: maxOf(1_000_000L, ollamaWatchdogSilenceMs + 60_000L) // Default >= 1000s to allow 900s watchdog

    val maxRetryCount: Int = dotenv?.get("MAX_RETRY_COUNT")?.toIntOrNull()
        ?: System.getenv("MAX_RETRY_COUNT")?.toIntOrNull()
        ?: 10

    // ── Silence Watchdog Abort Tracker ──────────────────────────────────────────
    // Tracks consecutive/total watchdog silence aborts per module.
    // If ANY module is aborted 3 times by the watchdog, execution is halted entirely.
    private val moduleWatchdogAbortCounts = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /**
     * Records a watchdog silence abort for [moduleName].
     * If the count reaches 3, throws [FatalWatchdogAbortException] to halt Apollo entirely.
     */
    fun recordWatchdogAbort(moduleName: String): Int {
        val key = moduleName.ifBlank { "general" }
        val count = moduleWatchdogAbortCounts.merge(key, 1) { old, one -> old + one } ?: 1
        System.err.println("[WatchdogTracker] ⚠️ Module '$key' was aborted by silence watchdog (Count: $count/3).")
        if (count >= 3) {
            val fatalMsg = "🚨 FATAL EXECUTION HALT: Module '$key' was stopped by the silence watchdog 3 times. Halting Apollo execution entirely as requested."
            System.err.println("==========================================================================")
            System.err.println(fatalMsg)
            System.err.println("==========================================================================")
            throw FatalWatchdogAbortException(fatalMsg)
        }
        return count
    }

    fun getWatchdogAbortCount(moduleName: String): Int =
        moduleWatchdogAbortCounts[moduleName.ifBlank { "general" }] ?: 0

    fun resetWatchdogAbortCounts() {
        moduleWatchdogAbortCounts.clear()
    }

    // Circuit Breaker for LLM Rate Limits (Groq, Ollama, etc.)
    private val rateLimitedProviders = java.util.concurrent.ConcurrentHashMap<LlmProvider, String>()

    val isGroqRateLimited: Boolean
        get() = isProviderRateLimited(LlmProvider.GROQ)

    fun isProviderRateLimited(provider: LlmProvider): Boolean =
        rateLimitedProviders.containsKey(provider)

    fun markProviderRateLimited(provider: LlmProvider, reason: String) {
        val previous = rateLimitedProviders.putIfAbsent(provider, reason)
        if (previous == null) {
            System.err.println("==========================================================================")
            System.err.println("[CIRCUIT BREAKER] $provider NON-SUCCESS / RATE LIMIT HIT - TRIPPED CIRCUIT BREAKER")
            System.err.println("   Reason: $reason")
            System.err.println("   Skipping $provider for the rest of this entire run. All requests route straight to the next fallback.")
            System.err.println("==========================================================================")
        }
    }

    fun markGroqRateLimited(reason: String) {
        markProviderRateLimited(LlmProvider.GROQ, reason)
    }

    fun resetRateLimits() {
        rateLimitedProviders.clear()
    }

    fun resetGroqRateLimit() {
        rateLimitedProviders.remove(LlmProvider.GROQ)
    }

    fun isRateLimitOr429(e: Throwable): Boolean {
        val msg = (e.message ?: "") + " " + (e.cause?.message ?: "")
        val msgLower = msg.lowercase()
        return msgLower.contains("429") ||
               msgLower.contains("rate limit") ||
               msgLower.contains("rate_limit") ||
               msgLower.contains("rate_limit_exceeded") ||
               msgLower.contains("tokens per minute") ||
               msgLower.contains("tokens per day") ||
               msgLower.contains("requests per minute") ||
               msgLower.contains("requests per day") ||
               msgLower.contains("tpm") ||
               msgLower.contains("rpm") ||
               msgLower.contains("rpd") ||
               msgLower.contains("tpd") ||
               msgLower.contains("quota exceeded") ||
               msgLower.contains("quota_exceeded") ||
               msgLower.contains("resource_exhausted") ||
               msgLower.contains("too many requests")
    }

    /**
     * For each key that matters to the LLM config, returns the resolved value and the
     * tier it came from: "dotenv (.env file)", "system env var", or "hardcoded default".
     * Used by Main.kt to print an unambiguous startup banner.
     */
    fun configDiagnostics(): Map<String, Pair<String, String>> {
        fun source(key: String, default: String): Pair<String, String> {
            val fromDotenv = dotenv?.get(key)
            if (fromDotenv != null) return Pair(fromDotenv, "dotenv (.env file)")
            val fromEnv = System.getenv(key)
            if (fromEnv != null) return Pair(fromEnv, "system env var")
            return Pair(default, "hardcoded default")
        }

        return linkedMapOf(
            "LLM_PROVIDER"                   to source("LLM_PROVIDER",                   "groq"),
            "LLM_MODEL"                      to source("LLM_MODEL",                      "openai/gpt-oss-120b"),
            "GROQ_API_KEY"                   to source("GROQ_API_KEY",                   "(empty)"),
            "OLLAMA_MODEL_PRIMARY"           to source("OLLAMA_MODEL_PRIMARY",           "qwen3-coder:30b"),
            "OLLAMA_MODEL_ESCALATION"        to source("OLLAMA_MODEL_ESCALATION",        "qwen3.8:27b"),
            "OLLAMA_WATCHDOG_TIMEOUT_SECONDS" to source("OLLAMA_WATCHDOG_TIMEOUT_SECONDS", "900"),
            "OLLAMA_BASE_URL"                to source("OLLAMA_BASE_URL",                "http://localhost:11434/v1"),
            "OLLAMA_API_KEY"                 to source("OLLAMA_API_KEY",                 "(empty)"),
            "MODULE_TIMEOUT_MS"              to source("MODULE_TIMEOUT_MS",              "1000000"),
            "MAX_RETRY_COUNT"                to source("MAX_RETRY_COUNT",                "10")
        )
    }

    fun getFallbackSequence(): List<LlmProvider> {
        val baseSequence = when (defaultProvider) {
            LlmProvider.GROQ   -> listOf(LlmProvider.GROQ, LlmProvider.OLLAMA)
            LlmProvider.OLLAMA -> listOf(LlmProvider.OLLAMA, LlmProvider.GROQ)
        }
        return baseSequence.filterNot { isProviderRateLimited(it) }
    }

    fun getFastProfile(): ModelProfile = when (defaultProvider) {
        LlmProvider.OLLAMA -> ModelProfile(
            provider = LlmProvider.OLLAMA,
            modelName = ollamaModelPrimary,
            apiKey = ollamaApiKey,
            temperature = 0.1
        )
        LlmProvider.GROQ -> ModelProfile(
            provider = LlmProvider.GROQ,
            modelName = defaultModel,
            apiKey = groqApiKey,
            temperature = 0.1
        )
    }

    fun getHeavyProfile(): ModelProfile = when (defaultProvider) {
        LlmProvider.OLLAMA -> ModelProfile(
            provider = LlmProvider.OLLAMA,
            modelName = ollamaModelPrimary,
            apiKey = ollamaApiKey,
            temperature = 0.2
        )
        LlmProvider.GROQ -> ModelProfile(
            provider = LlmProvider.GROQ,
            modelName = defaultModel,
            apiKey = groqApiKey,
            temperature = 0.2
        )
    }

    fun getOllamaProfile(): ModelProfile = ModelProfile(
        provider = LlmProvider.OLLAMA,
        modelName = ollamaModelPrimary,
        apiKey = ollamaApiKey,
        temperature = 0.1
    )

    /**
     * Executes an HTTP POST request to Groq chat completion endpoint.
     */
    fun callGroqDirect(
        prompt: String,
        systemPrompt: String = "",
        temperature: Double = 0.2,
        moduleName: String = ""
    ): String {
        if (isGroqRateLimited) {
            throw IllegalStateException("Groq circuit breaker is active (rate limited)")
        }
        if (groqApiKey.isBlank()) {
            throw IllegalStateException("GROQ_API_KEY is not configured")
        }

        val moduleTag = if (moduleName.isNotBlank()) " for '$moduleName'" else ""
        val model = defaultModel.ifBlank { "llama-3.3-70b-versatile" }
        println("[Groq] Dispatching request$moduleTag to model '$model' at api.groq.com...")

        val telemetryCallId = ApolloTelemetry.nextCallId()
        val telemetryStart = System.currentTimeMillis()
        ApolloTelemetry.emit(
            LlmCallEvent(
                callId = telemetryCallId,
                module = moduleName,
                provider = "GROQ",
                model = model,
                tier = "cloud",
                attempt = 0,
                systemPrompt = systemPrompt,
                userPrompt = prompt
            )
        )

        val url = URL("https://api.groq.com/openai/v1/chat/completions")
        val conn = url.openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer $groqApiKey")
        conn.connectTimeout = 30000
        conn.readTimeout = 60000
        conn.doOutput = true

        val requestBody = buildJsonObject {
            put("model", model)
            put("temperature", temperature)
            putJsonArray("messages") {
                if (systemPrompt.isNotBlank()) {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                }
                add(buildJsonObject {
                    put("role", "user")
                    put("content", prompt)
                })
            }
        }.toString()

        conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

        val responseCode = conn.responseCode
        if (responseCode !in 200..299) {
            val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            ApolloTelemetry.emit(
                LlmResultEvent(
                    callId = telemetryCallId, module = moduleName, ok = false,
                    error = "HTTP $responseCode: $errText",
                    elapsedMs = System.currentTimeMillis() - telemetryStart
                )
            )
            markGroqRateLimited("HTTP $responseCode: $errText")
            throw IllegalStateException("Groq API returned HTTP $responseCode: $errText")
        }

        val responseText = conn.inputStream.bufferedReader().use { it.readText() }
        val json = Json.parseToJsonElement(responseText).jsonObject
        val choices = json["choices"]?.jsonArray
        val message = choices?.getOrNull(0)?.jsonObject?.get("message")?.jsonObject
        val content = message?.get("content")?.jsonPrimitive?.content

        ApolloTelemetry.emit(
            LlmResultEvent(
                callId = telemetryCallId, module = moduleName, ok = content != null,
                response = content ?: "",
                error = if (content == null) "empty choice content" else "",
                elapsedMs = System.currentTimeMillis() - telemetryStart
            )
        )
        return content ?: throw IllegalStateException("Groq API returned empty choice content")
    }

    /**
     * Executes a **streaming** HTTP POST to Ollama (SSE / chunked JSON lines).
     * Model is selected automatically based on 2-tier routing:
     *   - Migrator & first 3 Fixer attempts → [ollamaModelPrimary] (27b)
     *   - Fixer attempt #4+ (after 3 failures on primary) → [ollamaModelEscalation] (30b)
     *
     * Liveness policy (no hard timeout):
     *   - A background watchdog thread tracks the last time a non-empty token was received.
     *   - If NO new tokens arrive for silence timeout, the connection is forcefully aborted.
     *   - The heartbeat ticker prints a line every 15 s so the console never looks frozen.
     *   - As long as tokens keep flowing (even slowly), the call continues indefinitely.
     */
    fun callOllamaDirect(
        prompt: String,
        systemPrompt: String = "",
        temperature: Double = 0.2,
        moduleName: String = "",
        attemptCount: Int = 0
    ): String {
        val moduleTag = if (moduleName.isNotBlank()) " for '$moduleName'" else ""
        val totalChars = prompt.length + systemPrompt.length
        val estimatedTokens = totalChars / 4
        val selectedModel = selectOllamaModel(moduleName, attemptCount)
        val modelTag = when {
            selectedModel == ollamaModelEscalation -> "$selectedModel (4th+ run escalation/27b)"
            else -> "$selectedModel (primary/30b)"
        }
        println("[Ollama] Dispatching request$moduleTag (Prompt: $totalChars chars, ~$estimatedTokens tokens) to model '$modelTag' at $ollamaBaseUrl...")

        val telemetryCallId = ApolloTelemetry.nextCallId()
        val telemetryStart = System.currentTimeMillis()
        ApolloTelemetry.emit(
            LlmCallEvent(
                callId = telemetryCallId,
                module = moduleName,
                provider = "OLLAMA",
                model = selectedModel,
                tier = if (selectedModel == ollamaModelEscalation) "escalation" else "primary",
                attempt = attemptCount,
                systemPrompt = systemPrompt,
                userPrompt = prompt
            )
        )

        // Shared liveness state
        val lastTokenTime = AtomicLong(System.currentTimeMillis())
        val stopMonitors = AtomicBoolean(false)
        val wasWatchdogAborted = AtomicBoolean(false)
        var activeConn: HttpURLConnection? = null
        var activeStream: java.io.InputStream? = null
        var activeReader: BufferedReader? = null
        val SILENCE_ABORT_MS = ollamaWatchdogSilenceMs  // 700 seconds default

        // Heartbeat ticker: prints every 15s so the terminal never looks frozen
        val tickerThread = Thread {
            val startTime = System.currentTimeMillis()
            while (!stopMonitors.get()) {
                try { Thread.sleep(15000) } catch (e: InterruptedException) { break }
                if (stopMonitors.get()) break
                val elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000
                println("[Ollama] Still waiting for response$moduleTag... ${elapsedSeconds}s elapsed")
            }
        }.apply { isDaemon = true; name = "ollama-heartbeat-$moduleName"; start() }

        // Silence watchdog: aborts the connection if no new token arrives within SILENCE_ABORT_MS (700s)
        val watchdogThread = Thread {
            while (!stopMonitors.get()) {
                try { Thread.sleep(5000) } catch (e: InterruptedException) { break }
                if (stopMonitors.get()) break
                val silenceMs = System.currentTimeMillis() - lastTokenTime.get()
                if (silenceMs > SILENCE_ABORT_MS) {
                    val silenceSec = silenceMs / 1000
                    wasWatchdogAborted.set(true)
                    System.err.println("[Ollama] ⚠️ SILENCE WATCHDOG: No token received for ${silenceSec}s$moduleTag. Aborting connection.")
                    try { activeReader?.close() } catch (_: Exception) {}
                    try { activeStream?.close() } catch (_: Exception) {}
                    try { activeConn?.disconnect() } catch (_: Exception) {}
                    break
                }
            }
        }.apply { isDaemon = true; name = "ollama-watchdog-$moduleName"; start() }

        try {
            val endpoint = if (ollamaBaseUrl.endsWith("/")) "${ollamaBaseUrl}chat/completions" else "$ollamaBaseUrl/chat/completions"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).also { activeConn = it }
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")
            if (ollamaApiKey.isNotBlank()) {
                conn.setRequestProperty("X-Api-Key", ollamaApiKey)
            }
            conn.connectTimeout = 30000
            conn.readTimeout = 0  // no read timeout — watchdog handles silence
            conn.doOutput = true

            val requestBody = buildJsonObject {
                put("model", selectedModel)
                put("temperature", temperature)
                put("stream", true)  // ← streaming enabled
                putJsonArray("messages") {
                    if (systemPrompt.isNotBlank()) {
                        add(buildJsonObject {
                            put("role", "system")
                            put("content", systemPrompt)
                        })
                    }
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", prompt)
                    })
                }
            }.toString()

            conn.outputStream.use { it.write(requestBody.toByteArray(Charsets.UTF_8)) }

            val responseCode = conn.responseCode
            if (responseCode !in 200..299) {
                val errText = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (responseCode == 429 || isRateLimitOr429(IllegalStateException(errText))) {
                    markProviderRateLimited(LlmProvider.OLLAMA, "HTTP $responseCode: $errText")
                }
                throw IllegalStateException("Ollama API returned HTTP $responseCode: $errText")
            }

            // Read streaming SSE lines: each line is either "data: {...}" or "data: [DONE]"
            val contentBuilder = StringBuilder()
            val stream = conn.inputStream.also { activeStream = it }
            val reader = BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).also { activeReader = it }
            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val raw = line!!.trim()
                if (raw.isEmpty()) continue

                val jsonStr = when {
                    raw.startsWith("data:") -> raw.removePrefix("data:").trim()
                    else                    -> raw  // non-SSE: plain JSON line (Ollama native format)
                }
                if (jsonStr == "[DONE]") break

                try {
                    val obj = Json.parseToJsonElement(jsonStr).jsonObject
                    // OpenAI-compat streaming: choices[0].delta.content
                    val deltaContent = obj["choices"]?.jsonArray
                        ?.getOrNull(0)?.jsonObject
                        ?.get("delta")?.jsonObject
                        ?.get("content")?.jsonPrimitive?.content
                    if (!deltaContent.isNullOrEmpty()) {
                        contentBuilder.append(deltaContent)
                        lastTokenTime.set(System.currentTimeMillis())  // reset silence clock
                    }
                    // Also check finish_reason to break cleanly
                    val finishReason = obj["choices"]?.jsonArray
                        ?.getOrNull(0)?.jsonObject
                        ?.get("finish_reason")?.jsonPrimitive?.content
                    if (finishReason == "stop") break
                } catch (_: Exception) {
                    // Malformed chunk — skip and continue reading
                }
            }

            val result = contentBuilder.toString().trim()
            ApolloTelemetry.emit(
                LlmResultEvent(
                    callId = telemetryCallId, module = moduleName, ok = result.isNotEmpty(),
                    response = result,
                    error = if (result.isEmpty()) "streaming returned empty content" else "",
                    elapsedMs = System.currentTimeMillis() - telemetryStart
                )
            )
            if (result.isEmpty()) throw IllegalStateException("Ollama streaming returned empty content for$moduleTag")
            return result
        } catch (e: Exception) {
            ApolloTelemetry.emit(
                LlmResultEvent(
                    callId = telemetryCallId, module = moduleName, ok = false,
                    error = e.message ?: e.javaClass.simpleName,
                    elapsedMs = System.currentTimeMillis() - telemetryStart
                )
            )
            if (wasWatchdogAborted.get()) {
                // Check if this module reached 3 watchdog aborts (will throw FatalWatchdogAbortException if >= 3)
                recordWatchdogAbort(moduleName)
                throw OllamaWatchdogTimeoutException(moduleName, "Ollama connection aborted by silence watchdog after ${SILENCE_ABORT_MS / 1000}s silence for '$moduleName'")
            }
            throw e
        } finally {
            stopMonitors.set(true)
            tickerThread.interrupt()
            watchdogThread.interrupt()
        }
    }

    /**
     * Unified LLM execution method with fallback and circuit breaker.
     * If any provider hits 429, trips circuit breaker and falls back immediately to next provider.
     */
    fun callLlmWithFallback(
        prompt: String,
        systemPrompt: String = "",
        temperature: Double = 0.2,
        moduleName: String = "",
        attemptCount: Int = 0
    ): String {
        val sequence = getFallbackSequence()
        var lastException: Exception? = null

        for (provider in sequence) {
            try {
                when (provider) {
                    LlmProvider.GROQ -> {
                        if (isProviderRateLimited(LlmProvider.GROQ)) {
                            println("[LlmConfig] Groq is rate-limited (circuit breaker tripped). Skipping straight to next fallback.")
                            continue
                        }
                        return callGroqDirect(prompt, systemPrompt, temperature, moduleName)
                    }
                    LlmProvider.OLLAMA -> {
                        if (isProviderRateLimited(LlmProvider.OLLAMA)) {
                            println("[LlmConfig] Ollama is rate-limited (circuit breaker tripped). Skipping straight to next fallback.")
                            continue
                        }
                        return callOllamaDirect(prompt, systemPrompt, temperature, moduleName, attemptCount)
                    }
                }
            } catch (e: FatalWatchdogAbortException) {
                // Must propagate fatal watchdog halt immediately
                throw e
            } catch (e: Exception) {
                if (e.cause is FatalWatchdogAbortException) throw e.cause as FatalWatchdogAbortException
                lastException = e
                if (provider == LlmProvider.GROQ) {
                    markProviderRateLimited(LlmProvider.GROQ, e.message ?: "Groq call failed")
                    println("[LlmConfig] Groq failure encountered (${e.message}). Circuit breaker tripped. Skipping Groq for rest of run and switching immediately to Ollama...")
                } else {
                    val is429 = isRateLimitOr429(e)
                    if (is429) {
                        markProviderRateLimited(provider, e.message ?: "429 Rate limit exceeded")
                        println("[LlmConfig] Provider $provider 429 Rate Limit encountered. Circuit breaker tripped. Skipping for rest of run and switching to next fallback...")
                    } else {
                        println("[LlmConfig] Provider $provider failed for '${moduleName.ifBlank { "request" }}': ${e.message}. Trying next fallback...")
                    }
                }
            }
        }

        throw lastException ?: IllegalStateException("All LLM providers failed for ${moduleName.ifBlank { "request" }}")
    }

    fun isConfigOr404Error(e: Throwable): Boolean {
        val msg = (e.message ?: "") + " " + (e.cause?.message ?: "")
        val msgLower = msg.lowercase()
        return msgLower.contains("404") ||
               msgLower.contains("not found") ||
               msgLower.contains("not_found") ||
               msgLower.contains("model_not_found") ||
               msgLower.contains("401") ||
               msgLower.contains("403") ||
               msgLower.contains("unauthorized") ||
               msgLower.contains("invalid api key") ||
               msgLower.contains("invalid_api_key") ||
               msgLower.contains("invalid model") ||
               msgLower.contains("does not exist") ||
               msgLower.contains("unknown model") ||
               msgLower.contains("modelnotset")
    }
}
