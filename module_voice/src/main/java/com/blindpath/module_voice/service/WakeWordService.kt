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
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject

/**
 * 语音唤醒常驻服务
 *
 * 功能：
 * - 前台服务，保活唤醒词检测
 * - 使用 Porcupine 引擎进行唤醒词检测
 * - 16kHz采样率音频采集
 * - 低功耗运行（锁屏时降低采样频率）
 * - 支持蓝牙耳机/骨传导耳机
 * - 与ASR模块协调，避免冲突
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

    // 音频参数（Porcupine 要求 16kHz, 16-bit, 单声道）
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    }

    // Porcupine 唤醒词检测器
    private var porcupineDetector: PorcupineWakeWordDetector? = null

    // 音频缓冲区（用于累积 Porcupine 所需的帧）
    private val audioBuffer = ArrayList<Short>(512)

    companion object {
        const val ACTION_START = "com.blindpath.wakeword.START"
        const val ACTION_STOP = "com.blindpath.wakeword.STOP"
        const val ACTION_WAKE_WORD_DETECTED = "com.blindpath.wakeword.DETECTED"
        const val EXTRA_WAKE_WORD = "wake_word"

        private const val NOTIFICATION_CHANNEL_ID = "wakeword_service_channel"
        private const val NOTIFICATION_ID = 1001

        // TODO: 从配置文件或 BuildConfig 读取
        const val PORCUPINE_ACCESS_KEY = "YOUR_ACCESS_KEY_HERE"

        @Volatile
        var isServiceRunning = false
            private set
    }

    override fun onCreate() {
        super.onCreate()
        Timber.i("WakeWordService: onCreate")
        createNotificationChannel()
        acquireWakeLock()
        initializePorcupine()
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
        porcupineDetector?.release()
        serviceScope.cancel()
    }

    private fun initializePorcupine() {
        try {
            // TODO: 替换为实际的 AccessKey
            if (PORCUPINE_ACCESS_KEY == "YOUR_ACCESS_KEY_HERE") {
                Timber.w("WakeWordService: Porcupine AccessKey not configured, using energy-based detection")
                return
            }

            porcupineDetector = PorcupineWakeWordDetector(
                context = this,
                accessKey = PORCUPINE_ACCESS_KEY,
                // TODO: 添加自定义中文唤醒词模型路径
                // keywordPath = "小智小智.ppn",
                // modelPath = "porcupine_params_zh.pv",
                sensitivity = 0.7f,
                onWakeWordDetected = { keyword ->
                    onWakeWordDetected(keyword)
                }
            )
            Timber.i("WakeWordService: Porcupine initialized")
        } catch (e: Exception) {
            Timber.e(e, "WakeWordService: Failed to initialize Porcupine, falling back to energy detection")
        }
    }

    private fun startWakeWordDetection() {
        if (isRunning) {
            Timber.d("WakeWordService: Already running")
            return
        }

        Timber.i("WakeWordService: Starting wake word detection")
        isRunning = true
        isServiceRunning = true

        // 启动前台服务
        startForeground(NOTIFICATION_ID, createNotification())

        // 请求音频焦点
        audioFocusManager.requestFocus("wakeword", priority = 10)

        // 切换到蓝牙耳机（如果已连接）
        if (audioFocusManager.isBluetoothHeadsetConnected()) {
            audioFocusManager.switchToBluetoothSco()
        }

        // 初始化并启动唤醒词检测
        serviceScope.launch {
            try {
                initAudioRecord()
                startAudioProcessing()
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

        // 清空音频缓冲区
        audioBuffer.clear()

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
        Timber.i("WakeWordService: Audio processing started")

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
        if (porcupineDetector != null) {
            // 使用 Porcupine 进行唤醒词检测
            processWithPorcupine(buffer, size)
        } else {
            // 降级方案：使用能量检测
            processWithEnergyDetection(buffer, size)
        }
    }

    private fun processWithPorcupine(buffer: ShortArray, size: Int) {
        // 将音频数据添加到缓冲区
        for (i in 0 until size) {
            audioBuffer.add(buffer[i])
        }

        // Porcupine 需要固定长度的帧（通常是512 samples）
        val frameLength = porcupineDetector?.getFrameLength() ?: 512

        // 处理缓冲区中完整的帧
        while (audioBuffer.size >= frameLength && !isWakeWordDetected) {
            val frame = ShortArray(frameLength)
            for (i in 0 until frameLength) {
                frame[i] = audioBuffer.removeAt(0)
            }

            val detected = porcupineDetector?.process(frame) ?: false
            if (detected) {
                // 唤醒词检测成功，onWakeWordDetected 回调会被触发
                break
            }
        }

        // 防止缓冲区无限增长（保留最多2帧的缓冲）
        val maxBufferSize = frameLength * 2
        while (audioBuffer.size > maxBufferSize) {
            audioBuffer.removeAt(0)
        }
    }

    private fun processWithEnergyDetection(buffer: ShortArray, size: Int) {
        // 简单的能量检测（降级方案）
        val energy = calculateEnergy(buffer, size)
        val threshold = 1000

        if (energy > threshold && !isWakeWordDetected) {
            onWakeWordDetected("小布")
        }
    }

    private fun calculateEnergy(buffer: ShortArray, size: Int): Double {
        var sum = 0.0
        for (i in 0 until size) {
            sum += buffer[i] * buffer[i]
        }
        return kotlin.math.sqrt(sum / size)
    }

    private fun onWakeWordDetected(wakeWord: String) {
        if (isWakeWordDetected) return

        Timber.i("WakeWordService: Wake word detected - $wakeWord")
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
        val launchIntent = packageManager.getLaunchIntentForPackage(packageName) ?: Intent().apply {
            setClassName(packageName, "${packageName}.MainActivity")
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("助盲智行")
            .setContentText("语音唤醒服务运行中")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    private fun acquireWakeLock() {
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = powerManager.newWakeLock(
            PowerManager.PARTIAL_WAKE_LOCK,
            "BlindPath::WakeWordWakeLock"
        ).apply {
            setReferenceCounted(false)
        }
        wakeLock?.acquire(10 * 60 * 1000L) // 10分钟
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
    fun process(audioData: ShortArray): Boolean
    fun release()
}
