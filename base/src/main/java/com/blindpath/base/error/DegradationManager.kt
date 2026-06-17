package com.blindpath.base.error

import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap

/**
 * 降级策略管理器
 * 当某功能不可用时，自动切换到降级模式
 */
object DegradationManager {
    
    private val degradedFeatures = ConcurrentHashMap<Feature, DegradationLevel>()
    private val listeners = mutableListOf<DegradationListener>()
    
    /**
     * 功能模块
     */
    enum class Feature {
        AI_DETECTION,      // AI 障碍物检测
        CAMERA,            // 摄像头
        GPS_NAVIGATION,    // GPS 导航
        TTS_VOICE,         // 语音播报
        NETWORK,           // 网络功能
        STORAGE,           // 存储功能
        COMMUNITY          // 社区功能
    }
    
    /**
     * 降级级别
     */
    enum class DegradationLevel {
        NORMAL,           // 正常模式
        REDUCED,          // 功能受限模式
        OFFLINE,          // 离线模式
        DISABLED          // 功能禁用
    }
    
    /**
     * 获取功能当前降级级别
     */
    fun getDegradationLevel(feature: Feature): DegradationLevel {
        return degradedFeatures[feature] ?: DegradationLevel.NORMAL
    }
    
    /**
     * 检查功能是否可用
     */
    fun isFeatureAvailable(feature: Feature): Boolean {
        return getDegradationLevel(feature) != DegradationLevel.DISABLED
    }
    
    /**
     * 检查功能是否处于降级模式
     */
    fun isFeatureDegraded(feature: Feature): Boolean {
        return getDegradationLevel(feature) != DegradationLevel.NORMAL
    }
    
    /**
     * 设置功能降级级别
     */
    fun setDegradationLevel(feature: Feature, level: DegradationLevel, reason: String? = null) {
        val previousLevel = degradedFeatures[feature] ?: DegradationLevel.NORMAL
        if (previousLevel == level) return
        
        degradedFeatures[feature] = level
        
        Timber.w("Feature $feature degraded from $previousLevel to $level. Reason: $reason")
        
        // 通知监听器
        listeners.forEach { listener ->
            try {
                listener.onDegradationChanged(feature, previousLevel, level, reason)
            } catch (e: Exception) {
                Timber.e(e, "Error in degradation listener")
            }
        }
    }
    
    /**
     * 恢复功能到正常模式
     */
    fun restoreFeature(feature: Feature) {
        val previousLevel = degradedFeatures.remove(feature) ?: DegradationLevel.NORMAL
        if (previousLevel == DegradationLevel.NORMAL) return
        
        Timber.i("Feature $feature restored to normal mode")
        
        listeners.forEach { listener ->
            try {
                listener.onDegradationChanged(feature, previousLevel, DegradationLevel.NORMAL, "Feature restored")
            } catch (e: Exception) {
                Timber.e(e, "Error in degradation listener")
            }
        }
    }
    
