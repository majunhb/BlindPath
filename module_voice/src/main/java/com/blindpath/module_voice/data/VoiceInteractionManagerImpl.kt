package com.blindpath.module_voice.data

import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.*
import com.blindpath.module_voice.domain.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音交互管理器实现
 * 
 * 协调 TTS 播报和语音识别，提供完整的语音交互体验
 */
@Singleton
class VoiceInteractionManagerImpl @Inject constructor(
    private val voiceRepository: VoiceRepository,
    private val commandRepository: VoiceCommandRepository
) : VoiceInteractionManager {
    
    private val _interactionState = MutableStateFlow(VoiceInteractionState())
    override val interactionState: StateFlow<VoiceInteractionState> = _interactionState.asStateFlow()
    
    override val isInitialized: Boolean
        get() = _isInitialized
    
    @Volatile
    private var _isInitialized = false
    
    private var commandExecutor: VoiceCommandExecutor? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var commandProcessingJob: Job? = null
    
    override suspend fun initialize(): Result<Boolean> {
        if (_isInitialized) {
            return Result.Success(true)
        }
        
        return try {
            // 初始化 TTS
            val ttsResult = voiceRepository.initialize()
            if (ttsResult is Result.Error) {
                Timber.e("VoiceInteraction: TTS initialization failed")
                return Result.Error(message = "TTS 初始化失败：${ttsResult.message}")
            }
            
            // 初始化语音识别
            val commandResult = commandRepository.initialize()
            if (commandResult is Result.Error) {
                Timber.e("VoiceInteraction: Command recognition initialization failed")
                return Result.Error(message = "语音识别初始化失败：${commandResult.message}")
            }
            
            // 启用唤醒词检测（持续监听模式）
            commandRepository.setWakeWordEnabled(true)
            
            // 监听语音识别结果
            startCommandProcessing()
            
            _isInitialized = true
            Timber.i("VoiceInteraction: Initialized successfully with continuous listening enabled")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteraction: Initialization failed")
            Result.Error(message = "语音交互初始化失败：${e.message}")
        }
    }
    
    override suspend fun speakWelcome() {
        speak(VoiceGuidance.WELCOME_MESSAGE, VoiceType.SYSTEM_STATUS)
        delay(1000) // 等待欢迎消息播报完成
        speak(VoiceGuidance.WAKE_WORD_PROMPT, VoiceType.SYSTEM_STATUS)
    }
    
    override suspend fun speakHelp() {
        speak(VoiceGuidance.HELP_MESSAGE, VoiceType.SYSTEM_STATUS)
    }
    
    override suspend fun speak(text: String, type: VoiceType) {
        voiceRepository.announce(text, type)
    }
    
    override suspend fun startListening(): Result<Boolean> {
        return commandRepository.startListening()
    }
    
    override suspend fun stopListening(): Result<Boolean> {
        return commandRepository.stopListening()
    }
    
    override suspend fun handleCommand(command: VoiceCommand): Boolean {
        return try {
            val executor = commandExecutor ?: run {
                Timber.w("VoiceInteraction: No command executor set")
                return false
            }
            
            Timber.d("VoiceInteraction: Handling command - ${command.name}")
            val success = executor.executeCommand(command)
            
            if (success) {
                Timber.i("VoiceInteraction: Command executed successfully - ${command.name}")
            } else {
                Timber.w("VoiceInteraction: Command execution failed - ${command.name}")
            }
            
            success
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteraction: Command handling failed")
            false
        }
    }
    
    override fun setCommandExecutor(executor: VoiceCommandExecutor) {
        this.commandExecutor = executor
        Timber.d("VoiceInteraction: Command executor set")
    }
    
    override fun release() {
        commandProcessingJob?.cancel()
        voiceRepository.release()
        commandRepository.release()
        scope.cancel()
        _isInitialized = false
        Timber.d("VoiceInteraction: Released")
    }
    
    /**
     * 启动指令处理协程
     */
    private fun startCommandProcessing() {
        commandProcessingJob?.cancel()
        commandProcessingJob = scope.launch {
            commandRepository.interactionState.collect { state ->
                _interactionState.value = state
                
                // 检测到唤醒词时播放提示音
                if (state.isWakeWordDetected) {
                    Timber.i("VoiceInteraction: Wake word detected, ready for command")
                    speak("我在，请说指令", VoiceType.SYSTEM_STATUS)
                }
                
                // 处理识别到的指令
                state.lastCommand?.let { result ->
                    if (result.isSuccess) {
                        result.command?.let { command ->
                            handleCommand(command)
                        }
                    }
                }
            }
        }
    }
}
