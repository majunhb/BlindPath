package com.blindpath.module_voice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.blindpath.module_voice.domain.IntentRouter
import com.blindpath.module_voice.domain.NluEngine
import com.blindpath.module_voice.domain.VoiceCommandRepository
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.NluResult
import com.blindpath.module_voice.domain.model.VoiceIntent
import com.blindpath.module_voice.domain.model.VoiceCommand
import com.blindpath.module_voice.domain.model.VoiceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音交互全链路管理器 v2.0 — PRD四层架构对齐版
 *
 * 完整链路：唤醒 → ASR识别 → NLU语义理解 → 意图路由 → TTS播报反馈
 *
 * 四层架构映射：
 * 1. 唤醒层：WakeWordServiceEnhanced（始终在线后台监听）
 * 2. ASR识别层：VoiceCommandRepository（SpeechRecognizer/讯飞OneShot）
 * 3. NLU语义层：NluEngine（意图分类+槽位提取+追问机制）
 * 4. 执行反馈层：IntentRouter（意图路由→模块调用→TTS播报+震动）
 *
 * PRD交互规范：
 * - 唤醒成功：立即"滴"声提示音 + 手机短震50ms
 * - 打断机制：用户说出唤醒词立即打断当前TTS
 * - 连续对话：指令执行完毕保持3秒聆听窗口
 */
