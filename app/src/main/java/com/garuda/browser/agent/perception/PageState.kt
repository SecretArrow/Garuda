package com.garuda.browser.agent.perception

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.garuda.browser.agent.action.PageControl
import org.json.JSONArray
import org.json.JSONObject

/**
 * How the agent "sees" a page (plan Prompt 3). Primary source is a single
 * Runtime.evaluate crawl (precise bboxes + values in one round trip);
 * [MARK_ATTR] marks are written into the live DOM so ids e1..eN stay STABLE
 * across steps (the JS-crawl equivalent of matching by backendNodeId).
 */
data class PageElement(
    val markId: String,
    val tag: String,
    val text: String,
    val placeholder: String,
    val ariaLabel: String,
    val value: String,
    val disabled: Boolean,
    val inputType: String,
    val role: String,
    val href: String,
    val contentEditable: Boolean,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int,
) {
    fun isInteractable(): Boolean = !disabled
}

data class PageState(
    val url: String,
    val title: String,
    val viewportWidth: Int,
    val viewportHeight: Int,
    val elements: List<PageElement>,
    val formSummary: String,
    val textDigest: String,
) {
    /** Token-economical serialization (plan Prompt 3 §4). */
    fun serialize(maxChars: Int = 12_000): String {
        val sb = StringBuilder()
        sb.append("URL: ").append(url).append('\n')
        sb.append("TITLE: ").append(title).append('\n')
        sb.append("VIEWPORT: ${viewportWidth}x${viewportHeight}\n")
        if (formSummary.isNotBlank()) sb.append("FORMS: ").append(formSummary).append('\n')
        sb.append("ELEMENTS:\n")
        for (e in elements) {
            val bits = mutableListOf(e.markId, e.tag.uppercase())
            if (e.role.isNotBlank()) bits.add("role=${e.role}")
            when (e.tag.lowercase()) {
                "input", "textarea" -> {
                    e.inputType.takeIf { it.isNotBlank() }?.let { bits.add("type=$it") }
                    e.placeholder.takeIf { it.isNotBlank() }?.let { bits.add("ph=\"$it\"") }
                    e.value.takeIf { it.isNotBlank() }?.let { bits.add("val=\"$it\"") }
                }
                "select" -> e.value.takeIf { it.isNotBlank() }?.let { bits.add("sel=\"$it\"") }
                "a" -> e.href.takeIf { it.isNotBlank() }?.let { bits.add("href=\"${e.href.take(80)}\"") }
            }
            e.ariaLabel.takeIf { it.isNotBlank() }?.let { bits.add("aria=\"$it\"") }
            e.text.takeIf { it.isNotBlank() }?.let { bits.add("\"${it.take(60)}\"") }
            if (e.disabled) bits.add("[disabled]")
            if (e.contentEditable) bits.add("[editable]")
            bits.add("@${e.x},${e.y},${e.width}x${e.height}")
            sb.append(bits.joinToString(" ")).append('\n')
            if (sb.length > maxChars) break
        }
        if (textDigest.isNotBlank()) {
            sb.append("PAGE TEXT DIGEST:\n").append(textDigest.take(2000))
        }
        return sb.toString()
    }
}

object Perception {

    const val MARK_ATTR = "data-garuda-mark"
    private const val MAX_ELEMENTS = 150

