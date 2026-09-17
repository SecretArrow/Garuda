package com.motion.browser.security

import java.util.concurrent.ConcurrentHashMap

/**
 * Automation-loop detector (spec §49).
 *
 * Keeps the last [WINDOW] action signatures per goal. A signature is typically
 * "toolId:hash(argsPrefix)" produced by the ToolRegistry. When the SAME signature repeats
 * [LOOP_THRESHOLD] or more consecutive times, the agent is stuck in a loop and [observe]
 * returns true — the SafetyGuard then denies the action and the runtime pauses the run.
 *
 * Contract (ARCHITECTURE.md §3.5) — exact signature:
 *   class LoopDetector { fun observe(goalId: String, signature: String): Boolean }
 */
class LoopDetector {

    private val history = ConcurrentHashMap<String, ArrayDeque<String>>()

    /**
     * Register [signature] for [goalId] and report whether a loop was detected:
     * true when the same signature appears >= [LOOP_THRESHOLD] times consecutively
     * at the tail of the window (window keeps at most [WINDOW] entries).
     */
    fun observe(goalId: String, signature: String): Boolean {
        val deque = history.getOrPut(goalId) { ArrayDeque() }
        synchronized(deque) {
            deque.addLast(signature)
            while (deque.size > WINDOW) deque.removeFirst()
            var consecutive = 0
            for (i in deque.indices.reversed()) {
                if (deque[i] == signature) consecutive++ else break
            }
            return consecutive >= LOOP_THRESHOLD
        }
    }

    /** Clear the window for one goal — call when a new run starts or after a successful distinct step. */
    fun reset(goalId: String) {
        history.remove(goalId)
    }

    /** Clear everything (emergency stop / tests). */
    fun resetAll() {
        history.clear()
    }

    companion object {
        const val WINDOW: Int = 12
        const val LOOP_THRESHOLD: Int = 5
    }
}
