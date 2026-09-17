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
        setContent {
            MotionRoot(onDeepLinkUrl = deepLinkUrl)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val url = intent.takeIf { it.action == Intent.ACTION_VIEW }?.dataString ?: return
        lifecycleScope.launch {
            runCatching { ServiceLocator.requireBrowser().openUrl(url) }
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
