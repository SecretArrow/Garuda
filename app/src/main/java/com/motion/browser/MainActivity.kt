package com.motion.browser

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.motion.browser.ui.nav.MotionRoot
import kotlinx.coroutines.launch

/**
 * Motion Browser single-activity entry. Hosts [MotionRoot] (browser shell,
 * Motion AI panel, Control Center) and handles ACTION_VIEW deep links so Motion
 * registers as a real browser (spec §41 navigation).
 */
class MainActivity : ComponentActivity() {

    private val notifPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            // Honest handling: notifications stay off when denied; agent results are
            // always visible in Control Center regardless.
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        ServiceLocator.attachBrowser(this)
        requestNotificationPermissionIfNeeded()

        val deepLinkUrl = intent?.takeIf { it.action == Intent.ACTION_VIEW }?.dataString
        consumeShareOrProcessText(intent)
        setContent {
            MotionRoot(onDeepLinkUrl = deepLinkUrl)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        consumeShareOrProcessText(intent)
        val url = intent.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        lifecycleScope.launch {
            runCatching { ServiceLocator.requireBrowser().openUrl(url) }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Re-assert adaptive system bar appearance — some OEM skins reset the
        // insets-controller flags when the window regains focus (resume,
        // WebView fullscreen exit, dialogs).
        if (hasFocus) {
            runCatching { com.motion.browser.ui.theme.MotionSystemBars.reassert(window) }
        }
    }

    /** Share-to-AI (ACTION_SEND) and selection → Ask AI (ACTION_PROCESS_TEXT). */
    private fun consumeShareOrProcessText(intent: Intent?) {
        intent ?: return
        val text = when (intent.action) {
            Intent.ACTION_SEND ->
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getStringExtra(Intent.EXTRA_SUBJECT)
            android.content.Intent.ACTION_PROCESS_TEXT ->
                intent.getCharSequenceExtra(android.content.Intent.EXTRA_PROCESS_TEXT)?.toString()
            else -> null
        }
        if (!text.isNullOrBlank()) {
            com.motion.browser.ui.chat.ChatIntake.pendingText = text
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notifPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
