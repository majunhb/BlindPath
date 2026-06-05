package com.blindpath.module_voice.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceCommandRepository
import com.blindpath.module_voice.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.text.Normalizer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音指令识别实现
 * 
 * 使用 Android 内置 SpeechRecognizer，支持唤醒词检测和指令识别。
 * 
 * 全程语音交互修复要点：
 * 1. 修复唤醒词匹配：使用 WakeWordConfig.containsWakeWord() 匹配所有别名（小智/小智同学/小智小智）
 * 2. 修复唤醒词文本去除：stripWakeWord() 去除所有别名变体，避免指令误解析
 * 3. 修复 TTS 协调：notifyTtsStop 后恢复识别时，重置 isWaitingForWakeWord 状态
 * 4. 修复 health check 逻辑：监听停止后自动重启
 * 
 * 核心交互流程（全程语音服务）：
 * [APP启动] → TTS初始化 → ASR初始化 → speakWelcome()
 *   → TTS播报欢迎词 → setWakeWordEnabled(true) → startContinuousListening()
 *   → SpeechRecognizer 持续监听 → 等待用户说唤醒词
 * [用户说"小智同学"] → onResults() 检测到唤醒词
 *   → isWakeWordDetected = true → VoiceInteractionManager 播报"我在，请说指令"
 *   → SpeechRecognizer 重启监听 → 等待用户说指令
 * [用户说"开启障碍物检测"] → onResults() 解析指令
 *   → consumeLastCommand() → VoiceInteractionManager 执行指令
 *   → 播报结果 → 回到等待唤醒词
 */
@Singleton
class VoiceCommandRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceCommandRepository {
    
    companion object {
        /**
         * Unicode NFC 规范化文本
         * 
         * 解决不同设备 SpeechRecognizer 返回不同 Unicode 形式的问题。
         */
        private fun normalizeText(text: String): String {
            return Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
                .replace(" ", "")           // 移除半角空格
                .replace("\u3000", "")      // 移除全角空格
                .replace("\u200B", "")      // 移除零宽空格
                .replace("\uFEFF", "")      // 移除 BOM
        }

        /**
         * 从识别文本中去除唤醒词（含所有别名）
         * 
         * 使用最长匹配优先策略，避免"小智"误去除"小智同学"中的部分
         */
        fun stripWakeWord(text: String): String {
            var result = text
            // 按长度降序排列，优先匹配最长的别名
            val sortedAliases = WakeWordConfig.WAKE_WORD_ALIASES.sortedByDescending { it.length }
            for (alias in sortedAliases) {
                result = result.replace(alias, "")
            }
            return result.trim()
        }
    }
    
    private val _interactionState = MutableStateFlow(VoiceInteractionState())
    override val interactionState: StateFlow<VoiceInteractionState> = _interactionState.asStateFlow()
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isInitialized = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var isContinuousListeningEnabled = false
    private var isWaitingForWakeWord = true
    private var listeningJob: Job? = null
    
    private var retryCount = 0
    private val maxRetries = 5
    private var healthCheckJob: Job? = null
    private var recreateJob: Job? = null  // 防止并发重建
    
    // TTS 协调机制
    @Volatile
    private var isTtsSpeaking = false
    private var ttsResumeJob: Job? = null
    