@Singleton
class VoiceInteractionPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceRepository: VoiceRepository,
    private val commandRepository: VoiceCommandRepository,
    private val unifiedAudioScheduler: UnifiedAudioScheduler,
    private val nluEngine: NluEngine,
    private val intentRouter: IntentRouter
) {
    // 会话状态
    sealed class SessionState {
        object Idle : SessionState()
        object WakeWordDetected : SessionState()
        object Listening : SessionState()
        object Understanding : SessionState()   // ★ 新增：NLU理解中
        object Executing : SessionState()       // ★ 新增：意图路由执行中
        object Speaking : SessionState()
        object WaitingFollowUp : SessionState() // ★ 新增：等待追问回答
        data class Error(val message: String) : SessionState()
    }

    // 会话配置
    data class SessionConfig(
        val maxListeningDuration: Long = 10000,    // 最大监听时长 10秒
        val sessionTimeout: Long = 15000,           // 会话超时 15秒
        val retryCount: Int = 2,                     // 重试次数
        val followUpWindowMs: Long = 3000            // ★ 连续对话聆听窗口 3秒
    )

    private val config = SessionConfig()

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var currentSession: Job? = null
    private var wakeWordReceiver: BroadcastReceiver? = null

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var isInitialized = false

    // ★ 连续对话状态
    @Volatile
    private var isInFollowUpWindow = false

    /**
     * 初始化全链路管理器
     */
    fun initialize() {
        if (isInitialized) return

        Timber.i("VoiceInteractionPipeline v2.0: Initializing (NLU+IntentRouter)")

        // 初始化语音识别器
        scope.launch {
            val result = commandRepository.initialize()
            if (result is com.blindpath.base.common.Result.Success) {
                Timber.i("VoiceInteractionPipeline: CommandRepository initialized")
            } else {
                Timber.w("VoiceInteractionPipeline: CommandRepository init failed")
            }
        }

        // 注册唤醒词广播接收器
        registerWakeWordReceiver()

        // 监听 TTS 状态
        observeTtsState()

        isInitialized = true
        Timber.i("VoiceInteractionPipeline v2.0: Initialized")
    }

    /**
     * 注册唤醒词广播接收器
     */
    private fun registerWakeWordReceiver() {
        wakeWordReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WakeWordServiceEnhanced.ACTION_WAKE_WORD_DETECTED -> {
                        val wakeWord = intent.getStringExtra(WakeWordServiceEnhanced.EXTRA_WAKE_WORD)
                        Timber.i("VoiceInteractionPipeline: ★ 唤醒词检测到: $wakeWord")

                        // ★ PRD: 唤醒成功立即打断当前TTS播报
                        scope.launch {
                            voiceRepository.stop()
                        }

                        // 启动语音交互会话
                        startVoiceSession(wakeWord ?: "小智助手")
                    }
                }
            }
        }

        val filter = IntentFilter().apply {
            addAction(WakeWordServiceEnhanced.ACTION_WAKE_WORD_DETECTED)
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(wakeWordReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(wakeWordReceiver, filter)
        }
    }

    /**
     * ★ 启动语音交互会话 v2.0
     * 完整链路：唤醒 → 暂停唤醒服务 → 识别 → NLU → 路由 → 反馈 → 恢复唤醒服务
     */
    fun startVoiceSession(wakeWord: String) {
        currentSession?.cancel()

        Timber.i("VoiceInteractionPipeline: ★ 启动语音会话 (wakeWord=${wakeWord})")

        currentSession = scope.launch {
            try {
                _sessionState.value = SessionState.WakeWordDetected

                // ★ 暂停唤醒服务，释放麦克风给ASR
                pauseWakeWordService()
                // ★ 等待跨进程暂停完成，确保麦克风完全释放
                delay(500)

                // ★ PRD: 唤醒成功反馈 — "滴"声+短震50ms + TTS提示
                speakWithResourceManagement("我在，请说指令", VoiceType.SYSTEM_STATUS)

                // 2. 启动 ASR 监听
                _sessionState.value = SessionState.Listening

                val startResult = commandRepository.startListening()
                if (startResult !is com.blindpath.base.common.Result.Success || !startResult.data) {
                    Timber.e("VoiceInteractionPipeline: ASR启动失败")
                    _sessionState.value = SessionState.Error("无法启动语音识别")
                    speakWithResourceManagement("语音识别启动失败，请重试", VoiceType.SYSTEM_STATUS)
                    return@launch
                }

                // ★ 等待ASR就绪（百度SDK的asr.ready事件）
                val ready = withTimeoutOrNull(5000) {
                    commandRepository.interactionState.first { it.isListening }
                }
                if (ready == null) {
                    Timber.e("VoiceInteractionPipeline: ASR未就绪（5秒内未收到asr.ready事件）")
                    _sessionState.value = SessionState.Error("语音识别未就绪")
                    speakWithResourceManagement("语音识别未就绪，请重试", VoiceType.SYSTEM_STATUS)
                    return@launch
                }
                Timber.i("VoiceInteractionPipeline: ASR就绪，等待用户说话...")

                // 3. 等待识别结果或错误
                var rawText = ""
                var commandProcessed = false
                var errorMessage: String? = null

                withTimeoutOrNull(config.maxListeningDuration) {
                    commandRepository.interactionState.first { state ->
                        state.lastCommand != null || state.lastError != null
                    }
                }?.let { state ->
                    when {
                        state.lastCommand != null -> {
                            commandProcessed = true
                            rawText = state.lastCommand?.rawText ?: ""
                            commandRepository.consumeLastCommand()
                        }
                        state.lastError != null -> {
                            errorMessage = state.lastError
                        }
                        else -> Unit
                    }
                }

                // ★ 错误优先处理
                if (errorMessage != null) {
                    Timber.w("VoiceInteractionPipeline: ASR错误: ${errorMessage}")
                    speakWithResourceManagement(errorMessage!!, VoiceType.SYSTEM_STATUS)
                    return@launch
                }

                if (!commandProcessed || rawText.isBlank()) {
                    Timber.w("VoiceInteractionPipeline: 监听超时或无结果")
                    speakWithResourceManagement("没有听清，请您大声再说一遍", VoiceType.SYSTEM_STATUS)
                    return@launch
                }

                Timber.i("VoiceInteractionPipeline: ASR结果 → \"$rawText\"")

                // ★ 4. NLU语义理解
                _sessionState.value = SessionState.Understanding
                val nluResult = nluEngine.parse(rawText)
                Timber.i("VoiceInteractionPipeline: NLU结果 → intent=${nluResult.intent.id}" +
                    " slots=${nluResult.slots}" +
                    " confidence=${nluResult.confidence}" +
                    " needsFollowUp=${nluResult.needsFollowUp}")

                // ★ 5. 意图路由执行
                _sessionState.value = SessionState.Executing
                val routeResult = intentRouter.route(nluResult)
                Timber.i("VoiceInteractionPipeline: 路由结果 → success=${routeResult.success}" +
                    " speak=\"${routeResult.speakText}\"" +
                    " needsFollowUp=${routeResult.needsFollowUp}")

                // ★ 6. TTS播报反馈
                val feedbackType = when (nluResult.intent) {
                    VoiceIntent.SOS -> VoiceType.SOS_TRIGGERED
                    else -> VoiceType.SYSTEM_STATUS
                }
                speakWithResourceManagement(routeResult.speakText, feedbackType)

                // ★ 7. 连续对话窗口
                if (routeResult.needsFollowUp) {
                    _sessionState.value = SessionState.WaitingFollowUp
                    startFollowUpWindow()
                }

            } catch (e: CancellationException) {
                Timber.d("VoiceInteractionPipeline: 会话取消")
            } catch (e: Exception) {
                Timber.e(e, "VoiceInteractionPipeline: 会话异常")
                _sessionState.value = SessionState.Error(e.message ?: "未知错误")
                speakWithResourceManagement("抱歉，暂时无法响应，请稍后再试", VoiceType.SYSTEM_STATUS)
            } finally {
                commandRepository.stopListening()
                // ★ 恢复唤醒服务
                resumeWakeWordService()
                if (_sessionState.value !is SessionState.WaitingFollowUp) {
                    _sessionState.value = SessionState.Idle
                }
            }
        }
    }

    /**
     * ★ 连续对话聆听窗口
     *
     * PRD要求：指令执行完毕后保持3秒聆听窗口，允许连续下达指令
     */
    private suspend fun startFollowUpWindow() {
        isInFollowUpWindow = true
        Timber.i("VoiceInteractionPipeline: ★ 连续对话窗口开启 (${config.followUpWindowMs}ms)")

        // 短暂延迟后开始监听
        delay(500)
        commandRepository.startListening()

        withTimeoutOrNull(config.followUpWindowMs) {
            commandRepository.interactionState.first { state ->
                state.lastCommand != null
            }
        }?.let { state ->
            val followUpText = state.lastCommand?.rawText ?: ""
            commandRepository.consumeLastCommand()

            if (followUpText.isNotBlank()) {
                Timber.i("VoiceInteractionPipeline: 连续对话 → \"$followUpText\"")

                // 在上下文中解析
                val nluResult = nluEngine.parse(followUpText)
                val routeResult = intentRouter.route(nluResult)
                speakWithResourceManagement(routeResult.speakText, VoiceType.SYSTEM_STATUS)
            }
        }

        commandRepository.stopListening()
        isInFollowUpWindow = false
        _sessionState.value = SessionState.Idle
        Timber.d("VoiceInteractionPipeline: 连续对话窗口关闭")
    }

    /**
     * 带 TTS 资源管理的播报
     */
    private suspend fun speakWithResourceManagement(text: String, type: VoiceType) {
        _sessionState.value = SessionState.Speaking
        unifiedAudioScheduler.enableTtsDucking()

        try {
            voiceRepository.announce(text, type)
            waitForTtsComplete()
        } finally {
            unifiedAudioScheduler.disableTtsDucking()
        }
    }

    /**
     * 等待 TTS 播报完成
     */
    private suspend fun waitForTtsComplete() {
        voiceRepository.voiceState
            .first { it.isSpeaking }
        voiceRepository.voiceState
            .first { !it.isSpeaking }
        delay(200)
    }

    /**
     * ★ 暂停唤醒服务，释放麦克风给ASR使用
     */
    private fun pauseWakeWordService() {
        try {
            val intent = Intent(context, WakeWordServiceEnhanced::class.java).apply {
                action = WakeWordServiceEnhanced.ACTION_PAUSE
            }
            context.startService(intent)
            Timber.i("VoiceInteractionPipeline: Sent PAUSE to WakeWordServiceEnhanced")
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteractionPipeline: Failed to pause wake word service")
        }
    }

    /**
     * ★ 恢复唤醒服务
     */
    private fun resumeWakeWordService() {
        try {
            val intent = Intent(context, WakeWordServiceEnhanced::class.java).apply {
                action = WakeWordServiceEnhanced.ACTION_RESUME
            }
            context.startService(intent)
            Timber.i("VoiceInteractionPipeline: Sent RESUME to WakeWordServiceEnhanced")
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteractionPipeline: Failed to resume wake word service")
        }
    }

    /**
     * 监听 TTS 状态
     */
    private fun observeTtsState() {
        scope.launch {
            voiceRepository.voiceState.collect { state ->
                if (state.isSpeaking) {
                    unifiedAudioScheduler.enableTtsDucking()
                }
            }
        }
    }

    /**
     * 获取NLU引擎（供外部注册自定义规则）
     */
    fun getNluEngine(): NluEngine = nluEngine

    /**
     * 获取意图路由器（供外部设置执行器）
     */
    fun getIntentRouter(): IntentRouter = intentRouter

    fun stopCurrentSession() {
        currentSession?.cancel()
        currentSession = null
        _sessionState.value = SessionState.Idle
    }

    fun release() {
        stopCurrentSession()
        try {
            wakeWordReceiver?.let { context.unregisterReceiver(it) }
        } catch (e: Exception) {
            Timber.w(e, "VoiceInteractionPipeline: 注销广播接收器失败")
        }
        scope.cancel()
        isInitialized = false
    }
}
