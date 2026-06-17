package com.blindpath.base.navigation

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber

/**
 * 导航模式管理器 - PRD V2.0 第一期
 *
 * 统一管理语音导航 / AR实景导航的模式切换。
 * 提供 StateFlow 暴露当前模式，供 UI 层响应式收集。
 *
 * 设计原则：
 * - 单一状态源（Single Source of Truth）
 * - 切换过程可感知（isTransitioning），UI 据此渲染过渡动画
 * - 切换时长 ≤ 1 秒（PRD 要求）
 */
class NavigationModeManager {

    private val _currentMode = MutableStateFlow(NavigationMode.VOICE)
    val currentMode: StateFlow<NavigationMode> = _currentMode.asStateFlow()

    private val _isTransitioning = MutableStateFlow(false)
    val isTransitioning: StateFlow<Boolean> = _isTransitioning.asStateFlow()

    /**
     * 切换导航模式
     *
     * @param targetMode 目标模式
     * @param onMidTransition 过渡中间回调（可选，用于触发语音播报等）
     *
     * 过渡流程：
     * 1. isTransitioning = true（触发 UI 淡出动画）
     * 2. 延迟 500ms（过渡中间点）
     * 3. 更新 _currentMode（UI 切换内容）
     * 4. 延迟 500ms（触发 UI 淡入动画）
     * 5. isTransitioning = false（过渡结束）
     *
     * 总时长 ≈ 1000ms（符合 PRD ≤1秒要求）
     */
    suspend fun switchMode(
        targetMode: NavigationMode,
        onMidTransition: (() -> Unit)? = null
    ) {
        if (targetMode == _currentMode.value) {
            Timber.d("NavigationModeManager: Already in $targetMode, skip switch")
            return
        }

        if (_isTransitioning.value) {
            Timber.w("NavigationModeManager: Already transitioning, ignore request")
            return
        }

        Timber.i("NavigationModeManager: Switching from ${_currentMode.value} to $targetMode")

        // Phase 1: 淡出
        _isTransitioning.value = true
        delay(TRANSITION_FADE_DURATION_MS)

        // Phase 2: 中间回调（语音播报等）
        onMidTransition?.invoke()

        // Phase 3: 切换模式
        _currentMode.value = targetMode

        // Phase 4: 淡入
        delay(TRANSITION_FADE_DURATION_MS)

        // Phase 5: 过渡完成
        _isTransitioning.value = false
        Timber.i("NavigationModeManager: Switch complete, now in $targetMode")
    }

    /**
     * 立即设置模式（不经过过渡动画）
     * 用于初始化或恢复状态时使用
     */
    fun setModeImmediately(mode: NavigationMode) {
        _currentMode.value = mode
        _isTransitioning.value = false
        Timber.d("NavigationModeManager: Mode set immediately to $mode")
    }

    companion object {
        /** 单次淡入/淡出时长（毫秒），总过渡 = 2 × 此值 ≤ 1000ms */
        const val TRANSITION_FADE_DURATION_MS = 450L
    }
}

/**
 * 导航模式枚举
 *
 * VOICE - 语音导航模式（ OutdoorNavigationScreen ）
 * AR    - 实景导航模式（ ARNavigationScreen ）
 */
enum class NavigationMode(val displayName: String) {
    VOICE("语音导航"),
    AR("实景导航")
}
