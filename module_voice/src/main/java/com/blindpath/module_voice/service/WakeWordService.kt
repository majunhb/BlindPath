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
            // ★★★ 修复：凭证缺失时不 stopSelf()，内置 SpeechRecognizer 唤醒路径已由
            // VoiceInteractionManagerImpl.speakWelcome() → commandRepository.setWakeWordEnabled(true) 接管。
            // 本服务没有调用 startForeground()，Android 系统会自动回收（正常行为，非崩溃）。
            Timber.i("WakeWordService: No external engine credentials configured.")
            Timber.i("WakeWordService: Built-in SpeechRecognizer wake word detection (via VoiceCommandRepositoryImpl) will handle it.")
            Timber.i("WakeWordService: To enable low-power wake word engine, set BAIDU_APP_ID in local.properties")
            // 直接 return，不调用 stopSelf()，让 VoiceCommandRepositoryImpl 的持续监听接管
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
        serviceScope.launch {
            try {
                initializeEngineManager()

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
                    // 处理音频数据
                    processAudioBuffer(buffer, readSize)
                }

                // 短暂休眠以降低CPU占用
                delay(10)
            } catch (e: Exception) {
                Timber.e(e, "WakeWordService: Audio processing error")
                delay(100)
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
