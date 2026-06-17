package com.blindpath.module_voice.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.IntentRouter
import com.blindpath.module_voice.domain.NavigationExecutor
import com.blindpath.module_voice.domain.SceneExecutor
import com.blindpath.module_voice.domain.SosExecutor
import com.blindpath.module_voice.domain.VoiceCommandExecutor
import com.blindpath.module_voice.domain.VoiceCommandRepository
import com.blindpath.module_voice.domain.VoiceControlExecutor
import com.blindpath.module_voice.domain.VoiceInteractionManager
import com.blindpath.module_voice.domain.model.*
import com.blindpath.module_voice.service.VoiceInteractionPipeline
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 语音交互 ViewModel v2.0 — NLU+IntentRouter集成版
 *
 * 职责：
 * - 管理 UI 层的语音交互状态显示
 * - 双路径指令执行：
 *   路径A（新）：Pipeline v2.0 → NLU → IntentRouter → Executor（主路径）
 *   路径B（旧）：VoiceCommandRepository → VoiceCommand → commandHandler（兼容路径）
 * - 初始化时将 Executor 注入到 IntentRouter
 */
@HiltViewModel
class VoiceInteractionViewModel @Inject constructor(
    private val interactionManager: VoiceInteractionManager,
    private val voiceCommandRepository: VoiceCommandRepository,
    private val pipeline: VoiceInteractionPipeline,
    private val intentRouter: IntentRouter
) : ViewModel(), VoiceCommandExecutor {

    private val _uiState = MutableStateFlow(VoiceInteractionUiState())
    val uiState: StateFlow<VoiceInteractionUiState> = _uiState.asStateFlow()

    // 旧路径：指令执行回调（由外部设置，用于旧 VoiceCommand 兼容）
    private var commandHandler: ((VoiceCommand) -> Boolean)? = null

    // 新路径：NLU意图执行回调（由外部设置，用于 VoiceIntent 路由）
    private var intentActionHandler: ((VoiceIntent, NluResult) -> Boolean)? = null

    init {
        observeInteractionState()
        observeCommandRepositoryState()
        observePipelineState()
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
                is Result.Loading -> {}
            }

            // 初始化 Pipeline v2.0
            pipeline.initialize()
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
     * 设置旧路径指令处理器（VoiceCommand 回调）
     */
    fun setCommandHandler(handler: (VoiceCommand) -> Boolean) {
        this.commandHandler = handler
        interactionManager.setCommandExecutor(this)
    }

    /**
     * ★ 设置NLU意图执行器 — 四大模块执行器
     *
     * 由 MainScreen 在初始化时调用，将UI层操作封装为4个Executor接口实现
     * 注入到 IntentRouter，Pipeline v2.0 的意图路由使用
     */
    fun setExecutors(
        navigationExecutor: NavigationExecutor,
        sceneExecutor: SceneExecutor,
        voiceControlExecutor: VoiceControlExecutor,
        sosExecutor: SosExecutor
    ) {
        intentRouter.setNavigationExecutor(navigationExecutor)
        intentRouter.setSceneExecutor(sceneExecutor)
        intentRouter.setVoiceControlExecutor(voiceControlExecutor)
        intentRouter.setSosExecutor(sosExecutor)
        Timber.i("VoiceInteractionViewModel: 四大Executor已注入IntentRouter")
    }

    /**
     * 设置意图动作回调（用于UI导航等需要Composable上下文的操作）
     */
    fun setIntentActionHandler(handler: (VoiceIntent, NluResult) -> Boolean) {
        this.intentActionHandler = handler
    }

    /**
     * 执行旧路径指令（实现 VoiceCommandExecutor 接口）
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
     * 观察 VoiceCommandRepository 的唤醒词/识别状态（旧路径兼容）
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

                // 旧路径：检测到 VoiceCommand，优先走桥接到新 IntentRouter
                val cmd = state.lastCommand
                if (cmd != null && cmd.command != null) {
                    Timber.i("VoiceInteractionViewModel: 旧路径指令 → ${cmd.command.name} (\"${cmd.rawText}\")")
                    voiceCommandRepository.consumeLastCommand()

                    // ★ 尝试走新 NLU 路径
                    val intent = VoiceCommandIntentBridge.toIntent(cmd.command)
                    val nluResult = NluResult(
                        intent = intent,
                        rawText = cmd.rawText,
                        confidence = cmd.confidence
                    )

                    val handled = intentActionHandler?.invoke(intent, nluResult) ?: false
                    if (!handled) {
                        // 降级到旧 commandHandler
                        val success = commandHandler?.invoke(cmd.command) ?: false
                        Timber.i("VoiceInteractionViewModel: 旧路径降级执行 → $success")
                    }
                }
            }
        }
    }

    /**
     * ★ 观察 Pipeline v2.0 的会话状态
     */
    private fun observePipelineState() {
        viewModelScope.launch {
            pipeline.sessionState.collect { state ->
                val pipelineStateStr = when (state) {
                    is VoiceInteractionPipeline.SessionState.Idle -> "idle"
                    is VoiceInteractionPipeline.SessionState.WakeWordDetected -> "wake_word"
                    is VoiceInteractionPipeline.SessionState.Listening -> "listening"
                    is VoiceInteractionPipeline.SessionState.Understanding -> "understanding"
                    is VoiceInteractionPipeline.SessionState.Executing -> "executing"
                    is VoiceInteractionPipeline.SessionState.Speaking -> "speaking"
                    is VoiceInteractionPipeline.SessionState.WaitingFollowUp -> "waiting_follow_up"
                    is VoiceInteractionPipeline.SessionState.Error -> "error: ${state.message}"
                }

                _uiState.update { it.copy(pipelineState = pipelineStateStr) }

                // 同步监听状态
                when (state) {
                    is VoiceInteractionPipeline.SessionState.Listening,
                    is VoiceInteractionPipeline.SessionState.WaitingFollowUp -> {
                        _uiState.update { it.copy(isListening = true) }
                    }
                    is VoiceInteractionPipeline.SessionState.Idle -> {
                        _uiState.update { it.copy(isListening = false) }
                    }
                    else -> {}
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
 * UI 状态 v2.0 — 增加 pipelineState 字段
 */
data class VoiceInteractionUiState(
    val isInitialized: Boolean = false,
    val isInitializing: Boolean = false,
    val isListening: Boolean = false,
    val isWakeWordDetected: Boolean = false,
    val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD,
    val lastCommand: VoiceCommandResult? = null,
    val error: String? = null,
    /** Pipeline v2.0 会话状态 */
    val pipelineState: String = "idle"
)
