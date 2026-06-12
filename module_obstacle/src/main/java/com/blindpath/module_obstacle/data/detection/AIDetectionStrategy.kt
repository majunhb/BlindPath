package com.blindpath.module_obstacle.data.detection

import android.graphics.Bitmap
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import kotlinx.coroutines.runBlocking
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AI 主检测策略 - YOLOv8 TFLite
 *
 * 包装已有的 AIDetector，当 TFLite 模型已加载时提供高精度检测。
 * 模型加载/卸载状态通过 markAvailable/markUnavailable 控制。
 */
@Singleton
class AIDetectionStrategy @Inject constructor(
    private val aiDetector: AIDetector
) : DetectionStrategy {

    override val name: String = "ai_detector"

    override var isAvailable: Boolean = false
        private set

    /**
     * 模型加载成功后调用
     */
    fun markAvailable() {
        isAvailable = true
        Timber.i("AI detection strategy marked as available")
    }

    /**
     * 模型卸载时调用
     */
    fun markUnavailable() {
        isAvailable = false
        Timber.w("AI detection strategy marked as unavailable")
    }

    override fun detect(bitmap: Bitmap): List<DetectedObstacle> {
        if (!isAvailable) {
            throw IllegalStateException("AI model not loaded")
        }
        // AIDetector.detect() 是 suspend 函数，使用 runBlocking 桥接
        return runBlocking { aiDetector.detect(bitmap) }
    }
}
