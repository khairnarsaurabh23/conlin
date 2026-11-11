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
 * Paper-and-ink look: warm off-white, near-black text, hairline borders, one muted green accent.
 * Serif for titles, the system sans for everything else. No gradients, shadows or tinted surfaces.
 */
private val LightScheme = lightColorScheme(
    primary = Color(0xFF2F5D50), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE3ECE8), onPrimaryContainer = Color(0xFF1C3A32),
    secondary = Color(0xFF2F5D50), onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDEBE5), onSecondaryContainer = Color(0xFF1F1E1C),
    tertiary = Color(0xFF8A5A14),
    background = Color(0xFFFAF9F6), onBackground = Color(0xFF1F1E1C),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF1F1E1C),
    surfaceVariant = Color(0xFFF1EFEA), onSurfaceVariant = Color(0xFF6B6A66),
    surfaceTint = Color(0xFFFFFFFF),
    surfaceContainerLowest = Color(0xFFFFFFFF), surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF), surfaceContainerHigh = Color(0xFFFFFFFF),
    surfaceContainerHighest = Color(0xFFF1EFEA),
    outline = Color(0xFFB9B6AE), outlineVariant = Color(0xFFE4E2DC),
    error = Color(0xFF9E3B32), onError = Color(0xFFFFFFFF),
    inverseSurface = Color(0xFF2A2927), inverseOnSurface = Color(0xFFF4F2EC), inversePrimary = Color(0xFF9CCDBD),
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF9CCDBD), onPrimary = Color(0xFF10261F),
    primaryContainer = Color(0xFF24362F), onPrimaryContainer = Color(0xFFCFE6DD),
    secondary = Color(0xFF9CCDBD), onSecondary = Color(0xFF10261F),
    secondaryContainer = Color(0xFF2C2B28), onSecondaryContainer = Color(0xFFECEAE4),
    tertiary = Color(0xFFD9B26A),
    background = Color(0xFF181716), onBackground = Color(0xFFECEAE4),
    surface = Color(0xFF201F1D), onSurface = Color(0xFFECEAE4),
    surfaceVariant = Color(0xFF2A2927), onSurfaceVariant = Color(0xFFA3A19A),
    surfaceTint = Color(0xFF201F1D),
    surfaceContainerLowest = Color(0xFF201F1D), surfaceContainerLow = Color(0xFF201F1D),
    surfaceContainer = Color(0xFF201F1D), surfaceContainerHigh = Color(0xFF262523),
    surfaceContainerHighest = Color(0xFF2A2927),
    outline = Color(0xFF55534E), outlineVariant = Color(0xFF34322F),
    error = Color(0xFFE39A91), onError = Color(0xFF3A100C),
    inverseSurface = Color(0xFFECEAE4), inverseOnSurface = Color(0xFF1F1E1C), inversePrimary = Color(0xFF2F5D50),
)

private val Serif = FontFamily.Serif

private val AppType = Typography().let { t ->
    t.copy(
        headlineSmall = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 26.sp, lineHeight = 32.sp),
        titleLarge = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 21.sp, lineHeight = 27.sp),
        titleMedium = TextStyle(fontFamily = Serif, fontWeight = FontWeight.Normal, fontSize = 18.sp, lineHeight = 24.sp),
        titleSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 21.sp),
        bodyLarge = t.bodyLarge.copy(fontSize = 15.sp, lineHeight = 22.sp, letterSpacing = 0.sp),
        bodyMedium = t.bodyMedium.copy(fontSize = 14.sp, lineHeight = 20.sp, letterSpacing = 0.sp),
        bodySmall = t.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp, letterSpacing = 0.sp),
        labelLarge = t.labelLarge.copy(fontSize = 14.sp, letterSpacing = 0.sp),
    )
}

@Composable
fun JobPilotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        typography = AppType,
        content = content,
    )
}

/** Muted amber used for cautions (limits, follow-ups). */
val warn: Color
    @Composable get() = MaterialTheme.colorScheme.tertiary

@Composable
fun scoreColor(score: Int): Color = when {
    score >= 70 -> MaterialTheme.colorScheme.primary
    score >= 45 -> MaterialTheme.colorScheme.onSurface
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}
