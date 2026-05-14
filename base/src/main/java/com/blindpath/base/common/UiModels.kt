package com.blindpath.base.common

/**
 * Alert level for obstacle warnings
 */
enum class AlertLevel(val displayName: String) {
    DANGER("危险"),
    WARNING("提醒"),
    SAFE("安全")
}

/**
 * Obstacle alert data
 */
data class ObstacleAlert(
    val level: AlertLevel,
    val description: String,
    val distance: Float,
    val direction: String
)

/**
 * Navigation info data
 */
data class NavigationInfo(
    val instruction: String,
    val remainingDistance: Int,
    val remainingTime: Int
)
