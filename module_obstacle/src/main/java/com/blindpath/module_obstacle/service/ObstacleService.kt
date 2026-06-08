package com.blindpath.module_obstacle.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blindpath.base.common.AlertLevel
import com.blindpath.base.common.ObstacleAlert
import com.blindpath.base.tts.VibrationHelper
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.VoiceRequest
import com.blindpath.module_voice.domain.model.VoiceType
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

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        // 初始化语音服务
        serviceScope.launch {
            try {
                voiceRepository.initialize()
                voiceRepository.speak("障碍物检测已开启", queueMode = false)
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
     * 处理障碍物预警：语音播报 + 振动反馈
     * 
     * 关键修复：
     * 1. 根据 AlertLevel 选择正确的 VoiceType
     * 2. 使用 voiceRepository.announce() 触发语音播报
     * 3. 危险告警立即打断当前播报
     */
    private fun handleAlert(alert: ObstacleAlert) {
        val currentTime = System.currentTimeMillis()

        // 冷却机制：避免重复播报
        if (alert.description == lastAlertMessage && currentTime - lastAlertTime < alertRepeatMinInterval) {
            return
        }

        lastAlertMessage = alert.description
        lastAlertTime = currentTime

        // 关键修复：根据危险等级选择播报类型
        val voiceType = when (alert.level) {
            AlertLevel.DANGER -> VoiceType.OBSTACLE_DANGER    // 危险：立即打断
            AlertLevel.WARNING -> VoiceType.OBSTACLE_NORMAL   // 警告：等待当前播报
            AlertLevel.SAFE -> VoiceType.OBSTACLE_LOW        // 安全：低优先级
            AlertLevel.UNKNOWN -> VoiceType.SYSTEM_STATUS     // 未知：系统状态播报
        }

        // 使用 voiceRepository.announce() 触发语音播报
        serviceScope.launch {
            try {
                val request = VoiceRequest(
                    text = alert.description,
                    type = voiceType,
                    // 危险预警需要打断当前播报
                    interruptCurrent = alert.level == AlertLevel.DANGER
                )
                voiceRepository.announce(request)
            } catch (e: Exception) {
                Timber.e(e, "Failed to announce obstacle alert")
            }
        }

        // 振动反馈（危险和警告级别）
        if (alert.level != AlertLevel.SAFE) {
            VibrationHelper.vibrate(this, alert.level)
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
        detectionJob?.cancel()
        VibrationHelper.cancel(this)
    }
}


