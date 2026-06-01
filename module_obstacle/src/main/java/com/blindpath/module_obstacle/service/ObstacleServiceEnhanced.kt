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
import com.blindpath.base.power.FrameRateController
import com.blindpath.base.power.PerformanceMode
import com.blindpath.base.power.SmartPowerManager
import com.blindpath.base.tts.VibrationHelper
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import timber.log.Timber
import javax.inject.Inject

/**
 * 避障前台服务（增强版 v2.0）
 * 
 * 新增功能：
 * - 智能省电管理：自适应调整检测频率、传感器采样率
 * - 电量监控与自动降级
 * - 智能帧率控制
 * - 错误处理与恢复
 * - 性能监控
 * - 温度监控：过热时自动降频
 */
@AndroidEntryPoint
class ObstacleServiceEnhanced : Service() {

    @Inject
    lateinit var obstacleRepository: ObstacleRepository

    @Inject
    lateinit var voiceRepository: VoiceRepository

    @Inject
    lateinit var smartPowerManager: SmartPowerManager

    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isRunning = false

    private var lastAlertMessage: String? = null
    private var lastAlertTime = 0L
    private val alertRepeatMinInterval = AppConfig.ObstacleAlert.ALERT_COOLDOWN_MS

    // 新增：电量监控
    private var batteryLevel = 100
    private var isLowPowerMode = false
    private var batteryReceiver: android.content.BroadcastReceiver? = null

    // 新增：帧率控制器
    private lateinit var frameRateController: FrameRateController

    companion object {
        const val ACTION_START = "com.blindpath.action.START_OBSTACLE"
        const val ACTION_STOP = "com.blindpath.action.STOP_OBSTACLE"
        private const val NOTIFICATION_ID = 1001
        const val CHANNEL_OBSTACLE = "channel_obstacle"
    }

    override fun onBind(intent: Intent): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        
        // 初始化帧率控制器
        frameRateController = FrameRateController()
        
        // 启动智能省电监控
        smartPowerManager.startMonitoring(frameRateController = frameRateController)
        
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

