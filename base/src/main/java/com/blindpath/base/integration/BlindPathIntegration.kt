package com.blindpath.base.integration

import android.content.Context
import com.blindpath.base.accessibility.AccessibilitySettings
import com.blindpath.base.analytics.AnalyticsManager
import com.blindpath.base.cache.CacheManager
import com.blindpath.base.error.DegradationManager
import com.blindpath.base.error.DegradationManager.DegradationLevel
import com.blindpath.base.i18n.LanguageManager
import com.blindpath.base.network.NetworkMonitor
import com.blindpath.base.offline.ModelPreloader
import com.blindpath.base.performance.PerformanceMonitor
import com.blindpath.base.power.PowerManager
import com.blindpath.base.security.PermissionManager
import com.blindpath.base.security.PrivacyManager
import com.blindpath.base.security.SecureStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * BlindPath 功能集成管理器
 * 
 * 统一管理所有新增的基础设施：
 * - 电量优化
 * - 网络监控
 * - 离线支持
 * - 性能监控
 * - 安全隐私
 * - 国际化
 * - 无障碍
 */
object BlindPathIntegration {
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var isInitialized = false
    
    private lateinit var accessibilitySettings: AccessibilitySettings
    private lateinit var languageManager: LanguageManager
    private lateinit var permissionManager: PermissionManager
    private lateinit var privacyManager: PrivacyManager
    private lateinit var networkMonitor: NetworkMonitor
    private lateinit var powerManager: PowerManager
    private lateinit var secureStorage: SecureStorage
    private lateinit var analyticsManager: AnalyticsManager
    private lateinit var performanceMonitor: PerformanceMonitor
    private lateinit var appContext: Context
    
    /**
     * 初始化所有集成组件
     */
    fun initialize(context: Context): Boolean {
        if (isInitialized) {
            return true
        }
        
        return try {
            Timber.i("Initializing BlindPath integration components...")
            appContext = context.applicationContext
            
            // 1. 初始化安全存储
            secureStorage = SecureStorage(context)
            secureStorage.initializeKey()
            Timber.d("SecureStorage initialized")
            
            // 2. 获取缓存管理器实例
            val cacheManager = CacheManager.getInstance(context)
            Timber.d("CacheManager initialized")
            
            // 3. 初始化语言管理器
            languageManager = LanguageManager(context)
            Timber.d("LanguageManager initialized")
            
            // 4. 初始化无障碍设置
            accessibilitySettings = AccessibilitySettings(context)
            Timber.d("AccessibilitySettings initialized")
            
            // 5. 初始化权限管理器
            permissionManager = PermissionManager(context)
            Timber.d("PermissionManager initialized")
            
            // 6. 初始化隐私管理器
            privacyManager = PrivacyManager(context)
            Timber.d("PrivacyManager initialized")
            
            // 7. 初始化网络监控
            networkMonitor = NetworkMonitor(context)
            startNetworkMonitoring()
            Timber.d("NetworkMonitor initialized")
            
            // 8. 初始化电量管理
            powerManager = PowerManager(context)
            Timber.d("PowerManager initialized")
            
            // 9. 获取模型预加载器实例
            val modelPreloader = ModelPreloader.getInstance(context)
            Timber.d("ModelPreloader initialized")
            
            // 10. 获取性能监控实例
            performanceMonitor = PerformanceMonitor.getInstance(context)
            Timber.d("PerformanceMonitor initialized")
            
            // 11. 初始化分析管理器
            analyticsManager = AnalyticsManager.getInstance(context)
            analyticsManager.initializeSession()
            Timber.d("AnalyticsManager initialized")
            
            isInitialized = true
            Timber.i("All integration components initialized successfully")
            
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize integration components")
            false
        }
    }
    
    /**
     * 启动网络监控
     */
    private fun startNetworkMonitoring() {
        scope.launch {
            try {
                val status = networkMonitor.getCurrentNetworkStatus()
                if (!status.isConnected) {
                    Timber.w("Network disconnected, entering offline mode")
                    DegradationManager.setDegradationLevel(
                        DegradationManager.Feature.NETWORK,
                        DegradationLevel.OFFLINE
                    )
                }
            } catch (e: Exception) {
                Timber.e(e, "Error monitoring network")
            }
        }
    }
    
