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
    private val wakeWordAssetPath: String = WakeWordConfig.BAIDU_WAKE_WORD_ASSET,
    private val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD,
    private val onWakeWordDetected: (String) -> Unit
) : WakeWordDetector {

    companion object {
        private const val TAG = "BaiduWakeWordDetector"
    }

    /** 记录初始化异常，用于 startListening 时重试（必须在 init{} 之前声明） */
    private var initializationError: Exception? = null

    private var wp: EventManager? = null
    private var isListening = false
    var isInitialized = false

    init {
        try {
            initialize()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize in init block, will retry in startListening")
            isInitialized = false
            initializationError = e
        }
    }

    private fun initialize() {
        if (appId.isBlank()) {
            throw IllegalArgumentException("Baidu AppID cannot be empty")
        }

        // 使用进程内模式 (useRemote=false)
        // AIDL 远程模式 (useRemote=true) 存在竞态条件：
        // bindService() 是异步的，但 SDK 内部事件回调 Runnable 在 Handler 上 post 时
        // 引用的 EventListener 可能为 null，导致 NullPointerException 崩溃
        // 进程内模式直接在当前进程创建事件管理器，不存在异步绑定问题
        try {
            wp = EventManagerFactory.create(context, "wp", false)  // useRemote=false，进程内模式
        } catch (e: NullPointerException) {
            // 百度 SDK 内部 EventListener 为 null 时会抛出 NPE
            // 这是 SDK 已知问题，在部分 Android 版本/设备上复现
            Timber.e(e, "$TAG: EventManagerFactory.create() threw NPE (SDK bug), marking as unavailable")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "$TAG: EventManagerFactory.create() failed")
            throw e
        }

        if (wp == null) {
            throw IllegalStateException("Failed to create EventManager (AIDL mode)")
        }

        // 注册事件监听器（必须在 create 成功后立即注册，防止 SDK 内部回调时 listener 为 null）
        try {
            wp?.registerListener(eventListener)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to register event listener")
            wp = null
            throw IllegalStateException("Failed to register event listener: ${e.message}")
        }

        isInitialized = true
        Timber.i("$TAG: Initialized successfully with direct EventManagerFactory (AppID: $appId)")
    }

    /**
     * 事件监听器 - 处理百度 SDK 回调
     */
    private val eventListener = EventListener { name, params, data, offset, length ->
        try {
            handleEvent(name, params, data, offset, length)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error in event callback")
        }
    }

    private fun handleEvent(name: String, params: String?, data: ByteArray?, offset: Int, length: Int) {
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
                        val detectedWord = (word.ifEmpty { desc.ifEmpty { wakeWord } }).toString()
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
    override fun startListening() {
        // 检查是否已初始化
        if (!isInitialized || wp == null) {
            Timber.w("$TAG: Not initialized, attempting re-initialization")
            val err = initializationError
            if (err != null) {
                Timber.i("$TAG: Previous error: ${err.message}")
                try {
                    initialize()
                    if (isInitialized) {
                        Timber.i("$TAG: Re-initialization succeeded")
                        initializationError = null
                    } else {
                        Timber.e("$TAG: Re-initialization still failed")
                        return
                    }
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Re-initialization also failed, giving up")
                    return
                }
            } else {
                Timber.w("$TAG: Not initialized but no previous error recorded, cannot re-initialize")
                return
            }
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
