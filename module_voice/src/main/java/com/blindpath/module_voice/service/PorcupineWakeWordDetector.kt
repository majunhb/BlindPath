package com.blindpath.module_voice.service

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.content.Context
import timber.log.Timber

/**
 * Porcupine 唤醒词检测器实现
 *
 * 使用 Picovoice Porcupine 引擎进行离线唤醒词检测
 * 支持中文唤醒词（需要中文模型文件）
 */
class PorcupineWakeWordDetector(
    private val context: Context,
    private val accessKey: String,
    private val keywordPath: String? = null,  // 自定义唤醒词模型路径
    private val modelPath: String? = null,    // 语言模型路径（中文用 porcupine_params_zh.pv）
    private val sensitivity: Float = 0.5f,
    private val onWakeWordDetected: (String) -> Unit
) : WakeWordDetector {

    private var porcupine: Porcupine? = null
    private var isInitialized = false

    companion object {
        // 内置唤醒词（用于测试）
        val BUILT_IN_KEYWORDS = listOf(
            Porcupine.BuiltInKeyword.PORCUPINE,
            Porcupine.BuiltInKeyword.BUMBLEBEE
        )
    }

    init {
        try {
            initialize()
        } catch (e: Exception) {
            Timber.e(e, "PorcupineWakeWordDetector: Failed to initialize")
        }
    }

    private fun initialize() {
        try {
            porcupine = if (keywordPath != null) {
                // 使用自定义唤醒词模型
                val builder = Porcupine.Builder()
                    .setAccessKey(accessKey)
                    .setKeywordPath(keywordPath)
                    .setSensitivity(sensitivity)

                // 如果指定了语言模型（如中文），设置模型路径
                if (modelPath != null) {
                    builder.setModelPath(modelPath)
                }

                builder.build(context)
            } else {
                // 使用内置唤醒词（测试用）
                Porcupine.Builder()
                    .setAccessKey(accessKey)
                    .setKeyword(Porcupine.BuiltInKeyword.PORCUPINE)
                    .setSensitivity(sensitivity)
                    .build(context)
            }

            isInitialized = true
            Timber.i("PorcupineWakeWordDetector: Initialized successfully")
        } catch (e: PorcupineException) {
            Timber.e(e, "PorcupineWakeWordDetector: Initialization failed - ${e.message}")
            throw e
        }
    }

    /**
     * 处理音频数据
     * @param audioData 16kHz, 16-bit, 单声道 PCM 音频数据
     * @return 是否检测到唤醒词
     */
    override fun process(audioData: ShortArray): Boolean {
        if (!isInitialized || porcupine == null) {
            return false
        }

        return try {
            // Porcupine 每次处理一帧音频（512 samples at 16kHz = 32ms）
            // 如果输入数据大于一帧，需要分帧处理
            val frameLength = porcupine!!.frameLength
            var detected = false

            for (i in audioData.indices step frameLength) {
                if (i + frameLength > audioData.size) break

                val frame = audioData.copyOfRange(i, i + frameLength)
                val keywordIndex = porcupine!!.process(frame)

                if (keywordIndex >= 0) {
                    detected = true
                    val keywordName = keywordPath?.let { "自定义唤醒词" } ?: "Porcupine"
                    Timber.i("PorcupineWakeWordDetector: Wake word detected - $keywordName")
                    onWakeWordDetected(keywordName)
                }
            }

            detected
        } catch (e: PorcupineException) {
            Timber.e(e, "PorcupineWakeWordDetector: Processing error")
            false
        }
    }

    /**
     * 获取帧长度（每次处理的采样点数）
     */
    fun getFrameLength(): Int {
        return porcupine?.frameLength ?: 512
    }

    /**
     * 获取采样率
     */
    fun getSampleRate(): Int {
        return porcupine?.sampleRate ?: 16000
    }

    override fun release() {
        porcupine?.delete()
        porcupine = null
        isInitialized = false
        Timber.i("PorcupineWakeWordDetector: Released")
    }
}
