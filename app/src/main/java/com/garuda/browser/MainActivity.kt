package com.garuda.browser

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Single-activity entry (plan Prompt 7): hosts the browser shell, chat drawer,
 * dashboard and settings. Receives ACTION_VIEW (Garuda is a real browser),
 * ACTION_SEND (share → new task) and ACTION_PROCESS_TEXT (selection → Ask AI).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.attach(this)
        consumeIntent(intent)
        setContent { com.garuda.browser.ui.GarudaApp() }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeIntent(intent)
    }

    private fun consumeIntent(intent: Intent?) {
        intent ?: return
        when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.let { url ->
                ServiceLocator.browser.createTab(url)
            }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT) ?: return
                ChatIntake.pendingText = "Automate this shared content:\n$text"
            }
            Intent.ACTION_PROCESS_TEXT -> {
                val text = intent.getCharSequenceExtra(Intent.EXTRA_PROCESS_TEXT)?.toString() ?: return
                ChatIntake.pendingText = "Ask about this selected text:\n$text"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Avoid leaking WebView renderer memory on activity recreation.
        if (isFinishing) {
            ServiceLocator.browser.tabs.forEach { runCatching { it.webView.destroy() } }
        }
    }
}

/** Pending share/selection text waiting to be picked up by the chat drawer. */
object ChatIntake {
    @Volatile var pendingText: String? = null
    fun consume(): String? = pendingText.also { pendingText = null }
}
