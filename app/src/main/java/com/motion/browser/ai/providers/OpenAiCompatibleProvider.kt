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
import com.motion.browser.ai.core.field
import com.motion.browser.ai.core.longField
import com.motion.browser.ai.core.objField
import com.motion.browser.ai.core.stringContent
import com.motion.browser.ai.core.strField
import com.motion.browser.security.SecretStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * One adapter for every OpenAI-compatible backend:
 * OPENAI_COMPATIBLE (api.openai.com/v1), OPENROUTER, OLLAMA (local /v1) and CUSTOM (user URL).
 *
 * POST {baseUrl}/chat/completions with `Authorization: Bearer <key from SecretStore>`.
 * The key is fetched per call via cfg.keyId and is never logged, stored or embedded
 * in any error message (spec §10/§63). Ollama-style servers without a key simply
 * omit the Authorization header.
 */
class OpenAiCompatibleProvider(private val secretStore: SecretStore) : AiProvider {

    override val type: ProviderType = ProviderType.OPENAI_COMPATIBLE

    override suspend fun complete(cfg: ProviderConfig, req: LlmRequest): LlmResponse =
        withContext(Dispatchers.IO) {
            val key = currentKey(cfg)
            try {
                val base = cfg.baseUrl.trim().trimEnd('/').ifBlank { baseUrlFromType(cfg.type) }
                if (base.isBlank()) {
                    return@withContext fail("Provider \"${cfg.name}\" needs a base URL (type ${cfg.type}).", key)
                }
                if (req.model.isBlank()) {
                    return@withContext fail("Provider \"${cfg.name}\" has no model set.", key)
                }

                val url = "$base/chat/completions"
                val headers = HashMap<String, String>()
                if (!key.isNullOrBlank()) headers["Authorization"] = "Bearer $key"

                var result = Http.post(url, headers, buildBody(req, jsonMode = req.options.jsonMode).toString())
                // Some OpenAI-compatible servers (Ollama, llama.cpp, vLLM, older proxies)
                // reject `response_format` with 400/404/422 — retry once without it.
                if (req.options.jsonMode && result.status in JSON_REJECT_STATUS) {
                    result = Http.post(url, headers, buildBody(req, jsonMode = false).toString())
                }

                val root = runCatching { AiJson.parseToJsonElement(result.body) }.getOrNull()
                    ?: return@withContext fail("Malformed response from \"${cfg.name}\" (HTTP ${result.status}).", key)

                if (!result.isSuccessful) {
                    val detail = root.objField("error").let { err ->
                        err?.strField("message") ?: root.strField("error")
                    }
                    return@withContext fail("HTTP ${result.status}${detail?.let { d -> ": $d" } ?: ""}", key)
                }

                val text = root.arrField("choices")?.firstOrNull()
                    ?.objField("message")?.field("content")?.stringContent()
                if (text.isNullOrEmpty()) {
                    return@withContext fail("Provider \"${cfg.name}\" returned an empty response.", key)
                }

                val usage = root.objField("usage")
                LlmResponse(
                    ok = true,
                    text = text,
                    promptTokens = usage?.longField("prompt_tokens") ?: 0L,
                    completionTokens = usage?.longField("completion_tokens") ?: 0L
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

    /** Request body: {model, messages, max_tokens, temperature, stream:false} [+ response_format]. */
    private fun buildBody(req: LlmRequest, jsonMode: Boolean): JsonObject {
        val image = req.options.imageBase64?.takeIf { it.isNotBlank() }
        // Vision: attach the image to the LAST user message (or a fresh one when absent).
        val lastUserIndex = req.messages.indexOfLast { it.role.equals("user", ignoreCase = true) }
        return buildJsonObject {
            put("model", req.model)
            put("max_tokens", req.options.maxTokens)
            put("temperature", req.options.temperature)
            put("stream", false)
            if (jsonMode) {
                put("response_format", buildJsonObject { put("type", "json_object") })
            }
            put("messages", buildJsonArray {
                req.messages.forEachIndexed { index, m ->
                    add(buildJsonObject {
                        put("role", m.role)
                        if (image != null && index == lastUserIndex) {
                            put("content", buildJsonArray {
                                if (m.content.isNotEmpty()) {
                                    add(buildJsonObject {
                                        put("type", "text")
                                        put("text", m.content)
                                    })
                                }
                                add(buildJsonObject {
                                    put("type", "image_url")
                                    put("image_url", buildJsonObject {
                                        put("url", "data:image/jpeg;base64,$image")
                                    })
                                })
                            })
                        } else {
                            put("content", m.content)
                        }
                    })
                }
                if (image != null && lastUserIndex < 0) {
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "image_url")
                                put("image_url", buildJsonObject {
                                    put("url", "data:image/jpeg;base64,$image")
                                })
                            })
                        })
                    })
                }
            })
        }
    }

    private fun currentKey(cfg: ProviderConfig): String? =
        cfg.keyId.takeIf { it.isNotBlank() }?.let { runCatching { secretStore.get(it) }.getOrNull() }

    private fun fail(message: String, key: String?): LlmResponse =
        LlmResponse(ok = false, error = Http.redact(message, key))

    companion object {
        /** Statuses that typically mean "response_format is not supported here". */
        val JSON_REJECT_STATUS = setOf(400, 404, 422)

        /** Default endpoints per provider type; [ProviderConfig.baseUrl] overrides when non-blank. */
        fun baseUrlFromType(type: ProviderType): String = when (type) {
            ProviderType.OPENAI_COMPATIBLE -> "https://api.openai.com/v1"
            ProviderType.OPENROUTER -> "https://openrouter.ai/api/v1"
            // Emulator loopback to a host-running Ollama; users override in Providers settings.
            ProviderType.OLLAMA -> "http://10.0.2.2:11434/v1"
            ProviderType.CUSTOM -> "" // must be user-set
            ProviderType.ANTHROPIC -> "https://api.anthropic.com"
            ProviderType.GEMINI -> "https://generativelanguage.googleapis.com"
        }
    }
}
