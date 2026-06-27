package com.blindpath.module_voice.service

import android.content.Context
import com.blindpath.module_voice.domain.WakeWordDetector
import com.blindpath.porcupine.PorcupineConfig
import com.blindpath.porcupine.PorcupineWakeWordEngine
import com.blindpath.module_voice.domain.model.WakeWordConfig
import timber.log.Timber

/**
 * Porcupine 离线唤醒词检测器（降级方案）
 *
 * 基于 Picovoice Porcupine SDK 实现，特点：
 * - 完全离线运行，无需网络
 * - 低延迟（<100ms），低功耗
 * - 华为设备完美兼容（不依赖 Google Play 服务）
 * - 需要外部传入音频帧（非自管理音频）
 *
 * 作为讯飞/百度引擎不可用时的可靠降级方案。
 */
class PorcupineWakeWordDetector(
    private val context: Context,
    private val accessKey: String,
    private val keywordAssetPath: String = WakeWordConfig.PORCUPINE_KEYWORD_ASSET,
    private val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD,
    private val onWakeWordDetected: (String) -> Unit
) : WakeWordDetector {

    companion object {
        private const val TAG = "PorcupineWakeWordDetector"
        private const val FRAME_LENGTH = 512 // Porcupine 要求 512 样本帧
        private const val SAMPLE_RATE = 16000
    }

    private var engine: PorcupineWakeWordEngine? = null
    private var isInitialized = false
    private var isListening = false
    private var callback: WakeWordDetector.Callback? = null

    init {
        try {
            initialize()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Initialization failed")
            isInitialized = false
        }
    }

    private fun initialize() {
        if (accessKey.isBlank()) {
            Timber.w("$TAG: Porcupine access key not configured, engine unavailable")
            isInitialized = false
            return
        }

        try {
            // 从 assets 提取唤醒词文件
            val keywordPath = PorcupineConfig.extractAsset(context, keywordAssetPath)

            engine = PorcupineWakeWordEngine(
                context = context,
                accessKey = accessKey,
                keywordPath = keywordPath
            )

            if (engine!!.initialize()) {
                isInitialized = true
                engine!!.onWakeWordDetected = { keywordIndex ->
                    Timber.i("$TAG: Wake word detected (index=$keywordIndex)")
                    onWakeWordDetected(wakeWord)
                    callback?.onWakeWordDetected(wakeWord, 1.0f)
                }
                engine!!.onError = { error ->
                    Timber.e(error, "$TAG: Engine error")
                    callback?.onError(WakeWordDetector.ERROR_UNKNOWN, error.message ?: "Unknown")
                }
                Timber.i("$TAG: Initialized successfully with keyword: $keywordAssetPath")
            } else {
                Timber.w("$TAG: Engine initialization returned false")
                isInitialized = false
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize Porcupine engine")
            isInitialized = false
        }
    }

    /**
     * 处理音频帧（供 WakeWordServiceEnhanced 手动采集时调用）
     * @param audioData 16-bit PCM 音频数据，长度必须为 FRAME_LENGTH (512)
     * @return 是否检测到唤醒词
     */
    fun process(audioData: ShortArray): Boolean {
        if (!isInitialized || engine == null || !isListening) return false

        if (audioData.size != FRAME_LENGTH) {
            Timber.w("$TAG: Frame length mismatch, expected $FRAME_LENGTH, got ${audioData.size}")
            return false
        }

        return try {
            val keywordIndex = engine!!.process(audioData)
            keywordIndex >= 0
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Process error")
            false
        }
    }

    fun getFrameLength(): Int = FRAME_LENGTH
    fun getSampleRate(): Int = SAMPLE_RATE

    override fun start(): Boolean {
        if (!isInitialized) {
            Timber.w("$TAG: Cannot start - not initialized")
            return false
        }
        isListening = true
        Timber.i("$TAG: Started listening")
        return true
    }

    override fun stop() {
        isListening = false
        Timber.i("$TAG: Stopped")
    }

    override fun setCallback(callback: WakeWordDetector.Callback) {
        this.callback = callback
    }

    override fun setSensitivity(sens: Float) {
        Timber.d("$TAG: Sensitivity not configurable for Porcupine (set to $sens)")
    }

    override fun release() {
        isListening = false
        engine?.release()
        engine = null
        isInitialized = false
        Timber.i("$TAG: Released")
    }
}