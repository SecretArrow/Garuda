package com.motion.browser.security

/**
 * SafetyGuard — the single gate every agent tool execution passes through (spec §25/§40/§49).
 *
 * Denial order (fail-closed, cheapest & most security-critical first):
 *  1. Permission/domain check — blocked domain or denied action → denied (reason cites §25).
 *  2. Loop detection — same action signature repeating ≥5 consecutive times → denied (§49).
 *  3. Rate limit — action count for this run reached [maxSteps] → denied (§40).
 *  4. Otherwise allowed; [GuardVerdict.requiresApproval] is taken from the PermissionManager
 *     verdict (CONFIGURABLE defaults and always-SUBMIT require user approval).
 *
 * Contract (ARCHITECTURE.md §3.5) — exact signature:
 *   class SafetyGuard(private val permissions: PermissionManager,
 *                     private val rateLimiter: RateLimiter,
 *                     private val loopDetector: LoopDetector) {
 *       suspend fun preAction(goalId: String?, domain: String, action: ToolAction,
 *                             signature: String, maxSteps: Int): GuardVerdict
 *   }
 */
class SafetyGuard(
    private val permissions: PermissionManager,
    private val rateLimiter: RateLimiter,
    private val loopDetector: LoopDetector
) {

    suspend fun preAction(
        goalId: String?,
        domain: String,
        action: ToolAction,
        signature: String,
        maxSteps: Int
    ): GuardVerdict {
        // 1. Domain/action permissions. A blocked domain (rule.read=false) or a denied
        //    action is an unconditional denial — approval cannot override it.
        val verdict = permissions.evaluate(domain, action)
        if (!verdict.allowed) {
            return GuardVerdict(allowed = false, requiresApproval = false, reason = verdict.reason)
        }

        if (goalId != null) {
            // 2. Loop detection (§49): protecting the site and the device battery.
            if (loopDetector.observe(goalId, signature)) {
                return GuardVerdict(
                    allowed = false,
                    requiresApproval = false,
                    reason = "automation loop detected — task paused to protect the site and battery (§49)"
                )
            }
            // 3. Rate limit (§40): the per-action budget for this run is maxSteps.
            if (rateLimiter.exceeded(goalId, action.name, maxSteps)) {
                return GuardVerdict(
                    allowed = false,
                    requiresApproval = false,
                    reason = "rate limit reached: action '$action' already ran $maxSteps times in this run (§40). " +
                        "Increase the goal's max steps or split the task into smaller goals."
                )
            }
        }

        // 4. Allowed; approval requirement comes from the permission verdict.
        return GuardVerdict(allowed = true, requiresApproval = verdict.requiresApproval, reason = verdict.reason)
    }

    /**
     * Record a completed execution into the rate limiter (called by the ToolRegistry after a
     * tool ran). Additive helper — keeps ONE shared RateLimiter instance between guard and
     * registry. Null goalId (no run context) is not counted.
     */
    fun recordExecution(goalId: String?, action: String) {
        if (goalId != null) rateLimiter.record(goalId, action)
    }
}
