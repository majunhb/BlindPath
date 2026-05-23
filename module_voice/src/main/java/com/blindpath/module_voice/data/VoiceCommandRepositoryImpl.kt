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
 */
@Singleton
class VoiceCommandRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceCommandRepository {
    
    private val _interactionState = MutableStateFlow(VoiceInteractionState())
    override val interactionState: StateFlow<VoiceInteractionState> = _interactionState.asStateFlow()
    
    private var speechRecognizer: SpeechRecognizer? = null
    private var isInitialized = false
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 语音识别监听器
    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Timber.d("VoiceCommand: Ready for speech")
            _interactionState.update { it.copy(isListening = true) }
        }
        
        override fun onBeginningOfSpeech() {
            Timber.d("VoiceCommand: Beginning of speech")
        }
        
        override fun onRmsChanged(rmsdB: Float) {
            // 音量变化，可用于 UI 反馈
        }
        
        override fun onBufferReceived(buffer: ByteArray?) {
            // 接收到音频数据
        }
        
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
            Timber.e("VoiceCommand: Recognition error - $errorMessage")
            _interactionState.update { 
                it.copy(
                    isListening = false,
                    lastError = errorMessage
                )
            }
        }
        
        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val confidences = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
            
            if (!matches.isNullOrEmpty()) {
                val rawText = matches[0]
                val confidence = confidences?.getOrNull(0) ?: 0.5f
                
                Timber.d("VoiceCommand: Recognized text: $rawText, confidence: $confidence")
                
                // 解析指令
                val command = VoiceCommand.fromSpokenText(rawText)
                val result = VoiceCommandResult(
                    command = command,
                    confidence = confidence,
                    rawText = rawText
                )
                
                _interactionState.update { 
                    it.copy(
                        lastCommand = result,
                        isListening = false
                    )
                }
                
                if (result.isSuccess) {
                    Timber.i("VoiceCommand: Command recognized - ${command?.name}")
                } else {
                    Timber.w("VoiceCommand: Command not recognized - ${result.failureReason}")
                }
            } else {
                Timber.w("VoiceCommand: No speech recognized")
                _interactionState.update { 
                    it.copy(
                        isListening = false,
                        lastError = "未识别到语音"
                    )
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
        speechRecognizer?.destroy()
        speechRecognizer = null
        isInitialized = false
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
    }
}
