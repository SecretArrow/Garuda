package com.motion.browser.ai.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Lenient JSON instance used for all provider request/response handling.
 * Provider responses vary wildly (Ollama / OpenRouter / llama.cpp / vLLM ...),
 * so parsing goes through tolerant JsonElement navigation instead of strict DTOs.
 */
internal val AiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    explicitNulls = false
}

/** Value of [key] on this JSON object, or null when missing / receiver is not an object. */
internal fun JsonElement?.field(key: String): JsonElement? =
    (this as? JsonObject)?.get(key)

internal fun JsonElement?.objField(key: String): JsonObject? =
    field(key) as? JsonObject

internal fun JsonElement?.arrField(key: String): JsonArray? =
    field(key) as? JsonArray

/**
 * String value of [key]. JsonNull / missing -> null.
 * Objects/arrays fall back to their raw JSON text (never contains our API key).
 */
internal fun JsonElement?.strField(key: String): String? {
    val v = field(key) ?: return null
    return when (v) {
        is JsonNull -> null
        is JsonPrimitive -> v.contentOrNull
        else -> v.toString()
    }
}

/** Numeric value of [key] (works for both int and long primitives), or null. */
internal fun JsonElement?.longField(key: String): Long? =
    (field(key) as? JsonPrimitive)?.longOrNull

/**
 * String content of a JSON value: primitive -> its content;
 * array of content parts -> all {"text": ...} parts joined; else null.
 * Handles OpenAI-style responses where `message.content` may be a string or a part array.
 */
internal fun JsonElement?.stringContent(): String? = when (this) {
    null, is JsonNull -> null
    is JsonPrimitive -> contentOrNull
    is JsonArray -> mapNotNull { part -> part.strField("text") }.joinToString("").ifEmpty { null }
    else -> null
}
