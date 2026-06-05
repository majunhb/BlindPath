package com.blindpath.module_voice.service

import com.blindpath.module_voice.domain.model.WakeWordConfig
import timber.log.Timber
import kotlin.math.sqrt

/**
 * 能量检测唤醒词检测器（降级方案）
 *
 * 简单的音频能量检测，用于当其他引擎都失败时的降级方案
 * 注意：这只是检测声音响度，无法识别具体的唤醒词
 */
class EnergyWakeWordDetector(
    private val threshold: Int = 1000,
    private val onWakeWordDetected: (String) -> Unit
) : WakeWordDetector {

    private var isInitialized = true
    private var lastDetectionTime = 0L
    private val detectionCooldown = 2000L // 2秒冷却时间

    companion object {
        private const val TAG = "EnergyWakeWordDetector"
    }

    init {
        Timber.i("$TAG: Initialized with threshold: $threshold")
    }

    /**
     * 处理音频数据
     * @param audioData 16-bit PCM 音频数据
     * @return 是否检测到声音（注意：不是唤醒词）
     */
    override fun startListening() {
        // Energy detector only works via process() calls, nothing to start
        Timber.d("$TAG: startListening (no-op for energy detector)")
    }

    override fun process(audioData: ShortArray): Boolean {
        if (!isInitialized) return false

        val energy = calculateEnergy(audioData)
        val currentTime = System.currentTimeMillis()

        // 检查是否超过阈值且冷却时间已过
        if (energy > threshold && (currentTime - lastDetectionTime) > detectionCooldown) {
            lastDetectionTime = currentTime
            Timber.i("$TAG: Sound detected (energy: ${energy.toInt()}), triggering wake word")
            onWakeWordDetected(WakeWordConfig.DEFAULT_WAKE_WORD)
            return true
        }

        return false
    }

    /**
     * 计算音频能量（RMS）
     */
    private fun calculateEnergy(buffer: ShortArray): Double {
        if (buffer.isEmpty()) return 0.0

        var sum = 0.0
        for (sample in buffer) {
            sum += sample * sample
        }
        return sqrt(sum / buffer.size)
    }

    /**
     * 获取帧长度（能量检测对帧长度没有严格要求，返回一个常用值）
     */
    override fun getFrameLength(): Int = 512

    /**
     * 获取采样率
     */
    override fun getSampleRate(): Int = 16000

    override fun release() {
        isInitialized = false
        Timber.i("$TAG: Released")
    }
}
