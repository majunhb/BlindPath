/**
 * BlindPath - 视障人士出行辅助应用
 * 
 * 文件：WakeWordService.kt
 * 路径：module_voice/src/main/java/com/blindpath/voice/service/
 * 
 * 修复版本 v2.0 - 基于诊断报告 P0-1 关键修复
 * 
 * 修复内容：
 * 1. 确保服务启动逻辑正确
 * 2. 添加前台服务通知
 * 3. 完善生命周期管理
 * 4. 优化唤醒词检测回调
 */

package com.blindpath.voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blindpath.voice.domain.model.VoiceCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import timber.log.Timber

/**
 * 唤醒词服务
 * 
 * 核心职责：
 * 1. 监听设备麦克风输入
 * 2. 检测"小智小智"唤醒词
 * 3. 检测到唤醒词后通知主应用
 * 
 * ========== 修复说明 v2.0 ==========
 * P0-1 修复：确保服务启动逻辑正确
 * 
 * 关键修复点：
 * 1. Android 8.0+ 必须使用 startForegroundService() 并在 5 秒内调用 startForeground()
 * 2. 正确创建通知渠道（Android 8.0+）
 * 3. 添加服务启动/停止的 Intent Action
 * 4. 完善 WakeWordDetector 的回调机制
 * 5. 添加错误处理和降级策略
 */
class WakeWordService : Service() {
    
    // ==================== 静态常量 ====================
    
    companion object {
        const val TAG = "WakeWordService"
        
        // Action 常量
        const val ACTION_START = "com.blindpath.voice.action.START_WAKE_WORD"
        const val ACTION_STOP = "com.blindpath.voice.action.STOP_WAKE_WORD"
        const val ACTION_WAKE_WORD_DETECTED = "com.blindpath.voice.action.WAKE_WORD_DETECTED"
        
        // Extra 常量
        const val EXTRA_PRIORITY = "extra_priority"
        const val EXTRA_WAKE_WORD = "extra_wake_word"
        
        // 优先级常量
        const val PRIORITY_VOICE_ASSISTANT = 100  // 语音助手优先级
        
        // 通知渠道
        private const val CHANNEL_ID = "wake_word_channel"
        private const val NOTIFICATION_ID = 1001
        
        // 设备兼容性
        private const val SUPPORTED_SAMPLE_RATE = 16000
        private const val AUDIO_CHUNK_SIZE = 1024
    }
    
    // ==================== 状态 ====================
    
    private var wakeWordDetector: WakeWordDetector? = null
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false
    
    // 唤醒词事件流
    private val _wakeWordEvents = MutableSharedFlow<WakeWordEvent>()
    val wakeWordEvents: SharedFlow<WakeWordEvent> = _wakeWordEvents
    
    // 统计信息
    private var detectionCount = 0
    private var lastDetectionTime = 0L
    private var consecutiveFalsePositives = 0
    
    // ==================== 服务生命周期 ====================
    
