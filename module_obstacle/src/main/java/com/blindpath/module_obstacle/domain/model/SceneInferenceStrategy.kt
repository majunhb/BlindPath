package com.blindpath.module_obstacle.domain.model

/**
 * 场景推断策略 - 基于传感器数据自动判断最合适的感知模式
 *
 * 判断逻辑：
 * 1. GPS信号强度 -> 弱信号倾向室内，强信号倾向室外
 * 2. 光线传感器 -> 昏暗倾向室内，明亮倾向室外
 * 3. 加速度计 -> 静止倾向室内，行走/运动倾向导航
 * 4. 综合评分 -> 加权决策
 */
data class SensorData(
    val gpsAccuracy: Float = Float.MAX_VALUE,  // GPS精度（米），越小越精确
    val lightLevel: Float = 0f,              // 光线强度（lux）
    val accelerationMagnitude: Float = 0f,   // 加速度大小
    val isMoving: Boolean = false,            // 是否在移动
    val speed: Float = 0f                     // 移动速度（m/s）
)

enum class InferredScene(
    val mode: PerceptionMode,
    val confidence: Float,
    val description: String
) {
    INDOOR_SCENE(PerceptionMode.INDOOR, 1.0f, "推断为室内场景"),
    OUTDOOR_STATIC(PerceptionMode.SCENE, 0.8f, "推断为室外静态场景"),
    OUTDOOR_MOVING(PerceptionMode.NAVIGATION, 1.0f, "推断为出行导航场景"),
    UNCERTAIN(PerceptionMode.NAVIGATION, 0.5f, "场景不确定，默认导航模式")
}

class SceneInferenceEngine {

    // 权重配置
    private val GPS_WEIGHT = 0.35f
    private val LIGHT_WEIGHT = 0.25f
    private val MOTION_WEIGHT = 0.40f

    // 阈值配置
    private val GPS_INDOOR_THRESHOLD = 30f      // GPS精度>30m 认为可能在室内
    private val LIGHT_INDOOR_THRESHOLD = 50f     // 光线<50lux 认为可能在室内
    private val MOTION_THRESHOLD = 0.5f          // 加速度>0.5 认为在移动

    // 平滑滤波
    private val recentScores = ArrayDeque<Float>(maxSize = 5)
    private var lastInferredMode: PerceptionMode = PerceptionMode.NAVIGATION
    private var stableCount = 0
    private val STABLE_THRESHOLD = 3  // 连续3次相同推断才切换

    /**
     * 基于传感器数据推断场景
     */
    fun infer(sensorData: SensorData): InferredScene {
        // 1. GPS评分（0=室内，1=室外）
        val gpsScore = when {
            sensorData.gpsAccuracy > GPS_INDOOR_THRESHOLD -> 0.1f  // 很可能在室内
            sensorData.gpsAccuracy > 15f -> 0.4f                  // 可能室外
            sensorData.gpsAccuracy > 5f -> 0.7f                   // 室外
            else -> 1.0f                                          // 明确室外
        }

        // 2. 光线评分（0=室内，1=室外）
        val lightScore = when {
            sensorData.lightLevel < LIGHT_INDOOR_THRESHOLD -> 0.1f  // 昏暗=室内
            sensorData.lightLevel < 200f -> 0.4f                     // 中等
            sensorData.lightLevel < 1000f -> 0.7f                   // 明亮
            else -> 1.0f                                            // 很亮=室外
        }

        // 3. 运动评分（0=静止=室内，1=运动=导航）
        val motionScore = when {
            sensorData.speed > 1.5f -> 1.0f                        // 快速移动=导航
            sensorData.isMoving && sensorData.accelerationMagnitude > MOTION_THRESHOLD -> 0.8f  // 行走
            sensorData.accelerationMagnitude > 0.2f -> 0.5f         // 轻微移动
            else -> 0.1f                                            // 静止
        }

        // 加权综合评分（0=室内，1=室外导航）
        val outdoorScore = gpsScore * GPS_WEIGHT +
                          lightScore * LIGHT_WEIGHT +
                          motionScore * MOTION_WEIGHT

        // 平滑滤波
        recentScores.addLast(outdoorScore)
        val smoothedScore = recentScores.average().toFloat()

        // 场景判断
        val inferred = when {
            smoothedScore < 0.35f -> InferredScene.INDOOR_SCENE
            smoothedScore < 0.55f -> InferredScene.OUTDOOR_STATIC
            smoothedScore >= 0.55f -> InferredScene.OUTDOOR_MOVING
            else -> InferredScene.UNCERTAIN
        }

        // 稳定性检查（避免频繁切换）
        if (inferred.mode == lastInferredMode) {
            stableCount++
        } else {
            stableCount = 0
        }

        if (stableCount >= STABLE_THRESHOLD) {
            lastInferredMode = inferred.mode
        }

        return InferredScene(
            mode = lastInferredMode,
            confidence = smoothedScore,
            description = inferred.description
        )
    }

    /**
     * 重置推断状态
     */
    fun reset() {
        recentScores.clear()
        lastInferredMode = PerceptionMode.NAVIGATION
        stableCount = 0
    }
}
