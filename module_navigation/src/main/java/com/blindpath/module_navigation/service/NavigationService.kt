package com.blindpath.module_navigation.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blindpath.base.config.AppConfig
import com.blindpath.base.error.DegradationManager
import com.blindpath.module_navigation.data.GpsQuality
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import timber.log.Timber
import javax.inject.Inject

/**
 * 导航前台服务
 * 持续定位并通过语音播报导航指令
 *
 * 架构改进：依赖 NavigationRepository 接口而非实现类
 *
 * Phase 1 改进：
 * 1. GPS 质量分级语音反馈（EXCELLENT/GOOD/FAIR/POOR）
 * 2. 精度播报改为"GPS精度X米，可安全导航"格式
 * 3. 信号弱时主动提醒用户
 * 4. 导航指令按距离段播报（不再每5米报一次）
 */
@AndroidEntryPoint
class NavigationService : LifecycleService() {

    @Inject
    lateinit var navigationRepository: NavigationRepository

    @Inject
    lateinit var voiceRepository: VoiceRepository

        // lifecycleScope inherited from LifecycleService
    private var isRunning = false

    // 防重复播报
    private var lastInstruction: String? = null
    private var lastKnownDistance = Int.MAX_VALUE
    private var lastGpsQualityAnnouncement: GpsQuality? = null
    private var lastLocationUpdate = 0L

    // GPS 精度播报节流（引用 AppConfig 集中管理）
    private var lastAccuracyAnnounceTime = 0L
    private val ACCURACY_ANNOUNCE_INTERVAL_MS = AppConfig.Navigation.ACCURACY_ANNOUNCE_INTERVAL_MS
    
    // [修复] 导航指令播报节流
    private var lastInstructionAnnounceTime = 0L
    private val INSTRUCTION_MIN_INTERVAL_MS = AppConfig.Navigation.INSTRUCTION_MIN_INTERVAL_MS

    // [修复] 位置地址播报节流
    private var lastLocationAddress: String? = null
    private var lastAddressAnnounceTime = 0L
    private val ADDRESS_ANNOUNCE_INTERVAL_MS = 30000L // 30秒

    companion object {
        const val ACTION_START = "com.blindpath.action.START_NAVIGATION"
        const val ACTION_STOP = "com.blindpath.action.STOP_NAVIGATION"
        private const val NOTIFICATION_ID = 1002

        const val CHANNEL_NAVIGATION = "channel_navigation"
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return null!!
    }

    override fun onCreate() {
        super.onCreate()
        lifecycleScope.launch {
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

        // 检查 GPS 降级状态
        if (!DegradationManager.isFeatureAvailable(DegradationManager.Feature.GPS_NAVIGATION)) {
            Timber.w("GPS navigation is disabled, cannot start")
            lifecycleScope.launch {
                voiceRepository.speak("导航功能当前不可用，请检查定位权限", queueMode = false)
            }
            return
        }
        if (DegradationManager.isFeatureDegraded(DegradationManager.Feature.GPS_NAVIGATION)) {
            Timber.w("GPS navigation is degraded, starting in reduced mode")
            lifecycleScope.launch {
                voiceRepository.speak("GPS信号较弱，导航精度可能降低", queueMode = false)
            }
        }

        isRunning = true

        // 启动前台通知
        val notification = createNotification("正在定位...")
        startForeground(NOTIFICATION_ID, notification)

        lifecycleScope.launch {
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

                            // [修复] 播报当前位置街道名称（带节流）
                            state.currentLocation?.let { location ->
                                announceLocationAddressIfNeeded(location)
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

        lifecycleScope.launch {
            navigationRepository.stopNavigation()
            voiceRepository.speak("导航已关闭，祝您平安", queueMode = false)
        }

        // [修复] 重置所有播报节流状态
        lastInstruction = null
        lastKnownDistance = Int.MAX_VALUE
        lastGpsQualityAnnouncement = null
        lastAccuracyAnnounceTime = 0L
        lastInstructionAnnounceTime = 0L
        lastWeakSignalAnnounceTime = 0L

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

        lifecycleScope.launch {
            voiceRepository.speak(announcement, queueMode = true)
        }

        Timber.d("GPS quality announced: $announcement")
    }

    /**
     * [修复] 信号弱时主动提醒（每5分钟最多播报一次）
     */
    private var lastWeakSignalAnnounceTime = 0L
    private val WEAK_SIGNAL_INTERVAL_MS = 300000L  // 5分钟

    private fun announceWeakSignal() {
        val now = System.currentTimeMillis()
        if (now - lastWeakSignalAnnounceTime >= WEAK_SIGNAL_INTERVAL_MS) {
            lastWeakSignalAnnounceTime = now
            lifecycleScope.launch {
                voiceRepository.speak("GPS信号弱，请在开阔地带使用", queueMode = false)
            }
        }
    }

    /**
     * [修复] 播报导航指令（防重复 + 时间节流）
     * 触发条件：(指令变化 OR 距离变化 ≥ 阈值) AND 距离上次播报 ≥ 15秒
     */
    private fun speakNavigation(instruction: String, remainingDistance: Int) {
        val now = System.currentTimeMillis()
        val distanceChanged = kotlin.math.abs(remainingDistance - lastKnownDistance) >= AppConfig.Navigation.INSTRUCTION_DISTANCE_THRESHOLD
        val timePassed = now - lastInstructionAnnounceTime >= INSTRUCTION_MIN_INTERVAL_MS
        
        // [修复] 必须同时满足：内容变化/距离变化 + 时间间隔足够
        if ((lastInstruction != instruction || distanceChanged) && timePassed) {
            lastInstruction = instruction
            lastKnownDistance = remainingDistance
            lastInstructionAnnounceTime = now
            lifecycleScope.launch {
                voiceRepository.speakNavigation(instruction)
            }
        }
    }

    /**
     * [修复] 播报当前位置地址信息（街道名称，带节流）
     */
    private fun announceLocationAddressIfNeeded(location: com.blindpath.module_navigation.domain.model.LocationInfo) {
        val now = System.currentTimeMillis()
        if (now - lastAddressAnnounceTime < ADDRESS_ANNOUNCE_INTERVAL_MS) return

        val addressText = buildString {
            append("当前位置：")
            when {
                location.address.isNotBlank() -> append(location.address)
                location.poiName.isNotBlank() -> append("附近${location.poiName}")
                else -> append("未知位置")
            }
        }

        if (addressText != lastLocationAddress) {
            lastLocationAddress = addressText
            lastAddressAnnounceTime = now
            lifecycleScope.launch {
                voiceRepository.speak(addressText, queueMode = true)
            }
            Timber.d("Location address announced: $addressText")
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
    }
}
