package com.issam.apollo.config

import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LlmConfigTest {

    @BeforeTest
    @AfterTest
    fun cleanup() {
        LlmConfig.resetGroqRateLimit()
    }

    @Test
    fun `test LLM provider configuration and profiles`() {
        assertTrue(LlmConfig.moduleTimeoutMs > 0, "Module timeout must be positive")
        assertTrue(LlmConfig.getFallbackSequence().isNotEmpty(), "Fallback sequence must not be empty")
        assertEquals(LlmConfig.defaultProvider, LlmConfig.getFastProfile().provider)
        assertEquals(LlmConfig.defaultProvider, LlmConfig.getHeavyProfile().provider)
    }

    @Test
    fun `test Groq 429 rate limit error detection and circuit breaker`() {
        val groq429ErrorMessage = """
            Error from client: OpenAILLMClient
            Status code: 429
            Error body:
            {"error":{"message":"Rate limit reached for model `llama-3.3-70b-versatile` in organization `org_01kymmw7h8e4d8p25mzz16jg7b` service tier `on_demand` on tokens per minute (TPM): Limit 12000, Used 5538, Requested 6661. Please try again in 994.999999ms. Need more tokens? Upgrade to Dev Tier today at https://console.groq.com/settings/billing","type":"tokens","code":"rate_limit_exceeded"}}
        """.trimIndent()

        // 1. Verify 429 error detection
        assertTrue(LlmConfig.isRateLimitOr429(RuntimeException(groq429ErrorMessage)))
        assertTrue(LlmConfig.isRateLimitOr429(RuntimeException("HTTP 429 Too Many Requests")))
        assertTrue(LlmConfig.isRateLimitOr429(RuntimeException("Rate limit reached on TPM")))
        assertFalse(LlmConfig.isRateLimitOr429(RuntimeException("Connection timed out")))

        // 2. Verify circuit breaker behavior
        assertFalse(LlmConfig.isGroqRateLimited)
        LlmConfig.markGroqRateLimited(groq429ErrorMessage)
        assertTrue(LlmConfig.isGroqRateLimited)

        // 3. Fallback sequence should skip Groq and go straight to Ollama
        assertEquals(listOf(LlmProvider.OLLAMA), LlmConfig.getFallbackSequence())

        // 4. Reset circuit breaker
        LlmConfig.resetGroqRateLimit()
        assertFalse(LlmConfig.isGroqRateLimited)
    }

    @Test
    fun `test isConfigOr404Error detection`() {
        assertTrue(LlmConfig.isConfigOr404Error(RuntimeException("model 'qwen2.5-coder:14b' not found")))
        assertTrue(LlmConfig.isConfigOr404Error(RuntimeException("404 Not Found")))
        assertTrue(LlmConfig.isConfigOr404Error(RuntimeException("Invalid API Key: 401 Unauthorized")))
        assertFalse(LlmConfig.isConfigOr404Error(RuntimeException("ConnectException: Connection refused")))
    }

    @Test
    fun `test 2-tier Ollama model routing`() {
        // Tier 1: Migrator / 1st attempt (attemptCount = 0) uses primary (27b)
        assertEquals(LlmConfig.ollamaModelPrimary, LlmConfig.selectOllamaModel("TestModule", attemptCount = 0))

        // Tier 1: Fixer attempt #1 and #2 (attemptCount = 1, 2) still use primary (27b)
        assertEquals(LlmConfig.ollamaModelPrimary, LlmConfig.selectOllamaModel("TestModule", attemptCount = 1))
        assertEquals(LlmConfig.ollamaModelPrimary, LlmConfig.selectOllamaModel("TestModule", attemptCount = 2))

        // Tier 2: Fixer attempt #4+ (after 3 failures on primary, attemptCount >= 3) escalates to 30b
        assertEquals(LlmConfig.ollamaModelEscalation, LlmConfig.selectOllamaModel("TestModule", attemptCount = 3))
        assertEquals(LlmConfig.ollamaModelEscalation, LlmConfig.selectOllamaModel("TestModule", attemptCount = 4))

        // Diagnostics contains primary and escalation keys
        assertTrue(LlmConfig.configDiagnostics().containsKey("OLLAMA_MODEL_PRIMARY"))
        assertTrue(LlmConfig.configDiagnostics().containsKey("OLLAMA_MODEL_ESCALATION"))
    }
}
