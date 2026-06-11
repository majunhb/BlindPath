package com.blindpath.module_voice.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import dagger.hilt.android.AndroidEntryPoint
import com.blindpath.module_voice.domain.model.WakeWordConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 唤醒词桥接服务
 *
 * ★★★ 修复 v2-2 核心文件
 *
 * 问题根因：
 * WakeWordReceiver 是静态注册的 BroadcastReceiver（在 AndroidManifest.xml 中声明），
 * @AndroidEntryPoint + @Inject 在静态 BR 上不生效，voiceCommandRepository 永远为 null。
 * 即使百度/讯飞引擎检测到"小智小智"，广播也接不通到 VoiceCommandRepository。
 *
 * 解决方案：
 * 新增运行在主进程的 BridgeService 作为中间层：
 * WakeWordReceiver（无注入）→ Intent → WakeWordBridgeService（Hilt注入）→ triggerWakeWordDetected()
 *
 * 架构说明：
 * - 本服务运行在主进程（默认），可以正常使用 Hilt 依赖注入
 * - 接收 WakeWordReceiver 转发的唤醒词信号
 * - 通过注入的 VoiceCommandRepository 触发指令识别模式
 * - 使用 startForeground() 确保不被系统杀死
 */
@AndroidEntryPoint
class WakeWordBridgeService : Service() {

    @Inject
    lateinit var voiceCommandRepository: com.blindpath.module_voice.domain.VoiceCommandRepository

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    companion object {
        const val ACTION_WAKE_WORD_BRIDGE = "com.blindpath.wakeword.BRIDGE"
        const val EXTRA_WAKE_WORD = "wake_word"

        private const val TAG = "WakeWordBridge"
        private const val NOTIFICATION_ID = 1002
        private const val CHANNEL_ID = "wakeword_bridge_channel"
        private const val DEBOUNCE_INTERVAL_MS = 3000L // 3秒防抖间隔
    }

    @Volatile
    private var lastWakeWordTime: Long = 0L

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.i("$TAG: Bridge service created, Hilt injection available")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // 修复 ForegroundServiceDidNotStartInTimeException：
        // Android 12+ 要求前台服务必须在启动后 5 秒内调用 startForeground()
        // 因此在 onStartCommand 入口处立即调用
        startForeground(NOTIFICATION_ID, buildNotification())
        Timber.i("$TAG: startForeground called immediately in onStartCommand")

        when (intent?.action) {
            ACTION_WAKE_WORD_BRIDGE -> handleWakeWordDetected(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleWakeWordDetected(intent: Intent) {
        val wakeWord = intent.getStringExtra(EXTRA_WAKE_WORD) ?: WakeWordConfig.DEFAULT_WAKE_WORD

        // 防抖机制：两次唤醒间隔至少 3 秒
        val currentTime = System.currentTimeMillis()
        val timeSinceLastWake = currentTime - lastWakeWordTime
        if (timeSinceLastWake < DEBOUNCE_INTERVAL_MS) {
            Timber.d("$TAG: Wake word debounced. Time since last: ${timeSinceLastWake}ms")
            return
        }
        lastWakeWordTime = currentTime

        Timber.i("$TAG: ★★★ Bridging wake word to VoiceCommandRepository - $wakeWord")

        // 通过 Hilt 注入的 repository 触发唤醒词检测
        // 这里 voiceCommandRepository 不再是 null（因为本服务在主进程中运行，@AndroidEntryPoint 正常工作）
        try {
            voiceCommandRepository.triggerWakeWordDetected(wakeWord)
            Timber.i("$TAG: Successfully triggered command recognition for wake word: $wakeWord")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to trigger command recognition")
        }
    }

    /**
     * 构建前台服务通知
     * Android 12+ (API 31+) 对前台服务通知有更严格的要求，
     * 必须使用 Notification.FOREGROUND_SERVICE_IMMEDIATE 或等效行为。
     */
    private fun buildNotification(): Notification {
        // 构建点击通知后跳转的 PendingIntent（指向应用主入口）
        val contentIntent = packageManager?.getLaunchIntentForPackage(packageName)?.let { launchIntent ->
            PendingIntent.getActivity(
                this,
                0,
                launchIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("语音唤醒服务运行中")
            .setContentText("正在监听唤醒词...")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)

        // Android 12+ 前台服务通知特殊处理
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            builder.setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
        }

        return builder.build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "唤醒词桥接服务",
            NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "桥接唤醒词信号到语音指令识别器"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
