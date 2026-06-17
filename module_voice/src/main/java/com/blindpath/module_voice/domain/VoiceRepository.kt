package com.blindpath.module_voice.domain

import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.model.VoiceRequest
import com.blindpath.module_voice.domain.model.VoiceState
import com.blindpath.module_voice.domain.model.VoiceStatistics
import com.blindpath.module_voice.domain.model.VoiceType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 语音播报仓库接口
 * 
 * 架构级重构 v3.0 - 修复语音播报集成
 * 
 * 核心职责：
 * 1. TTS 初始化和管理
 * 2. 分级播报队列管理
 * 3. 语音去重和冷却
 * 4. 打断与恢复策略
 */
interface VoiceRepository {
    
    /** 语音状态流 */
    val voiceState: StateFlow<VoiceState>
    
    /** 播报统计流 */
    val statistics: Flow<VoiceStatistics>

    /**
     * 初始化 TTS
     */
    suspend fun initialize(): Result<Boolean>
    
    /**
     * 播报文本
     * @param text 播报文本
     * @param queueMode true=排队播报，false=立即打断当前播报
     */
    suspend fun speak(text: String, queueMode: Boolean = true): Result<Boolean>
    
    /**
     * 停止播报
     */
    suspend fun stop(): Result<Boolean>
    
    /**
     * 暂停播报
     */
    suspend fun pause(): Result<Boolean>
    
    /**
     * 恢复播报
     */
    suspend fun resume(): Result<Boolean>
    
    /**
     * 释放资源
     */
    fun release()

    /**
     * 分级播报（推荐使用）
     * 
     * 根据播报类型自动确定优先级，支持去重、冷却、打断等智能策略
     * 
     * @param request 播报请求，包含文本、类型、优先级等信息
     */
    suspend fun announce(request: VoiceRequest): Result<Boolean>

    /**
     * 便捷方法：播报指定类型的内容
     * 
     * @param text 播报文本
     * @param type 播报类型，决定优先级和打断策略
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

    // ========== 避障专用方法 ==========

    /**
     * 播报避障预警（高优先级，立即打断当前播报）
     * 
     * 这是视障用户最重要的功能，必须确保：
     * 1. 立即打断当前任何播报
     * 2. 快速响应（< 500ms）
     * 3. 危险预警使用紧急级别
     * 
     * @param text 预警文本
     */
    suspend fun speakObstacleAlert(text: String) {
        announce(VoiceRequest(
            text = text, 
            type = VoiceType.OBSTACLE_DANGER,
            priority = com.blindpath.module_voice.domain.model.VoicePriority.EMERGENCY,
            interruptCurrent = true  // 立即打断当前播报
        ))
    }

    /**
     * 播报导航指令（低优先级，不打断避障预警）
     * 
     * @param text 导航文本
     */
    suspend fun speakNavigation(text: String) {
        announce(VoiceRequest(
            text = text,
            type = VoiceType.NAVIGATION_TURN,
            priority = com.blindpath.module_voice.domain.model.VoicePriority.NORMAL,
            interruptCurrent = false  // 不打断避障预警
        ))
    }
}
