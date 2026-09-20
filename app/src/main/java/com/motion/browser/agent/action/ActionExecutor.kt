package com.motion.browser.agent.action

import com.motion.browser.agent.llm.ToolCallRequest
import com.motion.browser.agent.llm.ToolDef
import com.motion.browser.agent.perception.PageElement
import com.motion.browser.agent.perception.PageState
import com.motion.browser.agent.perception.Perception
import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * The agent's hands (plan Prompt 4): every tool the LLM may call, executed via
 * CDP (trusted input), each verified for effect, rate limited per domain, and
 * audit-logged. Tool schemas are OpenAI-format and handed to the model verbatim.
 */
object ToolSchemas {

    private fun schema(props: Map<String, JSONObject>, required: List<String> = emptyList()): JSONObject =
        JSONObject()
            .put("type", "object")
            .put("properties", JSONObject().apply { props.forEach { (k, v) -> put(k, v) } })
            .put("required", JSONArray(required))

    private val s = { t: String -> JSONObject().put("type", t) }

    val all: List<ToolDef> = listOf(
        ToolDef("navigate", "Open a URL in the current tab", schema(mapOf("url" to s("string")), listOf("url"))),
        ToolDef("back", "Go back in history", schema(emptyMap())),
        ToolDef("wait", "Wait milliseconds", schema(mapOf("ms" to s("integer")), listOf("ms"))),
        ToolDef("wait_for", "Wait until text appears on the page", schema(
            mapOf("text" to s("string"), "timeout" to s("integer")), listOf("text"))),
        ToolDef("click", "Tap an element by markId (trusted input)", schema(
            mapOf("markId" to s("string")), listOf("markId"))),
        ToolDef("type", "Click an input and type text; optionally submit with Enter", schema(
            mapOf("markId" to s("string"), "text" to s("string"), "submit" to s("boolean")),
            listOf("markId", "text"))),
        ToolDef("press", "Press a key on the focused element (Enter, Tab, Escape)", schema(
            mapOf("key" to s("string")), listOf("key"))),
        ToolDef("scroll", "Scroll up/down by amount px", schema(
            mapOf("direction" to s("string"), "amount" to s("integer")), listOf("direction"))),
        ToolDef("hover", "Hover an element", schema(mapOf("markId" to s("string")), listOf("markId"))),
        ToolDef("drag", "Drag from element A to element B", schema(
            mapOf("fromMarkId" to s("string"), "toMarkId" to s("string")), listOf("fromMarkId", "toMarkId"))),
        ToolDef("select_option", "Select an <option> of a select by visible text or value", schema(
            mapOf("markId" to s("string"), "value" to s("string")), listOf("markId", "value"))),
        ToolDef("set_checkbox", "Set a checkbox/radio to a state", schema(
            mapOf("markId" to s("string"), "checked" to s("boolean")), listOf("markId", "checked"))),
        ToolDef("extract", "Read text/attribute of an element (markId) or CSS selector", schema(
            mapOf("markId" to s("string"), "selector" to s("string"), "attribute" to s("string")))),
        ToolDef("read_page", "Re-read the current page structure (fresh marks)", schema(emptyMap())),
        ToolDef("open_tab", "Open a URL in a new tab", schema(mapOf("url" to s("string")), listOf("url"))),
        ToolDef("switch_tab", "Switch to a tab index (0-based)", schema(
            mapOf("index" to s("integer")), listOf("index"))),
        ToolDef("close_tab", "Close a tab index (0-based; current tab by default)", schema(
            mapOf("index" to s("integer")))),
        ToolDef("solve_captcha", "Attempt captcha handling pipeline", schema(emptyMap())),
        ToolDef("notify_user", "Show an Android notification", schema(
            mapOf("message" to s("string"), "level" to s("string")), listOf("message"))),
        ToolDef("ask_human", "Pause and ask the human a question (ALSO used to confirm risky actions)", schema(
            mapOf("question" to s("string")), listOf("question"))),
        ToolDef("finish", "Finish the task with a final summary", schema(
            mapOf("summary" to s("string")), listOf("summary"))),
    )

    /** Risky verbs per plan §6B: submit, send, pay, delete… require ask_human. */
    private val riskyWords = listOf(
        "submit", "pay", "checkout", "buy", "order", "delete", "remove", "send",
        "post", "publish", "comment", "reply", "like", "share", "confirm", "purchase", "sign",
    )

