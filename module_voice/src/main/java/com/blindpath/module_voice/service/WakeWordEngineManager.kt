package com.blindpath.module_voice.service

import android.content.Context
import com.blindpath.module_voice.domain.model.WakeWordConfig
import timber.log.Timber

/**
 * 唤醒词引擎管理器
 *
 * 支持多引擎架构：
 * - 主引擎：百度语音唤醒（自动管理音频采集）
 * - 备选引擎：科大讯飞 MSC（自管理音频采集）
 * - 降级方案：能量检测（需要外部传入音频帧）
 *
 * 引擎模式：
 * - SELF_MANAGED：引擎自己管理音频采集（如百度 SDK）
 * - EXTERNAL_AUDIO：需要外部传入音频数据（如能量检测）
 */
class WakeWordEngineManager(private val context: Context) {

    enum class EngineType {
        BAIDU,          // 百度语音唤醒引擎（自管理音频）
        XF_IFLYTEK,     // 科大讯飞 MSC 唤醒引擎（自管理音频）
        ENERGY          // 能量检测（降级方案，需要外部音频）
    }

    /**
     * 引擎是否自己管理音频采集
     */
    fun isSelfManagedAudio(engineType: EngineType): Boolean {
        return engineType == EngineType.BAIDU || engineType == EngineType.XF_IFLYTEK
    }

    data class EngineConfig(
        val primaryEngine: EngineType = EngineType.BAIDU,
        val fallbackEnabled: Boolean = true,
        val baiduAppId: String = "",
        val baiduApiKey: String = "",
        val baiduSecretKey: String = "",
        val baiduWakeWordAsset: String = WakeWordConfig.BAIDU_WAKE_WORD_ASSET,
        val xfAppId: String = "",
        val xfApiKey: String = "",
        val xfApiSecret: String = "",
        val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD
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
                EngineType.BAIDU -> tryInitializeEngine(EngineType.XF_IFLYTEK)
                EngineType.XF_IFLYTEK -> tryInitializeEngine(EngineType.BAIDU)
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
                EngineType.BAIDU -> createBaiduEngine()
                EngineType.XF_IFLYTEK -> createXfEngine()
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
     * 创建百度语音唤醒引擎
     *
     * 注意：BaiduWakeWordDetector 在构造函数的 init{} 块中调用 initialize()
     * 如果百度 SDK 内部抛出 NullPointerException（EventListener 为 null 的已知 bug），
     * init 块会捕获并将 isInitialized 标记为 false。
     * 此处检查 isInitialized，如果为 false 则视为创建失败，返回 null 触发降级。
     */
    private fun createBaiduEngine(): BaiduWakeWordDetector? {
        if (config.baiduAppId.isBlank() || config.baiduApiKey.isBlank()) {
            Timber.w("WakeWordEngineManager: Baidu credentials not configured")
            return null
        }

        return try {
            val detector = BaiduWakeWordDetector(
                context = context,
                appId = config.baiduAppId,
                apiKey = config.baiduApiKey,
                secretKey = config.baiduSecretKey,
                wakeWordAssetPath = config.baiduWakeWordAsset,
                wakeWord = config.wakeWord,
                onWakeWordDetected = { keyword ->
                    onWakeWordDetected?.invoke(keyword)
                }
            )

            // 检查百度 SDK 是否真正初始化成功
            // BaiduWakeWordDetector.init{} 块中可能因 EventManagerFactory NPE 而失败
            if (!detector.isInitialized || !detector.isListening()) {
                Timber.w("WakeWordEngineManager: Baidu engine created but not initialized (SDK NPE likely)")
                // 释放资源，防止 SDK 后台线程继续访问已 null 的 listener
                try {
                    detector.release()
                } catch (e: Exception) {
                    Timber.w(e, "WakeWordEngineManager: Error releasing failed Baidu detector")
                }
                return null
            }

            Timber.i("WakeWordEngineManager: Baidu engine initialized successfully")
            detector
        } catch (e: Exception) {
            Timber.e(e, "WakeWordEngineManager: Baidu engine creation failed (likely SDK bug)")
            null
        }
    }

    /**
     * 创建科大讯飞 MSC 唤醒引擎
     */
    private fun createXfEngine(): XfWakeWordDetector? {
        if (config.xfAppId.isBlank() || config.xfApiKey.isBlank() || config.xfApiSecret.isBlank()) {
            Timber.w("WakeWordEngineManager: iFlytek credentials not fully configured")
            return null
        }

        return try {
            val detector = XfWakeWordDetector(
                context = context,
                appId = config.xfAppId,
                apiKey = config.xfApiKey,
                apiSecret = config.xfApiSecret,
                wakeWord = config.wakeWord,
                onWakeWordDetected = { keyword ->
                    onWakeWordDetected?.invoke(keyword)
                }
            )
            // 讯飞引擎需要手动启动监听
            detector.startListening()
            detector
        } catch (e: Exception) {
            Timber.e(e, "WakeWordEngineManager: iFlytek engine creation failed (is MSC SDK in libs/?)")
            null
        }
    }

    /**
     * 创建能量检测引擎（降级方案）
     */
    private fun createEnergyEngine(): EnergyWakeWordDetector {
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
     * 当前引擎是否自己管理音频
     */
    fun isCurrentEngineSelfManaged(): Boolean = isSelfManagedAudio(currentEngineType)

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
