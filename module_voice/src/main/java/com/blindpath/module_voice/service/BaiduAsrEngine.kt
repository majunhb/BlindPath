package com.blindpath.module_voice.service

import android.content.Context
import com.baidu.speech.EventListener
import com.baidu.speech.EventManager
import com.baidu.speech.EventManagerFactory
import com.baidu.speech.asr.SpeechConstant
import org.json.JSONObject
import timber.log.Timber

/**
 * 百度语音识别引擎 (ASR)
 *
 * 基于百度语音 SDK ASR V3 实现中文语音识别。
 * SDK 内部管理 AudioRecord，自动采集音频。
 *
 * 使用方式：
 * 1. initialize() 初始化引擎
 * 2. 设置回调 (onResult, onError, onReady 等)
 * 3. startListening() 开始识别
 * 4. stopListening() 停止并获取最终结果
 * 5. release() 释放资源
 */
class BaiduAsrEngine(
    private val context: Context,
    private val appId: String,
    private val apiKey: String,
    private val secretKey: String
) {
    companion object {
        private const val TAG = "BaiduAsrEngine"

        // 百度ASR错误码
        const val ERROR_AUDIO = 1
        const val ERROR_NETWORK = 2
        const val ERROR_AUDIO_TOO_LONG = 3
        const val ERROR_SERVER = 4
        const val ERROR_EMPTY_RESULT = 5
        const val ERROR_NO_MATCH = 6
        const val ERROR_NOT_READY = 7
    }

    private var asrManager: EventManager? = null
    private var isListening = false
    var isInitialized = false

    // 回调接口
    var onResult: ((text: String, isFinal: Boolean) -> Unit)? = null
    var onError: ((errorCode: Int, errorMsg: String) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onBegin: (() -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    var onVolume: ((Float) -> Unit)? = null

    /**
     * 初始化ASR引擎
     * @return true 成功, false 失败
     */
    fun initialize(): Boolean {
        if (appId.isBlank()) {
            Timber.e("$TAG: Baidu AppID is empty, cannot initialize ASR")
            return false
        }

        return try {
            asrManager = EventManagerFactory.create(context, "asr", false)
            asrManager?.registerListener(eventListener)
            isInitialized = asrManager != null
            if (isInitialized) {
                Timber.i("$TAG: ASR engine initialized (AppID: $appId)")
            } else {
                Timber.e("$TAG: EventManagerFactory returned null")
            }
            isInitialized
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize ASR engine")
            isInitialized = false
            false
        }
    }

    private val eventListener = EventListener { name, params, _, _, _ ->
        try {
            handleEvent(name, params)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error in event callback")
        }
    }

    private fun handleEvent(name: String, params: String?) {
        Timber.d("$TAG: onEvent name=${name}, params=${params?.take(200)}")

        when (name) {
            "asr.ready" -> {
                Timber.i("$TAG: ASR engine READY")
                onReady?.invoke()
            }

            "asr.begin" -> {
                Timber.i("$TAG: Speech detected, recognition started")
                onBegin?.invoke()
            }

            "asr.end" -> {
                Timber.i("$TAG: Speech ended")
                onEnd?.invoke()
            }

            "asr.result" -> {
                val text = parseResult(params)
                Timber.i("$TAG: ★ ASR Final Result: \"${text}\"")
                if (text.isNotBlank()) {
                    onResult?.invoke(text, true)
                }
            }

            "asr.partial" -> {
                val text = parsePartialResult(params)
                if (text.isNotBlank()) {
                    Timber.d("$TAG: Partial: \"${text}\"")
                    onResult?.invoke(text, false)
                }
            }

            "asr.volume" -> {
                try {
                    val json = JSONObject(params ?: "{}")
                    val vol = json.optDouble("volume", 0.0).toFloat()
                    onVolume?.invoke(vol)
                } catch (_: Exception) {}
            }

            "asr.error" -> {
                val (code, msg) = parseError(params)
                Timber.e("$TAG: ASR Error - code=${code}, msg=${msg}")
                isListening = false
                onError?.invoke(code, msg)
            }

            "asr.stoped", "asr.exit" -> {
                Timber.i("$TAG: ASR stopped/exit")
                isListening = false
            }

            else -> {
                Timber.d("$TAG: Unhandled event: ${name}")
            }
        }
    }

    /**
     * 解析最终识别结果
     */
    private fun parseResult(params: String?): String {
        if (params.isNullOrBlank()) return ""
        return try {
            val json = JSONObject(params)
            val resultsArray = json.optJSONArray("result")
            if (resultsArray != null && resultsArray.length() > 0) {
                resultsArray.getString(0)
            } else {
                json.optString("best_result", json.optString("result_recognition", ""))
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse result")
            ""
        }
    }

    /**
     * 解析部分识别结果
     */
    private fun parsePartialResult(params: String?): String {
        if (params.isNullOrBlank()) return ""
        return try {
            val json = JSONObject(params)
            val resultsArray = json.optJSONArray("result")
            if (resultsArray != null && resultsArray.length() > 0) {
                resultsArray.getString(0)
            } else {
                json.optString("best_result", "")
            }
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * 解析错误信息
     */
    private fun parseError(params: String?): Pair<Int, String> {
        if (params.isNullOrBlank()) return Pair(-1, "Unknown error")
        return try {
            val json = JSONObject(params)
            val code = json.optInt("error", -1)
            val subCode = json.optInt("subcode", -1)
            val desc = json.optString("desc", json.optString("error-desc", "Unknown"))
            val msg = if (subCode > 0) "${desc} (subcode: ${subCode})" else desc
            Pair(code, msg)
        } catch (e: Exception) {
            Pair(-1, "Parse error: ${e.message}")
        }
    }

    /**
     * 开始ASR识别
     * SDK内部管理AudioRecord，自动采集音频
     */
    fun startListening() {
        if (!isInitialized || asrManager == null) {
            Timber.w("$TAG: Not initialized, attempting re-init")
            if (!initialize()) {
                Timber.e("$TAG: Re-init failed, cannot start listening")
                return
            }
        }

        if (isListening) {
            Timber.d("$TAG: Already listening, stop first")
            stopListening()
        }

        try {
            val params = JSONObject().apply {
                // 百度ASR参数 — 使用字符串key（同BaiduWakeWordDetector风格）
                put("appid", appId)
                put(SpeechConstant.API_KEY, apiKey)
                put(SpeechConstant.SECRET, secretKey)
                put(SpeechConstant.LANGUAGE, "cmn-Hans-CN")
                put(SpeechConstant.SAMPLE_RATE, "16000")
                // PID 1536: 普通话(有标点)
                put("pid", 1536)
                // DNN VAD
                put("vad", SpeechConstant.VAD_DNN)
                // 音量回调
                put(SpeechConstant.ACCEPT_AUDIO_VOLUME, true)
                // 静音超时(ms): 说话后1500ms静音认为结束
                put(SpeechConstant.VAD_ENDPOINT_TIMEOUT, 1500)
                // 启用长语音模式（避免长指令被截断）
                put(SpeechConstant.BDS_ASR_ENABLE_LONG_SPEECH, false)
            }

            val json = params.toString()
            Timber.i("$TAG: Starting ASR with params")

            asrManager?.send(SpeechConstant.ASR_START, json, null, 0, 0)
            isListening = true
            Timber.i("$TAG: ASR started, waiting for speech...")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start ASR")
            isListening = false
        }
    }

    /**
     * 停止ASR识别
     * 调用后会触发最终结果的回调
     */
    fun stopListening() {
        if (asrManager == null || !isListening) return
        try {
            asrManager?.send(SpeechConstant.ASR_STOP, null, null, 0, 0)
            isListening = false
            Timber.i("$TAG: ASR stopped")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error stopping ASR")
        }
    }

    /**
     * 取消ASR识别（不获取结果）
     */
    fun cancel() {
        if (asrManager == null) return
        try {
            asrManager?.send(SpeechConstant.ASR_CANCEL, null, null, 0, 0)
            isListening = false
            Timber.d("$TAG: ASR cancelled")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error cancelling ASR")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        cancel()
        try {
            asrManager?.unregisterListener(eventListener)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error unregistering listener")
        }
        asrManager = null
        isInitialized = false
        Timber.i("$TAG: Released")
    }
}
