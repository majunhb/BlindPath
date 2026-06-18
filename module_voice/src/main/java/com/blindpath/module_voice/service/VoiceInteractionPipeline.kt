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
 * 语音交互全链路管理器 v3.0 — 讯飞全链路版
 *
 * 完整链路：唤醒 → ASR识别 → NLU语义理解 → 意图路由 → TTS播报反馈
 *
 * ★★★ v3.0 核心变更（讯飞全链路）：
 * 1. 百度云端ASR → 讯飞离线ASR（无网也能识别）
 * 2. 去掉TTS/ASR时序协调——讯飞VAD自动检测说话端点
 * 3. 去掉stop+restart ASR——VAD自动判断用户何时说完
 * 4. 去掉asr.ready等待——讯飞ASR启动即可写入音频
 * 5. 简化流程：唤醒→暂停唤醒服务→ASR→等结果→NLU→路由→恢复唤醒服务
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
    sealed class SessionState {
        object Idle : SessionState()
        object WakeWordDetected : SessionState()
        object Listening : SessionState()
        object Understanding : SessionState()
        object Executing : SessionState()
        object Speaking : SessionState()
        object WaitingFollowUp : SessionState()
        data class Error(val message: String) : SessionState()
    }

    data class SessionConfig(
        val maxListeningDuration: Long = 10000,
        val sessionTimeout: Long = 15000,
        val retryCount: Int = 2,
        val followUpWindowMs: Long = 3000
    )

    private val config = SessionConfig()

    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()

    private var currentSession: Job? = null
    private var wakeWordReceiver: BroadcastReceiver? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Volatile
    private var isInitialized = false

    @Volatile
    private var isInFollowUpWindow = false

    fun initialize() {
        if (isInitialized) return

        Timber.i("VoiceInteractionPipeline v3.0: Initializing (讯飞离线ASR)")

        scope.launch {
            val result = commandRepository.initialize()
            if (result is com.blindpath.base.common.Result.Success) {
                Timber.i("VoiceInteractionPipeline: CommandRepository initialized (讯飞ASR)")
            } else {
                Timber.w("VoiceInteractionPipeline: CommandRepository init failed")
            }
        }

        registerWakeWordReceiver()
        observeTtsState()
        isInitialized = true
        Timber.i("VoiceInteractionPipeline v3.0: Initialized")
    }

    private fun registerWakeWordReceiver() {
        wakeWordReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    WakeWordServiceEnhanced.ACTION_WAKE_WORD_DETECTED -> {
                        val wakeWord = intent.getStringExtra(WakeWordServiceEnhanced.EXTRA_WAKE_WORD)
                        Timber.i("VoiceInteractionPipeline: ★ 唤醒词检测到: $wakeWord")

                        // PRD: 唤醒成功立即打断当前TTS播报
                        scope.launch {
                            voiceRepository.stop()
                        }

                        startVoiceSession(wakeWord ?: "小智小智")
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
     * ★ 启动语音交互会话 v3.0（讯飞离线ASR简化版）
     *
     * 流程：唤醒 → 暂停唤醒服务 → ASR → 等结果 → NLU → 路由 → 恢复唤醒服务
     *
     * 相比v2.0的简化：
     * - 去掉 asr.ready 等待（讯飞启动即可用）
     * - 去掉 TTS→stop ASR→restart ASR 的时序hack（VAD自动处理）
     * - 去掉手动stopListening等ASR就绪（VAD自动检测说话结束）
     */
    fun startVoiceSession(wakeWord: String) {
        currentSession?.cancel()

        Timber.i("VoiceInteractionPipeline: ★ 启动语音会话 (wakeWord=\${wakeWord})")

        currentSession = scope.launch {
            try {
                _sessionState.value = SessionState.WakeWordDetected

                // ★ 暂停唤醒服务，释放麦克风给ASR
                pauseWakeWordService()
                delay(500) // 等跨进程暂停完成

                // ★ 启动ASR（讯飞离线，启动即可用，无需等ready）
                _sessionState.value = SessionState.Listening
                val startResult = commandRepository.startListening()
                if (startResult !is com.blindpath.base.common.Result.Success || !startResult.data) {
                    Timber.e("VoiceInteractionPipeline: ASR启动失败")
                    _sessionState.value = SessionState.Error("无法启动语音识别")
                    speakWithResourceManagement("语音识别启动失败，请重试", VoiceType.SYSTEM_STATUS)
                    return@launch
                }

                // ★ 播报短提示（讯飞VAD会自动忽略TTS音频，不需要stop+restart）
                speakWithResourceManagement("嗯，请说", VoiceType.SYSTEM_STATUS)

                // ★ 等待识别结果（VAD自动检测说话结束，无需手动stop）
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

                // 错误优先处理
                if (errorMessage != null) {
                    Timber.w("VoiceInteractionPipeline: ASR错误: \${errorMessage}")
                    speakWithResourceManagement(errorMessage!!, VoiceType.SYSTEM_STATUS)
                    return@launch
                }

                if (!commandProcessed || rawText.isBlank()) {
                    Timber.w("VoiceInteractionPipeline: 监听超时或无结果")
                    speakWithResourceManagement("没有听清，请您大声再说一遍", VoiceType.SYSTEM_STATUS)
                    return@launch
                }

                Timber.i("VoiceInteractionPipeline: ASR结果 → \"\${rawText}\"")

                // ★ NLU语义理解
                _sessionState.value = SessionState.Understanding
                val nluResult = nluEngine.parse(rawText)
                Timber.i("VoiceInteractionPipeline: NLU结果 → intent=\${nluResult.intent.id}" +
                    " slots=\${nluResult.slots}" +
                    " confidence=\${nluResult.confidence}" +
                    " needsFollowUp=\${nluResult.needsFollowUp}")

                // ★ 意图路由执行
                _sessionState.value = SessionState.Executing
                val routeResult = intentRouter.route(nluResult)
                Timber.i("VoiceInteractionPipeline: 路由结果 → success=\${routeResult.success}" +
                    " speak=\"\${routeResult.speakText}\"" +
                    " needsFollowUp=\${routeResult.needsFollowUp}")

                // ★ TTS播报反馈
                val feedbackType = when (nluResult.intent) {
                    VoiceIntent.SOS -> VoiceType.SOS_TRIGGERED
                    else -> VoiceType.SYSTEM_STATUS
                }
                speakWithResourceManagement(routeResult.speakText, feedbackType)

                // ★ 连续对话窗口
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
                resumeWakeWordService()
                if (_sessionState.value !is SessionState.WaitingFollowUp) {
                    _sessionState.value = SessionState.Idle
                }
            }
        }
    }

    private suspend fun startFollowUpWindow() {
        isInFollowUpWindow = true
        Timber.i("VoiceInteractionPipeline: ★ 连续对话窗口开启 (\${config.followUpWindowMs}ms)")

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
                Timber.i("VoiceInteractionPipeline: 连续对话 → \"\${followUpText}\"")
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

    private suspend fun waitForTtsComplete() {
        voiceRepository.voiceState.first { it.isSpeaking }
        voiceRepository.voiceState.first { !it.isSpeaking }
        delay(200)
    }

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

    private fun observeTtsState() {
        scope.launch {
            voiceRepository.voiceState.collect { state ->
                if (state.isSpeaking) {
                    unifiedAudioScheduler.enableTtsDucking()
                }
            }
        }
    }

    fun getNluEngine(): NluEngine = nluEngine
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
