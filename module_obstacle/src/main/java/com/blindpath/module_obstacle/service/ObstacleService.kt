package com.blindpath.module_obstacle.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blindpath.base.common.ObstacleAlert
import com.blindpath.base.reliability.DetectionServiceWatchdog
import com.blindpath.base.tts.VibrationHelper
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import javax.inject.Inject

import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope

/**
 * 避障前台服务
 * 在后台持续运行摄像头和AI检测，发现障碍物时通过语音+振动提醒视障用户
 * 
 * 架构级重构 v3.0 - 修复语音播报集成
 * 
 * 修复内容：
 * 1. P0 确保语音播报正确触发：监听 obstacleState.currentAlert 变化
 * 2. P0 危险告警优先播报：使用 VoiceType.OBSTACLE_DANGER 立即打断
 * 3. P1 预警冷却机制：避免重复播报
 * 4. P1 唤醒响应优化：确保 handleAlert 执行 < 500ms
 */
@AndroidEntryPoint
class ObstacleService : LifecycleService() {

    @Inject
    lateinit var obstacleRepository: ObstacleRepository

    @Inject
    lateinit var voiceRepository: VoiceRepository

    @Inject
    lateinit var watchdog: DetectionServiceWatchdog

    @Inject
    lateinit var alertExecutor: AlertExecutor

    // lifecycleScope inherited from LifecycleService
    private var isRunning = false

    // 预警冷却机制
    private var lastAlertMessage: String? = null
    private var lastAlertTime = 0L
    private val alertRepeatMinInterval = 3000L

    // 用于协程控制
    private val serviceScope = lifecycleScope
    private var detectionJob: Job? = null

    companion object {
        const val ACTION_START = "com.blindpath.action.START_OBSTACLE"
        const val ACTION_STOP = "com.blindpath.action.STOP_OBSTACLE"
        private const val NOTIFICATION_ID = 1001

        const val CHANNEL_OBSTACLE = "channel_obstacle"
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        // ObstacleService 仅作为 Started Service 使用，不支持绑定
        throw UnsupportedOperationException("ObstacleService does not support binding")
    }

    override fun onCreate() {
        super.onCreate()
        
        // 初始化语音服务
        serviceScope.launch {
            try {
                val result = voiceRepository.initialize()
                if (result is com.blindpath.base.common.Result.Success) {
                    voiceRepository.speak("障碍物检测已开启", queueMode = false)
                } else {
                    Timber.w("Voice init failed, TTS unavailable")
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize voice repository")
            }
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

        // 启动看门狗心跳
        watchdog.start()

        // ★ 【关键修复】设置 LifecycleOwner，使 CameraX 能绑定到 Service 生命周期
        // 诊断报告发现：未设置此属性导致 bindCameraUseCases() 中 lifecycleOwner==null 直接 return
        // 摄像头从未采集过一帧画面，障碍物检测功能完全瘫痪
        obstacleRepository.setLifecycleOwner(this)

        detectionJob = serviceScope.launch {
            try {
                // 初始化检测器
                val initResult = obstacleRepository.initialize()
                if (initResult !is com.blindpath.base.common.Result.Success) {
                    voiceRepository.speak("摄像头启动失败，请检查权限", queueMode = false)
                    stopObstacle()
                    return@launch
                }

                // 开始检测
                val result = obstacleRepository.startDetection()
                if (result !is com.blindpath.base.common.Result.Success) {
                    voiceRepository.speak("摄像头启动失败，请检查权限", queueMode = false)
                    stopObstacle()
                    return@launch
                }

                // 监听检测结果 - 关键修复：监听 obstacleState 变化触发语音播报
                obstacleRepository.obstacleState.collectLatest { state ->
                    try {
                        // 记录看门狗心跳
                        watchdog.recordHeartbeat()

                        // 更新通知
                        val alertText = state.currentAlert?.description ?: "正在检测障碍物"
                        updateNotification(alertText)

                        // 处理预警 - 关键修复：确保语音播报正确触发
                        state.currentAlert?.let { alert ->
                            handleAlert(alert)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing obstacle state")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Obstacle detection failed")
                voiceRepository.speak("障碍物检测异常", queueMode = false)
                stopObstacle()
            }
        }
    }

    private fun stopObstacle() {
        isRunning = false

        // 停止看门狗心跳
        watchdog.stop()

        // 取消协程
        detectionJob?.cancel()
        detectionJob = null
        
        VibrationHelper.cancel(this)

        serviceScope.launch {
            try {
                obstacleRepository.stopDetection()
                voiceRepository.speak("障碍物检测已关闭", queueMode = false)
            } catch (e: Exception) {
                Timber.e(e, "Error stopping detection")
            }
        }

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    /**
     * 处理障碍物预警：通过 AlertExecutor 三路并行输出（语音+振动）
     *
     * 使用 AlertExecutor 替代直接调用，确保：
     * 1. 语音和振动互不依赖，独立 try-catch
     * 2. 单路失败不影响其他通道
     * 3. 冷却机制仍在此层控制
     */
    private fun handleAlert(alert: ObstacleAlert) {
        val currentTime = System.currentTimeMillis()

        // 冷却机制：避免重复播报
        if (alert.description == lastAlertMessage && currentTime - lastAlertTime < alertRepeatMinInterval) {
            return
        }

        lastAlertMessage = alert.description
        lastAlertTime = currentTime

        // 委托给 AlertExecutor 三路并行执行
        alertExecutor.executeAlert(alert.level, alert.description)
    }

    private fun createNotification(): Notification {
        val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            flags
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
        detectionJob?.cancel()
        VibrationHelper.cancel(this)
    }
}


