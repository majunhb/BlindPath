package com.blindpath.module_obstacle.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.blindpath.base.common.AlertLevel
import com.blindpath.base.config.AppConfig
import com.blindpath.base.error.BlindPathError
import com.blindpath.base.error.DegradationManager
import com.blindpath.base.error.DegradationLevel
import com.blindpath.base.power.FrameRateController
import com.blindpath.base.power.PowerManager
import com.blindpath.base.tts.VibrationHelper
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import javax.inject.Inject

/**
 * 避障前台服务（增强版）
 * 
 * 新增功能：
 * - 电量监控与自动降级
 * - 智能帧率控制
 * - 错误处理与恢复
 * - 性能监控
 */
@AndroidEntryPoint
class ObstacleService : Service() {

    @Inject
    lateinit var obstacleRepository: ObstacleRepository

    @Inject
    lateinit var voiceRepository: VoiceRepository

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false

    private var lastAlertMessage: String? = null
    private var lastAlertTime = 0L
    private val alertRepeatMinInterval = AppConfig.Obstacle.ALERT_COOLDOWN_MS

    // 新增：电量监控
    private var batteryLevel = 100
    private var isLowPowerMode = false

    // 新增：帧率控制器
    private lateinit var frameRateController: FrameRateController

    companion object {
        const val ACTION_START = "com.blindpath.action.START_OBSTACLE"
        const val ACTION_STOP = "com.blindpath.action.STOP_OBSTACLE"
        private const val NOTIFICATION_ID = 1001
        const val CHANNEL_OBSTACLE = "channel_obstacle"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        // 初始化帧率控制器
        frameRateController = FrameRateController(this)
        
        // 监控电量变化
        registerBatteryReceiver()
        
        // 初始化服务
        serviceScope.launch {
            voiceRepository.initialize()
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

        // 检查电量并设置初始模式
        checkBatteryAndAdjustMode()

        createNotificationChannel()
        val notification = createNotification()
        startForeground(NOTIFICATION_ID, notification)

        serviceScope.launch {
            try {
                // 初始化检测器
                val initResult = obstacleRepository.initialize()
                if (initResult !is com.blindpath.base.common.Result.Success) {
                    handleError(BlindPathError.AI.INIT_FAILED)
                    return@launch
                }

                // 语音提示
                val modeText = if (isLowPowerMode) "省电模式" else "正常模式"
                voiceRepository.speak("障碍物检测已开启，${modeText}", queueMode = false)

                // 开始检测
                val result = obstacleRepository.startDetection()
                if (result !is com.blindpath.base.common.Result.Success) {
                    handleError(BlindPathError.Camera.INIT_FAILED)
                    stopObstacle()
                    return@launch
                }

                // 监听检测结果
                obstacleRepository.obstacleState.collectLatest { state ->
                    try {
                        val alertText = state.currentAlert?.description ?: "正在检测障碍物"
                        updateNotification(alertText)

                        state.currentAlert?.let { alert ->
                            handleAlert(alert.level, alert.description)
                        }
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing obstacle state")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Obstacle detection failed")
                handleError(BlindPathError.AI.INFERENCE_FAILED)
                stopObstacle()
            }
        }
    }

    private fun stopObstacle() {
        isRunning = false
        VibrationHelper.cancel(this)

        // 恢复正常模式
        if (isLowPowerMode) {
            DegradationManager.promote(DegradationLevel.NORMAL)
            frameRateController.setFrameRate(AppConfig.Power.NORMAL_FRAME_RATE)
        }

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

        // 低电量模式下减少播报频率
        val minInterval = if (isLowPowerMode) {
            alertRepeatMinInterval * 2
        } else {
            alertRepeatMinInterval
        }

        if (description == lastAlertMessage && currentTime - lastAlertTime < minInterval) {
            return
        }

        lastAlertMessage = description
        lastAlertTime = currentTime

        // 立即停止当前播报，播报预警
        serviceScope.launch {
            voiceRepository.speakObstacleAlert(description)
        }

        // 低电量模式下减少振动
        if (level != AlertLevel.SAFE && !isLowPowerMode) {
            VibrationHelper.vibrate(this, level)
        } else if (level == AlertLevel.DANGER) {
            // 危险级别始终振动
            VibrationHelper.vibrate(this, level)
        }
    }

    /**
     * 检查电量并调整运行模式
     */
    private fun checkBatteryAndAdjustMode() {
        val batteryManager = getSystemService(BATTERY_SERVICE) as BatteryManager
        batteryLevel = batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

        Timber.d("Battery level: $batteryLevel%")

        if (batteryLevel <= AppConfig.Power.LOW_BATTERY_THRESHOLD) {
            enableLowPowerMode()
        } else {
            disableLowPowerMode()
        }
    }

    /**
     * 启用低电量模式
     */
    private fun enableLowPowerMode() {
        if (isLowPowerMode) return
        
        isLowPowerMode = true
        Timber.i("Enabling low power mode (battery: $batteryLevel%)")
        
        // 应用降级策略
        DegradationManager.degrade(DegradationLevel.LOW_POWER)
        
        // 降低帧率
        frameRateController.setFrameRate(AppConfig.Power.LOW_POWER_FRAME_RATE)
        
        // 语音提示
        serviceScope.launch {
            voiceRepository.speak("电量较低，已切换到省电模式", queueMode = false)
        }
    }

    /**
     * 禁用低电量模式
     */
    private fun disableLowPowerMode() {
        if (!isLowPowerMode) return
        
        isLowPowerMode = false
        Timber.i("Disabling low power mode (battery: $batteryLevel%)")
        
        // 恢复正常模式
        DegradationManager.promote(DegradationLevel.NORMAL)
        
        // 恢复帧率
        frameRateController.setFrameRate(AppConfig.Power.NORMAL_FRAME_RATE)
    }

    /**
     * 注册电量变化接收器
     */
    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        registerBatteryReceiver(object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                
                if (level >= 0 && scale > 0) {
                    batteryLevel = (level * 100) / scale
                    
                    if (batteryLevel <= AppConfig.Power.LOW_BATTERY_THRESHOLD && !isLowPowerMode) {
                        enableLowPowerMode()
                    } else if (batteryLevel > AppConfig.Power.LOW_BATTERY_THRESHOLD + 5 && isLowPowerMode) {
                        disableLowPowerMode()
                    }
                }
            }
        }, filter)
    }

    /**
     * 处理错误
     */
    private fun handleError(error: BlindPathError) {
        Timber.e("ObstacleService error: $error")
        
        when (error) {
            is BlindPathError.Camera.PERMISSION_DENIED -> {
                serviceScope.launch {
                    voiceRepository.speak("相机权限被拒绝，请在设置中开启", queueMode = false)
                }
            }
            is BlindPathError.Camera.DEVICE_NOT_FOUND -> {
                serviceScope.launch {
                    voiceRepository.speak("未检测到相机设备", queueMode = false)
                }
            }
            is BlindPathError.AI.MODEL_NOT_FOUND -> {
                serviceScope.launch {
                    voiceRepository.speak("AI模型未加载，请检查应用设置", queueMode = false)
                }
            }
            is BlindPathError.AI.INFERENCE_FAILED -> {
                serviceScope.launch {
                    voiceRepository.speak("AI检测出现异常", queueMode = false)
                }
            }
            else -> {
                serviceScope.launch {
                    voiceRepository.speak("障碍物检测出现错误", queueMode = false)
                }
            }
        }
    }

    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        val modeText = if (isLowPowerMode) " [省电模式]" else ""
        return NotificationCompat.Builder(this, CHANNEL_OBSTACLE)
            .setContentTitle("避障功能运行中$modeText")
            .setContentText("正在为您检测周围障碍物")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val modeText = if (isLowPowerMode) " [省电模式]" else ""
        val batteryText = " 电量:$batteryLevel%"
        
        val notification = NotificationCompat.Builder(this, CHANNEL_OBSTACLE)
            .setContentTitle("避障功能运行中$modeText")
            .setContentText(text + batteryText)
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
