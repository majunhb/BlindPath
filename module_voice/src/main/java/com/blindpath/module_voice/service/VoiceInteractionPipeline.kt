package com.blindpath.module_voice.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import com.blindpath.module_voice.domain.VoiceCommandRepository
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.VoiceCommand
import com.blindpath.module_voice.domain.model.VoiceType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音交互全链路管理器
 * 
 * 核心职责：
 * 1. 打通唤醒 -> 识别 -> 执行 -> 反馈完整链路
 * 2. 维护语音会话生命周期
 * 3. 协调 TTS 播报与收音冲突
 * 4. 异常自动恢复机制
 * 
 * 链路流程：
 * 唤醒词检测 -> 启动 ASR 会话 -> 语音识别 -> NLP 解析 -> 指令执行 -> 结果反馈
 */
@Singleton
class VoiceInteractionPipeline @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceRepository: VoiceRepository,
    private val commandRepository: VoiceCommandRepository,
    private val unifiedAudioScheduler: UnifiedAudioScheduler
) {
    // 会话状态
    sealed class SessionState {
        object Idle : SessionState()
        object WakeWordDetected : SessionState()
        object Listening : SessionState()
        object Processing : SessionState()
        object Speaking : SessionState()
        data class Error(val message: String) : SessionState()
    }
    
    // 会话配置
    data class SessionConfig(
        val maxListeningDuration: Long = 10000,  // 最大监听时长 10秒
        val sessionTimeout: Long = 15000,        // 会话超时 15秒
        val retryCount: Int = 2                   // 重试次数
    )
    
    private val _sessionState = MutableStateFlow<SessionState>(SessionState.Idle)
    val sessionState: StateFlow<SessionState> = _sessionState.asStateFlow()
    
    private var currentSession: Job? = null
    private var wakeWordReceiver: BroadcastReceiver? = null
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    private var commandExecutor: ((VoiceCommand) -> Boolean)? = null
    
    @Volatile
    private var isInitialized = false
    
    /**
     * 初始化全链路管理器
     */
    fun initialize() {
        if (isInitialized) return
        
        Timber.i("VoiceInteractionPipeline: Initializing")
        
        // 注册唤醒词广播接收器
        registerWakeWordReceiver()
        
        // 监听 TTS 状态
        observeTtsState()
        
        isInitialized = true
        Timber.i("VoiceInteractionPipeline: Initialized")
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
                        val scene = intent.getStringExtra(WakeWordServiceEnhanced.EXTRA_SCENE)
                        
                        Timber.i("VoiceInteractionPipeline: Wake word received - $wakeWord (scene: $scene)")
                        
                        // 启动语音交互会话
                        startVoiceSession(wakeWord ?: "小志小志")
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
        
        Timber.d("VoiceInteractionPipeline: Wake word receiver registered")
    }
    
    /**
     * 启动语音交互会话
     */
    fun startVoiceSession(wakeWord: String) {
        // 取消之前的会话
        currentSession?.cancel()
        
        Timber.i("VoiceInteractionPipeline: Starting voice session for wake word: $wakeWord")
        
        currentSession = scope.launch {
            try {
                _sessionState.value = SessionState.WakeWordDetected
                
                // 1. 播报唤醒反馈
                speakWithResourceManagement("我在，请说指令", VoiceType.SYSTEM_STATUS)
                
                // 2. 启动 ASR 监听
                _sessionState.value = SessionState.Listening
                
                val startResult = commandRepository.startListening()
                if (startResult !is com.blindpath.base.Result.Success) {
                    Timber.e("VoiceInteractionPipeline: Failed to start listening")
                    _sessionState.value = SessionState.Error("无法启动语音识别")
                    speakWithResourceManagement("语音识别启动失败，请重试", VoiceType.ERROR)
                    return@launch
                }
                
                // 3. 等待识别结果
                withTimeoutOrNull(SessionConfig().maxListeningDuration) {
                    commandRepository.interactionState
                        .first { state ->
                            state.lastCommand != null || state.recognitionState == com.blindpath.module_voice.domain.model.RecognitionState.ERROR
                        }
                }?.let { state ->
                    state.lastCommand?.let { result ->
                        if (result.isSuccess && result.command != null) {
                            // 4. 处理指令
                            processCommand(result.command!!)
                        } else {
                            Timber.w("VoiceInteractionPipeline: Recognition failed - ${result.failureReason}")
                            _sessionState.value = SessionState.Error(result.failureReason ?: "识别失败")
                            speakWithResourceManagement("未识别的指令，请重新说", VoiceType.ERROR)
                        }
                    }
                } ?: run {
                    // 超时
                    Timber.w("VoiceInteractionPipeline: Listening timeout")
                    _sessionState.value = SessionState.Error("监听超时")
                    speakWithResourceManagement("未检测到语音，请重试", VoiceType.ERROR)
                }
                
            } catch (e: CancellationException) {
                Timber.d("VoiceInteractionPipeline: Session cancelled")
            } catch (e: Exception) {
                Timber.e(e, "VoiceInteractionPipeline: Session error")
                _sessionState.value = SessionState.Error(e.message ?: "未知错误")
                speakWithResourceManagement("语音交互出错，请重试", VoiceType.ERROR)
            } finally {
                // 停止监听
                commandRepository.stopListening()
                _sessionState.value = SessionState.Idle
            }
        }
    }
    
    /**
     * 处理语音指令
     */
    private suspend fun processCommand(command: VoiceCommand) {
        Timber.i("VoiceInteractionPipeline: Processing command - ${command.name}")
        
        _sessionState.value = SessionState.Processing
        
        // 播报执行状态
        speakWithResourceManagement("正在执行：${command.spokenText}", VoiceType.SYSTEM_STATUS)
        
        // 执行指令
        val success = commandExecutor?.invoke(command) ?: false
        
        // 播报执行结果
        if (success) {
            speakWithResourceManagement("指令执行成功", VoiceType.SYSTEM_STATUS)
        } else {
            speakWithResourceManagement("指令执行失败", VoiceType.ERROR)
        }
    }
    
    /**
     * 带 TTS 资源管理的播报
     * 
     * 解决 TTS 播报与收音冲突：
     * 1. 播报前请求音频资源
     * 2. 播报时不切断收音，使用 DUCK 模式
     * 3. 播报后释放资源，恢复收音
     */
    private suspend fun speakWithResourceManagement(text: String, type: VoiceType) {
        _sessionState.value = SessionState.Speaking
        
        // 启用 TTS DUCK 模式
        unifiedAudioScheduler.enableTtsDucking()
        
        try {
            // 播报
            voiceRepository.announce(text, type)
            
            // 等待播报完成
            waitForTtsComplete()
            
        } finally {
            // 禁用 TTS DUCK 模式
            unifiedAudioScheduler.disableTtsDucking()
        }
    }
    
    /**
     * 等待 TTS 播报完成
     */
    private suspend fun waitForTtsComplete() {
        // 等待 TTS 开始
        var waitCount = 0
        while (!voiceRepository.voiceState.first().isSpeaking && waitCount < 30) {
            delay(100)
            waitCount++
        }
        
        // 等待 TTS 结束
        if (voiceRepository.voiceState.first().isSpeaking) {
            waitCount = 0
            while (voiceRepository.voiceState.first().isSpeaking && waitCount < 100) {
                delay(100)
                waitCount++
            }
        }
        
        // 额外等待确保完成
        delay(300)
    }
    
    /**
     * 监听 TTS 状态
     */
    private fun observeTtsState() {
        scope.launch {
            voiceRepository.voiceState.collect { state ->
                if (state.isSpeaking) {
                    // TTS 正在播报，通知音频调度器
                    unifiedAudioScheduler.enableTtsDucking()
                }
            }
        }
    }
    
    /**
     * 设置指令执行器
     */
    fun setCommandExecutor(executor: (VoiceCommand) -> Boolean) {
        this.commandExecutor = executor
        Timber.d("VoiceInteractionPipeline: Command executor set")
    }
    
    /**
     * 停止当前会话
     */
    fun stopCurrentSession() {
        currentSession?.cancel()
        currentSession = null
        _sessionState.value = SessionState.Idle
        
        Timber.d("VoiceInteractionPipeline: Current session stopped")
    }
    
    /**
     * 释放资源
     */
    fun release() {
        Timber.i("VoiceInteractionPipeline: Releasing")
        
        // 停止当前会话
        stopCurrentSession()
        
        // 注销广播接收器
        try {
            wakeWordReceiver?.let {
                context.unregisterReceiver(it)
            }
        } catch (e: Exception) {
            Timber.w(e, "VoiceInteractionPipeline: Failed to unregister receiver")
        }
        
        // 取消协程
        scope.cancel()
        
        isInitialized = false
        Timber.i("VoiceInteractionPipeline: Released")
    }
}
