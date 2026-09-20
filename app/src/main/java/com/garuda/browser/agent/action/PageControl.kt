package com.garuda.browser.agent.action

import com.garuda.cdp.CdpTabSession
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * The slice of CDP the action layer needs, extracted as an interface so the
 * ActionExecutor is unit-testable with fakes (plan Prompt 4 acceptance:
 * "unit test mock CDP untuk tiap tool"). Production adapter = CdpTabSession.
 */
interface PageControl {
    suspend fun evaluate(expression: String): JSONObject
    suspend fun navigate(url: String)
    suspend fun goBack()
    suspend fun screenshotPng(): ByteArray
    suspend fun tap(x: Int, y: Int, pressDurationMs: Long = 80)
    suspend fun insertText(text: String)
    suspend fun pressEnter()
    suspend fun keyPressBackspace()
    suspend fun scroll(direction: String, amount: Int)
    suspend fun hover(x: Int, y: Int)
    suspend fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int)
}

/** Production adapter over a live [CdpTabSession]. */
class CdpPageControl(private val session: CdpTabSession) : PageControl {
    override suspend fun evaluate(expression: String): JSONObject = session.evaluate(expression)
    override suspend fun navigate(url: String) { session.navigate(url) }
    override suspend fun goBack() { session.evaluate("history.back()") }
    override suspend fun screenshotPng(): ByteArray = session.captureScreenshotPng()
    override suspend fun tap(x: Int, y: Int, pressDurationMs: Long) = session.dispatchTap(x, y, pressDurationMs)
    override suspend fun insertText(text: String) = session.insertText(text)
    override suspend fun pressEnter() = session.pressEnter()
    override suspend fun keyPressBackspace() = session.pressBackspace()
    override suspend fun scroll(direction: String, amount: Int) = session.scroll(direction, amount)
    override suspend fun hover(x: Int, y: Int) = session.mouseMove(x, y)
    override suspend fun drag(fromX: Int, fromY: Int, toX: Int, toY: Int) =
        session.dispatchDrag(fromX, fromY, toX, toY)
}

/** Per-domain rate limiting: minimum interval between agent actions per host. */
class DomainRateLimiter(private val minIntervalMs: Long) {
    private val lastActionAt = ConcurrentHashMap<String, Long>()

    suspend fun await(host: String) {
        if (minIntervalMs <= 0) return
        while (true) {
            val now = System.currentTimeMillis()
            val last = lastActionAt[host] ?: 0
            val elapsed = now - last
            if (elapsed >= minIntervalMs) {
                lastActionAt[host] = now
                return
            }
            kotlinx.coroutines.delay(minIntervalMs - elapsed)
        }
    }
}

/** Pause-the-task question to the user (plan Prompt 4: ask_human). */
fun interface ApprovalGateway {
    /** Returns true when the user allowed the action. Must suspend until answered. */
    suspend fun requestApproval(taskId: String, question: String, detail: String): Boolean
}

/** Android notification bridge (plan Prompt 4: notify_user). */
fun interface Notifier {
    fun notify(level: String, title: String, message: String)
}

/**
 * Captcha strategy pipeline (plan Prompt 8 §1 — abstract here, adapters later):
 * stage (a) vision click via SoM, (b) external solver API, (c) human handoff.
 */
interface CaptchaPipeline {
    /** Returns true when the captcha present on the current page was handled. */
    suspend fun solve(taskId: String, page: PageControl): Boolean
}

/** Default pipeline: honest stub — hands off to a human via notification. */
class HumanHandoffCaptchaPipeline(private val notifier: Notifier) : CaptchaPipeline {
    override suspend fun solve(taskId: String, page: PageControl): Boolean {
        notifier.notify("warn", "Captcha detected", "Task $taskId needs manual solving — open Garuda")
        return false
    }
}
