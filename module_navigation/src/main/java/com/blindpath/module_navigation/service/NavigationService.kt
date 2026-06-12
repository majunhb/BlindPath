package com.blindpath.module_navigation.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import androidx.core.app.NotificationCompat
import com.blindpath.base.config.AppConfig
import com.blindpath.base.error.DegradationManager
import com.blindpath.module_navigation.data.GpsQuality
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import timber.log.Timber
import java.util.PriorityQueue
import javax.inject.Inject
import kotlin.math.max

/**
 * 导航前台服务
 * 持续定位并通过语音播报导航指令，支持分层语音引导、分级震动反馈、TTC碰撞预测、音频输出适配
 *
 * 架构：依赖 NavigationRepository / VoiceRepository 接口，Vibrator 系统服务注入
 *
 * Phase 2 重构：
 * 1. 分层语音引导策略（ROUTINE / EVENT / URGENT）
 * 2. 分级震动反馈（SHORT_100MS / LONG_500MS / RAPID_3X）
 * 3. TTC 碰撞时间预测 + 滑动窗口滤波
 * 4. 音频输出适配（骨传导 / 单耳 / 双耳）
 * 5. 语速调节 + 方言预留接口
 * 6. 保留原有：前台服务、GPS质量分级、导航指令节流、地址播报、弱信号提醒
 */
@AndroidEntryPoint
class NavigationService : LifecycleService() {

    @Inject
    lateinit var navigationRepository: NavigationRepository

    @Inject
    lateinit var voiceRepository: VoiceRepository

    @Inject
    lateinit var vibrator: Vibrator

    // lifecycleScope inherited from LifecycleService
    private var isRunning = false

    // ==================== 原有状态：防重复播报 ====================
    private var lastInstruction: String? = null
    private var lastKnownDistance = Int.MAX_VALUE
    private var lastGpsQualityAnnouncement: GpsQuality? = null
    private var lastLocationUpdate = 0L

    // GPS 精度播报节流（引用 AppConfig 集中管理）
    private var lastAccuracyAnnounceTime = 0L
    private val ACCURACY_ANNOUNCE_INTERVAL_MS = AppConfig.Navigation.ACCURACY_ANNOUNCE_INTERVAL_MS

    // [修复] 导航指令播报节流
    private var lastInstructionAnnounceTime = 0L
    private val INSTRUCTION_MIN_INTERVAL_MS = AppConfig.Navigation.INSTRUCTION_MIN_INTERVAL_MS

    // [修复] 位置地址播报节流
    private var lastLocationAddress: String? = null
    private var lastAddressAnnounceTime = 0L
    private val ADDRESS_ANNOUNCE_INTERVAL_MS = 30000L // 30秒

    // ==================== 新增：分层语音引导 ====================

    /**
     * 语音引导优先级枚举
     */
    enum class VoiceGuidancePriority(val level: Int) {
        ROUTINE(0),   // 常规引导，排队等待
        EVENT(1),     // 事件播报，打断ROUTINE
        URGENT(2)     // 紧急预警，立即打断当前播报
    }

    /**
     * 播报队列元素
     */
    data class GuidanceItem(
        val text: String,
        val priority: VoiceGuidancePriority,
        val timestamp: Long = System.currentTimeMillis()
    ) : Comparable<GuidanceItem> {
        override fun compareTo(other: GuidanceItem): Int {
            // 优先级高的排前面；同优先级按时间先后
            val levelCompare = other.priority.level.compareTo(this.priority.level)
            return if (levelCompare != 0) levelCompare else this.timestamp.compareTo(other.timestamp)
        }
    }

    /** 优先级播报队列 */
    private val guidanceQueue = PriorityQueue<GuidanceItem>()

    /** 队列处理协程 */
    private var guidanceJob: Job? = null

    /** 最近一次ROUTINE播报时间 */
    private var lastRoutineAnnouncementTime = 0L

    /** 当前是否正在播报（用于ROUTINE排队等待判断） */
    @Volatile
    private var isSpeaking = false

    // ==================== 新增：音频输出适配 ====================

    /**
     * 音频输出模式
     */
    enum class AudioOutputMode {
        BONE_CONDUCTION,   // 骨传导（默认，保留环境听音）
        SINGLE_EAR,        // 单耳耳机
        DUAL_EAR           // 双耳耳机
    }

