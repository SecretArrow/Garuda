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
 * Google Gemini adapter (Generative Language REST API).
 *
 * POST {base}/v1beta/models/{model}:generateContent, base default
 * https://generativelanguage.googleapis.com. The API key is sent via the
 * `x-goog-api-key` HEADER (never a query parameter, so it can never leak into
 * logged URLs or exceptions) and is fetched from SecretStore via cfg.keyId.
 *
 * "assistant" maps to role "model"; system messages go into systemInstruction;
 * vision parts use {inline_data:{mime_type, data}}; jsonMode uses
 * generationConfig.responseMimeType = "application/json".
 */
class GeminiProvider(private val secretStore: SecretStore) : AiProvider {

    override val type: ProviderType = ProviderType.GEMINI

    /** Internal turn model: role ("user"|"model") -> ordered parts. */
    private data class Turn(val role: String, val parts: MutableList<JsonObject>)

    override suspend fun complete(cfg: ProviderConfig, req: LlmRequest): LlmResponse =
        withContext(Dispatchers.IO) {
            val key = currentKey(cfg)
            try {
                if (key.isNullOrBlank()) {
                    return@withContext fail("Gemini provider \"${cfg.name}\" has no API key saved.", key)
                }
                if (req.model.isBlank()) {
                    return@withContext fail("Provider \"${cfg.name}\" has no model set.", key)
                }
                val base = cfg.baseUrl.trim().trimEnd('/').ifBlank { DEFAULT_BASE }
                val url = "$base/v1beta/models/${req.model}:generateContent"
                val headers = mapOf("x-goog-api-key" to key)

                val result = Http.post(url, headers, buildBody(req).toString())

                val root = runCatching { AiJson.parseToJsonElement(result.body) }.getOrNull()
                    ?: return@withContext fail("Malformed response from \"${cfg.name}\" (HTTP ${result.status}).", key)

                if (!result.isSuccessful) {
                    val detail = root.objField("error")?.strField("message")
                    return@withContext fail("HTTP ${result.status}${detail?.let { d -> ": $d" } ?: ""}", key)
                }

                val candidate = root.arrField("candidates")?.firstOrNull()
                val text = candidate?.objField("content")?.arrField("parts")
                    ?.mapNotNull { part -> part.strField("text") }
                    ?.joinToString("")
                    .orEmpty()
                if (text.isEmpty()) {
                    val reason = root.objField("promptFeedback")?.strField("blockReason")
                        ?: candidate?.strField("finishReason")
                    return@withContext fail(
                        if (reason != null) "Provider \"${cfg.name}\" returned no content ($reason)."
                        else "Provider \"${cfg.name}\" returned an empty response.",
                        key
                    )
                }

                val usage = root.objField("usageMetadata")
                LlmResponse(
                    ok = true,
                    text = text,
                    promptTokens = usage?.longField("promptTokenCount") ?: 0L,
                    completionTokens = usage?.longField("candidatesTokenCount") ?: 0L
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
        val systemText = req.messages
            .filter { it.role.equals("system", ignoreCase = true) }
            .joinToString("\n\n") { it.content }

        val turns = mutableListOf<Turn>()
        for (m in req.messages) {
            if (m.role.equals("system", ignoreCase = true)) continue
            val role = if (m.role.equals("assistant", ignoreCase = true)) "model" else "user"
            val part = buildJsonObject { put("text", m.content.ifEmpty { " " }) }
            val last = turns.lastOrNull()
            if (last != null && last.role == role) last.parts.add(part) else turns.add(Turn(role, mutableListOf(part)))
        }

        if (image != null) {
            val imagePart = buildJsonObject {
                put("inline_data", buildJsonObject {
                    put("mime_type", "image/jpeg")
                    put("data", image)
                })
            }
            val idx = turns.indexOfLast { it.role == "user" }
            if (idx >= 0) turns[idx].parts.add(imagePart) else turns.add(Turn("user", mutableListOf(imagePart)))
        }
        if (turns.isEmpty()) {
            turns.add(Turn("user", mutableListOf(buildJsonObject { put("text", " ") })))
        }

        return buildJsonObject {
            put("contents", buildJsonArray {
                for (t in turns) {
                    add(buildJsonObject {
                        put("role", t.role)
                        put("parts", JsonArray(t.parts))
                    })
                }
            })
            if (systemText.isNotBlank()) {
                put("systemInstruction", buildJsonObject {
                    put("parts", buildJsonArray {
                        add(buildJsonObject { put("text", systemText) })
                    })
                })
            }
            put("generationConfig", buildJsonObject {
                put("maxOutputTokens", req.options.maxTokens)
                put("temperature", req.options.temperature)
                if (req.options.jsonMode) put("responseMimeType", "application/json")
            })
        }
    }

    private fun currentKey(cfg: ProviderConfig): String? =
        cfg.keyId.takeIf { it.isNotBlank() }?.let { runCatching { secretStore.get(it) }.getOrNull() }

    private fun fail(message: String, key: String?): LlmResponse =
        LlmResponse(ok = false, error = Http.redact(message, key))

    companion object {
        const val DEFAULT_BASE = "https://generativelanguage.googleapis.com"
    }
}