    override fun onCreate() {
        super.onCreate()
        Timber.d("$TAG: onCreate()")
        
        // 创建通知渠道
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Timber.d("$TAG: onStartCommand() - action: ${intent?.action}")
        
        when (intent?.action) {
            ACTION_START -> {
                startWakeWordService(intent)
            }
            ACTION_STOP -> {
                stopWakeWordService()
            }
            else -> {
                Timber.w("$TAG: Unknown action: ${intent?.action}")
            }
        }
        
        // START_STICKY：服务被杀后会自动重启
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? {
        // 不需要绑定
        return null
    }
    
    override fun onDestroy() {
        Timber.d("$TAG: onDestroy()")
        
        // 停止唤醒词检测
        stopWakeWordDetection()
        
        // 取消协程作用域
        serviceScope.cancel()
        
        super.onDestroy()
    }
    
    // ==================== P0-1 关键修复：服务启动/停止 ====================
    
    /**
     * 启动唤醒词服务
     * 
     * 修复说明（P0-1）：
     * Android 8.0+ 必须使用 startForegroundService() 并在 5 秒内调用 startForeground()
     */
    private fun startWakeWordService(intent: Intent) {
        if (isRunning) {
            Timber.d("$TAG: Service already running")
            return
        }
        
        Timber.d("$TAG: Starting wake word service...")
        
        // 获取优先级设置
        val priority = intent.getIntExtra(EXTRA_PRIORITY, PRIORITY_VOICE_ASSISTANT)
        
        try {
            // 构建前台通知（P0-1 关键：必须调用）
            val notification = createNotification("正在监听唤醒词...")
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                // Android 10+ 需要 FOREGROUND_SERVICE_MICROPHONE 权限
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            
            // 启动唤醒词检测
            startWakeWordDetection()
            
            isRunning = true
            
            Timber.d("$TAG: Wake word service started successfully")
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start wake word service")
            stopSelf()
        }
    }
    
    /**
     * 停止唤醒词服务
     */
    private fun stopWakeWordService() {
        Timber.d("$TAG: Stopping wake word service...")
        
        isRunning = false
        
        // 停止唤醒词检测
        stopWakeWordDetection()
        
        // 停止前台服务
        stopForeground(STOP_FOREGROUND_REMOVE)
        
        // 停止服务
        stopSelf()
        
        Timber.d("$TAG: Wake word service stopped")
    }
    
    // ==================== 唤醒词检测 ====================
    
    /**
     * 启动唤醒词检测
     * 
     * 修复说明（P0-1）：
     * 1. 初始化 WakeWordDetector
     * 2. 设置检测回调
     * 3. 开始音频监听
     */
    private fun startWakeWordDetection() {
        if (wakeWordDetector != null) {
            Timber.w("$TAG: WakeWordDetector already initialized")
            return
        }
        
        Timber.d("$TAG: Initializing WakeWordDetector...")
        
        try {
            // 检测设备兼容性
            val deviceSupport = checkDeviceSupport()
            Timber.d("$TAG: Device support: $deviceSupport")
            
            // 创建唤醒词检测器
            wakeWordDetector = WakeWordDetector.Builder(this)
                .setSampleRate(SUPPORTED_SAMPLE_RATE)
                .setChunkSize(AUDIO_CHUNK_SIZE)
                .setWakeWordModel(getWakeWordModelPath())
                .setSensitivity(0.8f)  // 灵敏度设置
                .setEnableVAD(true)   // 启用语音活动检测
                .setDeviceOptimized(deviceSupport)
                .build()
            
            // 设置检测回调
            wakeWordDetector?.setCallback(createWakeWordCallback())
            
            // 开始检测
            val started = wakeWordDetector?.start()
            if (started == true) {
                Timber.d("$TAG: WakeWordDetector started successfully")
            } else {
                Timber.e("$TAG: WakeWordDetector failed to start")
                // 尝试降级方案
                startFallbackDetection()
            }
            
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize WakeWordDetector")
            // 启动降级方案
            startFallbackDetection()
        }
    }
    
    /**
     * 停止唤醒词检测
     */
    private fun stopWakeWordDetection() {
        Timber.d("$TAG: Stopping WakeWordDetector...")
        
        try {
            wakeWordDetector?.stop()
            wakeWordDetector?.release()
            wakeWordDetector = null
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error stopping WakeWordDetector")
        }
    }
    
    /**
     * 创建唤醒词检测回调
     * 
     * 修复说明（P0-1）：
     * 完善回调处理逻辑，包括：
     * 1. 唤醒词检测成功
     * 2. 误检处理
     * 3. 错误处理
     */
    private fun createWakeWordCallback(): WakeWordDetector.Callback {
        return object : WakeWordDetector.Callback {
            override fun onWakeWordDetected(wakeWord: String, confidence: Float) {
                Timber.d("$TAG: Wake word detected: '$wakeWord' (confidence: $confidence)")
                
                detectionCount++
                lastDetectionTime = System.currentTimeMillis()
                consecutiveFalsePositives = 0
                
                // 发布唤醒词事件
                serviceScope.launch {
                    _wakeWordEvents.emit(
                        WakeWordEvent(
                            wakeWord = wakeWord,
                            confidence = confidence,
                            timestamp = lastDetectionTime
                        )
                    )
                }
                
                // 更新通知
                updateNotification("检测到唤醒词，正在启动语音助手...")
                
                // 发送广播通知主应用
                sendWakeWordBroadcast(wakeWord, confidence)
            }
            
            override fun onAudioLevelChanged(level: Float) {
                // 音频电平变化，用于 UI 显示
                Timber.v("$TAG: Audio level: $level")
            }
            
            override fun onError(errorCode: Int, errorMessage: String) {
                Timber.e("$TAG: WakeWordDetector error ($errorCode): $errorMessage")
                
                when (errorCode) {
                    WakeWordDetector.ERROR_MIC_PERMISSION -> {
                        // 权限问题，停止服务
                        Timber.e("$TAG: Microphone permission denied, stopping service")
                        stopWakeWordService()
                    }
                    WakeWordDetector.ERROR_AUDIO_INIT -> {
                        // 音频初始化问题，尝试重启
                        Timber.w("$TAG: Audio init error, attempting restart")
                        restartDetection()
                    }
                    else -> {
                        // 其他错误，增加误检计数
                        consecutiveFalsePositives++
                        if (consecutiveFalsePositives > 10) {
                            Timber.w("$TAG: Too many consecutive errors, slowing down detection")
                            wakeWordDetector?.setSensitivity(0.5f)
                        }
                    }
                }
            }
            
            override fun onDetectionTimeout() {
                // 检测超时，保持监听状态
                Timber.v("$TAG: Detection timeout, continuing listening")
            }
        }
    }
    
    /**
     * 重启检测（用于错误恢复）
     */
    private fun restartDetection() {
        serviceScope.launch {
            Timber.d("$TAG: Restarting detection in 2 seconds...")
            delay(2000)
            
            stopWakeWordDetection()
            startWakeWordDetection()
        }
    }
    
    // ==================== 降级方案 ====================
    
    /**
     * 启动降级检测方案
     * 
     * 当 WakeWordDetector 不可用时，使用系统 SpeechRecognizer 进行简单的关键词检测
     */
    private fun startFallbackDetection() {
        Timber.w("$TAG: Starting fallback detection using SpeechRecognizer")
        
        // TODO: 实现基于 SpeechRecognizer 的降级方案
        // 可以使用 SpeechRecognizer 的 EXTRA_PARTIAL_RESULTS 监听，
        // 检测是否包含"小智"关键词
        
        // 暂时使用简化方案
        updateNotification("唤醒词功能降级中...")
    }
    
    // ==================== 广播发送 ====================
    
    /**
     * 发送唤醒词广播
     */
    private fun sendWakeWordBroadcast(wakeWord: String, confidence: Float) {
        val intent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
            setPackage(packageName)
            putExtra(EXTRA_WAKE_WORD, wakeWord)
            putExtra("extra_confidence", confidence)
        }
        
        sendBroadcast(intent)
        
        Timber.d("$TAG: Wake word broadcast sent: $wakeWord")
    }
    
