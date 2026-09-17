package com.motion.browser.agent.memory

import com.motion.browser.data.dao.MemoryDao
import com.motion.browser.data.entity.MemoryEntity
import kotlinx.coroutines.flow.Flow
import java.security.MessageDigest

/**
 * Agent memory over the Room `memory` table (ARCHITECTURE.md §3.6, spec §39).
 *
 * Identity: the row id is a stable SHA-256 hash of the composite key
 * "scope|key|goalId|domain", so [put] with the same logical slot is an idempotent upsert.
 */
class MemoryManager(private val dao: MemoryDao) {

    suspend fun put(scope: String, key: String, value: String, goalId: String? = null, domain: String? = null) {
        dao.upsert(
            MemoryEntity(
                id = compositeId(scope, key, goalId, domain),
                scope = scope,
                key = key,
                value = value,
                goalId = goalId,
                domain = domain,
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    suspend fun get(scope: String, key: String, goalId: String? = null, domain: String? = null): String? {
        return dao.getExact(scope, key, goalId, domain)?.value
    }

    fun byGoal(goalId: String): Flow<List<MemoryEntity>> = dao.byGoal(goalId)

    private fun compositeId(scope: String, key: String, goalId: String?, domain: String?): String {
        val raw = "$scope|$key|${goalId ?: "-"}|${domain ?: "-"}"
        val digest = MessageDigest.getInstance("SHA-256").digest(raw.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val SCOPE_GLOBAL = "GLOBAL"
        const val SCOPE_GOAL = "GOAL"
        const val SCOPE_SITE = "SITE"
        const val SCOPE_RUN = "RUN"
        const val SCOPE_TEMP = "TEMP"

        /** Helper scopes used by the browser layer for bookmarks/history (ARCHITECTURE.md §3.2 note). */
        const val SCOPE_BOOKMARK = "BOOKMARK"
        const val SCOPE_HISTORY = "HISTORY"
    }
}
