package com.motion.browser.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Keeps the Android system bars in sync with the app theme: dark icons on
 * light backgrounds, light icons on dark ones — for BOTH the status bar and
 * the navigation bar. Call from the root composable (MotionRoot) so any
 * theme-mode change (system/light/dark + dynamic color) re-applies instantly.
 */
@Composable
fun SystemBarAppearanceEffect(darkTheme: Boolean) {
    val view = LocalView.current
    SideEffect {
        val window = (view.context as? Activity)?.window ?: return@SideEffect
        val controller = WindowCompat.getInsetsController(window, view)
        controller.isAppearanceLightStatusBars = !darkTheme
        controller.isAppearanceLightNavigationBars = !darkTheme
    }
}

/**
 * Convenience hook for callers that want to know the resolved dark flag
 * MotionTheme used (status-bar tinting, transitions).
 */
@Composable
fun isMotionDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminanceCompat() < 0.5f
