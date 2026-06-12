package com.blindpath.module_obstacle.data.detection

import android.graphics.Bitmap
import com.blindpath.base.reliability.ReliabilityLogger
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 组合检测管道 - 三层降级链
 *
 * 降级策略: AI主检测 -> 辅助边缘检测 -> 传感器兜底
 * 每层失败自动降级到下一层，永不返回空结果
 */
@Singleton
class CompositeDetectionPipeline @Inject constructor(
    private val aiDetectionStrategy: AIDetectionStrategy,
    private val assistedDetectionStrategy: AssistedDetectionStrategy,
    private val sensorFallbackStrategy: SensorFallbackStrategy
) {
    private val strategies = listOf(
        aiDetectionStrategy,
        assistedDetectionStrategy,
        sensorFallbackStrategy
    )

    /**
     * 执行检测，自动降级
     */
    fun detect(bitmap: Bitmap): List<DetectedObstacle> {
        for (strategy in strategies) {
            if (!strategy.isAvailable) {
                Timber.d("Detection strategy '%s' not available, skipping", strategy.name)
                continue
            }
            try {
                val result = strategy.detect(bitmap)
                if (result.isNotEmpty()) {
                    return result
                }
                Timber.d("Detection strategy '%s' returned empty, trying next", strategy.name)
            } catch (e: Exception) {
                Timber.w(e, "Detection strategy '%s' failed, falling back", strategy.name)
                ReliabilityLogger.logFallback(strategy.name, e.message)
            }
        }

        // 所有策略都失败，返回传感器最后已知数据
        Timber.w("All detection strategies failed, using sensor fallback")
        return sensorFallbackStrategy.detect(bitmap)
    }

    /**
     * 获取当前活跃的检测策略名称
     */
    fun getActiveStrategyName(): String {
        for (strategy in strategies) {
            if (strategy.isAvailable) return strategy.name
        }
        return "none"
    }
}
