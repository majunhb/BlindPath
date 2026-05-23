package com.blindpath.module_voice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceCommandExecutor
import com.blindpath.module_voice.domain.VoiceInteractionManager
import com.blindpath.module_voice.domain.model.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 语音交互 ViewModel
 * 
 * 管理 UI 层的语音交互状态
 */
@HiltViewModel
class VoiceInteractionViewModel @Inject constructor(
    private val interactionManager: VoiceInteractionManager
) : ViewModel(), VoiceCommandExecutor {
    
    private val _uiState = MutableStateFlow(VoiceInteractionUiState())
    val uiState: StateFlow<VoiceInteractionUiState> = _uiState.asStateFlow()
    
    // 指令执行回调（由外部设置）
    private var commandHandler: ((VoiceCommand) -> Boolean)? = null
    
    init {
        observeInteractionState()
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
