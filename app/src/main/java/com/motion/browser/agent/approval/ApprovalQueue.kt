package com.motion.browser.agent.approval

import com.motion.browser.agent.notify.MotionNotifier
import com.motion.browser.data.dao.ApprovalDao
import com.motion.browser.data.entity.ApprovalEntity
import java.util.UUID
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow

/**
 * Approval queue for high-risk agent actions (spec §25/§30/§31).
 *
 * Lifecycle: request() inserts a PENDING row + fires the high-importance notification → the user
 * resolves it from the UI (resolve) → the waiting executor observes the status via awaitResolution
 * (poll every 2 s until status != PENDING or timeout). Stale PENDING rows (older than 24 h) are
 * marked EXPIRED by expireStale — called by the ToolRegistry before each new approval request.
 *
 * Contract (ARCHITECTURE.md §3.5) — exact signatures:
 *   class ApprovalQueue(private val dao: ApprovalDao,
 *                       private val notifier: MotionNotifier) {
 *       suspend fun request(runId: String?, domain: String, action: String, argsJson: String): String
 *       fun pending(): Flow<List<ApprovalEntity>>
 *       suspend fun resolve(id: String, approved: Boolean, editedArgsJson: String? = null)
 *       suspend fun awaitResolution(id: String, timeoutMs: Long = 3_600_000): ApprovalEntity?
 *   }
 */
class ApprovalQueue(
    private val dao: ApprovalDao,
    private val notifier: MotionNotifier
) {

    /** Insert a PENDING approval, notify the user, return the approval id. */
    suspend fun request(runId: String?, domain: String, action: String, argsJson: String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insert(
            ApprovalEntity(
                id = id,
                runId = runId,
                domain = domain,
                action = action,
                argsJson = argsJson,
                status = STATUS_PENDING,
                createdAt = now,
                resolvedAt = null
            )
        )
        // The notification itself must never break the agent loop.
        runCatching { notifier.notifyApprovalNeeded(id, domain, action) }
        return id
    }

    /** Live flow of unresolved approvals (for the approval UI). */
    fun pending(): Flow<List<ApprovalEntity>> = dao.pending()

    /**
     * Record the user's decision. When approved with [editedArgsJson], the (possibly corrected)
     * args are persisted first so the executor picks them up.
     */
    suspend fun resolve(id: String, approved: Boolean, editedArgsJson: String? = null) {
        val approval = dao.getById(id) ?: return
        if (editedArgsJson != null && editedArgsJson != approval.argsJson) {
            dao.updateArgs(id, editedArgsJson)
        }
        dao.updateStatus(
            id = id,
            status = if (approved) STATUS_APPROVED else STATUS_REJECTED,
            resolvedAt = System.currentTimeMillis()
        )
    }

    /**
     * Poll every [POLL_INTERVAL_MS] until the approval leaves PENDING; null on timeout.
     * Never throws on storage hiccups — a temporary dao failure simply means "keep waiting".
     */
    suspend fun awaitResolution(id: String, timeoutMs: Long = 3_600_000): ApprovalEntity? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val approval = runCatching { dao.getById(id) }.getOrNull()
            if (approval != null && approval.status != STATUS_PENDING) return approval
            delay(POLL_INTERVAL_MS)
        }
        return null
    }

    /** Mark PENDING approvals older than [olderThanMs] as EXPIRED (default 24 h, spec §31). */
    suspend fun expireStale(olderThanMs: Long = DEFAULT_MAX_AGE_MS) {
        val now = System.currentTimeMillis()
        runCatching { dao.expireStale(olderThan = now - olderThanMs, resolvedAt = now) }
    }

    companion object {
        const val STATUS_PENDING = "PENDING"
        const val STATUS_APPROVED = "APPROVED"
        const val STATUS_REJECTED = "REJECTED"
        const val STATUS_EXPIRED = "EXPIRED"

        const val DEFAULT_MAX_AGE_MS: Long = 24L * 60L * 60L * 1000L
        const val POLL_INTERVAL_MS: Long = 2_000L
    }
}
