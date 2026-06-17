package com.blindpath.module_obstacle.data.detection

import com.blindpath.base.navigation.model.TrafficLightState
import android.graphics.Bitmap
import android.graphics.Color
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * 红绿灯状态分类器
 *
 * 两阶段检测流程：
 * 1. YOLO 检测红绿灯位置（DetectedObstacle.type == TRAFFIC_LIGHT）
 * 2. 对检测框内区域进行颜色直方图分类（红/绿/黄）
 *
 * 分类策略：
 * - 颜色直方图分析：统计红色/绿色/黄色像素占比
 * - 亮度归一化：处理过曝/欠曝场景
 * - 时序滤波：连续帧确认颜色，避免闪烁误判
 */
@Singleton
class TrafficLightClassifier @Inject constructor() {

    // 颜色判定阈值
    private val redDominance = 0.4f     // 红色像素占比 > 40% 判定为红灯
    private val greenDominance = 0.35f  // 绿色像素占比 > 35% 判定为绿灯
    private val yellowDominance = 0.3f  // 黄色像素占比 > 30% 判定为黄灯

    // 时序滤波
    private var consecutiveState = TrafficLightState.UNKNOWN
    private var consecutiveCount = 0
    private val minConsecutiveFrames = 3

    // 百分比阈值（相对亮度）
    private val minBrightness = 80 // 最低亮度阈值，过滤暗区

    /**
     * 对红绿灯检测框区域进行分类
     *
     * @param bitmap 相机帧
     * @param left 检测框左边界 (0-1 相对坐标)
     * @param top 检测框上边界 (0-1)
     * @param right 检测框右边界 (0-1)
     * @param bottom 检测框下边界 (0-1)
     * @return 红绿灯状态
     */
    fun classify(bitmap: Bitmap, left: Float, top: Float, right: Float, bottom: Float): TrafficLightState {
        try {
            val width = bitmap.width
            val height = bitmap.height

            // 扩展检测区域（红绿灯通常比检测框稍大）
            val margin = 0.05f
            val roiLeft = ((left - margin).coerceAtLeast(0f) * width).toInt()
            val roiTop = ((top - margin).coerceAtLeast(0f) * height).toInt()
            val roiRight = ((right + margin).coerceAtMost(1f) * width).toInt()
            val roiBottom = ((bottom + margin).coerceAtMost(1f) * height).toInt()

            if (roiRight <= roiLeft || roiBottom <= roiTop) {
                return TrafficLightState.UNKNOWN
            }

            var redCount = 0
            var greenCount = 0
            var yellowCount = 0
            var totalPixels = 0

            val step = 2 // 采样步长

            for (y in roiTop until roiBottom step step) {
                for (x in roiLeft until roiRight step step) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val brightness = (r + g + b) / 3

                    // 过滤暗区
                    if (brightness < minBrightness) continue

                    totalPixels++

                    // 红色判定：R 明显高于 G 和 B
                    if (r > g * 1.4f && r > b * 1.4f && r > 100) {
                        redCount++
                    }
                    // 绿色判定：G 明显高于 R 和 B
                    else if (g > r * 1.3f && g > b * 1.3f && g > 80) {
                        greenCount++
                    }
                    // 黄色判定：R 和 G 都高，B 低
                    else if (r > 150 && g > 120 && b < 100 && abs(r - g) < 80) {
                        yellowCount++
                    }
                }
            }

            if (totalPixels < 10) {
                return TrafficLightState.UNKNOWN
            }

            val redRatio = redCount.toFloat() / totalPixels
            val greenRatio = greenCount.toFloat() / totalPixels
            val yellowRatio = yellowCount.toFloat() / totalPixels

            // 确定当前状态
            val currentState = when {
                redRatio > redDominance && redRatio > greenRatio -> TrafficLightState.RED
                greenRatio > greenDominance && greenRatio > redRatio -> TrafficLightState.GREEN
                yellowRatio > yellowDominance -> TrafficLightState.YELLOW
                else -> TrafficLightState.UNKNOWN
            }

            // 时序滤波
            if (currentState == consecutiveState) {
                consecutiveCount++
            } else {
                consecutiveState = currentState
                consecutiveCount = 1
            }

            if (consecutiveCount < minConsecutiveFrames) {
                return TrafficLightState.UNKNOWN
            }

            Timber.d(
                "TrafficLight: state=$currentState (R:%.0f%%, G:%.0f%%, Y:%.0f%%)",
                redRatio * 100, greenRatio * 100, yellowRatio * 100
            )

            return currentState

        } catch (e: Exception) {
            Timber.e(e, "TrafficLight classification failed")
            return TrafficLightState.UNKNOWN
        }
    }

    /**
     * 重置状态
     */
    fun reset() {
        consecutiveState = TrafficLightState.UNKNOWN
        consecutiveCount = 0
    }
}