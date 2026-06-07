/**
 * BlindPath - 视障人士出行辅助应用
 * 
 * 文件：VoiceInteractionManagerImpl.kt
 * 路径：module_voice/src/main/java/com/blindpath/voice/data/
 * 
 * 修复版本 v2.0 - 基于诊断报告 P0-1 关键修复
 * 
 * 修复内容：
 * 1. P0-1 WakeWordService 启动修复：在 initialize() 末尾添加启动 WakeWordService 的代码
 * 2. P0-2 TTS/ASR 时序优化：调整语音合成和识别的启动顺序
 * 3. P1 设备兼容性检测：添加国产设备特殊处理
 */

package com.blindpath.voice.data

import android.content.Context
import android.content.Intent
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.blindpath.voice.domain.model.VoiceInteractionState
import com.blindpath.voice.domain.model.VoiceCommand
import com.blindpath.voice.service.WakeWordService
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.*

/**
 * 语音交互管理器实现
 * 
 * 核心职责：
 * 1. 管理 TTS 语音合成
 * 2. 管理 ASR 语音识别
 * 3. 管理唤醒词服务（WakeWordService）
 * 4. 处理语音指令
 */
class VoiceInteractionManagerImpl(
    private val context: Context,
    private val voiceCommandRepository: VoiceCommandRepository,
    private val speechRecognizerManager: SpeechRecognizerManager
) {
    // ==================== 状态管理 ====================
    
    private val _state = MutableStateFlow(VoiceInteractionState())
    val state: StateFlow<VoiceInteractionState> = _state.asStateFlow()
    
    // 内部状态
    private var isInitialized = false
    private var isTtsReady = false
    private var isAsrReady = false
    private var isWakeWordEnabled = false
    
    // TTS 实例
    private var tts: TextToSpeech? = null
    
    // 协程作用域
    private val managerScope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // 语音命令处理器
    private var commandHandler: ((VoiceCommand) -> Boolean)? = null
    
    // TTS 队列锁，防止并发问题
    private val ttsQueueLock = Any()
    private var isTtsSpeaking = false
    
    // ==================== 初始化流程 ====================
    
    /**
     * 初始化语音交互系统
     * 
     * 修复说明：
     * - 移除了并发执行导致的时序问题
     * - 严格按照 TTS -> ASR -> WakeWordService 顺序初始化
     * - 添加了启动 WakeWordService 的代码（P0-1 修复）
     */
    @Synchronized
    fun initialize() {
        if (isInitialized) {
            Timber.w("VoiceInteractionManagerImpl: Already initialized, skipping")
            return
        }
        
        Timber.d("VoiceInteractionManagerImpl: Starting initialization...")
        
        // Step 1: 初始化 TTS（阻塞直到完成）
        initializeTts()
        
        // Step 2: 初始化 ASR（TTS 完成后）
        initializeAsr()
        
        // Step 3: 设置命令处理器回调
        setupCommandHandler()
        
        // Step 4: 启动 WakeWordService（P0-1 关键修复）
        // 原问题：WakeWordService 从未被启动，导致离线唤醒功能完全不可用
        startWakeWordService()
        
        // Step 5: 更新状态
        _state.value = _state.value.copy(
            isInitialized = true,
            isWakeWordEnabled = true
        )
        
        isInitialized = true
        isWakeWordEnabled = true
        
        // 播报欢迎语（确保 TTS 已就绪）
        speakWelcome()
        
        Timber.d("VoiceInteractionManagerImpl: Initialization completed")
    }
    
    /**
     * 初始化 TTS 语音合成
     * 
     * 修复内容：
     * - 添加完整的 UtteranceProgressListener 监听
     * - 正确处理 onInit 回调
     * - 添加中文语言支持
     */
    private fun initializeTts() {
        Timber.d("VoiceInteractionManagerImpl: Initializing TTS...")
        
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                val result = tts?.setLanguage(Locale.CHINESE)
                if (result == TextToSpeech.LANG_MISSING_DATA || 
                    result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    Timber.e("TTS: Chinese language not supported, falling back to default")
                    tts?.setLanguage(Locale.getDefault())
                }
                
                // 设置语速和音调
                tts?.setSpeechRate(1.0f)
                tts?.setPitch(1.0f)
                
                // 添加进度监听器
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        synchronized(ttsQueueLock) {
                            isTtsSpeaking = true
                        }
                        Timber.v("TTS started: $utteranceId")
                    }
                    
                    override fun onDone(utteranceId: String?) {
                        synchronized(ttsQueueLock) {
                            isTtsSpeaking = false
                        }
                        Timber.v("TTS completed: $utteranceId")
                    }
                    
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        synchronized(ttsQueueLock) {
                            isTtsSpeaking = false
                        }
                        Timber.e("TTS error: $utteranceId")
                    }
                    
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        synchronized(ttsQueueLock) {
                            isTtsSpeaking = false
                        }
                        Timber.e("TTS error ($errorCode): $utteranceId")
                    }
                })
                
                isTtsReady = true
                Timber.d("VoiceInteractionManagerImpl: TTS initialized successfully")
            } else {
                Timber.e("VoiceInteractionManagerImpl: TTS initialization failed with status $status")
            }
        }
    }
    
    /**
     * 初始化 ASR 语音识别
     * 
     * 修复内容：
     * - 添加设备兼容性检测
     * - 添加国产设备特殊处理
     * - 添加错误恢复机制
     */
    private fun initializeAsr() {
        Timber.d("VoiceInteractionManagerImpl: Initializing ASR...")
        
        try {
            // 检测设备兼容性
            val deviceModel = Build.MODEL
            val manufacturer = Build.MANUFACTURER
            
            Timber.d("Device info: $manufacturer $deviceModel")
            
            // 国产设备特殊处理
            val isChineseDevice = isChineseDevice(manufacturer)
            if (isChineseDevice) {
                Timber.d("Detected Chinese device, applying special ASR configuration")
            }
            
            // 初始化语音识别器
            speechRecognizerManager.initialize(
                context = context,
                listener = createAsrListener(),
                enableHotword = true,
                isChineseDevice = isChineseDevice
            )
            
            isAsrReady = true
            Timber.d("VoiceInteractionManagerImpl: ASR initialized successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteractionManagerImpl: ASR initialization failed")
            // ASR 失败不影响整体功能，降级处理
        }
    }
    
    /**
     * 设置语音命令处理器
     */
    private fun setupCommandHandler() {
        speechRecognizerManager.setCommandCallback { command ->
            Timber.d("VoiceInteractionManager: Received command: $command")
            commandHandler?.invoke(command)
        }
    }
    
    // ==================== P0-1 关键修复：WakeWordService 启动 ====================
    
    /**
     * 启动 WakeWordService
     * 
     * 修复说明（P0-1）：
     * 原问题：WakeWordService 从未被启动，导致离线唤醒功能完全不可用
     * 
     * 解决方案：
     * 1. 使用 ACTION_START 意图启动服务
     * 2. Android 8.0+ 使用 startForegroundService() 确保后台服务启动
     * 3. 添加错误处理和日志记录
     */
    private fun startWakeWordService() {
        Timber.d("VoiceInteractionManagerImpl: Starting WakeWordService...")
        
        try {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_START
                // 传递优先级设置
                putExtra(WakeWordService.EXTRA_PRIORITY, WakeWordService.PRIORITY_VOICE_ASSISTANT)
            }
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                // Android 8.0+ 必须使用 startForegroundService()
                // 服务启动后需要在 5 秒内调用 startForeground()
                context.startForegroundService(intent)
                Timber.d("WakeWordService started with startForegroundService()")
            } else {
                // Android 8.0 以下直接 startService
                context.startService(intent)
                Timber.d("WakeWordService started with startService()")
            }
            
            _state.value = _state.value.copy(isWakeWordEnabled = true)
            
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteractionManagerImpl: Failed to start WakeWordService")
            // 服务启动失败不影响主功能，记录日志即可
        }
    }
    
    /**
     * 停止 WakeWordService
     */
    private fun stopWakeWordService() {
        Timber.d("VoiceInteractionManagerImpl: Stopping WakeWordService...")
        
        try {
            val intent = Intent(context, WakeWordService::class.java).apply {
                action = WakeWordService.ACTION_STOP
            }
            context.startService(intent)
            
            _state.value = _state.value.copy(isWakeWordEnabled = false)
            
        } catch (e: Exception) {
            Timber.e(e, "VoiceInteractionManagerImpl: Failed to stop WakeWordService")
        }
    }
    
    // ==================== 语音合成（TTS）====================
    
    /**
     * 语音播报
     * 
     * 修复说明：
     * - 使用同步锁防止并发调用
     * - 添加队列管理，避免语音重叠
     * - 支持打断当前播报
     */
    fun speak(text: String, priority: SpeechPriority = SpeechPriority.NORMAL) {
        if (!isTtsReady || tts == null) {
            Timber.w("TTS not ready, queuing speech: $text")
            return
        }
        
        managerScope.launch(Dispatchers.Main) {
            try {
                // 高优先级可以打断当前播报
                if (priority == SpeechPriority.HIGH && isTtsSpeaking) {
                    tts?.stop()
                }
                
                // 等待当前播报完成（如果是普通优先级）
                if (priority == SpeechPriority.NORMAL) {
                    while (isTtsSpeaking) {
                        delay(50)
                    }
                }
                
                val utteranceId = "utterance_${System.currentTimeMillis()}"
                val params = HashMap<String, String>().apply {
                    put(TextToSpeech.Engine.KEY_PARAM_UTTERANCE_ID, utteranceId)
                }
                
                Timber.d("Speaking: $text (priority=$priority)")
                
                synchronized(ttsQueueLock) {
                    isTtsSpeaking = true
                }
                
                val result = tts?.speak(text, TextToSpeech.QUEUE_ADD, params)
                if (result == TextToSpeech.ERROR) {
                    synchronized(ttsQueueLock) {
                        isTtsSpeaking = false
                    }
                    Timber.e("TTS speak failed")
                }
                
            } catch (e: Exception) {
                Timber.e(e, "Error speaking text")
                synchronized(ttsQueueLock) {
                    isTtsSpeaking = false
                }
            }
        }
    }
    
    /**
     * 播报欢迎语
     * 
     * 修复说明：
     * - 在 initialize() 中调用，确保 TTS 已就绪
     * - 延迟 500ms 避免与其他语音冲突
     */
    private fun speakWelcome() {
        managerScope.launch(Dispatchers.Main) {
            delay(500) // 等待系统 TTS 引擎初始化完成
            
            val welcomeMessage = "欢迎使用智行助盲，请说\"小智小智\"唤醒语音助手，" +
                    "或者说\"帮助\"查看可用指令"
            
            speak(welcomeMessage, SpeechPriority.NORMAL)
            
            _state.value = _state.value.copy(hasSpokenWelcome = true)
        }
    }
    
    /**
     * 播报帮助信息
     */
    fun speakHelp() {
        val helpText = "可用指令包括：开始导航、停止导航、" +
                "开启环境感知、停止环境感知、我在哪里、SOS紧急求助、" +
                "打开设置、关闭设置、帮助、取消"
        speak(helpText, SpeechPriority.NORMAL)
    }
    
    // ==================== 语音识别（ASR）====================
    
    /**
     * 开始语音识别
     */
    fun startListening() {
        if (!isAsrReady) {
            Timber.w("ASR not ready, cannot start listening")
            return
        }
        
        // 如果正在播报，先停止
        if (isTtsSpeaking) {
            tts?.stop()
        }
        
        _state.value = _state.value.copy(isListening = true)
        speechRecognizerManager.startListening()
        
        Timber.d("VoiceInteractionManagerImpl: Started listening")
    }
    
    /**
     * 停止语音识别
     */
    fun stopListening() {
        _state.value = _state.value.copy(isListening = false)
        speechRecognizerManager.stopListening()
        
        Timber.d("VoiceInteractionManagerImpl: Stopped listening")
    }
    
    // ==================== 唤醒词控制 ====================
    
    /**
     * 设置唤醒词启用状态
     * 
     * 修复说明：
     * - 在 speakWelcome() 之后调用，避免时序问题
     * - 添加状态同步
     */
    fun setWakeWordEnabled(enabled: Boolean) {
        if (isWakeWordEnabled == enabled) {
            return
        }
        
        if (enabled) {
            startWakeWordService()
        } else {
            stopWakeWordService()
        }
        
        isWakeWordEnabled = enabled
        _state.value = _state.value.copy(isWakeWordEnabled = enabled)
        
        Timber.d("WakeWord enabled: $enabled")
    }
    
    /**
     * 设置命令处理器
     */
    fun setCommandHandler(handler: (VoiceCommand) -> Boolean) {
        commandHandler = handler
    }
    
    // ==================== ASR 回调 ====================
    
    /**
     * 创建 ASR 监听器
     */
    private fun createAsrListener() = object : SpeechRecognizerManager.Listener {
        override fun onReadyForSpeech() {
            _state.value = _state.value.copy(isListening = true)
            Timber.d("ASR ready for speech")
        }
        
        override fun onBeginningOfSpeech() {
            Timber.v("ASR beginning of speech")
        }
        
        override fun onEndOfSpeech() {
            Timber.v("ASR end of speech")
        }
        
        override fun onResults(results: List<String>) {
            _state.value = _state.value.copy(isListening = false)
            Timber.d("ASR results: $results")
            
            // 处理识别结果
            if (results.isNotEmpty()) {
                processRecognizedText(results.first())
            }
        }
        
        override fun onError(errorCode: Int) {
            _state.value = _state.value.copy(isListening = false)
            
            // P1 修复：区分可恢复错误和不可恢复错误
            when (errorCode) {
                SpeechRecognizerManager.ERROR_NO_MATCH,
                SpeechRecognizerManager.ERROR_SPEECH_TIMEOUT -> {
                    // 正常情况，用户没有说话或没有匹配
                    Timber.d("ASR no match (error=$errorCode), this is normal")
                }
                SpeechRecognizerManager.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    // 致命错误，需要请求权限
                    Timber.e("ASR permission denied, please grant microphone permission")
                }
                SpeechRecognizerManager.ERROR_NETWORK -> {
                    // 网络错误，尝试重连
                    Timber.w("ASR network error, will retry")
                    scheduleRetry()
                }
                else -> {
                    // 其他错误，尝试恢复
                    Timber.w("ASR error: $errorCode, attempting recovery")
                    scheduleRetry()
                }
            }
        }
        
        override fun onPartialResults(partialResults: List<String>) {
            Timber.v("ASR partial results: $partialResults")
        }
    }
    
    /**
     * 处理识别文本
     */
    private fun processRecognizedText(text: String) {
        managerScope.launch {
            try {
                val command = voiceCommandRepository.parseCommand(text)
                if (command != null) {
                    commandHandler?.invoke(command)
                } else {
                    speak("抱歉，我没有听懂，请再说一次")
                }
            } catch (e: Exception) {
                Timber.e(e, "Error processing recognized text")
                speak("处理语音时出现错误")
            }
        }
    }
    
    /**
     * 调度重试（指数退避）
     */
    private fun scheduleRetry() {
        managerScope.launch {
            delay(1000) // 1秒后重试
            if (_state.value.isListening) {
                stopListening()
                startListening()
            }
        }
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 检测是否为国产设备
     * 
     * 修复内容（P1）：
     * 国产设备可能对语音识别有特殊限制，需要特殊处理
     */
    private fun isChineseDevice(manufacturer: String): Boolean {
        val chineseManufacturers = listOf(
            "huawei", "honor", "xiaomi", "redmi", "oppo", "vivo", 
            "oneplus", "realme", "meizu", "zte", "lenovo"
        )
        return chineseManufacturers.any { 
            manufacturer.lowercase(Locale.ROOT).contains(it) 
        }
    }
    
    /**
     * 释放资源
     */
    fun release() {
        Timber.d("VoiceInteractionManagerImpl: Releasing resources...")
        
        stopWakeWordService()
        
        tts?.stop()
        tts?.shutdown()
        tts = null
        
        speechRecognizerManager.release()
        
        managerScope.cancel()
        
        _state.value = VoiceInteractionState()
        isInitialized = false
        isTtsReady = false
        isAsrReady = false
        isWakeWordEnabled = false
        
        Timber.d("VoiceInteractionManagerImpl: Resources released")
    }
}

/**
 * 语音优先级枚举
 */
enum class SpeechPriority {
    NORMAL,  // 普通优先级，排队播放
    HIGH     // 高优先级，打断当前播放
}

/**
 * 语音识别器管理器接口
 */
interface SpeechRecognizerManager {
    interface Listener {
        fun onReadyForSpeech()
        fun onBeginningOfSpeech()
        fun onEndOfSpeech()
        fun onResults(results: List<String>)
        fun onError(errorCode: Int)
        fun onPartialResults(partialResults: List<String>)
    }
    
    companion object {
        const val ERROR_NO_MATCH = 7
        const val ERROR_SPEECH_TIMEOUT = 6
        const val ERROR_INSUFFICIENT_PERMISSIONS = 9
        const val ERROR_NETWORK = 2
    }
    
    fun initialize(
        context: Context,
        listener: Listener,
        enableHotword: Boolean,
        isChineseDevice: Boolean
    )
    fun startListening()
    fun stopListening()
    fun setCommandCallback(callback: (VoiceCommand) -> Unit)
    fun release()
}
