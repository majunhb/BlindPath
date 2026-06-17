package com.blindpath.base.reliability

import android.os.SystemClock
import timber.log.Timber

/**
 * 延迟追踪器
 * 用于测量关键链路的端到端延迟，超过预算时自动告警
 */
object LatencyTracker {

    // 延迟预算（毫秒）
    const val DETECTION_BUDGET_MS = 150L
    const val ALERT_OUTPUT_BUDGET_MS = 50L
    const val TTS_INIT_BUDGET_MS = 200L

    private val currentSpans = ThreadLocal.withInitial { mutableMapOf<String, Long>() }

    /**
     * 开始一个追踪段
     */
    fun beginSpan(name: String) {
        currentSpans.get()[name] = SystemClock.elapsedRealtime()
    }

    /**
     * 结束一个追踪段并返回延迟（毫秒）
     * @param budgetMs 延迟预算，超过时记录警告
     */
    fun endSpan(name: String, budgetMs: Long = DETECTION_BUDGET_MS): Long {
        val startTime = currentSpans.get().remove(name) ?: return -1
        val latency = SystemClock.elapsedRealtime() - startTime

        if (latency > budgetMs) {
            Timber.w("Latency exceeded budget: %s = %dms (budget: %dms)", name, latency, budgetMs)
            ReliabilityLogger.logMetric("latency_exceeded", mapOf(
                "span" to name,
                "latency_ms" to latency,
                "budget_ms" to budgetMs
            ))
        }

        return latency
    }

    /**
     * 测量一个代码块的执行时间
     */
    inline fun <T> measure(name: String, budgetMs: Long = DETECTION_BUDGET_MS, block: () -> T): T {
        beginSpan(name)
        return try {
            block()
        } finally {
            endSpan(name, budgetMs)
        }
    }
}
