package com.motion.browser.browser.engine

/**
 * JavaScript building blocks for the agent bridge (ARCHITECTURE.md §3.2).
 *
 * All scripts return a JSON string so [WebViewEngine.evaluateJs] can hand the
 * result straight to kotlinx-serialization. They never throw into the Java
 * layer: failures are returned as {"error": "..."} objects.
 *
 * SECURITY (spec §24): these scripts only OBSERVE and manipulate the page on
 * behalf of user instructions. They never read cookies, localStorage values
 * for exfiltration, or credentials. The inspection output is UNTRUSTED data.
 */
internal object Scripts {

    /**
     * Full page inspection (one evaluation per observation):
     *  - visibleText  : document.body.innerText (trimmed, capped)
     *  - metadata     : og:title / og:description / canonical / lang
     *  - links        : up to 50 anchors {text, href} (absolute)
     *  - elements     : interactive elements tagged with data-motion-id
     *  - domSummary   : structural counters + landmarks
     */
    const val INSPECT: String = """(function(){
  try {
    var MAX_TEXT = 60000, MAX_LINKS = 50, MAX_ELEM = 80, TEXT_CAP = 120;
    var out = {visibleText:"", metadata:{}, links:[], elements:[], domSummary:""};
    var body = document.body;
    if (body) {
      out.visibleText = (body.innerText || "").replace(/\s+\n/g, "\n").trim().slice(0, MAX_TEXT);
    }
    function meta(name) {
      var el = document.querySelector('meta[name="' + name + '"], meta[property="' + name + '"]');
      return el ? (el.getAttribute("content") || "") : "";
    }
    out.metadata = {
      "og:title": meta("og:title"),
      "og:description": meta("og:description"),
      "canonical": (document.querySelector('link[rel="canonical"]') || {}).href || "",
      "lang": document.documentElement.lang || ""
    };
    var counters = {};
    ["a","button","input","select","textarea","form","table","img","iframe","video"].forEach(function(t){
      counters[t] = document.getElementsByTagName(t).length;
    });
    var landmarks = [];
    ["header","nav","main","footer","aside"].forEach(function(t){
      if (document.getElementsByTagName(t).length > 0) landmarks.push(t);
    });
    out.domSummary = JSON.stringify({counters: counters, landmarks: landmarks, title: document.title});

    var abs = function(href) { try { return new URL(href, location.href).href; } catch (e) { return href || ""; } };
    var anchors = document.querySelectorAll("a[href]");
    for (var i = 0; i < anchors.length && out.links.length < MAX_LINKS; i++) {
      var a = anchors[i];
      var txt = (a.innerText || a.getAttribute("aria-label") || "").replace(/\s+/g, " ").trim().slice(0, TEXT_CAP);
      if (!txt) continue;
      out.links.push({text: txt, href: abs(a.getAttribute("href"))});
    }

    var selector = 'button, [role="button"], a[href], input:not([type="hidden"]), textarea, select, [contenteditable="true"], [contenteditable=""]';
    var nodes = document.querySelectorAll(selector);
    var n = 0;
    for (var j = 0; j < nodes.length && n < MAX_ELEM; j++) {
      var el = nodes[j];
      var rect = el.getBoundingClientRect();
      var visible = rect.width > 0 && rect.height > 0 && (el.offsetParent !== null || rect.top >= 0);
      var role = el.getAttribute("role");
      if (!role) {
        var tagName = el.tagName.toLowerCase();
        role = (tagName === "a") ? "link"
          : (tagName === "button") ? "button"
          : (tagName === "select") ? "select"
          : (tagName === "textarea") ? "textbox"
          : (tagName === "input") ? ({"text":"textbox","email":"textbox","search":"textbox","tel":"textbox","url":"textbox","password":"textbox","number":"textbox","checkbox":"checkbox","radio":"radio","submit":"button","button":"button"}[el.type] || "textbox")
          : (el.isContentEditable ? "textbox" : tagName);
      }
      var text = "";
      if (lowerTag(el) === "input" || lowerTag(el) === "textarea") {
        text = el.value || el.placeholder || "";
      } else {
        text = el.innerText || el.getAttribute("aria-label") || el.getAttribute("value") || "";
      }
      text = String(text).replace(/\s+/g, " ").trim().slice(0, TEXT_CAP);
      var id = "element_" + n;
      try { el.setAttribute("data-motion-id", id); } catch (e) {}
      out.elements.push({
        id: id, role: role, text: text,
        ariaLabel: el.getAttribute("aria-label"),
        visible: visible, enabled: !el.disabled,
        bounds: {x: Math.round(rect.left), y: Math.round(rect.top), width: Math.round(rect.width), height: Math.round(rect.height)},
        confidence: visible ? 1.0 : 0.3
      });
      n++;
    }
    return JSON.stringify(out);
  } catch (e) { return JSON.stringify({error: String(e)}); }
  function lowerTag(el){ return el.tagName.toLowerCase(); }
})()"""

