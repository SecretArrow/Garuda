package com.motion.browser.ai.providers

import com.motion.browser.ai.core.AIRequestException
import com.motion.browser.ai.core.AiJson
import com.motion.browser.ai.core.AiProvider
import com.motion.browser.ai.core.Http
import com.motion.browser.ai.core.LlmRequest
import com.motion.browser.ai.core.LlmResponse
import com.motion.browser.ai.core.ProviderConfig
import com.motion.browser.ai.core.ProviderType
import com.motion.browser.ai.core.arrField
import com.motion.browser.ai.core.longField
import com.motion.browser.ai.core.objField
import com.motion.browser.ai.core.strField
import com.motion.browser.security.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Anthropic Messages API adapter.
 *
 * POST {base}/v1/messages (base default https://api.anthropic.com) with headers
 * `x-api-key` (key fetched from SecretStore via cfg.keyId — never logged/stored
 * elsewhere) and `anthropic-version: 2023-06-01`.
 *
 * System messages are extracted into the top-level `system` field; remaining
 * messages are merged into strictly alternating user/assistant turns with
 * text-part content arrays. Vision uses
 * {type:"image", source:{type:"base64", media_type:"image/jpeg", data}}.
 * jsonMode (no native response_format in the Messages API) is emulated with a
 * system instruction demanding a single JSON document.
 */
class AnthropicProvider(private val secretStore: SecretStore) : AiProvider {

    override val type: ProviderType = ProviderType.ANTHROPIC

    /** Internal turn model: role -> ordered content parts. */
    private data class Turn(val role: String, val parts: MutableList<JsonObject>)

    override suspend fun complete(cfg: ProviderConfig, req: LlmRequest): LlmResponse =
        withContext(Dispatchers.IO) {
            val key = currentKey(cfg)
            try {
                if (key.isNullOrBlank()) {
                    return@withContext fail("Anthropic provider \"${cfg.name}\" has no API key saved.", key)
                }
                if (req.model.isBlank()) {
                    return@withContext fail("Provider \"${cfg.name}\" has no model set.", key)
                }
                val base = cfg.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE }
                val url = "$base/v1/messages"
                val headers = mapOf(
                    "x-api-key" to key,
                    "anthropic-version" to API_VERSION
                )

                val result = Http.post(url, headers, buildBody(req).toString())

                val root = runCatching { AiJson.parseToJsonElement(result.body) }.getOrNull()
                    ?: return@withContext fail("Malformed response from \"${cfg.name}\" (HTTP ${result.status}).", key)

                if (!result.isSuccessful) {
                    val detail = root.objField("error")?.strField("message")
                    return@withContext fail("HTTP ${result.status}${detail?.let { d -> ": $d" } ?: ""}", key)
                }

                // content: [{type:"text", text:"..."} ...] — join all text blocks.
                val text = root.arrField("content")
                    ?.mapNotNull { block -> if (block.strField("type") == "text") block.strField("text") else null }
                    ?.joinToString("")
                    .orEmpty()
                if (text.isEmpty()) {
                    return@withContext fail("Provider \"${cfg.name}\" returned an empty response.", key)
                }

                val usage = root.objField("usage")
                LlmResponse(
                    ok = true,
                    text = text,
                    promptTokens = usage?.longField("input_tokens") ?: 0L,
                    completionTokens = usage?.longField("output_tokens") ?: 0L
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: AIRequestException) {
                fail(e.message ?: "AI request failed.", key)
            } catch (e: Exception) {
                // Class name only — exception messages may echo request snippets.
                fail("AI request failed: ${e.javaClass.simpleName}", key)
            }
        }

    private fun buildBody(req: LlmRequest): JsonObject {
        val image = req.options.imageBase64?.takeIf { it.isNotBlank() }
        var systemText = req.messages
            .filter { it.role.equals("system", ignoreCase = true) }
            .joinToString("\n\n") { it.content }
        if (req.options.jsonMode) {
            systemText = (if (systemText.isBlank()) "" else "$systemText\n\n") +
                "Respond with a single valid JSON document and nothing else."
        }

        val turns = mutableListOf<Turn>()
        for (m in req.messages) {
            if (m.role.equals("system", ignoreCase = true)) continue
            val role = if (m.role.equals("assistant", ignoreCase = true)) "assistant" else "user"
            val part = buildJsonObject {
                put("type", "text")
                put("text", m.content.ifEmpty { " " }) // API rejects empty text blocks
            }
            val last = turns.lastOrNull()
            if (last != null && last.role == role) last.parts.add(part) else turns.add(Turn(role, mutableListOf(part)))
        }

        if (image != null) {
            val imagePart = buildJsonObject {
                put("type", "image")
                put("source", buildJsonObject {
                    put("type", "base64")
                    put("media_type", "image/jpeg")
                    put("data", image)
                })
            }
            val idx = turns.indexOfLast { it.role == "user" }
            if (idx >= 0) turns[idx].parts.add(imagePart) else turns.add(Turn("user", mutableListOf(imagePart)))
        }
        if (turns.isEmpty()) {
            turns.add(Turn("user", mutableListOf(textPart(" "))))
        }
        if (turns.first().role != "user") {
            // Messages API requires the first turn to be "user".
            turns.add(0, Turn("user", mutableListOf(textPart("(continue)"))))
        }

        return buildJsonObject {
            put("model", req.model)
            put("max_tokens", req.options.maxTokens)
            put("temperature", req.options.temperature.coerceIn(0.0, 1.0)) // Anthropic range 0..1
            if (systemText.isNotBlank()) put("system", systemText)
            put("messages", buildJsonArray {
                for (t in turns) {
                    add(buildJsonObject {
                        put("role", t.role)
                        put("content", JsonArray(t.parts))
                    })
                }
            })
        }
    }

    private fun textPart(text: String): JsonObject = buildJsonObject {
        put("type", "text")
        put("text", text)
    }

    private fun currentKey(cfg: ProviderConfig): String? =
        cfg.keyId.takeIf { it.isNotBlank() }?.let { runCatching { secretStore.get(it) }.getOrNull() }

    private fun fail(message: String, key: String?): LlmResponse =
        LlmResponse(ok = false, error = Http.redact(message, key))

    companion object {
        const val DEFAULT_BASE = "https://api.anthropic.com"
        const val API_VERSION = "2023-06-01"
    }
}