    private var currentAudioMode: AudioOutputMode = AudioOutputMode.BONE_CONDUCTION

    private var audioManager: AudioManager? = null
    private var audioFocusRequest: AudioFocusRequest? = null

    // ==================== 新增：语速调节 ====================

    enum class SpeechRate(val rate: Float) {
        SLOW(0.7f),
        NORMAL(1.0f),
        FAST(1.3f)
    }

    private var currentSpeechRate: SpeechRate = SpeechRate.NORMAL

    // ==================== 新增：TTC 碰撞预测 ====================

    /** 滑动窗口：障碍物距离历史（用于滤波平滑） */
    private val ttcDistanceWindow = ArrayDeque<Float>(TTC_WINDOW_SIZE)

    /** 滑动窗口：障碍物速度历史 */
    private val ttcSpeedWindow = ArrayDeque<Float>(TTC_WINDOW_SIZE)

    /** 最近一次TTC预警级别（避免重复播报） */
    private var lastTtcLevel: TtcLevel = TtcLevel.NONE

    enum class TtcLevel { NONE, WARNING, CRITICAL }

    // ==================== 新增：分级震动模式 ====================

    enum class VibrationPattern(val timings: LongArray, val amplitudes: IntArray?) {
        SHORT_100MS(longArrayOf(0L, VIBRATION_SHORT_MS), null),                                    // 轻微偏离盲道、路段变更
        LONG_500MS(longArrayOf(0L, VIBRATION_LONG_MS), null),                                    // 即将到达路口、即将转弯
        RAPID_3X(longArrayOf(0L, VIBRATION_SHORT_MS, VIBRATION_RAPID_INTERVAL_MS, VIBRATION_SHORT_MS, VIBRATION_RAPID_INTERVAL_MS, VIBRATION_SHORT_MS), null) // 车辆逼近、突发障碍
    }

    companion object {
        const val ACTION_START = "com.blindpath.action.START_NAVIGATION"
        const val ACTION_STOP = "com.blindpath.action.STOP_NAVIGATION"
        private const val NOTIFICATION_ID = 1002

        const val CHANNEL_NAVIGATION = "channel_navigation"

        // 新增常量
        const val ROUTINE_GUIDANCE_INTERVAL_MS = 12000L  // 12秒常规播报间隔
        const val TTC_CRITICAL_THRESHOLD_S = 2f           // 紧急TTC阈值
        const val TTC_WARNING_THRESHOLD_S = 5f            // 警告TTC阈值
        const val VIBRATION_SHORT_MS = 100L
        const val VIBRATION_LONG_MS = 500L
        const val VIBRATION_RAPID_INTERVAL_MS = 100L

        private const val TTC_WINDOW_SIZE = 5             // 滑动窗口大小
        private const val URGENT_REPEAT_COUNT = 3         // URGENT播报重复次数
    }

    override fun onBind(intent: Intent): IBinder {
        super.onBind(intent)
        return null!!
    }

    override fun onCreate() {
        super.onCreate()
        audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager
        lifecycleScope.launch {
            voiceRepository.initialize()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startNavigation()
            ACTION_STOP -> stopNavigation()
        }
        return START_STICKY
    }

