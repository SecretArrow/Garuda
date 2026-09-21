package com.motion.browser

import android.content.Intent
import android.os.Bundle
import android.webkit.WebView
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge

/**
 * Single-activity entry (plan Prompt 7): hosts the browser shell, chat drawer,
 * dashboard and settings. Receives ACTION_VIEW (Motion Browser is a real browser),
 * ACTION_SEND (share → new task) and ACTION_PROCESS_TEXT (selection → Ask AI).
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.attach(this)
        requestNotificationPermissionIfNeeded()
        consumeIntent(intent)
        setContent { com.motion.browser.ui.MotionBrowserApp() }
    }

    /** Download/agent progress notifications need a runtime grant on API 33+. */
    private fun requestNotificationPermissionIfNeeded() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            runCatching {
                requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 100)
            }
        }
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