    /** Clicks the element carrying [data-motion-id] and reports whether it existed. */
    fun clickByMotionId(motionId: String): String = """(function(){
  try {
    var el = document.querySelector('[data-motion-id="$motionId"]');
    if (!el) return JSON.stringify({ok:false, error:"element not found: $motionId"});
    el.scrollIntoView({block:"center"});
    el.click();
    return JSON.stringify({ok:true});
  } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
})()"""

    /** CSS-selector click fallback. */
    fun clickBySelector(selector: String): String = """(function(){
  try {
    var el = document.querySelector(${jsString(selector)});
    if (!el) return JSON.stringify({ok:false, error:"selector not found"});
    el.scrollIntoView({block:"center"});
    el.click();
    return JSON.stringify({ok:true});
  } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
})()"""

    /** Clicks the first visible clickable element whose text matches [text] (case-insensitive contains). */
    fun clickByText(text: String): String = """(function(){
  try {
    var want = ${jsString(text)}.toLowerCase();
    var nodes = document.querySelectorAll('button, [role="button"], a[href], input[type="submit"], input[type="button"], [onclick]');
    for (var i = 0; i < nodes.length; i++) {
      var el = nodes[i];
      var t = (el.innerText || el.value || el.getAttribute("aria-label") || "").replace(/\s+/g," ").trim().toLowerCase();
      if (t && t.indexOf(want) !== -1) {
        el.scrollIntoView({block:"center"});
        el.click();
        return JSON.stringify({ok:true, clicked:t});
      }
    }
    return JSON.stringify({ok:false, error:"no clickable element with text: $text"});
  } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
})()"""

    /** Sets an input's value and fires input+change so frameworks (React etc.) notice. */
    fun setInputValue(selector: String, value: String): String = """(function(){
  try {
    var el = document.querySelector(${jsString(selector)});
    if (!el) return JSON.stringify({ok:false, error:"selector not found"});
    el.focus();
    var proto = el.tagName === "TEXTAREA" ? HTMLTextAreaElement.prototype : HTMLInputElement.prototype;
    var setter = Object.getOwnPropertyDescriptor(proto, "value");
    if (setter && setter.set) setter.set.call(el, ${jsString(value)}); else el.value = ${jsString(value)};
    el.dispatchEvent(new Event("input", {bubbles:true}));
    el.dispatchEvent(new Event("change", {bubbles:true}));
    return JSON.stringify({ok:true});
  } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
})()"""

    fun setCheckbox(selector: String, checked: Boolean): String = """(function(){
  try {
    var el = document.querySelector(${jsString(selector)});
    if (!el) return JSON.stringify({ok:false, error:"selector not found"});
    el.checked = $checked;
    el.dispatchEvent(new Event("change", {bubbles:true}));
    el.dispatchEvent(new Event("input", {bubbles:true}));
    return JSON.stringify({ok:true});
  } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
})()"""

