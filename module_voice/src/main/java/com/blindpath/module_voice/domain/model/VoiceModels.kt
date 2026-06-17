package com.blindpath.module_voice.domain.model

/**
 * 语音播报优先级
 * 
 * 设计原则：
 * - P0（紧急）：立即打断当前播报，用于生命安全相关预警
 * - P1（重要）：等待当前句子完成后播报，用于关键导航指令
 * - P2（一般）：加入队列顺序播报，用于普通提示信息
 * - P3（背景）：仅在空闲时播报，用于系统状态通知
 */
enum class VoicePriority(val level: Int, val description: String) {
    EMERGENCY(0, "紧急"),      // 危险障碍物、跌倒检测、SOS 求助
    IMPORTANT(1, "重要"),      // 导航关键指令、场景变化
    NORMAL(2, "一般"),         // 普通障碍物提示、环境信息
    BACKGROUND(3, "背景");     // 系统状态、电量提示

    /**
     * 是否允许打断当前播报
     */
    fun shouldInterrupt(): Boolean = this == EMERGENCY

    /**
     * 是否需要等待当前句子完成
     */
    fun shouldWaitForSentence(): Boolean = this == IMPORTANT

    /**
     * 获取播报冷却时间（毫秒）
     */
    fun getCooldownMs(): Long = when (this) {
        EMERGENCY -> 1000      // 紧急预警 1 秒冷却
        IMPORTANT -> 3000      // 重要信息 3 秒冷却
        NORMAL -> 5000         // 一般信息 5 秒冷却
        BACKGROUND -> 10000    // 背景信息 10 秒冷却
    }
}

/**
 * 语音播报类型
 * 
 * 根据功能模块分类，便于管理和统计
 */
enum class VoiceType(val priority: VoicePriority) {
    // 紧急预警（P0）
    OBSTACLE_DANGER(VoicePriority.EMERGENCY),       // 危险障碍物预警
    FALL_DETECTED(VoicePriority.EMERGENCY),         // 跌倒检测
    SOS_TRIGGERED(VoicePriority.EMERGENCY),         // SOS 求助触发

    // 重要信息（P1）
    NAVIGATION_TURN(VoicePriority.IMPORTANT),       // 导航转弯指令
    NAVIGATION_ARRIVE(VoicePriority.IMPORTANT),     // 导航到达提示
    SCENE_CHANGE(VoicePriority.IMPORTANT),          // 场景变化（进入路口、楼梯等）
    TRAFFIC_LIGHT(VoicePriority.IMPORTANT),         // 红绿灯识别

    // 一般信息（P2）
    OBSTACLE_NORMAL(VoicePriority.NORMAL),          // 普通障碍物提示
    OBSTACLE_LOW(VoicePriority.NORMAL),             // 低危障碍物
    ENVIRONMENT_INFO(VoicePriority.NORMAL),         // 环境信息
    NAVIGATION_PROGRESS(VoicePriority.NORMAL),      // 导航进度提示

    // 背景信息（P3）
    SYSTEM_STATUS(VoicePriority.BACKGROUND),        // 系统状态
    BATTERY_LOW(VoicePriority.BACKGROUND),          // 电量提示
    MODE_CHANGE(VoicePriority.BACKGROUND);          // 模式切换
}

/**
 * 语音播报请求
 */
data class VoiceRequest(
    val text: String,
    val type: VoiceType,
    val priority: VoicePriority = type.priority,
    val timestamp: Long = System.currentTimeMillis(),
    val deduplicationKey: String? = null,  // 去重键（相同键的内容会被去重）
    val interruptCurrent: Boolean = priority.shouldInterrupt()
)

/**
 * 语音状态
 */
data class VoiceState(
    val isAvailable: Boolean = false,
    val isSpeaking: Boolean = false,
    val isListening: Boolean = false,
    val isWakeUp: Boolean = false,
    val currentPriority: VoicePriority? = null,  // 当前播报的优先级
    val queueSize: Int = 0,                       // 队列大小
    val lastError: String? = null
)

/**
 * 播报统计信息
 */
data class VoiceStatistics(
    val totalAnnouncements: Int = 0,
    val emergencyCount: Int = 0,
    val importantCount: Int = 0,
    val normalCount: Int = 0,
    val backgroundCount: Int = 0,
    val deduplicatedCount: Int = 0,  // 去重次数
    val interruptedCount: Int = 0    // 打断次数
)
