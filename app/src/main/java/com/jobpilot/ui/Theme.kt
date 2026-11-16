package com.jobpilot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/*
 * Warm and quiet: a cream page, white rounded cards, near-black text, serif headings and one
 * clay accent for the thing to do next. Green and amber only mark "for" and "watch out".
 */
private val Light = lightColorScheme(
    primary = Color(0xFFC6613F), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFF6E3DA), onPrimaryContainer = Color(0xFF5A2413),
    secondary = Color(0xFF4F7A5B), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF0EEE6), onSecondaryContainer = Color(0xFF1F1E1D),
    tertiary = Color(0xFFA8690F),
    background = Color(0xFFFAF9F5), onBackground = Color(0xFF1F1E1D),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1F1E1D),
    surfaceVariant = Color(0xFFF0EEE6), onSurfaceVariant = Color(0xFF6F6E69),
    surfaceTint = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF0EEE6),
    outline = Color(0xFFC9C6BA), outlineVariant = Color(0xFFE8E6DC),
    error = Color(0xFFB3402F), onError = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF30302E), inverseOnSurface = Color(0xFFF5F4EE), inversePrimary = Color(0xFFD97757),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFD97757), onPrimary = Color(0xFF2A120A),
    primaryContainer = Color(0xFF4A2A1E), onPrimaryContainer = Color(0xFFF6E3DA),
    secondary = Color(0xFF8DB598), onSecondary = Color(0xFF10261A),
    secondaryContainer = Color(0xFF3A3936), onSecondaryContainer = Color(0xFFF5F4EE),
    tertiary = Color(0xFFE0B15E),
    background = Color(0xFF262624), onBackground = Color(0xFFF5F4EE),
    surface = Color(0xFF30302E), onSurface = Color(0xFFF5F4EE),
    surfaceVariant = Color(0xFF3A3936), onSurfaceVariant = Color(0xFFA6A39A),
    surfaceTint = Color(0xFF30302E),
    surfaceContainerLowest = Color(0xFF30302E), surfaceContainerLow = Color(0xFF30302E),
    surfaceContainer = Color(0xFF30302E), surfaceContainerHigh = Color(0xFF353533),
    surfaceContainerHighest = Color(0xFF3A3936),
    outline = Color(0xFF5E5C56), outlineVariant = Color(0xFF3F3E3A),
    error = Color(0xFFEC8F82), onError = Color(0xFF3A100C),
    inverseSurface = Color(0xFFF5F4EE), inverseOnSurface = Color(0xFF1F1E1D), inversePrimary = Color(0xFFC6613F),
)

/** Kept for call sites that used a monospace face; the app now uses the system sans throughout. */
val Mono = FontFamily.Default
private val Serif = FontFamily.Serif

private val AppType = Typography().let { t ->
    t.copy(
        headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 22.sp, lineHeight = 28.sp),
        titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 19.sp, lineHeight = 25.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 16.sp, lineHeight = 24.sp, letterSpacing = 0.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodySmall = t.bodySmall.copy(fontSize = 13.5.sp, lineHeight = 19.sp, letterSpacing = 0.sp),
        labelLarge = t.labelLarge.copy(fontSize = 14.5.sp, letterSpacing = 0.sp),
        labelSmall = TextStyle(fontSize = 12.5.sp, lineHeight = 17.sp, fontWeight = FontWeight.Medium),
    )
}

@Composable
fun isDark(mode: String): Boolean = when (mode) {
    "light" -> false
    "system" -> isSystemInDarkTheme()
    else -> true
}

@Composable
fun JobPilotTheme(mode: String, content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isDark(mode)) Dark else Light, typography = AppType, content = content)
}

/** Amber: cautions, limits, things counting against. */
val warn: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary

/** Green: things counting for. */
val ok: Color
    @Composable get() = MaterialTheme.colorScheme.secondary

@Composable
fun scoreColor(score: Int): Color = when {
    score >= 70 -> ok
    score >= 45 -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
