package com.motion.browser.security

/**
 * Core security types for the Motion Browser agent toolchain (ARCHITECTURE.md §3.5).
 * These are the exact contract types other modules (2-c runtime, coordinator) compile against —
 * do not rename or reorder.
 */

/** Risk-classified browser operations an agent tool can perform. */
enum class ToolAction {
    READ,
    NAVIGATE,
    FILL_FORM,
    SUBMIT,
    DOWNLOAD,
    UPLOAD,
    NOTIFY
}

/** Risk level per spec §25: LOW = allowed by default, CONFIGURABLE = user rule or approval, HIGH = approval always. */
enum class Risk {
    LOW,
    CONFIGURABLE,
    HIGH
}

/**
 * Verdict of [PermissionManager.evaluate] for a (domain, action) pair.
 * @param allowed        false = the action is denied outright (domain blocked or rule denies).
 * @param requiresApproval true = execution must go through the ApprovalQueue before running.
 * @param risk           risk classification that produced this verdict.
 * @param reason         human-readable explanation (safe to show in UI / logs — never contains secrets).
 */
data class ActionVerdict(
    val allowed: Boolean,
    val requiresApproval: Boolean,
    val risk: Risk,
    val reason: String
)

/**
 * Verdict of [SafetyGuard.preAction] — the single gate every tool execution passes through.
 * @param allowed          false = deny; [reason] cites the violated policy section.
 * @param requiresApproval true = caller must request user approval (ApprovalQueue) before executing.
 * @param reason           human-readable explanation (policy citation, loop/rate-limit message).
 */
data class GuardVerdict(
    val allowed: Boolean,
    val requiresApproval: Boolean,
    val reason: String
)
