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
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.blindpath.module_voice.BuildConfig
import com.blindpath.module_voice.domain.model.WakeWordConfig
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.Properties
import android.app.PendingIntent.FLAG_IMMUTABLE
import android.app.PendingIntent.FLAG_UPDATE_CURRENT

/**
 * 语音唤醒常驻服务（增强版 v4.0）
 *
 * ★★★ v4.0 唤醒可靠性专项优化（2026-08-25）：
 * D5 引擎降级后重启音频采集：onEngineSwitched 收到回调后，如果当前不是
 *    self-managed，立即重启 AudioRecord + 处理循环，确保 frameLength 按
 *    新引擎取值（讯飞 320 / Porcupine 512 / Energy 512）
 * D6 蓝牙 SCO 启动失败不阻塞：蓝牙时仍用 MIC 或 VOICE_COMMUNICATION，但
 *    try 先 startBluetoothSco，失败则降级并 warn，不抛异常
 * D7 ASR 恢复后重置 lastAudioDataTime：resumeFromAsr 成功初始化 AudioRecord
 *    时顺便把健康检查计时也清零，防止 2s 内就误判超时重启
 * A+ 新增 ACTION_DIAGNOSE 广播：接收到就发送一条一次性诊断广播（含
 *    引擎/授权/帧量/错误统计），供开发者设置页 / QA 抓 logcat 一键诊断
 * A+ 新增每 60 秒"健康状态周期性汇总"日志，便于事后分析
 */
class WakeWordServiceEnhanced : Service() {

    private lateinit var unifiedAudioScheduler: UnifiedAudioScheduler
    private lateinit var audioFocusManager: AudioFocusManager

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val highPriorityExecutor = Dispatchers.Default

