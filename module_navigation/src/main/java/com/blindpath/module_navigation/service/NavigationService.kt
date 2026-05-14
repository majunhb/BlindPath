package com.blindpath.module_navigation.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blindpath.app.BlindPathApp
import com.blindpath.app.MainActivity
import com.blindpath.module_navigation.data.NavigationRepositoryImpl
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
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
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Timber.d("NavigationService created")
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
        Timber.d("Starting navigation service")

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
        Timber.d("Stopping navigation service")
        isRunning = false

        serviceScope.launch {
            navigationRepository.stopNavigation()
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, BlindPathApp.CHANNEL_NAVIGATION)
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
        val notification = NotificationCompat.Builder(this, BlindPathApp.CHANNEL_NAVIGATION)
            .setContentTitle("导航功能运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
        Timber.d("NavigationService destroyed")
    }
}
