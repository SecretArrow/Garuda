package com.garuda.browser.agent.llm

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Google Gemini adapter (generativelanguage, streamGenerateContent alt=sse).
 * Tool calls arrive as functionCall parts; results go back as functionResponse
 * parts inside the next user turn (plan Prompt 5 §2).
 */
class GeminiProvider(
    private val baseUrlProvider: () -> String,
) : LlmProvider {

    override val protocol: String = "gemini"

    private fun baseUrl() = baseUrlProvider().trimEnd('/').ifBlank { "https://generativelanguage.googleapis.com" }

    override suspend fun chat(request: LlmRequest, apiKey: String?): Flow<StreamEvent> = flow {
        val contents = JSONArray()
        request.messages.forEach { m ->
            val role = if (m.role == "assistant") "model" else "user"
            when {
                m.role == "tool" -> {
                    // functionResponse for the last matching functionCall
                    val call = recentCallFor(m.toolCallId, request.messages)
                    contents.put(JSONObject()
                        .put("role", "user")
                        .put("parts", JSONArray().put(JSONObject()
                            .put("functionResponse", JSONObject()
                                .put("name", call?.first ?: "tool")
                                .put("response", JSONObject().put("result", m.content))))))
                }
                m.role == "assistant" && m.toolCalls.isNotEmpty() -> {
                    val parts = JSONArray()
                    if (m.content.isNotBlank()) parts.put(JSONObject().put("text", m.content))
                    m.toolCalls.forEach { c ->
                        parts.put(JSONObject().put("functionCall", JSONObject()
                            .put("name", c.name)
                            .put("args", LlmJson.parseArgs(c.argumentsJson))))
                    }
                    contents.put(JSONObject().put("role", "model").put("parts", parts))
                }
                else -> contents.put(JSONObject()
                    .put("role", role)
                    .put("parts", JSONArray().put(JSONObject().put("text", m.content))))
            }
        }

        val body = JSONObject().apply {
            request.system?.let { put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", it)))) }
            put("contents", contents)
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().put(JSONObject().put("functionDeclarations", JSONArray().apply {
                    request.tools.forEach { t ->
                        put(JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("parameters", normalizeSchema(t.parametersJsonSchema)))
                    }
                })))
            }
            put("generationConfig", JSONObject()
                .put("temperature", request.temperature)
                .put("maxOutputTokens", request.maxTokens))
        }

        val url = "${baseUrl()}/v1beta/models/${request.model}:streamGenerateContent?alt=sse&key=${apiKey.orEmpty()}"
        val httpRequest = Request.Builder()
            .url(url)
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var pendingToolSeq = 0
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
                chunk.optJSONObject("usageMetadata")?.let {
                    promptTokens = it.optLong("promptTokenCount", promptTokens)
                    completionTokens = it.optLong("candidatesTokenCount", completionTokens)
                }
                val parts = chunk.optJSONArray("candidates")?.optJSONObject(0)
                    ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                for (i in 0 until parts.length()) {
                    val part = parts.optJSONObject(i) ?: continue
                    part.optString("text").takeIf { it.isNotEmpty() }?.let { emit(StreamEvent.TextDelta(it)) }
                    part.optJSONObject("functionCall")?.let { fc ->
                        pendingToolSeq++
                        emit(StreamEvent.ToolCall(ToolCallRequest(
                            id = "gemini_call_$pendingToolSeq",
                            name = fc.optString("name"),
                            argumentsJson = fc.optJSONObject("args")?.toString() ?: "{}",
                        )))
                    }
                }
            }
        }
        if (promptTokens > 0 || completionTokens > 0) {
            emit(StreamEvent.Usage(promptTokens, completionTokens))
        }
        emit(StreamEvent.Done)
    }.flowOn(Dispatchers.IO)

    /** Gemini schemas reject some OpenAI-isms (additionalProperties etc.). */
    private fun normalizeSchema(schema: JSONObject): JSONObject {
        val cleaned = JSONObject(schema.toString())
        cleaned.remove("additionalProperties")
        cleaned.remove("$schema")
        return cleaned
    }

    /** Maps a tool_result back to the function name Gemini expects. */
    private fun recentCallFor(
        toolCallId: String?,
        messages: List<LlmMessage>,
    ): Pair<String, String>? {
        if (toolCallId == null) return null
        for (m in messages.reversed()) {
            val call = m.toolCalls.firstOrNull { it.id == toolCallId } ?: continue
            return call.name to call.id
        }
        return null
    }

    override suspend fun listModels(baseUrl: String, apiKey: String?): List<String> {
        val req = Request.Builder()
            .url("${baseUrl()}/v1beta/models?key=${apiKey.orEmpty()}")
            .get().build()
        return runCatching {
            LlmHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull() ?: return emptyList()
                val models = json.optJSONArray("models") ?: return emptyList()
                (0 until models.length()).mapNotNull { i ->
                    val name = models.optJSONObject(i)?.optString("name") ?: return@mapNotNull null
                    name.removePrefix("models/")
                }.filter { it.contains("gemini") }
            }
        }.getOrDefault(emptyList())
    }
}
