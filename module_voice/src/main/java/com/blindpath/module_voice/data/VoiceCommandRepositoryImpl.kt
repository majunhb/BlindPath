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
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音指令识别实现
 * 
 * 使用 Android SpeechRecognizer API 进行离线语音识别
 * 支持唤醒词检测和指令识别
 * 
 * 修复记录：
 * - 修复 TTS 播报与识别器音频焦点冲突
 * - 修复 onError 后监听不重启的问题
 * - 修复唤醒词命中后指令监听不启动的问题
 * - 添加健康检查定时器防止监听静默失败
 */
@Singleton
class VoiceCommandRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceCommandRepository {
    
    private val _interactionState = MutableStateFlow(VoiceInteractionState())
    override val interactionState: StateFlow<VoiceInteractionState> = _interactionState.asStateFlow()
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isInitialized = false
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 持续监听模式
    private var isContinuousListeningEnabled = false
    private var isWaitingForWakeWord = true  // true: 等待唤醒词, false: 等待指令
    private var listeningJob: Job? = null
    
    // ====== 新增：错误恢复与健康检查 ======
    private var consecutiveErrors = 0
    private val maxConsecutiveErrors = 5
    private var healthCheckJob: Job? = null
    private var lastStateChangeTime = 0L
    private val healthCheckTimeoutMs = 8_000L  // 8秒无状态变化则重启
    
    // ====== 新增：TTS 播报暂停标记 ======
    @Volatile
    private var isTtsSpeaking = false
    
    // ====== 新增：监听状态追踪 ======
    @Volatile
    private var isRecognizerActive = false  // SpeechRecognizer 是否正在监听

