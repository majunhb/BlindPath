package com.blindpath.module_voice.service

import android.content.Context
import com.blindpath.module_voice.domain.WakeWordDetector
import com.blindpath.module_voice.domain.model.WakeWordConfig
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 唤醒词引擎管理器 v2.0
 *
 * ★★★ v2.0 修复（2026-06-18）：
 * 1. XF_IFLYTEK 改为非 self-managed（由 WakeWordServiceEnhanced 统一采集音频）
 * 2. createXfEngine 不再立即调 startListening()，由 AuthListener 回调触发
 * 3. 授权超时降级：15秒未授权成功 → 自动切换 Energy 引擎
 *
 * 引擎模式：
 * - SELF_MANAGED：引擎自己管理音频采集（仅百度 SDK）
 * - EXTERNAL_AUDIO：需要外部传入音频数据（讯飞 AIKit + 能量检测）
 */
class WakeWordEngineManager(private val context: Context) {

    enum class EngineType {
        BAIDU,          // 百度语音唤醒引擎（自管理音频）
        XF_IFLYTEK,     // 科大讯飞 AIKit 唤醒引擎（外部音频，SDK 异步授权）
        ENERGY          // 能量检测（降级方案，需要外部音频）
    }

    /**
     * 引擎是否自己管理音频采集
     * ★ v2.0: XF_IFLYTEK 改为 false — 由 WakeWordServiceEnhanced 统一采集并喂入
     */
    fun isSelfManagedAudio(engineType: EngineType): Boolean {
        return engineType == EngineType.BAIDU
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

    // 唤醒词检测回调
    var onWakeWordDetected: ((String) -> Unit)? = null

    // ★ 授权超时检查线程池
    private val timeoutExecutor = Executors.newSingleThreadScheduledExecutor()

    companion object {
        private const val AUTH_TIMEOUT_SECONDS = 15L
    }

    /**
     * 初始化引擎管理器
     */
    fun initialize(config: EngineConfig) {
        this.config = config
        Timber.i("WakeWordEngineManager v2.0: Initializing with primary engine: ${config.primaryEngine}")

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
                Timber.i("WakeWordEngineManager: ★ Engine initialized: $engineType")
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

            if (!detector.isInitialized) {
                Timber.w("WakeWordEngineManager: Baidu engine created but not initialized (SDK NPE likely)")
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
            Timber.e(e, "WakeWordEngineManager: Baidu engine creation failed")
            null
        }
    }

    /**
     * ★★★ v2.0: 创建科大讯飞 AIKit 唤醒引擎
     *
     * 关键变化：
     * - 不再立即调 startListening()，由 AuthListener 回调自动触发
     * - 设置授权超时：15秒未授权 → 降级到 Energy
     * - 设置 onAuthSuccess/onAuthFailed 回调
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

            // ★ 不立即调 startListening()！等 AuthListener 回调
            // SDK 初始化时已启动异步授权，授权成功后会自动调 startListening()

            // ★ 设置授权回调
            detector.onAuthSuccess = {
                Timber.i("WakeWordEngineManager: ★ XF auth success callback received")
            }
            detector.onAuthFailed = {
                Timber.e("WakeWordEngineManager: ✗ XF auth failed callback, switching to Energy")
                switchToEnergy()
            }

            // ★ 授权超时检查：15秒未授权成功 → 降级到 Energy
            timeoutExecutor.schedule({
                if (!detector.isAuthComplete()) {
                    Timber.w("WakeWordEngineManager: ⏰ XF auth timeout (${AUTH_TIMEOUT_SECONDS}s), switching to Energy")
                    switchToEnergy()
                }
            }, AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)

            Timber.i("WakeWordEngineManager: ★ XF engine created, waiting for auth (timeout=${AUTH_TIMEOUT_SECONDS}s)...")
            detector
        } catch (e: Exception) {
            Timber.e(e, "WakeWordEngineManager: iFlytek engine creation failed")
            null
        }
    }

    /**
     * ★ 创建能量检测引擎（降级方案）
     * 阈值降低到 800，更敏感地检测声音
     */
    private fun createEnergyEngine(): EnergyWakeWordDetector {
        return EnergyWakeWordDetector(
            threshold = 800,  // ★ 降低阈值，更敏感
            onWakeWordDetected = { keyword ->
                onWakeWordDetected?.invoke(keyword)
            }
        )
    }

    /**
     * ★ 降级到 Energy 引擎
     */
    private fun switchToEnergy() {
        if (currentEngineType == EngineType.ENERGY) return

        Timber.w("WakeWordEngineManager: ★ Switching from $currentEngineType to ENERGY")

        // 释放当前引擎
        try {
            currentEngine?.release()
        } catch (e: Exception) {
            Timber.w(e, "WakeWordEngineManager: Error releasing previous engine")
        }
        currentEngine = null

        // 初始化 Energy 引擎
        currentEngine = createEnergyEngine()
        currentEngineType = EngineType.ENERGY
        onEngineSwitched?.invoke(EngineType.ENERGY)
    }

    /**
     * 手动切换引擎
     */
    fun switchEngine(engineType: EngineType): Boolean {
        if (currentEngineType == engineType) {
            return true
        }

        currentEngine?.release()
        currentEngine = null

        val success = tryInitializeEngine(engineType)

        if (!success && config.fallbackEnabled) {
            tryInitializeEngine(EngineType.ENERGY)
        }

        return success
    }

    /**
     * 获取当前引擎
     */
    fun getCurrentEngine(): WakeWordDetector? = currentEngine

    /**
     * 启动当前引擎的唤醒词监听
     */
    fun startListening() {
        currentEngine?.start()
    }

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
        timeoutExecutor.shutdownNow()
        Timber.i("WakeWordEngineManager: Released")
    }
}
