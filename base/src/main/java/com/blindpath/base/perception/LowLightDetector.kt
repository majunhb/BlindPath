package com.blindpath.base.perception

import android.graphics.Bitmap
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * 弱光检测器 - PRD V2.0 第二期
 *
 * 分析相机帧亮度，检测弱光环境并触发屏幕补光。
 *
 * 检测原理：
 * 1. 对帧进行降采样（1/4）计算平均亮度
 * 2. 与阈值比较判断是否为弱光环境
 * 3. 使用滑动窗口平滑，避免闪烁误判
 * 4. 连续N帧确认后才切换状态
 *
 * 补光策略：
 * - 弱光检测到 → 屏幕最大亮度 + 白屏补光面板
 * - 恢复正常 → 恢复原始亮度 + 关闭补光面板
 * - 补光持续时间与导航状态绑定，导航中持续补光
 *
 * 阈值设计：
 * - 弱光阈值：平均亮度 < 40（0-255范围）
 * - 正常阈值：平均亮度 > 60（滞后区间防止抖动）
 * - 连续确认帧数：5帧（约1-2秒@3-5fps检测帧率）
 */
@Singleton
class LowLightDetector @Inject constructor() {

    data class LowLightState(
        val isLowLight: Boolean = false,
        val averageBrightness: Float = 128f,
        val shouldEnableScreenLight: Boolean = false,
        val confidence: Float = 0f
    )

    // ==================== 阈值配置 ====================

    /** 弱光判定阈值（0-255，低于此值判定为弱光）*/
    var lowLightThreshold: Int = 40

    /** 恢复正常阈值（高于此值判定为正常光线，需大于 lowLightThreshold）*/
    var normalLightThreshold: Int = 60

    /** 连续确认帧数（避免闪烁误判）*/
    var confirmationFrames: Int = 5

    /** 降采样步长（越大越快，但精度降低）*/
    var sampleStep: Int = 4

    // ==================== 内部状态 ====================

    private var consecutiveLowLightFrames = 0
    private var consecutiveNormalFrames = 0
    private var currentState = LowLightState()
    private val brightnessHistory = mutableListOf<Float>()
    private val historyWindowSize = 10

    /**
     * 检测当前帧是否处于弱光环境
     *
     * @param bitmap 相机帧（ARGB_8888）
     * @return LowLightState 当前弱光状态
     */
    fun detect(bitmap: Bitmap): LowLightState {
        try {
            val width = bitmap.width
            val height = bitmap.height

            // 降采样计算亮度
            var totalBrightness = 0L
            var pixelCount = 0

            for (y in 0 until height step sampleStep) {
                for (x in 0 until width step sampleStep) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = android.graphics.Color.red(pixel)
                    val g = android.graphics.Color.green(pixel)
                    val b = android.graphics.Color.blue(pixel)

                    // ITU-R BT.601 亮度公式
                    val brightness = 0.299 * r + 0.587 * g + 0.114 * b
                    totalBrightness += brightness.toInt()
                    pixelCount++
                }
            }

            val averageBrightness = if (pixelCount > 0) {
                totalBrightness.toFloat() / pixelCount
            } else {
                128f
            }

            // 滑动窗口平滑
            brightnessHistory.add(averageBrightness)
            if (brightnessHistory.size > historyWindowSize) {
                brightnessHistory.removeAt(0)
            }
            val smoothedBrightness = brightnessHistory.average().toFloat()

            return evaluateState(smoothedBrightness)

        } catch (e: Exception) {
            Timber.e(e, "LowLightDetector: detection failed")
            return currentState
        }
    }

    /**
     * 评估弱光状态
     */
    private fun evaluateState(brightness: Float): LowLightState {
        val wasLowLight = currentState.isLowLight

        when {
            // 弱光判定（滞后区间下方）
            brightness < lowLightThreshold -> {
                consecutiveLowLightFrames++
                consecutiveNormalFrames = 0
            }
            // 正常光线（滞后区间上方）
            brightness > normalLightThreshold -> {
                consecutiveNormalFrames++
                consecutiveLowLightFrames = 0
            }
            // 滞后区间内 → 不改变计数，维持当前状态
            else -> {
                // 缓慢衰减两个计数
                consecutiveLowLightFrames = max(0, consecutiveLowLightFrames - 1)
                consecutiveNormalFrames = max(0, consecutiveNormalFrames - 1)
            }
        }

        val isLowLight = when {
            // 从正常→弱光：需要连续确认
            !wasLowLight && consecutiveLowLightFrames >= confirmationFrames -> true
            // 从弱光→正常：也需要连续确认（避免闪烁）
            wasLowLight && consecutiveNormalFrames >= confirmationFrames -> false
            // 其他情况保持当前状态
            else -> wasLowLight
        }

        val shouldEnableScreenLight = isLowLight

        // 置信度：基于亮度偏离阈值的程度
        val confidence = if (isLowLight) {
            1f - (brightness / lowLightThreshold.toFloat()).coerceIn(0f, 1f)
        } else {
            ((brightness - normalLightThreshold) / (255f - normalLightThreshold)).coerceIn(0f, 1f)
        }

        currentState = LowLightState(
            isLowLight = isLowLight,
            averageBrightness = brightness,
            shouldEnableScreenLight = shouldEnableScreenLight,
            confidence = confidence
        )

        Timber.d(
            "LowLightDetector: brightness=%.1f, isLowLight=%b, confidence=%.2f",
            brightness, isLowLight, confidence
        )

        return currentState
    }

    /**
     * 重置检测状态
     */
    fun reset() {
        consecutiveLowLightFrames = 0
        consecutiveNormalFrames = 0
        currentState = LowLightState()
        brightnessHistory.clear()
    }
}
