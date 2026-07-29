package com.issam.apollo.config

import io.github.cdimascio.dotenv.dotenv

enum class LlmProvider {
    GEMINI,
    GROQ
}

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

    val geminiApiKey: String = dotenv?.get("GEMINI_API_KEY") ?: System.getenv("GEMINI_API_KEY") ?: ""
    val groqApiKey: String = dotenv?.get("GROQ_API_KEY") ?: System.getenv("GROQ_API_KEY") ?: ""

    val defaultProvider: LlmProvider = when ((dotenv?.get("LLM_PROVIDER") ?: System.getenv("LLM_PROVIDER") ?: "gemini").lowercase()) {
        "groq" -> LlmProvider.GROQ
        else -> LlmProvider.GEMINI
    }

    val defaultModel: String = dotenv?.get("LLM_MODEL") ?: System.getenv("LLM_MODEL") ?: "llama-3.3-70b-versatile"

    fun getFastProfile(): ModelProfile {
        return ModelProfile(
            provider = defaultProvider,
            modelName = if (defaultProvider == LlmProvider.GEMINI) "gemini-2.5-flash" else "llama-3.3-70b-versatile",
            apiKey = if (defaultProvider == LlmProvider.GEMINI) geminiApiKey else groqApiKey,
            temperature = 0.1
        )
    }

    fun getHeavyProfile(): ModelProfile {
        return ModelProfile(
            provider = defaultProvider,
            modelName = defaultModel,
            apiKey = if (defaultProvider == LlmProvider.GEMINI) geminiApiKey else groqApiKey,
            temperature = 0.2
        )
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
