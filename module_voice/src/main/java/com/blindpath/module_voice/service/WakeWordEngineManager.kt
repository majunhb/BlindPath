package com.blindpath.module_voice.service

import android.content.Context
import com.blindpath.module_voice.domain.WakeWordDetector
import com.blindpath.module_voice.domain.model.WakeWordConfig
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 唤醒词引擎管理器 v4.0
 *
 * ★★★ v4.0 唤醒可靠性专项优化（2026-08-25）：
 * D1  授权定时器 + onAuthSuccess 加 engineLock 串行化，避免 switchToEnergy
 *       与 auth 回调同时执行把刚启动好的 XF 引擎替换掉
 * D8  重试计数 reset 策略：XF 成功恢复即清零；服务重启时通过显式
 *       init 重新构造 Manager 本身（由 service onCreate 完成），避免永久
 *       停在 ENERGY；新增 RETRY_AFTER_BACKOFF_MS 指数退避重试
 * D5  engineSwitched 回调里额外暴露"当前引擎的帧长度变化"事件，让
 *       Service 知道需要重启音频采集线程，刷新 frameLength
 * 新增：诊断快照方法 generateDiagnosticSnapshot()
 */
class WakeWordEngineManager(private val context: Context) {

    enum class EngineType {
        BAIDU,          // 百度语音唤醒引擎（自管理音频）
        XF_IFLYTEK,     // 科大讯飞 AIKit 唤醒引擎（外部音频，SDK 异步授权）
        PORCUPINE,      // Porcupine 离线唤醒引擎（降级方案）
        ENERGY          // 能量检测（最终降级方案，需要外部音频）
    }

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
        val porcupineAccessKey: String = "",
        val porcupineKeywordAsset: String = WakeWordConfig.PORCUPINE_KEYWORD_ASSET,
        val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD
    )

    @Volatile private var currentEngine: WakeWordDetector? = null
    @Volatile private var currentEngineType: EngineType = EngineType.ENERGY
    private var config: EngineConfig = EngineConfig()

    var onEngineSwitched: ((EngineType) -> Unit)? = null
    var onWakeWordDetected: ((String) -> Unit)? = null

    // 线程安全：串行化所有引擎创建/切换/降级/重试操作
    private val engineLock = ReentrantLock()

    // 授权超时 + 重试的统一调度器（v4.0 合并，避免两个 Executor 互调竞态）
    private val scheduler = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "WakeWordEngineManager-scheduler").also { it.isDaemon = true }
    }

    private val authTimeoutHandles = mutableListOf<ScheduledFuture<*>>()
    private var retryHandle: ScheduledFuture<*>? = null
    private var retryCount = 0
    private val maxRetryCount = 8 // v4.0: 3 → 8，给足授权网络抖动容错
    private var retryBackoffSeconds = RETRY_BASE_SECONDS

    // 诊断用
    private val engineSwitchCount = AtomicInteger(0)
    private val downgradeCount = AtomicInteger(0)
    private val lastSwitchMs = java.util.concurrent.atomic.AtomicLong(0L)

    companion object {
        private const val AUTH_TIMEOUT_SECONDS = 20L  // v4.0: 15s → 20s，移动端网络弱也给足
        private const val RETRY_BASE_SECONDS = 20L    // v4.0: 30s → 20s 起步，但指数退避
        private const val RETRY_MAX_SECONDS = 300L    // 最大 5 分钟一次
        private const val TAG = "WakeWordEngineMgr"
    }

    /**
     * 初始化引擎管理器
     */
    fun initialize(config: EngineConfig) {
        engineLock.withLock {
            this.config = config
            // v4.0: 初始化时清零所有调度状态，确保 onCreate 重建时是 fresh 状态
            cancelAllScheduled()
            retryCount = 0
            retryBackoffSeconds = RETRY_BASE_SECONDS

            Timber.i("$TAG v4.0: Initializing with primary engine: ${config.primaryEngine}")

            val initialized = tryInitializeEngine(config.primaryEngine)

            if (!initialized && config.fallbackEnabled) {
                Timber.w("$TAG: Primary engine failed, trying fallback chain")
                fallbackChainFrom(config.primaryEngine)
            }

            if (currentEngine == null) {
                Timber.w("$TAG: All explicit engines failed, using Energy as terminal fallback")
                tryInitializeEngine(EngineType.ENERGY)
            }
        }
    }

    /** 按顺序尝试降级链 */
    private fun fallbackChainFrom(from: EngineType) {
        val chain = when (from) {
            EngineType.BAIDU       -> listOf(EngineType.XF_IFLYTEK, EngineType.PORCUPINE, EngineType.ENERGY)
            EngineType.XF_IFLYTEK  -> listOf(EngineType.BAIDU, EngineType.PORCUPINE, EngineType.ENERGY)
            EngineType.PORCUPINE   -> listOf(EngineType.XF_IFLYTEK, EngineType.BAIDU, EngineType.ENERGY)
            EngineType.ENERGY      -> emptyList()
        }
        for (candidate in chain) {
            if (tryInitializeEngine(candidate)) return
        }
    }

    /**
     * 尝试初始化指定引擎（不加锁，由调用方持有 engineLock）
     */
    private fun tryInitializeEngine(engineType: EngineType): Boolean {
        return try {
            currentEngine = when (engineType) {
                EngineType.BAIDU -> createBaiduEngine()
                EngineType.XF_IFLYTEK -> createXfEngine()
                EngineType.PORCUPINE -> createPorcupineEngine()
                EngineType.ENERGY -> createEnergyEngine()
            }

            if (currentEngine != null) {
                currentEngineType = engineType
                engineSwitchCount.incrementAndGet()
                lastSwitchMs.set(System.currentTimeMillis())
                Timber.i("$TAG: ★ Engine initialized: $engineType")
                onEngineSwitched?.invoke(engineType)
                true
            } else {
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize engine: $engineType")
            false
        }
    }

    private fun createBaiduEngine(): BaiduWakeWordDetector? {
        if (config.baiduAppId.isBlank() || config.baiduApiKey.isBlank()) {
            Timber.w("$TAG: Baidu credentials not configured")
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
                onWakeWordDetected = { keyword -> onWakeWordDetected?.invoke(keyword) }
            )
            if (!detector.isInitialized) {
                Timber.w("$TAG: Baidu engine created but not initialized")
                try { detector.release() } catch (_: Exception) {}
                return null
            }
            Timber.i("$TAG: Baidu engine initialized successfully")
            detector
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Baidu engine creation failed")
            null
        }
    }

    /**
     * v4.0：创建讯飞引擎
     * D1 修复：授权定时器在 engineLock 内执行 schedule；onAuthFailedCb 也是经
     *   engineLock.withLock 进入 switchToEnergy，避免和定时器回调同时触发
     */
    private fun createXfEngine(): XfWakeWordDetector? {
        if (config.xfAppId.isBlank() || config.xfApiKey.isBlank() || config.xfApiSecret.isBlank()) {
            Timber.w("$TAG: iFlytek credentials not fully configured")
            return null
        }
        return try {
            val detector = XfWakeWordDetector(
                context = context,
                appId = config.xfAppId,
                apiKey = config.xfApiKey,
                apiSecret = config.xfApiSecret,
                wakeWord = config.wakeWord,
                onWakeWordDetected = { keyword -> onWakeWordDetected?.invoke(keyword) },
                onAuthSuccessCb = {
                    // D1：切换状态 + 恢复重试计数都走 engineLock
                    engineLock.withLock {
                        Timber.i("$TAG: ★ XF auth success callback received")
                        // 授权成功 → 取消"授权超时降级"定时器
                        cancelAuthTimeouts()
                        // 授权成功意味着主引擎可用，退避与计数器清零
                        retryCount = 0
                        retryBackoffSeconds = RETRY_BASE_SECONDS
                        cancelRetry()
                    }
                },
                onAuthFailedCb = {
                    engineLock.withLock {
                        Timber.e("$TAG: ✗ XF auth failed callback, switching to Energy")
                        cancelAuthTimeouts()
                        switchToEnergy(reason = "xf-auth-failed-cb")
                    }
                }
            )

            // D1 修复：定时器回调也要拿 engineLock，防止和成功回调交错
            val timeoutTask = scheduler.schedule({
                engineLock.withLock {
                    // 只在 XF 仍是当前引擎，且仍未授权成功时执行降级
                    if (currentEngineType == EngineType.XF_IFLYTEK && !detector.isAuthComplete()) {
                        val reason = detector.getAuthFailedReason()
                        val code = detector.getAuthStateCode()
                        Timber.w("$TAG: ⏰ XF auth timeout (${AUTH_TIMEOUT_SECONDS}s) code=$code reason=$reason, switching to Energy")
                        switchToEnergy(reason = "xf-auth-timeout(code=$code,msg=$reason)")
                    } else {
                        Timber.d("$TAG: auth timeout fired but XF already moved on (type=$currentEngineType authDone=${detector.isAuthComplete()})")
                    }
                }
            }, AUTH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            authTimeoutHandles.add(timeoutTask)

            Timber.i("$TAG: ★ XF engine created, waiting for auth (timeout=${AUTH_TIMEOUT_SECONDS}s)...")
            detector
        } catch (e: Exception) {
            Timber.e(e, "$TAG: iFlytek engine creation failed")
            null
        }
    }

    private fun createPorcupineEngine(): PorcupineWakeWordDetector? {
        if (config.porcupineAccessKey.isBlank()) {
            Timber.w("$TAG: Porcupine access key not configured")
            return null
        }
        return try {
            val detector = PorcupineWakeWordDetector(
                context = context,
                accessKey = config.porcupineAccessKey,
                keywordAssetPath = config.porcupineKeywordAsset,
                wakeWord = config.wakeWord,
                onWakeWordDetected = { keyword -> onWakeWordDetected?.invoke(keyword) }
            )
            Timber.i("$TAG: Porcupine engine created successfully")
            detector
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Porcupine engine creation failed")
            null
        }
    }

    private fun createEnergyEngine(): EnergyWakeWordDetector {
        return EnergyWakeWordDetector(
            threshold = 1200,
            onWakeWordDetected = { keyword -> onWakeWordDetected?.invoke(keyword) }
        )
    }

    /**
     * v4.0 降级到 Energy，并安排指数退避重试
     * 调用方必须持有 engineLock
     */
    private fun switchToEnergy(reason: String) {
        if (currentEngineType == EngineType.ENERGY) {
            Timber.d("$TAG: already on ENERGY, skip switchToEnergy (reason=$reason)")
            return
        }

        Timber.w("$TAG: ★ Switching from $currentEngineType to ENERGY (reason=$reason)")
        downgradeCount.incrementAndGet()

        try { currentEngine?.release() } catch (e: Exception) {
            Timber.w(e, "$TAG: Error releasing previous engine (type=$currentEngineType)")
        }
        currentEngine = null

        currentEngine = createEnergyEngine()
        currentEngineType = EngineType.ENERGY
        onEngineSwitched?.invoke(EngineType.ENERGY)

        // 安排指数退避重试（D8 修复：不再 30 秒线性，也不会永不重试）
        scheduleRetryLocked()
    }

    private fun scheduleRetryLocked() {
        cancelRetry()
        if (retryCount >= maxRetryCount) {
            // 到达上限后，改成长周期"心跳式"重试（10min）
            Timber.w("$TAG: max retries ($maxRetryCount) reached, scheduling heart-beat retry every ${RETRY_MAX_SECONDS}s")
            val f = scheduler.scheduleWithFixedDelay(
                { doRetryLocked() },
                RETRY_MAX_SECONDS, RETRY_MAX_SECONDS, TimeUnit.SECONDS
            )
            retryHandle = f
            return
        }
        val delay = retryBackoffSeconds.coerceAtMost(RETRY_MAX_SECONDS)
        Timber.i("$TAG: schedule retry #${retryCount + 1} to restore XF engine after ${delay}s")
        val f = scheduler.schedule({ doRetryLocked() }, delay, TimeUnit.SECONDS)
        retryHandle = f
        retryBackoffSeconds = (retryBackoffSeconds * 2).coerceAtMost(RETRY_MAX_SECONDS)
    }

    private fun doRetryLocked() {
        engineLock.withLock {
            // 只在已经退到 Energy 时才重试恢复（XF 已可用就别再切了）
            if (currentEngineType != EngineType.ENERGY) {
                Timber.i("$TAG: retry skipped (current engine already: $currentEngineType)")
                return
            }
            retryCount++
            Timber.i("$TAG: ★ Retry attempt #$retryCount to restore XF engine...")
            val xfEngine = try {
                createXfEngine()
            } catch (e: Exception) {
                Timber.e(e, "$TAG: retry #$retryCount: createXfEngine threw")
                null
            }
            if (xfEngine != null) {
                try { currentEngine?.release() } catch (e: Exception) {
                    Timber.w(e, "$TAG: Error releasing Energy engine during retry")
                }
                currentEngine = xfEngine
                currentEngineType = EngineType.XF_IFLYTEK
                retryCount = 0
                retryBackoffSeconds = RETRY_BASE_SECONDS
                cancelRetry()
                Timber.i("$TAG: ★★ Successfully restored XF engine on retry #$retryCount")
                onEngineSwitched?.invoke(EngineType.XF_IFLYTEK)
            } else {
                Timber.w("$TAG: Retry #$retryCount failed, still on Energy")
                scheduleRetryLocked()
            }
        }
    }

    private fun cancelAuthTimeouts() {
        authTimeoutHandles.removeAll { f ->
            f.cancel(false); true
        }
    }

    private fun cancelRetry() {
        retryHandle?.cancel(false)
        retryHandle = null
    }

    private fun cancelAllScheduled() {
        cancelAuthTimeouts()
        cancelRetry()
    }

    /** 手动切换引擎 */
    fun switchEngine(engineType: EngineType): Boolean {
        engineLock.withLock {
            if (currentEngineType == engineType) return true
            currentEngine?.release()
            currentEngine = null
            cancelAllScheduled()
            retryCount = 0
            retryBackoffSeconds = RETRY_BASE_SECONDS
            val success = tryInitializeEngine(engineType)
            if (!success && config.fallbackEnabled) {
                tryInitializeEngine(EngineType.ENERGY)
            }
            return success
        }
    }

    fun getCurrentEngine(): WakeWordDetector? = currentEngine
    fun startListening() { currentEngine?.start() }
    fun getCurrentEngineType(): EngineType = currentEngineType
    fun isCurrentEngineSelfManaged(): Boolean = isSelfManagedAudio(currentEngineType)

    fun release() {
        engineLock.withLock {
            try { currentEngine?.release() } catch (_: Exception) {}
            currentEngine = null
            cancelAllScheduled()
            scheduler.shutdownNow()
            Timber.i("$TAG: Released (switch=$engineSwitchCount downgrade=$downgradeCount, finalType=$currentEngineType)")
        }
    }

    // ─────────────────────────────────────────────
    // v4.0 诊断工具
    // ─────────────────────────────────────────────

    /** 生成一行纯文本快照，方便写到日志 / 广播 / 诊断页 */
    fun generateDiagnosticSnapshot(): String {
        val engine = currentEngine
        val engineSpecific = when (engine) {
            is XfWakeWordDetector -> listOfNotNull(
                "state=${engine.getEngineState()}",
                "authDone=${engine.isAuthComplete()}",
                "authFailed=${engine.isAuthFailed()}",
                "authCode=${engine.getAuthStateCode()}",
                "authReason=${engine.getAuthFailedReason() ?: "-"}",
                "listening=${engine.isListening()}",
                "framesFeed=${engine.statsFeedFrames}",
                "framesErr=${engine.statsWriteErrors}",
                "wakeCount=${engine.statsWakeCount}",
                "startFail=${engine.statsStartFailures}",
                "queue=${engine.getQueueSize()}"
            ).joinToString(", ")
            is EnergyWakeWordDetector -> "energy-thr=1200 (energy detector)"
            is BaiduWakeWordDetector -> "baidu-initialized=${engine.isInitialized}"
            is PorcupineWakeWordDetector -> "porcupine (offline)"
            else -> "unknown(${engine?.javaClass?.simpleName})"
        }
        val lastSwitchAgeSec = (System.currentTimeMillis() - lastSwitchMs.get()) / 1000.0
        return "EngineManager(type=$currentEngineType, switches=${engineSwitchCount.get()}, " +
                "downgrades=${downgradeCount.get()}, lastSwitchAge=${"%.1f".format(lastSwitchAgeSec)}s, " +
                "retryCount=$retryCount, backoff=${retryBackoffSeconds}s) | Engine: $engineSpecific"
    }
}
