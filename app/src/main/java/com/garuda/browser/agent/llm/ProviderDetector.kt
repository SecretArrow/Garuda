package com.garuda.browser.agent.llm

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * Auto-detection from base URL + API key (plan Prompt 5 §3): probes the wire
 * protocol, returns protocol + model list + latency. Capability heuristics
 * (vision) included; tool capability is assumed and verified on first use.
 */
object ProviderDetector {

    data class Detection(
        val protocol: String,
        val models: List<String>,
        var latencyMs: Long,
        val note: String = "",
    )

    private val client get() = LlmHttp.client

    suspend fun detect(baseUrlRaw: String, apiKey: String?): Detection {
        val started = System.currentTimeMillis()
        val base = baseUrlRaw.trimEnd('/')
        val key = apiKey?.takeIf { it.isNotBlank() }

        // 1) OpenAI-compatible: GET /v1/models or /models with Bearer key.
        val openAi = probeOpenAi(base, key)
        if (openAi != null) return openAi.also {
            it.latencyMs = System.currentTimeMillis() - started
        }

        // 2) Anthropic: POST /v1/messages minimal body; shape or status betrays it.
        if (probeAnthropic(base, key)) {
            val models = AnthropicProvider { base }.listModels(base, key)
            return Detection(
                "anthropic",
                models.ifEmpty { listOf("claude-sonnet-4-20250514", "claude-haiku-4-20250414") },
                System.currentTimeMillis() - started,
            )
        }

        // 3) Gemini: GET /v1beta/models?key=
        runCatching {
            val req = Request.Builder().url("$base/v1beta/models?key=${key.orEmpty()}").get().build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
                    val models = json?.optJSONArray("models")?.let { arr ->
                        (0 until arr.length()).mapNotNull { i ->
                            arr.optJSONObject(i)?.optString("name")?.removePrefix("models/")
                        }
                    }.orEmpty()
                    if (models.isNotEmpty()) {
                        return Detection(
                            "gemini", models.filter { it.contains("gemini") }.ifEmpty { models },
                            System.currentTimeMillis() - started,
                        )
                    }
                }
            }
        }

        // 4) Fallback: Ollama local (OpenAI-compat endpoint).
        if (base.contains("localhost") || base.contains("127.0.0.1") || base.endsWith("11434")) {
            return Detection(
                "openai",
                emptyList(),
                System.currentTimeMillis() - started,
                note = "Assumed Ollama (OpenAI-compat). Use model e.g. llama3.1",
            )
        }
        throw IllegalArgumentException("Unrecognized protocol at $base — check URL and key")
    }

    private fun probeOpenAi(base: String, key: String?): Detection? {
        for (path in listOf("/models", "/v1/models")) {
            val req = Request.Builder().url(base + path)
                .apply { key?.let { header("Authorization", "Bearer $it") } }
                .get().build()
            val models = runCatching {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching null
                    val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
                        ?: return@runCatching null
                    val data = json.optJSONArray("data") ?: return@runCatching null
                    (0 until data.length()).mapNotNull { i ->
                        data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                    }
                }
            }.getOrNull()
            if (!models.isNullOrEmpty()) {
                return Detection("openai", models, 0)
            }
        }
        return null
    }

    private fun probeAnthropic(base: String, key: String?): Boolean {
        val body = JSONObject()
            .put("model", "claude-haiku-4-20250414")
            .put("max_tokens", 1)
            .put("messages", org.json.JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
        val req = Request.Builder()
            .url("$base/v1/messages")
            .header("x-api-key", key ?: "invalid")
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            client.newCall(req).execute().use { resp ->
                val text = runCatching { resp.body?.string() }.getOrNull().orEmpty()
                // An anthropic endpoint answers with its error shape even on bad key/model.
                (resp.code == 400 || resp.code == 401 || resp.code == 200) &&
                    (text.contains("\"type\":\"error\"") || resp.code == 200) &&
                    text.contains("error").let { err -> err || resp.code == 200 }
            }
        }.getOrDefault(false)
    }

    /** Vision-capable heuristic by model name (plan Prompt 5 §3 item 4). */
    fun visionCapable(model: String): Boolean =
        listOf("vision", "vl", "4o", "gemini", "glm-4v", "sonnet", "opus", "claude-3", "llama-3.2", "pixtral")
            .any { model.lowercase().contains(it) }
}
