package com.blindpath.base.config

/**
 * BlindPath 应用配置
 * 
 * 集中管理所有可配置参数，便于：
 * 1. 根据设备性能调整参数
 * 2. A/B 测试不同配置
 * 3. 用户个性化设置
 * 4. 快速调试和优化
 */
object AppConfig {
    // ============ AI 检测配置 ============
    
    object AIDetection {
        /** 模型文件名 */
        const val MODEL_NAME = "yolov8n.tflite"
        
        /** 模型输入尺寸 */
        const val INPUT_SIZE = 320
        
        /** 推理线程数 */
        const val NUM_THREADS = 8
        
        /** 置信度阈值 (0.0-1.0) - 0.4 平衡检出率与误报 */
        const val CONFIDENCE_THRESHOLD = 0.4f

        /** IoU 阈值 (0.0-1.0) */
        const val IOU_THRESHOLD = 0.45f
        
        /** 是否启用 GPU 加速 */
        const val ENABLE_GPU = true
        
        /** 是否启用 XNNPACK 加速 */
        const val ENABLE_XNNPACK = true
        
        /** 跳帧处理：每 N 帧处理 1 帧 */
        const val FRAME_SKIP = 3
    }

    // ============ 障碍物预警配置 ============
    
    object ObstacleAlert {
        /** 预警冷却时间（毫秒） */
        const val ALERT_COOLDOWN_MS = 2000L
        
        /** 场景播报冷却时间（毫秒） */
        const val SCENE_COOLDOWN_MS = 8000L
        
        /** 多障碍物播报间隔（毫秒） */
        const val MULTI_OBSTACLE_COOLDOWN_MS = 3000L
        
        /** 危险距离阈值（米） */
        const val DANGER_DISTANCE = 0.5f
        
        /** 警告距离阈值（米） */
        const val WARNING_DISTANCE = 2.0f
        
        /** 安全距离阈值（米） */
        const val SAFE_DISTANCE = 5.0f
        
        /** 服务预警最小间隔（毫秒） */
        const val SERVICE_ALERT_MIN_INTERVAL_MS = 3000L
    }

    // ============ 导航配置 ============
    
    object Navigation {
        /** GPS 更新最小间隔（毫秒） */
        const val LOCATION_UPDATE_INTERVAL_MS = 1000L
        
        /** GPS 更新最小距离（米） */
        const val MIN_UPDATE_DISTANCE_M = 0.5f
        
        /** GPS 精度播报间隔（毫秒） */
        const val ACCURACY_ANNOUNCE_INTERVAL_MS = 8000L
        
        /** 导航指令距离变化阈值（米） */
        const val INSTRUCTION_DISTANCE_THRESHOLD = 3
        
        /** 
         * GPS 质量分级阈值（米）
         * EXCELLENT: ≤ EXCELLENT_THRESHOLD
         * GOOD: ≤ GOOD_THRESHOLD
         * FAIR: ≤ FAIR_THRESHOLD
         * POOR: > FAIR_THRESHOLD
         */
        const val EXCELLENT_THRESHOLD = 1.0f
        const val GOOD_THRESHOLD = 3.0f
        const val FAIR_THRESHOLD = 10.0f

        /** GPS 精度常量别名（供 GpsQuality 等模块引用） */
        const val GPS_ACCURACY_EXCELLENT = EXCELLENT_THRESHOLD
        const val GPS_ACCURACY_GOOD = GOOD_THRESHOLD
        const val GPS_ACCURACY_FAIR = FAIR_THRESHOLD
    }

    // ============ 语音配置 ============
    
    object Voice {
        /** 语速 (0.5-2.0, 1.0 为正常) */
        const val SPEECH_RATE = 0.9f
        
        /** 音调 (0.5-2.0, 1.0 为正常) */
        const val PITCH = 1.0f
        
        /** 最大播报文本长度（字符） */
        const val MAX_ANNOUNCEMENT_LENGTH = 100
    }

    // ============ 振动配置 ============
    
    object Vibration {
        /** 危险振动模式（毫秒）：[等待, 振动, 等待, 振动, ...] */
        val DANGER_PATTERN = longArrayOf(0, 500, 200, 500, 200, 500)
        
        /** 警告振动模式（毫秒） */
        val WARNING_PATTERN = longArrayOf(0, 300, 200, 300)
        
        /** 安全振动模式（毫秒） */
        val SAFE_PATTERN = longArrayOf(0, 150)
    }

    // ============ UI 配置 ============
    
    object UI {
        /** 主按钮最小触摸尺寸（dp） */
        const val MIN_TOUCH_SIZE_DP = 48
        
        /** 列表项间距（dp） */
        const val LIST_ITEM_SPACING_DP = 16
        
        /** 页面边距（dp） */
        const val PAGE_PADDING_DP = 24
    }

    // ============ 调试配置 ============
    
    object Debug {
        /** 是否启用详细日志 */
        const val VERBOSE_LOGGING = false
        
        /** 是否启用性能监控 */
        const val ENABLE_PERFORMANCE_MONITORING = false
        
        /** 是否显示检测框（调试用） */
        const val SHOW_DETECTION_BOXES = false
        
        /** 是否显示 FPS */
        const val SHOW_FPS = false
    }

    // ============ SOS 配置 ============
    
    object SOS {
        /** 默认紧急联系人 */
        val DEFAULT_EMERGENCY_CONTACTS = listOf("110")
        
        /** SOS 消息前缀 */
        const val SOS_MESSAGE_PREFIX = "【紧急求助】"
    }
    
    // ============ 帧率配置 ============
    
    object FrameRate {
        /** 高性能模式目标帧率 */
        const val HIGH_FPS = 30
        
        /** 中等性能模式目标帧率 */
        const val MEDIUM_FPS = 20
        
        /** 低性能模式目标帧率 */
        const val LOW_FPS = 15
        
        /** 最大处理时间阈值（毫秒），超过则降低帧率 */
        const val MAX_PROCESSING_TIME_MS = 100f
        
        /** 自适应帧率启用 */
        const val ADAPTIVE_ENABLED = true
    }
    
    // ============ 省电配置 ============
    
    object PowerSaving {
        /** 低电量阈值（百分比） */
        const val LOW_BATTERY_THRESHOLD = 20
        
        /** 省电模式下的GPS更新间隔（毫秒） */
        const val POWER_SAVE_LOCATION_INTERVAL_MS = 3000L
        
        /** 省电模式下的帧率 */
        const val POWER_SAVE_FPS = 10
        
        /** 是否在低电量时自动启用省电模式 */
        const val AUTO_POWER_SAVE = true
    }
}
