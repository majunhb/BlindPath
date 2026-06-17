package com.blindpath.module_voice.service

import com.blindpath.module_voice.domain.WakeWordDetector

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
    private var callback: WakeWordDetector.Callback? = null

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

        try {
            wp = EventManagerFactory.create(context, "wp", false)
        } catch (e: NullPointerException) {
            Timber.e(e, "$TAG: EventManagerFactory.create() threw NPE (SDK bug), marking as unavailable")
            throw e
        } catch (e: Exception) {
            Timber.e(e, "$TAG: EventManagerFactory.create() failed")
            throw e
        }

        if (wp == null) {
            throw IllegalStateException("Failed to create EventManager (AIDL mode)")
        }

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
                Timber.i("$TAG: Wake word detected! params: $params")
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
            }

            "wp.ready" -> {
                Timber.i("$TAG: Wake up engine READY - now listening for '$wakeWord'")
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

    override fun start(): Boolean {
        startListening()
        return true
    }

    fun startListening() {
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
            params.put("kws-file", "assets://$wakeWordAssetPath")
            params.put("appid", appId)
            val wordsArray = org.json.JSONArray()
            wordsArray.put(wakeWord)
            for (alias in WakeWordConfig.WAKE_WORD_ALIASES) {
                if (alias != wakeWord) wordsArray.put(alias)
            }
            params.put("words", wordsArray)
            params.put("accept-audio-volume", true)

            val json = params.toString()
            Timber.i("$TAG: Starting wake-up with params: $json")

            wp?.send(SpeechConstant.WAKEUP_START, json, null, 0, 0)
            isListening = true
            Timber.i("$TAG: Wake-up start command sent, waiting for engine ready...")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start listening")
            isListening = false
        }
    }

    override fun stop() {
        stopListening()
    }

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

    override fun setCallback(callback: WakeWordDetector.Callback) {
        this.callback = callback
    }

    override fun setSensitivity(sens: Float) {
        Timber.d("$TAG: Set sensitivity: $sens")
    }

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
