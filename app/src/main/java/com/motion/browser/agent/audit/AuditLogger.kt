package com.motion.browser.agent.audit

import com.motion.browser.data.dao.EventDao
import com.motion.browser.data.entity.EventEntity
import kotlinx.coroutines.flow.Flow

/**
 * Structured audit log over the Room `events` table (spec §63: every agent decision is logged;
 * secrets are NEVER written here — callers must redact first, e.g. via
 * PromptInjectionDefense.redactSecrets).
 *
 * Contract (ARCHITECTURE.md §3.5) — exact signatures:
 *   class AuditLogger(private val dao: EventDao) {
 *       suspend fun log(category: String, message: String, detail: String? = null)
 *       fun recent(limit: Int = 200): Flow<List<EventEntity>>
 *   }
 */
class AuditLogger(private val dao: EventDao) {

    /**
     * Append one event. Unknown categories fall back to AUTOMATION (validated set below) so the
     * LogsScreen filter list (§63) stays exhaustive. Messages/details are control-char-sanitized
     * and length-capped to keep the table lean.
     */
    suspend fun log(category: String, message: String, detail: String? = null) {
        val cat = if (category in CATEGORIES) category else FALLBACK_CATEGORY
        val safeMessage = sanitize(message).take(MAX_MESSAGE_CHARS)
        val safeDetail = detail?.let { sanitize(it).take(MAX_DETAIL_CHARS) }
        dao.insert(
            EventEntity(
                id = 0L,
                category = cat,
                message = if (cat == FALLBACK_CATEGORY && category != FALLBACK_CATEGORY) {
                    "$safeMessage [category '$category' normalized]"
                } else {
                    safeMessage
                },
                detail = safeDetail,
                at = System.currentTimeMillis()
            )
        )
    }

    /** Newest-first flow of recent events (bounded by [limit]). */
    fun recent(limit: Int = 200): Flow<List<EventEntity>> = dao.recent(limit)

    private fun sanitize(text: String): String = text.replace(CONTROL_CHARS, " ")

    companion object {
        /** Valid event categories — must match EventEntity category contract (§3.4). */
        val CATEGORIES: Set<String> = setOf(
            "MOTION_BROWSER", "MOTION_AI", "AGENT", "AUTOMATION",
            "SECURITY", "NETWORK", "DOWNLOAD", "ERROR"
        )
        private const val FALLBACK_CATEGORY = "AUTOMATION"
        private const val MAX_MESSAGE_CHARS = 500
        private const val MAX_DETAIL_CHARS = 4_000
        private val CONTROL_CHARS = Regex("[\\p{Cntrl}&&[^\r\n\t]]")
    }
}
