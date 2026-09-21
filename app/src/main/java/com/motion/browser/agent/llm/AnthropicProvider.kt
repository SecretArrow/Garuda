package com.motion.browser.agent.llm

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
 * Anthropic Messages adapter ({base}/v1/messages, x-api-key + anthropic-version).
 * Tool calls arrive as content blocks (tool_use) and results go back as
 * tool_result blocks inside user messages (plan Prompt 5 §2).
 */
class AnthropicProvider(
    private val baseUrlProvider: () -> String,
) : LlmProvider {

    override val protocol: String = "anthropic"

    private fun baseUrl() = baseUrlProvider().trimEnd('/')

    override suspend fun chat(request: LlmRequest, apiKey: String?): Flow<StreamEvent> = flow {
        val content = JSONArray()
        request.messages.forEach { m ->
            when {
                m.role == "tool" -> {
                    // Tool result travels inside a user message.
                    content.put(JSONObject()
                        .put("role", "user")
                        .put("content", JSONArray().put(JSONObject()
                            .put("type", "tool_result")
                            .put("tool_use_id", m.toolCallId ?: "")
                            .put("content", m.content))))
                }
                m.role == "assistant" && m.toolCalls.isNotEmpty() -> {
                    val blocks = JSONArray()
                    if (m.content.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", m.content))
                    m.toolCalls.forEach { c ->
                        blocks.put(JSONObject()
                            .put("type", "tool_use")
                            .put("id", c.id)
                            .put("name", c.name)
                            .put("input", LlmJson.parseArgs(c.argumentsJson)))
                    }
                    content.put(JSONObject().put("role", "assistant").put("content", blocks))
                }
                else -> content.put(JSONObject()
                    .put("role", if (m.role == "assistant") "assistant" else "user")
                    .put("content", m.content))
            }
        }

        val body = JSONObject().apply {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            put("stream", true)
            put("temperature", request.temperature)
            request.system?.let { put("system", it) }
            put("messages", content)
            if (request.tools.isNotEmpty()) {
                put("tools", JSONArray().apply {
                    request.tools.forEach { t ->
                        put(JSONObject()
                            .put("name", t.name)
                            .put("description", t.description)
                            .put("input_schema", t.parametersJsonSchema))
                    }
                })
            }
        }

        val httpRequest = Request.Builder()
            .url("${baseUrl()}/v1/messages")
            .header("x-api-key", apiKey ?: "")
            .header("anthropic-version", "2023-06-01")
            .header("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        var currentToolId = ""
        var currentToolName = ""
        val argsAcc = StringBuilder()
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
                val event = runCatching { JSONObject(data) }.getOrNull() ?: continue
                when (event.optString("type")) {
                    "content_block_start" -> {
                        val block = event.optJSONObject("content_block") ?: continue
                        if (block.optString("type") == "tool_use") {
                            currentToolId = block.optString("id")
                            currentToolName = block.optString("name")
                            argsAcc.setLength(0)
                        }
                    }
                    "content_block_delta" -> {
                        val delta = event.optJSONObject("delta") ?: continue
                        when (delta.optString("type")) {
                            "text_delta" -> delta.optString("text").takeIf { it.isNotEmpty() }?.let {
                                emit(StreamEvent.TextDelta(it))
                            }
                            "input_json_delta" -> argsAcc.append(delta.optString("partial_json"))
                        }
                    }
                    "content_block_stop" -> {
                        if (currentToolId.isNotEmpty()) {
                            emit(StreamEvent.ToolCall(
                                ToolCallRequest(currentToolId, currentToolName, argsAcc.toString())))
                            currentToolId = ""
                        }
                    }
                    "message_start" -> {
                        event.optJSONObject("message")?.optJSONObject("usage")?.let {
                            promptTokens = it.optLong("input_tokens", promptTokens)
                        }
                    }
                    "message_delta" -> {
                        event.optJSONObject("usage")?.let {
                            completionTokens = it.optLong("output_tokens", completionTokens)
                        }
                    }
                    "error" -> {
                        emit(StreamEvent.Failure(event.optJSONObject("error")?.optString("message") ?: "stream error"))
                    }
                }
            }
        }
        if (promptTokens > 0 || completionTokens > 0) {
            emit(StreamEvent.Usage(promptTokens, completionTokens))
        }
        emit(StreamEvent.Done)
    }.flowOn(Dispatchers.IO)

    override suspend fun listModels(baseUrl: String, apiKey: String?): List<String> {
        val req = Request.Builder()
            .url("${baseUrl.trimEnd('/')}/v1/models")
            .header("x-api-key", apiKey ?: "")
            .header("anthropic-version", "2023-06-01")
            .get().build()
        return runCatching {
            LlmHttp.client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return emptyList()
                val json = runCatching { JSONObject(resp.body?.string().orEmpty()) }.getOrNull() ?: return emptyList()
                val data = json.optJSONArray("data") ?: return emptyList()
                (0 until data.length()).mapNotNull { i ->
                    data.optJSONObject(i)?.optString("id")?.takeIf { it.isNotBlank() }
                }
            }
        }.getOrDefault(emptyList())
    }
}
