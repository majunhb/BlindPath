package com.blindpath.module_voice.service

import com.blindpath.module_voice.domain.WakeWordDetector

import com.blindpath.module_voice.domain.model.WakeWordConfig
import timber.log.Timber
import kotlin.math.sqrt

/**
 * 能量检测唤醒词检测器（降级方案）v2.0
 *
 * ★ v2.0 改进（2026-06-23）：
 * 1. 添加过零率（ZCR）检测，区分语音和噪声
 * 2. 添加持续时间检测，避免短促噪声误触发
 * 3. 提高阈值到 1200，减少环境噪声误触发
 *
 * 注意：这只是检测声音特征，无法识别具体的唤醒词
 */
class EnergyWakeWordDetector(
    private val threshold: Int = 1200,  // ★ 提高阈值，减少误触发
    private val onWakeWordDetected: (String) -> Unit
) : WakeWordDetector {

    private var isInitialized = true
    private var lastDetectionTime = 0L
    private val detectionCooldown = 5000L // ★ 5秒冷却时间，避免频繁触发
    private var callback: WakeWordDetector.Callback? = null
    
    // ★ 持续时间检测
    private var soundStartTime = 0L
    private var isSoundDetected = false
    private val minSoundDuration = 300L  // ★ 最少持续300ms才触发（约1-2个音节）
    
    // ★ 过零率参数
    private val minZcr = 0.02  // 最小过零率（语音通常 > 0.02）
    private val maxZcr = 0.15  // 最大过零率（噪声通常 > 0.15）

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
     * ★ v2.0：处理音频数据，结合能量、过零率和持续时间检测
     * @param audioData 16-bit PCM 音频数据
     * @return 是否检测到语音特征（注意：不是真正的唤醒词识别）
     */
    fun process(audioData: ShortArray): Boolean {
        if (!isInitialized) return false

        val energy = calculateEnergy(audioData)
        val zcr = calculateZeroCrossingRate(audioData)
        val currentTime = System.currentTimeMillis()

        // ★ 检测声音开始
        if (energy > threshold && !isSoundDetected) {
            soundStartTime = currentTime
            isSoundDetected = true
            Timber.d("$TAG: Sound started (energy=${energy.toInt()}, zcr=${"%.3f".format(zcr)})")
        }
        
        // ★ 检测声音持续
        if (isSoundDetected && energy > threshold) {
            val duration = currentTime - soundStartTime
            
            // ★ 检查是否满足触发条件：持续时间 + 过零率范围
            if (duration >= minSoundDuration && zcr in minZcr..maxZcr) {
                if ((currentTime - lastDetectionTime) > detectionCooldown) {
                    lastDetectionTime = currentTime
                    isSoundDetected = false  // 重置状态
                    Timber.i("$TAG: ★ Voice-like sound detected (energy=${energy.toInt()}, zcr=${"%.3f".format(zcr)}, duration=${duration}ms)")
                    onWakeWordDetected(WakeWordConfig.DEFAULT_WAKE_WORD)
                    return true
                }
            }
        }
        
        // ★ 声音结束或能量过低，重置状态
        if (energy <= threshold * 0.7) {  // 70%阈值作为声音结束判断
            if (isSoundDetected) {
                val duration = currentTime - soundStartTime
                Timber.d("$TAG: Sound ended (duration=${duration}ms, below threshold)")
            }
            isSoundDetected = false
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
     * ★ 计算过零率（Zero Crossing Rate）
     * 语音信号的过零率通常在 0.02-0.15 之间
     * 噪声的过零率通常更高（>0.15）或更低（<0.02）
     */
    private fun calculateZeroCrossingRate(buffer: ShortArray): Double {
        if (buffer.size < 2) return 0.0
        
        var zeroCrossings = 0
        for (i in 1 until buffer.size) {
            // 检测符号变化（从正到负或从负到正）
            if ((buffer[i] >= 0 && buffer[i-1] < 0) || (buffer[i] < 0 && buffer[i-1] >= 0)) {
                zeroCrossings++
            }
        }
        
        return zeroCrossings.toDouble() / (buffer.size - 1)
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
