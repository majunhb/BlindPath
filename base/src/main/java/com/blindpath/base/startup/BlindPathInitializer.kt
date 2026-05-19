package com.blindpath.base.startup

import android.content.Context
import androidx.startup.AppInitializer
import androidx.startup.Initializer
import com.blindpath.base.analytics.AnalyticsManager
import com.blindpath.base.cache.CacheManager
import com.blindpath.base.common.BlindPathLog
import com.blindpath.base.config.AppConfig
import com.blindpath.base.performance.PerformanceMonitor
import com.blindpath.base.shortcuts.AppShortcutsManager
import com.blindpath.base.work.WorkScheduler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 应用启动初始化器
 * 
 * 使用 AndroidX Startup 库实现延迟初始化
 */
class BlindPathInitializer : Initializer<Unit> {
    
    override fun create(context: Context) {
        val startTime = System.currentTimeMillis()
        
        // 1. 初始化日志
        BlindPathLog.init(context)
        BlindPathLog.i("Startup", mapOf("event" to "initializer_start"))
        
        // 2. 加载配置
        loadConfig()
        
        // 3. 初始化核心组件
        initCoreComponents(context)
        
        val duration = System.currentTimeMillis() - startTime
        BlindPathLog.i("Startup", mapOf(
            "event" to "initializer_complete",
            "duration_ms" to duration
        ))
        
        // 4. 延迟初始化非核心组件
        initNonCriticalComponents(context)
    }
    
    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
    
    private fun loadConfig() {
        // 配置已在 AppConfig 对象中静态初始化
        BlindPathLog.i("Startup", mapOf("event" to "config_loaded"))
    }
    
    private fun initCoreComponents(context: Context) {
        // 核心组件同步初始化
        PerformanceMonitor.initialize(context)
    }
    
    private fun initNonCriticalComponents(context: Context) {
        // 非核心组件异步初始化
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // 缓存管理
                CacheManager.initialize(context)
                
                // 快捷方式
                AppShortcutsManager.updateShortcuts(context)
                
                // 后台任务
                WorkScheduler.initialize(context)
                
                // 分析
                AnalyticsManager.initialize(context)
                
                BlindPathLog.i("Startup", mapOf("event" to "non_critical_initialized"))
            } catch (e: Exception) {
                BlindPathLog.e("Startup", mapOf(
                    "event" to "non_critical_init_failed",
                    "error" to (e.message ?: "unknown")
                ), e)
            }
        }
    }
}

/**
 * 性能监控初始化器
 */
class PerformanceInitializer : Initializer<Unit> {
    override fun create(context: Context) {
        PerformanceMonitor.initialize(context)
    }
    
    override fun dependencies(): List<Class<out Initializer<*>>> {
        return emptyList()
    }
}

/**
 * 启动性能追踪工具
 */
object StartupTracker {
    
    private var startTime: Long = 0
    private val milestones = mutableMapOf<String, Long>()
    
    fun start() {
        startTime = System.currentTimeMillis()
    }
    
    fun milestone(name: String) {
        milestones[name] = System.currentTimeMillis() - startTime
    }
    
    fun report(): Map<String, Long> {
        val totalDuration = System.currentTimeMillis() - startTime
        return milestones + ("total" to totalDuration)
    }
    
    fun logReport() {
        val report = report()
        BlindPathLog.i("StartupTracker", mapOf(
            "event" to "startup_report",
            "milestones" to report.toString()
        ))
    }
}
