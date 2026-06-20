package com.blindpath.app.ui.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 高对比无障碍配色方案
 * 暗色模式：纯白文字 + 深底 = 18:1 对比度 (WCAG AAA)
 * 亮色模式：深色文字 + 白底 = 15:1 对比度 (WCAG AAA)
 */
private val HighContrastDarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = Color(0xFF003547),
    secondary = Color(0xFF81C784),
    onSecondary = Color(0xFF003A02),
    tertiary = Color(0xFFFFB74D),
    onTertiary = Color(0xFF3E2500),
    background = DarkBackground,
    onBackground = DarkOnSurface,
    surface = DarkSurface,
    onSurface = DarkOnSurface,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = DarkOnSurfaceVariant,
    error = DangerColor,
    onError = Color.White,
    outline = Color(0xFF3A3A55),
    outlineVariant = Color(0xFF2A2A40),
)

private val HighContrastLightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    secondary = Color(0xFF2E7D32),
    onSecondary = Color.White,
    tertiary = Color(0xFFE65100),
    onTertiary = Color.White,
    background = LightBackground,
    onBackground = LightOnSurface,
    surface = LightSurface,
    onSurface = LightOnSurface,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = LightOnSurfaceVariant,
    error = Color(0xFFC62828),
    onError = Color.White,
    outline = Color(0xFFC0C0D0),
    outlineVariant = Color(0xFFD0D0E0),
)

@Composable
fun BlindPathTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) HighContrastDarkColorScheme else HighContrastLightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.surface.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !darkTheme
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}
