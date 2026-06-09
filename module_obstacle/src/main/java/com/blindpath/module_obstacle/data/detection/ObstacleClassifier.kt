package com.blindpath.module_obstacle.data.detection

import com.blindpath.base.config.AppConfig
import com.blindpath.module_obstacle.domain.model.Direction
import com.blindpath.module_obstacle.domain.model.ObstacleType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 障碍物分类器 - 负责COCO标签到障碍物类型的映射、距离估算和方向判定
 *
 * 从 AIDetector 拆分出的单一职责组件：
 * - COCO 80类 -> ObstacleType 映射
 * - COCO 中文名称映射
 * - 障碍物已知高度表
 * - 三种感知模式的白名单
 * - 距离估算与方向判定
 * - 所有阈值常量
 */
@Singleton
class ObstacleClassifier @Inject constructor() {

    // ============ COCO 80类映射到障碍物类型 ============
    private val cocoToObstacle = mapOf(
        0 to ObstacleType.PERSON,
        1 to ObstacleType.BICYCLE, 2 to ObstacleType.VEHICLE, 3 to ObstacleType.MOTORCYCLE,
        5 to ObstacleType.BUS, 7 to ObstacleType.TRUCK,
        9 to ObstacleType.TRAFFIC_LIGHT, 10 to ObstacleType.TRAFFIC_SIGN, 11 to ObstacleType.TRAFFIC_SIGN,
        13 to ObstacleType.BENCH,
        15 to ObstacleType.CAT, 16 to ObstacleType.DOG,
        24 to ObstacleType.BACKPACK, 25 to ObstacleType.UMBRELLA, 26 to ObstacleType.HANDBAG,
        28 to ObstacleType.SUITCASE,
        32 to ObstacleType.SPORTS_BALL,
        36 to ObstacleType.SKATEBOARD,
        39 to ObstacleType.BOTTLE, 40 to ObstacleType.WINE_GLASS, 41 to ObstacleType.CUP,
        42 to ObstacleType.FORK, 43 to ObstacleType.KNIFE, 44 to ObstacleType.SPOON, 45 to ObstacleType.BOWL,
        46 to ObstacleType.BANANA, 47 to ObstacleType.APPLE,
        56 to ObstacleType.CHAIR, 57 to ObstacleType.SOFA, 58 to ObstacleType.POTTED_PLANT,
        59 to ObstacleType.BED, 60 to ObstacleType.TABLE,
        61 to ObstacleType.SINK, 62 to ObstacleType.TV,
        63 to ObstacleType.LAPTOP, 64 to ObstacleType.MOUSE_DEVICE, 65 to ObstacleType.REMOTE,
        66 to ObstacleType.KEYBOARD, 67 to ObstacleType.PHONE,
        68 to ObstacleType.MICROWAVE, 69 to ObstacleType.OVEN, 70 to ObstacleType.TOASTER,
        71 to ObstacleType.SINK, 72 to ObstacleType.REFRIGERATOR,
        73 to ObstacleType.BOOK, 74 to ObstacleType.CLOCK, 75 to ObstacleType.VASE,
        76 to ObstacleType.SCISSORS, 77 to ObstacleType.TEDDY_BEAR, 78 to ObstacleType.HAIR_DRYER,
        79 to ObstacleType.TOOTHBRUSH
    )

    // COCO中文名称（cocoLabelId -> 中文名）
    private val cocoChineseNames = mapOf(
        0 to "人", 1 to "自行车", 2 to "汽车", 3 to "摩托车",
        5 to "公交车", 7 to "卡车",
        9 to "红绿灯", 10 to "消防栓", 11 to "停车标志", 13 to "长椅",
        15 to "猫", 16 to "狗",
        24 to "背包", 25 to "雨伞", 26 to "手提包", 28 to "行李箱",
        32 to "运动球", 36 to "滑板",
        39 to "瓶子", 40 to "酒杯", 41 to "杯子", 42 to "叉子", 43 to "刀",
        44 to "勺子", 45 to "碗", 46 to "香蕉", 47 to "苹果",
        56 to "椅子", 57 to "沙发", 58 to "盆栽", 59 to "床", 60 to "餐桌",
        61 to "马桶", 62 to "电视",
        63 to "笔记本", 64 to "鼠标", 65 to "遥控器", 66 to "键盘", 67 to "手机",
        68 to "微波炉", 69 to "烤箱", 70 to "烤面包机", 71 to "水槽", 72 to "冰箱",
        73 to "书", 74 to "时钟", 75 to "花瓶", 76 to "剪刀", 77 to "玩具熊",
        78 to "吹风机", 79 to "牙刷"
    )

