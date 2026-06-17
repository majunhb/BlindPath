package com.blindpath.base.common

import timber.log.Timber

/**
 * BlindPath 统一日志工具
 * 
 * 提供结构化日志支持，便于：
 * 1. 调试和问题排查
 * 2. 性能监控
 * 3. 用户行为分析
 * 
 * 日志级别：
 * - VERBOSE: 详细调试信息（仅 Debug 构建）
 * - DEBUG: 调试信息
 * - INFO: 重要信息
 * - WARN: 警告
 * - ERROR: 错误
 * 
 * 使用示例：
 * ```kotlin
 * // 基本日志
 * BlindPathLog.d("Obstacle", "Detected obstacle at distance: 2.5m")
 * 
 * // 带上下文的日志
 * BlindPathLog.i("Navigation", mapOf(
 *     "action" to "start",
 *     "destination" to destinationName
 * ))
 * 
 * // 错误日志
 * BlindPathLog.e("AI", "Model loading failed", exception)
 * ```
 */
object BlindPathLog {

    // ============ 初始化 ============
    
    private var isInitialized = false
    
    /**
     * 初始化日志系统
     * 在应用启动时调用
     */
    fun init(context: android.content.Context) {
        if (isInitialized) return
        isInitialized = true
        // Timber 初始化由 Application 完成
        i("BlindPathLog", mapOf("event" to "initialized"))
    }

    // ============ 模块标签 ============
    
    const val TAG_OBSTACLE = "Obstacle"
    const val TAG_NAVIGATION = "Navigation"
    const val TAG_VOICE = "Voice"
    const val TAG_AI = "AI"
    const val TAG_CAMERA = "Camera"
    const val TAG_LOCATION = "Location"
    const val TAG_SOS = "SOS"
    const val TAG_TTS = "TTS"
    const val TAG_VIBRATION = "Vibration"
    const val TAG_INDOOR = "Indoor"
    const val TAG_COMMUNITY = "Community"
    const val TAG_TRIP = "TripAssist"

    // ============ 基本日志方法 ============

    /**
     * 详细调试日志（仅 Debug 构建）
     */
    fun v(tag: String, message: String) {
        Timber.tag(tag).v(message)
    }

    /**
     * 调试日志
     */
    fun d(tag: String, message: String) {
        Timber.tag(tag).d(message)
    }

    /**
     * 信息日志
     */
    fun i(tag: String, message: String) {
        Timber.tag(tag).i(message)
    }

    /**
     * 警告日志
     */
    fun w(tag: String, message: String) {
        Timber.tag(tag).w(message)
    }

    /**
     * 错误日志
     */
    fun e(tag: String, message: String, throwable: Throwable? = null) {
        if (throwable != null) {
            Timber.tag(tag).e(throwable, message)
        } else {
            Timber.tag(tag).e(message)
        }
    }

    // ============ 结构化日志方法 ============

    /**
     * 带上下文数据的信息日志
     * 
     * @param tag 模块标签
     * @param context 上下文数据（会自动格式化为 key=value 形式）
     */
    fun i(tag: String, context: Map<String, Any?>) {
        val message = formatContext(context)
        Timber.tag(tag).i(message)
    }

    /**
     * 带上下文数据的调试日志
     */
    fun d(tag: String, context: Map<String, Any?>) {
        val message = formatContext(context)
        Timber.tag(tag).d(message)
    }

    /**
     * 带上下文数据的警告日志
     */
    fun w(tag: String, context: Map<String, Any?>) {
        val message = formatContext(context)
        Timber.tag(tag).w(message)
    }

    /**
     * 带上下文数据的错误日志
     */
    fun e(tag: String, context: Map<String, Any?>, throwable: Throwable? = null) {
        val message = formatContext(context)
        if (throwable != null) {
            Timber.tag(tag).e(throwable, message)
        } else {
            Timber.tag(tag).e(message)
        }
    }

    // ============ 性能监控日志 ============

    /**
     * 记录操作开始时间
     */
    private val operationStartTimes = mutableMapOf<String, Long>()

    /**
     * 开始计时
     */
    fun startTimer(operation: String) {
        operationStartTimes[operation] = System.currentTimeMillis()
        Timber.tag("Performance").d("[$operation] Started")
    }

    /**
     * 结束计时并记录耗时
     */
    fun endTimer(operation: String): Long {
        val startTime = operationStartTimes.remove(operation) ?: return 0
        val elapsed = System.currentTimeMillis() - startTime
        Timber.tag("Performance").i("[$operation] Completed in ${elapsed}ms")
        return elapsed
    }

    /**
     * 记录 FPS
     */
    fun logFps(module: String, fps: Int) {
        Timber.tag("Performance").d("[$module] FPS: $fps")
    }

    // ============ 障碍物检测专用日志 ============

    /**
     * 记录障碍物检测事件
     */
    fun obstacleDetected(
        type: String,
        distance: Float,
        confidence: Float,
        direction: String? = null
    ) {
        i(TAG_OBSTACLE, mapOf(
            "event" to "detected",
            "type" to type,
            "distance" to distance,
            "confidence" to confidence,
            "direction" to (direction ?: "front")
        ))
    }

    /**
     * 记录障碍物预警事件
     */
    fun obstacleAlert(level: String, description: String) {
        w(TAG_OBSTACLE, mapOf(
            "event" to "alert",
            "level" to level,
            "description" to description
        ))
    }

    // ============ 导航专用日志 ============

    /**
     * 记录导航状态变化
     */
    fun navigationState(action: String, details: Map<String, Any?> = emptyMap()) {
        val context = mutableMapOf<String, Any?>("action" to action)
        context.putAll(details)
        i(TAG_NAVIGATION, context)
    }

    /**
     * 记录 GPS 质量
     */
    fun gpsQuality(accuracy: Float, quality: String) {
        d(TAG_LOCATION, mapOf(
            "accuracy" to accuracy,
            "quality" to quality
        ))
    }

    // ============ AI 检测专用日志 ============

    /**
     * 记录 AI 模型状态
     */
    fun aiModelState(
        event: String,
        model: String? = null,
        success: Boolean = true,
        error: String? = null
    ) {
        val context = mutableMapOf<String, Any?>(
            "event" to event,
            "success" to success
        )
        model?.let { context["model"] = it }
        error?.let { context["error"] = it }
        
        if (success) {
            i(TAG_AI, context)
        } else {
            e(TAG_AI, context)
        }
    }

    /**
     * 记录 AI 推理性能
     */
    fun aiInference(inferenceTime: Long, objectsDetected: Int) {
        d(TAG_AI, mapOf(
            "inferenceTime" to inferenceTime,
            "objectsDetected" to objectsDetected
        ))
    }

    // ============ 语音专用日志 ============

    /**
     * 记录语音播报事件
     */
    fun voiceEvent(action: String, text: String? = null, queueMode: Boolean? = null) {
        val context = mutableMapOf<String, Any?>("action" to action)
        text?.let { context["text"] = it.take(50) } // 截取前50字符
        queueMode?.let { context["queueMode"] = it }
        d(TAG_VOICE, context)
    }

    // ============ 辅助方法 ============

    /**
     * 格式化上下文数据
     */
    private fun formatContext(context: Map<String, Any?>): String {
        return context.entries
            .filter { it.value != null }
            .joinToString(", ") { "${it.key}=${it.value}" }
    }
}
