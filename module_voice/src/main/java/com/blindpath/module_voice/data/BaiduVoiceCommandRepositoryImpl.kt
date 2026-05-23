package com.blindpath.module_voice.data

import android.content.Context
import com.baidu.speech.EventListener
import com.baidu.speech.EventManager
import com.baidu.speech.EventManagerFactory
import com.baidu.speech.asr.SpeechConstant
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceCommandRepository
import com.blindpath.module_voice.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 百度语音识别实现
 * 
 * 使用百度语音 API 进行高精度语音识别
 * 支持离线唤醒词和在线语音识别
 */
@Singleton
class BaiduVoiceCommandRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceCommandRepository {
    
    private val _interactionState = MutableStateFlow(VoiceInteractionState())
    override val interactionState: StateFlow<VoiceInteractionState> = _interactionState.asStateFlow()
    
    private var asrEventManager: EventManager? = null
    private var wakeWordEventManager: EventManager? = null
    private var isInitialized = false
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 百度语音 API 配置
    companion object {
        const val APP_ID = "7bqc6ovRERcTumcd4h2dXhyj"
        const val APP_KEY = "7bqc6ovRERcTumcd4h2dXhyj"
        const val SECRET_KEY = "7bqc6ovRERcTumcd4h2dXhyj"
    }
    
    // 语音识别监听器
    private val asrEventListener = object : EventListener {
        override fun onEvent(name: String?, params: String?, data: ByteArray?, offset: Int, length: Int) {
            when (name) {
                SpeechConstant.CALLBACK_EVENT_ASR_READY -> {
                    Timber.d("BaiduVoice: Ready for speech")
                    _interactionState.update { it.copy(isListening = true) }
                }
                
                SpeechConstant.CALLBACK_EVENT_ASR_BEGIN -> {
                    Timber.d("BaiduVoice: Beginning of speech")
                }
                
                SpeechConstant.CALLBACK_EVENT_ASR_END -> {
                    Timber.d("BaiduVoice: End of speech")
                    _interactionState.update { it.copy(isListening = false) }
                }
                
                SpeechConstant.CALLBACK_EVENT_ASR_PARTIAL -> {
                    // 部分识别结果
                    params?.let { parsePartialResult(it) }
                }
                
                SpeechConstant.CALLBACK_EVENT_ASR_RESULT -> {
                    // 最终识别结果
                    params?.let { parseFinalResult(it) }
                }
                
                SpeechConstant.CALLBACK_EVENT_ASR_ERROR -> {
                    // 识别错误
                    params?.let { parseError(it) }
                }
                
                SpeechConstant.CALLBACK_EVENT_ASR_FINISH -> {
                    Timber.d("BaiduVoice: Recognition finished")
                    _interactionState.update { it.copy(isListening = false) }
                }
            }
        }
    }
    
    // 唤醒词监听器
    private val wakeWordEventListener = object : EventListener {
        override fun onEvent(name: String?, params: String?, data: ByteArray?, offset: Int, length: Int) {
            when (name) {
                "wp.data" -> {
                    // 唤醒词检测成功
                    Timber.i("BaiduVoice: Wake word detected")
                    _interactionState.update { 
                        it.copy(
                            isWakeWordDetected = true,
                            isListening = true
                        )
                    }
                    
                    // 自动开始语音识别
                    scope.launch {
                        startListening()
                    }
                }
                
                "wp.error" -> {
                    Timber.e("BaiduVoice: Wake word error - $params")
                }
            }
        }
    }
    
    override suspend fun initialize(): Result<Boolean> = withContext(Dispatchers.Main) {
        if (isInitialized) {
            return@withContext Result.Success(true)
        }
        
        try {
            // 初始化语音识别器
            asrEventManager = EventManagerFactory.create(context, "asr")
            asrEventManager?.registerListener(asrEventListener)
            
            // 初始化唤醒词检测器
            wakeWordEventManager = EventManagerFactory.create(context, "wp")
            wakeWordEventManager?.registerListener(wakeWordEventListener)
            
            isInitialized = true
            Timber.i("BaiduVoice: Initialized successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "BaiduVoice: Initialization failed")
            Result.Error("百度语音初始化失败：${e.message}")
        }
    }
    
    override suspend fun startListening(): Result<Boolean> = withContext(Dispatchers.Main) {
        if (!isInitialized || asrEventManager == null) {
            return@withContext Result.Error("语音识别未初始化")
        }
        
        try {
            val params = mapOf(
                SpeechConstant.ACCEPT_AUDIO_VOLUME to true,
                SpeechConstant.PID to 1537,  // 中文普通话模型
                SpeechConstant.DECODER to 0,  // 在线识别
                SpeechConstant.VAD to SpeechConstant.VAD_DNN,
                SpeechConstant.VAD_ENDPOINT_TIMEOUT to 1000,  // 静音超时 1 秒
                SpeechConstant.PROP to 2000  // 语音识别
            )
            
            val json = JSONObject(params).toString()
            asrEventManager?.send(SpeechConstant.ASR_START, json, null, 0, 0)
            
            Timber.d("BaiduVoice: Started listening")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "BaiduVoice: Failed to start listening")
            Result.Error("启动语音识别失败：${e.message}")
        }
    }
    
