package com.motion.browser.ui.control

import com.motion.browser.agent.planner.Planner

/**
 * Bridge between the UI layer and the goal Planner.
 *
 * The Planner instance is constructed by the coordinator as a private dependency of
 * [com.motion.browser.agent.runtime.MotionAgentRuntime] and is not exposed through
 * ServiceLocator (per ARCHITECTURE.md §3.1). The coordinator must therefore wire it
 * right after ServiceLocator.init():
 *
 *     GoalPlannerHolder.provider = { plannerInstance }
 *
 * Until it is wired, [planner] returns null and the Goal Editor shows an honest
 * "planner not ready" message instead of pretending to interpret text.
 */
object GoalPlannerHolder {
    @Volatile
    var provider: (() -> Planner?)? = null
}

/** Returns the wired Planner, or null when the coordinator has not wired it yet. */
fun planner(): Planner? = try {
    GoalPlannerHolder.provider?.invoke()
} catch (_: Exception) {
    null
}
