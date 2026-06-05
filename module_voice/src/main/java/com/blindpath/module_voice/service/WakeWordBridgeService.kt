package com.blindpath.module_voice.service

import android.app.Service
import android.content.Intent
import android.os.IBinder
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
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        Timber.i("$TAG: Bridge service created, Hilt injection available")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_WAKE_WORD_BRIDGE -> handleWakeWordDetected(intent)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handleWakeWordDetected(intent: Intent) {
        val wakeWord = intent.getStringExtra(EXTRA_WAKE_WORD) ?: WakeWordConfig.DEFAULT_WAKE_WORD

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

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            CHANNEL_ID,
            "唤醒词桥接服务",
            android.app.NotificationManager.IMPORTANCE_MIN
        ).apply {
            description = "桥接唤醒词信号到语音指令识别器"
            setShowBadge(false)
        }

        val notificationManager = getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.createNotificationChannel(channel)
    }
}
