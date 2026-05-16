package com.blindpath.module_navigation.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blindpath.module_navigation.data.NavigationRepositoryImpl
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import javax.inject.Inject

/**
 * 导航前台服务
 * 持续定位并通过语音播报导航指令
 */
@AndroidEntryPoint
class NavigationService : Service() {

    @Inject
    lateinit var navigationRepository: NavigationRepositoryImpl

    @Inject
    lateinit var voiceRepository: VoiceRepository

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false

    private var lastInstruction: String? = null
    private var lastKnownDistance = Int.MAX_VALUE
    private var lastLocationUpdate = 0L

    companion object {
        const val ACTION_START = "com.blindpath.action.START_NAVIGATION"
        const val ACTION_STOP = "com.blindpath.action.STOP_NAVIGATION"
        private const val NOTIFICATION_ID = 1002

        const val CHANNEL_NAVIGATION = "channel_navigation"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        serviceScope.launch {
            voiceRepository.initialize()
        }
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

        val notification = createNotification("正在定位...")
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            try {
                val result = navigationRepository.startNavigation()
                if (result !is com.blindpath.base.common.Result.Success) {
                    voiceRepository.speak("定位启动失败，请检查定位权限", queueMode = false)
                    stopNavigation()
                    return@launch
                }

                navigationRepository.navigationState.collectLatest { state ->
                    try {
                        val navText = state.currentInfo?.instruction ?: "定位中..."

                        // 更新通知（限流）
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastLocationUpdate > 5000) {
                            updateNotification(navText)
                            lastLocationUpdate = currentTime
                        }

                        state.currentInfo?.let { info ->
                            speakNavigation(info.instruction, info.remainingDistance)
                        }

                        if (state.currentLocation != null && state.isLocationAvailable) {
                            val accuracy = state.currentLocation.accuracy.toInt()
                            voiceRepository.speak("当前定位精度${accuracy}米", queueMode = true)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing navigation state")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Navigation failed")
                voiceRepository.speak("导航异常", queueMode = false)
                stopNavigation()
            }
        }
    }

    private fun stopNavigation() {
        isRunning = false

        serviceScope.launch {
            navigationRepository.stopNavigation()
            voiceRepository.speak("导航已关闭", queueMode = false)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 播报导航指令（防重复）
     */
    private fun speakNavigation(instruction: String, remainingDistance: Int) {
        val distanceChanged = kotlin.math.abs(remainingDistance - lastKnownDistance) >= 5

        if (lastInstruction != instruction || distanceChanged) {
            lastInstruction = instruction
            lastKnownDistance = remainingDistance
            serviceScope.launch {
                voiceRepository.speakNavigation(instruction)
            }
        }
    }

    private fun createNotification(text: String): Notification {
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
