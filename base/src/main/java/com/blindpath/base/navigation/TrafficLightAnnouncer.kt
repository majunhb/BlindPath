package com.blindpath.base.navigation

import com.blindpath.base.navigation.model.TrafficLightState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 红绿灯定时播报器 - PRD V2.0 第二期
 *
 * 功能：
 * - 红灯/黄灯：每10秒重复播报当前信号灯状态
 * - 绿灯：检测到即立刻播报，不等待定时周期
 * - 信号灯消失/变为 UNKNOWN：停止播报
 * - 信号灯状态变化（红→绿、绿→红等）：立即播报新状态
 *
 * 设计原则：
 * - 视障用户在路口等待时，需要持续获知信号灯状态
 * - 红灯等待时间长，需要定期提醒避免焦虑或误判
 * - 绿灯通行时间短，必须第一时间通知
 */
class TrafficLightAnnouncer {

    data class AnnouncerState(
        val currentLightState: TrafficLightState = TrafficLightState.UNKNOWN,
        val isAnnouncing: Boolean = false,
        val lastAnnounceTime: Long = 0L,
        val announceCount: Int = 0
    )

    private val _state = MutableStateFlow(AnnouncerState())
    val state: StateFlow<AnnouncerState> = _state.asStateFlow()

    // ==================== 参数配置 ====================

    /** 红灯/黄灯重复播报间隔（毫秒）*/
    var redYellowRepeatIntervalMs: Long = 10_000L

    /** 绿灯播报后最小间隔（毫秒）*/
    var greenMinIntervalMs: Long = 5_000L

    /** 状态变化后立即播报的最小间隔 */
    var stateChangeMinIntervalMs: Long = 2_000L

    // ==================== 内部状态 ====================

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var repeatJob: Job? = null
    private var lastAnnouncedState: TrafficLightState = TrafficLightState.UNKNOWN
    private var onAnnounce: ((TrafficLightState, String) -> Unit)? = null

    /**
     * 设置播报回调
     */
    fun setAnnounceCallback(callback: (TrafficLightState, String) -> Unit) {
        onAnnounce = callback
    }

    /**
     * 更新信号灯状态
     *
     * 由外部检测循环调用，每次检测到新的信号灯状态时调用此方法。
     *
     * @param newState 新的信号灯状态
     */
    fun updateState(newState: TrafficLightState) {
        val currentTime = System.currentTimeMillis()
        val prevState = _state.value.currentLightState
        val stateChanged = newState != prevState

        // 信号灯消失或未知 → 停止播报
        if (newState == TrafficLightState.UNKNOWN) {
            stopAnnouncing()
            _state.value = AnnouncerState(
                currentLightState = TrafficLightState.UNKNOWN,
                isAnnouncing = false
            )
            return
        }

        // 状态变化 → 立即播报
        if (stateChanged) {
            val message = generateMessage(newState)
            val timeSinceLastAnnounce = currentTime - _state.value.lastAnnounceTime

            if (timeSinceLastAnnounce >= stateChangeMinIntervalMs) {
                doAnnounce(newState, message, currentTime)
                lastAnnouncedState = newState
            }

            // 启动/重启定时播报
            startRepeatTimer(newState)
        }

        _state.value = _state.value.copy(
            currentLightState = newState,
            isAnnouncing = true
        )
    }

    /**
     * 启动定时重复播报
     *
     * 对于红灯/黄灯，每10秒重复播报一次。
     * 对于绿灯，不需要重复播报（通行时间短，一次即可）。
     */
    private fun startRepeatTimer(state: TrafficLightState) {
        repeatJob?.cancel()

        if (state == TrafficLightState.GREEN) {
            return
        }

        repeatJob = scope.launch {
            while (isActive) {
                delay(redYellowRepeatIntervalMs)

                val currentState = _state.value.currentLightState
                if (currentState == state && currentState != TrafficLightState.UNKNOWN) {
                    val currentTime = System.currentTimeMillis()
                    val message = generateMessage(currentState)
                    doAnnounce(currentState, message, currentTime)
                } else {
                    break
                }
            }
        }
    }

    /**
     * 执行播报
     */
    private fun doAnnounce(state: TrafficLightState, message: String, time: Long) {
        Timber.d("TrafficLightAnnouncer: announce state=$state, message=$message")
        onAnnounce?.invoke(state, message)

        _state.value = _state.value.copy(
            lastAnnounceTime = time,
            announceCount = _state.value.announceCount + 1
        )
    }

    /**
     * 生成播报文本
     */
    private fun generateMessage(state: TrafficLightState): String {
        return when (state) {
            TrafficLightState.RED -> "当前红灯，请继续等待"
            TrafficLightState.GREEN -> "绿灯亮了，可以通行"
            TrafficLightState.YELLOW -> "黄灯，请注意"
            TrafficLightState.FLASHING_YELLOW -> "黄灯闪烁，请谨慎通过"
            TrafficLightState.UNKNOWN -> ""
        }
    }

    /**
     * 停止播报
     */
    fun stopAnnouncing() {
        repeatJob?.cancel()
        repeatJob = null
        lastAnnouncedState = TrafficLightState.UNKNOWN
        _state.value = _state.value.copy(isAnnouncing = false)
    }

    /**
     * 重置
     */
    fun reset() {
        stopAnnouncing()
        _state.value = AnnouncerState()
    }

    /**
     * 释放资源
     */
    fun release() {
        stopAnnouncing()
        onAnnounce = null
    }
}
