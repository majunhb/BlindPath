package com.blindpath.module_obstacle.domain.model

data class SensorData(
    val gpsAccuracy: Float = Float.MAX_VALUE,
    val lightLevel: Float = 0f,
    val accelerationMagnitude: Float = 0f,
    val isMoving: Boolean = false,
    val speed: Float = 0f
)

data class SceneInferenceResult(
    val mode: PerceptionMode,
    val confidence: Float,
    val description: String,
    val isStable: Boolean
)

class SceneInferenceEngine {
    private val GPS_WEIGHT = 0.35f
    private val LIGHT_WEIGHT = 0.25f
    private val MOTION_WEIGHT = 0.40f
    private val GPS_INDOOR_THRESHOLD = 30f
    private val LIGHT_INDOOR_THRESHOLD = 50f
    private val MOTION_THRESHOLD = 0.5f

    private val recentScores = ArrayDeque<Float>()
    private val MAX_SCORES = 5
    private var lastInferredMode: PerceptionMode = PerceptionMode.NAVIGATION
    private var stableCount = 0
    private val STABLE_THRESHOLD = 3

    fun infer(sensorData: SensorData): SceneInferenceResult {
        val gpsScore = when {
            sensorData.gpsAccuracy > GPS_INDOOR_THRESHOLD -> 0.1f
            sensorData.gpsAccuracy > 15f -> 0.4f
            sensorData.gpsAccuracy > 5f -> 0.7f
            else -> 1.0f
        }
        val lightScore = when {
            sensorData.lightLevel < LIGHT_INDOOR_THRESHOLD -> 0.1f
            sensorData.lightLevel < 200f -> 0.4f
            sensorData.lightLevel < 1000f -> 0.7f
            else -> 1.0f
        }
        val motionScore = when {
            sensorData.speed > 1.5f -> 1.0f
            sensorData.isMoving && sensorData.accelerationMagnitude > MOTION_THRESHOLD -> 0.8f
            sensorData.accelerationMagnitude > 0.2f -> 0.5f
            else -> 0.1f
        }
        val outdoorScore = gpsScore * GPS_WEIGHT + lightScore * LIGHT_WEIGHT + motionScore * MOTION_WEIGHT

        recentScores.addLast(outdoorScore)
        if (recentScores.size > MAX_SCORES) { recentScores.removeFirst() }
        val smoothedScore = recentScores.average().toFloat()

        val candidateMode = when {
            smoothedScore < 0.35f -> PerceptionMode.INDOOR
            smoothedScore < 0.55f -> PerceptionMode.SCENE
            else -> PerceptionMode.NAVIGATION
        }
        val description = when (candidateMode) {
            PerceptionMode.INDOOR -> "推断为室内场景"
            PerceptionMode.SCENE -> "推断为室外静态场景"
            PerceptionMode.NAVIGATION -> "推断为出行导航场景"
            PerceptionMode.AUTO -> "场景不确定"
        }

        val isStable: Boolean
        if (candidateMode == lastInferredMode) {
            stableCount++
            isStable = stableCount >= STABLE_THRESHOLD
        } else {
            stableCount = 0
            isStable = false
        }
        if (isStable) { lastInferredMode = candidateMode }

        return SceneInferenceResult(mode = lastInferredMode, confidence = smoothedScore, description = description, isStable = isStable)
    }

    fun reset() {
        recentScores.clear()
        lastInferredMode = PerceptionMode.NAVIGATION
        stableCount = 0
    }
}
