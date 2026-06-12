package com.blindpath.base.reliability

import android.content.Context
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 可靠性指标日志记录器
 * 记录降级事件、延迟超标、服务重启等关键可靠性指标
 *
 * TODO: Firebase Analytics 集成后，所有指标应上报到远程
 */
object ReliabilityLogger {

    // 内存中的计数器
    private val counters = ConcurrentHashMap<String, AtomicLong>()

    // 降级事件计数
    private val fallbackCounts = ConcurrentHashMap<String, AtomicLong>()

    private var logDir: File? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    fun initialize(context: Context) {
        logDir = File(context.filesDir, "reliability_logs").also { it.mkdirs() }
        Timber.i("ReliabilityLogger initialized, logDir: ${logDir?.absolutePath}")
    }

    /**
     * 记录降级事件
     */
    fun logFallback(detectorName: String, reason: String?) {
        val count = fallbackCounts.getOrPut(detectorName) { AtomicLong(0) }
        count.incrementAndGet()
        Timber.w("Fallback: %s -> reason: %s (total: %d)", detectorName, reason, count.get())
        writeToFile("FALLBACK", mapOf(
            "detector" to detectorName,
            "reason" to (reason ?: "unknown"),
            "total_count" to count.get()
        ))
    }

    /**
     * 记录延迟超标
     */
    fun logMetric(metricName: String, params: Map<String, Any>) {
        Timber.d("Metric: %s %s", metricName, params)
        writeToFile("METRIC", mapOf("name" to metricName) + params)
    }

    /**
     * 记录错误
     */
    fun logError(tag: String?, message: String, throwable: Throwable?) {
        writeToFile("ERROR", mapOf(
            "tag" to (tag ?: "unknown"),
            "message" to message,
            "throwable" to (throwable?.message ?: "null")
        ))
    }

    /**
     * 记录警告
     */
    fun logWarning(tag: String?, message: String) {
        writeToFile("WARN", mapOf(
            "tag" to (tag ?: "unknown"),
            "message" to message
        ))
    }

    /**
     * 记录服务重启事件
     */
    fun logServiceRestart(reason: String) {
        Timber.w("Service restart: %s", reason)
        writeToFile("SERVICE_RESTART", mapOf("reason" to reason))
    }

    /**
     * 获取降级统计
     */
    fun getFallbackStats(): Map<String, Long> {
        return fallbackCounts.map { it.key to it.value.get() }.toMap()
    }

    private fun writeToFile(type: String, data: Map<String, Any>) {
        try {
            val dir = logDir ?: return
            // 按日期分文件
            val dateStr = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            val file = File(dir, "reliability_$dateStr.log")
            val timestamp = dateFormat.format(Date())
            val line = buildString {
                append(timestamp)
                append(" [$type] ")
                append(data.entries.joinToString(", ") { "${it.key}=${it.value}" })
                append("\n")
            }
            file.appendText(line)
        } catch (e: Exception) {
            Timber.e(e, "Failed to write reliability log")
        }
    }
}
