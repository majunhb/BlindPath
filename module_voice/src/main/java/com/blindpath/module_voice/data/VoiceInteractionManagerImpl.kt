package com.blindpath.module_voice.data

import android.content.Context
import android.content.Intent
import android.os.Build
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.*
import com.blindpath.module_voice.domain.model.*
import com.blindpath.module_voice.service.UnifiedAudioScheduler
import com.blindpath.module_voice.service.VoiceStateMachine
import com.blindpath.module_voice.service.WakeWordService
import dagger.hilt.android.qualifiers.ApplicationContext
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
 * 架构说明：
 * - 使用 VoiceStateMachine 统一管理所有状态转换
 * - 使用 UnifiedAudioScheduler 统一管理音频焦点
 * - 删除所有散落的 isTtsSpeaking/activeWakeSession 标志
 * 
 * 修复说明（全程修复语音交互）：
 * 1. 修复 waitForTtsComplete() 竞态条件
 * 2. 修复 speakWelcome() 对 TTS 失败的脆弱性
 * 3. 使用 VoiceStateMachine 替代所有手动状态管理
 * 4. 全程语音交互：无论唤醒引擎是否可用，都启动 ASR 持续监听
 */
@Singleton
class VoiceInteractionManagerImpl @Inject constructor(
    private val voiceRepository: VoiceRepository,
    private val commandRepository: VoiceCommandRepository,
    private val stateMachine: VoiceStateMachine,
    private val unifiedAudioScheduler: UnifiedAudioScheduler,
    @ApplicationContext private val context: Context
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
            // 检查 TTS 是否真正初始化成功
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

            // 启动 WakeWordService（百度/讯飞低功耗唤醒引擎）
            startWakeWordService()
            
            // 初始化状态机到 LISTENING_WAKE 状态
            stateMachine.transition(VoiceStateMachine.VoiceState.LISTENING_WAKE)

            Timber.i("VoiceInteraction: Initialized successfully, listening will start after welcome")
            
            _isInitialized = true
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteraction: Initialization failed")
            stateMachine.transition(VoiceStateMachine.VoiceState.ERROR, mapOf("error" to (e.message ?: "Unknown error")))
            Result.Error(message = "语音交互初始化失败：${e.message}")
        }
    }
    
    override suspend fun speakWelcome() {
        Timber.i("VoiceInteraction: Speaking welcome message")

        // 状态转换：开始欢迎流程
        stateMachine.transition(VoiceStateMachine.VoiceState.WAKE_DETECTED)
        
        // 使用 try-finally 确保 TTS 状态正确
        stateMachine.ttsStarted()
        try {
            // 启用 TTS DUCK 模式
            unifiedAudioScheduler.enableTtsDucking()
            
            // 第一段：欢迎语
            val welcomeText = VoiceGuidance.WELCOME_MESSAGE
            Timber.d("VoiceInteraction: Speaking welcome: $welcomeText")
            speak(welcomeText, VoiceType.SYSTEM_STATUS)
            waitForTtsComplete()
            
            // 状态转换：欢迎语播报完成，切换到指令监听状态
            stateMachine.transition(VoiceStateMachine.VoiceState.LISTENING_WAKE)
            
            // 在 notifyTtsStop() 之后立即启动 ASR 持续监听
            Timber.i("VoiceInteraction: Starting continuous listening after welcome message")
            commandRepository.setWakeWordEnabled(true)
            
            // 等待 SpeechRecognizer 初始化完成
            delay(600)
            
            // 第二段：唤醒词提示（ASR 已经在监听了，异步播报不阻塞唤醒检测）
            val promptText = VoiceGuidance.WAKE_WORD_PROMPT
            Timber.d("VoiceInteraction: Speaking wake word prompt: $promptText")
            speak(promptText, VoiceType.SYSTEM_STATUS)
            
        } finally {
            // TTS 播报计数递减
            stateMachine.ttsFinished()
            unifiedAudioScheduler.disableTtsDucking()
        }

        Timber.i("VoiceInteraction: Welcome sequence completed, listening active")
    }
    
    /**
     * 等待 TTS 播报完成
     * 
     * 使用状态快照轮询解决竞态问题
     */
    private suspend fun waitForTtsComplete() {
        // 快照当前 isSpeaking 状态
        val snapshotSpeaking = voiceRepository.voiceState.value.isSpeaking

        if (!snapshotSpeaking) {
            // TTS 尚未开始，等最多 300ms 看它是否开始
            val started = withTimeoutOrNull(300L) {
                voiceRepository.voiceState.first { it.isSpeaking }
            }
            if (started == null) {
                Timber.d("VoiceInteraction: TTS did not start within 300ms, assuming complete")
                delay(200)
                return
            }
            Timber.d("VoiceInteraction: TTS started, waiting for completion...")
        } else {
            Timber.d("VoiceInteraction: TTS currently speaking, waiting for completion...")
        }

        // 等待 isSpeaking → false，上限 8 秒
        withTimeoutOrNull(8_000L) {
            voiceRepository.voiceState.first { !it.isSpeaking }
        } ?: Timber.w("VoiceInteraction: TTS wait timed out (8s), continuing anyway")

        // 缓冲，确保队列处理器已处理完
        delay(300)
    }
    
    override suspend fun speakHelp() {
        stateMachine.ttsStarted()
        try {
            unifiedAudioScheduler.enableTtsDucking()
            speak(VoiceGuidance.HELP_MESSAGE, VoiceType.SYSTEM_STATUS)
            waitForTtsComplete()
        } finally {
            stateMachine.ttsFinished()
            unifiedAudioScheduler.disableTtsDucking()
        }
    }
    
    override suspend fun speak(text: String, type: VoiceType) {
        voiceRepository.announce(text, type)
    }
    
    override suspend fun startListening(): Result<Boolean> {
        // 检查是否允许启动监听
        if (!stateMachine.canStartListening()) {
            Timber.w("VoiceInteraction: Cannot start listening in state ${stateMachine.currentState}")
            // 允许在 SPEAKING 状态使用 DUCK 模式启动
            if (stateMachine.currentState == VoiceStateMachine.VoiceState.SPEAKING) {
                Timber.i("VoiceInteraction: Starting in DUCK mode (TTS speaking)")
            } else {
                return Result.Error(message = "当前状态不允许启动监听")
            }
        }
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
    
    /**
     * 启动 WakeWordService（低功耗唤醒引擎服务）
     */
    private fun startWakeWordService() {
        try {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_START
                setPackage(context.packageName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            Timber.i("VoiceInteraction: WakeWordService started - external wake word engine now active")
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteraction: Failed to start WakeWordService")
        }
    }

    override fun release() {
        commandProcessingJob?.cancel()
        voiceRepository.release()
        commandRepository.release()
        scope.cancel()
        _isInitialized = false
        stateMachine.forceReset(VoiceStateMachine.VoiceState.IDLE)
        Timber.d("VoiceInteraction: Released")
    }
    
    /**
     * 启动指令处理协程
     * 
     * 使用 VoiceStateMachine 统一管理状态，替代手动维护的 activeWakeSession 标志
     */
    private fun startCommandProcessing() {
        commandProcessingJob?.cancel()
        commandProcessingJob = scope.launch {
            // 收集 stateMachine 的状态变化
            launch {
                stateMachine.context.collect { ctx ->
                    when (ctx.state) {
                        VoiceStateMachine.VoiceState.ERROR -> {
                            Timber.e("VoiceStateMachine: Error state - ${ctx.lastError}")
                            // 自动恢复
                            delay(1000)
                            stateMachine.forceReset(VoiceStateMachine.VoiceState.LISTENING_WAKE)
                        }
                        else -> { /* no-op */ }
                    }
                }
            }
            
            // 收集命令处理
            commandRepository.interactionState.collect { state ->
                _interactionState.value = state

                // ★ 唤醒词检测：检查状态机是否在 LISTENING_WAKE 状态
                if (state.isWakeWordDetected && stateMachine.currentState == VoiceStateMachine.VoiceState.LISTENING_WAKE) {
                    Timber.i("VoiceInteraction: Wake word detected, transitioning to WAKE_DETECTED")
                    
                    // 状态转换到 WAKE_DETECTED
                    stateMachine.transition(VoiceStateMachine.VoiceState.WAKE_DETECTED)
                    
                    // 异步执行 TTS，不阻塞 collect
                    launch {
                        try {
                            stateMachine.ttsStarted()
                            unifiedAudioScheduler.enableTtsDucking()
                            
                            speak("我在，请说指令", VoiceType.SYSTEM_STATUS)
                            waitForTtsComplete()
                            
                            stateMachine.ttsFinished()
                            unifiedAudioScheduler.disableTtsDucking()
                            
                            // 播报完成后切换到 LISTENING_CMD
                            stateMachine.transition(VoiceStateMachine.VoiceState.LISTENING_CMD)
                            
                        } catch (e: Exception) {
                            Timber.e(e, "VoiceInteraction: Wake session TTS error")
                            stateMachine.ttsFinished()
                            stateMachine.transition(VoiceStateMachine.VoiceState.ERROR, mapOf("error" to (e.message ?: "Unknown error")))
                        }
                    }
                }

                // ★ 指令处理：使用 consumeLastCommand 原子消费
                val result = commandRepository.consumeLastCommand()
                if (result != null) {
                    Timber.d("VoiceInteraction: Command received, transitioning to PROCESSING")
                    
                    // 状态转换到 PROCESSING
                    stateMachine.transition(VoiceStateMachine.VoiceState.PROCESSING)

                    // 异步执行指令处理+TTS，不阻塞 collect
                    launch {
                        try {
                            stateMachine.ttsStarted()
                            unifiedAudioScheduler.enableTtsDucking()
                            
                            if (result.isSuccess && result.command != null) {
                                val command = result.command!!
                                Timber.i("VoiceInteraction: Command recognized - ${command.spokenText}")

                                speak("正在执行：${command.spokenText}", VoiceType.SYSTEM_STATUS)
                                waitForTtsComplete()

                                val success = handleCommand(command)

                                if (success) {
                                    speak("好的", VoiceType.SYSTEM_STATUS)
                                } else {
                                    speak("执行失败，请重试", VoiceType.SYSTEM_STATUS)
                                }
                                waitForTtsComplete()

                            } else if (result.failureReason != null) {
                                Timber.w("VoiceInteraction: Command not recognized - ${result.failureReason}")
                                speak("没听清，请再说一次", VoiceType.SYSTEM_STATUS)
                                waitForTtsComplete()
                            }
                            // command == null（只说了唤醒词没跟指令）：静默继续监听
                            
                            stateMachine.ttsFinished()
                            unifiedAudioScheduler.disableTtsDucking()
                            
                            // 处理完成后回到 LISTENING_WAKE
                            stateMachine.transition(VoiceStateMachine.VoiceState.LISTENING_WAKE)
                            
                        } catch (e: Exception) {
                            Timber.e(e, "VoiceInteraction: Command processing error")
                            stateMachine.ttsFinished()
                            stateMachine.transition(VoiceStateMachine.VoiceState.ERROR, mapOf("error" to (e.message ?: "Unknown error")))
                        }
                    }
                }
            }
        }
    }
}
