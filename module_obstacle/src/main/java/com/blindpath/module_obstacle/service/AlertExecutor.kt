package com.blindpath.module_obstacle.service

import android.content.Context
import com.blindpath.base.common.AlertLevel
import com.blindpath.base.reliability.LatencyTracker
import com.blindpath.base.reliability.ReliabilityLogger
import com.blindpath.base.tts.VibrationHelper
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.VoiceRequest
import com.blindpath.module_voice.domain.model.VoiceType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 三路并行告警执行器
 *
 * 语音、震动两个输出通道互不依赖，独立 try-catch。
 * 单路失败不影响其他通道。
 * 音效通道预留，待音效管理器就绪后接入。
 */
@Singleton
class AlertExecutor @Inject constructor(
    private val voiceRepository: VoiceRepository,
    private val applicationContext: Context
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * 执行告警 - 三路并行
     */
    fun executeAlert(level: AlertLevel, description: String) {
        if (level == AlertLevel.SAFE) return

        LatencyTracker.beginSpan("alert_output")

        // 三路并发，互不影响
        scope.launch { runCatching { speakAlert(level, description) } }
        scope.launch { runCatching { vibrateAlert(level) } }
        // 音效通道预留（当前无音效管理器）
        // scope.launch { runCatching { playSoundAlert(level) } }

        LatencyTracker.endSpan("alert_output", LatencyTracker.ALERT_OUTPUT_BUDGET_MS)
    }

    private suspend fun speakAlert(level: AlertLevel, description: String) {
        try {
            val voiceType = when (level) {
                AlertLevel.DANGER -> VoiceType.OBSTACLE_DANGER
                AlertLevel.WARNING -> VoiceType.OBSTACLE_NORMAL
                AlertLevel.UNKNOWN -> VoiceType.SYSTEM_STATUS
                AlertLevel.SAFE -> VoiceType.OBSTACLE_LOW
            }
            voiceRepository.announce(
                VoiceRequest(
                    text = description,
                    type = voiceType,
                    interruptCurrent = level == AlertLevel.DANGER
                )
            )
        } catch (e: Exception) {
            Timber.e(e, "AlertExecutor: TTS failed")
            ReliabilityLogger.logFallback("tts_output", e.message)
        }
    }

    private fun vibrateAlert(level: AlertLevel) {
        try {
            if (level == AlertLevel.DANGER || level == AlertLevel.WARNING) {
                VibrationHelper.vibrate(applicationContext, level)
            }
        } catch (e: Exception) {
            Timber.e(e, "AlertExecutor: Vibration failed")
            ReliabilityLogger.logFallback("vibration_output", e.message)
        }
    }
}
