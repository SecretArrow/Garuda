package com.motion.browser.ai.core

import kotlinx.serialization.Serializable

/**
 * Core AI layer types — Motion Browser contract §3.3.
 *
 * SECURITY (spec §10/§63): API keys never appear in any of these types.
 * [ProviderConfig.keyId] is only a *reference* to a key stored in
 * com.motion.browser.security.SecretStore ("provider_key_<id>").
 */

/** Supported AI provider backends. OPENAI_COMPATIBLE/OPENROUTER/OLLAMA/CUSTOM share one adapter. */
enum class ProviderType { OPENAI_COMPATIBLE, ANTHROPIC, GEMINI, OPENROUTER, OLLAMA, CUSTOM }

/** Logical roles the agent layer maps onto configured providers. */
enum class RoleType { PLANNER, ACTION, VISION, SUMMARIZATION, BACKGROUND }

/** One chat message. [role] is "system" | "user" | "assistant". */
data class LlmMessage(val role: String, val content: String)

data class LlmOptions(
    val maxTokens: Int = 2048,
    val temperature: Double = 0.2,
    val jsonMode: Boolean = false,
    /** Raw base64 of a JPEG frame (no data: prefix). Attached only when set; adapters that support vision forward it. */
    val imageBase64: String? = null
)

data class LlmRequest(
    val messages: List<LlmMessage>,
    val model: String,
    val options: LlmOptions
)

data class LlmResponse(
    val ok: Boolean,
    val text: String = "",
    /** Always safe to surface in UI/logs — never contains the API key. */
    val error: String? = null,
    val promptTokens: Long = 0,
    val completionTokens: Long = 0
)

@Serializable
data class ProviderConfig(
    val id: String,
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val model: String,
    val enabled: Boolean = false,
    /** SecretStore key reference ("provider_key_<id>") — NEVER the key itself. */
    val keyId: String = ""
)

interface AiProvider {
    val type: ProviderType
    suspend fun complete(cfg: ProviderConfig, req: LlmRequest): LlmResponse
}
