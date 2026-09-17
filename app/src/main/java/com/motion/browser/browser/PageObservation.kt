package com.motion.browser.browser

/**
 * A rectangle in page coordinates (CSS pixels relative to the document origin,
 * i.e. includes page scroll offset). Part of the ARCHITECTURE.md §3.2 contract.
 */
data class Rect(val x: Int, val y: Int, val width: Int, val height: Int)

/** A link extracted from the current page (anchor text + absolute href). */
data class LinkInfo(val text: String, val href: String)

/**
 * An interactive element observed on the page (buttons, links, inputs,
 * textareas, selects, checkboxes, radios, and any element with a [role]
 * attribute). Each observed element is tagged in the live DOM with
 * `data-motion-id` so follow-up actions (click/focus) can address it
 * deterministically.
 *
 * [confidence] is an honest heuristic: 1.0 for visible elements, 0.3 for
 * elements present in the DOM but currently not rendered.
 */
data class ElementInfo(
    val id: String,
    val role: String,
    val text: String,
    val ariaLabel: String?,
    val visible: Boolean,
    val enabled: Boolean,
    val bounds: Rect,
    val confidence: Double
)

/**
 * A full observation of the current page for the agent observe→plan→execute
 * loop (ARCHITECTURE.md §3.2). Page content is UNTRUSTED — consumers must
 * route [visibleText] through PromptInjectionDefense.sanitizePageContent
 * before it reaches any LLM (spec §24).
 */
data class PageObservation(
    val url: String,
    val title: String,
    val visibleText: String,
    val links: List<LinkInfo>,
    val elements: List<ElementInfo>,
    val metadata: Map<String, String>,
    val domSummary: String
)
