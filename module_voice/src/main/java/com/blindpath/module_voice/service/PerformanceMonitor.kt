package com.blindpath.module_voice.service

import android.content.Context
import com.blindpath.module_voice.config.VoiceServiceConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音唤醒性能监控与日志上报
 * 
 * 监控指标：
 * - 唤醒成功率
 * - 唤醒响应时间
 * - 误唤醒率
 * - 引擎稳定性
 * - 电量消耗
 * - 内存占用
 */
@Singleton
class PerformanceMonitor @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val scope = CoroutineScope(Dispatchers.Default)
    private var reportJob: Job? = null
    
    // 统计数据
    private val wakeEvents = ConcurrentLinkedQueue<WakeEvent>()
    private val totalWakeAttempts = AtomicLong(0)
    private val successfulWakes = AtomicLong(0)
    private val falseWakes = AtomicLong(0)
    private val totalWakeLatency = AtomicLong(0)
    
    // 引擎切换统计
    private val engineSwitches = ConcurrentLinkedQueue<EngineSwitchEvent>()
    
    // 性能数据存储
    private val performanceData = ConcurrentLinkedQueue<PerformanceSnapshot>()
    
    /**
     * 启动性能监控
     */
    fun startMonitoring() {
        if (!VoiceServiceConfig.enablePerformanceMonitor) {
            Timber.d("Performance monitoring disabled")
            return
        }
        
        if (reportJob?.isActive == true) {
            Timber.w("Performance monitoring already running")
            return
        }
        
        reportJob = scope.launch {
            Timber.d("Performance monitoring started")
            
            while (isActive) {
                delay(VoiceServiceConfig.PERFORMANCE_REPORT_INTERVAL_MS)
                
                if (isActive) {
                    generateAndSaveReport()
                }
            }
        }
    }
    
    /**
     * 停止性能监控
     */
    fun stopMonitoring() {
        reportJob?.cancel()
        reportJob = null
        
        Timber.d("Performance monitoring stopped")
    }
    
    /**
     * 记录唤醒尝试
     */
    fun recordWakeAttempt(timestamp: Long = System.currentTimeMillis()) {
        totalWakeAttempts.incrementAndGet()
        
        val event = WakeEvent(
            timestamp = timestamp,
            type = WakeEventType.ATTEMPT
        )
        wakeEvents.add(event)
        
        cleanupOldEvents()
    }
    
    /**
     * 记录唤醒成功
     */
    fun recordWakeSuccess(
        latencyMs: Long,
        engine: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        successfulWakes.incrementAndGet()
        totalWakeLatency.addAndGet(latencyMs)
        
        val event = WakeEvent(
            timestamp = timestamp,
            type = WakeEventType.SUCCESS,
            latencyMs = latencyMs,
            engine = engine
        )
        wakeEvents.add(event)
        
        Timber.d("Wake success: latency=${latencyMs}ms, engine=$engine")
    }
    
    /**
     * 记录唤醒失败
     */
    fun recordWakeFailure(
        reason: String,
        engine: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val event = WakeEvent(
            timestamp = timestamp,
            type = WakeEventType.FAILURE,
            engine = engine,
            reason = reason
        )
        wakeEvents.add(event)
        
        Timber.w("Wake failure: reason=$reason, engine=$engine")
    }
    
    /**
     * 记录误唤醒
     */
    fun recordFalseWake(
        timestamp: Long = System.currentTimeMillis()
    ) {
        falseWakes.incrementAndGet()
        
        val event = WakeEvent(
            timestamp = timestamp,
            type = WakeEventType.FALSE_WAKE
        )
        wakeEvents.add(event)
        
        Timber.w("False wake detected")
    }
    
    /**
     * 记录引擎切换
     */
    fun recordEngineSwitch(
        fromEngine: String,
        toEngine: String,
        reason: String,
        timestamp: Long = System.currentTimeMillis()
    ) {
        val event = EngineSwitchEvent(
            timestamp = timestamp,
            fromEngine = fromEngine,
            toEngine = toEngine,
            reason = reason
        )
        engineSwitches.add(event)
        
        Timber.i("Engine switched: $fromEngine -> $toEngine (reason: $reason)")
    }
    
    /**
     * 获取唤醒成功率
     */
    fun getWakeSuccessRate(): Double {
        val attempts = totalWakeAttempts.get()
        if (attempts == 0L) return 0.0
        
        return successfulWakes.get().toDouble() / attempts * 100
    }
    
    /**
     * 获取平均唤醒延迟
     */
    fun getAverageWakeLatency(): Long {
        val successCount = successfulWakes.get()
        if (successCount == 0L) return 0L
        
        return totalWakeLatency.get() / successCount
    }
    
    /**
     * 获取误唤醒率（每小时）
     */
    fun getFalseWakeRatePerHour(): Double {
        val oneHourAgo = System.currentTimeMillis() - 60 * 60 * 1000
        
        val recentFalseWakes = wakeEvents.count {
            it.type == WakeEventType.FALSE_WAKE && it.timestamp > oneHourAgo
        }
        
        return recentFalseWakes.toDouble()
    }
    
    /**
     * 获取性能摘要
     */
    fun getPerformanceSummary(): PerformanceSummary {
        return PerformanceSummary(
            totalAttempts = totalWakeAttempts.get(),
            successfulWakes = successfulWakes.get(),
            falseWakes = falseWakes.get(),
            successRate = getWakeSuccessRate(),
            averageLatencyMs = getAverageWakeLatency(),
            falseWakeRatePerHour = getFalseWakeRatePerHour(),
            engineSwitches = engineSwitches.size
        )
    }
    
    /**
     * 生成并保存性能报告
     */
    private suspend fun generateAndSaveReport() {
        withContext(Dispatchers.IO) {
            try {
                val snapshot = PerformanceSnapshot(
                    timestamp = System.currentTimeMillis(),
                    summary = getPerformanceSummary()
                )
                
                performanceData.add(snapshot)
                
                // 清理旧数据
                cleanupOldData()
                
                // 保存到文件或上报到服务器
                saveReport(snapshot)
                
                Timber.d("Performance report generated: $snapshot")
            } catch (e: Exception) {
                Timber.e(e, "Failed to generate performance report")
            }
        }
    }
    
    /**
     * 清理旧事件
     */
    private fun cleanupOldEvents() {
        val cutoffTime = System.currentTimeMillis() - VoiceServiceConfig.PERFORMANCE_DATA_RETENTION_MS
        
        while (wakeEvents.isNotEmpty() && wakeEvents.peek().timestamp < cutoffTime) {
            wakeEvents.poll()
        }
        
        while (engineSwitches.isNotEmpty() && engineSwitches.peek().timestamp < cutoffTime) {
            engineSwitches.poll()
        }
    }
    
    /**
     * 清理旧数据
     */
    private fun cleanupOldData() {
        val cutoffTime = System.currentTimeMillis() - VoiceServiceConfig.PERFORMANCE_DATA_RETENTION_MS
        
        while (performanceData.isNotEmpty() && performanceData.peek().timestamp < cutoffTime) {
            performanceData.poll()
        }
    }
    
    /**
     * 保存报告
     */
    private fun saveReport(snapshot: PerformanceSnapshot) {
        // TODO: 保存到本地文件或上报到服务器
        // 这里可以使用 SharedPreferences、SQLite 或网络请求
    }
    
    /**
     * 唤醒事件类型
     */
    enum class WakeEventType {
        ATTEMPT,    // 尝试
        SUCCESS,    // 成功
        FAILURE,    // 失败
        FALSE_WAKE  // 误唤醒
    }
    
    /**
     * 唤醒事件
     */
    data class WakeEvent(
        val timestamp: Long,
        val type: WakeEventType,
        val latencyMs: Long = 0,
        val engine: String = "",
        val reason: String = ""
    )
    
    /**
     * 引擎切换事件
     */
    data class EngineSwitchEvent(
        val timestamp: Long,
        val fromEngine: String,
        val toEngine: String,
        val reason: String
    )
    
    /**
     * 性能摘要
     */
    data class PerformanceSummary(
        val totalAttempts: Long,
        val successfulWakes: Long,
        val falseWakes: Long,
        val successRate: Double,
        val averageLatencyMs: Long,
        val falseWakeRatePerHour: Double,
        val engineSwitches: Int
    )
    
    /**
     * 性能快照
     */
    data class PerformanceSnapshot(
        val timestamp: Long,
        val summary: PerformanceSummary
    )
}
