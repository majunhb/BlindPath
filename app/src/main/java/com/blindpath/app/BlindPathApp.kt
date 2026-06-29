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
import com.blindpath.base.reliability.WakeWordWatchdogReceiver
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

        // ★ 【修复】初始化SOS紧急联系人持久化
        com.blindpath.base.sos.SosHelper.init(this)

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
            
            // ★ 华为/荣耀设备：请求电池优化豁免（防止系统杀死wakeword进程）
            requestBatteryOptimizationExemption()

            // 启动增强版语音唤醒服务
            startWakeWordService()
            
            // ★ 启动唤醒词看门狗（每60秒检查wakeword进程是否被杀，被杀则自动重启）
            WakeWordWatchdogReceiver.start(this)

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
     * ★ 华为/荣耀设备电池优化豁免
     *
     * 根因：华为 iAwareF (SystemManager) 会强杀 :wakeword 进程，
     * 即使前台服务也无法幸免。必须将应用加入电池优化白名单。
     *
     * 注意：此方法只是引导，用户仍需在设置中手动确认。
     */
    private fun requestBatteryOptimizationExemption() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            val packageName = packageName
            
            // ★ 华为/荣耀特殊处理：尝试打开"启动管理"页面
            if (isHuaweiOrHonor()) {
                Timber.w("★ 华为/荣耀设备检测：尝试打开启动管理")
                if (openHuaweiStartupManager()) {
                    Timber.i("★ 已打开华为启动管理页面，请用户手动将助盲智行设为「手动管理」并开启所有开关")
                    return
                }
                // 降级到通用电池优化页面
                Timber.w("★ 无法打开华为启动管理，降级到通用电池优化设置")
            }
            
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                Timber.w("★ Battery optimization NOT exempted for $packageName")
                
                try {
                    val intent = android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS.let {
                        android.content.Intent(it).apply {
                            data = android.net.Uri.parse("package:$packageName")
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    }
                    startActivity(intent)
                    Timber.i("★ Opened battery optimization settings")
                } catch (e: Exception) {
                    Timber.e(e, "★ Failed to open battery optimization settings")
                    try {
                        val intent = android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                            data = android.net.Uri.parse("package:$packageName")
                            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        startActivity(intent)
                    } catch (e2: Exception) {
                        Timber.e(e2, "★ Failed to open app settings")
                    }
                }
            } else {
                Timber.d("★ Battery optimization already exempted")
            }
        }
    }
    
    /**
     * 判断是否为华为或荣耀设备
     */
    private fun isHuaweiOrHonor(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("huawei") || manufacturer.contains("honor")
    }
    
    /**
     * 尝试打开华为"启动管理"页面
     * 华为 EMUI/HarmonyOS 使用自己的电池管理系统，需要通过特殊 Intent 打开
     *
     * [P2 修复 2026-06-29] 部分华为设备（如 FOA-AL00 HarmonyOS）的启动管理 Activity
     * 需要 com.huawei.permission.external_app_settings.USE_COMPONENT 权限，
     * resolveActivity() 能返回结果但 startActivity() 会抛 SecurityException。
     * 修复：对每个 Intent 分别 try-catch，第一个失败则尝试备用路径。
     */
    private fun openHuaweiStartupManager(): Boolean {
        val pm = packageManager

        // 尝试路径 1：启动管理页面
        val intent1 = android.content.Intent().apply {
            component = android.content.ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            )
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (pm.resolveActivity(intent1, 0) != null) {
            try {
                startActivity(intent1)
                return true
            } catch (e: SecurityException) {
                // [P2 修复] 权限被拒，不中断流程，继续尝试备用路径
                Timber.w("★ Huawei startup manager path 1 denied (SecurityException), trying fallback")
            } catch (e: Exception) {
                Timber.w(e, "★ Huawei startup manager path 1 failed, trying fallback")
            }
        }

        // 尝试路径 2：电池优化保护页面
        val intent2 = android.content.Intent().apply {
            component = android.content.ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        if (pm.resolveActivity(intent2, 0) != null) {
            try {
                startActivity(intent2)
                return true
            } catch (e: SecurityException) {
                Timber.w("★ Huawei startup manager path 2 denied (SecurityException)")
            } catch (e: Exception) {
                Timber.w(e, "★ Huawei startup manager path 2 failed")
            }
        }

        Timber.w("★ All Huawei startup manager paths unavailable, will fallback to generic battery settings")
        return false
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
