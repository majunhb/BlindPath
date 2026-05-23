package com.blindpath.module_voice.domain

import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.model.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * 语音交互管理器
 * 
 * 统一管理 TTS 播报和语音指令识别
 * 提供完整的语音交互体验
 */
interface VoiceInteractionManager {
    val interactionState: StateFlow<VoiceInteractionState>
    val isInitialized: Boolean
    
    /**
     * 初始化语音交互系统
     */
    suspend fun initialize(): Result<Boolean>
    
    /**
     * 播报欢迎消息
     */
    suspend fun speakWelcome()
    
    /**
     * 播报帮助信息
     */
    suspend fun speakHelp()
    
    /**
     * 播报指定文本
     */
    suspend fun speak(text: String, type: VoiceType = VoiceType.SYSTEM_STATUS)
    
    /**
     * 开始监听语音指令
     */
    suspend fun startListening(): Result<Boolean>
    
    /**
     * 停止监听
     */
    suspend fun stopListening(): Result<Boolean>
    
    /**
     * 处理语音指令
     * @return 是否成功处理
     */
    suspend fun handleCommand(command: VoiceCommand): Boolean
    
    /**
     * 设置指令执行回调
     */
    fun setCommandExecutor(executor: VoiceCommandExecutor)
    
    /**
     * 释放资源
     */
    fun release()
}

/**
 * 语音指令执行器接口
 * 
 * 由 UI 层实现，用于执行具体的指令操作
 */
interface VoiceCommandExecutor {
    suspend fun executeCommand(command: VoiceCommand): Boolean
}
