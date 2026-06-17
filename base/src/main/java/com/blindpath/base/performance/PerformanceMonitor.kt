package com.blindpath.base.performance

import android.content.Context
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * 性能监控管理器
 * 采集和上报应用性能指标
 */
class PerformanceMonitor(
    private val context: Context
) {
    companion object {
        private const val TAG = "PerformanceMonitor"
        
        @Volatile
        private var instance: PerformanceMonitor? = null
        
        fun getInstance(context: Context): PerformanceMonitor {
            return instance ?: synchronized(this) {
                instance ?: PerformanceMonitor(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // 性能指标存储
    private val metrics = ConcurrentHashMap<String, PerformanceMetric>()
    private val traces = ConcurrentHashMap<String, Trace>()
    
    // 统计数据
    private val totalMemoryUsage = AtomicLong(0)
    private val totalCpuTime = AtomicLong(0)
    
    /**
     * 性能指标
     */
    data class PerformanceMetric(
        val name: String,
        var count: Long = 0,
        var totalTimeMs: Long = 0,
        var minTimeMs: Long = Long.MAX_VALUE,
        var maxTimeMs: Long = 0,
        var lastValue: Long = 0
    ) {
        val averageTimeMs: Long
            get() = if (count > 0) totalTimeMs / count else 0
    }
    
    /**
     * 追踪
     */
    data class Trace(
        val name: String,
        val startTime: Long,
        var endTime: Long = 0,
        val attributes: MutableMap<String, Any> = mutableMapOf()
    ) {
        val durationMs: Long
            get() = if (endTime > 0) endTime - startTime else System.currentTimeMillis() - startTime
        
        val isCompleted: Boolean
            get() = endTime > 0
    }
    
    /**
     * 开始追踪
     */
    fun startTrace(name: String): Trace {
        val trace = Trace(name, System.currentTimeMillis())
        traces[name] = trace
        Timber.d("Trace started: $name")
        return trace
    }
    
    /**
     * 结束追踪
     */
    fun endTrace(name: String) {
        val trace = traces[name] ?: return
        trace.endTime = System.currentTimeMillis()
        
        // 记录指标
        recordMetric(name, trace.durationMs)
        
        Timber.d("Trace ended: $name, duration: ${trace.durationMs}ms")
    }
    
    /**
     * 添加追踪属性
     */
    fun addTraceAttribute(traceName: String, key: String, value: Any) {
        traces[traceName]?.attributes?.put(key, value)
    }
    
    /**
     * 记录指标
     */
    fun recordMetric(name: String, valueMs: Long) {
        val metric = metrics.getOrPut(name) { PerformanceMetric(name) }
        
        synchronized(metric) {
            metric.count++
            metric.totalTimeMs += valueMs
            metric.minTimeMs = minOf(metric.minTimeMs, valueMs)
            metric.maxTimeMs = maxOf(metric.maxTimeMs, valueMs)
            metric.lastValue = valueMs
        }
    }
    
    /**
     * 记录计数
     */
    fun incrementCounter(name: String, delta: Long = 1) {
        val metric = metrics.getOrPut(name) { PerformanceMetric(name) }
        synchronized(metric) {
            metric.count += delta
        }
    }
    
    /**
     * 获取指标
     */
    fun getMetric(name: String): PerformanceMetric? = metrics[name]
    
    /**
     * 获取所有指标
     */
    fun getAllMetrics(): Map<String, PerformanceMetric> = metrics.toMap()
    
    /**
     * 获取性能报告
     */
    fun getPerformanceReport(): PerformanceReport {
        return PerformanceReport(
            metrics = metrics.toMap(),
            activeTraces = traces.filter { !it.value.isCompleted }.size,
            completedTraces = traces.count { it.value.isCompleted },
            timestamp = System.currentTimeMillis()
        )
    }
    
    /**
     * 清除所有数据
     */
    fun clear() {
        metrics.clear()
        traces.clear()
        totalMemoryUsage.set(0)
        totalCpuTime.set(0)
    }
    
    /**
     * 测量代码块执行时间
     */
    inline fun <T> measure(name: String, block: () -> T): T {
        val startTime = System.currentTimeMillis()
        return try {
            block()
        } finally {
            val duration = System.currentTimeMillis() - startTime
            recordMetric(name, duration)
        }
    }
    
    /**
     * 性能报告
     */
    data class PerformanceReport(
        val metrics: Map<String, PerformanceMetric>,
        val activeTraces: Int,
        val completedTraces: Int,
        val timestamp: Long
    ) {
        fun toFormattedString(): String {
            val sb = StringBuilder()
            sb.appendLine("=== Performance Report ===")
            sb.appendLine("Timestamp: ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(timestamp))}")
            sb.appendLine()
            sb.appendLine("Metrics:")
            metrics.forEach { (name, metric) ->
                sb.appendLine("  $name: avg=${metric.averageTimeMs}ms, count=${metric.count}, min=${metric.minTimeMs}ms, max=${metric.maxTimeMs}ms")
            }
            sb.appendLine()
            sb.appendLine("Traces: $activeTraces active, $completedTraces completed")
            return sb.toString()
        }
    }
}

/**
 * 性能指标名称定义
 */
object MetricNames {
    // AI 检测相关
    const val AI_INFERENCE_TIME = "ai_inference_time"
    const val AI_FRAME_PROCESSING = "ai_frame_processing"
    const val AI_MODEL_LOAD_TIME = "ai_model_load_time"
    
    // 导航相关
    const val GPS_LOCATION_UPDATE = "gps_location_update"
    const val NAVIGATION_ROUTE_CALCULATION = "navigation_route_calculation"
    const val NAVIGATION_VOICE_ANNOUNCEMENT = "navigation_voice_announcement"
    
    // 语音相关
    const val TTS_SPEAK_TIME = "tts_speak_time"
    const val TTS_INIT_TIME = "tts_init_time"
    const val VOICE_RECOGNITION_TIME = "voice_recognition_time"
    
    // UI 相关
    const val UI_FRAME_RENDER = "ui_frame_render"
    const val SCREEN_LOAD_TIME = "screen_load_time"
    
    // 网络相关
    const val NETWORK_REQUEST_TIME = "network_request_time"
    const val NETWORK_FAILURE_COUNT = "network_failure_count"
    
    // 电池相关
    const val BATTERY_USAGE = "battery_usage"
    const val CPU_USAGE = "cpu_usage"
    const val MEMORY_USAGE = "memory_usage"
}

/**
 * 追踪名称定义
 */
object TraceNames {
    const val APP_STARTUP = "app_startup"
    const val OBSTACLE_DETECTION_SESSION = "obstacle_detection_session"
    const val NAVIGATION_SESSION = "navigation_session"
    const val SOS_SESSION = "sos_session"
    const val MODEL_DOWNLOAD = "model_download"
}
