package com.jobpilot.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val LinkedInBlue = Color(0xFF0A66C2)
val Good = Color(0xFF1E8E3E)
val Mid = Color(0xFFE3A008)
val Low = Color(0xFFC5221F)

@Composable
fun JobPilotTheme(content: @Composable () -> Unit) {
    val scheme = if (isSystemInDarkTheme()) darkColorScheme(
        primary = Color(0xFF70B5F9),
        secondary = Color(0xFF9CC9F5),
    ) else lightColorScheme(
        primary = LinkedInBlue,
        secondary = Color(0xFF378FE9),
        primaryContainer = Color(0xFFDCEBFA),
        onPrimaryContainer = Color(0xFF0B2E52),
    )
    MaterialTheme(colorScheme = scheme, content = content)
}

fun scoreColor(score: Int): Color = when {
    score >= 70 -> Good
    score >= 45 -> Mid
    else -> Low
}
