package com.motion.browser.agent.llm

import kotlinx.coroutines.flow.Flow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Normal form for every LLM interaction (plan Prompt 5 §1): one interface,
 * protocol-specific adapters normalize to/from OpenAI-style tool definitions.
 */

/** OpenAI-format JSON schema for a tool. */
data class ToolDef(val name: String, val description: String, val parametersJsonSchema: JSONObject) {
    fun toJson(): JSONObject = JSONObject()
        .put("type", "function")
        .put("function", JSONObject()
            .put("name", name)
            .put("description", description)
            .put("parameters", parametersJsonSchema))
}

data class ToolCallRequest(val id: String, val name: String, val argumentsJson: String)

data class LlmMessage(
    val role: String, // system | user | assistant | tool
    val content: String,
    val toolCallId: String? = null,
    val toolCalls: List<ToolCallRequest> = emptyList(),
)

data class LlmRequest(
    val model: String,
    val system: String?,
    val messages: List<LlmMessage>,
    val tools: List<ToolDef>,
    val temperature: Double = 0.2,
    val maxTokens: Int = 4096,
)

sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class ToolCall(val call: ToolCallRequest) : StreamEvent()
    data class Usage(val promptTokens: Long, val completionTokens: Long) : StreamEvent()
    data class Failure(val message: String) : StreamEvent()
    object Done : StreamEvent()
}

/**
 * A chat-capable provider. Implementations stream via [StreamEvent] and MUST
 * emit exactly one Usage (when available) and one Done/Failure terminator.
 */
interface LlmProvider {
    val protocol: String
    suspend fun chat(request: LlmRequest, apiKey: String?): Flow<StreamEvent>
    suspend fun listModels(baseUrl: String, apiKey: String?): List<String>
}

/** Shared OkHttp client for all adapters (connection pool, no per-call setup). */
object LlmHttp {
    val client: okhttp3.OkHttpClient by lazy {
        okhttp3.OkHttpClient.Builder()
            .connectTimeout(java.time.Duration.ofSeconds(20))
            .readTimeout(java.time.Duration.ofSeconds(180))
            .writeTimeout(java.time.Duration.ofSeconds(30))
            .build()
    }
}

/** Minimal SSE line reader: yields data payloads until [DONE] or stream end. */
fun okhttp3.Response.sseDataLines(): Sequence<String> = sequence {
    val source = body?.source() ?: return@sequence
    while (true) {
        val line = runCatching { source.readUtf8Line() }.getOrNull() ?: break
        when {
            line.startsWith("data:") -> {
                val data = line.removePrefix("data:").trim()
                if (data == "[DONE]") break
                if (data.isNotEmpty()) yield(data)
            }
            line.isEmpty() -> Unit
        }
    }
}

/** Helpers shared by adapters. */
internal object LlmJson {
    fun parseArgs(raw: String): JSONObject =
        runCatching { JSONObject(raw.ifBlank { "{}" }) }.getOrElse { JSONObject() }

    fun toolsArray(tools: List<ToolDef>): JSONArray =
        JSONArray().apply { tools.forEach { put(it.toJson()) } }
}
