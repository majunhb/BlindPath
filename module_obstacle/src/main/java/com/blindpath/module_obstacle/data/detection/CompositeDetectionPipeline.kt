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
        val allResults = mutableListOf<DetectedObstacle>()

        // [修复] 并行执行所有可用策略，合并结果
        // 根因: 原逻辑 AI有结果就return → AssistedDetector.detectWalls() 永远不被调用
        for (strategy in strategies) {
            if (!strategy.isAvailable) {
                Timber.d("Detection strategy '%s' not available, skipping", strategy.name)
                continue
            }
            try {
                val result = strategy.detect(bitmap)
                allResults.addAll(result)
                Timber.d("Detection strategy '%s' returned %d results", strategy.name, result.size)
            } catch (e: Exception) {
                Timber.w(e, "Detection strategy '%s' failed, falling back", strategy.name)
                ReliabilityLogger.logFallback(strategy.name, e.message)
            }
        }

        // 去重：相同类型和方向的障碍物只保留置信度最高的
        val deduplicated = deduplicateResults(allResults)

        if (deduplicated.isNotEmpty()) {
            Timber.d("检测总结果: %d 个 → 去重后 %d 个", allResults.size, deduplicated.size)
            return deduplicated
        }

        // 如果所有策略都失败，使用传感器兜底
        Timber.w("All detection strategies returned empty, using sensor fallback")
        return sensorFallbackStrategy.detect(bitmap)
    }

    /**
     * [修复] 去重：相同类型和方向的障碍物只保留置信度最高的
     * 防止 AI 检测 和 AssistedDetector.detectWalls() 同时检测到墙壁时重复报警
     */
    private fun deduplicateResults(results: List<DetectedObstacle>): List<DetectedObstacle> {
        if (results.isEmpty()) return emptyList()
        return results
            .groupBy { "${it.type}_${it.direction}" }
            .map { (_, obstacles) -> obstacles.maxByOrNull { it.confidence }!! }
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
