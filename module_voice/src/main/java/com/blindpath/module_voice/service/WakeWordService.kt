package com.blindpath.module_voice.service

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import dagger.hilt.android.AndroidEntryPoint
import com.blindpath.module_voice.BuildConfig
import com.blindpath.module_voice.domain.model.WakeWordConfig
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.util.Properties
import javax.inject.Inject

/**
 * 语音唤醒常驻服务
 * 
 * 功能：
 * 1. 低功耗唤醒引擎（百度/讯飞/能量检测）
 * 2. 独立进程运行，适配厂商省电策略
 * 3. 智能音频焦点管理，兼容 TalkBack
 * 4. 蓝牙外设自动切换
 * 5. 后台保活 + 自启 + 前台服务
 * 6. 异常自动重启机制
 * 7. 多引擎降级架构
 * 8. 触觉反馈
 * 
 * 架构说明：
 * - 使用 UnifiedAudioScheduler 统一管理音频焦点
 * - 整合了原 WakeWordServiceEnhanced 的增强功能
 * 
 * 适配场景：
 * - 前台/后台/锁屏唤醒
 * - 读屏服务开启状态
 * - 蓝牙耳机/骨传导耳机
 * - 户外嘈杂环境
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject
    lateinit var unifiedAudioScheduler: UnifiedAudioScheduler

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    
    // 运行状态
    @Volatile private var isRunning = false
    @Volatile private var isWakeWordDetected = false
    @Volatile private var restartAttempts = 0
    
    // 音频参数（16kHz, 16-bit, 单声道）
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize: Int by lazy {
        maxOf(
            AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat),
            3840 // 确保至少 240ms 的缓冲
        )
    }
    
    // 引擎管理器
    private lateinit var engineManager: WakeWordEngineManager
    private val audioBuffer = ArrayList<Short>(1024)
    
    // 健康检查
    private var lastAudioDataTime = 0L
    private var audioDataTimeout = 10_000L // 10秒无音频数据视为异常
    
    // 场景检测
    private var currentScene = UnifiedAudioScheduler.AudioScene.FOREGROUND

    companion object {
        const val ACTION_START = "com.blindpath.wakeword.START"
        const val ACTION_STOP = "com.blindpath.wakeword.STOP"
        const val ACTION_RESTART = "com.blindpath.wakeword.RESTART"
        const val ACTION_WAKE_WORD_DETECTED = "com.blindpath.wakeword.DETECTED"
        const val EXTRA_WAKE_WORD = "wake_word"
        const val EXTRA_SCENE = "scene"
        
        private const val NOTIFICATION_CHANNEL_ID = "wakeword_service_channel"
        private const val NOTIFICATION_ID = 1001
        
        private const val MAX_RESTART_ATTEMPTS = 5
        private const val RESTART_DELAY_MS = 3000L
        
        @Volatile
        var isServiceRunning = false
            private set
            
        @Volatile
        var currentEngineType = "UNKNOWN"
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("WakeWordService: onCreate")
        createNotificationChannel()
        acquireWakeLock()
        initializeEngineManager()
        startHealthCheck()
        observeSceneChanges()
        Timber.i("WakeWordService: Service created successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Timber.i("WakeWordService: Received START action")
                startWakeWordDetection()
            }
            ACTION_STOP -> {
                Timber.i("WakeWordService: Received STOP action")
                stopWakeWordDetection()
            }
            ACTION_RESTART -> {
                Timber.i("WakeWordService: Received RESTART action")
                restartService()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.i("WakeWordService: onDestroy")
        
        isRunning = false
        isServiceRunning = false
        
        stopAudioCapture()
        
        if (::engineManager.isInitialized) {
            engineManager.release()
        }
        
        releaseWakeLock()
        serviceScope.cancel()
        
        sendRestartBroadcast()
        
        super.onDestroy()
    }
    
    /**
     * 初始化引擎管理器
     */
    private fun initializeEngineManager() {
        engineManager = WakeWordEngineManager(this)
        
        engineManager.onWakeWordDetected = { wakeWord ->
            onWakeWordDetected(wakeWord)
        }
        
        engineManager.onEngineSwitched = { engineType ->
            Timber.i("WakeWordService: Engine switched to $engineType")
            currentEngineType = engineType.name
            updateNotification()
        }
        
        // 读取凭证
        val localProps = readCredentialsFromAllSources()
        
        val baiduAppId = getCredential(BuildConfig.BAIDU_APP_ID, "BAIDU_APP_ID", localProps)
        val baiduApiKey = getCredential(BuildConfig.BAIDU_API_KEY, "BAIDU_API_KEY", localProps)
        val baiduSecretKey = getCredential(BuildConfig.BAIDU_SECRET_KEY, "BAIDU_SECRET_KEY", localProps)
        val xfAppId = getCredential(BuildConfig.IFLYTEK_APP_ID, "IFLYTEK_APP_ID", localProps)
        val xfApiKey = getCredential(BuildConfig.IFLYTEK_API_KEY, "IFLYTEK_API_KEY", localProps)
        val xfApiSecret = getCredential(BuildConfig.IFLYTEK_API_SECRET, "IFLYTEK_API_SECRET", localProps)
        
        val config = WakeWordEngineManager.EngineConfig(
            primaryEngine = WakeWordEngineManager.EngineType.BAIDU,
            fallbackEnabled = true,
            baiduAppId = baiduAppId,
            baiduApiKey = baiduApiKey,
            baiduSecretKey = baiduSecretKey,
            baiduWakeWordAsset = WakeWordConfig.BAIDU_WAKE_WORD_ASSET,
            xfAppId = xfAppId,
            xfApiKey = xfApiKey,
            xfApiSecret = xfApiSecret,
            wakeWord = WakeWordConfig.DEFAULT_WAKE_WORD
        )
        
        engineManager.initialize(config)
        currentEngineType = engineManager.getCurrentEngineType().name
        
        Timber.i("WakeWordService: Engine initialized - $currentEngineType")
    }
    
    /**
     * 启动唤醒词检测
     */
    private fun startWakeWordDetection() {
        if (isRunning) {
            Timber.d("WakeWordService: Already running")
            return
        }
        
        Timber.i("WakeWordService: Starting wake word detection")
        isRunning = true
        isServiceRunning = true
        restartAttempts = 0
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())
        
        // 请求音频资源
        val granted = unifiedAudioScheduler.requestAudioResource(
            UnifiedAudioScheduler.AudioModule.WAKE_WORD,
            onGranted = {
                Timber.i("WakeWordService: Audio resource granted")
            },
            onLost = {
                handleAudioResourceLost()
            }
        )
        
        if (!granted) {
            Timber.w("WakeWordService: Audio resource not granted, using shared mode")
        }
        
        // 切换到蓝牙耳机（如果已连接）
        if (unifiedAudioScheduler.isBluetoothActive()) {
            unifiedAudioScheduler.switchToBluetooth()
        }
        
        serviceScope.launch {
            try {
                // 引擎就绪后更新通知显示实际引擎类型
                updateNotification()
                
                val engineType = engineManager.getCurrentEngineType()
                if (engineType == WakeWordEngineManager.EngineType.ENERGY) {
                    // 能量检测不可靠，不使用
                    Timber.w("WakeWordService: Only energy detection available, not reliable. Stopping.")
                    stopSelf()
                    return@launch
                }
                
                if (engineManager.isCurrentEngineSelfManaged()) {
                    // 百度/讯飞引擎：SDK 自己管理音频采集
                    Timber.i("WakeWordService: Using self-managed audio engine ($engineType), starting listener...")
                    engineManager.startListening()
                    Timber.i("WakeWordService: Self-managed engine listener started")
                } else {
                    // 非自管理引擎：需要手动采集音频
                    initAudioRecord()
                    startAudioProcessing()
                }
            } catch (e: NullPointerException) {
                Timber.e(e, "WakeWordService: SDK NPE caught! Disabling engine. Falling back to built-in wake word detection.")
                try {
                    if (::engineManager.isInitialized) {
                        engineManager.release()
                    }
                } catch (_: Exception) {}
                stopSelf()
            } catch (e: Exception) {
                Timber.e(e, "WakeWordService: Failed to start detection")
                stopSelf()
            }
        }
    }
    
    private fun stopWakeWordDetection() {
        if (!isRunning) return

        Timber.i("WakeWordService: Stopping wake word detection")
        isRunning = false
        isServiceRunning = false

        stopAudioCapture()
        unifiedAudioScheduler.releaseAudioResource(UnifiedAudioScheduler.AudioModule.WAKE_WORD)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    private fun initAudioRecord() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord initialization failed")
        }

        Timber.i("WakeWordService: AudioRecord initialized (sampleRate=$sampleRate, bufferSize=$bufferSize)")
    }
    
    private suspend fun startAudioProcessing() {
        audioRecord?.startRecording()
        Timber.i("WakeWordService: Audio processing started with engine: ${engineManager.getCurrentEngineType()}")
        lastAudioDataTime = System.currentTimeMillis()

        val buffer = ShortArray(bufferSize)
        var consecutiveEmptyReads = 0

        while (isRunning && currentCoroutineContext().isActive) {
            try {
                val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0

                if (readSize > 0) {
                    lastAudioDataTime = System.currentTimeMillis()
                    consecutiveEmptyReads = 0
                    processAudioBuffer(buffer, readSize)
                } else {
                    consecutiveEmptyReads++
                    if (consecutiveEmptyReads > 10) {
                        throw RuntimeException("Consecutive audio read errors: $consecutiveEmptyReads")
                    }
                }
                
                delay(5)
            } catch (e: Exception) {
                Timber.e(e, "WakeWordService: Audio processing error")
                handleDetectionError(e)
                break
            }
        }
    }
    
    private fun processAudioBuffer(buffer: ShortArray, size: Int) {
        if (isWakeWordDetected) return
        
        val engine = engineManager.getCurrentEngine() ?: return
        
        // 累积音频帧
        for (i in 0 until size) {
            audioBuffer.add(buffer[i])
        }
        
        // 获取引擎帧长度
        val frameLength = when (engine) {
            is WakeWordDetector -> engine.getFrameLength()
            else -> 512
        }
        
        // 处理完整帧
        while (audioBuffer.size >= frameLength && !isWakeWordDetected) {
            val frame = ShortArray(frameLength)
            for (i in 0 until frameLength) {
                frame[i] = audioBuffer.removeAt(0)
            }
            
            try {
                val detected = engine.process(frame)
                if (detected) break
            } catch (e: Exception) {
                Timber.e(e, "WakeWordService: Engine process error")
            }
        }
        
        // 防止缓冲区无限增长
        val maxBufferSize = frameLength * 4
        while (audioBuffer.size > maxBufferSize) {
            audioBuffer.removeAt(0)
        }
    }
    
    private fun onWakeWordDetected(wakeWord: String) {
        if (isWakeWordDetected) return
        
        Timber.i("WakeWordService: Wake word detected - $wakeWord")
        isWakeWordDetected = true
        
        // 发送广播通知主进程
        val intent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
            setPackage(packageName)
            putExtra(EXTRA_WAKE_WORD, wakeWord)
            putExtra(EXTRA_SCENE, currentScene.name)
        }
        sendBroadcast(intent)
        
        // 触觉反馈
        triggerHapticFeedback()
        
        // 短暂暂停后恢复监听
        serviceScope.launch {
            delay(3000) // 3秒冷却时间
            isWakeWordDetected = false
            Timber.d("WakeWordService: Resumed listening")
        }
    }
    
    /**
     * 触觉反馈
     */
    private fun triggerHapticFeedback() {
        try {
            val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? android.os.Vibrator
            vibrator?.let {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    it.vibrate(android.os.VibrationEffect.createOneShot(200, android.os.VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    it.vibrate(200)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "WakeWordService: Haptic feedback failed")
        }
    }
    
    private fun stopAudioCapture() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            audioBuffer.clear()
            Timber.d("WakeWordService: Audio capture stopped")
        } catch (e: Exception) {
            Timber.e(e, "WakeWordService: Error stopping audio capture")
        }
    }
    
    private fun handleAudioResourceLost() {
        Timber.w("WakeWordService: Audio resource lost, attempting to regain...")
        
        serviceScope.launch {
            delay(500)
            
            if (isRunning) {
                val regained = unifiedAudioScheduler.requestAudioResource(
                    UnifiedAudioScheduler.AudioModule.WAKE_WORD,
                    onGranted = { Timber.i("WakeWordService: Audio resource regained") },
                    onLost = { handleAudioResourceLost() }
                )
                
                if (!regained) {
                    Timber.w("WakeWordService: Cannot regain audio resource, pausing...")
                }
            }
        }
    }
    
    private fun handleDetectionError(error: Exception) {
        Timber.e(error, "WakeWordService: Detection error")
        
        restartAttempts++
        
        if (restartAttempts <= MAX_RESTART_ATTEMPTS) {
            Timber.i("WakeWordService: Attempting restart ($restartAttempts/$MAX_RESTART_ATTEMPTS)")
            
            serviceScope.launch {
                delay(RESTART_DELAY_MS)
                
                if (isRunning) {
                    stopAudioCapture()
                    startWakeWordDetection()
                }
            }
        } else {
            Timber.e("WakeWordService: Max restart attempts reached, stopping service")
            stopWakeWordDetection()
        }
    }
    
    private fun restartService() {
        Timber.i("WakeWordService: Restarting service")
        
        stopAudioCapture()
        restartAttempts = 0
        
        serviceScope.launch {
            delay(1000)
            startWakeWordDetection()
        }
    }
    
    /**
     * 启动健康检查
     */
    private fun startHealthCheck() {
        serviceScope.launch {
            while (isActive) {
                delay(5000)
                
                if (isRunning) {
                    val timeSinceLastData = System.currentTimeMillis() - lastAudioDataTime
                    
                    if (timeSinceLastData > audioDataTimeout) {
                        Timber.w("WakeWordService: No audio data for ${timeSinceLastData}ms, restarting...")
                        handleDetectionError(RuntimeException("Audio data timeout"))
                    }
                }
            }
        }
    }
    
    /**
     * 监听场景变化
     */
    private fun observeSceneChanges() {
        serviceScope.launch {
            unifiedAudioScheduler.audioState.collect { state ->
                val newScene = state.currentScene
                
                if (newScene != currentScene) {
                    Timber.i("WakeWordService: Scene changed from $currentScene to $newScene")
                    currentScene = newScene
                    
                    // 场景变化时重新初始化音频源
                    if (isRunning && !engineManager.isCurrentEngineSelfManaged()) {
                        restartAudioCapture()
                    }
                }
            }
        }
    }
    
    private fun restartAudioCapture() {
        serviceScope.launch {
            stopAudioCapture()
            delay(500)
            initAudioRecord()
            startAudioProcessing()
        }
    }
    
    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BlindPath::WakeWordWakeLock"
        ).apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // 1小时
        }
        Timber.d("WakeWordService: WakeLock acquired")
    }
    
    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
        Timber.d("WakeWordService: WakeLock released")
    }
    
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "语音唤醒服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持语音唤醒功能在后台持续运行"
            setShowBadge(false)
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    private fun createNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent().apply {
            setClassName(packageName, "${packageName}.MainActivity")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("助盲智行")
            .setContentText("语音唤醒服务运行中 [$currentEngineType]")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }
    
    private fun updateNotification() {
        if (isRunning) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification())
            Timber.i("WakeWordService: Notification updated [$currentEngineType]")
        }
    }
    
    private fun sendRestartBroadcast() {
        val intent = Intent(ACTION_RESTART).apply {
            setPackage(packageName)
        }
        sendBroadcast(intent)
    }
    
    /**
     * 从多个来源读取凭证
     */
    private fun readCredentialsFromAllSources(): Map<String, String> {
        val creds = mutableMapOf<String, String>()
        
        try {
            assets.open("credentials.properties").use { stream ->
                val props = Properties()
                props.load(stream)
                props.forEach { key, value ->
                    creds[key.toString()] = value.toString()
                }
                Timber.d("WakeWordService: Loaded credentials from assets (${creds.size} values)")
            }
        } catch (e: Exception) {
            Timber.d("WakeWordService: No credentials in assets")
        }
        
        if (creds.isEmpty()) {
            try {
                val paths = listOf(
                    "/data/local/tmp/com.blindpath.app/local.properties",
                    filesDir.absolutePath + "/../local.properties",
                    "/sdcard/BlindPath/local.properties"
                )
                
                for (path in paths) {
                    val file = File(path)
                    if (file.exists()) {
                        val props = Properties()
                        file.inputStream().use { props.load(it) }
                        props.forEach { key, value ->
                            creds[key.toString()] = value.toString()
                        }
                        Timber.d("WakeWordService: Loaded credentials from $path")
                        break
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "WakeWordService: Failed to read external credentials")
            }
        }
        
        return creds
    }
    
    /**
     * 获取凭证值
     */
    private fun getCredential(buildConfigValue: String, propertyName: String, localProps: Map<String, String>): String {
        if (buildConfigValue.isNotBlank()) return buildConfigValue
        return localProps[propertyName] ?: ""
    }
}
