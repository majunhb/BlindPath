package com.blindpath.module_voice.service

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 全局语音状态机
 * 
 * 统一管理 TTS/ASR/唤醒引擎的状态转换，从根本上解决状态竞争和死循环。
 * 
 * 状态转换规则：
 * IDLE → LISTENING_WAKE (启动持续监听)
 * LISTENING_WAKE → WAKE_DETECTED (唤醒词被检测到)
 * WAKE_DETECTED → LISTENING_CMD (播报"我在"后启动指令监听)
 * LISTENING_CMD → PROCESSING (收到指令)
 * PROCESSING → SPEAKING (开始播报执行结果)
 * SPEAKING → LISTENING_WAKE (播报完成后回到待机)
 * 
 * 任何状态 → ERROR (异常) → LISTENING_WAKE (自动恢复)
 * 任何状态 + TTS请求 → TTS 独立运行（DUCK模式，不中断ASR）
 * 
 * 音频焦点优先级：
 * 避障预警 > 导航播报 > AI对话 > 系统提示
 */
class VoiceStateMachine {
    
    enum class VoiceState {
        IDLE,               // 空闲，等待初始化
        LISTENING_WAKE,     // 持续监听唤醒词
        WAKE_DETECTED,      // 唤醒词检测到，准备播报"我在"
        LISTENING_CMD,      // 监听用户指令
        PROCESSING,         // 处理/执行指令中
        SPEAKING,           // TTS播报中
        ERROR               // 错误状态
    }
    
    data class StateContext(
        val state: VoiceState = VoiceState.IDLE,
        val previousState: VoiceState = VoiceState.IDLE,
        val activeTtsCount: Int = 0,        // 活跃TTS播报计数（支持嵌套）
        val wakeWordSource: String = "",     // 唤醒词来源（baidu/xf/speech_recognizer）
        val lastError: String? = null,
        val stateTimestamp: Long = System.currentTimeMillis()
    )
    
    private val _context = MutableStateFlow(StateContext())
    val context: StateFlow<StateContext> = _context.asStateFlow()
    
    val currentState: VoiceState get() = _context.value.state
    
    /**
     * 尝试状态转换
     * @return 是否转换成功
     */
    fun transition(target: VoiceState, metadata: Map<String, String> = emptyMap()): Boolean {
        val current = _context.value.state
        
        val allowed = when (current) {
            VoiceState.IDLE -> setOf(VoiceState.LISTENING_WAKE, VoiceState.ERROR)
            VoiceState.LISTENING_WAKE -> setOf(VoiceState.WAKE_DETECTED, VoiceState.SPEAKING, VoiceState.ERROR, VoiceState.IDLE)
            VoiceState.WAKE_DETECTED -> setOf(VoiceState.LISTENING_CMD, VoiceState.SPEAKING, VoiceState.ERROR)
            VoiceState.LISTENING_CMD -> setOf(VoiceState.PROCESSING, VoiceState.SPEAKING, VoiceState.LISTENING_WAKE, VoiceState.ERROR)
            VoiceState.PROCESSING -> setOf(VoiceState.SPEAKING, VoiceState.LISTENING_WAKE, VoiceState.ERROR)
            VoiceState.SPEAKING -> setOf(VoiceState.LISTENING_WAKE, VoiceState.LISTENING_CMD, VoiceState.ERROR)
            VoiceState.ERROR -> setOf(VoiceState.LISTENING_WAKE, VoiceState.IDLE)
        }
        
        if (target !in allowed) {
            Timber.w("VoiceStateMachine: Invalid transition $current → $target (allowed: $allowed)")
            return false
        }
        
        val wakeSource = metadata["wakeWordSource"] ?: _context.value.wakeWordSource
        val error = metadata["error"] ?: if (target == VoiceState.ERROR) "Unknown error" else null
        
        _context.value = StateContext(
            state = target,
            previousState = current,
            activeTtsCount = _context.value.activeTtsCount,
            wakeWordSource = wakeSource,
            lastError = error,
            stateTimestamp = System.currentTimeMillis()
        )
        
        Timber.i("VoiceStateMachine: $current → $target${if (wakeSource.isNotEmpty()) " (source: $wakeSource)" else ""}")
        return true
    }
    
    /**
     * TTS 播报开始（引用计数，支持嵌套）
     */
    fun ttsStarted() {
        _context.value = _context.value.copy(activeTtsCount = _context.value.activeTtsCount + 1)
        Timber.d("VoiceStateMachine: TTS started (count: ${_context.value.activeTtsCount})")
    }
    
    /**
     * TTS 播报结束（引用计数递减）
     * 当计数归零时，根据当前状态决定下一步
     */
    fun ttsFinished() {
        val newCount = maxOf(0, _context.value.activeTtsCount - 1)
        _context.value = _context.value.copy(activeTtsCount = newCount)
        Timber.d("VoiceStateMachine: TTS finished (count: $newCount)")
    }
    
    /**
     * 是否有任何 TTS 正在播报
     */
    fun isTtsSpeaking(): Boolean = _context.value.activeTtsCount > 0
    
    /**
     * 是否允许 ASR 启动
     * 当 TTS 正在播报时，ASR 使用 DUCK 模式（降低音量但不中断）
     */
    fun canStartListening(): Boolean {
        return _context.value.state in setOf(
            VoiceState.LISTENING_WAKE, 
            VoiceState.LISTENING_CMD,
            VoiceState.WAKE_DETECTED
        )
    }
    
    /**
     * 强制重置到指定状态（用于异常恢复）
     */
    fun forceReset(target: VoiceState = VoiceState.LISTENING_WAKE) {
        Timber.w("VoiceStateMachine: Force reset from ${_context.value.state} to $target")
        _context.value = StateContext(
            state = target,
            previousState = _context.value.state,
            activeTtsCount = 0,
            wakeWordSource = "",
            lastError = null
        )
    }
}
