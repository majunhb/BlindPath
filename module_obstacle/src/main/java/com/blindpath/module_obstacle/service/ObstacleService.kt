package com.blindpath.module_obstacle.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blindpath.base.common.AlertLevel
import com.blindpath.base.tts.VibrationHelper
import com.blindpath.module_obstacle.data.ObstacleRepositoryImpl
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

/**
 * 避障前台服务
 * 在后台持续运行摄像头和AI检测，发现障碍物时通过语音+振动提醒视障用户
 */
@AndroidEntryPoint
class ObstacleService : Service() {

    @Inject
    lateinit var obstacleRepository: ObstacleRepositoryImpl

    @Inject
    lateinit var voiceRepository: VoiceRepository

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false

    private var lastAlertMessage: String? = null
    private var lastAlertTime = 0L
    private val alertRepeatMinInterval = 3000L

    companion object {
        const val ACTION_START = "com.blindpath.action.START_OBSTACLE"
        const val ACTION_STOP = "com.blindpath.action.STOP_OBSTACLE"
        private const val NOTIFICATION_ID = 1001

        const val CHANNEL_OBSTACLE = "channel_obstacle"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            voiceRepository.initialize()
            voiceRepository.speak("障碍物检测已开启", queueMode = false)
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startObstacle()
            ACTION_STOP -> stopObstacle()
        }
        return START_STICKY
    }

    private fun startObstacle() {
        if (isRunning) return

        isRunning = true

        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            try {
                val result = obstacleRepository.startDetection()
                if (result !is com.blindpath.base.common.Result.Success) {
                    voiceRepository.speak("摄像头启动失败，请检查权限", queueMode = false)
                    stopObstacle()
                    return@launch
                }

                obstacleRepository.obstacleState.collectLatest { state ->
                    try {
                        val alertText = state.currentAlert?.description ?: "正在检测障碍物"
                        updateNotification(alertText)

                        state.currentAlert?.let { alert ->
                            handleAlert(alert.level, alert.description)
                        }
                    } catch (e: Exception) {
                        timber.log.Timber.e(e, "Error processing obstacle state")
                    }
                }
            } catch (e: Exception) {
                timber.log.Timber.e(e, "Obstacle detection failed")
                voiceRepository.speak("障碍物检测异常", queueMode = false)
                stopObstacle()
            }
        }
    }

    private fun stopObstacle() {
        isRunning = false
        VibrationHelper.cancel(this)

        serviceScope.launch {
            obstacleRepository.stopDetection()
            voiceRepository.speak("障碍物检测已关闭", queueMode = false)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 处理障碍物预警：语音播报 + 振动反馈
     */
    private fun handleAlert(level: AlertLevel, description: String) {
        val currentTime = System.currentTimeMillis()

        if (description == lastAlertMessage && currentTime - lastAlertTime < alertRepeatMinInterval) {
            return
        }

        lastAlertMessage = description
        lastAlertTime = currentTime

        // 立即停止当前播报，播报预警
        serviceScope.launch {
            voiceRepository.speakObstacleAlert(description)
        }

        if (level != AlertLevel.SAFE) {
            VibrationHelper.vibrate(this, level)
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_OBSTACLE)
            .setContentTitle("避障功能运行中")
            .setContentText("正在为您检测周围障碍物")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_OBSTACLE)
            .setContentTitle("避障功能运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_OBSTACLE,
                "避障预警",
                android.app.NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "视障人士避障预警信息"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }

            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        VibrationHelper.cancel(this)
    }
}
