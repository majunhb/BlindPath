package com.blindpath.module_voice.service

import android.content.Context
import com.baidu.speech.EventListener
import com.baidu.speech.EventManager
import com.baidu.speech.EventManagerFactory
import com.baidu.speech.asr.SpeechConstant
import com.blindpath.module_voice.domain.model.WakeWordConfig
import org.json.JSONObject
import timber.log.Timber

/**
 * 百度语音唤醒检测器
 *
 * 基于百度语音唤醒 SDK（ASR V3 3.5.1）实现
 *
 * SDK 调用链（反编译验证）：
 *   EventManagerFactory.create(context, "wp", true)
 *     -> new EventManagerRemote2Local(context, "wp")  // 远程 AIDL 模式
 *   -> eventManagerRemote2Local.send("wp.start", jsonParams, ...)
 *     -> bindService(EventRecognitionService) 绑定远程服务
 *     -> Handler.postDelayed() 在主线程通过 AIDL remoteEM 转发到远程服务
 *   -> 远程服务处理 -> native 唤醒引擎 -> AIDL 回调到 EventListener
 *
 * 回调事件名（SpeechConstant 定义）：
 *   "wp.data"  - 唤醒成功
 *   "wp.error" - 唤醒错误
 *   "wp.ready" - 引擎就绪
 *   "wp.exit"  - 引擎退出
 *   "wp.stoped" - 唤醒停止
 *
 * 百度应用凭证：通过 BuildConfig 传入，见 local.properties
 */
class BaiduWakeWordDetector(
    private val context: Context,
    private val appId: String,
    private val apiKey: String,
    private val secretKey: String,
    private val wakeWordAssetPath: String = "WakeUp.bin",
    private val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD,
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
        if (appId.isBlank()) {
            throw IllegalArgumentException("Baidu AppID cannot be empty")
        }

        // 使用远程 AIDL 模式 (useRemote=true)
        // EventManagerRemote2Local 通过 bindService 绑定 EventRecognitionService，
        // 通过 AIDL 接口与远程服务通信
        // 服务端负责初始化 native 唤醒引擎，可能会获得更完整的错误信息
        wp = EventManagerFactory.create(context, "wp", true)  // useRemote=true

        if (wp == null) {
            throw IllegalStateException("Failed to create EventManager (AIDL mode)")
        }

        // 注册事件监听器
        wp?.registerListener(eventListener)

        isInitialized = true
        Timber.i("$TAG: Initialized successfully with direct EventManagerFactory (AppID: $appId)")
    }

    /**
     * 事件监听器 - 处理百度 SDK 回调
     */
    private val eventListener = EventListener { name, params, data, offset, length ->
        Timber.d("$TAG: onEvent name=$name, params=$params, dataLen=$length")

        when (name) {
            "wp.data" -> {
                // 唤醒词检测成功
                Timber.i("$TAG: ★★★ Wake word detected! params: $params")
                try {
                    val json = JSONObject(params)
                    val desc = json.optString("desc", "")
                    val errorCode = json.optInt("error", 0)
                    val word = json.optString("word", "")

                    Timber.i("$TAG: Detection result - desc: $desc, word: $word, error: $errorCode")

                    if (errorCode == 0) {
                        val detectedWord = word.ifEmpty { desc.ifEmpty { wakeWord } }
                        onWakeWordDetected.invoke(detectedWord)
                    } else {
                        Timber.w("$TAG: Wake up returned error code: $errorCode")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to parse wake up result, triggering callback anyway")
                    onWakeWordDetected.invoke(wakeWord)
                }
            }

            "wp.error" -> {
                Timber.e("$TAG: Wake up error event: $params")
                try {
                    val json = JSONObject(params)
                    val errorCode = json.optInt("error", -1)
                    val errorDesc = json.optString("desc", "unknown")
                    Timber.e("$TAG: Error code: $errorCode, desc: $errorDesc")
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to parse error params")
                }
            }

            "wp.ready" -> {
                Timber.i("$TAG: ★ Wake up engine READY - now listening for '$wakeWord'")
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
                Timber.d("$TAG: Unhandled event: $name, params: $params")
            }
        }
    }

    /**
     * 开始监听唤醒词
     *
     * 根据反编译 WakeUpControl.initWp()，需要传入以下参数：
     * - "appid": 应用 ID
     * - "kws-file": 唤醒词模型文件路径
     * - "words": 唤醒词文本列表（JSONArray 格式）
     * - "accept-audio-volume": 是否接受音频音量回调
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
            val params = JSONObject()

            // 唤醒词模型文件路径（assets 目录）
            // 注意：正确格式是 assets://WakeUp.bin，不是 assets:///WakeUp.bin
            params.put("kws-file", "assets://$wakeWordAssetPath")

            // appid - WakeUpControl.initWp() 需要从 params 中提取
            params.put("appid", appId)

            // 唤醒词文本列表 - WakeUpControl.initWp() 读取 "words" JSONArray
            val wordsArray = org.json.JSONArray()
            wordsArray.put(wakeWord)
            params.put("words", wordsArray)

            // 音频音量回调（调试用）
            params.put("accept-audio-volume", true)

            val json = params.toString()
            Timber.i("$TAG: Starting wake-up with params: $json")

            // SpeechConstant.WAKEUP_START = "wp.start"
            wp?.send(SpeechConstant.WAKEUP_START, json, null, 0, 0)
            isListening = true
            Timber.i("$TAG: Wake-up start command sent, waiting for engine ready...")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start listening")
            isListening = false
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
