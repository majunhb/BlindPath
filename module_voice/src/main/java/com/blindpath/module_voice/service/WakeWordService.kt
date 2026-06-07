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
import android.os.IBinder
import android.os.PowerManager
import android.os.Process
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
 * 全程语音交互修复：
 * 1. 修复凭证缺失时的处理：不再启动无意义的能量检测前台服务
 * 2. 凭证缺失时直接停止服务，让 VoiceCommandRepositoryImpl 的内置唤醒词检测工作
 * 3. 添加详细日志帮助诊断唤醒问题
 *
 * 架构说明：
 * - 唤醒词检测有两条路径：
 *   a) 外部引擎（百度/讯飞）：由 WakeWordService 管理，检测到后发广播
 *   b) 内置检测（SpeechRecognizer）：由 VoiceCommandRepositoryImpl 管理，在 onResults() 中匹配
 * - 两条路径互不干扰，内部检测使用 WakeWordConfig.containsWakeWord() 匹配所有别名
 */
@AndroidEntryPoint
class WakeWordService : Service() {

    @Inject
    lateinit var audioFocusManager: AudioFocusManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null

    private var isRunning = false
    private var isWakeWordDetected = false

    // [P2 异常熔断] 收音断流检测
    private var consecutiveReadErrors = 0
    private val MAX_CONSECUTIVE_ERRORS = 10
    private var restartCount = 0
    private val MAX_RESTART_COUNT = 5

