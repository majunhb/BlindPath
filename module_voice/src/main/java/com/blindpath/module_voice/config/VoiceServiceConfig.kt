package com.blindpath.module_voice.config

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder

/**
 * 语音服务统一配置中心
 * 
 * 管理所有语音相关参数，包括：
 * - 音频采集参数
 * - 唤醒词配置
 * - 引擎选择策略
 * - 性能阈值
 * - 多场景适配参数
 */
object VoiceServiceConfig {
    
    // ==================== 音频采集参数 ====================
    
    /**
     * 采样率：16kHz（语音识别标准）
     */
    const val SAMPLE_RATE = 16000
    
    /**
     * 声道配置：单声道
     */
    const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
    
    /**
     * 音频格式：16-bit PCM
     */
    const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    
    /**
     * 音频源：语音识别优化
     */
    const val AUDIO_SOURCE = MediaRecorder.AudioSource.VOICE_RECOGNITION
    
    /**
     * 缓冲区大小倍数（相对于最小缓冲区）
     */
    const val BUFFER_SIZE_FACTOR = 2
    
    /**
     * 获取音频缓冲区大小
     */
    fun getBufferSize(context: Context): Int {
        val minSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        return minSize * BUFFER_SIZE_FACTOR
    }
    
    // ==================== 唤醒词配置 ====================
    
    /**
     * 主唤醒词
     */
    const val WAKE_WORD_PRIMARY = "小志小志"
    
    /**
     * 备用唤醒词（可选）
     */
    const val WAKE_WORD_SECONDARY = "你好小志"
    
    /**
     * 唤醒词检测灵敏度（1-1000）
     * - 百度语音：500 为中等，建议 600-800
     */
    const val WAKE_SENSITIVITY = 700
    
    /**
     * 唤醒超时时间（毫秒）
     * 超时后自动停止检测，节省电量
     */
    const val WAKE_TIMEOUT_MS = 30_000L
    
    /**
     * 唤醒成功后的提示音延迟（毫秒）
     */
    const val WAKE_FEEDBACK_DELAY_MS = 100L
    
    // ==================== 引擎选择策略 ====================
    
    /**
     * 语音引擎类型
     */
    enum class EngineType {
        BAIDU,      // 百度语音（主）
        XUNFEI,     // 科大讯飞（备选）
        ENERGY      // 能量检测（降级）
    }
    
    /**
     * 当前首选引擎
     */
    var preferredEngine = EngineType.BAIDU
    
    /**
     * 引擎切换阈值
     * 连续失败此次数后自动切换引擎
     */
    const val ENGINE_SWITCH_THRESHOLD = 3
    
    /**
     * 引擎重试间隔（毫秒）
     */
    const val ENGINE_RETRY_INTERVAL_MS = 5_000L
    
    // ==================== 性能阈值 ====================
    
    /**
     * 目标唤醒成功率（百分比）
     */
    const val TARGET_WAKE_SUCCESS_RATE = 98
    
    /**
     * 唤醒响应时间目标（毫秒）
     */
    const val TARGET_WAKE_LATENCY_MS = 800L
    
    /**
     * 误唤醒率阈值（每小时）
     */
    const val FALSE_WAKE_THRESHOLD_PER_HOUR = 1
    
    /**
     * 音量检测阈值（用于能量检测降级方案）
     */
    const val ENERGY_THRESHOLD = 2000
    
    /**
     * 音量检测窗口大小（采样点数）
     */
    const val ENERGY_WINDOW_SIZE = 1024
    
    // ==================== 后台保活配置 ====================
    
    /**
     * 前台服务通知渠道 ID
     */
    const val NOTIFICATION_CHANNEL_ID = "blindpath_voice_channel"
    
    /**
     * 前台服务通知 ID
     */
    const val NOTIFICATION_ID = 1001
    
    /**
     * 保活心跳间隔（毫秒）
     */
    const val KEEP_ALIVE_INTERVAL_MS = 60_000L
    
    /**
     * 是否启用自动重启
     */
    var enableAutoRestart = true
    
    /**
     * 自动重启最大次数
     */
    const val AUTO_RESTART_MAX_COUNT = 3
    
    /**
     * 自动重启冷却时间（毫秒）
     */
    const val AUTO_RESTART_COOLDOWN_MS = 10_000L
    
