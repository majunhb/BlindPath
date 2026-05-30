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

@Singleton
class VoiceCommandRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceCommandRepository {
    
    companion object {
        /**
         * Unicode NFC 规范化文本
         * 
         * 解决不同设备 SpeechRecognizer 返回不同 Unicode 形式的问题。
         * 例如：组合字符可能有 NFC（预组合）和 NFD（分解）两种形式，
         * 使用 NFC 规范化确保一致性匹配。
         */
        private fun normalizeText(text: String): String {
            return Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
                .replace(" ", "")           // 移除半角空格
                .replace("\u3000", "")      // 移除全角空格
                .replace("\u200B", "")      // 移除零宽空格
                .replace("\uFEFF", "")      // 移除 BOM
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
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                    else -> "未知错误：$error"
                }
                Timber.e("VoiceCommand: Recognition error - $errorMessage (retry: $retryCount/$maxRetries)")
                _interactionState.update { 
                    it.copy(isListening = false, lastError = errorMessage)
                }
                if (isContinuousListeningEnabled) {
                    retryCount++
                    if (retryCount <= maxRetries) {
                        val delayMs = minOf(1000L * retryCount, 5000L)
                        scope.launch {
                            delay(delayMs)
                            if (isContinuousListeningEnabled) {
                                if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                                    speechRecognizer?.destroy()
                                    speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                                    speechRecognizer?.setRecognitionListener(recognitionListener)
                                }
                                startListening()
                            }
                        }
                    } else {
                        retryCount = 0
                        scope.launch {
                            delay(1000)
                            if (isContinuousListeningEnabled) startListening()
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
                    Timber.d("VoiceCommand: Recognized text: $rawText, confidence: $confidence")
                    val wakeWord = _interactionState.value.wakeWord
                    // Unicode NFC 规范化匹配，解决不同设备编码差异
                    val normalizedText = normalizeText(rawText)
                    val normalizedWakeWord = normalizeText(wakeWord)
                    if (isWaitingForWakeWord && normalizedText.contains(normalizedWakeWord, ignoreCase = true)) {
                        Timber.i("VoiceCommand: Wake word detected - $rawText")
                        isWaitingForWakeWord = false
                        retryCount = 0
                        _interactionState.update { it.copy(isWakeWordDetected = true, isListening = false) }
                        scope.launch {
                            delay(800)
                            Timber.i("VoiceCommand: Starting command listening after wake word")
                            startListening()
                        }
                    } else if (!isWaitingForWakeWord) {
                        val command = VoiceCommand.fromSpokenText(rawText)
                        val result = VoiceCommandResult(command = command, confidence = confidence, rawText = rawText)
                        _interactionState.update { it.copy(lastCommand = result, isListening = false, isWakeWordDetected = false) }
                        isWaitingForWakeWord = true
                        if (result.isSuccess) {
                            Timber.i("VoiceCommand: Command recognized - ${command?.name}")
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
        // TTS 协调：如果 TTS 正在播报，等待
        if (isTtsSpeaking) {
            Timber.d("VoiceCommand: TTS speaking, waiting before startListening")
            var waitCount = 0
            while (isTtsSpeaking && waitCount < 50) { delay(100); waitCount++ }
            if (isTtsSpeaking) {
                Timber.w("VoiceCommand: TTS still speaking, aborting startListening")
                return@withContext Result.Error(message = "TTS 正在播报")
            }
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
    
    override fun release() {
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
        if (enabled) startContinuousListening() else stopContinuousListening()
    }
    
    /** TTS 开始播报时调用 */
    fun notifyTtsStart() {
        isTtsSpeaking = true
        ttsResumeJob?.cancel()
        Timber.d("VoiceCommand: TTS started, pausing recognition")
        if (_interactionState.value.isListening) {
            scope.launch { stopListening() }
        }
    }
    
    /** TTS 停止播报时调用 */
    fun notifyTtsStop() {
        isTtsSpeaking = false
        Timber.d("VoiceCommand: TTS stopped, will resume recognition")
        ttsResumeJob = scope.launch {
            delay(600)
            // 只在持续监听模式且未检测到唤醒词时恢复识别
            // 避免与 recognizeOnce() 中的 startListening() 冲突
            if (isContinuousListeningEnabled && !isTtsSpeaking && !_interactionState.value.isWakeWordDetected) {
                Timber.i("VoiceCommand: Resuming recognition after TTS")
                startListening()
            }
        }
    }
    
    private fun startContinuousListening() {
        if (isContinuousListeningEnabled) return
        isContinuousListeningEnabled = true
        isWaitingForWakeWord = true
        retryCount = 0
        Timber.i("VoiceCommand: Continuous listening started")
        listeningJob = scope.launch {
            delay(500)
            startListening()
        }
        healthCheckJob?.cancel()
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
        healthCheckJob?.cancel()
        healthCheckJob = null
        Timber.i("VoiceCommand: Continuous listening stopped")
    }

    /**
     * 外部触发唤醒词检测
     * 
     * 由 WakeWordService 调用，当百度唤醒引擎检测到唤醒词时触发
     * 会自动切换到指令识别模式
     */
    override fun triggerWakeWordDetected(wakeWord: String) {
        Timber.i("VoiceCommand: Wake word triggered externally - $wakeWord")
        
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
}