    private val recognitionListener: RecognitionListener by lazy {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Timber.d("VoiceCommand: Ready for speech")
                _interactionState.update { it.copy(isListening = true) }
            }
            override fun onBeginningOfSpeech() {
                Timber.d("VoiceCommand: Beginning of speech")
            }
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                Timber.d("VoiceCommand: End of speech")
                _interactionState.update { it.copy(isListening = false) }
            }
            override fun onError(error: Int) {
                val errorMessage = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"       // 正常：用户没说话或没说清楚
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"     // 需要重建
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"         // 正常：等待超时
                    else -> "未知错误：$error"
                }
                Timber.e("VoiceCommand: Recognition error - $errorMessage (retry: $retryCount/$maxRetries)")
                _interactionState.update {
                    it.copy(isListening = false, lastError = errorMessage)
                }

                if (!isContinuousListeningEnabled) return

                // ★ 修复：根据错误类型分类处理，避免无效重试
                when (error) {
                    // 不可恢复的错误 — 直接停止连续监听
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                        Timber.e("VoiceCommand: No RECORD_AUDIO permission, stopping continuous listening")
                        stopContinuousListening()
                        return
                    }
                    // 需要重建识别器的错误
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, SpeechRecognizer.ERROR_CLIENT -> {
                        // 防止并发重建：已有重建任务进行中则跳过
                        if (recreateJob?.isActive == true) {
                            Timber.d("VoiceCommand: Recreate already in progress, skipping")
                            return
                        }
                        retryCount++
                        if (retryCount <= maxRetries) {
                            val delayMs = minOf(1000L * retryCount, 5000L)
                            recreateJob = scope.launch {
                                delay(delayMs)
                                if (isContinuousListeningEnabled) {
                                    safeRecreateRecognizer("attempt $retryCount")
                                    startListening()
                                }
                            }
                        } else {
                            // 超过重试次数，彻底重建（更长冷却）
                            retryCount = 0
                            recreateJob = scope.launch {
                                delay(3000)
                                safeRecreateRecognizer("full reset after max retries")
                                if (isContinuousListeningEnabled) startListening()
                            }
                        }
                    }
                    // 正常/可恢复错误 — 短延迟后重启（不叠加惩罚）
                    SpeechRecognizer.ERROR_NO_MATCH,
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                    SpeechRecognizer.ERROR_AUDIO,
                    SpeechRecognizer.ERROR_NETWORK,
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
                    SpeechRecognizer.ERROR_SERVER -> {
                        // NO_MATCH 和 TIMEOUT 是正常的，不加指数退避
                        val delayMs = when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 300L  // 快速恢复
                            else -> 1000L
                        }
                        scope.launch {
                            delay(delayMs)
                            if (isContinuousListeningEnabled) startListening()
                        }
                    }
                    else -> {
                        // 其他未知错误，保守策略
                        retryCount++
                        if (retryCount <= maxRetries) {
                            val delayMs = minOf(1000L * retryCount, 3000L)
                            scope.launch {
                                delay(delayMs)
                                if (isContinuousListeningEnabled) startListening()
                            }
                        } else {
                            retryCount = 0
                            scope.launch { delay(1000); if (isContinuousListeningEnabled) startListening() }
                        }
                    }
                }
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                if (!matches.isNullOrEmpty()) {
                    val rawText = matches[0]
                    val confidence = confidences?.getOrNull(0) ?: 0.5f
                    Timber.i("VoiceCommand: ★ Recognized: '$rawText' (confidence=$confidence, waitingForWake=$isWaitingForWakeWord)")
                    
                    // Unicode NFC 规范化匹配
                    val normalizedText = normalizeText(rawText)

                    // 修复：使用 WakeWordConfig.containsWakeWord() 匹配所有别名
                    // 不再只用 DEFAULT_WAKE_WORD 做单字符串匹配
                    val isWakeWordInText = WakeWordConfig.containsWakeWord(normalizedText)

                    // 关键修复：只在等待唤醒词 且 未被外部引擎触发时 才做内部唤醒词检测
                    // 避免与百度唤醒引擎双引擎冲突导致指令误解析
                    if (isWaitingForWakeWord && !_interactionState.value.isWakeWordDetected
                        && isWakeWordInText
                    ) {
                        Timber.i("VoiceCommand: ★★★ Wake word detected internally: '$rawText'")
                        isWaitingForWakeWord = false
                        retryCount = 0
                        _interactionState.update { it.copy(isWakeWordDetected = true, isListening = false) }
                        scope.launch {
                            delay(800)
                            Timber.i("VoiceCommand: Starting command listening after internal wake word")
                            startListening()
                        }
                    } else if (!isWaitingForWakeWord) {
                        // 已唤醒状态：解析指令
                        // 修复：使用 stripWakeWord() 去除所有别名变体
                        val commandText = stripWakeWord(normalizedText)
                        val command = if (commandText.isNotEmpty()) {
                            VoiceCommand.fromSpokenText(commandText)
                        } else {
                            // 如果用户只说了唤醒词没有跟指令
                            Timber.d("VoiceCommand: Only wake word spoken, waiting for command")
                            null
                        }
                        val result = VoiceCommandResult(
                            command = command,
                            confidence = confidence,
                            rawText = rawText
                        )
                        if (command != null) {
                            _interactionState.update {
                                it.copy(lastCommand = result, isListening = false, isWakeWordDetected = false)
                            }
                        }
                        // 重置回等待唤醒词状态
                        isWaitingForWakeWord = true
                        if (command != null && result.isSuccess) {
                            Timber.i("VoiceCommand: ★ Command recognized: '${command.name}'")
                        } else if (command == null) {
                            Timber.d("VoiceCommand: No command extracted, continuing to listen")
                        } else {
                            Timber.w("VoiceCommand: Command not recognized - ${result.failureReason}")
                        }
                        if (isContinuousListeningEnabled) {
                            scope.launch { delay(1000); startListening() }
                        }
                    } else {
                        Timber.d("VoiceCommand: Not wake word, continuing to listen")
                        _interactionState.update { it.copy(isListening = false) }
                        if (isContinuousListeningEnabled) {
                            scope.launch { delay(300); startListening() }
                        }
                    }
                } else {
                    Timber.w("VoiceCommand: No speech recognized")
                    _interactionState.update { it.copy(isListening = false, lastError = "未识别到语音") }
                    if (isContinuousListeningEnabled) {
                        scope.launch { delay(500); startListening() }
                    }
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    Timber.d("VoiceCommand: Partial result - ${matches[0]}")
                }
            }
            override fun onEvent(eventType: Int, params: Bundle?) {
                Timber.d("VoiceCommand: Event - $eventType")
            }
        }
    }
    
    override suspend fun initialize(): Result<Boolean> = withContext(Dispatchers.Main) {
        if (isInitialized) return@withContext Result.Success(true)
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                return@withContext Result.Error(message = "设备不支持语音识别")
            }
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)
            isInitialized = true
            Timber.i("VoiceCommand: Initialized successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "VoiceCommand: Initialization failed")
            Result.Error(message = "语音识别初始化失败：${e.message}")
        }
    }
    
    override suspend fun startListening(): Result<Boolean> = withContext(Dispatchers.Main) {
        if (!isInitialized || speechRecognizer == null) {
            return@withContext Result.Error(message = "语音识别未初始化")
        }
        // TTS 协调：不再因为 TTS 播报而阻断 startListening
        // notifyTtsStart 已不再调用 stopListening()，此处守卫也无需阻断
        if (isTtsSpeaking) {
            Timber.d("VoiceCommand: TTS speaking, but continuing startListening (non-blocking)")
            // 不返回 Error，继续执行 startListening，让系统级 AudioFocus 处理内容
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            speechRecognizer?.startListening(intent)
            Timber.d("VoiceCommand: Started listening")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "VoiceCommand: Failed to start listening")
            Result.Error(message = "启动语音识别失败：${e.message}")
        }
    }
    
    override suspend fun stopListening(): Result<Boolean> = withContext(Dispatchers.Main) {
        try {
            speechRecognizer?.stopListening()
            _interactionState.update { it.copy(isListening = false) }
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(message = "停止语音识别失败：${e.message}")
        }
    }
    
    override suspend fun recognizeOnce(): Result<VoiceCommandResult> {
        val startResult = startListening()
        if (startResult is Result.Error) return Result.Error(message = startResult.message ?: "启动语音识别失败")
        return withTimeoutOrNull(10_000) {
            interactionState.filter { it.lastCommand != null }.map { it.lastCommand!! }.first()
        }?.let { Result.Success(it) } ?: Result.Error(message = "语音识别超时")
    }
    
    /**
     * 安全重建 SpeechRecognizer：先 cancel → 清 listener → destroy → 延迟 → 重建
     * 解决 ERROR_RECOGNIZER_BUSY 死循环：旧实例未完全释放时新建会导致永久忙碌
     */
    private suspend fun safeRecreateRecognizer(reason: String) {
        try {
            Timber.i("VoiceCommand: Safe recreate start ($reason)")
            // 1. 停止当前识别
            speechRecognizer?.cancel()
            // 2. 移除 listener 防止回调到已销毁实例
            speechRecognizer?.setRecognitionListener(null)
            // 3. 销毁旧实例
            speechRecognizer?.destroy()
            speechRecognizer = null
            // 4. 等待系统释放麦克风资源（关键！）
            delay(500)
            // 5. 创建新实例
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)
            Timber.i("VoiceCommand: Safe recreate done ($reason)")
        } catch (e: Exception) {
            Timber.e(e, "VoiceCommand: Safe recreate failed ($reason)")
            speechRecognizer = null
        }
    }

    override fun release() {
        recreateJob?.cancel()
        stopContinuousListening()
        speechRecognizer?.cancel()
        speechRecognizer?.setRecognitionListener(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        isInitialized = false
        scope.cancel()
        Timber.d("VoiceCommand: Released")
    }
    
    override fun setWakeWord(word: String) {
        _interactionState.update { it.copy(wakeWord = word) }
    }
    
    override fun setWakeWordEnabled(enabled: Boolean) {
        _interactionState.update { it.copy(isWakeWordEnabled = enabled) }
        if (enabled) {
            // ★ 修复：启动持续监听前先取消 ttsResumeJob，
            // 防止 notifyTtsStop() 的延迟恢复协程与 startContinuousListening 竞争，
            // 导致 startListening() 被重复调用引发 ERROR_RECOGNIZER_BUSY
            ttsResumeJob?.cancel()
            ttsResumeJob = null
            isTtsSpeaking = false   // 确保 guard 不阻塞第一次 startListening
            startContinuousListening()
        } else {
            stopContinuousListening()
        }
    }
    
    /** TTS 开始播报时调用
     *
     * ★★★ TTS避让修复：
     * - 原实现：设置 isTtsSpeaking=true 并且调用 stopListening()
     * - 问题：每次导航播报/欢迎语播报，ASR 完全停止，唤醒词检测归零
     * - 新实现：只设置标志位，不中断 SpeechRecognizer
     * - 依据：UnifiedAudioScheduler.enableTtsDucking() 已实现 TTS DUCK 模式，
     *   TTS 播报与 ASR 监听可以共存，麦克风资源由系统级 AudioFocus 调度
     */
    override fun notifyTtsStart() {
        isTtsSpeaking = true
        ttsResumeJob?.cancel()
        Timber.d("VoiceCommand: TTS started (ASR continues, no stopListening)")
        // 不调用 stopListening()，让 SpeechRecognizer 持续监听
        // 唤醒词检测在 TTS 播报期间仍工作
    }
    
    /** TTS 停止播报时调用
     *
     * ★★★ TTS避让修复：
     * - 原实现： TTS 停止后才调用 startListening()，每次播报后有 300ms 空白
     * - 新实现：只清除标志位。若 ASR 已经在监听，无需重启；
     *   若 ASR 异常停止了，health check 会在 2s 内重启它
     */
    override fun notifyTtsStop() {
        isTtsSpeaking = false
        Timber.d("VoiceCommand: TTS stopped, ASR was not paused")
        // 不需要延迟恢复 startListening()，因为 notifyTtsStart 没有停止 ASR
        // health check 内建守厤中断的 SpeechRecognizer 不需要外部串联触发
    }
    
    private fun startContinuousListening() {
        if (isContinuousListeningEnabled) return
        isContinuousListeningEnabled = true
        isWaitingForWakeWord = true
        retryCount = 0
        recreateJob?.cancel()  // 取消待定重建
        Timber.i("VoiceCommand: ★ Continuous listening STARTED")
        listeningJob = scope.launch {
            // ★ 修复：从 500ms 减少到 200ms，加快监听启动
            delay(200)
            startListening()
        }
        healthCheckJob = scope.launch {
            var lastStateTime = System.currentTimeMillis()
            var lastListeningState = _interactionState.value.isListening
            while (isContinuousListeningEnabled) {
                delay(2000)
                val currentState = _interactionState.value.isListening
                if (currentState != lastListeningState) {
                    lastStateTime = System.currentTimeMillis()
                    lastListeningState = currentState
                } else {
                    val elapsed = System.currentTimeMillis() - lastStateTime
                    if (elapsed > 8000 && !currentState) {
                        Timber.w("VoiceCommand: Health check restart (inactive ${elapsed}ms)")
                        lastStateTime = System.currentTimeMillis()
                        recreateJob?.cancel()
                        startListening()
                    }
                }
            }
        }
    }
    
    private fun stopContinuousListening() {
        isContinuousListeningEnabled = false
        listeningJob?.cancel()
        listeningJob = null
        ttsResumeJob?.cancel()
        ttsResumeJob = null
        recreateJob?.cancel()
        recreateJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        Timber.i("VoiceCommand: Continuous listening STOPPED")
    }

    /**
     * 外部触发唤醒词检测
     * 
     * 由 WakeWordService 调用，当百度唤醒引擎检测到唤醒词时触发
     * 会自动切换到指令识别模式
     */
    override fun triggerWakeWordDetected(wakeWord: String) {
        Timber.i("VoiceCommand: ★ Wake word triggered externally - $wakeWord")
        
        // 设置唤醒词检测状态
        isWaitingForWakeWord = false
        retryCount = 0
        _interactionState.update { it.copy(isWakeWordDetected = true, wakeWord = wakeWord) }
        
        // 启动指令识别监听
        scope.launch {
            delay(500)  // 等待用户准备说指令
            Timber.i("VoiceCommand: Starting command listening after external wake word trigger")
            startListening()
        }
    }

    /**
     * 原子消费并清除 lastCommand
     * 
     * 读取当前 lastCommand 并立即从 _interactionState 中清除，
     * 确保 VoiceInteractionManagerImpl 每条指令只处理一次，
     * 避免因两个不同 StateFlow 实例导致的重复处理 bug
     */
    override fun consumeLastCommand(): VoiceCommandResult? {
        val current = _interactionState.value.lastCommand
        if (current != null) {
            Timber.d("VoiceCommand: Consuming lastCommand - ${current.command?.name ?: current.failureReason}")
            _interactionState.update { it.copy(lastCommand = null) }
        }
        return current
    }
}