    /**
     * 检查电量状态并应用降级策略
     */
    fun checkPowerStatus() {
        try {
            val powerState = powerManager.getPowerState()
            when {
                powerState.isLowBattery -> {
                    Timber.w("Low battery level: ${powerState.batteryLevel}%")
                    DegradationManager.setDegradationLevel(
                        DegradationManager.Feature.AI_DETECTION,
                        DegradationLevel.REDUCED
                    )
                }
                powerState.isCharging -> {
                    Timber.i("Device charging, restoring normal mode")
                    DegradationManager.restoreFeature(DegradationManager.Feature.AI_DETECTION)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Error checking power status")
        }
    }
    
    /**
     * 预加载 AI 模型
     */
    fun preloadModels(context: Context, onComplete: (Result<Any>) -> Unit) {
        try {
            Timber.i("Preloading AI models...")
            ModelPreloader.getInstance(context).preloadInBackground { result ->
                result.fold(
                    onSuccess = { file ->
                        Timber.i("AI models preloaded successfully: ${file.absolutePath}")
                        onComplete(Result.success(file))
                    },
                    onFailure = { error ->
                        Timber.e(error, "Failed to preload AI models")
                        onComplete(Result.failure(error))
                    }
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to preload AI models")
            onComplete(Result.failure(e))
        }
    }
    
    /**
     * 检查核心权限状态
     */
    fun checkCorePermissions(): Boolean {
        return permissionManager.hasCorePermissions()
    }
    
    /**
     * 获取缺失的核心权限
     */
    fun getMissingCorePermissions(): List<String> {
        return permissionManager.getMissingPermissions(PermissionManager.PermissionGroups.CORE_PERMISSIONS)
    }
    
    /**
     * 获取当前运行状态摘要
     */
    fun getStatusSummary(): StatusSummary {
        val powerState = try {
            powerManager.getPowerState()
        } catch (e: Exception) {
            null
        }
        
        val networkStatus = try {
            networkMonitor.getCurrentNetworkStatus()
        } catch (e: Exception) {
            null
        }
        
        val performanceReport = try {
            performanceMonitor.getPerformanceReport()
        } catch (e: Exception) {
            null
        }
        
        // 计算平均帧时间（如果 metrics 中有相关数据）
        val isPerformanceOptimal = performanceReport?.metrics?.values?.any { metric ->
            metric.averageTimeMs < 33
        } ?: false
        
        return StatusSummary(
            isInitialized = isInitialized,
            degradationLevel = DegradationManager.getDegradationLevel(DegradationManager.Feature.AI_DETECTION),
            batteryLevel = powerState?.batteryLevel ?: -1,
            isCharging = powerState?.isCharging ?: false,
            isNetworkConnected = networkStatus?.isConnected ?: false,
            currentLanguage = languageManager.selectedLanguage,
            fontSizeScale = accessibilitySettings.fontScale,
            isHighContrastEnabled = accessibilitySettings.isHighContrastEnabled,
            isPerformanceOptimal = isPerformanceOptimal
        )
    }
    
    /**
     * 状态摘要数据类
     */
    data class StatusSummary(
        val isInitialized: Boolean,
        val degradationLevel: DegradationLevel,
        val batteryLevel: Int,
        val isCharging: Boolean,
        val isNetworkConnected: Boolean,
        val currentLanguage: LanguageManager.Language,
        val fontSizeScale: Float,
        val isHighContrastEnabled: Boolean,
        val isPerformanceOptimal: Boolean
    )
    
    /**
     * 释放资源
     */
    fun release() {
        try {
            CacheManager.getInstance(appContext).clearAll()
            performanceMonitor.clear()
            Timber.i("BlindPath integration resources released")
        } catch (e: Exception) {
            Timber.e(e, "Error releasing integration resources")
        }
    }
}
