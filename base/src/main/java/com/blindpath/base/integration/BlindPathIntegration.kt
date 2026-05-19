package com.blindpath.base.integration

import android.content.Context
import com.blindpath.base.accessibility.AccessibilitySettings
import com.blindpath.base.accessibility.FontSizeScale
import com.blindpath.base.analytics.AnalyticsManager
import com.blindpath.base.cache.CacheManager
import com.blindpath.base.config.AppConfig
import com.blindpath.base.error.BlindPathError
import com.blindpath.base.error.DegradationManager
import com.blindpath.base.error.DegradationLevel
import com.blindpath.base.i18n.Language
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
            SecureStorage.initialize(context)
            Timber.d("SecureStorage initialized")
            
            // 2. 初始化缓存管理器
            CacheManager.initialize(context)
            Timber.d("CacheManager initialized")
            
            // 3. 初始化语言管理器
            LanguageManager.initialize(context)
            Timber.d("LanguageManager initialized")
            
            // 4. 初始化无障碍设置
            AccessibilitySettings.initialize(context)
            Timber.d("AccessibilitySettings initialized")
            
            // 5. 初始化权限管理器
            PermissionManager.initialize(context)
            Timber.d("PermissionManager initialized")
            
            // 6. 初始化隐私管理器
            PrivacyManager.initialize(context)
            Timber.d("PrivacyManager initialized")
            
            // 7. 初始化网络监控
            NetworkMonitor.initialize(context)
            startNetworkMonitoring()
            Timber.d("NetworkMonitor initialized")
            
            // 8. 初始化电量管理
            PowerManager.initialize(context)
            startPowerMonitoring()
            Timber.d("PowerManager initialized")
            
            // 9. 初始化模型预加载器
            ModelPreloader.initialize(context)
            Timber.d("ModelPreloader initialized")
            
            // 10. 初始化性能监控
            PerformanceMonitor.initialize()
            Timber.d("PerformanceMonitor initialized")
            
            // 11. 初始化分析管理器
            AnalyticsManager.initialize(context)
            Timber.d("AnalyticsManager initialized")
            
            isInitialized = true
            Timber.i("All integration components initialized successfully")
            
            // 记录启动事件
            AnalyticsManager.logEvent("app_started", mapOf(
                "version" to AppConfig.App.VERSION_NAME,
                "language" to LanguageManager.currentLanguage.value.code
            ))
            
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
            NetworkMonitor.networkStatus.collectLatest { status ->
                when {
                    !status.isConnected -> {
                        Timber.w("Network disconnected, entering offline mode")
                        DegradationManager.degrade(DegradationLevel.OFFLINE)
                        
                        AnalyticsManager.logEvent("network_offline", mapOf(
                            "was_wifi" to status.isWifi,
                            "was_cellular" to status.isCellular
                        ))
                    }
                    status.isConnected && DegradationManager.currentLevel.value == DegradationLevel.OFFLINE -> {
                        Timber.i("Network restored, exiting offline mode")
                        DegradationManager.promote(DegradationLevel.NORMAL)
                        
                        AnalyticsManager.logEvent("network_restored", mapOf(
                            "is_wifi" to status.isWifi,
                            "is_cellular" to status.isCellular
                        ))
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
            PowerManager.batteryState.collectLatest { state ->
                when {
                    state.level <= AppConfig.Power.CRITICAL_BATTERY_THRESHOLD -> {
                        Timber.w("Critical battery level: ${state.level}%")
                        DegradationManager.degrade(DegradationLevel.MINIMAL)
                        
                        AnalyticsManager.logEvent("battery_critical", mapOf(
                            "level" to state.level
                        ))
                    }
                    state.level <= AppConfig.Power.LOW_BATTERY_THRESHOLD -> {
                        Timber.i("Low battery level: ${state.level}%")
                        DegradationManager.degrade(DegradationLevel.LOW_POWER)
                        
                        AnalyticsManager.logEvent("battery_low", mapOf(
                            "level" to state.level
                        ))
                    }
                    state.isCharging -> {
                        Timber.i("Device charging, restoring normal mode")
                        DegradationManager.promote(DegradationLevel.NORMAL)
                    }
                }
            }
        }
    }
    
    /**
     * 预加载 AI 模型
     */
    suspend fun preloadModels(): Result<Boolean> {
        return try {
            Timber.i("Preloading AI models...")
            ModelPreloader.preloadAll()
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
        
        return required.filter { !PermissionManager.isGranted(it) }
    }
    
    /**
     * 获取当前运行状态摘要
     */
    fun getStatusSummary(): StatusSummary {
        return StatusSummary(
            isInitialized = isInitialized,
            degradationLevel = DegradationManager.currentLevel.value,
            batteryLevel = PowerManager.batteryState.value.level,
            isCharging = PowerManager.batteryState.value.isCharging,
            isNetworkConnected = NetworkMonitor.networkStatus.value.isConnected,
            currentLanguage = LanguageManager.currentLanguage.value,
            fontSizeScale = AccessibilitySettings.fontSizeScale.value,
            isHighContrastEnabled = AccessibilitySettings.highContrastEnabled.value,
            isPerformanceOptimal = PerformanceMonitor.getReport().averageFrameTimeMs < 33
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
        val currentLanguage: Language,
        val fontSizeScale: FontSizeScale,
        val isHighContrastEnabled: Boolean,
        val isPerformanceOptimal: Boolean
    )
    
    /**
     * 释放资源
     */
    fun release() {
        try {
            CacheManager.clearExpired()
            PerformanceMonitor.release()
            Timber.i("BlindPath integration resources released")
        } catch (e: Exception) {
            Timber.e(e, "Error releasing integration resources")
        }
    }
}
