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
 * Developer-tool look: neutral greys, 1px borders, square corners, one blue for links and
 * selection. Sans for reading, monospace for labels, counts and anything tabular.
 * Green and amber are only used the way a diff uses them: for and against.
 */
private val Dark = darkColorScheme(
    primary = Color(0xFF93C5FD), onPrimary = Color(0xFF0B1B2B),
    primaryContainer = Color(0xFF233041), onPrimaryContainer = Color(0xFFD6E8FF),
    secondary = Color(0xFF8FD19E), onSecondary = Color(0xFF0E2415),
    secondaryContainer = Color(0xFF2E2E2E), onSecondaryContainer = Color(0xFFEDEDED),
    tertiary = Color(0xFFE5C07B),
    background = Color(0xFF1F1F1F), onBackground = Color(0xFFEDEDED),
    surface = Color(0xFF262626), onSurface = Color(0xFFEDEDED),
    surfaceVariant = Color(0xFF2E2E2E), onSurfaceVariant = Color(0xFF9CA3AF),
    surfaceTint = Color(0xFF262626),
    surfaceContainerLowest = Color(0xFF262626), surfaceContainerLow = Color(0xFF262626),
    surfaceContainer = Color(0xFF262626), surfaceContainerHigh = Color(0xFF2B2B2B),
    surfaceContainerHighest = Color(0xFF2E2E2E),
    outline = Color(0xFF5A5A5A), outlineVariant = Color(0xFF3A3A3A),
    error = Color(0xFFF28B82), onError = Color(0xFF3A0F0B),
    inverseSurface = Color(0xFFEDEDED), inverseOnSurface = Color(0xFF1F1F1F), inversePrimary = Color(0xFF2563EB),
)

private val Light = lightColorScheme(
    primary = Color(0xFF2563EB), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE5EDFF), onPrimaryContainer = Color(0xFF12306E),
    secondary = Color(0xFF15803D), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFF3F4F6), onSecondaryContainer = Color(0xFF111827),
    tertiary = Color(0xFFB45309),
    background = Color(0xFFFAFAFA), onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFF3F4F6), onSurfaceVariant = Color(0xFF6B7280),
    surfaceTint = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF3F4F6),
    outline = Color(0xFF9CA3AF), outlineVariant = Color(0xFFE5E7EB),
    error = Color(0xFFB91C1C), onError = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF1F1F1F), inverseOnSurface = Color(0xFFEDEDED), inversePrimary = Color(0xFF93C5FD),
)

val Mono = FontFamily.Monospace

private val AppType = Typography().let { t ->
    t.copy(
        headlineSmall = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 20.sp, lineHeight = 26.sp),
        titleLarge = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 25.sp),
        titleMedium = TextStyle(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodySmall = t.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelLarge = t.labelLarge.copy(fontSize = 13.sp, letterSpacing = 0.sp),
        labelSmall = TextStyle(fontFamily = Mono, fontSize = 11.5.sp, lineHeight = 16.sp),
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