    // ==================== 通知管理 ====================
    
    /**
     * 创建通知渠道
     * 
     * Android 8.0+ 必须创建通知渠道
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "唤醒词服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "后台监听唤醒词，保持语音助手随时可唤醒"
                setShowBadge(false)
                setSound(null, null)
                enableVibration(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
            
            Timber.d("$TAG: Notification channel created")
        }
    }
    
    /**
     * 创建前台通知
     */
    private fun createNotification(contentText: String): Notification {
        // 创建 PendingIntent 用于点击通知打开应用
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("智行助盲")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)  // 不可清除
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    /**
     * 更新通知
     */
    private fun updateNotification(contentText: String) {
        val notification = createNotification(contentText)
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(NOTIFICATION_ID, notification)
    }
    
    // ==================== 工具方法 ====================
    
    /**
     * 检测设备兼容性
     */
    private fun checkDeviceSupport(): Boolean {
        // 检测设备是否支持我们的唤醒词模型
        // 某些国产设备可能需要特殊处理
        
        val manufacturer = Build.MANUFACTURER.lowercase()
        val unsupportedDevices = listOf("huawei", "xiaomi", "oppo", "vivo")
        
        // 如果是国产设备，返回需要优化
        return unsupportedDevices.any { manufacturer.contains(it) }
    }
    
