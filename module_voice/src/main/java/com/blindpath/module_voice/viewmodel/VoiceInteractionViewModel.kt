package com.blindpath.module_voice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceCommandExecutor
import com.blindpath.module_voice.domain.VoiceCommandRepository
import com.blindpath.module_voice.domain.VoiceInteractionManager
import com.blindpath.module_voice.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 语音交互 ViewModel
 * 
 * 管理 UI 层的语音交互状态
 * 同时观察 VoiceInteractionManager 和 VoiceCommandRepository 两个状态流
 * 确保唤醒词检测事件能正确触发 TTS 响应
 */
@HiltViewModel
class VoiceInteractionViewModel @Inject constructor(
    private val interactionManager: VoiceInteractionManager,
    private val voiceCommandRepository: VoiceCommandRepository
) : ViewModel(), VoiceCommandExecutor {
    
    private val _uiState = MutableStateFlow(VoiceInteractionUiState())
    val uiState: StateFlow<VoiceInteractionUiState> = _uiState.asStateFlow()
    
    // 指令执行回调（由外部设置）
    private var commandHandler: ((VoiceCommand) -> Boolean)? = null
    
    init {
        observeInteractionState()
        observeWakeWordState()
    }
    
    /**
     * 初始化语音交互
     */
    fun initialize() {
        viewModelScope.launch {
            _uiState.update { it.copy(isInitializing = true) }
            
            when (val result = interactionManager.initialize()) {
                is Result.Success -> {
                    _uiState.update { 
                        it.copy(
                            isInitialized = true,
                            isInitializing = false,
                            error = null
                        )
                    }
                    
                    // 播报欢迎消息
                    interactionManager.speakWelcome()
                }
                is Result.Error -> {
                    _uiState.update { 
                        it.copy(
                            isInitialized = false,
                            isInitializing = false,
                            error = result.message
                        )
                    }
                }
                is Result.Loading -> {
                    // 已经设置了 isInitializing = true，无需额外处理
                }
            }
        }
    }
    
    /**
     * 开始监听语音指令
     */
    fun startListening() {
        viewModelScope.launch {
            when (interactionManager.startListening()) {
                is Result.Success -> {
                    _uiState.update { it.copy(isListening = true) }
                }
                is Result.Error -> {
                    _uiState.update { it.copy(error = "启动语音识别失败") }
                }
                is Result.Loading -> {
                    // 启动监听不会返回 Loading 状态
                }
            }
        }
    }
    
    /**
     * 停止监听
     */
    fun stopListening() {
        viewModelScope.launch {
            interactionManager.stopListening()
            _uiState.update { it.copy(isListening = false) }
        }
    }
    
    /**
     * 播报文本
     */
    fun speak(text: String, type: VoiceType = VoiceType.SYSTEM_STATUS) {
        viewModelScope.launch {
            interactionManager.speak(text, type)
        }
    }
    
    /**
     * 播报帮助信息
     */
    fun speakHelp() {
        viewModelScope.launch {
            interactionManager.speakHelp()
        }
    }
    
    /**
     * 设置指令处理器
     */
    fun setCommandHandler(handler: (VoiceCommand) -> Boolean) {
        this.commandHandler = handler
        interactionManager.setCommandExecutor(this)
    }
    
    /**
     * 执行指令（实现 VoiceCommandExecutor 接口）
     */
    override suspend fun executeCommand(command: VoiceCommand): Boolean {
        return commandHandler?.invoke(command) ?: false
    }
    
    /**
     * 观察语音交互状态
     */
    private fun observeInteractionState() {
        viewModelScope.launch {
            interactionManager.interactionState.collect { state ->
                _uiState.update { 
                    it.copy(
                        isListening = state.isListening,
                        lastCommand = state.lastCommand,
                        error = state.lastError
                    )
                }
            }
        }
    }

    /**
     * 观察唤醒词检测状态（来自 WakeWordBridgeService → VoiceCommandRepository）
     *
     * 当百度唤醒引擎在独立进程检测到唤醒词后，
     * 通过广播 → WakeWordBridgeService → VoiceCommandRepository.triggerWakeWordDetected()
     * 写入 interactionState.isWakeWordDetected = true
     *
     * 此处消费该标志，触发 TTS 播报和语音识别
     */
    private fun observeWakeWordState() {
        viewModelScope.launch {
            voiceCommandRepository.interactionState.collect { state ->
                if (state.isWakeWordDetected) {
                    Timber.i("VoiceInteractionViewModel: 唤醒词检测到: ${state.wakeWord}")

                    // 1. 播报响应（TTS 线路，不阻塞后续流程）
                    launch {
                        interactionManager.speak("我在，请说指令", VoiceType.SYSTEM_STATUS)
                    }

                    // 2. 开始监听语音指令
                    // 短暂延迟让 TTS 播完"我在"后再开始识别
                    launch {
                        delay(300L)
                        interactionManager.startListening()
                    }

                    // 3. 消费唤醒词标志（VoiceCommandRepositoryImpl 内部自动重置）
                    // consumeWakeWordDetected() 确保不会重复处理
                    voiceCommandRepository.consumeLastCommand()
                }
            }
        }
    }
    
    override fun onCleared() {
        super.onCleared()
        interactionManager.release()
    }
}

/**
 * UI 状态
 */
data class VoiceInteractionUiState(
    val isInitialized: Boolean = false,
    val isInitializing: Boolean = false,
    val isListening: Boolean = false,
    val lastCommand: VoiceCommandResult? = null,
    val error: String? = null
)
