package com.blindpath.module_voice.domain.model

/**
 * 语音状态
 */
data class VoiceState(
    val isAvailable: Boolean = false,
    val isSpeaking: Boolean = false,
    val isListening: Boolean = false,
    val isWakeUp: Boolean = false,
    val lastError: String? = null
)
