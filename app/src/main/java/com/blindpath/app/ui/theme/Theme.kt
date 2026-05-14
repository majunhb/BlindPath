package com.blindpath.app.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val DarkColorScheme = darkColorScheme(
    primary = Primary,
    onPrimary = TextHighEmphasis,
    secondary = Secondary,
    onSecondary = TextHighEmphasis,
    background = DarkBackground,
    onBackground = TextHighEmphasis,
    surface = DarkSurface,
    onSurface = TextHighEmphasis,
    surfaceVariant = DarkSurfaceVariant,
    error = DangerColor,
    onError = TextHighEmphasis
)

private val LightColorScheme = lightColorScheme(
    primary = Primary,
    onPrimary = TextHighEmphasis,
    secondary = Secondary,
    onSecondary = TextHighEmphasis,
    background = LightBackground,
    onBackground = DarkSurface,
    surface = LightSurface,
    onSurface = DarkSurface,
    error = DangerColor,
    onError = TextHighEmphasis
)

@Composable
fun BlindPathTheme(
    darkTheme: Boolean = true, // 默认深色主题，省电+护眼
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = PrimaryDark.toArgb()
            window.navigationBarColor = DarkBackground.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
