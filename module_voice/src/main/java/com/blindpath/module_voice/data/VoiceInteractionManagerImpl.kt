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
 * 修复说明（全程修复语音交互）：
 * 1. 修复 waitForTtsComplete() 竞态条件：
 *    - 原实现先等 start 再等 stop，但 TTS 可能在 await start 之前就已完成
 *    - 修复：合并为单次等待，使用 currentCoroutineContext 检查超时
 * 2. 修复 speakWelcome() 对 TTS 失败的脆弱性：
 *    - 原实现依赖 TTS 回调来确定播报完成，若 TTS 未初始化会卡住
 *    - 修复：添加超时保护，TTS 不可用时候直接启动监听
 * 3. 修复 initialize() 的错误处理：
 *    - 原实现在 TTS 或 ASR 初始化失败时仍继续执行
 *    - 修复：任一初始化失败立即返回 Error
 * 4. 全程语音交互：无论唤醒引擎是否可用，都启动 ASR 持续监听
 *    - 内置唤醒词检测（VoiceCommandRepositoryImpl.onResults() 中已实现）
 *    - 不依赖第三方唤醒 SDK
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
            Timber.i("VoiceInteraction: Initializing TTS...")
            val ttsResult = voiceRepository.initialize()
            if (ttsResult is Result.Error) {
                Timber.e("VoiceInteraction: TTS initialization failed: ${ttsResult.message}")
                return Result.Error(message = "TTS 初始化失败：${ttsResult.message}")
            }
            // 修复：检查 TTS 是否真正初始化成功（Result.Success(false) 也表示失败）
            if (ttsResult is Result.Success && ttsResult.data == false) {
                Timber.e("VoiceInteraction: TTS initialization returned false")
                return Result.Error(message = "TTS 初始化返回失败")
            }
            Timber.i("VoiceInteraction: TTS initialized successfully")
            
            // 初始化语音识别
            Timber.i("VoiceInteraction: Initializing command recognition...")
            val commandResult = commandRepository.initialize()
            if (commandResult is Result.Error) {
                Timber.e("VoiceInteraction: Command recognition initialization failed: ${commandResult.message}")
                return Result.Error(message = "语音识别初始化失败：${commandResult.message}")
            }
            Timber.i("VoiceInteraction: Command recognition initialized successfully")
            
            // 监听语音识别结果
            startCommandProcessing()
            
            // 注意：不再在此处启动监听，改到 speakWelcome() 中 TTS 播报完成后启动
            Timber.i("VoiceInteraction: Initialized successfully, listening will start after welcome")
            
            _isInitialized = true
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteraction: Initialization failed")
            Result.Error(message = "语音交互初始化失败：${e.message}")
        }
    }
    
    override suspend fun speakWelcome() {
        Timber.i("VoiceInteraction: Speaking welcome message")
        
        // 通知识别器 TTS 开始播报
        commandRepository.notifyTtsStart()
        
        // 播报欢迎消息（带超时保护，防止 TTS 失败时卡住）
        val welcomeText = VoiceGuidance.WELCOME_MESSAGE
        Timber.d("VoiceInteraction: Speaking welcome: $welcomeText")
        speak(welcomeText, VoiceType.SYSTEM_STATUS)
        
        // 等待队列处理完成（关键修复：使用合并后的 waitForTtsComplete）
        waitForTtsComplete()
        
        // 通知识别器 TTS 停止播报
        commandRepository.notifyTtsStop()
        
        // 额外等待确保音频焦点释放
        delay(800)
        
        // 现在启动持续监听（TTS 播报完成后）
        // 修复：无论唤醒引擎是否可用，都启动 ASR 持续监听
        // 内置唤醒词检测在 VoiceCommandRepositoryImpl.onResults() 中实现
        Timber.i("VoiceInteraction: Starting continuous listening after welcome message")
        commandRepository.setWakeWordEnabled(true)
        
        // 等待监听启动
        delay(500)
        
        // 播报唤醒词提示（此时监听已启动）
        commandRepository.notifyTtsStart()
        val promptText = VoiceGuidance.WAKE_WORD_PROMPT
        Timber.d("VoiceInteraction: Speaking wake word prompt: $promptText")
        speak(promptText, VoiceType.SYSTEM_STATUS)
        
        // 等待播报完成
        waitForTtsComplete()
        
        commandRepository.notifyTtsStop()
        
        Timber.i("VoiceInteraction: Welcome sequence completed, listening active")
    }
    
    /**
     * 等待 TTS 播报完成
     * 
     * 修复说明：
     * 原实现分两步：
     *   1. 等 TTS 开始（first { it.isSpeaking }）
     *   2. 等 TTS 结束（first { !it.isSpeaking }）
     * 问题：TTS 可能在第 1 步等待之前就已完成，导致第 1 步永远挂起
     * 
     * 新实现：
     *   1. 先检查当前状态（如果已在播报中，直接等结束）
     *   2. 如果未在播报，等开始后再等结束
     *   3. 添加整体超时（12秒），防止永远挂起
     */
    private suspend fun waitForTtsComplete() {
        withTimeoutOrNull(12_000L) {
            val currentState = voiceRepository.voiceState.value
            if (currentState.isSpeaking) {
                // TTS 正在播报，直接等待结束
                Timber.d("VoiceInteraction: TTS currently speaking, waiting for completion")
                voiceRepository.voiceState.first { !it.isSpeaking }
            } else {
                // TTS 可能还没开始，等待开始然后等待结束
                Timber.d("VoiceInteraction: Waiting for TTS to start...")
                voiceRepository.voiceState.first { it.isSpeaking }
                Timber.d("VoiceInteraction: TTS started, waiting for completion...")
                voiceRepository.voiceState.first { !it.isSpeaking }
            }
        } ?: run {
            Timber.w("VoiceInteraction: TTS wait timed out (12s), continuing anyway")
        }
        
        // 额外等待确保队列处理完成
        delay(500)
    }
    
    override suspend fun speakHelp() {
        commandRepository.notifyTtsStart()
        speak(VoiceGuidance.HELP_MESSAGE, VoiceType.SYSTEM_STATUS)
        
        // 等待播报完成
        waitForTtsComplete()
        
        commandRepository.notifyTtsStop()
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
                    
                    commandRepository.notifyTtsStart()
                    speak("我在，请说指令", VoiceType.SYSTEM_STATUS)
                    
                    // 等待播报完成
                    waitForTtsComplete()
                    
                    commandRepository.notifyTtsStop()
                }
                
                // 处理识别到的指令（通过 consumeLastCommand 原子消费，防止重复处理）
                val result = commandRepository.consumeLastCommand()
                if (result != null) {
                    if (result.isSuccess && result.command != null) {
                        val command = result.command!!
                        Timber.i("VoiceInteraction: Command recognized - ${command.spokenText}")
                        
                        // 播报指令识别结果
                        commandRepository.notifyTtsStart()
                        speak("正在执行：${command.spokenText}", VoiceType.SYSTEM_STATUS)
                        
                        // 等待播报完成
                        waitForTtsComplete()
                        commandRepository.notifyTtsStop()
                        
                        // 执行指令
                        val success = handleCommand(command)
                        
                        // 播报执行结果
                        commandRepository.notifyTtsStart()
                        if (success) {
                            speak("指令执行成功", VoiceType.SYSTEM_STATUS)
                        } else {
                            speak("指令执行失败", VoiceType.SYSTEM_STATUS)
                        }
                        
                        // 等待播报完成
                        waitForTtsComplete()
                        commandRepository.notifyTtsStop()
                        
                    } else if (!result.isSuccess) {
                        // 指令识别失败
                        Timber.w("VoiceInteraction: Command not recognized - ${result.failureReason}")
                        
                        commandRepository.notifyTtsStart()
                        speak("未识别的指令，请重新说", VoiceType.SYSTEM_STATUS)
                        
                        // 等待播报完成
                        waitForTtsComplete()
                        commandRepository.notifyTtsStop()
                    }
                }
            }
        }
    }
}
