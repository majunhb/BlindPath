package com.blindpath.module_voice.domain

import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.model.VoiceCommand
import com.blindpath.module_voice.domain.model.VoiceCommandResult
import com.blindpath.module_voice.domain.model.VoiceInteractionState
import kotlinx.coroutines.flow.Flow

/**
 * 语音指令识别接口
 * 
 * 功能：
 * - 语音唤醒词检测
 * - 语音指令识别
 * - 指令解析与执行
 */
interface VoiceCommandRepository {
    val interactionState: Flow<VoiceInteractionState>
    
    /**
     * 初始化语音识别
     */
    suspend fun initialize(): Result<Boolean>
    
    /**
     * 开始监听语音指令
     */
    suspend fun startListening(): Result<Boolean>
    
    /**
     * 停止监听
     */
    suspend fun stopListening(): Result<Boolean>
    
    /**
     * 手动触发语音识别（不使用唤醒词）
     */
    suspend fun recognizeOnce(): Result<VoiceCommandResult>
    
    /**
     * 释放资源
     */
    fun release()
    
    /**
     * 设置唤醒词
     */
    fun setWakeWord(word: String)
    
    /**
     * 启用/禁用唤醒词
     */
    fun setWakeWordEnabled(enabled: Boolean)
}
