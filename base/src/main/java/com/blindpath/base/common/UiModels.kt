package com.blindpath.base.common

/**
 * 预警级别
 */
enum class AlertLevel(val displayName: String) {
    DANGER("危险"),
    WARNING("提醒"),
    SAFE("安全")
}

/**
 * 障碍物预警信息（用于UI展示）
 */
data class ObstacleAlert(
    val level: AlertLevel,
    val description: String,
    val distance: Float,
    val direction: String
)

/**
 * 导航信息（用于UI展示）
 */
data class NavigationInfo(
    val instruction: String,
    val remainingDistance: Int,
    val remainingTime: Int
)
