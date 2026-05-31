package com.blindpath.module_voice.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.blindpath.base.common.Result
import com.blindpath.base.config.AppConfig
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.suspendCancellableCoroutine
import timber.log.Timber
import java.util.*
import java.util.concurrent.PriorityBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * 语音播报仓库实现（分级播报版）
 * 
 * 核心特性：
 * - 四级优先级队列（P0-P3）
 * - 智能去重与冷却机制
 * - 打断与恢复策略
 * - 播报统计与分析
 * 
 * 修复说明（全程修复语音交互）：
 * 1. 修复 initialize() 错误返回 Result.Success(false) 而非 Result.Error 的 bug
 *    → 导致 VoiceInteractionManager 误判初始化成功，后续 TTS 调用全部失败
 * 2. 添加 TTS 初始化超时保护，防止 onInit 永远不回调导致协程挂死
 * 3. 在 announce() 中增加 isInitialized 的自动重初始化逻辑
 */
@Singleton
class VoiceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceRepository {

    private val _state = MutableStateFlow(VoiceState())
    override val voiceState: StateFlow<VoiceState> = _state.asStateFlow()

    private val _statistics = MutableStateFlow(VoiceStatistics())
    override val statistics: StateFlow<VoiceStatistics> = _statistics.asStateFlow()

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    // 优先级队列（按优先级排序）
    private val announcementQueue = PriorityBlockingQueue<VoiceRequest>(
        11,
        compareBy<VoiceRequest> { it.priority.level }
    )

    // 去重缓存：记录最近播报的内容
    private val recentAnnouncements = mutableMapOf<String, Long>()

    // 冷却计时器
    private var lastAnnouncementTime = mutableMapOf<VoicePriority, Long>()

    // 当前正在播报的请求
    @Volatile
    private var currentRequest: VoiceRequest? = null

    // 播放协程作用域
    private val playbackScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // 队列处理协程
    private var queueProcessorJob: Job? = null

    // 队列通知信号（替代 poll+delay 轮询）
    private val queueSignal = Channel<Unit>(Channel.CONFLATED)

    override suspend fun initialize(): Result<Boolean> = suspendCancellableCoroutine { continuation ->
        continuation.invokeOnCancellation {
            Timber.d("TTS initialization cancelled")
        }
        try {
            Timber.d("Initializing Android TTS with priority queue")

            // 超时保护：10秒后强制恢复，防止 onInit 永远不回调
            val timeoutJob = CoroutineScope(Dispatchers.Default).launch {
                delay(10_000)
                if (continuation.isActive) {
                    Timber.e("TTS initialization timed out after 10s")
                    continuation.resume(Result.Error(message = "TTS 初始化超时（10秒）"), null)
                }
            }

            tts = TextToSpeech(context) { status ->
                timeoutJob.cancel()
                if (status == TextToSpeech.SUCCESS) {
                    // 先尝试中文
                    var result = tts?.setLanguage(Locale.CHINESE)

                    // 中文不可用，回退到英文
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Timber.w("Chinese TTS not available, trying English")
                        result = tts?.setLanguage(Locale.US)
                    }

                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Timber.e("No TTS language available")
                        _state.update { it.copy(lastError = "不支持任何语音语言") }
                        isInitialized = false
                        // 修复：失败时返回 Result.Error，而非 Result.Success(false)
                        if (continuation.isActive) continuation.resume(Result.Error(message = "TTS 不支持任何语言，请安装语音数据包"), null)
                    } else {
                        isInitialized = true
                        tts?.setSpeechRate(AppConfig.Voice.SPEECH_RATE)
                        _state.update { it.copy(isAvailable = true) }

                        // 启动队列处理器
                        startQueueProcessor()

                        Timber.d("TTS initialized successfully with priority queue")
                        if (continuation.isActive) continuation.resume(Result.Success(true), null)
                    }
                } else {
                    // 修复：初始化失败时返回 Result.Error，而非 Result.Success(false)
                    Timber.e("TTS initialization failed with status: $status")
                    if (continuation.isActive) continuation.resume(Result.Error(message = "TTS 初始化失败，错误码：$status"), null)
                }
            }

