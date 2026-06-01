package com.blindpath.module_indoor.domain.model

import com.blindpath.module_obstacle.domain.model.BoundingBox
import com.blindpath.module_obstacle.domain.model.Direction

/**
 * 房间类型枚举
 * 包含常见的室内房间类型
 */
enum class RoomType(val chineseName: String) {
    LIVING_ROOM("客厅"),
    BEDROOM("卧室"),
    KITCHEN("厨房"),
    BATHROOM("卫生间"),
    BALCONY("阳台"),
    HALLWAY("走廊"),
    STAIRS("楼梯间"),
    UNKNOWN("未知房间")
}

/**
 * 室内障碍物类型枚举
 * 包含常见的室内家具和障碍物
 */
enum class IndoorObstacleType(val chineseName: String, val priority: Int) {
    SOFA("沙发", 1),
    CHAIR("椅子", 1),
    TABLE("桌子", 2),
    BED("床", 1),
    CABINET("柜子", 2),
    DOOR("门", 3),
    WINDOW("窗户", 2),
    STAIRS("楼梯", 5), // 最高优先级
    TV("电视", 1),
    REFRIGERATOR("冰箱", 2),
    WASHING_MACHINE("洗衣机", 2),
    UNKNOWN("未知物品", 0);

    /**
     * 生成预警语音消息
     * 格式：方位 + 距离 + 物体名称
     */
    fun getAlertMessage(distance: Float, direction: Direction? = null): String {
        val distanceInt = distance.toInt()
        val directionPrefix = when (direction) {
            Direction.LEFT, Direction.LEFT_FRONT -> "左侧"
            Direction.RIGHT, Direction.RIGHT_FRONT -> "右侧"
            Direction.CENTER -> ""
            Direction.BACK -> "后方"
            null -> ""
        }

        return when (this) {
            SOFA -> "$directionPrefix${distanceInt}米处有沙发"
            CHAIR -> "$directionPrefix${distanceInt}米处有椅子"
            TABLE -> "$directionPrefix${distanceInt}米处有桌子"
            BED -> "$directionPrefix${distanceInt}米处有床"
            CABINET -> "$directionPrefix${distanceInt}米处有柜子"
            DOOR -> "$directionPrefix${distanceInt}米处有门"
            WINDOW -> "$directionPrefix${distanceInt}米处有窗户"
            STAIRS -> if (distance < 2f) "注意！前方${distanceInt}米处有楼梯" else "前方${distanceInt}米处有楼梯"
            TV -> "$directionPrefix${distanceInt}米处有电视"
            REFRIGERATOR -> "$directionPrefix${distanceInt}米处有冰箱"
            WASHING_MACHINE -> "$directionPrefix${distanceInt}米处有洗衣机"
            UNKNOWN -> "$directionPrefix${distanceInt}米处有物体"
        }
    }
}

/**
 * 检测到的室内障碍物
 */
data class DetectedIndoorObstacle(
    val type: IndoorObstacleType,
    val confidence: Float,
    val distance: Float, // 估算距离（米）
    val direction: Direction,
    val boundingBox: BoundingBox
)

/**
 * 室内场景识别结果
 */
data class IndoorScene(
    val roomType: RoomType,
    val confidence: Float,
    val obstacles: List<DetectedIndoorObstacle>,
    val timestamp: Long = System.currentTimeMillis()
) {
    /**
     * 获取场景进入播报消息
     */
    fun getEntryAnnouncement(): String {
        return when (roomType) {
            RoomType.LIVING_ROOM -> "进入客厅"
            RoomType.BEDROOM -> "进入卧室"
            RoomType.KITCHEN -> "进入厨房"
            RoomType.BATHROOM -> "进入卫生间"
            RoomType.BALCONY -> "进入阳台"
            RoomType.HALLWAY -> "进入走廊"
            RoomType.STAIRS -> "进入楼梯间，请注意安全"
            RoomType.UNKNOWN -> "进入房间"
        }
    }
}

/**
 * 室内检测状态
 */
data class IndoorDetectionState(
    val isRunning: Boolean = false,
    val isCameraReady: Boolean = false,
    val isModelLoaded: Boolean = false,
    val currentScene: IndoorScene? = null,
    val lastRoomType: RoomType? = null,
    val fps: Int = 0,
    val lastError: String? = null
)