    // ==================== 音频焦点管理 ====================
    
    /**
     * 音频焦点获取超时（毫秒）
     */
    const val AUDIO_FOCUS_TIMEOUT_MS = 3_000L
    
    /**
     * TalkBack 兼容模式等待时间（毫秒）
     */
    const val TALKBACK_WAIT_MS = 500L
    
    /**
     * 蓝牙 SCO 连接超时（毫秒）
     */
    const val BLUETOOTH_SCO_TIMEOUT_MS = 5_000L
    
    // ==================== 多场景适配参数 ====================
    
    /**
     * 场景类型
     */
    enum class SceneType {
        NORMAL,         // 普通模式
        OUTDOOR_NOISY,  // 户外嘈杂
        INDOOR_QUIET,   // 室内安静
        BLUETOOTH,      // 蓝牙耳机
        LOW_BATTERY     // 低电量模式
    }
    
    /**
     * 场景参数配置
     */
    data class SceneConfig(
        val sensitivity: Int,
        val audioSource: Int,
        val enableNoiseSuppression: Boolean,
        val enableAgc: Boolean,              // 自动增益控制
        val wakeTimeoutMs: Long
    )
    
    /**
     * 默认场景配置映射
     */
    val sceneConfigs = mapOf(
        SceneType.NORMAL to SceneConfig(
            sensitivity = 700,
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            enableNoiseSuppression = true,
            enableAgc = true,
            wakeTimeoutMs = 30_000L
        ),
        SceneType.OUTDOOR_NOISY to SceneConfig(
            sensitivity = 900,  // 提高灵敏度
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            enableNoiseSuppression = true,
            enableAgc = true,
            wakeTimeoutMs = 60_000L
        ),
        SceneType.INDOOR_QUIET to SceneConfig(
            sensitivity = 500,  // 降低灵敏度，减少误唤醒
            audioSource = MediaRecorder.AudioSource.MIC,
            enableNoiseSuppression = false,
            enableAgc = false,
            wakeTimeoutMs = 30_000L
        ),
        SceneType.BLUETOOTH to SceneConfig(
            sensitivity = 700,
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            enableNoiseSuppression = true,
            enableAgc = true,
            wakeTimeoutMs = 30_000L
        ),
        SceneType.LOW_BATTERY to SceneConfig(
            sensitivity = 600,  // 中等灵敏度
            audioSource = MediaRecorder.AudioSource.VOICE_RECOGNITION,
            enableNoiseSuppression = false,  // 禁用降噪节省电量
            enableAgc = false,
            wakeTimeoutMs = 15_000L  // 缩短超时时间
        )
    )
    
    /**
     * 获取当前场景配置
     */
    fun getSceneConfig(sceneType: SceneType): SceneConfig {
        return sceneConfigs[sceneType] ?: sceneConfigs[SceneType.NORMAL]!!
    }
    
    // ==================== 调试与日志配置 ====================
    
    /**
     * 是否启用调试模式
     */
    var enableDebugMode = false
    
    /**
     * 是否记录音频数据（用于问题诊断）
     */
    var enableAudioRecording = false
    
    /**
     * 日志级别
     */
    enum class LogLevel {
        VERBOSE,
        DEBUG,
        INFO,
        WARNING,
        ERROR
    }
    
    /**
     * 当前日志级别
     */
    var logLevel = LogLevel.DEBUG
    
    // ==================== 百度语音 SDK 配置 ====================
    
    /**
     * 百度语音唤醒模型文件名
     */
    const val BAIDU_WAKE_MODEL_FILE = "baidu_wake_words.bin"
    
    /**
     * 百度语音识别语言
     */
    const val BAIDU_LANGUAGE = "cmn-Hans-CN"  // 普通话
    
    /**
     * 百度语音识别采样率
     */
    const val BAIDU_SAMPLE_RATE = 16000
    
    // ==================== 性能监控配置 ====================
    
    /**
     * 是否启用性能监控
     */
    var enablePerformanceMonitor = true
    
    /**
     * 性能数据上报间隔（毫秒）
     */
    const val PERFORMANCE_REPORT_INTERVAL_MS = 60_000L
    
    /**
     * 性能数据保留时长（毫秒）
     */
    const val PERFORMANCE_DATA_RETENTION_MS = 24 * 60 * 60 * 1000L  // 24小时
}