    /**
     * Crawl JS: assigns stable [MARK_ATTR] ids to interactive elements, then
     * returns a compact JSON of the page. Existing marks are REUSED, so the
     * LLM's references remain valid between steps.
     */
    private val crawlJs = """
        (() => {
          const MARK = '$MARK_ATTR';
          const sel = 'a,button,input,select,textarea,[role=button],[role=link],[role=checkbox],[role=radio],[role=tab],[role=menuitem],[onclick],[contenteditable]';
          const els = Array.from(document.querySelectorAll(sel));
          let counter = 0;
          const seen = new Set();
          const out = [];
          const vw = window.innerWidth, vh = window.innerHeight;
          for (const el of els) {
            if (out.length >= $MAX_ELEMENTS) break;
            let mark = el.getAttribute(MARK);
            if (!mark) {
              counter = out.length + 1;
              mark = 'e' + counter;
              try { el.setAttribute(MARK, mark); } catch (e) {}
            }
            if (seen.has(mark)) continue;
            const r = el.getBoundingClientRect();
            if (!r || r.width <= 0 || r.height <= 0) continue;
            if (r.bottom < -50 || r.top > vh + 50) continue; // far off-viewport: skip
            const st = getComputedStyle(el);
            if (st.visibility === 'hidden' || st.display === 'none') continue;
            const tag = el.tagName.toLowerCase();
            const txt = (el.innerText || el.value || '').replace(/\s+/g, ' ').trim();
            out.push({
              m: mark, tag: tag,
              t: txt.slice(0, 80),
              ph: el.placeholder || '',
              al: (el.getAttribute('aria-label') || el.title || ''),
              v: (el.value !== undefined ? String(el.value).slice(0, 60) : ''),
              dis: !!el.disabled,
              ty: (el.type || ''),
              ro: (el.getAttribute('role') || ''),
              hr: (tag === 'a' ? (el.href || '') : ''),
              ce: (el.isContentEditable || el.getAttribute('contenteditable') === 'true'),
              r: [Math.round(r.x), Math.round(r.y), Math.round(r.width), Math.round(r.height)]
            });
          }
          const forms = Array.from(document.querySelectorAll('form')).map(f => {
            const fields = Array.from(f.querySelectorAll('input,select,textarea'))
              .map(i => (i.type || i.tagName.toLowerCase()) + (i.required ? '*' : ''))
              .join(',');
            return fields.slice(0, 200);
          }).filter(Boolean);
          const digest = (document.body ? document.body.innerText : '').replace(/\s+/g, ' ').trim();
          return JSON.stringify({
            url: location.href,
            title: document.title,
            vw: vw, vh: vh,
            els: out,
            forms: forms.slice(0, 5),
            digest: digest.slice(0, 2200)
          });
        })()
    """.trimIndent()

    /** Observes the page in [session] and returns the marked [PageState]. */
    suspend fun observe(session: PageControl): PageState {
        val result = session.evaluate(crawlJs)
        val json = runCatching { JSONObject(result.optString("value", "{}")) }
            .getOrElse { JSONObject() }
        val elsJson = json.optJSONArray("els") ?: JSONArray()
        val elements = (0 until elsJson.length()).mapNotNull { i ->
            val e = elsJson.optJSONObject(i) ?: return@mapNotNull null
            val r = e.optJSONArray("r") ?: return@mapNotNull null
            PageElement(
                markId = e.optString("m"),
                tag = e.optString("tag"),
                text = e.optString("t"),
                placeholder = e.optString("ph"),
                ariaLabel = e.optString("al"),
                value = e.optString("v"),
                disabled = e.optBoolean("dis"),
                inputType = e.optString("ty"),
                role = e.optString("ro"),
                href = e.optString("hr"),
                contentEditable = e.optBoolean("ce"),
                x = r.optInt(0), y = r.optInt(1),
                width = r.optInt(2), height = r.optInt(3),
            )
        }
        return PageState(
            url = json.optString("url"),
            title = json.optString("title"),
            viewportWidth = json.optInt("vw"),
            viewportHeight = json.optInt("vh"),
            elements = elements,
            formSummary = (json.optJSONArray("forms") ?: JSONArray()).let { forms ->
                (0 until forms.length()).joinToString(" | ") { forms.optString(it) }
            },
            textDigest = json.optString("digest"),
        )
    }

    /** Refreshes one element's on-screen rect (after scroll) by re-running the crawl. */
    suspend fun reobserve(session: PageControl): PageState = observe(session)
}

/**
 * Set-of-Mark renderer (plan Prompt 3 §3): draws numbered boxes over the raw
 * screenshot so vision models can reference elements.
 */
object SoMRenderer {

    fun render(screenshot: Bitmap, elements: List<PageElement>): Bitmap {
        val bitmap = screenshot.copy(Bitmap.Config.ARGB_8888, true)
        val canvas = Canvas(bitmap)
        val boxPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 2.5f
            color = Color.rgb(255, 82, 82)
        }
        val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(235, 244, 67, 54) }
        val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 26f
            isFakeBoldText = true
        }
        for (e in elements) {
            if (e.width <= 0 || e.height <= 0) continue
            canvas.drawRect(
                e.x.toFloat(), e.y.toFloat(),
                (e.x + e.width).toFloat(), (e.y + e.height).toFloat(),
                boxPaint,
            )
            val label = e.markId
            val tw = labelPaint.measureText(label)
            val lx = e.x.toFloat()
            val ly = (e.y - 30).coerceAtLeast(0).toFloat()
            canvas.drawRect(lx, ly, lx + tw + 12, ly + 30, labelBg)
            canvas.drawText(label, lx + 6, ly + 22, labelPaint)
        }
        return bitmap
    }
}
