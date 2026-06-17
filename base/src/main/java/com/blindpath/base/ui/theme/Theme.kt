package com.blindpath.base.ui.theme

import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

/**
 * 主题模式
 */
enum class ThemeMode {
    LIGHT,      // 浅色模式
    DARK,       // 深色模式
    SYSTEM,     // 跟随系统
    HIGH_CONTRAST // 高对比度模式
}

/**
 * 主题管理器
 */
class ThemeManager(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "theme_settings"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_DYNAMIC_COLOR = "dynamic_color"
        private const val KEY_CUSTOM_PRIMARY_COLOR = "custom_primary_color"
        
        @Volatile
        private var instance: ThemeManager? = null
        
        fun getInstance(context: Context): ThemeManager {
            return instance ?: synchronized(this) {
                instance ?: ThemeManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 当前主题模式
     */
    var themeMode: ThemeMode
        get() {
            val modeName = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
            return ThemeMode.entries.find { it.name == modeName } ?: ThemeMode.SYSTEM
        }
        set(value) {
            prefs.edit().putString(KEY_THEME_MODE, value.name).apply()
        }
    
    /**
     * 是否启用动态颜色
     */
    var isDynamicColorEnabled: Boolean
        get() = prefs.getBoolean(KEY_DYNAMIC_COLOR, true)
        set(value) {
            prefs.edit().putBoolean(KEY_DYNAMIC_COLOR, value).apply()
        }
    
    /**
     * 自定义主色
     */
    var customPrimaryColor: Color?
        get() {
            val colorValue = prefs.getInt(KEY_CUSTOM_PRIMARY_COLOR, -1)
            return if (colorValue != -1) Color(colorValue) else null
        }
        set(value) {
            if (value != null) {
                prefs.edit().putInt(KEY_CUSTOM_PRIMARY_COLOR, value.toArgb()).apply()
            } else {
                prefs.edit().remove(KEY_CUSTOM_PRIMARY_COLOR).apply()
            }
        }
}

/**
 * 浅色配色方案
 */
private val LightColorScheme = lightColorScheme(
    primary = Color(0xFF1976D2),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFBBDEFB),
    onPrimaryContainer = Color(0xFF0D47A1),
    
    secondary = Color(0xFF03DAC6),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFFB2DFDB),
    onSecondaryContainer = Color(0xFF00695C),
    
    tertiary = Color(0xFF3700B3),
    onTertiary = Color.White,
    
    background = Color(0xFFFAFAFA),
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    
    error = Color(0xFFB3261E),
    onError = Color.White,
    errorContainer = Color(0xFFF9DEDC),
    onErrorContainer = Color(0xFF410E0B)
)

/**
 * 深色配色方案
 */
private val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF90CAF9),
    onPrimary = Color(0xFF0D47A1),
    primaryContainer = Color(0xFF1565C0),
    onPrimaryContainer = Color(0xFFBBDEFB),
    
    secondary = Color(0xFF03DAC6),
    onSecondary = Color.Black,
    secondaryContainer = Color(0xFF004D40),
    onSecondaryContainer = Color(0xFFB2DFDB),
    
    tertiary = Color(0xFF7D5260),
    onTertiary = Color.White,
    
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    
    error = Color(0xFFF2B8B5),
    onError = Color(0xFF601410),
    errorContainer = Color(0xFF8C1D18),
    onErrorContainer = Color(0xFFF9DEDC)
)

/**
 * 高对比度配色方案
 */
private val HighContrastColorScheme = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color.White,
    onPrimaryContainer = Color.Black,
    
    secondary = Color.Black,
    onSecondary = Color.White,
    secondaryContainer = Color.White,
    onSecondaryContainer = Color.Black,
    
    tertiary = Color.Black,
    onTertiary = Color.White,
    
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    
    error = Color(0xFFDD0000),
    onError = Color.White,
    errorContainer = Color(0xFFFFEBEE),
    onErrorContainer = Color(0xFFB71C1C)
)

/**
 * BlindPath 主题
 */
@Composable
fun BlindPathTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    themeMode: ThemeMode = ThemeManager.getInstance(LocalContext.current).themeMode,
    dynamicColor: Boolean = ThemeManager.getInstance(LocalContext.current).isDynamicColorEnabled,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    
    // 确定是否使用深色主题
    val useDarkTheme = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> darkTheme
        ThemeMode.HIGH_CONTRAST -> false
    }
    
    // 选择配色方案
    val colorScheme = when {
        themeMode == ThemeMode.HIGH_CONTRAST -> HighContrastColorScheme
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            if (useDarkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        useDarkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    
    // 设置状态栏颜色
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.primary.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = !useDarkTheme
        }
    }
    
    MaterialTheme(
        colorScheme = colorScheme,
        typography = BlindPathTypography,
        content = content
    )
}

/**
 * 字体样式
 */
private val BlindPathTypography = Typography(
    // 标题样式
    headlineLarge = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(32f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
    ),
    headlineMedium = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(28f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold
    ),
    headlineSmall = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(24f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
    ),
    
    // 正文样式
    bodyLarge = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(16f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal
    ),
    bodyMedium = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal
    ),
    bodySmall = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Normal
    ),
    
    // 标签样式
    labelLarge = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(14f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
    ),
    labelMedium = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(12f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
    ),
    labelSmall = androidx.compose.ui.text.TextStyle(
        fontSize = androidx.compose.ui.unit.TextUnit(11f, androidx.compose.ui.unit.TextUnitType.Sp),
        fontWeight = androidx.compose.ui.text.font.FontWeight.Medium
    )
)
