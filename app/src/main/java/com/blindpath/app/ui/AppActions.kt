package com.blindpath.app.ui

/**
 * 应用操作常量定义
 * 集中管理所有 ViewModel、Service、UI 间传递的 action 字符串，避免硬编码
 */
object AppActions {
    // 障碍物检测
    const val START_OBSTACLE = "start_obstacle"
    const val STOP_OBSTACLE = "stop_obstacle"

    // 导航
    const val START_NAVIGATION = "start_navigation"
    const val STOP_NAVIGATION = "stop_navigation"

    // 语音
    const val START_VOICE = "start_voice"
    const val STOP_VOICE = "stop_voice"

    // SOS
    const val TRIGGER_SOS = "trigger_sos"
    const val CANCEL_SOS = "cancel_sos"
}