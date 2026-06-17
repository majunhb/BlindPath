package com.blindpath.base.tts

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import timber.log.Timber
import com.blindpath.base.common.AlertLevel
import com.blindpath.base.navigation.BlindPathGuidanceEngine
import com.blindpath.base.navigation.model.Direction

/**
 * 方向性震动反馈管理器 - PRD V2.0 第二期
 *
 * 基于障碍物方向和危险等级，生成方向性震动模式：
 *
 * 分级策略：
 * 1. LOW（安全/远距离）→ 无震动
 * 2. HIGH（预警/1.5-3m）→ 单侧短震（左/右根据障碍物方向）
 * 3. CRITICAL（紧急/<1.5m）→ 双侧急促连续震动
 *
 * 方向性震动：
 * - 障碍物在左前方 → 左侧震动（短-长模式）
 * - 障碍物在右前方 → 右侧震动（长-短模式）
 * - 障碍物在正前方 → 双侧同时震动
 * - 障碍物在后方 → 双侧交替震动
 *
 * 盲道引导震动：
 * - 偏左微调 → 左侧轻微提示
 * - 偏右微调 → 右侧轻微提示
 * - 大幅偏移 → 对应侧强震
 *
 * 技术限制：
 * - 标准手机只有单个震动马达，无法真正实现方向性震动
 * - 通过震动节奏模式模拟方向感（左=短-长，右=长-短）
 * - 未来可扩展为双马达设备（如手表+手机）的真正方向震动
 */
object DirectionalVibrationHelper {

    private const val TAG = "DirectionalVibration"

    // ==================== 方向性震动模式 ====================

    /** 左侧提示：短-长节奏（100ms + 200ms 间隔 + 300ms）*/
    private val LEFT_PATTERN = longArrayOf(0, 100, 200, 300)

    /** 右侧提示：长-短节奏（300ms + 200ms 间隔 + 100ms）*/
    private val RIGHT_PATTERN = longArrayOf(0, 300, 200, 100)

    /** 正前方/双侧：均匀双震（200ms + 100ms 间隔 + 200ms）*/
    private val CENTER_PATTERN = longArrayOf(0, 200, 100, 200)

    /** 后方/交替：三连震（150ms + 100ms + 150ms + 100ms + 150ms）*/
    private val REAR_PATTERN = longArrayOf(0, 150, 100, 150, 100, 150)

    // ==================== 预警级别震动 ====================

    /** HIGH（1.5-3m）：方向性单次震动 */
    fun vibrateWarning(context: Context, direction: Direction) {
        val pattern = directionToPattern(direction)
        vibrateOnce(context, pattern)
    }

    /** CRITICAL（<1.5m）：方向性急促连续震动 */
    fun vibrateCritical(context: Context, direction: Direction) {
        val pattern = directionToPattern(direction)
        vibrateUrgent(context, pattern)
    }

    // ==================== 盲道引导震动 ====================

    /**
     * 盲道偏移引导震动
     *
     * 根据偏移方向和程度生成对应的震动模式
     */
    fun vibrateBlindPathGuidance(
        context: Context,
        offsetLevel: BlindPathGuidanceEngine.OffsetLevel
    ) {
        when (offsetLevel) {
            BlindPathGuidanceEngine.OffsetLevel.CENTER -> {
                // 在盲道中心，轻触确认
                VibrationHelper.vibrate(context, longArrayOf(0, 50))
            }
            BlindPathGuidanceEngine.OffsetLevel.SLIGHT_LEFT -> {
                // 需要向左微调 → 左侧提示
                vibrateOnce(context, LEFT_PATTERN)
            }
            BlindPathGuidanceEngine.OffsetLevel.SLIGHT_RIGHT -> {
                // 需要向右微调 → 右侧提示
                vibrateOnce(context, RIGHT_PATTERN)
            }
            BlindPathGuidanceEngine.OffsetLevel.MAJOR_LEFT -> {
                // 需要向左大幅调整 → 左侧强震
                vibrateUrgent(context, LEFT_PATTERN)
            }
            BlindPathGuidanceEngine.OffsetLevel.MAJOR_RIGHT -> {
                // 需要向右大幅调整 → 右侧强震
                vibrateUrgent(context, RIGHT_PATTERN)
            }
        }
    }

    /**
     * 盲道丢失预警震动
     */
    fun vibrateBlindPathLost(context: Context) {
        // 盲道丢失 → 三次急促震动
        VibrationHelper.vibrate(context, longArrayOf(0, 300, 100, 300, 100, 300))
    }

    // ==================== 内部方法 ====================

    /**
     * 方向转震动模式
     */
    private fun directionToPattern(direction: Direction): LongArray {
        return when (direction) {
            Direction.LEFT -> LEFT_PATTERN
            Direction.LEFT_FRONT -> LEFT_PATTERN
            Direction.RIGHT -> RIGHT_PATTERN
            Direction.RIGHT_FRONT -> RIGHT_PATTERN
            Direction.CENTER -> CENTER_PATTERN
            Direction.BACK -> REAR_PATTERN
        }
    }

    /**
     * 单次震动（不重复）
     */
    private fun vibrateOnce(context: Context, pattern: LongArray) {
        val vibrator = getVibrator(context) ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, -1)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Directional vibration failed")
        }
    }

    /**
     * 紧急震动（重复3次）
     */
    private fun vibrateUrgent(context: Context, basePattern: LongArray) {
        val vibrator = getVibrator(context) ?: return
        try {
            // 将基础模式重复3次，中间加间隔
            val urgentPattern = buildUrgentPattern(basePattern, repeatCount = 3)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(urgentPattern, -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(urgentPattern, -1)
            }
        } catch (e: Exception) {
            Timber.tag(TAG).e(e, "Urgent vibration failed")
        }
    }

    /**
     * 构建紧急震动模式（重复基础模式N次）
     */
    private fun buildUrgentPattern(basePattern: LongArray, repeatCount: Int): LongArray {
        val result = mutableListOf<Long>()
        for (i in 0 until repeatCount) {
            result.addAll(basePattern.toList())
            // 每次重复之间加200ms间隔
            if (i < repeatCount - 1) {
                result.add(200L)
            }
        }
        return result.toLongArray()
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}
