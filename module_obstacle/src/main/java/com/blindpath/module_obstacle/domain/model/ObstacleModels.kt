package com.blindpath.module_obstacle.domain.model

import com.blindpath.base.common.AlertLevel

/**
 * 障碍物类型枚举
 */
enum class ObstacleType(
    val chineseName: String,
    val severity: Int // 1=低, 2=中, 3=高
) {
    STEP_UP("上台阶", 2),
    STEP_DOWN("下台阶", 3),
    CURB("路沿", 2),
    PILLAR("石墩/柱子", 3),
    ELECTRIC_POLE("电线杆", 2),
    VEHICLE("非机动车", 3),
    OBSTACLE("障碍物", 3),
    PERSON("行人", 2),
    TRUCK("卡车", 3),
    PIT("坑洼", 3),
    UNKNOWN("未知", 1);

    fun getAlertMessage(distance: Float): String {
        return when (this) {
            STEP_UP -> "前方${distance.toInt()}米有台阶，请抬脚"
            STEP_DOWN -> "前方${distance.toInt()}米有下坡，注意安全"
            CURB -> "前方${distance.toInt()}米有路沿，小心绊倒"
            PILLAR -> "前方${distance.toInt()}米有石墩，请绕行"
            ELECTRIC_POLE -> "前方${distance.toInt()}米有电线杆"
            VEHICLE -> "注意，前方${distance.toInt()}米有车辆"
            TRUCK -> "注意，前方${distance.toInt()}米有卡车"
            PIT -> "注意，前方${distance.toInt()}米有坑洼"
            OBSTACLE -> "注意，前方${distance.toInt()}米有障碍物"
            PERSON -> "前方${distance.toInt()}米有行人"
            UNKNOWN -> "注意，前方${distance.toInt()}米有物体"
        }
    }
}

/**
 * 障碍物方向
 */
enum class Direction {
    LEFT,       // 左前方
    LEFT_FRONT, // 左侧
    FRONT_LEFT, // 正左方
    CENTER,     // 正前方
    RIGHT,      // 右前方
    RIGHT_FRONT,
    FRONT_RIGHT,
    BACK        // 后方（一般不播报）
}

/**
 * 检测到的障碍物
 */
data class DetectedObstacle(
    val type: ObstacleType,
    val confidence: Float,        // 置信度 0-1
    val distance: Float,         // 距离（米）
    val direction: Direction,    // 方向
    val boundingBox: BoundingBox, // 包围框（用于调试显示）
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 包围框
 */
data class BoundingBox(
    val left: Float,   // 0-1 相对坐标
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val centerX: Float get() = (left + right) / 2
    val centerY: Float get() = (top + bottom) / 2
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

/**
 * 预警信息
 */
data class AlertInfo(
    val level: AlertLevel,
    val obstacle: DetectedObstacle,
    val message: String,
    val isVoiceEnabled: Boolean = true,
    val isVibrationEnabled: Boolean = true,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 避障模块状态
 */
data class ObstacleState(
    val isRunning: Boolean = false,
    val isCameraReady: Boolean = false,
    val isModelLoaded: Boolean = false,
    val currentAlert: com.blindpath.base.common.ObstacleAlert? = null,
    val detectedObstacles: List<DetectedObstacle> = emptyList(),
    val fps: Int = 0,
    val lastError: String? = null
)
