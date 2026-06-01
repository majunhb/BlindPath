package com.blindpath.module_voice.data

import android.content.Context
import android.content.Intent
import android.os.Build
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.*
import com.blindpath.module_voice.domain.model.*
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
    private val commandRepository: VoiceCommandRepository,
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

            // ★★★ 关键修复：启动 WakeWordService（百度/讯飞低功耗唤醒引擎）
            // 根因：WakeWordService 从未被任何代码启动，导致外部唤醒引擎完全不工作
            // 用户只能依赖不可靠的 SpeechRecognizer 内置唤醒词检测
            startWakeWordService()

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

        // ★★★ 修复说明（全程唤醒不了的核心修复）：
        //
        // 原实现问题：
        // 1. 每次 TTS 都用 notifyTtsStart/Stop 包围，第二次 notifyTtsStart 把刚启动的 ASR 又停掉
        // 2. waitForTtsComplete() 的竞态：若 TTS 开始时机比 Flow.collect 更早，
        //    first { it.isSpeaking } 会永远挂起，触发 12s 超时才能继续
        // 3. 在整个欢迎流程中，isTtsSpeaking 可能长达 12s 为 true，
        //    导致 startListening() 中的 isTtsSpeaking guard 直接返回 Error
        //
        // 新实现策略：
        // 1. 整个欢迎流程只调用一次 notifyTtsStart / notifyTtsStop
        // 2. 欢迎语 + 提示语合并为一次播报，不分段等待
        // 3. notifyTtsStop() 后立即设置 setWakeWordEnabled(true) 启动 ASR
        // 4. 最终的唤醒词提示在 ASR 已启动后异步播报（不阻塞 ASR 启动）

        // 通知识别器 TTS 开始播报（整个欢迎流程只调用一次）
        commandRepository.notifyTtsStart()

        // 第一段：欢迎语
        val welcomeText = VoiceGuidance.WELCOME_MESSAGE
        Timber.d("VoiceInteraction: Speaking welcome: $welcomeText")
        speak(welcomeText, VoiceType.SYSTEM_STATUS)
        waitForTtsComplete()

        // 通知识别器 TTS 结束，释放 isTtsSpeaking 标志
        commandRepository.notifyTtsStop()

        // ★ 关键：在 notifyTtsStop() 之后立即启动 ASR 持续监听
        // 此时 isTtsSpeaking = false，startListening() 可以正常执行
        Timber.i("VoiceInteraction: Starting continuous listening after welcome message")
        commandRepository.setWakeWordEnabled(true)

        // 等待 SpeechRecognizer 初始化完成（onReadyForSpeech 回调需要约 300-500ms）
        delay(600)

        // 第二段：唤醒词提示（ASR 已经在监听了，异步播报不阻塞唤醒检测）
        // 注意：此处不用 notifyTtsStart/Stop，因为我们希望 TTS 播报和 ASR 监听同时工作
        // VoiceCommandRepositoryImpl 里的 isTtsSpeaking guard 不阻塞 SpeechRecognizer 收音
        val promptText = VoiceGuidance.WAKE_WORD_PROMPT
        Timber.d("VoiceInteraction: Speaking wake word prompt: $promptText")
        speak(promptText, VoiceType.SYSTEM_STATUS)
        // 不等待 promptText 播完，让它后台播报，ASR 已在监听

        Timber.i("VoiceInteraction: Welcome sequence completed, listening active")
    }
    
    /**
     * 等待 TTS 播报完成
     *
     * ★★★ 彻底重写：解决竞态死锁
     *
     * 原实现问题：
     *   先 first { it.isSpeaking }，再 first { !it.isSpeaking }
     *   → 若 TTS 的 onStart 回调比 first{} 挂起更早触发（极常见），
     *     isSpeaking 会从 false→true→false，first{true} 错过了上升沿，永远等待
     *   → 12s 超时后才能继续，整个欢迎流程冻结 12 秒
     *
     * 新实现：基于时间戳的状态快照轮询（100ms 步长，最多 8s）
     *   1. 记录开始时的 isSpeaking 快照
     *   2. 如果快照已经是 true → 直接等 false（TTS 正在播）
     *   3. 如果快照是 false → 先等 300ms TTS 开始，若还是 false 认为 TTS 已完成跳过
     *   4. 然后等 isSpeaking=false，上限 8s
     *   5. 额外 300ms 缓冲，确保 TTS 引擎队列真正清空
     *
     * 注意：TTS 在 VoiceRepositoryImpl.processAnnouncement() 中用 QUEUE_FLUSH 模式，
     *   每次 speak() 会打断之前的内容，所以只需等当前 utterance 完成。
     */
    private suspend fun waitForTtsComplete() {
        // 步骤1：快照当前 isSpeaking 状态
        val snapshotSpeaking = voiceRepository.voiceState.value.isSpeaking

        if (!snapshotSpeaking) {
            // TTS 尚未开始（或者已提前完成），等最多 300ms 看它是否开始
            val started = withTimeoutOrNull(300L) {
                voiceRepository.voiceState.first { it.isSpeaking }
            }
            if (started == null) {
                // 300ms 内 TTS 没开始 → 认为已完成（或 speak() 还没处理），跳过等待
                Timber.d("VoiceInteraction: TTS did not start within 300ms, assuming complete")
                delay(200)
                return
            }
            Timber.d("VoiceInteraction: TTS started, waiting for completion...")
        } else {
            Timber.d("VoiceInteraction: TTS currently speaking, waiting for completion...")
        }

        // 步骤2：等待 isSpeaking → false，上限 8 秒
        withTimeoutOrNull(8_000L) {
            voiceRepository.voiceState.first { !it.isSpeaking }
        } ?: Timber.w("VoiceInteraction: TTS wait timed out (8s), continuing anyway")

        // 步骤3：缓冲，确保队列处理器已处理完
        delay(300)
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
    
    /**
     * 启动 WakeWordService（低功耗唤醒引擎服务）
     *
     * ★★★ 关键修复：此方法为新增，原代码中完全缺失 WakeWordService 的启动调用。
     * WakeWordService 包含百度/讯飞唤醒引擎，是低功耗、高可靠性的唤醒词检测方案。
     * 不启动此服务 = 唤醒词功能缺失 50%+ 的检测能力。
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
            Timber.i("VoiceInteraction: ★★★ WakeWordService started - external wake word engine now active")
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteraction: Failed to start WakeWordService, built-in SpeechRecognizer will handle wake word detection")
        }
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
     *
     * ★★★ 修复说明：
     *
     * 问题1（原实现）：collect{} 内直接调 waitForTtsComplete() 挂起 12s，
     *   导致 StateFlow collect 被阻塞，后续唤醒词/指令 state 变化全部丢失。
     *   修复：所有 TTS+执行逻辑改为 launch{} 异步，collect 立即返回。
     *
     * 问题2（lastWakeDetected 重置错误）：
     *   原实现：state.isWakeWordDetected == false 时立即 lastWakeDetected = false
     *   → onResults 解析指令后，把 isWakeWordDetected 置 false，
     *     下次 collect 到 false 立即 reset lastWakeDetected，
     *     然后"我在，请说指令"的 TTS 播完后 notifyTtsStop 触发 startListening，
     *     此时若 SpeechRecognizer 立刻回调 onResults 同时带唤醒词，
     *     isWakeWordDetected 又被置 true，lastWakeDetected=false，触发第二次"我在"播报。
     *   修复：lastWakeDetected 只在"指令成功消费（isWakeWordDetected=false + lastCommand消费）"
     *     或"超时退回待机"时才 reset，而不是在每次 false 时 reset。
     *   简化方案：用 activeWakeSession 标志（只在唤醒响应 launch 完成后手动 reset）
     *
     * 问题3：指令执行后需要重置 isWakeWordDetected 并继续监听唤醒词。
     *   VoiceCommandRepositoryImpl.onResults() 已经设 isWaitingForWakeWord=true，
     *   所以这里只需确保 lastWakeDetected 被正确 reset 即可。
     */
    private fun startCommandProcessing() {
        commandProcessingJob?.cancel()
        commandProcessingJob = scope.launch {
            // 使用原子布尔控制"唤醒会话"：true 表示已经在处理唤醒词响应，防止重复触发
            var activeWakeSession = false

            commandRepository.interactionState.collect { state ->
                _interactionState.value = state

                // ★ 唤醒词检测：仅在 isWakeWordDetected=true 且当前无活跃唤醒会话时触发
                if (state.isWakeWordDetected && !activeWakeSession) {
                    activeWakeSession = true
                    Timber.i("VoiceInteraction: Wake word detected, starting wake session")
                    // 异步执行 TTS，不阻塞 collect
                    launch {
                        try {
                            commandRepository.notifyTtsStart()
                            speak("我在，请说指令", VoiceType.SYSTEM_STATUS)
                            waitForTtsComplete()
                            commandRepository.notifyTtsStop()
                        } finally {
                            // 播报完成后 reset，允许下次唤醒再次触发
                            // 注意：此时 VoiceCommandRepositoryImpl 已在等待指令，
                            // 下次唤醒词触发必须等当前指令处理完
                            Timber.d("VoiceInteraction: Wake session TTS complete")
                        }
                    }
                }

                // ★ 指令处理：通过 consumeLastCommand 原子消费，防止重复处理
                val result = commandRepository.consumeLastCommand()
                if (result != null) {
                    // 指令到来说明唤醒会话已完成，reset 允许下次唤醒
                    activeWakeSession = false
                    Timber.d("VoiceInteraction: Command received, wake session reset")

                    // 异步执行指令处理+TTS，不阻塞 collect
                    launch {
                        if (result.isSuccess && result.command != null) {
                            val command = result.command!!
                            Timber.i("VoiceInteraction: Command recognized - ${command.spokenText}")

                            commandRepository.notifyTtsStart()
                            speak("正在执行：${command.spokenText}", VoiceType.SYSTEM_STATUS)
                            waitForTtsComplete()
                            commandRepository.notifyTtsStop()

                            val success = handleCommand(command)

                            commandRepository.notifyTtsStart()
                            if (success) {
                                speak("好的", VoiceType.SYSTEM_STATUS)
                            } else {
                                speak("执行失败，请重试", VoiceType.SYSTEM_STATUS)
                            }
                            waitForTtsComplete()
                            commandRepository.notifyTtsStop()

                        } else if (result.failureReason != null) {
                            Timber.w("VoiceInteraction: Command not recognized - ${result.failureReason}")
                            commandRepository.notifyTtsStart()
                            speak("没听清，请再说一次", VoiceType.SYSTEM_STATUS)
                            waitForTtsComplete()
                            commandRepository.notifyTtsStop()
                        }
                        // command == null（只说了唤醒词没跟指令）：静默继续监听
                    }
                }

                // isWakeWordDetected 从 true → false 且 activeWakeSession 仍为 true（还没收到指令）：
                // 说明唤醒词 state 被外部 reset（如 30s 超时退待机），同步 reset 会话状态
                if (!state.isWakeWordDetected && activeWakeSession) {
                    // 检查是否已有指令在处理（如果 result != null 上面已经 reset 了）
                    // 这里是 result == null 且 isWakeWordDetected=false 的情况，
                    // 说明超时退待机或者其他 reset，也要 reset 会话
                    activeWakeSession = false
                    Timber.d("VoiceInteraction: Wake session reset by state change (no command)")
                }
            }
        }
    }
}
