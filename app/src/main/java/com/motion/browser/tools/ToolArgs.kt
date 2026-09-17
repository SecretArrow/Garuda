package com.motion.browser.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Internal JSON helpers shared by all tools: defensive argument parsing (never throw on
 * malformed planner output) and defensive parsing of JavaScript results.
 */
internal object ToolArgs {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Parse argsJson as a JSON object; malformed/blank input yields an empty object. */
    fun parse(argsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(argsJson.ifBlank { "{}" }).jsonObject }
            .getOrDefault(buildJsonObject { })

    fun str(obj: JsonObject, key: String): String? = when (val e = obj[key]) {
        is JsonPrimitive -> if (e is JsonNull) null else e.content
        else -> null
    }

    fun int(obj: JsonObject, key: String, default: Int): Int =
        (obj[key] as? JsonPrimitive)?.content?.trim()?.toIntOrNull() ?: default

    fun long(obj: JsonObject, key: String, default: Long): Long =
        (obj[key] as? JsonPrimitive)?.content?.trim()?.toLongOrNull() ?: default

    fun bool(obj: JsonObject, key: String, default: Boolean): Boolean =
        (obj[key] as? JsonPrimitive)?.booleanOrNull ?: default

    /**
     * Parse a browser JS evaluation result. evaluateJs returns a JSON-encoded value, so the
     * payload may be double-encoded (a JSON string containing JSON). Unwrap one level when
     * the outer element is a string that itself parses as JSON.
     */
    fun parseJsResult(raw: String?): JsonElement {
        if (raw.isNullOrBlank()) return JsonNull
        val first = runCatching { json.parseToJsonElement(raw) }.getOrNull() ?: return JsonPrimitive(raw)
        if (first is JsonPrimitive && first.isString) {
            val inner = runCatching { json.parseToJsonElement(first.content) }.getOrNull()
            if (inner != null) return inner
        }
        return first
    }

    /** Parse raw text as JSON, or null when it is not JSON (callers decide how to wrap it). */
    fun parseElement(raw: String?): JsonElement? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.parseToJsonElement(raw) }.getOrNull()
    }

    /** JSON-escape a Kotlin string for safe embedding into a JavaScript snippet. */
    fun jsString(value: String): String = JsonPrimitive(value).toString()
}