    // 障碍物已知高度（米）- 用于单目测距
    private val obstacleKnownHeights = mapOf(
        ObstacleType.PERSON to 1.7f,
        ObstacleType.VEHICLE to 1.5f, ObstacleType.BUS to 3.0f, ObstacleType.TRUCK to 2.5f,
        ObstacleType.MOTORCYCLE to 1.2f, ObstacleType.BICYCLE to 1.3f,
        ObstacleType.TRAFFIC_LIGHT to 0.6f, ObstacleType.TRAFFIC_SIGN to 0.6f,
        ObstacleType.PILLAR to 0.3f, ObstacleType.BENCH to 0.8f,
        ObstacleType.STEP_UP to 0.2f, ObstacleType.STEP_DOWN to 0.2f,
        ObstacleType.STAIRS to 0.18f, ObstacleType.CURB to 0.15f,
        ObstacleType.CHAIR to 0.9f, ObstacleType.SOFA to 0.8f,
        ObstacleType.POTTED_PLANT to 0.5f, ObstacleType.BED to 0.5f, ObstacleType.TABLE to 0.75f,
        ObstacleType.CAT to 0.3f, ObstacleType.DOG to 0.5f,
        ObstacleType.REFRIGERATOR to 1.8f, ObstacleType.TV to 0.6f,
        ObstacleType.BOOK to 0.03f, ObstacleType.VASE to 0.4f, ObstacleType.CLOCK to 0.3f
    )

    // COCO标签 -> ObstacleType 的反向查找表（用于 getChineseName）
    private val obstacleTypeToChineseName: Map<ObstacleType, String> by lazy {
        val map = mutableMapOf<ObstacleType, String>()
        for ((cocoId, obstacleType) in cocoToObstacle) {
            val name = cocoChineseNames[cocoId]
            if (name != null && obstacleType !in map) {
                map[obstacleType] = name
            }
        }
        map
    }

    var calibratedFocalLength: Float? = null

    companion object {
        val DANGER_DISTANCE = AppConfig.ObstacleAlert.DANGER_DISTANCE
        val WARNING_DISTANCE = AppConfig.ObstacleAlert.WARNING_DISTANCE
        const val CONF_DANGER = 0.28f
        const val CONF_WARNING = 0.45f
        const val CONF_IGNORE = 0.7f

        val INDOOR_WHITELIST = setOf(
            ObstacleType.STEP_UP, ObstacleType.STEP_DOWN, ObstacleType.STAIRS,
            ObstacleType.PIT, ObstacleType.PUDDLE,
            ObstacleType.CHAIR, ObstacleType.TABLE, ObstacleType.SOFA,
            ObstacleType.BED, ObstacleType.POTTED_PLANT,
            ObstacleType.SINK, ObstacleType.REFRIGERATOR,
            ObstacleType.MICROWAVE, ObstacleType.OVEN, ObstacleType.TOASTER,
            ObstacleType.PHONE, ObstacleType.BACKPACK, ObstacleType.CUP,
            ObstacleType.BOTTLE, ObstacleType.BOOK, ObstacleType.KEYBOARD,
            ObstacleType.MOUSE_DEVICE, ObstacleType.REMOTE,
            ObstacleType.CAT, ObstacleType.DOG,
            ObstacleType.PERSON, ObstacleType.OBSTACLE
        )

        val NAVIGATION_WHITELIST = setOf(
            ObstacleType.TRAFFIC_LIGHT, ObstacleType.TRAFFIC_SIGN, ObstacleType.ZEBRA_CROSSING,
            ObstacleType.PERSON, ObstacleType.BICYCLE, ObstacleType.MOTORCYCLE,
            ObstacleType.VEHICLE, ObstacleType.BUS, ObstacleType.TRUCK,
            ObstacleType.CURB, ObstacleType.PUDDLE, ObstacleType.MANHOLE, ObstacleType.PIT,
            ObstacleType.PILLAR, ObstacleType.ELECTRIC_POLE, ObstacleType.BENCH,
            ObstacleType.HANDRAIL, ObstacleType.OBSTACLE
        )

        val SCENE_WHITELIST = setOf(
            ObstacleType.BENCH, ObstacleType.HANDRAIL,
            ObstacleType.TRAFFIC_SIGN,
            ObstacleType.PERSON, ObstacleType.VEHICLE,
            ObstacleType.OBSTACLE
        )
    }

    fun classifyByCocoId(cocoId: Int): ObstacleType? = cocoToObstacle[cocoId]

    fun getChineseName(type: ObstacleType): String =
        obstacleTypeToChineseName[type] ?: type.chineseName

    fun getKnownHeight(type: ObstacleType): Float = obstacleKnownHeights[type] ?: 1.0f

    fun estimateDistance(type: ObstacleType, pixelHeight: Float, imageHeight: Float): Float {
        val knownHeight = obstacleKnownHeights[type] ?: 1.0f
        val focalLength = calibratedFocalLength ?: 800f
        val distance = if (pixelHeight > 0) knownHeight * focalLength / pixelHeight else 10f

        return when (type) {
            ObstacleType.STEP_UP, ObstacleType.STEP_DOWN, ObstacleType.CURB, ObstacleType.PIT ->
                distance.coerceIn(0.3f, 5f)
            ObstacleType.PERSON ->
                distance.coerceIn(0.5f, 15f)
            ObstacleType.VEHICLE, ObstacleType.BUS, ObstacleType.TRUCK ->
                distance.coerceIn(1f, 30f)
            ObstacleType.TRAFFIC_LIGHT ->
                distance.coerceIn(1f, 50f)
            else ->
                distance.coerceIn(0.3f, 10f)
        }
    }

    fun calculateDirection(centerX: Float, imageWidth: Float): Direction {
        val ratio = centerX / imageWidth
        return when {
            ratio < 0.33f -> Direction.LEFT
            ratio < 0.66f -> Direction.CENTER
            else -> Direction.RIGHT
        }
    }
}