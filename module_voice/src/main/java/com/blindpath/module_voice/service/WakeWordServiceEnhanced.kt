package com.blindpath.module_voice.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.*
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.blindpath.module_voice.BuildConfig
import com.blindpath.module_voice.domain.model.WakeWordConfig
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.util.Properties
import javax.inject.Inject
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT

/**
 * 语音唤醒常驻服务（增强版）
 * 
 * 核心优化：
 * 1. 独立进程运行，不受主进程影响
 * 2. 7x24 小时常驻监听，适配厂商省电策略
 * 3. 智能音频焦点管理，兼容 TalkBack
 * 4. 蓝牙外设自动切换
 * 5. 后台保活 + 自启 + 前台服务
 * 6. 异常自动重启机制
 * 7. 多引擎降级架构
 * 
 * 适配场景：
 * - 前台/后台/锁屏唤醒
 * - 读屏服务开启状态
 * - 蓝牙耳机/骨传导耳机
 * - 户外嘈杂环境
 */
/**
 * ★★★ 多进程安全：移除 @AndroidEntryPoint
 *
 * WakeWordServiceEnhanced 运行在 :wakeword 独立进程。
 * Hilt 的 @AndroidEntryPoint 在子进程中会导致 “HiltComponentManager not found” 崩溃。
 * 解决：移除 @AndroidEntryPoint，改为在 onCreate() 中手动构建依赖。
 * 由于 UnifiedAudioScheduler 和 AudioFocusManager 只需要 applicationContext，
 * 可以直接手动 new ，不依赖 Hilt。
 */
class WakeWordServiceEnhanced : Service() {

    // 移除 @Inject，改为延迟初始化（在 onCreate 时构建）
    private lateinit var unifiedAudioScheduler: UnifiedAudioScheduler
    private lateinit var audioFocusManager: AudioFocusManager

    // 独立协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // 高优先级线程池
    private val highPriorityExecutor = Dispatchers.Default
    
    // 音频采集
    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var partialWakeLock: PowerManager.WakeLock? = null
    
    // 运行状态
    @Volatile private var isRunning = false
    @Volatile private var isWakeWordDetected = false
    @Volatile private var restartAttempts = 0
    
