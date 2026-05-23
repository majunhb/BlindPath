package com.blindpath.module_voice.domain

import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.model.VoiceRequest
import com.blindpath.module_voice.domain.model.VoiceState
import com.blindpath.module_voice.domain.model.VoiceStatistics
import com.blindpath.module_voice.domain.model.VoiceType
import kotlinx.coroutines.flow.Flow

interface VoiceRepository {
    val voiceState: Flow<VoiceState>
    val statistics: Flow<VoiceStatistics>

    suspend fun initialize(): Result<Boolean>
    suspend fun speak(text: String, queueMode: Boolean = true): Result<Boolean>
    suspend fun stop(): Result<Boolean>
    suspend fun pause(): Result<Boolean>
    suspend fun resume(): Result<Boolean>
    fun release()

    /**
     * 分级播报（推荐使用）
     * 
     * 根据播报类型自动确定优先级，支持去重、冷却、打断等智能策略
     */
    suspend fun announce(request: VoiceRequest): Result<Boolean>

    /**
     * 便捷方法：播报指定类型的内容
     */
    suspend fun announce(text: String, type: VoiceType): Result<Boolean> {
        return announce(VoiceRequest(text = text, type = type))
    }

    /**
     * 清空播报队列
     */
    suspend fun clearQueue(): Result<Boolean>

    /**
     * 获取当前队列大小
     */
    fun getQueueSize(): Int

    // ========== 保留旧方法以兼容现有代码 ==========

    /**
     * 播报避障预警（高优先级，立即打断当前播报）
     * @deprecated 请使用 announce(text, VoiceType.OBSTACLE_DANGER)
     */
    suspend fun speakObstacleAlert(text: String) {
        announce(text, VoiceType.OBSTACLE_DANGER)
    }

    /**
     * 播报导航指令（低优先级，不打断避障预警）
     * @deprecated 请使用 announce(text, VoiceType.NAVIGATION_TURN)
     */
    suspend fun speakNavigation(text: String) {
        announce(text, VoiceType.NAVIGATION_TURN)
    }
}
