package com.blindpath.base.integration

import android.content.Context
import com.blindpath.base.accessibility.AccessibilitySettings
import com.blindpath.base.analytics.AnalyticsManager
import com.blindpath.base.cache.CacheManager
import com.blindpath.base.config.AppConfig
import com.blindpath.base.error.BlindPathError
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
import kotlinx.coroutines.flow.collectLatest
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
    
    /**
     * 初始化所有集成组件
     */
    suspend fun initialize(context: Context): Result<Boolean> {
        if (isInitialized) {
            return Result.success(true)
        }
        
        return try {
            Timber.i("Initializing BlindPath integration components...")
            
            // 1. 初始化安全存储
            SecureStorage.initializeKey(context)
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
            startPowerMonitoring()
            Timber.d("PowerManager initialized")
            
            // 9. 获取模型预加载器实例
            val modelPreloader = ModelPreloader.getInstance(context)
            Timber.d("ModelPreloader initialized")
            
            // 10. 获取性能监控实例
            val performanceMonitor = PerformanceMonitor.getInstance()
            Timber.d("PerformanceMonitor initialized")
            
            // 11. 初始化分析管理器
            AnalyticsManager.initializeSession(context)
            Timber.d("AnalyticsManager initialized")
            
            isInitialized = true
            Timber.i("All integration components initialized successfully")
            
            Result.success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize integration components")
            Result.failure(e)
        }
    }
    
    /**
     * 启动网络监控
     */
    private fun startNetworkMonitoring() {
        scope.launch {
            networkMonitor.networkStatus.collectLatest { status ->
                when {
                    !status.isConnected -> {
                        Timber.w("Network disconnected, entering offline mode")
                        DegradationManager.setDegradationLevel(
                            DegradationManager.Feature.NETWORK,
                            DegradationLevel.OFFLINE
                        )
                    }
                    status.isConnected && DegradationManager.getDegradationLevel(DegradationManager.Feature.NETWORK) == DegradationLevel.OFFLINE -> {
                        Timber.i("Network restored, exiting offline mode")
                        DegradationManager.restoreFeature(DegradationManager.Feature.NETWORK)
                    }
                }
            }
        }
    }
    
    /**
     * 启动电量监控
     */
    private fun startPowerMonitoring() {
        scope.launch {
            powerManager.batteryState.collectLatest { state ->
                when {
                    state.level <= AppConfig.Power.CRITICAL_BATTERY_THRESHOLD -> {
                        Timber.w("Critical battery level: ${state.level}%")
                        DegradationManager.setDegradationLevel(
                            DegradationManager.Feature.AI_DETECTION,
                            DegradationLevel.DISABLED
                        )
                    }
                    state.level <= AppConfig.Power.LOW_BATTERY_THRESHOLD -> {
                        Timber.i("Low battery level: ${state.level}%")
                        DegradationManager.setDegradationLevel(
                            DegradationManager.Feature.AI_DETECTION,
                            DegradationLevel.REDUCED
                        )
                    }
                    state.isCharging -> {
                        Timber.i("Device charging, restoring normal mode")
                        DegradationManager.restoreFeature(DegradationManager.Feature.AI_DETECTION)
                    }
                }
            }
        }
    }
    
    /**
     * 预加载 AI 模型
     */
    suspend fun preloadModels(context: Context): Result<Boolean> {
        return try {
            Timber.i("Preloading AI models...")
            ModelPreloader.getInstance(context).preloadAll()
            Timber.i("AI models preloaded successfully")
            Result.success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to preload AI models")
            Result.failure(e)
        }
    }
    
    /**
     * 检查权限状态
     */
    fun checkRequiredPermissions(): List<PermissionManager.Permission> {
        val required = listOf(
            PermissionManager.Permission.CAMERA,
            PermissionManager.Permission.LOCATION,
            PermissionManager.Permission.RECORD_AUDIO,
            PermissionManager.Permission.PHONE
        )
        
        return required.filter { !permissionManager.isGranted(it) }
    }
    
    /**
     * 获取当前运行状态摘要
     */
    fun getStatusSummary(): StatusSummary {
        return StatusSummary(
            isInitialized = isInitialized,
            degradationLevel = DegradationManager.getDegradationLevel(DegradationManager.Feature.AI_DETECTION),
            batteryLevel = powerManager.batteryState.value.level,
            isCharging = powerManager.batteryState.value.isCharging,
            isNetworkConnected = networkMonitor.networkStatus.value.isConnected,
            currentLanguage = languageManager.selectedLanguage,
            fontSizeScale = accessibilitySettings.fontScale,
            isHighContrastEnabled = accessibilitySettings.isHighContrastEnabled,
            isPerformanceOptimal = PerformanceMonitor.getInstance().getReport().averageFrameTimeMs < 33
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
            CacheManager.getInstance().clearExpired()
            PerformanceMonitor.getInstance().release()
            Timber.i("BlindPath integration resources released")
        } catch (e: Exception) {
            Timber.e(e, "Error releasing integration resources")
        }
    }
}
