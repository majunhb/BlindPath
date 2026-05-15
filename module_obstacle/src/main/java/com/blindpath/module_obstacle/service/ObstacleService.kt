package com.blindpath.module_obstacle.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blindpath.module_obstacle.data.ObstacleRepositoryImpl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import javax.inject.Inject

/**
 * 避障前台服务
 * 在后台持续运行摄像头和AI检测
 */
@AndroidEntryPoint
class ObstacleService : Service() {

    @Inject
    lateinit var obstacleRepository: ObstacleRepositoryImpl

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false

    companion object {
        const val ACTION_START = "com.blindpath.action.START_OBSTACLE"
        const val ACTION_STOP = "com.blindpath.action.STOP_OBSTACLE"
        private const val NOTIFICATION_ID = 1001

        // 通知渠道ID（需要与 Application 中定义的保持一致）
        const val CHANNEL_OBSTACLE = "channel_obstacle"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("ObstacleService created")
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
        Timber.d("Starting obstacle service")

        // 创建通知渠道（如果尚未创建）
        createNotificationChannel()

        // 启动前台服务
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        // 启动避障检测
        serviceScope.launch {
            obstacleRepository.startDetection()

            // 监听状态变化
            obstacleRepository.obstacleState.collectLatest { state ->
                if (!state.isRunning) {
                    stopSelf()
                }

                // 更新通知
                val alertText = state.currentAlert?.description ?: "正在检测障碍物"
                updateNotification(alertText)
            }
        }
    }

    private fun stopObstacle() {
        Timber.d("Stopping obstacle service")
        isRunning = false

        serviceScope.launch {
            obstacleRepository.stopDetection()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
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
        Timber.d("ObstacleService destroyed")
    }
}
