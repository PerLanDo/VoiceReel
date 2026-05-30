package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Voice Reels is an always-dark experience built around a deep slate canvas with a sky-blue
// accent, so we ship a single curated dark color scheme rather than relying on dynamic color.
private val VoiceReelsColorScheme =
  darkColorScheme(
    primary = Color(0xFF3B82F6),
    onPrimary = Color.White,
    secondary = Color(0xFF38BDF8),
    onSecondary = Color.White,
    tertiary = Color(0xFFEC4899),
    background = Color(0xFF020617),
    onBackground = Color(0xFFE2E8F0),
    surface = Color(0xFF0F172A),
    onSurface = Color(0xFFE2E8F0),
    surfaceVariant = Color(0xFF1E293B),
  )

@Composable
fun MyApplicationTheme(content: @Composable () -> Unit) {
  MaterialTheme(colorScheme = VoiceReelsColorScheme, typography = Typography, content = content)
}
