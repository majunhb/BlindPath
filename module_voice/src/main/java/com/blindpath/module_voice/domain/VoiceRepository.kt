package com.blindpath.module_voice.domain

import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.model.VoiceState
import kotlinx.coroutines.flow.Flow

interface VoiceRepository {
    val voiceState: Flow<VoiceState>

    suspend fun initialize(): Result<Boolean>
    suspend fun speak(text: String, queueMode: Boolean = true): Result<Boolean>
    suspend fun stop(): Result<Boolean>
    suspend fun pause(): Result<Boolean>
    suspend fun resume(): Result<Boolean>
    fun release()
}
