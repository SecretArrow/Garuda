package com.motion.cdp

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.coroutineContext

/** A CDP event pushed by the browser (method + raw params). */
data class CdpEvent(val method: String, val params: JSONObject)

/**
 * One DevTools WebSocket connection (one CDP endpoint = one tab/page target).
 * Correlates command ids with responses, exposes browser events as a SharedFlow,
 * and provides per-command timeouts. Coroutine-friendly and reconnect-agnostic:
 * ownership of reconnection lives in [DevToolsClient].
 */
class CdpConnection(
    private val ws: WsClient,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val defaultTimeoutMs: Long = 20_000,
) {
    private val pending = ConcurrentHashMap<Int, CompletableDeferred<JSONObject>>()
    private val nextId = AtomicInteger(0)
    private val _events = MutableSharedFlow<CdpEvent>(extraBufferCapacity = 512)
    val events: SharedFlow<CdpEvent> = _events.asSharedFlow()

    @Volatile private var running = false
    private var readerThread: Thread? = null

    /** Starts the reader loop (idempotent). */
    fun start() {
        if (running) return
        running = true
        val thread = Thread({
            try {
                while (running) {
                    val text = ws.receiveText() ?: break
                    handleMessage(text)
                }
            } catch (_: Exception) {
                // Socket died or codec error — complete pending calls with failure.
            } finally {
                running = false
                pending.values.forEach { it.completeExceptionally(IOException("CDP connection closed")) }
                pending.clear()
            }
        }, "cdp-reader-${ws.hashCode()}")
        thread.isDaemon = true
        thread.start()
        readerThread = thread
    }

    private fun handleMessage(text: String) {
        val json = runCatching { JSONObject(text) }.getOrNull() ?: return
        val id = json.optInt("id", -1)
        if (id >= 0 && pending.containsKey(id)) {
            val error = json.optJSONObject("error")
            val deferred = pending.remove(id)
            if (deferred != null) {
                if (error != null) {
                    deferred.completeExceptionally(CdpError(error.optString("message", "CDP error")))
                } else {
                    deferred.complete(json.optJSONObject("result") ?: JSONObject())
                }
            }
        } else {
            val method = json.optString("method", "")
            if (method.isNotEmpty()) {
                _events.tryEmit(CdpEvent(method, json.optJSONObject("params") ?: JSONObject()))
            }
        }
    }

    /**
     * Sends a CDP command and suspends until the matching response arrives or
     * [timeoutMs] elapses. Safe from any coroutine context.
     */
    suspend fun call(
        method: String,
        params: JSONObject? = null,
        timeoutMs: Long = defaultTimeoutMs,
    ): JSONObject = withContext(Dispatchers.IO) {
        val id = nextId.incrementAndGet()
        val deferred = CompletableDeferred<JSONObject>()
        pending[id] = deferred
        val message = JSONObject().apply {
            put("id", id)
            put("method", method)
            put("params", params ?: JSONObject())
        }
        try {
            ws.sendText(message.toString())
        } catch (e: Exception) {
            pending.remove(id)
            throw IOException("Failed to send $method", e)
        }
        try {
            withTimeout(timeoutMs) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(id)
            throw e
        }
    }

    fun close() {
        running = false
        runCatching { ws.close() }
        readerThread?.interrupt()
    }
}

/** Typed DevTools error surfaced from a CDP error response. */
class CdpError(message: String) : IOException(message)

/**
 * High-level self-connecting CDP client (plan §1): discovers the DevTools
 * abstract unix socket belonging to this process, lists targets, and hands out
 * one [CdpConnection] per page target.
 */
class DevToolsClient(
    private val socketName: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) {
    companion object {
        /** Auto-discovers the live socket (fork first, then WebView debug socket). */
        fun autoDiscover(): DevToolsClient? {
            val name = DevToolsLocator.findLiveSocket() ?: return null
            return DevToolsClient(name)
        }
    }

    data class TargetInfo(val targetId: String, val type: String, val title: String, val url: String)

    /** GET /json/list — all debuggable targets on this socket. */
    fun listTargets(): List<TargetInfo> {
        val body = LocalSocketHttp(socketName).get("/json/list")
        val array = org.json.JSONArray(body)
        return (0 until array.length()).mapNotNull { i ->
            val o = array.optJSONObject(i) ?: return@mapNotNull null
            TargetInfo(
                targetId = o.optString("id"),
                type = o.optString("type"),
                title = o.optString("title"),
                url = o.optString("url"),
            )
        }.filter { it.type == "page" }
    }

    /**
     * Connects to a page target: WebSocket path /devtools/page/[targetId].
     * [attach] also enables Page + Runtime domains so navigation and evaluation
     * events flow immediately.
     */
    suspend fun connectTo(targetId: String, attach: Boolean = true): CdpTabSession {
        val ws = WsClient(socketName, "/devtools/page/$targetId")
        ws.connect()
        val connection = CdpConnection(ws, scope)
        connection.start()
        val session = CdpTabSession(targetId, connection)
        if (attach) session.enableLifecycle()
        return session
    }
}
