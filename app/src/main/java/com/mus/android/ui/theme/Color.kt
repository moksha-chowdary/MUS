package com.mus.android.ui.theme

import androidx.compose.ui.graphics.Color

// MUS base palette — dark-first, Nothing-inspired
object MusColors {
    val Background = Color(0xFF0A0A0A)
    val Surface = Color(0xFF121212)
    val SurfaceVariant = Color(0xFF1A1A1A)
    val SurfaceElevated = Color(0xFF222222)

    val OnBackground = Color(0xFFEEEEEE)
    val OnBackgroundSecondary = Color(0xFFAAAAAA)
    val OnBackgroundTertiary = Color(0xFF666666)

    val Accent = Color(0xFFDDDDDD) // near-white accent, not colorful
    val AccentDim = Color(0xFF888888)

    val Divider = Color(0xFF2A2A2A)

    val Favorite = Color(0xFFE05555) // subtle red for favorite heart
    val Error = Color(0xFFCF6679) // subtle red/pink for destructive actions and error states

    val Transparent = Color.Transparent
    val Black = Color.Black
    val White = Color.White

    // Waveform
    val WaveformPlayed = Color(0xFFEEEEEE)
    val WaveformUnplayed = Color(0xFF444444) // ~25-30% opacity feel
    val WaveformPlayhead = Color.White
}
