package com.blindpath.module_navigation.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blindpath.module_navigation.data.NavigationRepositoryImpl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import javax.inject.Inject

/**
 * 导航前台服务
 */
@AndroidEntryPoint
class NavigationService : Service() {

    @Inject
    lateinit var navigationRepository: NavigationRepositoryImpl

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false

    companion object {
        const val ACTION_START = "com.blindpath.action.START_NAVIGATION"
        const val ACTION_STOP = "com.blindpath.action.STOP_NAVIGATION"
        private const val NOTIFICATION_ID = 1002

        // 通知渠道ID（需要与 Application 中定义的保持一致）
        const val CHANNEL_NAVIGATION = "channel_navigation"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startNavigation()
            ACTION_STOP -> stopNavigation()
        }
        return START_STICKY
    }

    private fun startNavigation() {
        if (isRunning) return

        isRunning = true

        val notification = createNotification("正在规划路线")
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            navigationRepository.startNavigation()

            navigationRepository.navigationState.collectLatest { state ->
                if (!state.isRunning) {
                    stopSelf()
                }

                val navText = state.currentInfo?.instruction ?: "定位中..."
                updateNotification(navText)
            }
        }
    }

    private fun stopNavigation() {
        isRunning = false

        serviceScope.launch {
            navigationRepository.stopNavigation()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(text: String): Notification {
        // 创建通知渠道（如果尚未创建）
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_NAVIGATION)
            .setContentTitle("导航功能运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_NAVIGATION)
            .setContentTitle("导航功能运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_NAVIGATION,
                "导航指引",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "步行导航指引信息"
                setSound(null, null)
            }

            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}