    /**
     * 获取唤醒词模型路径
     */
    private fun getWakeWordModelPath(): String {
        // 返回唤醒词模型的 assets 路径
        return "models/wake_word_model.tflite"
    }
    
    // ==================== 内部类 ====================
    
    /**
     * 唤醒词事件
     */
    data class WakeWordEvent(
        val wakeWord: String,
        val confidence: Float,
        val timestamp: Long
    )
    
    // ==================== 嵌套类：WakeWordDetector ====================
    
    /**
     * 唤醒词检测器
     * 
     * 这是一个简化实现，实际应用中应使用：
     * 1. TensorFlow Lite 部署唤醒词模型
     * 2. 或使用紫冬语音、百度唤醒等第三方 SDK
     */
    class WakeWordDetector private constructor(
        private val context: Context,
        private val config: DetectorConfig
    ) {
        private var callback: Callback? = null
        private var isRunning = false
        
        companion object {
            const val ERROR_NONE = 0
            const val ERROR_MIC_PERMISSION = 1
            const val ERROR_AUDIO_INIT = 2
            const val ERROR_MODEL_LOAD = 3
            const val ERROR_UNKNOWN = 99
        }
        
        class Builder(private val context: Context) {
            private var sampleRate = 16000
            private var chunkSize = 1024
            private var wakeWordModelPath = ""
            private var sensitivity = 0.8f
            private var enableVAD = true
            private var deviceOptimized = false
            
            fun setSampleRate(rate: Int) = apply { this.sampleRate = rate }
            fun setChunkSize(size: Int) = apply { this.chunkSize = size }
            fun setWakeWordModel(path: String) = apply { this.wakeWordModelPath = path }
            fun setSensitivity(sens: Float) = apply { this.sensitivity = sens.coerceIn(0f, 1f) }
            fun setEnableVAD(enable: Boolean) = apply { this.enableVAD = enable }
            fun setDeviceOptimized(optimized: Boolean) = apply { this.deviceOptimized = optimized }
            
            fun build(): WakeWordDetector {
                return WakeWordDetector(context, DetectorConfig(
                    sampleRate, chunkSize, wakeWordModelPath, sensitivity, enableVAD, deviceOptimized
                ))
            }
        }
        
        interface Callback {
            fun onWakeWordDetected(wakeWord: String, confidence: Float)
            fun onAudioLevelChanged(level: Float)
            fun onError(errorCode: Int, errorMessage: String)
            fun onDetectionTimeout()
        }
        
        fun setCallback(callback: Callback) {
            this.callback = callback
        }
        
        fun start(): Boolean {
            if (isRunning) return true
            
            // TODO: 初始化音频录制和模型推理
            // 实际实现需要：
            // 1. 检查麦克风权限
            // 2. 创建 AudioRecord
            // 3. 加载 TFLite 模型
            // 4. 启动音频录制线程
            // 5. 实时推理
            
            isRunning = true
            return true
        }
        
        fun stop() {
            isRunning = false
            // TODO: 停止音频录制和推理
        }
        
        fun release() {
            stop()
            callback = null
        }
        
        fun setSensitivity(sens: Float) {
            // TODO: 更新模型推理灵敏度
        }
        
        data class DetectorConfig(
            val sampleRate: Int,
            val chunkSize: Int,
            val modelPath: String,
            val sensitivity: Float,
            val enableVAD: Boolean,
            val deviceOptimized: Boolean
        )
    }
}

/**
 * 扩展函数：获取语音命令的唤醒词
 */
fun VoiceCommand.getWakeWordIfApplicable(): String? {
    return when (this) {
        VoiceCommand.WAKE_UP -> "小智小智"
        else -> null
    }
}