    // 语音识别监听器
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Timber.d("VoiceCommand: Ready for speech")
            isRecognizerActive = true
            lastStateChangeTime = System.currentTimeMillis()
            _interactionState.update { it.copy(isListening = true) }
        }
        
        override fun onBeginningOfSpeech() {
            Timber.d("VoiceCommand: Beginning of speech")
            lastStateChangeTime = System.currentTimeMillis()
        }
        
        override fun onRmsChanged(rmsdB: Float) {
            // 音量变化，可用于 UI 反馈
        }
        
        override fun onBufferReceived(buffer: ByteArray?) {
            // 接收到音频数据
        }
        
        override fun onEndOfSpeech() {
            Timber.d("VoiceCommand: End of speech")
            isRecognizerActive = false
            lastStateChangeTime = System.currentTimeMillis()
            _interactionState.update { it.copy(isListening = false) }
        }
        
        override fun onError(error: Int) {
            isRecognizerActive = false
            lastStateChangeTime = System.currentTimeMillis()
            consecutiveErrors++
            
            val errorMessage = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "音频录制错误"
                SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                else -> "未知错误：$error"
            }
            Timber.e("VoiceCommand: Recognition error (#$consecutiveErrors) - $errorMessage")
            _interactionState.update { 
                it.copy(
                    isListening = false,
                    lastError = errorMessage
                )
            }
            
            // ====== 修复：所有错误类型均自动重连 ======
            if (isContinuousListeningEnabled && consecutiveErrors <= maxConsecutiveErrors) {
                val delayMs = when {
                    error == SpeechRecognizer.ERROR_NO_MATCH -> 300L
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 500L
                    error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1000L
                    else -> (500L * minOf(consecutiveErrors, 3))  // 递增延迟
                }
                Timber.d("VoiceCommand: Auto-restart listening after ${delayMs}ms (attempt $consecutiveErrors)")
                scope.launch {
                    delay(delayMs)
                    restartListeningSafely()
                }
            } else if (consecutiveErrors > maxConsecutiveErrors) {
                Timber.e("VoiceCommand: Too many consecutive errors ($consecutiveErrors), stopping continuous listening")
                // 重置错误计数，延迟后重试
                scope.launch {
                    delay(3000)
                    consecutiveErrors = 0
                    restartListeningSafely()
                }
            }
        }
        
        override fun onResults(results: Bundle?) {
            consecutiveErrors = 0  // 成功识别，重置错误计数
            lastStateChangeTime = System.currentTimeMillis()
            
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            
            if (!matches.isNullOrEmpty()) {
                val rawText = matches[0]
                val confidence = confidences?.getOrNull(0) ?: 0.5f
                
                Timber.d("VoiceCommand: Recognized text: $rawText, confidence: $confidence")
                
                // 检查是否是唤醒词
                val wakeWord = _interactionState.value.wakeWord
                val normalizedText = rawText.trim().replace(" ", "")
                val normalizedWakeWord = wakeWord.trim().replace(" ", "")
                
                if (isWaitingForWakeWord && normalizedText.contains(normalizedWakeWord, ignoreCase = true)) {
                    // 检测到唤醒词
                    Timber.i("VoiceCommand: Wake word detected - $rawText")
                    isWaitingForWakeWord = false
                    _interactionState.update { 
                        it.copy(
                            isWakeWordDetected = true,
                            isListening = false
                        )
                    }
                    
                    // ====== 修复：切到 Main 线程启动指令监听 ======
                    scope.launch {
                        delay(800)  // 给用户一点反应时间
                        Timber.d("VoiceCommand: Starting command listening after wake word")
                        startListening()
                    }
                } else if (!isWaitingForWakeWord) {
                    // 等待指令模式：解析指令
                    val command = VoiceCommand.fromSpokenText(rawText)
                    val result = VoiceCommandResult(
                        command = command,
                        confidence = confidence,
                        rawText = rawText
                    )
                    
                    _interactionState.update { 
                        it.copy(
                            lastCommand = result,
                            isListening = false,
                            isWakeWordDetected = false
                        )
                    }
                    
                    isWaitingForWakeWord = true // 重置为等待唤醒词模式
                    
                    if (result.isSuccess) {
                        Timber.i("VoiceCommand: Command recognized - ${command?.name}")
                    } else {
                        Timber.w("VoiceCommand: Command not recognized - ${result.failureReason}")
                    }
                    
                    // 如果启用了持续监听，继续监听下一次唤醒
                    if (isContinuousListeningEnabled) {
                        scope.launch {
                            delay(1000) // 给用户一点时间
                            startListening()
                        }
                    }
                } else {
                    // 等待唤醒词模式，但没检测到唤醒词
                    Timber.d("VoiceCommand: Not wake word, continuing to listen")
                    _interactionState.update { it.copy(isListening = false) }
                    
                    // 持续监听模式：继续监听
                    if (isContinuousListeningEnabled) {
                        scope.launch {
                            delay(300)
                            startListening()
                        }
                    }
                }
            } else {
                Timber.w("VoiceCommand: No speech recognized")
                _interactionState.update { 
                    it.copy(
                        isListening = false,
                        lastError = "未识别到语音"
                    )
                }
                
                // 持续监听模式：继续监听
                if (isContinuousListeningEnabled) {
                    scope.launch {
                        delay(500)
                        startListening()
                    }
                }
            }
        }
        
        override fun onPartialResults(partialResults: Bundle?) {
            // 部分识别结果（实时反馈）
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                Timber.d("VoiceCommand: Partial result - ${matches[0]}")
            }
        }
        
        override fun onEvent(eventType: Int, params: Bundle?) {
            Timber.d("VoiceCommand: Event - $eventType")
        }
    }
    
    override suspend fun initialize(): Result<Boolean> = withContext(Dispatchers.Main) {
        if (isInitialized) {
            return@withContext Result.Success(true)
        }
        
        try {
            // 检查语音识别是否可用
            if (!SpeechRecognizer.isRecognitionAvailable(context)) {
                Timber.e("VoiceCommand: Speech recognition not available")
                return@withContext Result.Error(message = "设备不支持语音识别")
            }
            
            // 创建语音识别器
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)
            
            isInitialized = true
            lastStateChangeTime = System.currentTimeMillis()
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
        
        // ====== 新增：TTS 正在播报时延迟启动 ======
        if (isTtsSpeaking) {
            Timber.d("VoiceCommand: TTS is speaking, delaying listening start")
            delay(500)
            if (isTtsSpeaking) {
                // TTS 还在说，再等一会
                delay(500)
            }
        }
        
        try {
            // ====== 新增：如果识别器还在活跃状态，先停止 ======
            if (isRecognizerActive) {
                Timber.d("VoiceCommand: Recognizer still active, stopping first")
                speechRecognizer?.stopListening()
                delay(200)
            }
            
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            }
            
            speechRecognizer?.startListening(intent)
            lastStateChangeTime = System.currentTimeMillis()
            Timber.d("VoiceCommand: Started listening (wakeWord=$isWaitingForWakeWord)")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "VoiceCommand: Failed to start listening")
            // ====== 新增：启动失败时尝试重建识别器 ======
            scope.launch {
                recreateRecognizer()
                if (isContinuousListeningEnabled) {
                    delay(500)
                    startListening()
                }
            }
            Result.Error(message = "启动语音识别失败：${e.message}")
        }
    }
    
    override suspend fun stopListening(): Result<Boolean> = withContext(Dispatchers.Main) {
        try {
            speechRecognizer?.stopListening()
            isRecognizerActive = false
            _interactionState.update { it.copy(isListening = false) }
            Timber.d("VoiceCommand: Stopped listening")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "VoiceCommand: Failed to stop listening")
            Result.Error(message = "停止语音识别失败：${e.message}")
        }
    }
    
    override suspend fun recognizeOnce(): Result<VoiceCommandResult> {
        val startResult = startListening()
        if (startResult is Result.Error) {
            return Result.Error(message = startResult.message ?: "启动语音识别失败")
        }
        
        // 等待识别结果（最多 10 秒）
        return withTimeoutOrNull(10_000) {
            interactionState
                .filter { it.lastCommand != null }
                .map { it.lastCommand!! }
                .first()
        }?.let { Result.Success(it) }
            ?: Result.Error(message = "语音识别超时")
    }
    
    override fun release() {
        healthCheckJob?.cancel()
        healthCheckJob = null
        speechRecognizer?.destroy()
        speechRecognizer = null
        isInitialized = false
        isRecognizerActive = false
        scope.cancel()
        Timber.d("VoiceCommand: Released")
    }
    
    override fun setWakeWord(word: String) {
        _interactionState.update { it.copy(wakeWord = word) }
        Timber.d("VoiceCommand: Wake word set to '$word'")
    }
    
    override fun setWakeWordEnabled(enabled: Boolean) {
        _interactionState.update { it.copy(isWakeWordEnabled = enabled) }
        Timber.d("VoiceCommand: Wake word enabled = $enabled")
        
        if (enabled) {
            // 启用持续监听模式
            startContinuousListening()
        } else {
            // 禁用持续监听模式
            stopContinuousListening()
        }
    }
    
    // ====== 新增：TTS 状态通知接口 ======
    
    /**
     * 通知 TTS 开始播报
     * 识别器在 TTS 播报时应暂停，避免音频焦点冲突
     */
    fun notifyTtsStart() {
        isTtsSpeaking = true
        Timber.d("VoiceCommand: TTS started speaking, pausing recognition")
        // 暂停当前识别
        if (isRecognizerActive) {
            try {
                speechRecognizer?.stopListening()
                isRecognizerActive = false
            } catch (e: Exception) {
                Timber.w(e, "VoiceCommand: Failed to stop recognizer for TTS")
            }
        }
    }
    
    /**
     * 通知 TTS 播报结束
     * 播报结束后延迟恢复识别监听
     */
    fun notifyTtsStop() {
        isTtsSpeaking = false
        Timber.d("VoiceCommand: TTS stopped speaking, resuming recognition")
        // 延迟恢复监听，给音频焦点释放的时间
        if (isContinuousListeningEnabled) {
            scope.launch {
                delay(600)  // 等待音频焦点释放
                if (!isRecognizerActive && !isTtsSpeaking) {
                    Timber.d("VoiceCommand: Resuming listening after TTS stop")
                    startListening()
                }
            }
        }
    }
    
    /**
     * 启动持续监听模式
     */
    private fun startContinuousListening() {
        if (isContinuousListeningEnabled) return
        
        isContinuousListeningEnabled = true
        isWaitingForWakeWord = true
        consecutiveErrors = 0
        Timber.i("VoiceCommand: Continuous listening started")
        
        // ====== 调试：通过状态通知外部 ======
        _interactionState.update { 
            it.copy(
                lastError = "持续监听已启动",  // 利用 lastError 字段传递调试信息
                isWakeWordEnabled = true
            )
        }
        
        listeningJob = scope.launch {
            delay(500) // 短暂延迟后开始
            startListening()
        }
        
        // ====== 新增：启动健康检查 ======
        startHealthCheck()
    }
    
    /**
     * 停止持续监听模式
     */
    private fun stopContinuousListening() {
        isContinuousListeningEnabled = false
        listeningJob?.cancel()
        listeningJob = null
        healthCheckJob?.cancel()
        healthCheckJob = null
        Timber.i("VoiceCommand: Continuous listening stopped")
    }
    
    // ====== 新增：安全重启监听 ======
    
    /**
     * 安全重启监听 - 检查前置条件后启动
     */
    private suspend fun restartListeningSafely() {
        if (!isContinuousListeningEnabled || !isInitialized || speechRecognizer == null) {
            Timber.d("VoiceCommand: Skip restart - not enabled or not initialized")
            return
        }
        
        if (isTtsSpeaking) {
            Timber.d("VoiceCommand: Skip restart - TTS is speaking")
            return
        }
        
        if (isRecognizerActive) {
            Timber.d("VoiceCommand: Skip restart - recognizer is already active")
            return
        }
        
        try {
            startListening()
        } catch (e: Exception) {
            Timber.e(e, "VoiceCommand: Safe restart failed")
        }
    }
    
    // ====== 新增：健康检查定时器 ======
    
    /**
     * 启动健康检查 - 定期检查识别器是否卡死
     */
    private fun startHealthCheck() {
        healthCheckJob?.cancel()
        healthCheckJob = scope.launch {
            while (isActive) {
                delay(healthCheckTimeoutMs)
                
                if (!isContinuousListeningEnabled || !isInitialized) continue
                
                val timeSinceLastChange = System.currentTimeMillis() - lastStateChangeTime
                
                // 如果长时间没有状态变化且识别器不在活跃状态，重启监听
                if (timeSinceLastChange > healthCheckTimeoutMs && !isRecognizerActive && !isTtsSpeaking) {
                    Timber.w("VoiceCommand: Health check - no activity for ${timeSinceLastChange}ms, restarting listener")
                    consecutiveErrors = 0
                    restartListeningSafely()
                }
            }
        }
    }
    
    // ====== 新增：重建识别器 ======
    
    /**
     * 重建 SpeechRecognizer 实例
     * 用于识别器进入异常状态时的恢复
     */
    private suspend fun recreateRecognizer() {
        Timber.w("VoiceCommand: Recreating SpeechRecognizer")
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Timber.w(e, "VoiceCommand: Error destroying old recognizer")
        }
        
        try {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)
            isRecognizerActive = false
            Timber.i("VoiceCommand: SpeechRecognizer recreated successfully")
        } catch (e: Exception) {
            Timber.e(e, "VoiceCommand: Failed to recreate SpeechRecognizer")
            isInitialized = false
        }
    }
}
