package com.blindpath.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.blindpath.audio.AudioRecorder
import com.blindpath.porcupine.PorcupineConfig
import com.blindpath.porcupine.PorcupineWakeWordEngine
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/**
 * 语音交互管理器
 * 
 * 整合 Porcupine 唤醒 + SpeechRecognizer 指令识别的完整语音交互方案：
 * 
 * 1. 唤醒阶段（持续）：
 *    - AudioRecorder 采集音频
 *    - PorcupineEngine 本地检测唤醒词
 *    - 检测到唤醒词 → 触发 onWakeWordDetected → 启动指令识别
 * 
 * 2. 指令阶段（单次）：
 *    - SpeechRecognizer 进行语音识别
 *    - 识别结果 → 触发 onCommandRecognized
 *    - 超时/错误 → 回到唤醒阶段
 * 
 * 特点：
 * - 唤醒完全离线（Porcupine），华为设备兼容
 * - 指令识别使用云端（准确率高），仅在唤醒后使用
 * - 自动状态管理，无需手动切换
 * 
 * @param context 应用上下文
 * @param porcupineConfig Porcupine 配置
 */
class VoiceInteractionManager(
    private val context: Context,
    private val porcupineConfig: PorcupineConfig
) {
    // 状态定义
    enum class State {
        IDLE,           // 空闲
        LISTENING,      // 监听唤醒词中
        WAKEUP_DETECTED,// 唤醒词已检测
        RECOGNIZING,    // 识别指令中
        ERROR           // 错误状态
    }

    // 协程作用域
    private val managerScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // 组件
    private var audioRecorder: AudioRecorder? = null
    private var porcupineEngine: PorcupineWakeWordEngine? = null
    private var speechRecognizer: SpeechRecognizer? = null
    
    // 状态
    private var currentState = State.IDLE
    private var isInitialized = false
    
    // 事件流
    private val _stateFlow = MutableSharedFlow<State>(extraBufferCapacity = 1)
    val stateFlow: SharedFlow<State> = _stateFlow.asSharedFlow()
    
    private val _wakeWordFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val wakeWordFlow: SharedFlow<String> = _wakeWordFlow.asSharedFlow()
    
    private val _commandFlow = MutableSharedFlow<RecognitionResult>(extraBufferCapacity = 1)
    val commandFlow: SharedFlow<RecognitionResult> = _commandFlow.asSharedFlow()
    
    private val _errorFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorFlow: SharedFlow<String> = _errorFlow.asSharedFlow()

    // 回调（替代 Flow 的简化方式）
    var onWakeWordDetected: (() -> Unit)? = null
    var onCommandRecognized: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStateChanged: ((State) -> Unit)? = null

    /**
     * 初始化语音交互系统
     * 
     * @return true 初始化成功
     */
    fun initialize(): Boolean {
        if (isInitialized) return true

        return try {
            // 1. 初始化音频采集器
            audioRecorder = AudioRecorder(context)
            
            // 2. 初始化 Porcupine 唤醒引擎
            val keywordPath = PorcupineConfig.extractAsset(context, porcupineConfig.keywordAssetPath)
            val modelPath = porcupineConfig.modelAssetPath?.let {
                PorcupineConfig.extractAsset(context, it)
            }
            
            porcupineEngine = PorcupineWakeWordEngine(
                context = context,
                accessKey = porcupineConfig.accessKey,
                keywordPath = keywordPath,
                modelPath = modelPath
            ).apply {
                onWakeWordDetected = { index ->
                    handleWakeWordDetected(index)
                }
                onError = { error ->
                    handleError("Porcupine错误: ${error.message}")
                }
            }
            
            if (!porcupineEngine!!.initialize()) {
                throw IllegalStateException("Porcupine 初始化失败")
            }

            // 3. 初始化语音识别器（用于指令识别）
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                setupRecognitionListener()
            } else {
                Timber.w("VoiceInteractionManager: 设备不支持 SpeechRecognizer")
            }

            isInitialized = true
            Timber.i("VoiceInteractionManager: 初始化成功")
            true
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteractionManager: 初始化失败")
            handleError("初始化失败: ${e.message}")
            false
        }
    }

    /**
     * 开始语音交互（进入唤醒监听状态）
     * 
     * @return true 启动成功
     */
    fun start(): Boolean {
        if (!isInitialized) {
            Timber.e("VoiceInteractionManager: 未初始化")
            return false
        }

        if (currentState == State.LISTENING) {
            return true
        }

        // 启动音频采集
        if (audioRecorder?.start() != true) {
            handleError("无法启动录音")
            return false
        }

        // 开始监听 PCM 数据流
        managerScope.launch {
            audioRecorder?.pcmFlow?.collect { pcmData ->
                if (currentState == State.LISTENING) {
                    porcupineEngine?.process(pcmData)
                }
            }
        }

        updateState(State.LISTENING)
        Timber.i("VoiceInteractionManager: 开始监听唤醒词")
        return true
    }

    /**
     * 停止语音交互
     */
    fun stop() {
        audioRecorder?.stop()
        speechRecognizer?.stopListening()
        updateState(State.IDLE)
        Timber.i("VoiceInteractionManager: 已停止")
    }

    /**
     * 释放资源
     */
    fun release() {
        stop()
        managerScope.cancel()
        
        audioRecorder?.release()
        porcupineEngine?.release()
        speechRecognizer?.destroy()
        
        audioRecorder = null
        porcupineEngine = null
        speechRecognizer = null
        isInitialized = false
        
        Timber.i("VoiceInteractionManager: 已释放")
    }

    /**
     * 处理唤醒词检测
     */
    private fun handleWakeWordDetected(keywordIndex: Int) {
        if (currentState != State.LISTENING) return

        updateState(State.WAKEUP_DETECTED)
        
        // 触发回调
        onWakeWordDetected?.invoke()
        _wakeWordFlow.tryEmit("Porcupine-$keywordIndex")
        
        Timber.i("VoiceInteractionManager: 唤醒词检测成功，开始识别指令")
        
        // 延迟一点再启动语音识别，避免唤醒词被识别为指令
        managerScope.launch {
            delay(300)
            startCommandRecognition()
        }
    }

    /**
     * 启动指令识别（使用 SpeechRecognizer）
     */
    private fun startCommandRecognition() {
        if (speechRecognizer == null) {
            handleError("语音识别器不可用")
            // 回到监听状态
            updateState(State.LISTENING)
            return
        }

        updateState(State.RECOGNIZING)

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }

        try {
            speechRecognizer?.startListening(intent)
            Timber.d("VoiceInteractionManager: 开始指令识别")
        } catch (e: Exception) {
            handleError("启动语音识别失败: ${e.message}")
            updateState(State.LISTENING)
        }
    }

    /**
     * 设置语音识别监听器
     */
    private fun setupRecognitionListener() {
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Timber.d("SpeechRecognizer: 准备就绪")
            }

            override fun onBeginningOfSpeech() {
                Timber.d("SpeechRecognizer: 开始说话")
            }

            override fun onRmsChanged(rmsdB: Float) {
                // 音量变化，可用于 UI 反馈
            }

            override fun onBufferReceived(buffer: ByteArray?) {
                // 音频缓冲区
            }

            override fun onEndOfSpeech() {
                Timber.d("SpeechRecognizer: 结束说话")
            }

            override fun onError(error: Int) {
                val errorMsg = when (error) {
                    SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                    SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                    SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                    SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙"
                    SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "说话超时"
                    else -> "未知错误: $error"
                }
                
                Timber.w("SpeechRecognizer: 错误 - $errorMsg")
                
                // 错误后回到监听状态
                if (error != SpeechRecognizer.ERROR_CLIENT) {
                    handleError(errorMsg)
                }
                updateState(State.LISTENING)
            }

            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val bestMatch = matches[0]
                    Timber.i("SpeechRecognizer: 识别结果 - $bestMatch")
                    
                    onCommandRecognized?.invoke(bestMatch)
                    _commandFlow.tryEmit(RecognitionResult.Success(bestMatch, matches))
                } else {
                    _commandFlow.tryEmit(RecognitionResult.NoMatch)
                }
                
                // 识别完成后回到监听状态
                updateState(State.LISTENING)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                Timber.d("SpeechRecognizer: 部分结果 - $partial")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {
                // 其他事件
            }
        })
    }

    /**
     * 更新状态
     */
    private fun updateState(newState: State) {
        if (currentState != newState) {
            currentState = newState
            onStateChanged?.invoke(newState)
            _stateFlow.tryEmit(newState)
            Timber.d("VoiceInteractionManager: 状态变化 -> $newState")
        }
    }

    /**
     * 处理错误
     */
    private fun handleError(message: String) {
        Timber.e("VoiceInteractionManager: $message")
        onError?.invoke(message)
        _errorFlow.tryEmit(message)
        updateState(State.ERROR)
    }

    /**
     * 获取当前状态
     */
    fun getCurrentState(): State = currentState
}

/**
 * 识别结果封装
 */
sealed class RecognitionResult {
    data class Success(
        val bestMatch: String,
        val allMatches: List<String>
    ) : RecognitionResult()
    
    object NoMatch : RecognitionResult()
    
    data class Error(val message: String) : RecognitionResult()
}
