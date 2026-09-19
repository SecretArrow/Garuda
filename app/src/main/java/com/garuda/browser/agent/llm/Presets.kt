package com.garuda.browser.agent.llm

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * API key storage (plan Prompt 5 §5): EncryptedSharedPreferences with an
 * Android Keystore master key. Keys NEVER touch plain prefs or logs.
 */
class KeyVault(context: Context) {

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            "garuda_keys",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun saveKey(providerId: String, apiKey: String) {
        prefs.edit().putString(providerId, apiKey).apply()
    }

    fun getKey(providerId: String): String? =
        prefs.getString(providerId, null)?.takeIf { it.isNotBlank() }

    fun deleteKey(providerId: String) {
        prefs.edit().remove(providerId).apply()
    }
}

/**
 * Preset provider registry (plan Prompt 5 §2). Base URLs only — the user
 * supplies their own key; nothing is hardcoded or fetched remotely.
 */
object Presets {

    data class Preset(val id: String, val name: String, val baseUrl: String, val protocol: String, val defaultModel: String)

    val all = listOf(
        Preset("openai", "OpenAI", "https://api.openai.com/v1", "openai", "gpt-4o"),
        Preset("anthropic", "Anthropic", "https://api.anthropic.com", "anthropic", "claude-sonnet-4-20250514"),
        Preset("gemini", "Google Gemini", "https://generativelanguage.googleapis.com", "gemini", "gemini-1.5-pro"),
        Preset("groq", "Groq", "https://api.groq.com/openai/v1", "openai", "llama-3.3-70b-versatile"),
        Preset("openrouter", "OpenRouter", "https://openrouter.ai/api/v1", "openai", "openai/gpt-4o"),
        Preset("deepseek", "DeepSeek", "https://api.deepseek.com/v1", "openai", "deepseek-chat"),
        Preset("mistral", "Mistral", "https://api.mistral.ai/v1", "openai", "mistral-large-latest"),
        Preset("xai", "xAI", "https://api.x.ai/v1", "openai", "grok-2-latest"),
        Preset("zai", "z.ai GLM", "https://api.z.ai/api/paas/v4", "openai", "glm-4-plus"),
        Preset("together", "Together", "https://api.together.xyz/v1", "openai", "meta-llama/Llama-3-70b-chat-hf"),
        Preset("fireworks", "Fireworks", "https://api.fireworks.ai/inference/v1", "openai", "accounts/fireworks/models/llama-v3p1-70b-instruct"),
        Preset("ollama", "Ollama (local)", "http://localhost:11434/v1", "openai", "llama3.1"),
    )

    fun byId(id: String): Preset? = all.firstOrNull { it.id == id }

    /** Builds the right adapter for a protocol; base URL read live from the entity. */
    fun adapterFor(protocol: String, baseUrlProvider: () -> String): LlmProvider = when (protocol) {
        "anthropic" -> AnthropicProvider(baseUrlProvider)
        "gemini" -> GeminiProvider(baseUrlProvider)
        else -> OpenAiCompatProvider(baseUrlProvider)
    }
}
