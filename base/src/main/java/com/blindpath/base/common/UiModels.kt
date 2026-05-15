package com.blindpath.base.common

/**
 * 预警等级
 */
enum class AlertLevel {
    DANGER,  // 危险（<0.5m），急促报警
    WARNING, // 提醒（0.5-1m），温和提示
    SAFE    // 安全（>1m），不播报
}

/**
 * 避障预警信息（用于UI展示）
 */
data class ObstacleAlert(
    val level: AlertLevel,
    val description: String,
    val distance: Float,
    val direction: String,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 导航信息（用于UI展示）
 */
data class NavigationInfo(
    val instruction: String,
    val remainingDistance: Int,
    val remainingTime: Int,
    val timestamp: Long = System.currentTimeMillis()
)
