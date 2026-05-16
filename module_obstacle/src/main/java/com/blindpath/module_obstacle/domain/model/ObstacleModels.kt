package com.blindpath.module_obstacle.domain.model

import com.blindpath.base.common.AlertLevel

/**
 * 障碍物类型枚举
 * 包含视障用户日常生活中常见的各类障碍物和道路元素
 */
enum class ObstacleType(
    val chineseName: String,
    val severity: Int, // 1=低, 2=中, 3=高
    val voicePriority: Int // 播报优先级，数字越小优先级越高
) {
    // ============ 地面障碍物 ============
    STEP_UP("上台阶", 2, 1),
    STEP_DOWN("下台阶", 3, 1),
    STAIRS("楼梯", 3, 1),
    CURB("路沿", 2, 3),
    PUDDLE("水坑", 2, 4),
    MANHOLE("井盖", 2, 5),
    PIT("坑洼", 3, 2),
    ZEBRA_CROSSING("斑马线", 1, 10), // 低危险但需提示

    // ============ 交通工具 ============
    VEHICLE("车辆", 3, 1),
    BUS("公交车", 3, 1),
    TRUCK("卡车", 3, 1),
    BICYCLE("自行车", 2, 4),
    MOTORCYCLE("摩托车", 2, 3),

    // ============ 道路用户 ============
    PERSON("行人", 2, 5),

    // ============ 街道设施 ============
    PILLAR("石墩/柱子", 3, 2),
    ELECTRIC_POLE("电线杆", 2, 4),
    TRAFFIC_LIGHT("红绿灯", 2, 3),
    TRAFFIC_SIGN("交通标志", 1, 7),
    BENCH("长椅", 1, 7),
    HANDRAIL("扶手", 1, 6),

    // ============ 家居物品（可能阻挡路径） ============
    CHAIR("椅子", 2, 6),
    SOFA("沙发", 1, 7),
    TABLE("桌子", 2, 6),
    BED("床", 1, 7),
    POTTTED_PLANT("盆栽", 1, 7),

    // ============ 个人物品 ============
    BACKPACK("背包", 1, 8),
    HANDBAG("手提包", 1, 9),
    UMBRELLA("雨伞", 1, 9),
    SUITCASE("行李箱", 2, 6),
    BOTTLE("瓶子", 1, 9),

    // ============ 电子设备 ============
    LAPTOP("笔记本电脑", 1, 8),
    PHONE("手机", 1, 9),

    // ============ 通用障碍物 ============
    OBSTACLE("障碍物", 3, 2),
    UNKNOWN("未知物体", 1, 9);

    /**
     * 生成预警语音消息
     * 格式：方位 + 距离 + 物体名称 + 动作建议
     */
    fun getAlertMessage(distance: Float, direction: Direction? = null): String {
        val distanceInt = distance.toInt()
        val directionPrefix = when (direction) {
            Direction.LEFT, Direction.LEFT_FRONT, Direction.FRONT_LEFT -> "左侧"
            Direction.RIGHT, Direction.RIGHT_FRONT, Direction.FRONT_RIGHT -> "右侧"
            Direction.CENTER -> ""
            Direction.FRONT -> ""
            Direction.BACK -> "后方"
            null -> ""
        }

        return when (this) {
            // 台阶类 - 需要特别提醒抬脚或注意落差
            STEP_UP -> if (distance < 1.5f) "前方${distanceInt}米有台阶，请抬脚" else "注意，前方${distanceInt}米有上台阶"
            STEP_DOWN -> if (distance < 1.5f) "前方${distanceInt}米有台阶，注意落差" else "注意，前方${distanceInt}米有下台阶"
            STAIRS -> if (distance < 2f) "前方${distanceInt}米有楼梯，请注意" else "注意，前方${distanceInt}米有楼梯"

            // 地面障碍物
            CURB -> if (distance < 1f) "小心，路沿就在脚下" else "前方${distanceInt}米有路沿"
            PUDDLE -> if (distance < 1.5f) "前方${distanceInt}米有水坑，请绕行" else "注意，前方${distanceInt}米有水坑"
            MANHOLE -> if (distance < 1.5f) "前方${distanceInt}米有井盖" else "注意，前方${distanceInt}米有井盖"
            PIT -> if (distance < 1f) "危险，前方有坑洼" else "注意，前方${distanceInt}米有坑洼"
            ZEBRA_CROSSING -> "斑马线"

            // 悬空/侧面障碍物
            PILLAR -> "$directionPrefix${distanceInt}米有石墩，请绕行"
            ELECTRIC_POLE -> "$directionPrefix${distanceInt}米有电线杆"
            TRAFFIC_LIGHT -> when {
                distance < 3f -> "红绿灯，前方${distanceInt}米"
                else -> "注意，前方有红绿灯"
            }
            TRAFFIC_SIGN -> "注意，前方${distanceInt}米有交通标志"
            BENCH -> "$directionPrefix${distanceInt}米有长椅"
            HANDRAIL -> "$directionPrefix${distanceInt}米有扶手"

            // 交通工具
            VEHICLE -> if (distance < 2f) "注意，前方${distanceInt}米有车辆" else "远处有车辆"
            BUS -> if (distance < 3f) "注意，前方${distanceInt}米有公交车" else "远处有公交车"
            TRUCK -> if (distance < 3f) "注意，前方${distanceInt}米有卡车" else "远处有卡车"
            BICYCLE -> "注意，前方${distanceInt}米有自行车"
            MOTORCYCLE -> "注意，前方${distanceInt}米有摩托车"

            // 移动物体 - 需要特别提醒
            PERSON -> if (distance < 1.5f) "前方${distanceInt}米有行人" else "注意，前方有行人"

            // 家居物品
            CHAIR -> "$directionPrefix${distanceInt}米有椅子"
            SOFA -> "$directionPrefix${distanceInt}米有沙发"
            TABLE -> "$directionPrefix${distanceInt}米有桌子"
            BED -> "$directionPrefix${distanceInt}米有床"
            POTTTED_PLANT -> "$directionPrefix${distanceInt}米有盆栽"

            // 个人物品
            BACKPACK -> "注意，前方${distanceInt}米有背包"
            HANDBAG -> "注意，前方${distanceInt}米有手提包"
            UMBRELLA -> "注意，前方${distanceInt}米有雨伞"
            SUITCASE -> "注意，前方${distanceInt}米有行李箱"
            BOTTLE -> "注意，前方${distanceInt}米有瓶子"

            // 电子设备
            LAPTOP -> "注意，前方${distanceInt}米有笔记本电脑"
            PHONE -> "注意，前方${distanceInt}米有手机"

            // 通用
            OBSTACLE -> "注意，前方${distanceInt}米有障碍物"
            UNKNOWN -> "注意，前方${distanceInt}米有物体"
        }
    }

    /**
     * 获取危险级别描述
     */
    fun getSeverityDescription(): String {
        return when (severity) {
            3 -> "高危"
            2 -> "中危"
            else -> "低危"
        }
    }
}