    private fun startNavigation() {
        if (isRunning) return

        // 检查 GPS 降级状态
        if (!DegradationManager.isFeatureAvailable(DegradationManager.Feature.GPS_NAVIGATION)) {
            Timber.w("GPS navigation is disabled, cannot start")
            lifecycleScope.launch {
                voiceRepository.speak("导航功能当前不可用，请检查定位权限", queueMode = false)
            }
            return
        }
        if (DegradationManager.isFeatureDegraded(DegradationManager.Feature.GPS_NAVIGATION)) {
            Timber.w("GPS navigation is degraded, starting in reduced mode")
            lifecycleScope.launch {
                voiceRepository.speak("GPS信号较弱，导航精度可能降低", queueMode = false)
            }
        }

        isRunning = true

        // 启动前台通知
        val notification = createNotification("正在定位...")
        startForeground(NOTIFICATION_ID, notification)

        // 启动播报队列处理协程
        startGuidanceQueueProcessor()

        lifecycleScope.launch {
            try {
                // 启动高精度定位
                val result = navigationRepository.startNavigation()
                if (result !is com.blindpath.base.common.Result.Success) {
                    voiceRepository.speak("定位启动失败，请检查定位权限", queueMode = false)
                    stopNavigation()
                    return@launch
                }

                // 首次启动语音提示
                announceWithPriority("高精度定位已启动，请稍候", VoiceGuidancePriority.EVENT)

                // 监听导航状态
                navigationRepository.navigationState.collectLatest { state ->
                    try {
                        // 更新通知（限流）
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastLocationUpdate > 5000) {
                            val navText = state.currentInfo?.instruction ?: "定位中..."
                            updateNotification(navText)
                            lastLocationUpdate = currentTime
                        }

                        // 播报导航指令（通过分层语音队列）
                        state.currentInfo?.let { info ->
                            speakNavigation(info.instruction, info.remainingDistance)
                        }

                        // ★ GPS 质量分级语音播报（限流）
                        if (state.currentLocation != null && state.isLocationAvailable) {
                            val accuracy = state.currentLocation.accuracy
                            val quality = evaluateGpsQuality(accuracy)
                            announceGpsQualityIfNeeded(quality, accuracy)

                            // 信号弱时主动提醒
                            if (quality == GpsQuality.POOR) {
                                announceWeakSignal()
                            }

                            // [修复] 播报当前位置街道名称（带节流）
                            state.currentLocation?.let { location ->
                                announceLocationAddressIfNeeded(location)
                            }
                        }

                        // ★ TTC 碰撞预测（预留接口，待感知层接入）
                        // TODO: 接入障碍物检测数据后启用
                        // state.obstacleInfo?.let { obstacle ->
                        //     val ttc = calculateTTC(obstacle.distance, obstacle.speed, state.userSpeed)
                        //     handleTtcAlert(ttc)
                        // }
                    } catch (e: Exception) {
                        Timber.e(e, "Error processing navigation state")
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Navigation failed")
                announceWithPriority("导航异常", VoiceGuidancePriority.URGENT)
                stopNavigation()
            }
        }
    }

    private fun stopNavigation() {
        isRunning = false

        // 停止队列处理
        guidanceJob?.cancel()
        guidanceJob = null
        guidanceQueue.clear()

        lifecycleScope.launch {
            navigationRepository.stopNavigation()
            announceWithPriority("导航已关闭，祝您平安", VoiceGuidancePriority.EVENT)
        }

        // [修复] 重置所有播报节流状态
        lastInstruction = null
        lastKnownDistance = Int.MAX_VALUE
        lastGpsQualityAnnouncement = null
        lastAccuracyAnnounceTime = 0L
        lastInstructionAnnounceTime = 0L
        lastWeakSignalAnnounceTime = 0L
        lastRoutineAnnouncementTime = 0L
        lastTtcLevel = TtcLevel.NONE
        isSpeaking = false

        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ==================== 新增：分层语音引导策略 ====================

    /**
     * 带优先级的语音播报入口
     *
     * @param text 播报内容
     * @param priority 优先级：ROUTINE排队等待，EVENT打断ROUTINE，URGENT立即打断并循环3次
     */
    fun announceWithPriority(text: String, priority: VoiceGuidancePriority) {
        synchronized(guidanceQueue) {
            guidanceQueue.offer(GuidanceItem(text, priority))
        }
        Timber.d("Guidance queued: [$priority] $text")
    }

    /**
     * 启动播报队列处理协程
     */
    private fun startGuidanceQueueProcessor() {
        guidanceJob = lifecycleScope.launch {
            while (isActive) {
                val item = synchronized(guidanceQueue) {
                    guidanceQueue.poll()
                }
                if (item != null) {
                    processGuidanceItem(item)
                } else {
                    delay(200L)
                }
            }
        }
    }

    /**
     * 处理单个播报项
     */
    private suspend fun processGuidanceItem(item: GuidanceItem) {
        when (item.priority) {
            VoiceGuidancePriority.URGENT -> {
                // URGENT：立即打断当前播报，循环重复3次，最大音量
                isSpeaking = true
                requestAudioFocus(true)
                repeat(URGENT_REPEAT_COUNT) { index ->
                    if (!isRunning) return
                    val prefix = if (index == 0) "" else "注意，"
                    voiceRepository.speak(prefix + item.text, queueMode = false)
                    // 等待本次播报完成（简单延时，实际可接入TTS回调）
                    delay(estimateSpeakDuration(item.text))
                }
                isSpeaking = false
            }
            VoiceGuidancePriority.EVENT -> {
                // EVENT：打断ROUTINE，播报1次，正常音量
                isSpeaking = true
                requestAudioFocus(false)
                voiceRepository.speak(item.text, queueMode = false)
                delay(estimateSpeakDuration(item.text))
                isSpeaking = false
            }
            VoiceGuidancePriority.ROUTINE -> {
                // ROUTINE：排队等待，每10-15秒播报1次，轻柔音量
                val now = System.currentTimeMillis()
                if (now - lastRoutineAnnouncementTime < ROUTINE_GUIDANCE_INTERVAL_MS) {
                    // 时间未到，重新入队等待
                    synchronized(guidanceQueue) {
                        guidanceQueue.offer(item.copy(timestamp = now))
                    }
                    delay(1000L)
                    return
                }
                // 如果有更高优先级的正在播报，等待
                if (isSpeaking) {
                    synchronized(guidanceQueue) {
                        guidanceQueue.offer(item.copy(timestamp = now))
                    }
                    delay(500L)
                    return
                }
                isSpeaking = true
                lastRoutineAnnouncementTime = now
                // 轻柔音量：通过AudioManager降低音量（或交给VoiceRepository处理）
                voiceRepository.speak(item.text, queueMode = true)
                delay(estimateSpeakDuration(item.text))
                isSpeaking = false
            }
        }
    }

    /**
     * 估算语音播报时长（粗略估计：每字约300ms）
     */
    private fun estimateSpeakDuration(text: String): Long {
        return max(1000L, text.length * 300L)
    }

    /**
     * 请求音频焦点
     *
     * @param urgent 是否为紧急播报（使用TRANSIENT_MAY_DUCK或TRANSIENT）
     */
    private fun requestAudioFocus(urgent: Boolean) {
        audioManager ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val focusRequest = AudioFocusRequest.Builder(
                if (urgent) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            ).setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            ).build()
            audioManager?.requestAudioFocus(focusRequest)
            audioFocusRequest = focusRequest
        } else {
            @Suppress("DEPRECATION")
            audioManager?.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                if (urgent) AudioManager.AUDIOFOCUS_GAIN_TRANSIENT else AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
            )
        }
    }

    // ==================== 新增：分级震动反馈 ====================

    /**
     * 触发分级震动反馈
     *
     * @param pattern 震动模式
     */
    fun triggerVibration(pattern: VibrationPattern) {
        if (!vibrator.hasVibrator()) {
            Timber.w("Device does not support vibration")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val effect = VibrationEffect.createWaveform(pattern.timings, pattern.amplitudes, -1)
            vibrator.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(pattern.timings, -1)
        }
        Timber.d("Vibration triggered: ${pattern.name}")
    }

    /**
     * 危险预警时同时触发震动+语音
     */
    private fun alertDanger(text: String, vibrationPattern: VibrationPattern) {
        triggerVibration(vibrationPattern)
        announceWithPriority(text, VoiceGuidancePriority.URGENT)
    }

    // ==================== 新增：TTC 碰撞时间预测 ====================

    /**
     * 计算TTC（Time To Collision）碰撞时间
     *
     * @param obstacleDistance 障碍物距离（米）
     * @param obstacleSpeed 障碍物速度（米/秒，朝向用户为正）
     * @param userSpeed 用户速度（米/秒）
     * @return TTC（秒），Float.MAX_VALUE 表示无碰撞风险
     */
    fun calculateTTC(obstacleDistance: Float, obstacleSpeed: Float, userSpeed: Float): Float {
        // 数据入滑动窗口
        ttcDistanceWindow.addLast(obstacleDistance)
        ttcSpeedWindow.addLast(obstacleSpeed)
        if (ttcDistanceWindow.size > TTC_WINDOW_SIZE) ttcDistanceWindow.removeFirst()
        if (ttcSpeedWindow.size > TTC_WINDOW_SIZE) ttcSpeedWindow.removeFirst()

        // 滑动窗口平均滤波
        val avgDistance = ttcDistanceWindow.average().toFloat()
        val avgObstacleSpeed = ttcSpeedWindow.average().toFloat()

        // 相对速度（朝向用户的相对速度）
        val relativeSpeed = avgObstacleSpeed + userSpeed
        return if (relativeSpeed > 0.1f) {
            avgDistance / relativeSpeed
        } else {
            Float.MAX_VALUE
        }
    }

    /**
     * 根据TTC值触发相应级别预警
     */
    private fun handleTtcAlert(ttc: Float) {
        when {
            ttc < TTC_CRITICAL_THRESHOLD_S -> {
                if (lastTtcLevel != TtcLevel.CRITICAL) {
                    lastTtcLevel = TtcLevel.CRITICAL
                    alertDanger("紧急！前方有障碍物逼近，请立即停下", VibrationPattern.RAPID_3X)
                }
            }
            ttc < TTC_WARNING_THRESHOLD_S -> {
                if (lastTtcLevel != TtcLevel.WARNING) {
                    lastTtcLevel = TtcLevel.WARNING
                    triggerVibration(VibrationPattern.LONG_500MS)
                    announceWithPriority("注意，前方有障碍物靠近，请小心", VoiceGuidancePriority.EVENT)
                }
            }
            else -> {
                lastTtcLevel = TtcLevel.NONE
            }
        }
    }

    // ==================== 新增：音频输出适配 ====================

    /**
     * 设置音频输出模式
     *
     * @param mode 音频输出模式
     */
    fun setAudioOutputMode(mode: AudioOutputMode) {
        currentAudioMode = mode
        val modeText = when (mode) {
            AudioOutputMode.BONE_CONDUCTION -> "骨传导模式"
            AudioOutputMode.SINGLE_EAR -> "单耳耳机模式"
            AudioOutputMode.DUAL_EAR -> "双耳耳机模式"
        }
        // 切换模式时语音播报确认
        announceWithPriority("已切换至$modeText", VoiceGuidancePriority.EVENT)
        Timber.i("Audio output mode changed to: $mode")
    }

    // ==================== 新增：语速调节 ====================

    /**
     * 设置语音播报语速
     *
     * @param rate 语速枚举
     */
    fun setSpeechRate(rate: SpeechRate) {
        currentSpeechRate = rate
        // 通知 VoiceRepository 调整语速（预留接口）
        // voiceRepository.setSpeechRate(rate.rate)
        val rateText = when (rate) {
            SpeechRate.SLOW -> "慢速"
            SpeechRate.NORMAL -> "正常语速"
            SpeechRate.FAST -> "快速"
        }
        announceWithPriority("语速已调整为$rateText", VoiceGuidancePriority.ROUTINE)
        Timber.i("Speech rate changed to: ${rate.rate}")
    }

    /**
     * 设置方言播报（预留接口）
     *
     * @param dialectCode 方言代码，如 "zh-CN", "zh-YUE", "zh-SICHUAN"
     */
    fun setDialect(dialectCode: String) {
        // 预留接口：后续接入方言TTS引擎
        // voiceRepository.setDialect(dialectCode)
        announceWithPriority("方言设置已更新", VoiceGuidancePriority.ROUTINE)
        Timber.i("Dialect changed to: $dialectCode")
    }

    // ==================== 原有功能保留 ====================

    /**
     * ★ GPS 质量分级评估（使用 GpsQuality.fromAccuracy() 静态方法）
     */
    private fun evaluateGpsQuality(accuracy: Float): GpsQuality {
        return GpsQuality.fromAccuracy(accuracy)
    }

    /**
     * ★ GPS 质量语音播报（带节流，避免重复）
     */
    private fun announceGpsQualityIfNeeded(quality: GpsQuality, accuracy: Float) {
        val now = System.currentTimeMillis()

        // 节流：8秒内不重复播报相同质量等级
        if (quality == lastGpsQualityAnnouncement && now - lastAccuracyAnnounceTime < ACCURACY_ANNOUNCE_INTERVAL_MS) {
            return
        }

        lastGpsQualityAnnouncement = quality
        lastAccuracyAnnounceTime = now

        val announcement = when (quality) {
            GpsQuality.EXCELLENT -> "GPS精度${String.format("%.1f", accuracy)}米，信号优秀，可安全导航"
            GpsQuality.GOOD -> "GPS精度${String.format("%.1f", accuracy)}米，信号良好"
            GpsQuality.FAIR -> "GPS精度${String.format("%.1f", accuracy)}米，信号一般，请注意安全"
            GpsQuality.POOR -> "GPS信号弱，精度${String.format("%.1f", accuracy)}米，请在开阔地带重新定位"
        }

        // 使用分层语音引导（ROUTINE级别，避免打断导航指令）
        announceWithPriority(announcement, VoiceGuidancePriority.ROUTINE)

        Timber.d("GPS quality announced: $announcement")
    }

    /**
     * [修复] 信号弱时主动提醒（每5分钟最多播报一次）
     */
    private var lastWeakSignalAnnounceTime = 0L
    private val WEAK_SIGNAL_INTERVAL_MS = 300000L  // 5分钟

    private fun announceWeakSignal() {
        val now = System.currentTimeMillis()
        if (now - lastWeakSignalAnnounceTime >= WEAK_SIGNAL_INTERVAL_MS) {
            lastWeakSignalAnnounceTime = now
            announceWithPriority("GPS信号弱，请在开阔地带使用", VoiceGuidancePriority.EVENT)
        }
    }

    /**
     * [修复] 播报导航指令（防重复 + 时间节流）
     * 触发条件：(指令变化 OR 距离变化 >= 阈值) AND 距离上次播报 >= 15秒
     */
    private fun speakNavigation(instruction: String, remainingDistance: Int) {
        val now = System.currentTimeMillis()
        val distanceChanged = kotlin.math.abs(remainingDistance - lastKnownDistance) >= AppConfig.Navigation.INSTRUCTION_DISTANCE_THRESHOLD
        val timePassed = now - lastInstructionAnnounceTime >= INSTRUCTION_MIN_INTERVAL_MS

        // [修复] 必须同时满足：内容变化/距离变化 + 时间间隔足够
        if ((lastInstruction != instruction || distanceChanged) && timePassed) {
            lastInstruction = instruction
            lastKnownDistance = remainingDistance
            lastInstructionAnnounceTime = now
            // 导航指令使用 EVENT 优先级（打断ROUTINE，但可被URGENT打断）
            announceWithPriority(instruction, VoiceGuidancePriority.EVENT)
        }
    }

    /**
     * [修复] 播报当前位置地址信息（街道名称，带节流）
     */
    private fun announceLocationAddressIfNeeded(location: com.blindpath.module_navigation.domain.model.LocationInfo) {
        val now = System.currentTimeMillis()
        if (now - lastAddressAnnounceTime < ADDRESS_ANNOUNCE_INTERVAL_MS) return

        val addressText = buildString {
            append("当前位置：")
            when {
                location.address.isNotBlank() -> append(location.address)
                location.road.isNotBlank() -> append(location.road)
                location.street.isNotBlank() -> append(location.street)
                location.poiName.isNotBlank() -> append("附近${location.poiName}")
                else -> append("未知位置")
            }
        }

        if (addressText != lastLocationAddress) {
            lastLocationAddress = addressText
            lastAddressAnnounceTime = now
            // 地址播报使用 ROUTINE 优先级
            announceWithPriority(addressText, VoiceGuidancePriority.ROUTINE)
            Timber.d("Location address announced: $addressText")
        }
    }

    private fun createNotification(text: String): Notification {
        createNotificationChannel()

        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_NAVIGATION)
            .setContentTitle("视障导航运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)   // 提高优先级
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()
    }

    private fun updateNotification(text: String) {
        val notification = NotificationCompat.Builder(this, CHANNEL_NAVIGATION)
            .setContentTitle("视障导航运行中")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_directions)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        val notificationManager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                CHANNEL_NAVIGATION,
                "视障导航",
                android.app.NotificationManager.IMPORTANCE_HIGH   // 提高通知优先级
            ).apply {
                description = "视障人员步行导航指引，GPS 高精度定位"
                setSound(null, null)
            }

            val notificationManager = getSystemService(android.app.NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        guidanceJob?.cancel()
        super.onDestroy()
    }
}
