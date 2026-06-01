package com.blindpath.base.accessibility

import android.content.Context
import android.content.SharedPreferences
import android.view.accessibility.AccessibilityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import timber.log.Timber

/**
 * 无障碍设置管理器
 * 管理应用的无障碍相关设置
 */
class AccessibilitySettings(
    context: Context
) {
    companion object {
        private const val PREFS_NAME = "accessibility_settings"
        
        // 字体缩放
        private const val KEY_FONT_SCALE = "font_scale"
        const val FONT_SCALE_NORMAL = 1.0f
        const val FONT_SCALE_LARGE = 1.3f
        const val FONT_SCALE_EXTRA_LARGE = 1.6f
        
        // 高对比度模式
        private const val KEY_HIGH_CONTRAST = "high_contrast"
        
        // 语音交互
        private const val KEY_VOICE_ENABLED = "voice_enabled"
        private const val KEY_VOICE_SPEED = "voice_speed"
        
        // 振动反馈
        private const val KEY_VIBRATION_ENABLED = "vibration_enabled"
        private const val KEY_VIBRATION_INTENSITY = "vibration_intensity"
        
        // 手势操作
        private const val KEY_GESTURE_ENABLED = "gesture_enabled"
        
        // 自动播报
        private const val KEY_AUTO_ANNOUNCE = "auto_announce"
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    
    // ==================== 字体缩放 ====================
    
    /**
     * 字体缩放比例
     */
    var fontScale: Float
        get() = prefs.getFloat(KEY_FONT_SCALE, FONT_SCALE_NORMAL)
        set(value) {
            prefs.edit().putFloat(KEY_FONT_SCALE, value.coerceIn(0.8f, 2.0f)).apply()
            Timber.d("Font scale set to: $value")
        }
    
    /**
     * 字体缩放级别
     */
    enum class FontScaleLevel(val scale: Float, val displayName: String) {
        NORMAL(FONT_SCALE_NORMAL, "标准"),
        LARGE(FONT_SCALE_LARGE, "大字体"),
        EXTRA_LARGE(FONT_SCALE_EXTRA_LARGE, "超大字体");
        
        companion object {
            fun fromScale(scale: Float): FontScaleLevel {
                return entries.minByOrNull { kotlin.math.abs(it.scale - scale) } ?: NORMAL
            }
        }
    }
    
    val fontScaleLevel: FontScaleLevel
        get() = FontScaleLevel.fromScale(fontScale)
    
    // ==================== 高对比度模式 ====================
    
    /**
     * 高对比度模式
     */
    var isHighContrastEnabled: Boolean
        get() = prefs.getBoolean(KEY_HIGH_CONTRAST, false)
        set(value) {
            prefs.edit().putBoolean(KEY_HIGH_CONTRAST, value).apply()
            Timber.d("High contrast ${if (value) "enabled" else "disabled"}")
        }
    
    /**
     * 检查系统是否启用了高对比度
     */
    val isSystemHighContrastEnabled: Boolean
        get() = false // Android 没有直接的API，需要根据系统设置判断
    
    // ==================== 语音交互 ====================
    
    /**
     * 语音交互是否启用
     */
    var isVoiceEnabled: Boolean
        get() = prefs.getBoolean(KEY_VOICE_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_VOICE_ENABLED, value).apply()
        }
    
    /**
     * 语音播报速度 (0.5 - 2.0)
     */
    var voiceSpeed: Float
        get() = prefs.getFloat(KEY_VOICE_SPEED, 0.9f)
        set(value) {
            prefs.edit().putFloat(KEY_VOICE_SPEED, value.coerceIn(0.5f, 2.0f)).apply()
        }
    
    // ==================== 振动反馈 ====================
    
    /**
     * 振动反馈是否启用
     */
    var isVibrationEnabled: Boolean
        get() = prefs.getBoolean(KEY_VIBRATION_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_VIBRATION_ENABLED, value).apply()
        }
    
    /**
     * 振动强度 (0.0 - 1.0)
     */
    var vibrationIntensity: Float
        get() = prefs.getFloat(KEY_VIBRATION_INTENSITY, 1.0f)
        set(value) {
            prefs.edit().putFloat(KEY_VIBRATION_INTENSITY, value.coerceIn(0.0f, 1.0f)).apply()
        }
    
    // ==================== 手势操作 ====================
    
    /**
     * 手势操作是否启用
     */
    var isGestureEnabled: Boolean
        get() = prefs.getBoolean(KEY_GESTURE_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_GESTURE_ENABLED, value).apply()
        }
    
    // ==================== 自动播报 ====================
    
    /**
     * 自动播报是否启用
     */
    var isAutoAnnounceEnabled: Boolean
        get() = prefs.getBoolean(KEY_AUTO_ANNOUNCE, true)
        set(value) {
            prefs.edit().putBoolean(KEY_AUTO_ANNOUNCE, value).apply()
        }
    
    // ==================== 辅助方法 ====================
    
    /**
     * 检查是否有辅助功能服务启用
     */
    val isAccessibilityServiceEnabled: Boolean
        get() = accessibilityManager.isEnabled
    
    /**
     * 检查是否正在探索（TalkBack 模式）
     */
    val isTouchExplorationEnabled: Boolean
        get() = accessibilityManager.isTouchExplorationEnabled
    
    /**
     * 获取所有设置的摘要
     */
    fun getSettingsSummary(): Map<String, Any> {
        return mapOf(
            "fontScale" to fontScale,
            "highContrast" to isHighContrastEnabled,
            "voiceEnabled" to isVoiceEnabled,
            "voiceSpeed" to voiceSpeed,
            "vibrationEnabled" to isVibrationEnabled,
            "vibrationIntensity" to vibrationIntensity,
            "gestureEnabled" to isGestureEnabled,
            "autoAnnounce" to isAutoAnnounceEnabled
        )
    }
    
    /**
     * 重置所有设置到默认值
     */
    fun resetToDefaults() {
        prefs.edit().clear().apply()
        Timber.d("Accessibility settings reset to defaults")
    }
}

/**
 * Composable 函数：获取当前字体大小（考虑缩放）
 */
@Composable
fun rememberScaledFontSize(baseFontSize: androidx.compose.ui.unit.TextUnit): androidx.compose.ui.unit.TextUnit {
    val context = LocalContext.current
    val settings = remember { AccessibilitySettings(context) }
    
    return androidx.compose.ui.unit.TextUnit(
        value = (baseFontSize.value * settings.fontScale).coerceIn(8f, 96f),
        type = androidx.compose.ui.unit.TextUnitType.Sp
    )
}

/**
 * Composable 函数：监听高对比度模式变化
 */
@Composable
fun rememberHighContrastState(): State<Boolean> {
    val context = LocalContext.current
    val settings = remember { AccessibilitySettings(context) }
    val state = remember { mutableStateOf(settings.isHighContrastEnabled) }
    
    DisposableEffect(settings) {
        state.value = settings.isHighContrastEnabled
        onDispose { }
    }
    
    return state
}
