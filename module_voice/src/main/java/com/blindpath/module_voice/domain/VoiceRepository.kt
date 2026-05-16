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

    /**
     * 播报避障预警（高优先级，立即打断当前播报）
     */
    suspend fun speakObstacleAlert(text: String)

    /**
     * 播报导航指令（低优先级，不打断避障预警）
     */
    suspend fun speakNavigation(text: String)
}
