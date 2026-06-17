package com.blindpath.base.navigation

import com.blindpath.module_obstacle.data.detection.TactilePavingResult
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import kotlin.math.abs

/**
 * 盲道实时引导引擎 - PRD V2.0 第二期
 *
 * 接收 TactilePavingDetector 的检测结果，生成实时语音引导指令。
 * 核心设计：
 * - 偏离分级：微调 / 大幅调整 / 完全偏离
 * - 方向判断：基于 offsetFromCenter 生成"向左/向右"指令
 * - 走向判断：基于 direction 角度变化生成"直行/转弯"指令
 * - 冷却机制：避免频繁播报，同一方向3秒内不重复
 * - 盲道断点/丢失检测：连续N帧丢失触发"未检测到盲道"预警
 *
 * 语音指令映射：
 * - offset ≈ 0，方向正确 → "盲道直行"
 * - offset 偏左/偏右 ≤ 0.3 → "向左/右微调"
 * - offset 偏左/偏右 > 0.3 → "向左/右大幅调整"
 * - 连续5帧未检测到 → "未检测到盲道，请注意安全"
 * - direction 变化 > 30° → "前方盲道转弯"
 */
class BlindPathGuidanceEngine {

    // ==================== 输出状态 ====================

    data class GuidanceState(
        val instruction: String = "",
        val direction: GuidanceDirection = GuidanceDirection.STRAIGHT,
        val offsetLevel: OffsetLevel = OffsetLevel.CENTER,
        val isBlindPathVisible: Boolean = false,
        val consecutiveLostFrames: Int = 0,
        val confidence: Float = 0f,
        val shouldSpeak: Boolean = false
    )

    enum class GuidanceDirection(val voiceText: String) {
        STRAIGHT("盲道直行"),
        TURN_LEFT("前方盲道左转"),
        TURN_RIGHT("前方盲道右转"),
        LOST("未检测到盲道，请注意安全")
    }

    enum class OffsetLevel(val voiceText: String) {
        CENTER(""),
        SLIGHT_LEFT("向左微调"),
        SLIGHT_RIGHT("向右微调"),
        MAJOR_LEFT("向左大幅调整，回到盲道上"),
        MAJOR_RIGHT("向右大幅调整，回到盲道上")
    }

    private val _state = MutableStateFlow(GuidanceState())
    val state: StateFlow<GuidanceState> = _state.asStateFlow()

    // ==================== 参数配置 ====================

    var slightOffsetThreshold: Float = 0.15f
    var majorOffsetThreshold: Float = 0.35f
    var directionChangeThreshold: Float = Math.toRadians(30.0).toFloat()
    var lostFrameThreshold: Int = 5
    var speakCooldownMs: Long = 3000L
    var sameInstructionMinIntervalMs: Long = 5000L

    // ==================== 内部状态 ====================

    private var lastSpokenInstruction: String = ""
    private var lastSpeakTime: Long = 0L
    private var lastDirectionAngle: Float = 0f
    private var consecutiveLostFrames: Int = 0
    private var directionStableCount: Int = 0
    private val directionStableThreshold = 3

    /**
     * 处理一帧盲道检测结果
     *
     * @param result 盲道检测结果，null 表示未检测到
     * @return 当前引导状态
     */
    fun processFrame(result: TactilePavingResult?): GuidanceState {
        val currentTime = System.currentTimeMillis()

        if (result == null || !result.detected) {
            consecutiveLostFrames++
            val wasVisible = _state.value.isBlindPathVisible

            if (consecutiveLostFrames >= lostFrameThreshold) {
                val instruction = GuidanceDirection.LOST.voiceText
                val shouldSpeak = wasVisible || shouldSpeakNow(instruction, currentTime)

                _state.value = GuidanceState(
                    instruction = instruction,
                    direction = GuidanceDirection.LOST,
                    offsetLevel = OffsetLevel.CENTER,
                    isBlindPathVisible = false,
                    consecutiveLostFrames = consecutiveLostFrames,
                    confidence = 0f,
                    shouldSpeak = shouldSpeak
                )

                if (shouldSpeak) {
                    lastSpokenInstruction = instruction
                    lastSpeakTime = currentTime
                }

                return _state.value
            }

            _state.value = _state.value.copy(
                consecutiveLostFrames = consecutiveLostFrames,
                isBlindPathVisible = consecutiveLostFrames < lostFrameThreshold
            )
            return _state.value
        }

        consecutiveLostFrames = 0

        // 1. 偏移等级
        val offsetLevel = classifyOffset(result.offsetFromCenter)

        // 2. 方向变化
        val direction = classifyDirection(result.direction)

        // 3. 引导指令
        val instruction = buildInstruction(direction, offsetLevel)

        // 4. 是否需要播报
        val shouldSpeak = shouldSpeakNow(instruction, currentTime)

        _state.value = GuidanceState(
            instruction = instruction,
            direction = direction,
            offsetLevel = offsetLevel,
            isBlindPathVisible = true,
            consecutiveLostFrames = 0,
            confidence = result.confidence,
            shouldSpeak = shouldSpeak
        )

        if (shouldSpeak) {
            lastSpokenInstruction = instruction
            lastSpeakTime = currentTime
        }

        lastDirectionAngle = result.direction
        return _state.value
    }

