package com.blindpath.module_voice.domain

interface WakeWordDetector {
    interface Callback {
        fun onWakeWordDetected(wakeWord: String, confidence: Float)
        fun onAudioLevelChanged(level: Float)
        fun onError(errorCode: Int, errorMessage: String)
        fun onDetectionTimeout()
    }
    
    companion object {
        const val ERROR_NONE = 0
        const val ERROR_MIC_PERMISSION = 1
        const val ERROR_AUDIO_INIT = 2
        const val ERROR_MODEL_LOAD = 3
        const val ERROR_UNKNOWN = 99
    }
    
    fun setCallback(callback: Callback)
    fun start(): Boolean
    fun stop()
    fun release()
    fun setSensitivity(sens: Float)
}
