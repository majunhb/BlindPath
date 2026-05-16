package com.blindpath.base.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import java.util.*

/**
 * 语音播报服务 - 为视障用户提供语音反馈
 * 已下沉到 base 模块，供所有服务直接使用
 */
class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingText: String? = null

    // 语音合成完成回调
    var onSpeakComplete: (() -> Unit)? = null

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 设置中文语音
                val result = tts?.setLanguage(Locale.CHINESE)
                isInitialized = result != TextToSpeech.LANG_MISSING_DATA &&
                        result != TextToSpeech.LANG_NOT_SUPPORTED

                if (!isInitialized) {
                    // 回退到英文
                    tts?.setLanguage(Locale.US)
                    isInitialized = true
                }

                // 设置语速（稍慢，方便视障用户理解）
                tts?.setSpeechRate(0.9f)

                // 设置音调
                tts?.setPitch(1.0f)

                // 设置监听器
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        onSpeakComplete?.invoke()
                    }
                    override fun onError(utteranceId: String?) {}
                })

                // 如果有等待播报的文本
                pendingText?.let {
                    speak(it)
                    pendingText = null
                }

                Log.d(TAG, "TTS initialized successfully")
            } else {
                Log.e(TAG, "TTS initialization failed: $status")
            }
        }
    }

    /**
     * 播报文本
     */
    fun speak(text: String, flush: Boolean = false) {
        if (!isInitialized) {
            pendingText = text
            return
        }

        if (flush) {
            tts?.stop()
        }

        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, text.hashCode().toString())
    }

    /**
     * 播报并等待完成
     */
    fun speakAndWait(text: String) {
        speak(text, flush = true)
    }

    /**
     * 停止播报
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * 释放资源
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    /**
     * 检查是否正在播报
     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    companion object {
        private const val TAG = "TtsManager"

        // 常用播报文本
        const val MSG_APP_READY = "智行助盲应用已启动"
        const val MSG_OBSTACLE_DETECTED = "注意，前方有障碍物"
        const val MSG_SAFE_TO_GO = "前方安全，可以继续前行"
        const val MSG_CAMERA_STARTED = "障碍物检测已开启"
        const val MSG_CAMERA_STOPPED = "障碍物检测已关闭"
        const val MSG_LOCATION_UPDATED = "位置已更新"
        const val MSG_SOS_SENT = "紧急求助已发送"
        const val MSG_CALLING = "正在拨打"
    }
}
