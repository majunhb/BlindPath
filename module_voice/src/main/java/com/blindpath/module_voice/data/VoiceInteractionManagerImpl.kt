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
 * 
 * 修复记录：
 * - TTS 播报前通知识别器暂停，播报结束后通知恢复
 * - 避免音频焦点冲突导致语音唤醒失败
 * - 播报欢迎语后延迟启动持续监听
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
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var commandProcessingJob: Job? = null
    
    // ====== 新增：追踪唤醒词检测状态，防止重复处理 ======
    private var lastWakeWordDetectedTime = 0L
    private val wakeWordDebounceMs = 2000L  // 唤醒词去抖时间
    
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
            
            // 监听语音识别结果
            startCommandProcessing()
            
            _isInitialized = true
            Timber.i("VoiceInteraction: Initialized successfully")
            
            // ====== 修复：先播报欢迎语，播报完成后再启用持续监听 ======
            // 这样避免 TTS 和 SpeechRecognizer 同时抢占音频焦点
            speakWelcomeSafely()
            
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteraction: Initialization failed")
            Result.Error(message = "语音交互初始化失败：${e.message}")
        }
    }
    
    /**
     * 安全播报欢迎语
     * 欢迎语播报完成后再启动持续监听，避免音频焦点冲突
     */
    private suspend fun speakWelcomeSafely() {
        Timber.d("VoiceInteraction: Speaking welcome message")
        
        // 通知识别器 TTS 即将开始
        notifyTtsStart()
        
        // 播报欢迎消息
        speak(VoiceGuidance.WELCOME_MESSAGE, VoiceType.SYSTEM_STATUS)
        
        // 等待欢迎消息播报完成（TTS 是异步的，需要等待 isSpeaking 变为 false）
        var waitCount = 0
        while (voiceRepository.voiceState.first().isSpeaking && waitCount < 100) {
            delay(100)
            waitCount++
        }
        delay(500) // 额外等待确保播报完成
        
        // 播报唤醒词提示
        speak(VoiceGuidance.WAKE_WORD_PROMPT, VoiceType.SYSTEM_STATUS)
        
        // 等待唤醒词提示播报完成
        waitCount = 0
        while (voiceRepository.voiceState.first().isSpeaking && waitCount < 100) {
            delay(100)
            waitCount++
        }
        delay(500) // 额外等待确保播报完成
        
        // ====== 修复：欢迎语播报完成后才启用持续监听 ======
        notifyTtsStop()
        
        // 额外等待音频焦点释放
        delay(500)
        
        // 现在安全地启用唤醒词检测（持续监听模式）
        commandRepository.setWakeWordEnabled(true)
        Timber.i("VoiceInteraction: Welcome speech done, continuous listening enabled")
    }
    
    override suspend fun speakWelcome() {
        speakWelcomeSafely()
    }
    
    override suspend fun speakHelp() {
        notifyTtsStart()
        speak(VoiceGuidance.HELP_MESSAGE, VoiceType.SYSTEM_STATUS)
        // 帮助消息较长，给更多等待时间
        scope.launch {
            delay(5000)  // 等待帮助消息播完
            notifyTtsStop()
        }
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
                
                // ====== 修复：唤醒词检测带去抖 ======
                if (state.isWakeWordDetected) {
                    val now = System.currentTimeMillis()
                    if (now - lastWakeWordDetectedTime > wakeWordDebounceMs) {
                        lastWakeWordDetectedTime = now
                        Timber.i("VoiceInteraction: Wake word detected, ready for command")
                        
                        // 通知识别器 TTS 即将开始
                        notifyTtsStart()
                        speak("我在，请说指令", VoiceType.SYSTEM_STATUS)
                        
                        // TTS 播报完成后通知识别器恢复
                        scope.launch {
                            delay(1000)  // 等待短播报完成
                            notifyTtsStop()
                        }
                    } else {
                        Timber.d("VoiceInteraction: Wake word debounced")
                    }
                }
                
                // 处理识别到的指令
                state.lastCommand?.let { result ->
                    if (result.isSuccess && result.command != null) {
                        val command = result.command!!
                        Timber.i("VoiceInteraction: Command recognized - ${command.spokenText}")
                        
                        // 清除 lastCommand 防止重复处理
                        _interactionState.update { it.copy(lastCommand = null) }
                        
                        // 通知识别器 TTS 即将开始
                        notifyTtsStart()
                        speak("正在执行：${command.spokenText}", VoiceType.SYSTEM_STATUS)
                        
                        // 执行指令
                        val success = handleCommand(command)
                        
                        // 播报执行结果
                        if (success) {
                            speak("指令执行成功", VoiceType.SYSTEM_STATUS)
                        } else {
                            speak("指令执行失败", VoiceType.SYSTEM_STATUS)
                        }
                        
                        // TTS 播报完成后通知识别器恢复
                        scope.launch {
                            delay(1500)  // 等待播报完成
                            notifyTtsStop()
                        }
                    } else if (!result.isSuccess) {
                        // 指令识别失败
                        Timber.w("VoiceInteraction: Command not recognized - ${result.failureReason}")
                        
                        // 清除 lastCommand 防止重复处理
                        _interactionState.update { it.copy(lastCommand = null) }
                        
                        notifyTtsStart()
                        speak(VoiceGuidance.COMMAND_NOT_RECOGNIZED, VoiceType.SYSTEM_STATUS)
                        
                        scope.launch {
                            delay(1500)
                            notifyTtsStop()
                        }
                    }
                }
            }
        }
    }
    
    // ====== 新增：TTS 与识别器协调 ======
    
    /**
     * 通知识别器 TTS 即将开始播报
     * 识别器应暂停监听，避免音频焦点冲突
     */
    private fun notifyTtsStart() {
        if (commandRepository is VoiceCommandRepositoryImpl) {
            (commandRepository as VoiceCommandRepositoryImpl).notifyTtsStart()
        }
    }
    
    /**
     * 通知识别器 TTS 播报已结束
     * 识别器可以恢复监听
     */
    private fun notifyTtsStop() {
        if (commandRepository is VoiceCommandRepositoryImpl) {
            (commandRepository as VoiceCommandRepositoryImpl).notifyTtsStop()
        }
    }
}
