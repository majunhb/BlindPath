package com.blindpath.app.service

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.*

/**
 * 璇煶鎾姤鏈嶅姟 - 涓鸿闅滅敤鎴锋彁渚涜闊冲弽棣? */
class TtsManager(private val context: Context) {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private var pendingText: String? = null

    // 璇煶鍚堟垚瀹屾垚鍥炶皟
    var onSpeakComplete: (() -> Unit)? = null

    init {
        initTts()
    }

    private fun initTts() {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                // 璁剧疆涓枃璇煶
                val result = tts?.setLanguage(Locale.CHINESE)
                isInitialized = result != TextToSpeech.LANG_MISSING_DATA &&
                                 result != TextToSpeech.LANG_NOT_SUPPORTED

                if (!isInitialized) {
                    // 鍥為€€鍒拌嫳鏂?                    tts?.setLanguage(Locale.US)
                    isInitialized = true
                }

                // 璁剧疆璇€燂紙绋嶆參锛屾柟渚胯闅滅敤鎴风悊瑙ｏ級
                tts?.setSpeechRate(0.9f)

                // 璁剧疆闊宠皟
                tts?.setPitch(1.0f)

                // 璁剧疆鐩戝惉鍣?                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        onSpeakComplete?.invoke()
                    }
                    override fun onError(utteranceId: String?) {}
                })

                // 濡傛灉鏈夌瓑寰呮挱鎶ョ殑鏂囨湰
                pendingText?.let {
                    speak(it)
                    pendingText = null
                }
            }
        }
    }

    /**
     * 鎾姤鏂囨湰
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
     * 鎾姤骞剁瓑寰呭畬鎴?     */
    fun speakAndWait(text: String) {
        speak(text, flush = true)
    }

    /**
     * 鍋滄鎾姤
     */
    fun stop() {
        tts?.stop()
    }

    /**
     * 閲婃斁璧勬簮
     */
    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
    }

    /**
     * 妫€鏌ユ槸鍚︽鍦ㄦ挱鎶?     */
    fun isSpeaking(): Boolean {
        return tts?.isSpeaking == true
    }

    companion object {
        // 甯哥敤鎾姤鏂囨湰
        const val MSG_APP_READY = "鏅鸿鍔╃洸搴旂敤宸插惎鍔?
        const val MSG_OBSTACLE_DETECTED = "娉ㄦ剰锛屽墠鏂规湁闅滅鐗?
        const val MSG_SAFE_TO_GO = "鍓嶆柟瀹夊叏锛屽彲浠ョ户缁墠琛?
        const val MSG_CAMERA_STARTED = "闅滅鐗╂娴嬪凡寮€鍚?
        const val MSG_CAMERA_STOPPED = "闅滅鐗╂娴嬪凡鍏抽棴"
        const val MSG_LOCATION_UPDATED = "浣嶇疆宸叉洿鏂?
        const val MSG_SOS_SENT = "绱ф€ユ眰鍔╁凡鍙戦€?
        const val MSG_CALLING = "姝ｅ湪鎷ㄦ墦"
    }
}
