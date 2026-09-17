package com.motion.browser.security

import com.motion.browser.data.dao.PermissionDao
import com.motion.browser.data.entity.PermissionEntity
import kotlinx.coroutines.flow.Flow

/**
 * Per-domain permission evaluation (defaults per spec §25).
 *
 * Default matrix (no user rule present):
 *  - READ / NAVIGATE / NOTIFY            → LOW, allowed, no approval.
 *  - FILL_FORM / DOWNLOAD / UPLOAD       → CONFIGURABLE: a user rule overrides; otherwise
 *                                          allowed but requiresApproval = true.
 *  - SUBMIT                              → HIGH: always requires user approval (spec §25/§29);
 *                                          a rule with submit=false denies outright.
 *
 * A rule with read=false marks the whole domain BLOCKED — every agent action on it is denied.
 * Goal-level allowedDomains/blockedDomains lists are enforced by the agent runtime (agent 2-c),
 * on top of this layer.
 *
 * Contract (ARCHITECTURE.md §3.5) — exact signatures:
 *   class PermissionManager(private val dao: PermissionDao) {
 *       suspend fun evaluate(domain: String, action: ToolAction): ActionVerdict
 *       suspend fun setRule(rule: PermissionEntity)
 *       fun rules(): Flow<List<PermissionEntity>>
 *   }
 */
class PermissionManager(private val dao: PermissionDao) {

    suspend fun evaluate(domain: String, action: ToolAction): ActionVerdict {
        val rule = dao.forDomain(domain)
        if (rule != null && !rule.read) {
            return ActionVerdict(
                allowed = false,
                requiresApproval = false,
                risk = Risk.HIGH,
                reason = "Domain '$domain' is blocked by user permission rules (§25): the rule sets read=false, which blocks all agent actions on this site."
            )
        }
        return when (action) {
            ToolAction.READ -> ActionVerdict(
                true, false, Risk.LOW,
                "READ is allowed by default (§25); no approval needed."
            )
            ToolAction.NAVIGATE -> byRule(rule?.navigate, "NAVIGATE")
            ToolAction.NOTIFY -> ActionVerdict(
                true, false, Risk.LOW,
                "NOTIFY (local notification) is allowed by default (§25)."
            )
            ToolAction.FILL_FORM -> byRule(rule?.fillForms, "FILL_FORM")
            ToolAction.DOWNLOAD -> byRule(rule?.download, "DOWNLOAD")
            ToolAction.UPLOAD -> byRule(rule?.upload, "UPLOAD")
            ToolAction.SUBMIT -> if (rule?.submit == false) {
                ActionVerdict(
                    false, false, Risk.HIGH,
                    "SUBMIT is denied by the user permission rule for '$domain' (§25)."
                )
            } else {
                ActionVerdict(
                    true, true, Risk.HIGH,
                    "SUBMIT always requires explicit user approval (§25/§29) — never auto-submitted."
                )
            }
        }
    }

    /** Insert or overwrite the rule for the rule's domain. */
    suspend fun setRule(rule: PermissionEntity) {
        dao.upsert(rule)
    }

    /** Observe all permission rules (for the control-center UI). */
    fun rules(): Flow<List<PermissionEntity>> = dao.all()

    private fun byRule(flag: Boolean?, action: String): ActionVerdict = when (flag) {
        true -> ActionVerdict(true, false, Risk.CONFIGURABLE, "$action allowed by the user permission rule for this domain (§25).")
        false -> ActionVerdict(false, false, Risk.CONFIGURABLE, "$action denied by the user permission rule for this domain (§25).")
        null -> ActionVerdict(true, true, Risk.CONFIGURABLE, "$action defaults to approval-required when no user rule exists (§25).")
    }
}