    /**
     * 根据错误自动设置降级策略
     */
    fun handleDegradation(error: BlindPathError) {
        when (error) {
            // AI 相关错误 -> 降级到基础检测
            is BlindPathError.ModelLoadError,
            is BlindPathError.ModelNotFoundError,
            is BlindPathError.InferenceError -> {
                setDegradationLevel(
                    Feature.AI_DETECTION,
                    DegradationLevel.REDUCED,
                    error.message
                )
            }
            
            // 摄像头错误 -> 禁用摄像头功能
            is BlindPathError.CameraPermissionDenied,
            is BlindPathError.CameraBusy,
            is BlindPathError.CameraInitError -> {
                setDegradationLevel(
                    Feature.CAMERA,
                    DegradationLevel.DISABLED,
                    error.message
                )
            }
            
            // GPS 错误 -> 降级或禁用导航
            is BlindPathError.GpsPermissionDenied -> {
                setDegradationLevel(
                    Feature.GPS_NAVIGATION,
                    DegradationLevel.DISABLED,
                    error.message
                )
            }
            is BlindPathError.GpsDisabled,
            is BlindPathError.GpsSignalWeak -> {
                setDegradationLevel(
                    Feature.GPS_NAVIGATION,
                    DegradationLevel.REDUCED,
                    error.message
                )
            }
            
            // TTS 错误 -> 禁用语音或降级
            is BlindPathError.TtsInitError -> {
                setDegradationLevel(
                    Feature.TTS_VOICE,
                    DegradationLevel.DISABLED,
                    error.message
                )
            }
            is BlindPathError.TtsLanguageNotSupported -> {
                setDegradationLevel(
                    Feature.TTS_VOICE,
                    DegradationLevel.REDUCED,
                    error.message
                )
            }
            
            // 网络错误 -> 离线模式
            is BlindPathError.NetworkUnavailable,
            is BlindPathError.NetworkTimeout -> {
                setDegradationLevel(
                    Feature.NETWORK,
                    DegradationLevel.OFFLINE,
                    error.message
                )
            }
            
            // 存储错误
            is BlindPathError.StoragePermissionDenied,
            is BlindPathError.StorageInsufficient -> {
                setDegradationLevel(
                    Feature.STORAGE,
                    DegradationLevel.DISABLED,
                    error.message
                )
            }
            
            else -> {
                // 其他错误不做自动降级
            }
        }
    }
    
    /**
     * 获取所有降级状态
     */
    fun getAllDegradationStates(): Map<Feature, DegradationLevel> {
        return degradedFeatures.toMap()
    }
    
    /**
     * 获取降级状态摘要（用于用户提示）
     */
    fun getDegradationSummary(): String {
        val degradedList = degradedFeatures.entries
            .filter { it.value != DegradationLevel.NORMAL }
            .map { entry ->
                val featureName = when (entry.key) {
                    Feature.AI_DETECTION -> "AI检测"
                    Feature.CAMERA -> "摄像头"
                    Feature.GPS_NAVIGATION -> "导航"
                    Feature.TTS_VOICE -> "语音"
                    Feature.NETWORK -> "网络"
                    Feature.STORAGE -> "存储"
                    Feature.COMMUNITY -> "社区"
                }
                val status = when (entry.value) {
                    DegradationLevel.NORMAL -> "正常"
                    DegradationLevel.REDUCED -> "受限"
                    DegradationLevel.OFFLINE -> "离线"
                    DegradationLevel.DISABLED -> "禁用"
                }
                "$featureName($status)"
            }
        
        return if (degradedList.isEmpty()) {
            "所有功能正常"
        } else {
            "以下功能受限: ${degradedList.joinToString(", ")}"
        }
    }
    
    /**
     * 重置所有降级状态
     */
    fun resetAll() {
        val previousStates = degradedFeatures.toMap()
        degradedFeatures.clear()
        
        previousStates.forEach { (feature, previousLevel) ->
            if (previousLevel != DegradationLevel.NORMAL) {
                listeners.forEach { listener ->
                    try {
                        listener.onDegradationChanged(feature, previousLevel, DegradationLevel.NORMAL, "Reset all")
                    } catch (e: Exception) {
                        Timber.e(e, "Error in degradation listener")
                    }
                }
            }
        }
        
        Timber.i("All degradation states reset")
    }
    
    /**
     * 添加降级状态变化监听器
     */
    fun addListener(listener: DegradationListener) {
        listeners.add(listener)
    }
    
    /**
     * 移除降级状态变化监听器
     */
    fun removeListener(listener: DegradationListener) {
        listeners.remove(listener)
    }
    
    /**
     * 降级状态变化监听器
     */
    interface DegradationListener {
        fun onDegradationChanged(
            feature: Feature,
            previousLevel: DegradationLevel,
            newLevel: DegradationLevel,
            reason: String?
        )
    }
}