            // 设置监听器
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _state.update { it.copy(isSpeaking = true) }
                }

                override fun onDone(utteranceId: String?) {
                    _state.update {
                        it.copy(
                            isSpeaking = false,
                            currentPriority = null
                        )
                    }
                    currentRequest = null
                    Timber.d("TTS playback finished")
                }

                @Deprecated("Deprecated in API")
                override fun onError(utteranceId: String?) {
                    Timber.e("TTS error: $utteranceId")
                    _state.update {
                        it.copy(
                            isSpeaking = false,
                            lastError = "语音播放错误"
                        )
                    }
                    currentRequest = null
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Timber.e("TTS error: $utteranceId, code: $errorCode")
                    _state.update {
                        it.copy(
                            isSpeaking = false,
                            lastError = "语音播放错误: $errorCode"
                        )
                    }
                    currentRequest = null
                }
            })

        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize TTS")
            _state.update { it.copy(lastError = e.message) }
            if (continuation.isActive) continuation.resume(Result.Error(message = e.message ?: "语音初始化失败"), null)
        }
    }

    /**
     * 启动队列处理器
     */
    private fun startQueueProcessor() {
        queueProcessorJob = playbackScope.launch {
            while (isActive) {
                try {
                    // 等待新元素信号或直接尝试消费队列
                    // 先尝试立即消费队列中已有的元素
                    var request = announcementQueue.poll()
                    if (request == null) {
                        // 队列为空，使用 Channel.receiveCatching() 挂起等待，避免 CPU 轮询
                        queueSignal.receiveCatching()
                        request = announcementQueue.poll()
                    }

                    if (request != null) {
                        processAnnouncement(request)
                    }

                    // 更新队列大小
                    _state.update { it.copy(queueSize = announcementQueue.size) }
                } catch (e: Exception) {
                    Timber.e(e, "Error processing announcement queue")
                }
            }
        }
    }

    /**
     * 处理单个播报请求
     */
    private suspend fun processAnnouncement(request: VoiceRequest) {
        // 检查去重
        if (shouldDeduplicate(request)) {
            Timber.d("Deduplicating announcement: ${request.text}")
            _statistics.update {
                it.copy(deduplicatedCount = it.deduplicatedCount + 1)
            }
            return
        }

        // 检查冷却
        if (isInCooldown(request.priority)) {
            Timber.d("Announcement in cooldown: ${request.priority}")
            return
        }

        // 如果需要打断当前播报
        if (request.interruptCurrent && _state.value.isSpeaking) {
            Timber.d("Interrupting current announcement for: ${request.priority}")
            tts?.stop()
            _statistics.update {
                it.copy(interruptedCount = it.interruptedCount + 1)
            }
            delay(100) // 等待停止完成
        }

        // 播报
        currentRequest = request
        _state.update {
            it.copy(currentPriority = request.priority)
        }

        val utteranceId = UUID.randomUUID().toString()
        tts?.speak(request.text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)

        // 记录播报
        recordAnnouncement(request)

        // 更新统计
        updateStatistics(request)

        // 等待播报完成（使用 StateFlow.first() 响应式等待，替代 while+delay 轮询）
        try {
            // 第一步：等待 TTS 开始播报（最多等 3 秒）
            withTimeoutOrNull(3000L) {
                voiceState.first { it.isSpeaking }
            }
            // 第二步：等待 TTS 播报完成（最多等 10 秒）
            withTimeoutOrNull(10000L) {
                voiceState.first { !it.isSpeaking }
            }
        } catch (e: Exception) {
            Timber.w("Error waiting for TTS: ${e.message}")
        }
    }

    /**
     * 检查是否应该去重
     */
    private fun shouldDeduplicate(request: VoiceRequest): Boolean {
        val key = request.deduplicationKey ?: request.text
        val lastTime = recentAnnouncements[key]

        if (lastTime != null) {
            val elapsed = System.currentTimeMillis() - lastTime
            val cooldown = request.priority.getCooldownMs()

            if (elapsed < cooldown) {
                return true
            }
        }

        return false
    }

    /**
     * 检查是否在冷却期
     */
    private fun isInCooldown(priority: VoicePriority): Boolean {
        val lastTime = lastAnnouncementTime[priority] ?: return false
        val elapsed = System.currentTimeMillis() - lastTime
        val cooldown = priority.getCooldownMs()

        return elapsed < cooldown
    }

    /**
     * 记录播报（用于去重和冷却）
     */
    private fun recordAnnouncement(request: VoiceRequest) {
        val now = System.currentTimeMillis()

        // 记录去重键
        val key = request.deduplicationKey ?: request.text
        recentAnnouncements[key] = now

        // 记录优先级冷却
        lastAnnouncementTime[request.priority] = now

        // 清理过期的去重记录（保留最近 1 分钟）
        val expireThreshold = now - 60_000
        recentAnnouncements.entries.removeAll { it.value < expireThreshold }
    }

    /**
     * 更新统计信息
     */
    private fun updateStatistics(request: VoiceRequest) {
        _statistics.update { stats ->
            val newStats = when (request.priority) {
                VoicePriority.EMERGENCY -> stats.copy(
                    totalAnnouncements = stats.totalAnnouncements + 1,
                    emergencyCount = stats.emergencyCount + 1
                )
                VoicePriority.IMPORTANT -> stats.copy(
                    totalAnnouncements = stats.totalAnnouncements + 1,
                    importantCount = stats.importantCount + 1
                )
                VoicePriority.NORMAL -> stats.copy(
                    totalAnnouncements = stats.totalAnnouncements + 1,
                    normalCount = stats.normalCount + 1
                )
                VoicePriority.BACKGROUND -> stats.copy(
                    totalAnnouncements = stats.totalAnnouncements + 1,
                    backgroundCount = stats.backgroundCount + 1
                )
            }
            newStats
        }
    }

    /**
     * 分级播报（核心方法）
     */
    override suspend fun announce(request: VoiceRequest): Result<Boolean> {
        return try {
            if (!isInitialized) {
                val initResult = initialize()
                // 修复：正确处理 initialize() 的返回类型
                if (initResult is Result.Error || (initResult is Result.Success && initResult.data == false)) {
                    return Result.Error(message = "TTS 初始化失败，无法播报")
                }
            }

            if (tts == null) {
                return Result.Error(message = "TTS 未初始化")
            }

            // 加入优先级队列并通知消费者
            announcementQueue.offer(request)
            queueSignal.trySend(Unit)  // 通知队列处理器有新元素

            // 更新队列大小
            _state.update { it.copy(queueSize = announcementQueue.size) }

            Timber.d("Queued announcement: priority=${request.priority}, text=${request.text}")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to queue announcement")
            Result.Error(message = e.message ?: "语音播报失败")
        }
    }

    /**
     * 清空播报队列
     */
    override suspend fun clearQueue(): Result<Boolean> {
        return try {
            announcementQueue.clear()
            _state.update { it.copy(queueSize = 0) }
            Timber.d("Announcement queue cleared")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to clear queue")
            Result.Error(message = e.message ?: "清空队列失败")
        }
    }

    /**
     * 获取当前队列大小
     */
    override fun getQueueSize(): Int = announcementQueue.size

    // ========== 保留旧方法以兼容现有代码 ==========
    override suspend fun speak(text: String, queueMode: Boolean): Result<Boolean> {
        // 旧方法默认使用 NORMAL 优先级
        val type = if (queueMode) VoiceType.OBSTACLE_NORMAL else VoiceType.OBSTACLE_DANGER
        return announce(VoiceRequest(text = text, type = type))
    }

    /**
     * 兼容方法：接受 VoiceType 参数（使用 VoiceType 自带的 priority）
     */
    override suspend fun announce(text: String, type: VoiceType): Result<Boolean> {
        return announce(VoiceRequest(text = text, type = type))
    }

    override suspend fun stop(): Result<Boolean> {
        return try {
            tts?.stop()
            announcementQueue.clear()
            currentRequest = null
            _state.update {
                it.copy(
                    isSpeaking = false,
                    currentPriority = null,
                    queueSize = 0
                )
            }
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(message = e.message ?: "停止语音失败")
        }
    }

    override suspend fun pause(): Result<Boolean> {
        // Android TTS 不支持暂停，使用 stop 代替
        return stop()
    }

    override suspend fun resume(): Result<Boolean> {
        // Android TTS 不支持恢复，重新播放
        return Result.Error(message = "TTS 不支持恢复功能")
    }

    override fun release() {
        queueProcessorJob?.cancel()
        playbackScope.cancel()

        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false

        announcementQueue.clear()
        recentAnnouncements.clear()
        lastAnnouncementTime.clear()
        currentRequest = null

        _state.update { VoiceState() }
        _statistics.update { VoiceStatistics() }

        Timber.d("TTS released")
    }
}
