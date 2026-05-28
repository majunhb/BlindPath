package com.blindpath.module_voice.service

import android.content.Context
import timber.log.Timber

/**
 * 百度语音唤醒检测器
 *
 * 基于百度语音唤醒 SDK 实现
 * 百度 SDK 使用 EventManager 自动管理音频采集，不需要手动传入音频数据
 *
 * 集成步骤：
 * 1. 从 https://ai.baidu.com/sdk 下载语音识别离在线融合 SDK
 * 2. 将 bdasr_V3_xxx_xxx.jar 放入 module_voice/libs/
 * 3. 将 jniLibs 下的 SO 库放入 module_voice/src/main/jniLibs/
 * 4. 将唤醒词模型文件（如 WakeUp.bin）放入 module_voice/src/main/assets/
 * 5. 在 build.gradle.kts 中添加 implementation(files("libs/bdasr_V3_xxx_xxx.jar"))
 * 6. 在 AndroidManifest.xml 中配置 meta-data 和 Service（已完成）
 *
 * 百度应用凭证：
 * - AppID: 123301672
 * - API Key: 7bqc6ovRERcTumcd4h2dXhyj
 * - Secret Key: kuVbgAvSYkVPMcDWDjMkG5KlJZBLts3
 *
 * 当前状态：SDK 未集成，使用占位实现
 * TODO_BAIDU_SDK: 下载 SDK 后取消注释并删除占位代码
 */
class BaiduWakeWordDetector(
    private val context: Context,
    private val appId: String,
    private val apiKey: String,
    private val secretKey: String,
    private val wakeWordAssetPath: String = "WakeUp.bin",
    private val onWakeWordDetected: (String) -> Unit
) : WakeWordDetector {

    companion object {
        private const val TAG = "BaiduWakeWordDetector"
    }

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

        // TODO_BAIDU_SDK: 下载百度 SDK 后替换为以下代码
        //
        // import com.baidu.speech.EventListener
        // import com.baidu.speech.EventManager
        // import com.baidu.speech.EventManagerFactory
        // import com.baidu.speech.asr.SpeechConstant
        //
        // eventManager = EventManagerFactory.create(context, "wp")
        // eventManager?.registerListener(object : EventListener {
        //     override fun onEvent(name: String?, params: String?, data: ByteArray?, offset: Int, length: Int) {
        //         when (name) {
        //             "wp.data" -> {
        //                 // 唤醒词检测成功
        //                 onWakeWordDetected.invoke("小智小智")
        //         }
        //     }
        // })

        isInitialized = true
        Timber.i("$TAG: Initialized (placeholder mode - SDK not yet integrated)")
        Timber.w("$TAG: TODO: Download Baidu SDK from https://ai.baidu.com/sdk to enable wake word detection")
    }

    /**
     * 开始监听唤醒词
     *
     * TODO_BAIDU_SDK: 下载 SDK 后替换为：
     * val params = HashMap<String, Any>()
     * params["kws-file"] = "assets:///$wakeWordAssetPath"
     * params["kws-sensitivity"] = "0.7"
     * eventManager?.send(SpeechConstant.WAKEUP_START, JSONObject(params).toString(), null, 0, 0)
     */
    fun startListening() {
        if (!isInitialized) {
            Timber.w("$TAG: Not initialized, cannot start listening")
            return
        }

        isListening = true
        Timber.i("$TAG: Started listening (placeholder mode)")
        Timber.w("$TAG: Baidu SDK not integrated - wake word detection will not work until SDK is added")
    }

    /**
     * 停止监听唤醒词
     */
    fun stopListening() {
        isListening = false
        Timber.i("$TAG: Stopped listening")
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
        isInitialized = false
        Timber.i("$TAG: Released")
    }
}
