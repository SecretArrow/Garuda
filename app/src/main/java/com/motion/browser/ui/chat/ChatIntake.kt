package com.motion.browser.ui.chat

/**
 * Hand-off channel for text sent into Motion AI from outside the chat screen:
 * ACTION_SEND (share page/text) and ACTION_PROCESS_TEXT (selection → Ask AI).
 * MainActivity writes; ChatScreen consumes once on entry.
 */
object ChatIntake {
    @Volatile
    var pendingText: String? = null

    fun consume(): String? {
        val t = pendingText
        pendingText = null
        return t
    }
}