        // 检查功能是否因外部降级而不可用（如系统低电/网络离线等）
        if (!DegradationManager.isFeatureAvailable(DegradationManager.Feature.CAMERA)) {
            Timber.w("Camera is disabled via degradation, cannot start obstacle detection")
            serviceScope.launch {
                voiceRepository.speak("摄像头功能不可用，无法启动障碍物检测", queueMode = false)
            }
            return
        }

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
                    handleError(BlindPathError.ModelLoadError("初始化失败"))
                    return@launch
                }

                // 语音提示
                val modeText = if (isLowPowerMode) "省电模式" else "正常模式"
                voiceRepository.speak("障碍物检测已开启，${modeText}", queueMode = false)

                // 开始检测
                val result = obstacleRepository.startDetection()
                if (result !is com.blindpath.base.common.Result.Success) {
                    handleError(BlindPathError.CameraInitError())
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
                handleError(BlindPathError.InferenceError("检测失败"))
                stopObstacle()
            }
        }
    }

    private fun stopObstacle() {
        isRunning = false
        VibrationHelper.cancel(this)

        // 恢复正常模式
        if (isLowPowerMode) {
            DegradationManager.restoreFeature(DegradationManager.Feature.AI_DETECTION)
            frameRateController.setPerformanceMode(PerformanceMode.HIGH)
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

        // 根据省电模式调整播报频率
        val powerSavingMode = smartPowerManager.powerSavingMode.value
        val minInterval = when (powerSavingMode.level) {
            0 -> alertRepeatMinInterval           // 正常模式
            1 -> alertRepeatMinInterval * 2        // 中度省电
            2 -> alertRepeatMinInterval * 3        // 激进省电
            else -> alertRepeatMinInterval * 4     // 超级省电
        }

        if (description == lastAlertMessage && currentTime - lastAlertTime < minInterval) {
            return
        }

        lastAlertMessage = description
        lastAlertTime = currentTime

        // 根据危险等级选择播报类型
        val voiceType = when (level) {
            AlertLevel.DANGER -> com.blindpath.module_voice.domain.model.VoiceType.OBSTACLE_DANGER
            AlertLevel.WARNING -> com.blindpath.module_voice.domain.model.VoiceType.OBSTACLE_NORMAL
            AlertLevel.SAFE -> com.blindpath.module_voice.domain.model.VoiceType.OBSTACLE_LOW
            else -> com.blindpath.module_voice.domain.model.VoiceType.OBSTACLE_NORMAL
        }

        // 使用分级播报系统
        serviceScope.launch {
            voiceRepository.announce(description, voiceType)
        }

        // 根据省电模式决定是否振动
        if (level != AlertLevel.SAFE && !smartPowerManager.shouldDisableVibration()) {
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

        if (batteryLevel <= AppConfig.PowerSaving.LOW_BATTERY_THRESHOLD) {
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
        DegradationManager.setDegradationLevel(
            DegradationManager.Feature.AI_DETECTION,
            DegradationManager.DegradationLevel.REDUCED,
            "Low battery"
        )
        
        // 降低帧率
        frameRateController.setPerformanceMode(PerformanceMode.LOW)
        
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
        DegradationManager.restoreFeature(DegradationManager.Feature.AI_DETECTION)
        
        // 恢复帧率
        frameRateController.setPerformanceMode(PerformanceMode.HIGH)
    }

    /**
     * 注册电量变化接收器
     */
    private fun registerBatteryReceiver() {
        val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        batteryReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: android.content.Context?, intent: Intent?) {
                val level = intent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = intent?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                
                if (level >= 0 && scale > 0) {
                    batteryLevel = (level * 100) / scale
                    
                    if (batteryLevel <= AppConfig.PowerSaving.LOW_BATTERY_THRESHOLD && !isLowPowerMode) {
                        enableLowPowerMode()
                    } else if (batteryLevel > AppConfig.PowerSaving.LOW_BATTERY_THRESHOLD + 5 && isLowPowerMode) {
                        disableLowPowerMode()
                    }
                }
            }
        }
        registerReceiver(batteryReceiver, filter)
    }

    /**
     * 处理错误
     */
    private fun handleError(error: BlindPathError) {
        Timber.e("ObstacleService error: $error")
        
        when (error) {
            is BlindPathError.CameraPermissionDenied -> {
                serviceScope.launch {
                    voiceRepository.speak("相机权限被拒绝，请在设置中开启", queueMode = false)
                }
            }
            is BlindPathError.CameraInitError -> {
                serviceScope.launch {
                    voiceRepository.speak("相机初始化失败", queueMode = false)
                }
            }
            is BlindPathError.ModelNotFoundError -> {
                serviceScope.launch {
                    voiceRepository.speak("AI模型未加载，请检查应用设置", queueMode = false)
                }
            }
            is BlindPathError.InferenceError -> {
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
        val powerSavingMode = smartPowerManager.powerSavingMode.value
        val modeIndicator = when (powerSavingMode.level) {
            0 -> ""
            1 -> " [省电]"
            2 -> " [低功耗]"
            3 -> " [超级省电]"
            else -> ""
        }
        
        return NotificationCompat.Builder(this, CHANNEL_OBSTACLE)
            .setContentTitle("避障功能运行中$modeText$modeIndicator")
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
        val temperature = smartPowerManager.powerState.value.temperature
        val tempText = if (temperature > 40) " 温度:${temperature.toInt()}°C" else ""
        
        val notification = NotificationCompat.Builder(this, CHANNEL_OBSTACLE)
            .setContentTitle("避障功能运行中$modeText")
            .setContentText(text + batteryText + tempText)
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
        batteryReceiver?.let { unregisterReceiver(it) }
        smartPowerManager.stopMonitoring()
        serviceScope.cancel()
        VibrationHelper.cancel(this)
    }
}
