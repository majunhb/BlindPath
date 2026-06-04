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
import com.blindpath.module_voice.config.VoiceServiceConfig
import com.blindpath.module_voice.service.BluetoothDeviceMonitor
import com.blindpath.module_voice.service.PerformanceMonitor
import com.blindpath.module_voice.service.SceneAdaptationManager
import com.blindpath.module_voice.service.WakeWordServiceEnhanced
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

    override fun onCreate() {
        super.onCreate()
        
        // 初始化 Timber 日志
        initTimber()
        
        // 初始化全局异常捕获器
        GlobalExceptionHandler.initialize(this)
        
        // 初始化高德地图 SDK
        initAMapSDK()
        
        // 初始化语音服务
        initVoiceService()
        
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
            // Release 模式：可以接入 Crashlytics 等服务
            // Timber.plant(CrashlyticsTree())
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
