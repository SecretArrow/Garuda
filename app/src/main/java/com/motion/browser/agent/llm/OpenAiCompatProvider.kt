package com.motion.browser.agent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * OpenAI-compatible chat adapter. Covers OpenAI, Groq, OpenRouter, DeepSeek,
 * Mistral, Together, xAI, Fireworks, vLLM, z.ai (openai-compat path) and
 * Ollama's OpenAI-compatible endpoint — everywhere {base}/chat/completions
 * speaks the same wire format (plan Prompt 5 §2).
 */
class OpenAiCompatProvider(
    private val baseUrlProvider: () -> String,
) : LlmProvider {

    override val protocol: String = "openai"

    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    override suspend fun chat(request: LlmRequest, apiKey: String?): Flow<StreamEvent> = flow {
        val body = JSONObject().apply {
            put("model", request.model)
            put("stream", true)
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
            put("stream_options", JSONObject().put("include_usage", true))
            val messages = JSONArray()
            request.system?.let { messages.put(JSONObject().put("role", "system").put("content", it)) }
            request.messages.forEach { m ->
                when (m.role) {
                    "tool" -> messages.put(JSONObject()
                        .put("role", "tool")
                        .put("tool_call_id", m.toolCallId ?: "")
                        .put("content", m.content))
                    "assistant" -> {
                        val o = JSONObject().put("role", "assistant").put("content", m.content)
                        if (m.toolCalls.isNotEmpty()) {
                            val calls = JSONArray()
                            m.toolCalls.forEach { c ->
                                calls.put(JSONObject()
                                    .put("id", c.id)
                                    .put("type", "function")
                                    .put("function", JSONObject()
                                        .put("name", c.name)
                                        .put("arguments", c.argumentsJson)))
                            }
                            o.put("tool_calls", calls)
                        }
                        messages.put(o)
                    }
                    else -> messages.put(JSONObject().put("role", m.role).put("content", m.content))
                }
            }
            put("messages", messages)
            if (request.tools.isNotEmpty()) put("tools", LlmJson.toolsArray(request.tools))
        }

        val httpRequest = Request.Builder()
            .url("${baseUrl()}/chat/completions")
            .header("Content-Type", "application/json")
            .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        // Accumulate streamed tool-call argument fragments by index.
        val toolAccumulator = LinkedHashMap<Int, StringBuilder>()
        val toolMeta = LinkedHashMap<Int, Pair<String, String>>() // index → (id, name)
        var textAll = StringBuilder()
        var promptTokens = 0L
        var completionTokens = 0L

        LlmHttp.client.newCall(httpRequest).execute().use { response ->
            if (!response.isSuccessful) {
                val err = runCatching { response.body?.string() }.getOrNull().orEmpty().take(400)
                emit(StreamEvent.Failure("HTTP ${response.code}: $err"))
                emit(StreamEvent.Done)
                return@flow
            }
            for (data in response.sseDataLines()) {
                val chunk = runCatching { JSONObject(data) }.getOrNull() ?: continue
                val usage = chunk.optJSONObject("usage")
                if (usage != null) {
                    promptTokens = usage.optLong("prompt_tokens", promptTokens)
                    completionTokens = usage.optLong("completion_tokens", completionTokens)
                }
                val choice = chunk.optJSONArray("choices")?.optJSONObject(0) ?: continue
                val delta = choice.optJSONObject("delta") ?: continue
                delta.optString("content").takeIf { it.isNotEmpty() }?.let {
                    textAll.append(it)
                    emit(StreamEvent.TextDelta(it))
                }
                val toolCalls = delta.optJSONArray("tool_calls")
                if (toolCalls != null) {
                    for (i in 0 until toolCalls.length()) {
                        val tc = toolCalls.optJSONObject(i) ?: continue
                        val index = tc.optInt("index", i)
                        tc.optJSONObject("function")?.let { fn ->
                            fn.optString("name").takeIf { it.isNotEmpty() }?.let { name ->
                                toolMeta[index] = (tc.optString("id", "call_$index") to name)
                            }
                            fn.optString("arguments").takeIf { it.isNotEmpty() }?.let { args ->
                                toolAccumulator.getOrPut(index) { StringBuilder() }.append(args)
                            }
                        }
                    }
                }
            }
        }
        toolMeta.forEach { (index, meta) ->
            val args = toolAccumulator[index]?.toString() ?: "{}"
            emit(StreamEvent.ToolCall(ToolCallRequest(meta.first, meta.second, args)))
        }
        if (promptTokens > 0 || completionTokens > 0) {
            emit(StreamEvent.Usage(promptTokens, completionTokens))
        }
        emit(StreamEvent.Done)
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(baseUrl: String, apiKey: String?): List<String> {
        val base = baseUrl.trimEnd('/')
        val urls = listOf("$base/models", "$base/v1/models")
        for (url in urls) {
            val req = Request.Builder().url(url)
                .apply { apiKey?.let { header("Authorization", "Bearer $it") } }
                .get().build()
            runCatching {
                LlmHttp.client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) return@runCatching
                    val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull()
                        ?: return@runCatching
                    val data = json.optJSONArray("data") ?: return@runCatching
                    val models = (0 until data.length()).mapNotNull { i ->
                        data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                    }
                    if (models.isNotEmpty()) return models
                }
            }
        }
        return emptyList()
    }
}