    // 音频参数（16kHz, 16-bit, 单声道）
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    }

    // 引擎管理器
    private lateinit var engineManager: WakeWordEngineManager

    companion object {
        const val ACTION_START = "com.blindpath.wakeword.START"
        const val ACTION_STOP = "com.blindpath.wakeword.STOP"
        const val ACTION_WAKE_WORD_DETECTED = "com.blindpath.wakeword.DETECTED"
        const val EXTRA_WAKE_WORD = "wake_word"

        private const val NOTIFICATION_CHANNEL_ID = "wakeword_service_channel"
        private const val NOTIFICATION_ID = 1001

        @Volatile
        var isServiceRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("WakeWordService: onCreate")
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startWakeWordDetection()
            ACTION_STOP -> stopWakeWordDetection()
            // [P1 修复] 蓝牙设备切换时重新绑定音频源
            "com.blindpath.wakeword.BLUETOOTH_CONNECTED",
            "com.blindpath.wakeword.BLUETOOTH_DISCONNECTED" -> {
                Timber.i("WakeWordService: Bluetooth audio route changed, reinitializing audio source")
                if (isRunning) {
                    reinitializeAudioAfterRouteChange()
                }
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        Timber.i("WakeWordService: onDestroy")
        stopWakeWordDetection()
        releaseWakeLock()
        if (::engineManager.isInitialized) {
            engineManager.release()
        }
        serviceScope.cancel()
    }

    /**
     * 从多个来源读取凭证（优先级：BuildConfig > assets > 外部文件）
     * BuildConfig 在某些构建环境下可能为空，此方法作为备用方案
     */
    private fun readCredentialsFromAllSources(): Map<String, String> {
        val creds = mutableMapOf<String, String>()
        
        // 1. 尝试从assets读取（最可靠，随APK打包）
        try {
            assets.open("credentials.properties").use { stream ->
                val props = Properties()
                props.load(stream)
                props.forEach { key, value ->
                    creds[key.toString()] = value.toString()
                }
                Timber.i("WakeWordService: Loaded credentials from assets (${creds.size} values)")
            }
        } catch (e: Exception) {
            Timber.d("WakeWordService: No credentials in assets")
        }
        
        // 2. 尝试从外部文件读取（用于动态更新）
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
                        Timber.i("WakeWordService: Loaded credentials from $path (${creds.size} values)")
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
     * 获取凭证值：优先 BuildConfig，其次 local.properties 文件
     */
    private fun getCredential(buildConfigValue: String, propertyName: String, localProps: Map<String, String>): String {
        if (buildConfigValue.isNotBlank()) return buildConfigValue
        val fileValue = localProps[propertyName] ?: ""
        if (fileValue.isNotBlank()) {
            Timber.i("WakeWordService: Using $propertyName from local.properties (BuildConfig was empty)")
        }
        return fileValue
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
            updateNotification()
        }

        val localProps = readCredentialsFromAllSources()

        // 百度凭证安全策略：只信任 BuildConfig 值（来自 local.properties 或 CI Secrets）
        // assets/credentials.properties 中的百度凭证可能导致 SDK 的 EventListener NPE bug
        // 因此不使用 assets 中的百度凭证
        val baiduAppId = BuildConfig.BAIDU_APP_ID
        val baiduApiKey = BuildConfig.BAIDU_API_KEY
        val baiduSecretKey = BuildConfig.BAIDU_SECRET_KEY

        // 讯飞凭证可以使用 assets 中的值（讯飞 SDK 没有 NPE bug）
        val xfAppId = getCredential(BuildConfig.IFLYTEK_APP_ID, "IFLYTEK_APP_ID", localProps)
        val xfApiKey = getCredential(BuildConfig.IFLYTEK_API_KEY, "IFLYTEK_API_KEY", localProps)
        val xfApiSecret = getCredential(BuildConfig.IFLYTEK_API_SECRET, "IFLYTEK_API_SECRET", localProps)

        Timber.d("WakeWordService: BAIDU_APP_ID=${if (baiduAppId.isNotBlank()) "from BuildConfig" else "EMPTY (not using assets)"}")
        Timber.d("WakeWordService: IFLYTEK_APP_ID=${if (xfAppId.isNotBlank()) "configured" else "EMPTY"}")

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
        Timber.i("WakeWordService: Engine manager initialized with ${engineManager.getCurrentEngineType()}")
    }

    /**
     * 启动唤醒词检测
     *
     * 设计原则：
     * 1. 若配置了百度/讯飞凭证，则启动低功耗的专用唤醒引擎。
     * 2. 若凭证缺失（开发/测试阶段），本服务静默退出，
     *    由主进程 VoiceCommandRepositoryImpl 的内置 SpeechRecognizer 持续监听接管唤醒词检测。
     *    ★ 关键修复：原逻辑在凭证缺失时调用 stopSelf() 后直接 return，
     *      这不影响内置唤醒路径（两者独立），但要确保 startCommandProcessing 已启动。
     *      修复后改为 notifyNoExternalEngine() 记录日志并安静退出，
     *      不再调用 stopSelf()（服务根本就没有调用 startForeground，Android 会自动回收它）。
     *    注意：stopSelf() 本身没问题，但调用后 return 会让调用方误以为唤醒服务在工作。
     *    实际内置路径：MainActivity.initializeVoiceInteraction()
     *      → voiceInteractionManager.initialize()
     *      → speakWelcome() → commandRepository.setWakeWordEnabled(true)
     *      → VoiceCommandRepositoryImpl.startContinuousListening()（主进程）
     * 3. 百度 SDK 存在 EventListener NPE 的已知 bug（异步线程崩溃），
     *    仅当 BuildConfig 有 BAIDU_APP_ID 时才使用，避免 assets 中占位值触发 SDK bug。
     */
    private fun startWakeWordDetection() {
        if (isRunning) {
            Timber.d("WakeWordService: Already running")
            return
        }

        Timber.i("WakeWordService: Starting wake word detection")

        // 检查凭证是否可用
        val localProps = readCredentialsFromAllSources()

        // 优先使用 BuildConfig 值（通过 local.properties 或 CI Secrets 配置）
        val baiduAppId = BuildConfig.BAIDU_APP_ID
        val xfAppId = getCredential(BuildConfig.IFLYTEK_APP_ID, "IFLYTEK_APP_ID", localProps)

        // 安全检查：百度 BuildConfig 有值且非占位符才使用
        val useBaidu = baiduAppId.isNotBlank() && baiduAppId != "BAIDU_APP_ID"
        val useXf = xfAppId.isNotBlank()

        Timber.i("WakeWordService: baiduAppId from BuildConfig=${useBaidu}, xfAppId available=${useXf}")

        if (!useBaidu && !useXf) {
            // ★★★ 降级方案：使用 SpeechRecognizer 内置唤醒词模式
            // 内置模式通过 VoiceCommandRepositoryImpl 的 onResults() 回调检测唤醒词
            // 默认唤醒词："小智小智"（支持别名：小智同学、小智、晓得同学、小智同窗）
            Timber.i("WakeWordService: No Baidu/iFlytek credentials, using built-in SpeechRecognizer wake word mode")
            Timber.i("WakeWordService: Built-in wake words: 小智小智, 小智同学, 小智, 晓得同学, 小智同窗")
            Timber.i("WakeWordService: To enable low-power wake word engine, set BAIDU_APP_ID in local.properties")
            return
        }

        isRunning = true
        isServiceRunning = true

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())

        // 获取 WakeLock（无超时，手动释放）
        acquireWakeLock()

        // 请求音频焦点
        audioFocusManager.requestFocus("wakeword", priority = 10)

        // 切换到蓝牙耳机（如果已连接）
        if (audioFocusManager.isBluetoothHeadsetConnected()) {
            audioFocusManager.switchToBluetoothSco()
        }

        // 初始化并启动唤醒词检测
        serviceScope.launch(Dispatchers.IO) {
            // [P1 修复] 提升线程优先级到最高音频级别，避免被 UI/导航线程压制
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_URGENT_AUDIO)
            Timber.i("WakeWordService: Thread priority set to URGENT_AUDIO")
            try {
                initializeEngineManager()

                // 引擎就绪后更新通知显示实际引擎类型
                updateNotification()

                val engineType = engineManager.getCurrentEngineType()
                if (engineType == WakeWordEngineManager.EngineType.ENERGY) {
                    // ★★★ 修复 v2-3：能量检测模式不再 stopSelf()
                    // 原逻辑：检测到 ENERGY 模式 → stopSelf() → 服务直接自杀
                    // 问题：百度/讯飞凭证都为空时降级到 ENERGY → 服务启动后立即销毁
                    //       如果后续凭证变为可用（如运行时配置），服务已死无法恢复
                    // 修复：保持服务存活但不做有意义的唤醒检测（ENERGY 本身就不可靠），
                    //       真正的唤醒词检测由 VoiceCommandRepositoryImpl 的内置 SpeechRecognizer 接管
                    Timber.w("WakeWordService: Only energy detection available (no valid Baidu/iFlytek credentials)")
                    Timber.w("WakeWordService: Service stays alive but wake word detection relies on built-in SpeechRecognizer")
                    Timber.w("WakeWordService: To enable low-power engine, configure BAIDU_APP_ID in local.properties")
                    // 不再调用 stopSelf()，让服务以最低资源占用保持存活
                    return@launch
                }

                if (engineManager.isCurrentEngineSelfManaged()) {
                    // 百度/讯飞引擎：SDK 自己管理音频采集，不需要手动启动 AudioRecord
                    // ★★ 关键修复：必须调用 startListening() 才能真正开始检测唤醒词
                    Timber.i("WakeWordService: Using self-managed audio engine ($engineType), starting listener...")
                    engineManager.startListening()
                    Timber.i("WakeWordService: Self-managed engine listener started")
                } else {
                    // 非自管理引擎：需要手动采集音频
                    initAudioRecord()
                    startAudioProcessing()
                }
            } catch (e: NullPointerException) {
                // 百度 SDK 的 EventListener NPE bug 可能在同步或异步线程上触发
                Timber.e(e, "WakeWordService: ★★★ Baidu SDK NPE caught! Disabling Baidu engine. Falling back to built-in wake word detection.")
                // 停止服务，让内置唤醒词检测接管
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

        // 停止音频处理
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // 停止蓝牙耳机音频
        audioFocusManager.stopBluetoothSco()

        // 释放音频焦点
        audioFocusManager.abandonFocus("wakeword")

        // 停止前台服务
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

        val buffer = ShortArray(bufferSize)

        while (isRunning && currentCoroutineContext().isActive) {
            try {
                val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0

                if (readSize > 0) {
                    // [P2 熔断] 成功读取，重置错误计数
                    consecutiveReadErrors = 0
                    // 处理音频数据
                    processAudioBuffer(buffer, readSize)
                } else if (readSize == 0) {
                    // [P2 熔断] 读到 0 字节，可能是音频源失效
                    consecutiveReadErrors++
                    Timber.w("WakeWordService: Read 0 bytes (error count: $consecutiveReadErrors/$MAX_CONSECUTIVE_ERRORS)")
                    if (consecutiveReadErrors >= MAX_CONSECUTIVE_ERRORS) {
                        triggerCircuitBreaker("consecutive zero reads")
                        return
                    }
                }

                // 短暂休眠以降低CPU占用
                delay(10)
            } catch (e: Exception) {
                consecutiveReadErrors++
                Timber.e(e, "WakeWordService: Audio processing error (count: $consecutiveReadErrors/$MAX_CONSECUTIVE_ERRORS)")
                if (consecutiveReadErrors >= MAX_CONSECUTIVE_ERRORS) {
                    triggerCircuitBreaker("consecutive exceptions: ${e.message}")
                    return
                }
                delay(100)
            }
        }
    }

    /**
     * [P2 异常熔断] 音频收音断流时自动重启
     */
    private fun triggerCircuitBreaker(reason: String) {
        Timber.w("WakeWordService: ★ Circuit breaker triggered! Reason: $reason")
        consecutiveReadErrors = 0

        if (restartCount >= MAX_RESTART_COUNT) {
            Timber.e("WakeWordService: Max restart count ($MAX_RESTART_COUNT) reached. Stopping service.")
            stopWakeWordDetection()
            return
        }

        restartCount++
        Timber.i("WakeWordService: Attempting restart $restartCount/$MAX_RESTART_COUNT...")

        serviceScope.launch(Dispatchers.IO) {
            // 停止当前音频
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (e: Exception) {
                Timber.w(e, "WakeWordService: Error stopping audio during restart")
            }

            // 等待音频资源释放
            delay(500)

            // 重新初始化音频
            try {
                initAudioRecord()
                startAudioProcessing()
                Timber.i("WakeWordService: Restart successful (attempt $restartCount)")
            } catch (e: Exception) {
                Timber.e(e, "WakeWordService: Restart failed")
                if (restartCount >= MAX_RESTART_COUNT) {
                    stopWakeWordDetection()
                }
            }
        }
    }

    /**
     * [P1 修复] 蓝牙音频路由切换后重新初始化音频源
     */
    private fun reinitializeAudioAfterRouteChange() {
        serviceScope.launch(Dispatchers.IO) {
            Timber.i("WakeWordService: Reinitializing audio after route change...")

            // 停止当前音频
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (e: Exception) {
                Timber.w(e, "WakeWordService: Error stopping audio for route change")
            }

            // 等待蓝牙 SCO 切换完成
            delay(1000)

            // 重新初始化
            try {
                initAudioRecord()
                startAudioProcessing()
                consecutiveReadErrors = 0
                Timber.i("WakeWordService: Audio reinitialized after route change")
            } catch (e: Exception) {
                Timber.e(e, "WakeWordService: Failed to reinitialize audio after route change")
                triggerCircuitBreaker("route change reinit failed")
            }
        }
    }

    private fun processAudioBuffer(buffer: ShortArray, size: Int) {
        if (isWakeWordDetected) return

        val engine = engineManager.getCurrentEngine()
        if (engine == null) {
            Timber.w("WakeWordService: No engine available")
            return
        }

        val audioBuffer = ArrayList<Short>(512)

        // 将音频数据添加到缓冲区
        for (i in 0 until size) {
            audioBuffer.add(buffer[i])
        }

        // 获取帧长度
        val frameLength = 512

        // 处理缓冲区中完整的帧
        while (audioBuffer.size >= frameLength && !isWakeWordDetected) {
            val frame = ShortArray(frameLength)
            for (i in 0 until frameLength) {
                frame[i] = audioBuffer.removeAt(0)
            }

            val detected = engine.process(frame)
            if (detected) {
                break
            }
        }
    }

    private fun onWakeWordDetected(wakeWord: String) {
        if (isWakeWordDetected) return

        Timber.i("WakeWordService: ★★★ Wake word detected - $wakeWord")
        isWakeWordDetected = true

        // 发送广播通知
        val intent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
            setPackage(packageName)
            putExtra(EXTRA_WAKE_WORD, wakeWord)
        }
        sendBroadcast(intent)

        // 短暂暂停后恢复监听
        serviceScope.launch {
            delay(2000)
            isWakeWordDetected = false
            Timber.d("WakeWordService: Resumed listening")
        }
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "语音唤醒服务",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "保持语音唤醒功能在后台运行"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        // 安全获取引擎类型，未初始化时显示 "启动中..."
        val engineType = if (::engineManager.isInitialized) {
            engineManager.getCurrentEngineType().name
        } else {
            "启动中..."
        }

        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent().apply {
            setClassName(packageName, "${packageName}.MainActivity")
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        // 注意：不要在此处引用 engineManager，因为 createNotification() 可能在 engineManager 初始化之前被调用
        val engineInfo = if (::engineManager.isInitialized) {
            engineManager.getCurrentEngineType().name
        } else {
            "initializing"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("助盲智行")
            .setContentText("语音唤醒服务运行中 [$engineType]")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    /**
     * 引擎就绪或切换后更新通知内容
     */
    private fun updateNotification() {
        try {
            val notificationManager = NotificationManagerCompat.from(this)
            notificationManager.notify(NOTIFICATION_ID, createNotification())
            Timber.i("WakeWordService: Notification updated [${engineManager.getCurrentEngineType()}]")
        } catch (e: Exception) {
            Timber.w(e, "WakeWordService: Failed to update notification")
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BlindPath::WakeWordWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
        // 无超时 acquire，在 onDestroy/releaseWakeLock 中手动释放
        wakeLock?.acquire()
    }

    private fun releaseWakeLock() {
        wakeLock?.let {
            if (it.isHeld) {
                it.release()
            }
        }
        wakeLock = null
    }
}

/**
 * 唤醒词检测器接口
 */
interface WakeWordDetector {
    fun startListening()
    fun process(audioData: ShortArray): Boolean
    fun release()
}
