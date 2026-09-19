package com.garuda.cdp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * One CDP session bound to a page target. Typed wrappers over the domains the
 * agent needs (plan Prompt 2 §3): Page, Runtime, DOMSnapshot, Input, Network,
 * Emulation. Input dispatch produces REAL, isTrusted:true browser input —
 * this is the core advantage over naive JS injection.
 */
class CdpTabSession(
    val targetId: String,
    private val cdp: CdpConnection,
) {
    val events: SharedFlowAccess = SharedFlowAccess(cdp)

    class SharedFlowAccess(private val cdp: CdpConnection) {
        val events get() = cdp.events
    }

    // ------------------------------------------------------------ lifecycle

    suspend fun enableLifecycle() {
        cdp.call("Page.enable")
        cdp.call("Runtime.enable")
    }

    // ---------------------------------------------------------------- Page

    suspend fun navigate(url: String): JSONObject =
        cdp.call("Page.navigate", JSONObject().put("url", url))

    /** Waits until the page load event fires or [timeoutMs] elapses (best effort). */
    suspend fun waitForLoad(timeoutMs: Long = 15_000): Boolean {
        val start = System.currentTimeMillis()
        // Poll readyState via Runtime (robust across WebView quirks).
        while (System.currentTimeMillis() - start < timeoutMs) {
            val ready = evaluateString("document.readyState")
            if (ready == "complete" || ready == "interactive") {
                if (ready == "complete") return true
            }
            kotlinx.coroutines.delay(120)
        }
        return false
    }

    /** Full-page screenshot as PNG bytes. */
    suspend fun captureScreenshotPng(): ByteArray {
        val result = cdp.call(
            "Page.captureScreenshot",
            JSONObject().put("format", "png").put("fromSurface", true),
        )
        val data = result.optString("data")
        return Base64.decode(data, Base64.DEFAULT)
    }

    suspend fun captureScreenshotBitmap(): Bitmap? =
        runCatching {
            captureScreenshotPng().let { BitmapFactory.decodeByteArray(it, 0, it.size) }
        }.getOrNull()

    // ------------------------------------------------------------- Runtime

    /** Evaluates [expression] with returnByValue; returns the raw result object. */
    suspend fun evaluate(expression: String): JSONObject {
        val result = cdp.call(
            "Runtime.evaluate",
            JSONObject()
                .put("expression", expression)
                .put("returnByValue", true)
                .put("awaitPromise", true),
        )
        return result.optJSONObject("result") ?: JSONObject()
    }

    suspend fun evaluateString(expression: String): String? =
        evaluate(expression).optString("value").ifBlank { null }

    /** Installs a script that re-runs on every new document (verification hooks). */
    suspend fun addInitScript(source: String) {
        cdp.call("Page.addScriptToEvaluateOnNewDocument", JSONObject().put("source", source))
    }

    // ---------------------------------------------------------- DOMSnapshot

    /**
     * Captures the full DOM snapshot (nodes + layout + text). Computed styles
     * omitted for token economy; layout gives bounding boxes for marking.
     */
    suspend fun captureDomSnapshot(): JSONObject =
        cdp.call(
            "DOMSnapshot.captureSnapshot",
            JSONObject()
                .put("computedStyles", JSONArray())
                .put("includeDOMRects", true)
                .put("includePaintOrder", false),
        )

    // ---------------------------------------------------------------- Input
    // All dispatches here are trusted browser input (isTrusted: true).

    /** Realistic tap at viewport coordinates via touch events. */
    suspend fun dispatchTap(x: Int, y: Int, pressDurationMs: Long = 80) {
        cdp.call(
            "Input.dispatchTouchEvent",
            JSONObject()
                .put("type", "touchStart")
                .put("touchPoints", JSONArray().put(
                    JSONObject().put("x", x).put("y", y).put("radiusX", 4).put("radiusY", 4)
                )),
        )
        kotlinx.coroutines.delay(pressDurationMs.coerceIn(50, 120))
        cdp.call(
            "Input.dispatchTouchEvent",
            JSONObject().put("type", "touchEnd").put("touchPoints", JSONArray()),
        )
    }

    /** Mouse-based tap (some pages/tests require mouse semantics). */
    suspend fun dispatchClick(x: Int, y: Int) {
        val base = JSONObject().put("x", x).put("y", y).put("button", "left").put("clickCount", 1)
        cdp.call("Input.dispatchMouseEvent", JSONObject().put("type", "mousePressed").putAll(base))
        kotlinx.coroutines.delay(60)
        cdp.call("Input.dispatchMouseEvent", JSONObject().put("type", "mouseReleased").putAll(base))
    }

    /** Types [text] into the currently focused element via synthesized key events. */
    suspend fun insertText(text: String) {
        cdp.call("Input.insertText", JSONObject().put("text", text))
    }

    suspend fun keyEvent(type: String, key: String, code: Int, windowsVirtualKeyCode: Int) {
        cdp.call(
            "Input.dispatchKeyEvent",
            JSONObject()
                .put("type", type)
                .put("key", key)
                .put("code", "Key${key.uppercase().take(1)}")
                .put("windowsVirtualKeyCode", windowsVirtualKeyCode)
                .put("nativeVirtualKeyCode", windowsVirtualKeyCode),
        )
    }

    suspend fun pressEnter() {
        keyEvent("rawKeyDown", "Enter", 66, 13)
        kotlinx.coroutines.delay(20)
        keyEvent("keyUp", "Enter", 66, 13)
    }

    /** Smooth-ish scroll: sequence of mouse wheel events. */
    suspend fun scroll(direction: String, amount: Int) {
        val dy = if (direction == "up") -amount else amount
        val steps = (amount / 300).coerceIn(1, 6)
        repeat(steps) {
            cdp.call(
                "Input.dispatchMouseEvent",
                JSONObject()
                    .put("type", "mouseWheel")
                    .put("x", 200)
                    .put("y", 400)
                    .put("deltaX", 0)
                    .put("deltaY", dy / steps),
            )
            kotlinx.coroutines.delay(60)
        }
    }

    /** Mouse move (hover). */
    suspend fun mouseMove(x: Int, y: Int) {
        cdp.call(
            "Input.dispatchMouseEvent",
            JSONObject().put("type", "mouseMoved").put("x", x).put("y", y),
        )
    }

    /** Drag via touch sequence with interpolated move points. */
    suspend fun dispatchDrag(fromX: Int, fromY: Int, toX: Int, toY: Int) {
        cdp.call(
            "Input.dispatchTouchEvent",
            JSONObject()
                .put("type", "touchStart")
                .put("touchPoints", JSONArray().put(JSONObject().put("x", fromX).put("y", fromY))),
        )
        val steps = 8
        for (i in 1 until steps) {
            val x = fromX + (toX - fromX) * i / steps
            val y = fromY + (toY - fromY) * i / steps
            cdp.call(
                "Input.dispatchTouchEvent",
                JSONObject()
                    .put("type", "touchMove")
                    .put("touchPoints", JSONArray().put(JSONObject().put("x", x).put("y", y))),
            )
            kotlinx.coroutines.delay(30)
        }
        cdp.call(
            "Input.dispatchTouchEvent",
            JSONObject()
                .put("type", "touchEnd")
                .put("touchPoints", JSONArray().put(JSONObject().put("x", toX).put("y", toY))),
        )
    }

    // ------------------------------------------------------------ Emulation

    suspend fun setUserAgentOverride(userAgent: String, locale: String? = null, timezoneId: String? = null) {
        val params = JSONObject().put("userAgent", userAgent)
        locale?.let { params.put("locale", it) }
        cdp.call("Emulation.setUserAgentOverride", params)
        timezoneId?.let { cdp.call("Emulation.setTimezoneOverride", JSONObject().put("timezoneId", it)) }
    }

    suspend fun setDeviceMetricsOverride(width: Int, height: Int, deviceScaleFactor: Double, mobile: Boolean) {
        cdp.call(
            "Emulation.setDeviceMetricsOverride",
            JSONObject()
                .put("width", width)
                .put("height", height)
                .put("deviceScaleFactor", deviceScaleFactor)
                .put("mobile", mobile),
        )
    }

    // -------------------------------------------------------------- Network

    suspend fun enableNetwork() = cdp.call("Network.enable")

    suspend fun getResponseBody(requestId: String): String? {
        val result = runCatching {
            cdp.call("Network.getResponseBody", JSONObject().put("requestId", requestId))
        }.getOrNull() ?: return null
        return result.optString("body").ifBlank { null }
    }

    // -------------------------------------------------------------- helpers

    /** Center of a DOMSnapshot layout rect [x, y, w, h] as tap coordinates. */
    fun centerOf(rect: JSONArray): Pair<Int, Int> {
        val x = rect.optDouble(0, 0.0) + rect.optDouble(2, 0.0) / 2.0
        val y = rect.optDouble(1, 0.0) + rect.optDouble(3, 0.0) / 2.0
        return x.roundToInt() to y.roundToInt()
    }

    fun close() = cdp.close()
}