    /**
     * 偏移等级分类
     *
     * offsetFromCenter: -1(最左) 到 +1(最右)
     * 负值 = 盲道在画面右侧 = 用户偏左 = 需要向右调整
     * 正值 = 盲道在画面左侧 = 用户偏右 = 需要向左调整
     */
    private fun classifyOffset(offset: Float): OffsetLevel {
        val absOffset = abs(offset)
        return when {
            absOffset < slightOffsetThreshold -> OffsetLevel.CENTER
            offset < 0 && absOffset < majorOffsetThreshold -> OffsetLevel.SLIGHT_RIGHT
            offset > 0 && absOffset < majorOffsetThreshold -> OffsetLevel.SLIGHT_LEFT
            offset < 0 -> OffsetLevel.MAJOR_RIGHT
            else -> OffsetLevel.MAJOR_LEFT
        }
    }

    /**
     * 方向变化分类
     */
    private fun classifyDirection(currentDirection: Float): GuidanceDirection {
        val directionDelta = abs(currentDirection - lastDirectionAngle)
        val normalizedDelta = if (directionDelta > Math.PI) {
            (2 * Math.PI - directionDelta).toFloat()
        } else {
            directionDelta
        }

        if (normalizedDelta > directionChangeThreshold) {
            directionStableCount = 0
            val crossProduct = kotlin.math.sin(currentDirection - lastDirectionAngle)
            return if (crossProduct > 0) GuidanceDirection.TURN_LEFT else GuidanceDirection.TURN_RIGHT
        }

        directionStableCount++
        return GuidanceDirection.STRAIGHT
    }

    /**
     * 构建引导指令
     */
    private fun buildInstruction(direction: GuidanceDirection, offsetLevel: OffsetLevel): String {
        if (direction == GuidanceDirection.TURN_LEFT) {
            return GuidanceDirection.TURN_LEFT.voiceText
        }
        if (direction == GuidanceDirection.TURN_RIGHT) {
            return GuidanceDirection.TURN_RIGHT.voiceText
        }
        if (offsetLevel != OffsetLevel.CENTER) {
            return offsetLevel.voiceText
        }
        return GuidanceDirection.STRAIGHT.voiceText
    }

    /**
     * 判断是否需要播报
     */
    private fun shouldSpeakNow(instruction: String, currentTime: Long): Boolean {
        val timeSinceLastSpeak = currentTime - lastSpeakTime
        val isUrgent = instruction.contains("大幅调整") || instruction.contains("未检测到盲道")
        val effectiveCooldown = if (isUrgent) speakCooldownMs / 2 else speakCooldownMs

        if (timeSinceLastSpeak < effectiveCooldown) return false
        if (instruction == lastSpokenInstruction && timeSinceLastSpeak < sameInstructionMinIntervalMs) return false

        return true
    }

    /**
     * 重置引擎状态
     */
    fun reset() {
        _state.value = GuidanceState()
        lastSpokenInstruction = ""
        lastSpeakTime = 0L
        lastDirectionAngle = 0f
        consecutiveLostFrames = 0
        directionStableCount = 0
    }
}