    private var audioRecord: AudioRecord? = null
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var isRunning = false
    @Volatile private var isWakeWordDetected = false
    @Volatile private var restartAttempts = 0

    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize: Int by lazy {
        maxOf(
            AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat),
            3840
        )
    }

    private lateinit var engineManager: WakeWordEngineManager
    private val audioBuffer = java.util.concurrent.ConcurrentLinkedDeque<Short>()

    private var lastSceneCheckTime = 0L
    private var currentScene = UnifiedAudioScheduler.AudioScene.FOREGROUND

    private var lastAudioDataTime = 0L
    private var audioDataTimeout = 10_000L
    @Volatile private var isWaitingForAuth = false

    companion object {
        const val ACTION_START = "com.blindpath.wakeword.START"
        const val ACTION_STOP = "com.blindpath.wakeword.STOP"
        const val ACTION_RESTART = "com.blindpath.wakeword.RESTART"
        const val ACTION_PAUSE = "com.blindpath.wakeword.PAUSE"
        const val ACTION_RESUME = "com.blindpath.wakeword.RESUME"
        const val ACTION_WAKE_WORD_DETECTED = "com.blindpath.wakeword.DETECTED"
        const val ACTION_DIAGNOSE = "com.blindpath.wakeword.DIAGNOSE"            // v4.0 新增
        const val ACTION_DIAGNOSE_RESULT = "com.blindpath.wakeword.DIAGNOSE_RESULT"// v4.0 新增
        const val EXTRA_WAKE_WORD = "wake_word"
        const val EXTRA_SCENE = "scene"
        const val EXTRA_DIAG_TEXT = "diag_text"                                    // v4.0 新增

        private const val NOTIFICATION_CHANNEL_ID = "wakeword_service_channel_enhanced"
        private const val NOTIFICATION_ID = 2001

        private const val MAX_RESTART_ATTEMPTS = 10
        private const val RESTART_DELAY_MS = 3000L

        @Volatile
        var isServiceRunning = false
            private set

        @Volatile
        var currentEngineType = "UNKNOWN"
            private set

        // v4.0 周期汇总日志
        private const val HEALTH_LOG_INTERVAL_MS = 60_000L
        private const val TAG = "WakeWordEnhanced"
    }

    override fun onCreate() {
        unifiedAudioScheduler = UnifiedAudioScheduler(applicationContext)
        audioFocusManager = AudioFocusManager(applicationContext)
        super.onCreate()
        Timber.i("$TAG: onCreate (v4.0 Diagnostics)")

        createNotificationChannel()
        acquireWakeLocks()
        initializeEngineManager()
        startHealthCheck()
        startDiagnosticResponder()
        startPeriodicHealthLog()
        observeSceneChanges()

        Timber.i("$TAG: Service created successfully")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                Timber.i("$TAG: Received START action")
                startWakeWordDetection()
            }
            ACTION_STOP -> {
                Timber.i("$TAG: Received STOP action")
                stopWakeWordDetection()
            }
            ACTION_RESTART -> {
                Timber.i("$TAG: Received RESTART action")
                restartService()
            }
            ACTION_PAUSE -> {
                Timber.i("$TAG: ★ Received PAUSE action (ASR needs mic)")
                pauseForAsr()
            }
            ACTION_RESUME -> {
                Timber.i("$TAG: ★ Received RESUME action (ASR done, resuming wake)")
                resumeFromAsr()
            }
            ACTION_DIAGNOSE -> {
                Timber.i("$TAG: Received DIAGNOSE action")
                sendDiagnosticBroadcast()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        Timber.i("$TAG: onDestroy")
        isRunning = false
        isServiceRunning = false
        stopAudioCapture()
        if (::engineManager.isInitialized) {
            engineManager.release()
        }
        releaseWakeLocks()
        serviceScope.cancel()
        sendRestartBroadcast()
        super.onDestroy()
    }

    private fun initializeEngineManager() {
        engineManager = WakeWordEngineManager(this)

        engineManager.onWakeWordDetected = { wakeWord ->
            onWakeWordDetected(wakeWord)
        }

        engineManager.onEngineSwitched = { engineType ->
            Timber.i("$TAG: Engine switched to $engineType")
            currentEngineType = engineType.name
            // v3.2: 授权等待状态复位
            if (engineType != WakeWordEngineManager.EngineType.XF_IFLYTEK) {
                isWaitingForAuth = false
            }
            updateNotification()
            // ★ D5 修复：只要不是 self-managed，重启音频采集 → 刷新 frameLength
            if (isRunning && !engineManager.isCurrentEngineSelfManaged()) {
                Timber.i("$TAG: ★ engine switched, restarting audio capture (frameLength refresh)")
                serviceScope.launch(highPriorityExecutor) {
                    restartAudioCaptureInternal(delayMs = 200)
                }
            }
        }

        val localProps = readCredentialsFromAllSources()
        val baiduAppId = getCredential(BuildConfig.BAIDU_APP_ID, "BAIDU_APP_ID", localProps)
        val baiduApiKey = getCredential(BuildConfig.BAIDU_API_KEY, "BAIDU_API_KEY", localProps)
        val baiduSecretKey = getCredential(BuildConfig.BAIDU_SECRET_KEY, "BAIDU_SECRET_KEY", localProps)
        val xfAppId = getCredential(BuildConfig.IFLYTEK_APP_ID, "IFLYTEK_APP_ID", localProps)
        val xfApiKey = getCredential(BuildConfig.IFLYTEK_API_KEY, "IFLYTEK_API_KEY", localProps)
        val xfApiSecret = getCredential(BuildConfig.IFLYTEK_API_SECRET, "IFLYTEK_API_SECRET", localProps)
        val porcupineAccessKey = getCredential("", "PORCUPINE_ACCESS_KEY", localProps)

        val config = WakeWordEngineManager.EngineConfig(
            primaryEngine = WakeWordEngineManager.EngineType.XF_IFLYTEK,
            fallbackEnabled = true,
            baiduAppId = baiduAppId,
            baiduApiKey = baiduApiKey,
            baiduSecretKey = baiduSecretKey,
            baiduWakeWordAsset = WakeWordConfig.BAIDU_WAKE_WORD_ASSET,
            xfAppId = xfAppId,
            xfApiKey = xfApiKey,
            xfApiSecret = xfApiSecret,
            porcupineAccessKey = porcupineAccessKey,
            porcupineKeywordAsset = WakeWordConfig.PORCUPINE_KEYWORD_ASSET,
            wakeWord = WakeWordConfig.DEFAULT_WAKE_WORD
        )

        engineManager.initialize(config)
        currentEngineType = engineManager.getCurrentEngineType().name

        Timber.i("$TAG: Engine initialized - $currentEngineType")
    }

    // ──────────────────────────────────────────────
    // 启动 / 停止
    // ──────────────────────────────────────────────

    private fun startWakeWordDetection() {
        if (isRunning) {
            Timber.d("$TAG: Already running")
            return
        }
        Timber.i("$TAG: Starting wake word detection")
        isRunning = true
        isServiceRunning = true
        restartAttempts = 0

        startForeground(NOTIFICATION_ID, createNotification())

        val granted = unifiedAudioScheduler.requestAudioResource(
            UnifiedAudioScheduler.AudioModule.WAKE_WORD,
            onGranted = { Timber.i("$TAG: Audio resource granted") },
            onLost = {
                Timber.w("$TAG: Audio resource lost")
                handleAudioResourceLost()
            }
        )
        if (!granted) {
            Timber.w("$TAG: Failed to get audio resource, retrying in 1s")
            serviceScope.launch {
                delay(1000)
                if (isRunning) startWakeWordDetection()
            }
            return
        }

        if (unifiedAudioScheduler.isBluetoothActive()) {
            // D6 修复：try-start SCO，失败不 throw，仅打印 w 然后继续
            tryStartBluetoothSco()
        }

        serviceScope.launch(highPriorityExecutor) {
            try {
                if (engineManager.isCurrentEngineSelfManaged()) {
                    Timber.i("$TAG: Using self-managed engine (Baidu)")
                    engineManager.getCurrentEngine()?.let { engine ->
                        if (engine is BaiduWakeWordDetector) engine.startListening()
                    }
                } else {
                    val engineType = engineManager.getCurrentEngineType()
                    Timber.i("$TAG: ★ Starting manual audio capture (engine=$engineType)")
                    isWaitingForAuth = (engineType == WakeWordEngineManager.EngineType.XF_IFLYTEK)
                    initAudioRecord()
                    startAudioProcessing()
                }
                lastAudioDataTime = System.currentTimeMillis()

            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to start detection")
                handleDetectionError(e)
            }
        }
    }

    private fun stopWakeWordDetection() {
        if (!isRunning) return
        Timber.i("$TAG: Stopping wake word detection")
        isRunning = false
        isServiceRunning = false
        stopAudioCapture()
        unifiedAudioScheduler.releaseAudioResource(UnifiedAudioScheduler.AudioModule.WAKE_WORD)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ──────────────────────────────────────────────
    // D6: 蓝牙 SCO 尽力而为尝试
    // ──────────────────────────────────────────────
    private fun tryStartBluetoothSco() {
        try {
            val audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager
            if (audioManager.isBluetoothScoAvailableOffCall) {
                Timber.i("$TAG: ★ Try startBluetoothSco (BT headset active)")
                audioManager.startBluetoothSco()
                audioManager.isBluetoothScoOn = true
            } else {
                Timber.w("$TAG: Bluetooth SCO not available off-call, continuing with normal mic")
            }
        } catch (e: Exception) {
            Timber.w(e, "$TAG: startBluetoothSco failed, continuing with normal mic path")
        }
    }

    // ──────────────────────────────────────────────
    // 音频采集
    // ──────────────────────────────────────────────

    private fun initAudioRecord() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            throw SecurityException("RECORD_AUDIO permission not granted")
        }
        val audioSource = selectOptimalAudioSource()
        audioRecord = AudioRecord(
            audioSource,
            sampleRate,
            channelConfig,
            audioFormat,
            bufferSize * 2
        )
        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            throw IllegalStateException("AudioRecord initialization failed")
        }
        Timber.i("$TAG: AudioRecord initialized - source=$audioSource, rate=$sampleRate, buffer=$bufferSize")
    }

    private fun selectOptimalAudioSource(): Int {
        return when {
            unifiedAudioScheduler.isTalkBackActive() -> {
                Timber.d("$TAG: Using VOICE_RECOGNITION source (TalkBack active)")
                MediaRecorder.AudioSource.VOICE_RECOGNITION
            }
            unifiedAudioScheduler.isBluetoothActive() -> {
                Timber.d("$TAG: Using VOICE_COMMUNICATION source (Bluetooth active)")
                MediaRecorder.AudioSource.VOICE_COMMUNICATION
            }
            else -> {
                Timber.d("$TAG: Using MIC source")
                MediaRecorder.AudioSource.MIC
            }
        }
    }

    private suspend fun startAudioProcessing() {
        audioRecord?.startRecording()
        Timber.i("$TAG: Audio processing started")

        val buffer = ShortArray(bufferSize)
        var consecutiveEmptyReads = 0

        while (isRunning && !isPausedForAsr && currentCoroutineContext().isActive) {
            try {
                val readSize = audioRecord?.read(buffer, 0, bufferSize) ?: 0
                if (readSize > 0) {
                    lastAudioDataTime = System.currentTimeMillis()
                    consecutiveEmptyReads = 0
                    processAudioBuffer(buffer, readSize)
                } else if (readSize < 0) {
                    Timber.w("$TAG: Audio read error: $readSize")
                    consecutiveEmptyReads++
                    if (consecutiveEmptyReads > 10) {
                        throw RuntimeException("Consecutive audio read errors: $consecutiveEmptyReads")
                    }
                }
                delay(5)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Audio processing error")
                handleDetectionError(e)
                break
            }
        }
        Timber.i("$TAG: Audio processing loop exited (running=${isRunning}, paused=${isPausedForAsr})")
    }

    private fun processAudioBuffer(buffer: ShortArray, size: Int) {
        if (isWakeWordDetected) return
        val engine = engineManager.getCurrentEngine() ?: return

        for (i in 0 until size) audioBuffer.offer(buffer[i])

        val frameLength = when (engine) {
            is EnergyWakeWordDetector -> engine.getFrameLength()
            is PorcupineWakeWordDetector -> engine.getFrameLength()
            is XfWakeWordDetector -> engine.getFrameLength()
            else -> 512
        }

        while (audioBuffer.size >= frameLength && !isWakeWordDetected) {
            val frame = ShortArray(frameLength)
            for (i in 0 until frameLength) frame[i] = audioBuffer.poll() ?: break

            try {
                val detected = when (engine) {
                    is EnergyWakeWordDetector -> engine.process(frame)
                    is PorcupineWakeWordDetector -> engine.process(frame)
                    is XfWakeWordDetector -> {
                        val bytes = ByteArray(frame.size * 2)
                        java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.nativeOrder()).let {
                            for (s in frame) it.putShort(s)
                        }
                        engine.feedAudioData(bytes)
                        false
                    }
                    else -> false
                }
                if (detected) break
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Engine process error")
            }
        }

        val maxBufferSize = frameLength * 4
        while (audioBuffer.size > maxBufferSize) audioBuffer.poll()
    }

    private fun onWakeWordDetected(wakeWord: String) {
        if (isWakeWordDetected) return
        Timber.i("$TAG: Wake word detected - $wakeWord (engine: $currentEngineType)")
        isWakeWordDetected = true

        val intent = Intent(ACTION_WAKE_WORD_DETECTED).apply {
            setPackage(packageName)
            putExtra(EXTRA_WAKE_WORD, wakeWord)
            putExtra(EXTRA_SCENE, currentScene.name)
        }
        sendBroadcast(intent)
        triggerHapticFeedback()

        val cooldownMs = if (currentEngineType == "ENERGY") 10_000L else 3_000L
        serviceScope.launch {
            delay(cooldownMs)
            isWakeWordDetected = false
            Timber.d("$TAG: Resumed listening (cooldown: ${cooldownMs}ms)")
        }
    }

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
            Timber.e(e, "$TAG: Haptic feedback failed")
        }
    }

    private fun stopAudioCapture() {
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            audioBuffer.clear()
            Timber.d("$TAG: Audio capture stopped")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error stopping audio capture")
        }
    }

    // ──────────────────────────────────────────────
    // ASR 暂停/恢复
    // ──────────────────────────────────────────────

    @Volatile
    private var isPausedForAsr = false

    private fun pauseForAsr() {
        if (!isRunning) {
            Timber.w("$TAG: Service not running, skip pause")
            return
        }
        isPausedForAsr = true
        stopAudioCapture()
        unifiedAudioScheduler.releaseAudioResource(UnifiedAudioScheduler.AudioModule.WAKE_WORD)
        Timber.i("$TAG: ★ Paused for ASR (mic released)")
    }

    private fun resumeFromAsr() {
        if (!isPausedForAsr) {
            Timber.d("$TAG: Not paused, skip resume")
            return
        }
        isPausedForAsr = false
        isWakeWordDetected = false

        serviceScope.launch(highPriorityExecutor) {
            delay(300)
            if (isRunning && !isPausedForAsr) {
                Timber.i("$TAG: ★ Resuming wake word detection")
                try {
                    stopAudioCapture()
                    initAudioRecord()
                    startAudioProcessing()
                    // ★ D7 修复：ASR 恢复后重置健康检查计时，防止立即触发超时重启
                    lastAudioDataTime = System.currentTimeMillis()
                    Timber.i("$TAG: ★ Wake word detection resumed (health timer reset)")
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to resume audio capture")
                    handleDetectionError(e)
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // 异常 / 资源丢失 / 重启
    // ──────────────────────────────────────────────

    private fun handleAudioResourceLost() {
        Timber.w("$TAG: Audio resource lost, attempting to regain...")
        serviceScope.launch {
            delay(500)
            if (isRunning) {
                val regained = unifiedAudioScheduler.requestAudioResource(
                    UnifiedAudioScheduler.AudioModule.WAKE_WORD,
                    onGranted = { Timber.i("$TAG: Audio resource regained") },
                    onLost = { handleAudioResourceLost() }
                )
                if (!regained) {
                    Timber.w("$TAG: Cannot regain audio resource, pausing...")
                }
            }
        }
    }

    private fun handleDetectionError(error: Exception) {
        Timber.e(error, "$TAG: Detection error")
        restartAttempts++
        if (restartAttempts <= MAX_RESTART_ATTEMPTS) {
            Timber.i("$TAG: Attempting restart ($restartAttempts/$MAX_RESTART_ATTEMPTS)")
            isRunning = false
            isServiceRunning = false
            stopAudioCapture()
            serviceScope.launch {
                delay(RESTART_DELAY_MS)
                startWakeWordDetection()
            }
        } else {
            Timber.e("$TAG: Max restart attempts reached, stopping service")
            stopWakeWordDetection()
        }
    }

    private fun restartService() {
        Timber.i("$TAG: ★ Restarting service (ACTION_RESTART)")
        isRunning = false
        isServiceRunning = false
        stopAudioCapture()
        restartAttempts = 0
        serviceScope.launch {
            delay(1000)
            startWakeWordDetection()
        }
    }

    private fun restartAudioCaptureInternal(delayMs: Long = 500) {
        serviceScope.launch {
            stopAudioCapture()
            if (delayMs > 0) delay(delayMs)
            try {
                initAudioRecord()
                startAudioProcessing()
                // 任何原因重启音频采集都一起 reset lastAudioDataTime
                lastAudioDataTime = System.currentTimeMillis()
                Timber.i("$TAG: Audio capture restarted ok")
            } catch (e: Exception) {
                Timber.e(e, "$TAG: restartAudioCaptureInternal failed")
                handleDetectionError(e)
            }
        }
    }

    // ──────────────────────────────────────────────
    // 健康检查 + 诊断
    // ──────────────────────────────────────────────

    private fun startHealthCheck() {
        serviceScope.launch {
            while (isActive) {
                delay(5000)
                if (isRunning) {
                    when {
                        isPausedForAsr -> {
                            // ASR 占用麦克风
                        }
                        isWaitingForAuth -> {
                            val engine = engineManager.getCurrentEngine()
                            if (engine is XfWakeWordDetector && engine.isAuthComplete()) {
                                isWaitingForAuth = false
                                Timber.i("$TAG: ★ XF auth complete, health check resumed")
                                lastAudioDataTime = System.currentTimeMillis()
                            }
                        }
                        else -> {
                            val timeSinceLastData = System.currentTimeMillis() - lastAudioDataTime
                            if (timeSinceLastData > audioDataTimeout) {
                                Timber.w("$TAG: No audio data for ${timeSinceLastData}ms, restarting...")
                                handleDetectionError(RuntimeException("Audio data timeout"))
                            }
                        }
                    }
                }
            }
        }
    }

    private fun observeSceneChanges() {
        serviceScope.launch {
            unifiedAudioScheduler.audioState.collect { state ->
                val newScene = state.currentScene
                if (newScene != currentScene) {
                    Timber.i("$TAG: Scene changed from $currentScene to $newScene")
                    currentScene = newScene
                    if (isRunning && !engineManager.isCurrentEngineSelfManaged()) {
                        restartAudioCaptureInternal(delayMs = 500)
                    }
                }
            }
        }
    }

    /** v4.0: 收到 DIAGNOSE 广播时响应一条诊断快照 */
    private fun startDiagnosticResponder() {
        serviceScope.launch {
            // 没什么特别，ACTION_DIAGNOSE 进入 onStartCommand 直接发送即可
        }
    }

    /** v4.0: 每 60 秒打一条完整健康汇总日志，便于 QA/事后定位 */
    private fun startPeriodicHealthLog() {
        serviceScope.launch {
            while (isActive) {
                delay(HEALTH_LOG_INTERVAL_MS)
                try {
                    val diag = runCatching { engineManager.generateDiagnosticSnapshot() }
                        .getOrElse { "diag-error: ${it.message}" }
                    val dataAge = System.currentTimeMillis() - lastAudioDataTime
                    Timber.i("$TAG: [HEALTH-60s] running=$isRunning, paused=$isPausedForAsr, " +
                            "wakeFrozen=$isWakeWordDetected, engine=$currentEngineType, " +
                            "dataAgeMs=$dataAge, restarts=$restartAttempts, buffer=${audioBuffer.size} | $diag")
                } catch (t: Throwable) {
                    Timber.w("$TAG: [HEALTH-60s] log error: ${t.message}")
                }
            }
        }
    }

    /** v4.0: 构造并一次性发送诊断广播结果 */
    private fun sendDiagnosticBroadcast() {
        val engineDiag = runCatching { engineManager.generateDiagnosticSnapshot() }
            .getOrElse { "diag-exception: ${it.message}" }
        val dataAge = System.currentTimeMillis() - lastAudioDataTime
        val sb = buildString {
            append("WakeWordServiceEnhanced{\n")
            append("  isRunning=").append(isRunning).append('\n')
            append("  isPausedForAsr=").append(isPausedForAsr).append('\n')
            append("  currentEngine=").append(currentEngineType).append('\n')
            append("  restartAttempts=").append(restartAttempts).append('/').append(MAX_RESTART_ATTEMPTS).append('\n')
            append("  lastAudioDataAgeMs=").append(dataAge).append('\n')
            append("  audioBufferSize=").append(audioBuffer.size).append('\n')
            append("  audioSourceSel=").append(
                when {
                    unifiedAudioScheduler.isTalkBackActive() -> "VOICE_RECOGNITION"
                    unifiedAudioScheduler.isBluetoothActive() -> "VOICE_COMMUNICATION"
                    else -> "MIC"
                }
            ).append('\n')
            append("  scene=").append(currentScene).append('\n')
            append("  manager=").append(engineDiag).append('\n')
            append("}")
        }
        Timber.i("$TAG: ★ [DIAGNOSE] sending:\n$sb")
        val out = Intent(ACTION_DIAGNOSE_RESULT).apply {
            setPackage(packageName)
            putExtra(EXTRA_DIAG_TEXT, sb.toString())
        }
        sendBroadcast(out)
    }

    // ──────────────────────────────────────────────
    // WakeLock / 通知 / 凭证
    // ──────────────────────────────────────────────

    private fun acquireWakeLocks() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BlindPath::WakeWordWakeLockEnhanced"
        ).apply {
            setReferenceCounted(false)
            acquire(60 * 60 * 1000L)
        }
        Timber.d("$TAG: WakeLock acquired")
    }

    private fun releaseWakeLocks() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Timber.d("$TAG: WakeLock released")
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "语音唤醒服务（增强版）",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "保持语音唤醒功能在后台持续运行（高优先级防止系统杀死）"
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    private fun createNotification(): Notification {
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName)
            ?: Intent().apply { setClassName(packageName, "${packageName}.MainActivity") }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, launchIntent, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("助盲智行")
            .setContentText(buildNotificationText())
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private fun buildNotificationText(): String = when (currentEngineType) {
        "BAIDU" -> "语音唤醒服务运行中 [百度引擎]"
        "XF_IFLYTEK" -> "语音唤醒服务运行中 [讯飞引擎]"
        "ENERGY" -> "语音唤醒运行中 [声音检测] - 唤醒词引擎未配置，注视屏幕或用语音指令"
        else -> "语音唤醒服务运行中 [$currentEngineType]"
    }

    private fun updateNotification() {
        if (isRunning) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, createNotification())
        }
    }

    private fun sendRestartBroadcast() {
        val intent = Intent(ACTION_RESTART).apply { setPackage(packageName) }
        sendBroadcast(intent)
    }

    private fun readCredentialsFromAllSources(): Map<String, String> {
        val creds = mutableMapOf<String, String>()
        try {
            assets.open("credentials.properties").use { stream ->
                val props = Properties()
                props.load(stream)
                props.forEach { k, v -> creds[k.toString()] = v.toString() }
                Timber.d("$TAG: Loaded credentials from assets (${creds.size} values)")
            }
        } catch (_: Exception) {}
        return creds
    }

    private fun getCredential(buildConfigValue: String, propertyName: String, localProps: Map<String, String>): String {
        if (buildConfigValue.isNotBlank()) return buildConfigValue
        return localProps[propertyName] ?: ""
    }
}