    fun selectOption(selector: String, value: String): String = """(function(){
  try {
    var el = document.querySelector(${jsString(selector)});
    if (!el || el.tagName !== "SELECT") return JSON.stringify({ok:false, error:"select not found"});
    var opt = Array.prototype.find.call(el.options, function(o){ return o.value === ${jsString(value)} || o.text === ${jsString(value)}; });
    if (!opt) return JSON.stringify({ok:false, error:"option not found: $value"});
    el.value = opt.value;
    el.dispatchEvent(new Event("change", {bubbles:true}));
    return JSON.stringify({ok:true});
  } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
})()"""

    /** Absolute image URLs (up to 60), following srcset best candidate. */
    const val EXTRACT_IMAGES: String = """(function(){
  try {
    var imgs = document.querySelectorAll("img"), out = [];
    for (var i = 0; i < imgs.length && out.length < 60; i++) {
      var im = imgs[i];
      var src = im.currentSrc || im.src || "";
      if (!src) continue;
      try { src = new URL(src, location.href).href; } catch (e) {}
      out.push(src);
    }
    return JSON.stringify(out);
  } catch (e) { return JSON.stringify({error:String(e)}); }
})()"""

    /** Tables serialized as arrays of row-arrays of cell text (up to 12 tables). */
    const val EXTRACT_TABLES: String = """(function(){
  try {
    var tables = document.querySelectorAll("table"), out = [];
    for (var t = 0; t < tables.length && out.length < 12; t++) {
      var rows = tables[t].querySelectorAll("tr"), table = [];
      var max = Math.min(rows.length, 40);
      for (var r = 0; r < max; r++) {
        var cells = rows[r].querySelectorAll("th,td"), row = [];
        for (var c = 0; c < cells.length && c < 20; c++) row.push((cells[c].innerText||"").replace(/\s+/g," ").trim().slice(0,200));
        table.push(row);
      }
      out.push(table);
    }
    return JSON.stringify(out);
  } catch (e) { return JSON.stringify({error:String(e)}); }
})()"""

    /** Scrolls the first element containing [text] into view. */
    fun scrollToText(text: String): String = """(function(){
  try {
    var want = ${jsString(text)}.toLowerCase();
    var walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT, null, false);
    var node;
    while ((node = walker.nextNode())) {
      if (node.textContent && node.textContent.toLowerCase().indexOf(want) !== -1) {
        node.parentElement.scrollIntoView({block:"center"});
        return JSON.stringify({ok:true});
      }
    }
    return JSON.stringify({ok:false, error:"text not found"});
  } catch (e) { return JSON.stringify({ok:false, error:String(e)}); }
})()"""

    /** Structural DOM summary for inspectDom(). */
    const val DOM_SUMMARY: String = """(function(){
  try {
    var counters = {};
    ["div","span","a","button","input","select","textarea","form","table","img","iframe","video","h1","h2","h3","p"].forEach(function(t){
      counters[t] = document.getElementsByTagName(t).length;
    });
    var landmarks = [];
    ["header","nav","main","footer","aside","article"].forEach(function(t){
      if (document.getElementsByTagName(t).length > 0) landmarks.push(t);
    });
    return JSON.stringify({title: document.title, url: location.href, counters: counters, landmarks: landmarks, readyState: document.readyState});
  } catch (e) { return JSON.stringify({error:String(e)}); }
})()"""

    /** Non-null check used by waitForElement polling. */
    fun elementExists(selector: String): String =
        "!!document.querySelector(" + jsString(selector) + ")"

    /** Escapes a Kotlin/Java string into a JS single-quoted literal. */
    fun jsString(raw: String): String {
        val sb = StringBuilder("'")
        for (ch in raw) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '\'' -> sb.append("\\'")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                '<' -> sb.append("\\u003C")
                else -> if (ch.code < 0x20) sb.append("\\u%04x".format(ch.code)) else sb.append(ch)
            }
        }
        sb.append('\'')
        return sb.toString()
    }
}
