package com.blindpath.module_navigation.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blindpath.module_navigation.data.GpsQuality
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
 *
 * Phase 1 改进：
 * 1. GPS 质量分级语音反馈（EXCELLENT/GOOD/FAIR/POOR）
 * 2. 精度播报改为"GPS精度X米，可安全导航"格式
 * 3. 信号弱时主动提醒用户
 * 4. 导航指令按距离段播报（不再每5米报一次）
 */
@AndroidEntryPoint
class NavigationService : Service() {

    @Inject
    lateinit var navigationRepository: NavigationRepositoryImpl

    @Inject
    lateinit var voiceRepository: VoiceRepository

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false

    // 防重复播报
    private var lastInstruction: String? = null
    private var lastKnownDistance = Int.MAX_VALUE
    private var lastGpsQualityAnnouncement: GpsQuality? = null
    private var lastLocationUpdate = 0L

    // GPS 精度播报节流（避免频繁播报）
    private var lastAccuracyAnnounceTime = 0L
    private val ACCURACY_ANNOUNCE_INTERVAL_MS = 8000L // 8秒内不重复报精度

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

        // 启动前台通知
        val notification = createNotification("正在定位...")
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            try {
                // 启动高精度定位
                val result = navigationRepository.startNavigation()
                if (result !is com.blindpath.base.common.Result.Success) {
                    voiceRepository.speak("定位启动失败，请检查定位权限", queueMode = false)
                    stopNavigation()
                    return@launch
                }

                // 首次启动语音提示
                voiceRepository.speak("高精度定位已启动，请稍候", queueMode = false)

                // 监听导航状态
                navigationRepository.navigationState.collectLatest { state ->
                    try {
                        // 更新通知（限流）
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastLocationUpdate > 5000) {
                            val navText = state.currentInfo?.instruction ?: "定位中..."
                            updateNotification(navText)
                            lastLocationUpdate = currentTime
                        }

                        // 播报导航指令
                        state.currentInfo?.let { info ->
                            speakNavigation(info.instruction, info.remainingDistance)
                        }

                        // ★ GPS 质量分级语音播报（限流）
                        if (state.currentLocation != null && state.isLocationAvailable) {
                            val accuracy = state.currentLocation.accuracy
                            val quality = evaluateGpsQuality(accuracy)
                            announceGpsQualityIfNeeded(quality, accuracy)

                            // 信号弱时主动提醒
                            if (quality == GpsQuality.POOR) {
                                announceWeakSignal()
                            }
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
            voiceRepository.speak("导航已关闭，祝您平安", queueMode = false)
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * ★ GPS 质量分级评估（使用 GpsQuality.fromAccuracy() 静态方法）
     */
    private fun evaluateGpsQuality(accuracy: Float): GpsQuality {
        return GpsQuality.fromAccuracy(accuracy)
    }

    /**
     * ★ GPS 质量语音播报（带节流，避免重复）
     */
    private fun announceGpsQualityIfNeeded(quality: GpsQuality, accuracy: Float) {
        val now = System.currentTimeMillis()

        // 节流：8秒内不重复播报相同质量等级
        if (quality == lastGpsQualityAnnouncement && now - lastAccuracyAnnounceTime < ACCURACY_ANNOUNCE_INTERVAL_MS) {
            return
        }

        lastGpsQualityAnnouncement = quality
        lastAccuracyAnnounceTime = now

        val announcement = when (quality) {
            GpsQuality.EXCELLENT -> "GPS精度${String.format("%.1f", accuracy)}米，信号优秀，可安全导航"
            GpsQuality.GOOD -> "GPS精度${String.format("%.1f", accuracy)}米，信号良好"
            GpsQuality.FAIR -> "GPS精度${String.format("%.1f", accuracy)}米，信号一般，请注意安全"
            GpsQuality.POOR -> "GPS信号弱，精度${String.format("%.1f", accuracy)}米，请在开阔地带重新定位"
        }

        serviceScope.launch {
            voiceRepository.speak(announcement, queueMode = true)
        }

        Timber.d("GPS quality announced: $announcement")
    }

    /**
     * 信号弱时主动提醒（仅首次检测到弱信号时播报）
     */
    private var hasAnnouncedWeakSignal = false

    private fun announceWeakSignal() {
        if (!hasAnnouncedWeakSignal) {
            hasAnnouncedWeakSignal = true
            serviceScope.launch {
                voiceRepository.speak("警告：GPS信号弱，可能影响定位精度，请在开阔地带重新获取信号", queueMode = false)
            }
        }
    }

    /**
     * 播报导航指令（防重复）
     * 触发条件：指令变化 OR 距离变化 ≥ 3米
     */
    private fun speakNavigation(instruction: String, remainingDistance: Int) {
        val distanceChanged = kotlin.math.abs(remainingDistance - lastKnownDistance) >= 3

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
            .setContentTitle("视障导航运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)   // 提高优先级
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_NAVIGATION)
            .setContentTitle("视障导航运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_NAVIGATION,
                "视障导航",
                android.app.NotificationManager.IMPORTANCE_HIGH   // 提高通知优先级
            ).apply {
                description = "视障人员步行导航指引，GPS 高精度定位"
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
