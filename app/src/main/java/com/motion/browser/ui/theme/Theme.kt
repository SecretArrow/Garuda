package com.motion.browser.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Motion Browser brand: Motion Browser blue + gold. Adaptive light/dark (plan Prompt 7). */
private val MotionBlue = Color(0xFF2A5CFF)
private val MotionGold = Color(0xFFFFB300)

private val LightColors = lightColorScheme(
    primary = MotionBlue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE3FF),
    onPrimaryContainer = Color(0xFF0A1E6B),
    secondary = MotionGold,
    onSecondary = Color(0xFF2B1D00),
    secondaryContainer = Color(0xFFFFE199),
    onSecondaryContainer = Color(0xFF241A00),
    background = Color(0xFFFCFBFF),
    surface = Color(0xFFFCFBFF),
    surfaceVariant = Color(0xFFE2E2F0),
    onSurfaceVariant = Color(0xFF454651),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFB4C3FF),
    onPrimary = Color(0xFF0A2280),
    primaryContainer = Color(0xFF1237A8),
    onPrimaryContainer = Color(0xFFDCE3FF),
    secondary = MotionGold,
    background = Color(0xFF0F1222),
    surface = Color(0xFF0F1222),
    surfaceVariant = Color(0xFF262940),
    onSurfaceVariant = Color(0xFFC5C4D8),
)

@Composable
fun MotionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        content = content,
    )
}