    // 音频参数（标准化：16kHz, 16-bit, 单声道）
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize: Int by lazy {
        maxOf(
            AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat),
            3840 // 确保至少 240ms 的缓冲
        )
    }
    
    // 引擎管理
    private lateinit var engineManager: WakeWordEngineManager
    private val audioBuffer = ArrayList<Short>(1024)
    
    // 场景检测
    private var lastSceneCheckTime = 0L
    private var currentScene = UnifiedAudioScheduler.AudioScene.FOREGROUND
    
    // 健康检查
    private var lastAudioDataTime = 0L
    private var audioDataTimeout = 10_000L // 10秒无音频数据视为异常
    
    companion object {
        const val ACTION_START = "com.blindpath.wakeword.START"
        const val ACTION_STOP = "com.blindpath.wakeword.STOP"
        const val ACTION_RESTART = "com.blindpath.wakeword.RESTART"
        const val ACTION_WAKE_WORD_DETECTED = "com.blindpath.wakeword.DETECTED"
        const val EXTRA_WAKE_WORD = "wake_word"
        const val EXTRA_SCENE = "scene"
        
        private const val NOTIFICATION_CHANNEL_ID = "wakeword_service_channel_enhanced"
        private const val NOTIFICATION_ID = 2001
        
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
        // ★★★ 多进程安全：手动构建依赖（不依赖 Hilt）
        unifiedAudioScheduler = UnifiedAudioScheduler(applicationContext)
        audioFocusManager = AudioFocusManager(applicationContext)
        super.onCreate()
        Timber.i("WakeWordServiceEnhanced: onCreate (Enhanced Version)")
        
        // 创建通知渠道
        createNotificationChannel()
        
        // 获取 WakeLock
        acquireWakeLocks()
        
        // 初始化引擎
        initializeEngineManager()
        
        // 启动健康检查
        startHealthCheck()
        
        // 监听场景变化
        observeSceneChanges()
        
        Timber.i("WakeWordServiceEnhanced: Service created successfully")
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Timber.i("WakeWordServiceEnhanced: Received START action")
                startWakeWordDetection()
            }
            ACTION_STOP -> {
                Timber.i("WakeWordServiceEnhanced: Received STOP action")
                stopWakeWordDetection()
            }
            ACTION_RESTART -> {
                Timber.i("WakeWordServiceEnhanced: Received RESTART action")
                restartService()
            }
        }
        
        // START_STICKY: 服务被杀死后会自动重启
        // START_REDELIVER_INTENT: 重启后会重新发送最后的 Intent
        return START_STICKY
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onDestroy() {
        Timber.i("WakeWordServiceEnhanced: onDestroy")
        
        isRunning = false
        isServiceRunning = false
        
        // 停止音频处理
        stopAudioCapture()
        
        // 释放引擎
        if (::engineManager.isInitialized) {
            engineManager.release()
        }
        
        // 释放 WakeLock
        releaseWakeLocks()
        
        // 取消协程
        serviceScope.cancel()
        
        // 发送重启广播（服务被系统杀死时尝试重启）
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
            Timber.i("WakeWordServiceEnhanced: Engine switched to $engineType")
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
        
        Timber.i("WakeWordServiceEnhanced: Engine initialized - $currentEngineType")
    }
    
    /**
     * 启动唤醒词检测
     */
    private fun startWakeWordDetection() {
        if (isRunning) {
            Timber.d("WakeWordServiceEnhanced: Already running")
            return
        }
        
        Timber.i("WakeWordServiceEnhanced: Starting wake word detection")
        isRunning = true
        isServiceRunning = true
        restartAttempts = 0
        
        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())
        
        // 请求音频资源
        val granted = unifiedAudioScheduler.requestAudioResource(
            UnifiedAudioScheduler.AudioModule.WAKE_WORD,
            onGranted = {
                Timber.i("WakeWordServiceEnhanced: Audio resource granted")
            },
            onLost = {
                Timber.w("WakeWordServiceEnhanced: Audio resource lost")
                handleAudioResourceLost()
            }
        )
        
        if (!granted) {
            Timber.w("WakeWordServiceEnhanced: Failed to get audio resource, retrying...")
            // 延迟重试
            serviceScope.launch {
                delay(1000)
                if (isRunning) {
                    startWakeWordDetection()
                }
            }
            return
        }
        
        // 切换到蓝牙（如果已连接）
        if (unifiedAudioScheduler.isBluetoothActive()) {
            unifiedAudioScheduler.switchToBluetooth()
        }
        
        // 启动音频采集
        serviceScope.launch(highPriorityExecutor) {
            try {
                if (engineManager.isCurrentEngineSelfManaged()) {
                    // 自管理音频引擎（百度/讯飞）
                    Timber.i("WakeWordServiceEnhanced: Using self-managed engine")
                    engineManager.getCurrentEngine()?.let { engine ->
                        if (engine is BaiduWakeWordDetector) {
                            engine.startListening()
                        } else if (engine is XfWakeWordDetector) {
                            engine.startListening()
                        }
                    }
                } else {
                    // 手动音频采集（能量检测）
                    Timber.i("WakeWordServiceEnhanced: Starting manual audio capture")
                    initAudioRecord()
                    startAudioProcessing()
                }
                
                lastAudioDataTime = System.currentTimeMillis()
                
            } catch (e: Exception) {
                Timber.e(e, "WakeWordServiceEnhanced: Failed to start detection")
                handleDetectionError(e)
            }
        }
    }
    
    /**
     * 停止唤醒词检测
     */
    private fun stopWakeWordDetection() {
        if (!isRunning) return
        
        Timber.i("WakeWordServiceEnhanced: Stopping wake word detection")
        isRunning = false
        isServiceRunning = false
        
        // 停止音频采集
        stopAudioCapture()
        
        // 释放音频资源
        unifiedAudioScheduler.releaseAudioResource(UnifiedAudioScheduler.AudioModule.WAKE_WORD)
        
        // 停止前台服务
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }
    
    /**
     * 初始化音频录制
     */
    private fun initAudioRecord() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
        
        // 优化音频源选择
        val audioSource = selectOptimalAudioSource()
        
        audioRecord = AudioRecord(
            audioSource,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 2 // 双倍缓冲
        )
        
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord initialization failed")
        }
        
        Timber.i("WakeWordServiceEnhanced: AudioRecord initialized - source=$audioSource, rate=$sampleRate, buffer=$bufferSize")
    }
    
    /**
     * 选择最优音频源
     */
    private fun selectOptimalAudioSource(): Int {
        // 根据场景选择音频源
        return when {
            // 读屏服务开启时使用 VOICE_RECOGNITION
            unifiedAudioScheduler.isTalkBackActive() -> {
                Timber.d("WakeWordServiceEnhanced: Using VOICE_RECOGNITION source (TalkBack active)")
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
            // 蓝牙耳机连接时使用 VOICE_COMMUNICATION
            unifiedAudioScheduler.isBluetoothActive() -> {
                Timber.d("WakeWordServiceEnhanced: Using VOICE_COMMUNICATION source (Bluetooth active)")
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }
            // 默认使用 MIC
            else -> {
                Timber.d("WakeWordServiceEnhanced: Using MIC source")
                MediaRecorder.AudioSource.MIC
            }
        }
    }
    
    /**
     * 启动音频处理循环
     */
    private suspend fun startAudioProcessing() {
        audioRecord?.startRecording()
        Timber.i("WakeWordServiceEnhanced: Audio processing started")
        
        val buffer = ShortArray(bufferSize)
        var consecutiveEmptyReads = 0
        
        while (isRunning && currentCoroutineContext().isActive) {
            try {
                val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                
                if (readSize > 0) {
                    lastAudioDataTime = System.currentTimeMillis()
                    consecutiveEmptyReads = 0
                    
                    // 处理音频数据
                    processAudioBuffer(buffer, readSize)
                } else if (readSize < 0) {
                    Timber.w("WakeWordServiceEnhanced: Audio read error: $readSize")
                    consecutiveEmptyReads++
                    
                    if (consecutiveEmptyReads > 10) {
                        throw RuntimeException("Consecutive audio read errors: $consecutiveEmptyReads")
                    }
                }
                
                // 短暂休眠（降低 CPU 占用）
                delay(5)
                
            } catch (e: Exception) {
                Timber.e(e, "WakeWordServiceEnhanced: Audio processing error")
                handleDetectionError(e)
                break
            }
        }
    }
    
    /**
     * 处理音频缓冲区
     */
    private fun processAudioBuffer(buffer: ShortArray, size: Int) {
        if (isWakeWordDetected) return
        
        val engine = engineManager.getCurrentEngine() ?: return
        
        // 累积音频帧
        for (i in 0 until size) {
            audioBuffer.add(buffer[i])
        }
        
        // 获取引擎帧长度
        val frameLength = when (engine) {
            is EnergyWakeWordDetector -> engine.getFrameLength()
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
                Timber.e(e, "WakeWordServiceEnhanced: Engine process error")
            }
        }
        
        // 防止缓冲区无限增长
        val maxBufferSize = frameLength * 4
        while (audioBuffer.size > maxBufferSize) {
            audioBuffer.removeAt(0)
        }
    }
    
    /**
     * 唤醒词检测成功回调
     */
    private fun onWakeWordDetected(wakeWord: String) {
        if (isWakeWordDetected) return
        
        Timber.i("WakeWordServiceEnhanced: Wake word detected - $wakeWord")
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
            Timber.d("WakeWordServiceEnhanced: Resumed listening")
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
            Timber.e(e, "WakeWordServiceEnhanced: Haptic feedback failed")
        }
    }
    
    /**
     * 停止音频采集
     */
    private fun stopAudioCapture() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            audioBuffer.clear()
            Timber.d("WakeWordServiceEnhanced: Audio capture stopped")
        } catch (e: Exception) {
            Timber.e(e, "WakeWordServiceEnhanced: Error stopping audio capture")
        }
    }
    
    /**
     * 处理音频资源丢失
     */
    private fun handleAudioResourceLost() {
        Timber.w("WakeWordServiceEnhanced: Audio resource lost, attempting to regain...")
        
        serviceScope.launch {
            delay(500)
            
            if (isRunning) {
                val regained = unifiedAudioScheduler.requestAudioResource(
                    UnifiedAudioScheduler.AudioModule.WAKE_WORD,
                    onGranted = { Timber.i("WakeWordServiceEnhanced: Audio resource regained") },
                    onLost = { handleAudioResourceLost() }
                )
                
                if (!regained) {
                    Timber.w("WakeWordServiceEnhanced: Cannot regain audio resource, pausing...")
                }
            }
        }
    }
    
    /**
     * 处理检测错误
     */
    private fun handleDetectionError(error: Exception) {
        Timber.e(error, "WakeWordServiceEnhanced: Detection error")
        
        restartAttempts++
        
        if (restartAttempts <= MAX_RESTART_ATTEMPTS) {
            Timber.i("WakeWordServiceEnhanced: Attempting restart ($restartAttempts/$MAX_RESTART_ATTEMPTS)")
            
            serviceScope.launch {
                delay(RESTART_DELAY_MS)
                
                if (isRunning) {
                    stopAudioCapture()
                    startWakeWordDetection()
                }
            }
        } else {
            Timber.e("WakeWordServiceEnhanced: Max restart attempts reached, stopping service")
            stopWakeWordDetection()
        }
    }
    
    /**
     * 重启服务
     */
    private fun restartService() {
        Timber.i("WakeWordServiceEnhanced: Restarting service")
        
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
                        Timber.w("WakeWordServiceEnhanced: No audio data for ${timeSinceLastData}ms, restarting...")
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
                    Timber.i("WakeWordServiceEnhanced: Scene changed from $currentScene to $newScene")
                    currentScene = newScene
                    
                    // 场景变化时重新初始化音频源
                    if (isRunning && !engineManager.isCurrentEngineSelfManaged()) {
                        restartAudioCapture()
                    }
                }
            }
        }
    }
    
    /**
     * 重启音频采集
     */
    private fun restartAudioCapture() {
        serviceScope.launch {
            stopAudioCapture()
            delay(500)
            initAudioRecord()
            startAudioProcessing()
        }
    }
    
    /**
     * 获取 WakeLock
     */
    private fun acquireWakeLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        
        // PARTIAL_WAKE_LOCK: 保持 CPU 运行，允许屏幕关闭
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BlindPath::WakeWordWakeLockEnhanced"
        ).apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L) // 1小时
        }
        
        Timber.d("WakeWordServiceEnhanced: WakeLock acquired")
    }
    
    /**
     * 释放 WakeLock
     */
    private fun releaseWakeLocks() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
        
        Timber.d("WakeWordServiceEnhanced: WakeLock released")
    }
    
    /**
     * 创建通知渠道
     */
    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "语音唤醒服务（增强版）",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持语音唤醒功能在后台持续运行"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
    
    /**
     * 创建前台服务通知
     */
    private fun createNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent().apply {
            setClassName(packageName, "${packageName}.MainActivity")
        }
        
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT
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
    
    /**
     * 更新通知
     */
    private fun updateNotification() {
        if (isRunning) {
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.notify(NOTIFICATION_ID, createNotification())
        }
    }
    
    /**
     * 发送重启广播
     */
    private fun sendRestartBroadcast() {
        // 服务被杀死时，发送广播尝试重启
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
                Timber.d("WakeWordServiceEnhanced: Loaded credentials from assets (${creds.size} values)")
            }
        } catch (e: Exception) {
            Timber.d("WakeWordServiceEnhanced: No credentials in assets")
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
                        Timber.d("WakeWordServiceEnhanced: Loaded credentials from $path")
                        break
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "WakeWordServiceEnhanced: Failed to read external credentials")
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
