package com.motion.browser.ai.core

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * Thrown when an AI HTTP exchange ultimately fails (retries exhausted).
 * [status] is the last HTTP status seen, or 0 for pure network failures.
 * [message] is ALWAYS safe to surface: it never contains secrets, request
 * headers, or request payloads.
 */
class AIRequestException(val status: Int, message: String) : RuntimeException(message)

/** Outcome of one POST exchange (body fully read). */
data class HttpResult(val status: Int, val body: String) {
    val isSuccessful: Boolean get() = status in 200..299
}

/**
 * Shared HTTP layer for all AI providers (contract §3.3).
 *
 * - One shared OkHttpClient: connect 15 s / read 120 s / write 60 s.
 * - [post] runs on Dispatchers.IO and retries IOException / HTTP 429 / HTTP 5xx
 *   with backoff BACKOFF_BASE_MS * attempt, at most [MAX_ATTEMPTS] attempts total.
 * - 2xx and non-retryable 4xx statuses are returned as [HttpResult] so callers
 *   can read provider error details and decide (e.g. drop `response_format`).
 * - Coroutines cancelled mid-flight also cancel the underlying OkHttp call.
 * - Nothing is ever logged; error text never contains the API key or request body.
 */
object Http {

    const val MAX_ATTEMPTS: Int = 3
    const val BACKOFF_BASE_MS: Long = 1_500L
    const val REDACTED: String = "••••"

    val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * POST [jsonBody] to [url] with [headers].
     * Returns [HttpResult] for 2xx and for 4xx statuses that are not worth retrying;
     * throws [AIRequestException] when retries on IOException/429/5xx are exhausted.
     */
    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        jsonBody: String
    ): HttpResult = withContext(Dispatchers.IO) {
        var lastStatus = 0
        var lastBody = ""
        var lastIoError: String? = null
        for (attempt in 1..MAX_ATTEMPTS) {
            val request = Request.Builder()
                .url(url)
                .apply { for ((name, value) in headers) header(name, value) }
                .post(jsonBody.toRequestBody(jsonMediaType))
                .build()
            lastIoError = null
            try {
                val result = exchange(request)
                lastStatus = result.status
                lastBody = result.body
                val retryable = result.status == 429 || result.status in 500..599
                if (result.isSuccessful || !retryable) return@withContext result
            } catch (e: IOException) {
                lastIoError = e.javaClass.simpleName
            }
            if (attempt < MAX_ATTEMPTS) delay(BACKOFF_BASE_MS * attempt)
        }
        throw AIRequestException(lastStatus, describeFailure(lastStatus, lastBody, lastIoError))
    }

    /** One async exchange; coroutine cancellation propagates to the OkHttp call. */
    private suspend fun exchange(request: Request): HttpResult = suspendCancellableCoroutine { cont ->
        val call = client.newCall(request)
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (cont.isActive) cont.resumeWith(Result.failure(e))
            }

            override fun onResponse(call: Call, response: Response) {
                val outcome = try {
                    val body = response.body?.string().orEmpty()
                    Result.success(HttpResult(response.code, body))
                } catch (e: IOException) {
                    Result.failure(e)
                } finally {
                    response.close()
                }
                if (cont.isActive) cont.resumeWith(outcome)
            }
        })
    }

    private fun describeFailure(status: Int, body: String, ioError: String?): String {
        if (status == 0 && ioError != null) {
            return "Network error while contacting the AI provider ($ioError). Check the connection and base URL."
        }
        // Response bodies come from the provider itself; truncate defensively.
        // (Providers never echo our key in bodies, and callers redact the key again anyway.)
        val excerpt = body.replace(Regex("\\s+"), " ").trim().take(300)
        return if (excerpt.isEmpty()) "AI provider returned HTTP $status."
        else "AI provider returned HTTP $status: $excerpt"
    }

    /**
     * Replaces every occurrence of each secret with [REDACTED].
     * Last-resort redaction applied to any message that may reach UI/logs.
     */
    fun redact(message: String?, vararg secrets: String?): String {
        var out = message ?: return ""
        for (secret in secrets) {
            if (secret.isNullOrBlank()) continue
            out = out.replace(secret, REDACTED)
        }
        return out
    }
}
