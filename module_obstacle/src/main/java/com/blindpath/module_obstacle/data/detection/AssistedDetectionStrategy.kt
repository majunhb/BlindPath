package com.blindpath.module_obstacle.data.detection

import android.graphics.Bitmap
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 辅助边缘检测策略 - 基于图像处理的轻量级检测
 *
 * 包装已有的 AssistedDetector，提供运动检测、边缘检测、
 * 墙壁检测、通用物体检测和 ML Kit 回退检测。
 * 辅助检测始终可用，作为 AI 检测失败后的第一降级选择。
 */
@Singleton
class AssistedDetectionStrategy @Inject constructor(
    private val assistedDetector: AssistedDetector
) : DetectionStrategy {

    override val name: String = "assisted_detector"

    override val isAvailable: Boolean = true  // 辅助检测始终可用

    override fun detect(bitmap: Bitmap): List<DetectedObstacle> {
        return assistedDetector.assistedDetect(bitmap)
    }
}
