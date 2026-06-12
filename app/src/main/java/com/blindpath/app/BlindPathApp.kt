package com.blindpath.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.lifecycle.ProcessLifecycleOwner
import com.amap.api.location.AMapLocationClient
import com.blindpath.base.error.GlobalExceptionHandler
import com.blindpath.base.reliability.CrashlyticsTree
import com.blindpath.base.reliability.DetectionServiceWatchdog
import com.blindpath.base.reliability.ReliabilityLogger
import com.blindpath.module_voice.config.VoiceServiceConfig
import com.blindpath.module_voice.service.BluetoothDeviceMonitor
import com.blindpath.module_voice.service.PerformanceMonitor
import com.blindpath.module_voice.service.SceneAdaptationManager
import com.blindpath.module_voice.service.WakeWordServiceEnhanced
import com.blindpath.module_voice.service.VoiceInteractionPipeline
import android.app.ActivityManager
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber
import javax.inject.Inject

@HiltAndroidApp
class BlindPathApp : Application() {

    @Inject
    lateinit var bluetoothMonitor: BluetoothDeviceMonitor
    
    @Inject
    lateinit var sceneManager: SceneAdaptationManager
    
    @Inject
    lateinit var performanceMonitor: PerformanceMonitor

    @Inject
    lateinit var watchdog: DetectionServiceWatchdog

    @Inject
    lateinit var voiceInteractionPipeline: VoiceInteractionPipeline

    override fun onCreate() {
        super.onCreate()

        // ★★★ 多进程安全守卫
        // WakeWordServiceEnhanced 运行在 :wakeword 独立进程，Application.onCreate() 也会被调用一次
        // 若在子进程中执行主进程的初始化逻辑，Hilt 注入会崩溃
        // 解决：检测当前进程名，若不是主进程则跳过测选初始化
        if (!isMainProcess()) {
            // 子进程只需要最基础的初始化
            initTimber()
            return
        }

        // 初始化 Timber 日志
        initTimber()

        // Phase 2: 初始化可靠性日志
        ReliabilityLogger.initialize(this)
        
        // 初始化全局异常捕获器
        GlobalExceptionHandler.initialize(this)
        
        // 初始化高德地图 SDK
        initAMapSDK()
        
        // 初始化语音服务
        initVoiceService()

        // Phase 1: 启动检测服务看门狗
        watchdog.start()
        Timber.i("Detection service watchdog started")
        
        // 确保 ProcessLifecycleOwner 被初始化，供 CameraX 等组件使用
        ProcessLifecycleOwner.get()
        
        Timber.d("BlindPath Application initialized")
    }
    
    /**
     * 初始化 Timber 日志
     */
    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            // Debug 模式：打印到 Logcat
            Timber.plant(Timber.DebugTree())
        } else {
            // Release 模式：Crashlytics 上报 + 本地文件兜底
            Timber.plant(CrashlyticsTree())
        }
    }
    
    /**
     * 初始化高德地图 SDK
     */
    private fun initAMapSDK() {
        try {
            // 设置高德隐私合规（Android 11+ 必需）
            AMapLocationClient.updatePrivacyShow(this, true, true)
            AMapLocationClient.updatePrivacyAgree(this, true)
            
            Timber.d("AMap SDK initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AMap SDK")
        }
    }
    
    /**
     * 初始化语音服务
     */
    private fun initVoiceService() {
        try {
            // 创建通知渠道
            createNotificationChannel()
            
            // 配置调试模式
            VoiceServiceConfig.enableDebugMode = BuildConfig.DEBUG
            VoiceServiceConfig.enablePerformanceMonitor = true
            
            // 启动性能监控
            performanceMonitor.startMonitoring()
            
            // 启动场景适配管理
            sceneManager.startMonitoring()
            
            // 启动蓝牙设备监听
            bluetoothMonitor.startMonitoring()
            
            // 启动增强版语音唤醒服务
            startWakeWordService()

            // 初始化全链路语音交互管道（动态注册 BroadcastReceiver 监听唤醒词）
            voiceInteractionPipeline.initialize()
            
            Timber.d("Voice service initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize voice service")
        }
    }
    
    /**
     * 创建通知渠道（Android 8.0+ 必需）
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                VoiceServiceConfig.NOTIFICATION_CHANNEL_ID,
                "语音唤醒服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "保持语音唤醒服务在后台运行"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            notificationManager.createNotificationChannel(channel)
            
            Timber.d("Notification channel created")
        }
    }
    
    /**
     * 启动增强版语音唤醒服务
     */
    private fun startWakeWordService() {
        val intent = Intent(this, WakeWordServiceEnhanced::class.java).apply {
            action = WakeWordServiceEnhanced.ACTION_START
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        Timber.d("Wake word service started")
    }
    
    /**
     * 判断当前进程是否为主进程
     *
     * ★★★ 多进程安全：WakeWordServiceEnhanced 运行在 :wakeword 独立进程
     * 主进程名 = packageName，子进程名 = packageName:wakeword
     */
    private fun isMainProcess(): Boolean {
        val pid = android.os.Process.myPid()
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        return activityManager.runningAppProcesses?.any {
            it.pid == pid && it.processName == packageName
        } ?: true  // 无法确认时默认为主进程，避免跳过必要初始化
    }

    override fun onTerminate() {
        super.onTerminate()
        
        // 停止性能监控
        performanceMonitor.stopMonitoring()
        
        // 停止场景适配管理
        sceneManager.stopMonitoring()
        
        // 停止蓝牙监听
        bluetoothMonitor.stopMonitoring()
        
        Timber.d("BlindPath Application terminated")
    }
}