    override suspend fun stopListening(): Result<Boolean> = withContext(Dispatchers.Main) {
        try {
            asrEventManager?.send(SpeechConstant.ASR_STOP, null, null, 0, 0)
            _interactionState.update { it.copy(isListening = false) }
            Timber.d("BaiduVoice: Stopped listening")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "BaiduVoice: Failed to stop listening")
            Result.Error("停止语音识别失败：${e.message}")
        }
    }
    
    override suspend fun recognizeOnce(): Result<VoiceCommandResult> {
        val startResult = startListening()
        if (startResult is Result.Error) {
            return Result.Error(startResult.message ?: "启动语音识别失败")
        }
        
        // 等待识别结果（最多 10 秒）
        return withTimeoutOrNull(10_000) {
            interactionState
                .filter { it.lastCommand != null }
                .map { it.lastCommand!! }
                .first()
        }?.let { Result.Success(it) }
            ?: Result.Error("语音识别超时")
    }
    
    override fun release() {
        asrEventManager?.send(SpeechConstant.ASR_CANCEL, null, null, 0, 0)
        asrEventManager = null
        
        wakeWordEventManager?.send("wp.stop", null, null, 0, 0)
        wakeWordEventManager = null
        
        isInitialized = false
        scope.cancel()
        Timber.d("BaiduVoice: Released")
    }
    
    override fun setWakeWord(word: String) {
        _interactionState.update { it.copy(wakeWord = word) }
        
        // 配置唤醒词
        val params = mapOf(
            "word" to word,
            "threshold" to "0.1"  // 唤醒词阈值
        )
        val json = JSONObject(params).toString()
        wakeWordEventManager?.send("wp.data", json, null, 0, 0)
        
        Timber.d("BaiduVoice: Wake word set to '$word'")
    }
    
    override fun setWakeWordEnabled(enabled: Boolean) {
        _interactionState.update { it.copy(isWakeWordEnabled = enabled) }
        
        if (enabled) {
            wakeWordEventManager?.send("wp.start", null, null, 0, 0)
        } else {
            wakeWordEventManager?.send("wp.stop", null, null, 0, 0)
        }
        
        Timber.d("BaiduVoice: Wake word enabled = $enabled")
    }
    
    /**
     * 解析部分识别结果
     */
    private fun parsePartialResult(json: String) {
        try {
            val result = JSONObject(json)
            val results = result.optJSONArray("results_recognition")
            if (results != null && results.length() > 0) {
                val text = results.getString(0)
                Timber.d("BaiduVoice: Partial result - $text")
            }
        } catch (e: Exception) {
            Timber.e(e, "BaiduVoice: Failed to parse partial result")
        }
    }
    
    /**
     * 解析最终识别结果
     */
    private fun parseFinalResult(json: String) {
        try {
            val result = JSONObject(json)
            val results = result.optJSONArray("results_recognition")
            
            if (results != null && results.length() > 0) {
                val rawText = results.getString(0)
                val confidence = result.optDouble("confidence", 0.8).toFloat()
                
                Timber.d("BaiduVoice: Recognized text - $rawText, confidence - $confidence")
                
                // 解析指令
                val command = VoiceCommand.fromSpokenText(rawText)
                val commandResult = VoiceCommandResult(
                    command = command,
                    confidence = confidence,
                    rawText = rawText
                )
                
                _interactionState.update { 
                    it.copy(
                        lastCommand = commandResult,
                        isListening = false,
                        isWakeWordDetected = false
                    )
                }
                
                if (commandResult.isSuccess) {
                    Timber.i("BaiduVoice: Command recognized - ${command?.name}")
                } else {
                    Timber.w("BaiduVoice: Command not recognized - ${commandResult.failureReason}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "BaiduVoice: Failed to parse final result")
        }
    }
    
    /**
     * 解析错误信息
     */
    private fun parseError(json: String) {
        try {
            val error = JSONObject(json)
            val errorCode = error.optInt("error", -1)
            val errorMessage = error.optString("desc", "未知错误")
            
            Timber.e("BaiduVoice: Recognition error - $errorCode: $errorMessage")
            
            _interactionState.update { 
                it.copy(
                    isListening = false,
                    lastError = "识别错误：$errorMessage"
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "BaiduVoice: Failed to parse error")
        }
    }
}
