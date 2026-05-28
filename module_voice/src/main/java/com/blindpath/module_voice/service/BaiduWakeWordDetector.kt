package com.blindpath.module_voice.service

import android.content.Context
import com.baidu.aipe.asr.AipeEventManagerFactory
import com.baidu.speech.EventListener
import com.baidu.speech.EventManager
import com.baidu.speech.asr.SpeechConstant
import org.json.JSONObject
import timber.log.Timber

/**
 * 百度语音唤醒检测器
 *
 * 基于百度语音唤醒 SDK（ASR V3 3.5.1）实现
 * SDK 使用 EventManager 自动管理音频采集，不需要手动传入音频数据
 *
 * 集成资源：
 * - AAR: module_voice/libs/bdasr_aipd_V3_20250717_1e379e2.aar
 * - 唤醒词模型: module_voice/src/main/assets/WakeUp.bin
 * - AndroidManifest: 已配置 APP_ID / API_KEY / SECRET_KEY meta-data
 *
 * 百度应用凭证：
 * - AppID: 123301672
 * - API Key: 7bqc6ovRERcTumcd4h2dXhyj
 * - Secret Key: kuVbgAvSYkVPMcDWDjMkG5KlJZBLts3
 */
class BaiduWakeWordDetector(
    private val context: Context,
    private val appId: String,
    private val apiKey: String,
    private val secretKey: String,
    private val wakeWordAssetPath: String = "WakeUp.bin",
    private val sensitivity: Float = 0.7f,
    private val onWakeWordDetected: (String) -> Unit
) : WakeWordDetector {

    companion object {
        private const val TAG = "BaiduWakeWordDetector"
    }

    private var wp: EventManager? = null
    private var isListening = false
    private var isInitialized = false

    init {
        try {
            initialize()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize")
        }
    }

    private fun initialize() {
        if (appId.isBlank() || apiKey.isBlank() || secretKey.isBlank()) {
            throw IllegalArgumentException("Baidu credentials cannot be empty")
        }

        // 使用 AipeEventManagerFactory 创建唤醒事件管理器
        val factory = AipeEventManagerFactory()
        factory.setAkSk(appId, apiKey, secretKey)
        wp = factory.create(context, "wp")

        // 注册事件监听器
        wp?.registerListener(eventListener)

        isInitialized = true
        Timber.i("$TAG: Initialized successfully (AppID: $appId)")
    }

    /**
     * 事件监听器 - 处理百度 SDK 回调
     */
    private val eventListener = EventListener { name, params, data, offset, length ->
        when (name) {
            "wp.data" -> {
                // 唤醒词检测成功
                Timber.i("$TAG: Wake word detected! params: $params")
                try {
                    val json = JSONObject(params)
                    val word = json.optString("desc", "") ?: ""
                    val errorCode = json.optInt("error", 0)

                    if (errorCode == 0) {
                        val wakeWord = word.ifEmpty { "小智小智" }
                        onWakeWordDetected.invoke(wakeWord)
                    } else {
                        Timber.w("$TAG: Wake up error, code: $errorCode")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to parse wake up result")
                    onWakeWordDetected.invoke("小智小智")
                }
            }

            "wp.error" -> {
                Timber.e("$TAG: Wake up error: $params")
            }

            "wp.ready" -> {
                Timber.i("$TAG: Wake up engine ready")
            }

            "wp.stoped" -> {
                Timber.i("$TAG: Wake up stopped")
                isListening = false
            }

            "wp.exit" -> {
                Timber.i("$TAG: Wake up engine exited")
                isListening = false
            }

            else -> {
                Timber.d("$TAG: Event: $name, params: $params")
            }
        }
    }

    /**
     * 开始监听唤醒词
     */
    fun startListening() {
        if (!isInitialized || wp == null) {
            Timber.w("$TAG: Not initialized, cannot start listening")
            return
        }

        if (isListening) {
            Timber.d("$TAG: Already listening")
            return
        }

        try {
            val params = LinkedHashMap<String, Any>()
            // 唤醒词文件路径（assets 目录下）
            params[SpeechConstant.WP_WORDS_FILE] = "assets:///$wakeWordAssetPath"
            // 唤醒灵敏度
            params["kws-sensitivity"] = sensitivity.toString()

            val json = JSONObject(params as Map<*, *>).toString()
            wp?.send(SpeechConstant.WAKEUP_START, json, null, 0, 0)
            isListening = true
            Timber.i("$TAG: Started listening with wake word file: $wakeWordAssetPath, sensitivity: $sensitivity")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start listening")
        }
    }

    /**
     * 停止监听唤醒词
     */
    fun stopListening() {
        if (wp == null || !isListening) return

        try {
            wp?.send(SpeechConstant.WAKEUP_STOP, null, null, 0, 0)
            isListening = false
            Timber.i("$TAG: Stopped listening")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to stop listening")
        }
    }

    /**
     * 百度 SDK 自动管理音频采集，此方法不使用
     */
    override fun process(audioData: ShortArray): Boolean {
        return false
    }

    fun getFrameLength(): Int = 512
    fun getSampleRate(): Int = 16000
    fun isListening(): Boolean = isListening

    override fun release() {
        stopListening()
        try {
            wp?.send(SpeechConstant.WAKEUP_STOP, null, null, 0, 0)
            wp?.unregisterListener(eventListener)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error during release")
        }
        wp = null
        isInitialized = false
        Timber.i("$TAG: Released")
    }
}
