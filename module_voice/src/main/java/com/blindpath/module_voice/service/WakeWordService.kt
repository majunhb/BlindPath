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
    
    // 音频参数
    private val sampleRate = 16000
    private val channelConfig = AudioFormat.CHANNEL_IN_MONO
    private val audioFormat = AudioFormat.ENCODING_PCM_16BIT
    private val bufferSize: Int by lazy {
        AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
    }
    
    // 唤醒词检测器（后续替换为Porcupine/Snowboy）
    private var wakeWordDetector: WakeWordDetector? = null
    
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
        acquireWakeLock()
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
        serviceScope.cancel()
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
        // 简单的能量检测（后续替换为真正的唤醒词检测）
        val energy = calculateEnergy(buffer, size)
        
        // 检测阈值（临时方案）
        val threshold = 1000
        
        if (energy > threshold && !isWakeWordDetected) {
            // 模拟唤醒词检测成功
            // 实际应该调用 Porcupine/Snowboy 进行检测
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
 * 后续实现：Porcupine 或 Snowboy
 */
interface WakeWordDetector {
    fun process(audioData: ShortArray): Boolean
    fun release()
}
