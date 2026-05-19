package com.blindpath.base.power

import com.blindpath.base.config.AppConfig
import timber.log.Timber
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * 帧率控制器
 * 根据电量状态和性能需求动态调整帧率
 */
class FrameRateController(
    initialMode: PerformanceMode = PerformanceMode.HIGH
) {
    /**
     * 帧率配置
     */
    data class FrameRateConfig(
        val targetFps: Int,
        val frameIntervalMs: Long,
        val skipFrames: Int,          // 每N帧处理1帧
        val processEveryNFrames: Int
    ) {
        companion object {
            val HIGH = FrameRateConfig(
                targetFps = 30,
                frameIntervalMs = 33,
                skipFrames = 0,
                processEveryNFrames = 1
            )
            
            val MEDIUM = FrameRateConfig(
                targetFps = 20,
                frameIntervalMs = 50,
                skipFrames = 1,
                processEveryNFrames = 2
            )
            
            val LOW = FrameRateConfig(
                targetFps = 15,
                frameIntervalMs = 67,
                skipFrames = 2,
                processEveryNFrames = 3
            )
            
            /**
             * 静音模式（屏幕关闭时）
             */
            val SCREEN_OFF = FrameRateConfig(
                targetFps = 5,
                frameIntervalMs = 200,
                skipFrames = 5,
                processEveryNFrames = 6
            )
        }
    }
    
    private val currentMode = AtomicReference(initialMode)
    private val currentConfig = AtomicReference(getConfigForMode(initialMode))
    private val frameCount = AtomicInteger(0)
    private val lastFrameTime = AtomicLong(0)
    private val isScreenOn = AtomicReference(true)
    
    // 自适应帧率
    private var adaptiveEnabled = true
    private var lastProcessingTimeMs = 0L
    private var averageProcessingTimeMs = 0f
    private val processingTimeSamples = mutableListOf<Float>()
    
    /**
     * 设置性能模式
     */
    fun setPerformanceMode(mode: PerformanceMode) {
        if (currentMode.get() == mode) return
        
        currentMode.set(mode)
        currentConfig.set(getConfigForMode(mode))
        frameCount.set(0)
        
        Timber.d("Frame rate mode changed to: $mode, config: ${currentConfig.get()}")
    }
    
    /**
     * 设置屏幕状态
     */
    fun setScreenState(isOn: Boolean) {
        isScreenOn.set(isOn)
        
        if (!isOn) {
            // 屏幕关闭时使用更低的帧率
            currentConfig.set(FrameRateConfig.SCREEN_OFF)
            Timber.d("Screen off, using reduced frame rate")
        } else {
            currentConfig.set(getConfigForMode(currentMode.get()))
            Timber.d("Screen on, restored frame rate")
        }
    }
    
    /**
     * 检查当前帧是否应该处理
     * @return true 如果应该处理这一帧
     */
    fun shouldProcessFrame(): Boolean {
        val config = currentConfig.get()
        val currentTime = System.currentTimeMillis()
        val lastTime = lastFrameTime.get()
        
        // 检查帧间隔
        if (currentTime - lastTime < config.frameIntervalMs) {
            return false
        }
        
        // 更新帧计数
        val count = frameCount.incrementAndGet()
        lastFrameTime.set(currentTime)
        
        // 检查是否应该跳过
        return count % config.processEveryNFrames == 0
    }
    
    /**
     * 记录处理时间（用于自适应帧率）
     */
    fun recordProcessingTime(timeMs: Long) {
        lastProcessingTimeMs = timeMs
        
        // 更新平均处理时间
        processingTimeSamples.add(timeMs.toFloat())
        if (processingTimeSamples.size > 30) {
            processingTimeSamples.removeAt(0)
        }
        averageProcessingTimeMs = processingTimeSamples.average().toFloat()
        
        // 自适应调整
        if (adaptiveEnabled) {
            adaptFrameRate()
        }
    }
    
    /**
     * 自适应帧率调整
     */
    private fun adaptFrameRate() {
        if (averageProcessingTimeMs > AppConfig.FrameRate.MAX_PROCESSING_TIME_MS) {
            // 处理时间过长，降低帧率
            val currentModeValue = currentMode.get()
            when (currentModeValue) {
                PerformanceMode.HIGH -> setPerformanceMode(PerformanceMode.MEDIUM)
                PerformanceMode.MEDIUM -> setPerformanceMode(PerformanceMode.LOW)
                PerformanceMode.LOW -> { /* 已经是最低 */ }
            }
        }
    }
    
    /**
     * 获取当前配置
     */
    fun getCurrentConfig(): FrameRateConfig = currentConfig.get()
    
    /**
     * 获取当前模式
     */
    fun getCurrentMode(): PerformanceMode = currentMode.get()
    
    /**
     * 获取帧率统计
     */
    fun getStats(): FrameRateStats {
        return FrameRateStats(
            currentMode = currentMode.get(),
            currentFps = currentConfig.get().targetFps,
            frameCount = frameCount.get(),
            averageProcessingTimeMs = averageProcessingTimeMs,
            isScreenOn = isScreenOn.get()
        )
    }
    
    /**
     * 重置帧计数
     */
    fun reset() {
        frameCount.set(0)
        lastFrameTime.set(0)
        processingTimeSamples.clear()
        averageProcessingTimeMs = 0f
    }
    
    /**
     * 启用/禁用自适应帧率
     */
    fun setAdaptiveEnabled(enabled: Boolean) {
        adaptiveEnabled = enabled
    }
    
    private fun getConfigForMode(mode: PerformanceMode): FrameRateConfig {
        return when (mode) {
            PerformanceMode.HIGH -> FrameRateConfig.HIGH
            PerformanceMode.MEDIUM -> FrameRateConfig.MEDIUM
            PerformanceMode.LOW -> FrameRateConfig.LOW
        }
    }
}

/**
 * 帧率统计
 */
data class FrameRateStats(
    val currentMode: PerformanceMode,
    val currentFps: Int,
    val frameCount: Int,
    val averageProcessingTimeMs: Float,
    val isScreenOn: Boolean
)
