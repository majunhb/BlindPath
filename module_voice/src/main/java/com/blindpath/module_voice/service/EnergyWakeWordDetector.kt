package com.blindpath.module_voice.service

import com.blindpath.module_voice.domain.WakeWordDetector

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
    private var callback: WakeWordDetector.Callback? = null

    companion object {
        private const val TAG = "EnergyWakeWordDetector"
    }

    init {
        Timber.i("$TAG: Initialized with threshold: $threshold")
    }

    override fun start(): Boolean {
        startListening()
        return true
    }

    fun startListening() {
        Timber.d("$TAG: startListening (no-op for energy detector)")
    }

    override fun stop() {
        Timber.d("$TAG: stop (no-op for energy detector)")
    }

    /**
     * 处理音频数据（供 WakeWordServiceEnhanced 手动采集时调用）
     * @param audioData 16-bit PCM 音频数据
     * @return 是否检测到声音（注意：不是唤醒词）
     */
    fun process(audioData: ShortArray): Boolean {
        if (!isInitialized) return false

        val energy = calculateEnergy(audioData)
        val currentTime = System.currentTimeMillis()

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

    fun getFrameLength(): Int = 512
    fun getSampleRate(): Int = 16000

    override fun setCallback(callback: WakeWordDetector.Callback) {
        this.callback = callback
    }

    override fun setSensitivity(sens: Float) {
        Timber.d("$TAG: Set sensitivity: $sens")
    }

    override fun release() {
        isInitialized = false
        Timber.i("$TAG: Released")
    }
}
