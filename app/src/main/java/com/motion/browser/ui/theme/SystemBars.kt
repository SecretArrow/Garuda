package com.motion.browser.ui.theme

import android.app.Activity
import android.os.Build
import android.view.View
import android.view.ViewTreeObserver
import android.view.Window
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * Keeps the Android system bars adaptive to the app theme:
 *   - dark icons on light backgrounds, light icons on dark ones — for BOTH the
 *     status bar and the navigation bar;
 *   - fully transparent bar scrims so the bars always show the themed app
 *     background (adaptive, never a dead black/white strip);
 *   - contrast-enforcement scrim on the navigation bar disabled (API 29+),
 *     otherwise the system draws a translucent overlay that ignores the theme.
 *
 * Robustness: the appearance is (a) applied on every theme change, (b) re-applied
 * when the view attaches (composition may run before attachment — several OEM
 * skins drop insets-controller flags set on a detached window), and (c) re-applied
 * on every window focus gain (resume, WebView fullscreen exit, dialogs).
 */
object MotionSystemBars {

    @Volatile
    private var lightIcons: Boolean = false

    /** True when system bar icons must be dark (i.e. light app theme). */
    val isLightIcons: Boolean get() = lightIcons

    /** Re-apply the last known appearance to [window] (call on focus gain). */
    fun reassert(window: Window) = apply(window, lightIcons)

    @Suppress("DEPRECATION")
    internal fun apply(window: Window, lightIcons: Boolean) {
        this.lightIcons = lightIcons
        runCatching {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            controller.isAppearanceLightStatusBars = lightIcons
            controller.isAppearanceLightNavigationBars = lightIcons
        }
        if (Build.VERSION.SDK_INT < 35) {
            runCatching {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
            }
        }
        if (Build.VERSION.SDK_INT >= 29) {
            runCatching { window.isNavigationBarContrastEnforced = false }
        }
    }
}

/**
 * Root-level effect binding the resolved Motion theme to the system bar
 * appearance. Call from MotionRoot so any theme change (light/dark/system,
 * dynamic color) re-applies instantly and survives focus/attach races.
 */
@Composable
fun SystemBarAppearanceEffect(darkTheme: Boolean) {
    val view = LocalView.current
    DisposableEffect(darkTheme) {
        val lightIcons = !darkTheme
        val window = (view.context as? Activity)?.window

        val applyNow = { window?.let { MotionSystemBars.apply(it, lightIcons) } }
        applyNow()

        val attachListener = object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(v: View) { applyNow() }
            override fun onViewDetachedFromWindow(v: View) { }
        }
        view.addOnAttachStateChangeListener(attachListener)

        val focusListener = ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) applyNow()
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(focusListener)

        onDispose {
            view.removeOnAttachStateChangeListener(attachListener)
            runCatching {
                view.viewTreeObserver.removeOnWindowFocusChangeListener(focusListener)
            }
        }
    }
}

/**
 * Convenience hook for callers that want to know the resolved dark flag
 * MotionTheme used (status-bar tinting, transitions).
 */
@Composable
fun isMotionDarkTheme(): Boolean = MaterialTheme.colorScheme.background.luminanceCompat() < 0.5f
