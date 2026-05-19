package com.blindpath.base.analytics

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 分析管理器
 * 收集用户行为数据，用于改进产品体验
 */
class AnalyticsManager(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "analytics_settings"
        private const val KEY_ENABLED = "analytics_enabled"
        private const val KEY_SESSION_COUNT = "session_count"
        private const val KEY_FIRST_RUN = "first_run"
        
        @Volatile
        private var instance: AnalyticsManager? = null
        
        fun getInstance(context: Context): AnalyticsManager {
            return instance ?: synchronized(this) {
                instance ?: AnalyticsManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val eventQueue = ConcurrentLinkedQueue<AnalyticsEvent>()
    
    /**
     * 分析是否启用
     */
    var isEnabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) {
            prefs.edit().putBoolean(KEY_ENABLED, value).apply()
        }
    
    /**
     * 会话计数
     */
    val sessionCount: Int
        get() = prefs.getInt(KEY_SESSION_COUNT, 0)
    
    /**
     * 是否首次运行
     */
    val isFirstRun: Boolean
        get() = prefs.getBoolean(KEY_FIRST_RUN, true)
    
    /**
     * 初始化会话
     */
    fun initializeSession() {
        // 标记非首次运行
        if (isFirstRun) {
            prefs.edit().putBoolean(KEY_FIRST_RUN, false).apply()
        }
        
        // 增加会话计数
        prefs.edit().putInt(KEY_SESSION_COUNT, sessionCount + 1).apply()
        
        // 记录会话开始事件
        logEvent(EventName.SESSION_START, mapOf(
            Param.SESSION_NUMBER to sessionCount,
            Param.IS_FIRST_RUN to isFirstRun
        ))
    }
    
    /**
     * 记录事件
     */
    fun logEvent(name: String, params: Map<String, Any> = emptyMap()) {
        if (!isEnabled) return
        
        val event = AnalyticsEvent(
            name = name,
            params = params,
            timestamp = System.currentTimeMillis()
        )
        
        eventQueue.add(event)
        Timber.d("Event logged: $name, params: $params")
        
        // 队列超过阈值时批量上报
        if (eventQueue.size >= 50) {
            flush()
        }
    }
    
    /**
     * 记录屏幕浏览
     */
    fun logScreenView(screenName: String) {
        logEvent(EventName.SCREEN_VIEW, mapOf(
            Param.SCREEN_NAME to screenName
        ))
    }
    
    /**
     * 记录功能使用
     */
    fun logFeatureUsage(feature: String, action: String) {
        logEvent(EventName.FEATURE_USAGE, mapOf(
            Param.FEATURE_NAME to feature,
            Param.ACTION to action
        ))
    }
    
    /**
     * 记录错误
     */
    fun logError(errorType: String, message: String) {
        logEvent(EventName.ERROR, mapOf(
            Param.ERROR_TYPE to errorType,
            Param.ERROR_MESSAGE to message
        ))
    }
    
    /**
     * 记录性能指标
     */
    fun logPerformance(metric: String, value: Long, unit: String = "ms") {
        logEvent(EventName.PERFORMANCE, mapOf(
            Param.METRIC_NAME to metric,
            Param.METRIC_VALUE to value,
            Param.UNIT to unit
        ))
    }
    
    /**
     * 刷新事件队列（上报到服务器）
     */
    fun flush() {
        if (eventQueue.isEmpty()) return
        
        val events = mutableListOf<AnalyticsEvent>()
        while (eventQueue.isNotEmpty()) {
            eventQueue.poll()?.let { events.add(it) }
        }
        
        // 这里应该上报到服务器，现在只记录日志
        Timber.d("Flushing ${events.size} analytics events")
        events.forEach { event ->
            Timber.d("Event: ${event.name} at ${event.timestamp}")
        }
    }
    
    /**
     * 设置用户属性
     */
    fun setUserProperty(name: String, value: String) {
        prefs.edit().putString("user_prop_$name", value).apply()
    }
    
    /**
     * 获取用户属性
     */
    fun getUserProperty(name: String): String? {
        return prefs.getString("user_prop_$name", null)
    }
    
    /**
     * 清除所有分析数据
     */
    fun clearAllData() {
        eventQueue.clear()
        prefs.edit().clear().apply()
    }
    
    /**
     * 分析事件
     */
    data class AnalyticsEvent(
        val name: String,
        val params: Map<String, Any>,
        val timestamp: Long
    )
}

/**
 * 事件名称定义
 */
object EventName {
    const val SESSION_START = "session_start"
    const val SESSION_END = "session_end"
    const val SCREEN_VIEW = "screen_view"
    const val FEATURE_USAGE = "feature_usage"
    const val ERROR = "error"
    const val PERFORMANCE = "performance"
    
    // 障碍物检测
    const val OBSTACLE_DETECTED = "obstacle_detected"
    const val OBSTACLE_ALERT = "obstacle_alert"
    const val DETECTION_SESSION = "detection_session"
    
    // 导航
    const val NAVIGATION_START = "navigation_start"
    const val NAVIGATION_END = "navigation_end"
    const val NAVIGATION_ERROR = "navigation_error"
    
    // 语音
    const val VOICE_COMMAND = "voice_command"
    const val TTS_USED = "tts_used"
    
    // SOS
    const val SOS_TRIGGERED = "sos_triggered"
    const val SOS_CANCELLED = "sos_cancelled"
}

/**
 * 参数名称定义
 */
object Param {
    const val SESSION_NUMBER = "session_number"
    const val IS_FIRST_RUN = "is_first_run"
    const val SCREEN_NAME = "screen_name"
    const val FEATURE_NAME = "feature_name"
    const val ACTION = "action"
    const val ERROR_TYPE = "error_type"
    const val ERROR_MESSAGE = "error_message"
    const val METRIC_NAME = "metric_name"
    const val METRIC_VALUE = "metric_value"
    const val UNIT = "unit"
    
    // 障碍物检测
    const val OBSTACLE_TYPE = "obstacle_type"
    const val OBSTACLE_DISTANCE = "obstacle_distance"
    const val ALERT_LEVEL = "alert_level"
    const val DETECTION_DURATION = "detection_duration"
    
    // 导航
    const val DESTINATION = "destination"
    const val ROUTE_DISTANCE = "route_distance"
    const val NAVIGATION_DURATION = "navigation_duration"
    
    // 语音
    const val COMMAND_TYPE = "command_type"
    const val COMMAND_SUCCESS = "command_success"
    
    // SOS
    const val SOS_METHOD = "sos_method"
    const val SOS_CONTACTS_COUNT = "sos_contacts_count"
}

/**
 * 用户属性定义
 */
object UserProperty {
    const val USER_TYPE = "user_type"
    const val LANGUAGE = "language"
    const val DEVICE_TYPE = "device_type"
    const val APP_VERSION = "app_version"
    const val FIRST_LAUNCH_DATE = "first_launch_date"
    const val TOTAL_DETECTIONS = "total_detections"
    const val TOTAL_NAVIGATIONS = "total_navigations"
}
