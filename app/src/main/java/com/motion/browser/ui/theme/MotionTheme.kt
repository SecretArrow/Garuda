package com.motion.browser.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * Motion Browser brand theme.
 * Brand palette: primary #5B8CFF, secondary #FFC857, dark background #1A1C2E.
 * Material You dynamic color is applied only when API >= 31 and [dynamicColor] is true.
 */
val MotionPrimary = Color(0xFF5B8CFF)
val MotionSecondary = Color(0xFFFFC857)
val MotionDarkBackground = Color(0xFF1A1C2E)

private val LightColors = lightColorScheme(
    primary = MotionPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE5FF),
    onPrimaryContainer = Color(0xFF0F2A6B),
    inversePrimary = Color(0xFFB4C4FF),
    secondary = MotionSecondary,
    onSecondary = Color(0xFF3F2D00),
    secondaryContainer = Color(0xFFFFE0A6),
    onSecondaryContainer = Color(0xFF271A00),
    tertiary = Color(0xFF7A5AF8),
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6DEFF),
    onTertiaryContainer = Color(0xFF24146B),
    background = Color(0xFFFBF9FF),
    onBackground = Color(0xFF1A1B24),
    surface = Color(0xFFFBF9FF),
    onSurface = Color(0xFF1A1B24),
    surfaceVariant = Color(0xFFE2E1F0),
    onSurfaceVariant = Color(0xFF454651),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC6C5D4),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = MotionPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF2F4E9E),
    onPrimaryContainer = Color(0xFFD8E1FF),
    inversePrimary = Color(0xFF2B4BA5),
    secondary = MotionSecondary,
    onSecondary = Color(0xFF3F2D00),
    secondaryContainer = Color(0xFF4A3A10),
    onSecondaryContainer = Color(0xFFFFE0A6),
    tertiary = Color(0xFFB8A7FF),
    onTertiary = Color(0xFF2A1668),
    tertiaryContainer = Color(0xFF43318F),
    onTertiaryContainer = Color(0xFFE6DEFF),
    background = MotionDarkBackground,
    onBackground = Color(0xFFE4E1F2),
    surface = MotionDarkBackground,
    onSurface = Color(0xFFE4E1F2),
    surfaceVariant = Color(0xFF262940),
    onSurfaceVariant = Color(0xFFC5C4D8),
    outline = Color(0xFF8F90A6),
    outlineVariant = Color(0xFF45465C),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/**
 * Default Material typography with display styles tuned for the "Motion" wordmark
 * and headings. Body/label styles follow Material 3 defaults.
 */
private val MotionTypography = Typography(
    displayLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 44.sp, lineHeight = 50.sp, letterSpacing = (-0.5).sp,
    ),
    displayMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 34.sp, lineHeight = 40.sp, letterSpacing = (-0.25).sp,
    ),
    displaySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold,
        fontSize = 28.sp, lineHeight = 34.sp,
    ),
    headlineLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 30.sp, lineHeight = 36.sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 26.sp, lineHeight = 32.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 22.sp, lineHeight = 28.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 20.sp, lineHeight = 26.sp, letterSpacing = 0.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp, lineHeight = 22.sp, letterSpacing = 0.15.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.3.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.2.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Normal,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.1.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.4.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Medium,
        fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp,
    ),
)

@Composable
fun MotionTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MotionTypography,
        content = content,
    )
}

/** Relative luminance via ColorSpace-level math available in Compose Color. */
fun Color.luminanceCompat(): Float =
    (0.2126f * red + 0.7152f * green + 0.0722f * blue)

/**
 * MotionTheme variant driven by the user's Settings (ThemeMode + dynamic color).
 * The original [MotionTheme] overload is kept for tests/previews.
 */
@androidx.compose.runtime.Composable
fun MotionTheme(
    themeMode: com.motion.browser.data.ThemeMode,
    dynamicColor: Boolean,
    content: @Composable () -> Unit,
) {
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val dark = when (themeMode) {
        com.motion.browser.data.ThemeMode.SYSTEM -> systemDark
        com.motion.browser.data.ThemeMode.LIGHT -> false
        com.motion.browser.data.ThemeMode.DARK -> true
    }
    MotionTheme(darkTheme = dark, dynamicColor = dynamicColor, content = content)
}