    fun isRiskyClick(element: PageElement?): Boolean {
        element ?: return false
        val haystack = listOf(element.text, element.ariaLabel, element.inputType, element.value)
            .joinToString(" ").lowercase()
        return riskyWords.any { haystack.contains(it) }
    }
}

data class ActionResult(val ok: Boolean, val summary: String, val changed: Boolean = false, val data: String = "")

/**
 * Executes one tool call against the current page. All effects verified via a
 * lightweight page signature (URL + text length + element count) before/after.
 */
class ActionExecutor(
    private val page: suspend () -> PageControl,
    private val currentState: suspend () -> PageState?,
    private val tabOps: TabOps,
    private val rateLimiter: DomainRateLimiter,
    private val audit: suspend (kind: String, label: String, detail: String, ok: Boolean) -> Unit,
    private val approvals: ApprovalGateway,
    private val notifier: Notifier,
    private val captcha: CaptchaPipeline,
    private val requireConfirmRisky: Boolean,
    private val taskId: String,
) {
    /** Tab surface the agent may manipulate (open/switch/close). */
    interface TabOps {
        suspend fun openTab(url: String): Int
        suspend fun switchTo(index: Int): Boolean
        suspend fun closeTab(index: Int?): Boolean
        suspend fun currentTabIndex(): Int
    }

    private fun hostOf(url: String): String = runCatching { URI(url).host ?: "local" }.getOrDefault("local")

    suspend fun execute(call: ToolCallRequest): ActionResult {
        val args = runCatching { JSONObject(call.argumentsJson) }.getOrElse { JSONObject() }
        val state = currentState()
        val host = state?.url?.let { hostOf(it) } ?: "local"
        return try {
            rateLimiter.await(host)
            val result = dispatch(call.name, args, state)
            audit("action", call.name, args.toString().take(600), result.ok)
            result
        } catch (e: Exception) {
            audit("action", call.name, "ERROR ${e.message}".take(600), false)
            ActionResult(false, "tool ${call.name} failed: ${e.message}")
        }
    }

    private suspend fun dispatch(name: String, args: JSONObject, state: PageState?): ActionResult {
        return when (name) {
        "navigate" -> {
            val url = args.getString("url")
            page().navigate(url)
            ActionResult(true, "navigated to $url")
        }
        "back" -> { page().goBack(); ActionResult(true, "went back") }
        "wait" -> { kotlinx.coroutines.delay(args.optLong("ms", 1000).coerceIn(50, 30_000)); ActionResult(true, "waited") }
        "wait_for" -> waitForText(args.getString("text"), args.optLong("timeout", 10_000))
        "click" -> click(args.getString("markId"))
        "type" -> type(args.getString("markId"), args.getString("text"), args.optBoolean("submit", false))
        "press" -> press(args.optString("key", "Enter"))
        "scroll" -> {
            val dir = args.optString("direction", "down")
            page().scroll(dir, args.optInt("amount", 600))
            ActionResult(true, "scrolled $dir")
        }
        "hover" -> {
            val el = elementFor(args.getString("markId"), state)
            el ?: return ActionResult(false, "unknown markId ${args.getString("markId")}")
            page().hover(el.x + el.width / 2, el.y + el.height / 2)
            ActionResult(true, "hovered ${el.markId}")
        }
        "drag" -> {
            val a = elementFor(args.getString("fromMarkId"), state)
            val b = elementFor(args.getString("toMarkId"), state)
            if (a == null || b == null) ActionResult(false, "unknown markId for drag")
            else {
                page().drag(a.x + a.width / 2, a.y + a.height / 2, b.x + b.width / 2, b.y + b.height / 2)
                ActionResult(true, "dragged ${a.markId} → ${b.markId}")
            }
        }
        "select_option" -> selectOption(args.getString("markId"), args.getString("value"), state)
        "set_checkbox" -> setCheckbox(args.getString("markId"), args.optBoolean("checked", true), state)
        "extract" -> extract(args, state)
        "read_page" -> ActionResult(true, "page re-read (next perceive delivers fresh state)")
        "open_tab" -> {
            val index = tabOps.openTab(args.getString("url"))
            ActionResult(true, "opened tab #$index")
        }
        "switch_tab" -> {
            val ok = tabOps.switchTo(args.optInt("index", 0))
            ActionResult(ok, if (ok) "switched tab" else "tab index out of range")
        }
        "close_tab" -> {
            val idx = if (args.has("index")) args.optInt("index") else null
            val ok = tabOps.closeTab(idx)
            ActionResult(ok, if (ok) "closed tab" else "could not close tab")
        }
        "solve_captcha" -> {
            val solved = captcha.solve(taskId, page())
            ActionResult(solved, if (solved) "captcha handled" else "captcha needs human — handed off")
        }
        "notify_user" -> {
            notifier.notify(args.optString("level", "info"), "Motion Browser agent", args.getString("message"))
            ActionResult(true, "notified user")
        }
        "ask_human" -> {
            val allowed = approvals.requestApproval(taskId, args.getString("question"), "")
            audit("human", "ask_human", args.optString("question", ""), allowed)
            ActionResult(allowed, if (allowed) "human approved" else "human denied", changed = true)
        }
        "finish" -> ActionResult(true, args.getString("summary"), changed = true)
        else -> ActionResult(false, "unknown tool $name")
        }
    }

    // ------------------------------------------------------------- internals

    private suspend fun waitForText(text: String, timeoutMs: Long): ActionResult {
        val deadline = System.currentTimeMillis() + timeoutMs.coerceIn(500, 60_000)
        while (System.currentTimeMillis() < deadline) {
            val found = page().evaluate(
                "document.body ? document.body.innerText.includes(${jsonString(text)}) : false"
            ).optBoolean("value", false)
            if (found) return ActionResult(true, "found \"$text\"", changed = true)
            kotlinx.coroutines.delay(250)
        }
        return ActionResult(false, "\"$text\" not found within ${timeoutMs}ms")
    }

    private suspend fun elementFor(markId: String, state: PageState?): PageElement? {
        state?.elements?.firstOrNull { it.markId == markId }?.let { return it }
        // Mark may be stale (scroll changed list) — refresh once.
        val fresh = currentState() ?: return null
        return fresh.elements.firstOrNull { it.markId == markId }
    }

    private suspend fun click(markId: String): ActionResult {
        val state = currentState()
        val el = elementFor(markId, state) ?: return ActionResult(false, "unknown markId $markId")
        if (el.disabled) return ActionResult(false, "$markId is disabled")
        if (requireConfirmRisky && ToolSchemas.isRiskyClick(el)) {
            val allowed = approvals.requestApproval(
                taskId,
                "Allow ${el.tag.uppercase()} \"${(el.text.ifBlank { el.ariaLabel }).take(60)}\"?",
                "risky action on ${state?.url.orEmpty()}",
            )
            audit("human", "risk-approval", "$markId ${el.text.take(60)}", allowed)
            if (!allowed) return ActionResult(false, "user denied risky action on $markId")
        }
        val p = page()
        val before = signature()
        // Fresh coordinates: the element may have scrolled since perception.
        val rect = freshRect(markId) ?: (el.x to el.y)
        p.tap(rect.first + (el.width / 2).coerceAtLeast(1), rect.second + (el.height / 2).coerceAtLeast(1))
        kotlinx.coroutines.delay(250)
        val after = signature()
        return ActionResult(true, "clicked $markId (${(el.text.ifBlank { el.ariaLabel }).take(40)})", changed = before != after)
    }

    private suspend fun type(markId: String, text: String, submit: Boolean): ActionResult {
        val state = currentState()
        val el = elementFor(markId, state) ?: return ActionResult(false, "unknown markId $markId")
        val editable = el.tag in listOf("input", "textarea") || el.contentEditable
        if (!editable) return ActionResult(false, "$markId is not an input")
        if (el.disabled) return ActionResult(false, "$markId is disabled")
        val p = page()
        // Verify-and-retry (plan Prompt 4): tap→focus is asynchronous, so the
        // first insertText can land nowhere. Verify the field value, clear via
        // Backspace key events, and retry up to 3 times with growing delays.
        repeat(3) { attempt ->
            val rect = freshRect(markId) ?: (el.x to el.y)
            p.tap(rect.first + el.width / 2, rect.second + el.height / 2)
            kotlinx.coroutines.delay(300 + attempt * 250L)
            p.insertText(text)
            kotlinx.coroutines.delay(300)
            val current = currentValue(markId)
            if (current.contains(text)) {
                if (submit) p.pressEnter()
                kotlinx.coroutines.delay(250)
                return ActionResult(true, "typed into $markId" + if (submit) " and submitted" else "")
            }
            clearField(markId, current.length)
        }
        return ActionResult(false, "could not type into $markId after 3 attempts")
    }

    private suspend fun currentValue(markId: String): String = runCatching {
        page().evaluate(
            "(()=>{const el=document.querySelector('[${Perception.MARK_ATTR}=\"$markId\"]');" +
                "return el?(el.value!==undefined?String(el.value):''):''})()"
        ).optString("value")
    }.getOrDefault("")

    private suspend fun clearField(markId: String, length: Int) {
        val p = page()
        repeat(length.coerceAtMost(80)) {
            p.keyPressBackspace()
        }
    }

    private suspend fun press(key: String): ActionResult {
        when (key.lowercase()) {
            "enter" -> page().pressEnter()
            "tab" -> page().evaluate("(()=>{const f=document.querySelector('input,select,textarea,[tabindex]');if(f)f.focus();})()")
            "escape" -> Unit
            else -> Unit
        }
        return ActionResult(true, "pressed $key")
    }

    private suspend fun selectOption(markId: String, value: String, state: PageState?): ActionResult {
        elementFor(markId, state) ?: return ActionResult(false, "unknown markId $markId")
        val ok = page().evaluate(
            """
            (() => {
              const el = document.querySelector('[${com.motion.browser.agent.perception.Perception.MARK_ATTR}="$markId"]');
              if (!el || el.tagName !== 'SELECT') return false;
              const opts = Array.from(el.options);
              const target = opts.find(o => o.value === ${jsonString(value)} || o.text.trim() === ${jsonString(value)});
              if (!target) return false;
              el.value = target.value;
              el.dispatchEvent(new Event('change', {bubbles: true}));
              return true;
            })()
            """.trimIndent()
        ).optBoolean("value", false)
        return ActionResult(ok, if (ok) "selected \"$value\" on $markId" else "option \"$value\" not found")
    }

    private suspend fun setCheckbox(markId: String, checked: Boolean, state: PageState?): ActionResult {
        val el = elementFor(markId, state) ?: return ActionResult(false, "unknown markId $markId")
        val p = page()
        val current = p.evaluate(
            "(()=>{const el=document.querySelector('[${Perception.MARK_ATTR}=\"$markId\"]');return el?(el.checked===true):false})()"
        ).optBoolean("value", false)
        if (current != checked) {
            val rect = freshRect(markId) ?: (el.x to el.y)
            p.tap(rect.first + el.width / 2, rect.second + el.height / 2)
            kotlinx.coroutines.delay(150)
        }
        return ActionResult(true, "checkbox $markId → $checked")
    }

    private suspend fun extract(args: JSONObject, state: PageState?): ActionResult {
        val attribute = args.optString("attribute", "text").ifBlank { "text" }
        val markId = args.optString("markId", "")
        val selector = args.optString("selector", "")
        val expr = if (markId.isNotBlank()) {
            val el = elementFor(markId, state)
            el ?: return ActionResult(false, "unknown markId $markId")
            val attrJs = if (attribute == "text") "el.innerText" else "el.getAttribute('$attribute')"
            "(()=>{const el=document.querySelector('[${Perception.MARK_ATTR}=\"$markId\"]');return el?String($attrJs).slice(0,4000):''})()"
        } else if (selector.isNotBlank()) {
            val attrJs = if (attribute == "text") "el.innerText" else "el.getAttribute('$attribute')"
            "(()=>{const el=document.querySelector(${jsonString(selector)});return el?String($attrJs).slice(0,4000):''})()"
        } else return ActionResult(false, "extract needs markId or selector")
        val value = page().evaluate(expr).optString("value")
        return ActionResult(value.isNotBlank(), "extracted ${value.length} chars", data = value)
    }

    /** Fresh viewport rect for a mark (post-scroll) or null when gone. */
    private suspend fun freshRect(markId: String): Pair<Int, Int>? {
        val r = page().evaluate(
            "(()=>{const el=document.querySelector('[${Perception.MARK_ATTR}=\"$markId\"]');" +
                "if(!el)return null;const r=el.getBoundingClientRect();" +
                "return JSON.stringify([Math.round(r.x),Math.round(r.y)]);})()"
        ).optString("value")
        if (r.isBlank() || r == "null") return null
        val arr = runCatching { JSONArray(r) }.getOrNull() ?: return null
        return arr.optInt(0, -1) to arr.optInt(1, -1)
    }

    /** Cheap page signature for effect verification (plan Prompt 4). */
    private suspend fun signature(): String = runCatching {
        page().evaluate(
            "JSON.stringify({u:location.href,l:(document.body?document.body.innerText.length:0),n:document.querySelectorAll('*').length})"
        ).optString("value")
    }.getOrDefault("")

    private fun jsonString(value: String): String = JSONObject.quote(value)
}
