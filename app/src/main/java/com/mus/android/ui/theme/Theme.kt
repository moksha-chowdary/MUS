package com.mus.android.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val MusDarkColorScheme = darkColorScheme(
    primary = MusColors.Accent,
    onPrimary = MusColors.Background,
    secondary = MusColors.AccentDim,
    onSecondary = MusColors.OnBackground,
    background = MusColors.Background,
    onBackground = MusColors.OnBackground,
    surface = MusColors.Surface,
    onSurface = MusColors.OnBackground,
    surfaceVariant = MusColors.SurfaceVariant,
    onSurfaceVariant = MusColors.OnBackgroundSecondary,
    outline = MusColors.Divider,
    error = Color(0xFFCF6679),
)

@Composable
fun MusTheme(content: @Composable () -> Unit) {
    val colorScheme = MusDarkColorScheme
    val view = LocalView.current

    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = Color.Transparent.toArgb()
            window.navigationBarColor = Color.Transparent.toArgb()
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = false
                isAppearanceLightNavigationBars = false
            }
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = MusTypography,
        content = content,
    )
}
