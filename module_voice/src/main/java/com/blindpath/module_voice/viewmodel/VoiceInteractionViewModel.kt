package com.blindpath.module_voice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceCommandExecutor
import com.blindpath.module_voice.domain.VoiceCommandRepository
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
 * 职责：
 * - 管理 UI 层的语音交互状态显示
 * - 提供指令执行回调
 * - 唤醒词→识别→执行的完整链路由 VoiceInteractionPipeline 处理
 * - 本 ViewModel 仅同步状态用于 UI 展示
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
        observeCommandRepositoryState()
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
                is Result.Loading -> {}
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
     * 观察 VoiceInteractionManager 的交互状态
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
     * 观察 VoiceCommandRepository 的唤醒词/识别状态
     *
     * 1. 同步 UI 展示字段（isWakeWordDetected、wakeWord）
     * 2. 当识别到指令时（lastCommand 非空），通过 commandHandler 执行
     */
    private fun observeCommandRepositoryState() {
        viewModelScope.launch {
            voiceCommandRepository.interactionState.collect { state ->
                _uiState.update {
                    it.copy(
                        isWakeWordDetected = state.isWakeWordDetected,
                        wakeWord = state.wakeWord
                    )
                }

                // 检测到新指令，执行并消费
                val cmd = state.lastCommand
                if (cmd != null && cmd.command != null) {
                    Timber.i("VoiceInteractionViewModel: 执行指令: ${cmd.command.name} (\"${cmd.rawText}\")")

                    // 消费指令（防止重复处理）
                    voiceCommandRepository.consumeLastCommand()

                    // 执行指令
                    val success = commandHandler?.invoke(cmd.command) ?: false
                    Timber.i("VoiceInteractionViewModel: 指令执行结果: $success")
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
    val isWakeWordDetected: Boolean = false,
    val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD,
    val lastCommand: VoiceCommandResult? = null,
    val error: String? = null
)