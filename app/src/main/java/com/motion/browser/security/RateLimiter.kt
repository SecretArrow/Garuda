package com.motion.browser.security

import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory per-goal rate limiter (spec §40 retries/budgets, §49 loop protection).
 *
 * Contract (ARCHITECTURE.md §3.5) — exact signatures:
 *   class RateLimiter {
 *       fun record(goalId: String, action: String)
 *       suspend fun exceeded(goalId: String, action: String, maxPerRun: Int): Boolean
 *   }
 *
 * Counters live in memory only; a process restart resets them (the runtime restarts runs
 * with fresh budgets anyway — state persists in Room, not here).
 */
class RateLimiter {

    private val counts = ConcurrentHashMap<String, MutableMap<String, Int>>()

    /** Record one execution of [action] for [goalId]. */
    fun record(goalId: String, action: String) {
        val perGoal = counts.getOrPut(goalId) { ConcurrentHashMap() }
        perGoal.merge(action, 1, Int::plus)
    }

    /**
     * True when [action] already ran [maxPerRun] or more times for [goalId] in this run
     * (i.e., one more execution would exceed the budget). maxPerRun <= 0 disables the check.
     */
    suspend fun exceeded(goalId: String, action: String, maxPerRun: Int): Boolean {
        if (maxPerRun <= 0) return false
        val current = counts[goalId]?.get(action) ?: 0
        return current >= maxPerRun
    }

    /** Reset the budget for one goal — call when a new run for the goal starts (runtime, agent 2-c). */
    fun reset(goalId: String) {
        counts.remove(goalId)
    }

    /** Reset everything (emergency stop / tests). */
    fun resetAll() {
        counts.clear()
    }
}
