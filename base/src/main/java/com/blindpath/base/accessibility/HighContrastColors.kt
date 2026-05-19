package com.blindpath.base.accessibility

import androidx.compose.ui.graphics.Color

/**
 * 高对比度颜色方案
 * 为视障用户提供更清晰的视觉体验
 */
object HighContrastColors {
    
    // ==================== 标准对比度颜色 ====================
    
    // 主色调
    val Primary = Color(0xFF1976D2)
    val PrimaryDark = Color(0xFF0D47A1)
    val PrimaryLight = Color(0xFF42A5F5)
    
    // 背景
    val Background = Color(0xFFFAFAFA)
    val Surface = Color(0xFFFFFFFF)
    val SurfaceVariant = Color(0xFFF5F5F5)
    
    // 文字
    val OnBackground = Color(0xFF1C1B1F)
    val OnSurface = Color(0xFF1C1B1F)
    val OnSurfaceVariant = Color(0xFF49454F)
    
    // 语义颜色
    val Success = Color(0xFF4CAF50)
    val Warning = Color(0xFFFF9800)
    val Error = Color(0xFFF44336)
    val Info = Color(0xFF2196F3)
    
    // 障碍物等级颜色
    val ObstacleDanger = Color(0xFFD32F2F)
    val ObstacleWarning = Color(0xFFFF8F00)
    val ObstacleCaution = Color(0xFFFFC107)
    val ObstacleSafe = Color(0xFF388E3C)
    
    // ==================== 高对比度模式颜色 ====================
    
    // 主色调 - 更深的蓝色
    val HighContrastPrimary = Color(0xFF0000FF)
    val HighContrastPrimaryDark = Color(0xFF000080)
    
    // 背景 - 纯黑白
    val HighContrastBackground = Color(0xFFFFFFFF)
    val HighContrastSurface = Color(0xFFFFFFFF)
    
    // 文字 - 纯黑
    val HighContrastOnBackground = Color(0xFF000000)
    val HighContrastOnSurface = Color(0xFF000000)
    
    // 边框 - 纯黑
    val HighContrastBorder = Color(0xFF000000)
    
    // 语义颜色 - 更鲜明
    val HighContrastSuccess = Color(0xFF00AA00)
    val HighContrastWarning = Color(0xFFDD8800)
    val HighContrastError = Color(0xFFDD0000)
    val HighContrastInfo = Color(0xFF0000DD)
    
    // 障碍物等级颜色 - 高对比度
    val HighContrastObstacleDanger = Color(0xFFDD0000)
    val HighContrastObstacleWarning = Color(0xFFEE8800)
    val HighContrastObstacleCaution = Color(0xFFEECC00)
    val HighContrastObstacleSafe = Color(0xFF00AA00)
    
    // ==================== 反色模式颜色（可选）====================
    
    // 反色背景
    val InverseBackground = Color(0xFF000000)
    val InverseSurface = Color(0xFF1A1A1A)
    
    // 反色文字
    val InverseOnBackground = Color(0xFFFFFFFF)
    val InverseOnSurface = Color(0xFFFFFFFF)
    
    // ==================== 辅助方法 ====================
    
    /**
     * 获取障碍物等级颜色
     */
    fun getObstacleLevelColor(level: AlertLevel, isHighContrast: Boolean): Color {
        return if (isHighContrast) {
            when (level) {
                AlertLevel.CRITICAL -> HighContrastObstacleDanger
                AlertLevel.HIGH -> HighContrastObstacleWarning
                AlertLevel.MEDIUM -> HighContrastObstacleCaution
                AlertLevel.LOW -> ObstacleSafe
                AlertLevel.SAFE -> HighContrastObstacleSafe
            }
        } else {
            when (level) {
                AlertLevel.CRITICAL -> ObstacleDanger
                AlertLevel.HIGH -> ObstacleWarning
                AlertLevel.MEDIUM -> ObstacleCaution
                AlertLevel.LOW -> ObstacleSafe
                AlertLevel.SAFE -> ObstacleSafe
            }
        }
    }
    
    /**
     * 获取语义颜色
     */
    fun getSemanticColor(type: SemanticColorType, isHighContrast: Boolean): Color {
        return if (isHighContrast) {
            when (type) {
                SemanticColorType.SUCCESS -> HighContrastSuccess
                SemanticColorType.WARNING -> HighContrastWarning
                SemanticColorType.ERROR -> HighContrastError
                SemanticColorType.INFO -> HighContrastInfo
            }
        } else {
            when (type) {
                SemanticColorType.SUCCESS -> Success
                SemanticColorType.WARNING -> Warning
                SemanticColorType.ERROR -> Error
                SemanticColorType.INFO -> Info
            }
        }
    }
}

/**
 * 预警级别
 */
enum class AlertLevel {
    CRITICAL,  // 危险：立即停止
    HIGH,      // 高危：小心绕行
    MEDIUM,    // 中等：注意前方
    LOW,       // 低风险：谨慎前行
    SAFE       // 安全：可以通行
}

/**
 * 语义颜色类型
 */
enum class SemanticColorType {
    SUCCESS,
    WARNING,
    ERROR,
    INFO
}

/**
 * 障碍物检测 UI 颜色方案
 */
data class ObstacleColorScheme(
    val criticalBackground: Color,
    val criticalText: Color,
    val highBackground: Color,
    val highText: Color,
    val mediumBackground: Color,
    val mediumText: Color,
    val lowBackground: Color,
    val lowText: Color,
    val safeBackground: Color,
    val safeText: Color
) {
    companion object {
        /**
         * 标准颜色方案
         */
        val Standard = ObstacleColorScheme(
            criticalBackground = Color(0xFFFFEBEE),
            criticalText = Color(0xFFB71C1C),
            highBackground = Color(0xFFFFF3E0),
            highText = Color(0xFFE65100),
            mediumBackground = Color(0xFFFFF8E1),
            mediumText = Color(0xFFF57F17),
            lowBackground = Color(0xFFE8F5E9),
            lowText = Color(0xFF2E7D32),
            safeBackground = Color(0xFFE8F5E9),
            safeText = Color(0xFF1B5E20)
        )
        
        /**
         * 高对比度颜色方案
         */
        val HighContrast = ObstacleColorScheme(
            criticalBackground = Color(0xFFFF0000),
            criticalText = Color(0xFFFFFFFF),
            highBackground = Color(0xFFFF8800),
            highText = Color(0xFF000000),
            mediumBackground = Color(0xFFFFFF00),
            mediumText = Color(0xFF000000),
            lowBackground = Color(0xFF00FF00),
            lowText = Color(0xFF000000),
            safeBackground = Color(0xFF00AA00),
            safeText = Color(0xFFFFFFFF)
        )
    }
    
    /**
     * 根据预警级别获取对应颜色
     */
    fun getColorsForLevel(level: AlertLevel): Pair<Color, Color> {
        return when (level) {
            AlertLevel.CRITICAL -> criticalBackground to criticalText
            AlertLevel.HIGH -> highBackground to highText
            AlertLevel.MEDIUM -> mediumBackground to mediumText
            AlertLevel.LOW -> lowBackground to lowText
            AlertLevel.SAFE -> safeBackground to safeText
        }
    }
}