/**
 * 障碍物方向
 */
enum class Direction {
    LEFT,       // 左侧
    LEFT_FRONT, // 左前方
    FRONT_LEFT, // 左前方
    CENTER,     // 正前方
    FRONT,      // 正前方
    FRONT_RIGHT,// 右前方
    RIGHT_FRONT,// 右前方
    RIGHT,      // 右侧
    BACK;       // 后方

    /**
     * 获取方位的中文描述
     */
    fun getChineseName(): String {
        return when (this) {
            LEFT -> "左侧"
            LEFT_FRONT -> "左前方"
            FRONT_LEFT -> "左前方"
            CENTER -> "正前方"
            FRONT -> "正前方"
            FRONT_RIGHT -> "右前方"
            RIGHT_FRONT -> "右前方"
            RIGHT -> "右侧"
            BACK -> "后方"
        }
    }
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
    val timestamp: Long = System.currentTimeMillis(),
    val sceneContext: String? = null // 场景上下文
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
 * 场景类型
 */
enum class SceneType(
    val chineseName: String,
    val description: String
) {
    SIDEWALK("人行道", "人行道区域"),
    CROSSWALK("斑马线", "前方有斑马线"),
    STAIR_ENTRANCE("楼梯口", "楼梯入口区域"),
    INTERSECTION("路口", "十字路口或丁字路口"),
    ROAD("普通道路", "普通道路区域"),
    BUILDING_ENTRANCE("建筑物入口", "建筑物入口区域"),
    PARK("公园/绿地", "公园或绿化区域"),
    UNKNOWN("未知", "未识别场景");

    /**
     * 获取场景进入提示
     */
    fun getEntryAnnouncement(): String {
        return when (this) {
            SIDEWALK -> "进入人行道区域"
            CROSSWALK -> "前方斑马线，请注意过往车辆"
            STAIR_ENTRANCE -> "前方楼梯口，请注意台阶"
            INTERSECTION -> "前方路口，请注意交通信号"
            BUILDING_ENTRANCE -> "建筑物入口，请注意"
            PARK -> "进入公园区域"
            ROAD -> ""
            UNKNOWN -> ""
        }
    }
}

/**
 * 场景识别结果
 */
data class SceneRecognitionResult(
    val sceneType: SceneType,
    val confidence: Float,
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
    val sceneRecognition: SceneRecognitionResult? = null,
    val fps: Int = 0,
    val lastError: String? = null
)
