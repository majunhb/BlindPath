package com.blindpath.module_voice.service

import android.content.Context
import timber.log.Timber

/**
 * 唤醒词引擎管理器
 *
 * 支持双引擎架构：
 * - 主引擎：Porcupine（默认）
 * - 备选引擎：百度语音唤醒
 *
 * 支持动态切换和自动降级
 */
class WakeWordEngineManager(private val context: Context) {

    enum class EngineType {
        PORCUPINE,      // Porcupine 引擎
        BAIDU,          // 百度语音唤醒引擎
        ENERGY          // 能量检测（降级方案）
    }

    data class EngineConfig(
        val primaryEngine: EngineType = EngineType.PORCUPINE,
        val fallbackEnabled: Boolean = true,
        val baiduAppId: String = "",
        val baiduApiKey: String = "",
        val baiduSecretKey: String = "",
        val porcupineAccessKey: String = "",
        val wakeWord: String = "小智小智"
    )

    private var currentEngine: WakeWordDetector? = null
    private var currentEngineType: EngineType = EngineType.ENERGY
    private var config: EngineConfig = EngineConfig()

    // 引擎切换监听器
    var onEngineSwitched: ((EngineType) -> Unit)? = null

    /**
     * 初始化引擎管理器
     */
    fun initialize(config: EngineConfig) {
        this.config = config
        Timber.i("WakeWordEngineManager: Initializing with primary engine: ${config.primaryEngine}")

        // 尝试初始化主引擎
        val initialized = tryInitializeEngine(config.primaryEngine)

        if (!initialized && config.fallbackEnabled) {
            // 主引擎失败，尝试备选引擎
            Timber.w("WakeWordEngineManager: Primary engine failed, trying fallback")
            when (config.primaryEngine) {
                EngineType.PORCUPINE -> tryInitializeEngine(EngineType.BAIDU)
                EngineType.BAIDU -> tryInitializeEngine(EngineType.PORCUPINE)
                EngineType.ENERGY -> { /* 已经是最后的降级方案 */ }
            }
        }

        if (currentEngine == null) {
            // 所有引擎都失败，使用能量检测
            Timber.w("WakeWordEngineManager: All engines failed, using energy detection")
            tryInitializeEngine(EngineType.ENERGY)
        }
    }

    /**
     * 尝试初始化指定引擎
     */
    private fun tryInitializeEngine(engineType: EngineType): Boolean {
        return try {
            currentEngine = when (engineType) {
                EngineType.PORCUPINE -> createPorcupineEngine()
                EngineType.BAIDU -> createBaiduEngine()
                EngineType.ENERGY -> createEnergyEngine()
            }

            if (currentEngine != null) {
                currentEngineType = engineType
                Timber.i("WakeWordEngineManager: Engine initialized: $engineType")
                onEngineSwitched?.invoke(engineType)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "WakeWordEngineManager: Failed to initialize engine: $engineType")
            false
        }
    }

    /**
     * 创建 Porcupine 引擎
     */
    private fun createPorcupineEngine(): WakeWordDetector? {
        if (config.porcupineAccessKey.isBlank() ||
            config.porcupineAccessKey == "YOUR_ACCESS_KEY_HERE") {
            Timber.w("WakeWordEngineManager: Porcupine AccessKey not configured")
            return null
        }

        return PorcupineWakeWordDetector(
            context = context,
            accessKey = config.porcupineAccessKey,
            sensitivity = 0.7f,
            onWakeWordDetected = { keyword ->
                onWakeWordDetected?.invoke(keyword)
            }
        )
    }

    /**
     * 创建百度语音唤醒引擎
     */
    private fun createBaiduEngine(): WakeWordDetector? {
        if (config.baiduAppId.isBlank() || config.baiduApiKey.isBlank()) {
            Timber.w("WakeWordEngineManager: Baidu credentials not configured")
            return null
        }

        // TODO: 实现百度语音唤醒引擎
        // 需要百度 SDK AAR 文件
        Timber.w("WakeWordEngineManager: Baidu engine not yet implemented")
        return null
    }

    /**
     * 创建能量检测引擎（降级方案）
     */
    private fun createEnergyEngine(): WakeWordDetector {
        return EnergyWakeWordDetector(
            threshold = 1000,
            onWakeWordDetected = { keyword ->
                onWakeWordDetected?.invoke(keyword)
            }
        )
    }

    /**
     * 手动切换引擎
     */
    fun switchEngine(engineType: EngineType): Boolean {
        if (currentEngineType == engineType) {
            return true
        }

        // 释放当前引擎
        currentEngine?.release()
        currentEngine = null

        // 初始化新引擎
        val success = tryInitializeEngine(engineType)

        if (!success && config.fallbackEnabled) {
            // 切换失败，回退到能量检测
            tryInitializeEngine(EngineType.ENERGY)
        }

        return success
    }

    /**
     * 获取当前引擎
     */
    fun getCurrentEngine(): WakeWordDetector? = currentEngine

    /**
     * 获取当前引擎类型
     */
    fun getCurrentEngineType(): EngineType = currentEngineType

    /**
     * 释放所有资源
     */
    fun release() {
        currentEngine?.release()
        currentEngine = null
        Timber.i("WakeWordEngineManager: Released")
    }

    // 唤醒词检测回调
    var onWakeWordDetected: ((String) -> Unit)? = null
}
