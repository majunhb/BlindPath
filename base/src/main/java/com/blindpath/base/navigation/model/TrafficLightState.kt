package com.blindpath.base.navigation.model

/**
 * 红绿灯状态枚举
 * 共享模型，供 base 和 module_obstacle 共同使用
 */
enum class TrafficLightState(val chineseName: String, val voicePrompt: String) {
    RED("红灯", "前方红灯，请等待"),
    GREEN("绿灯", "绿灯，可以通行"),
    YELLOW("黄灯", "黄灯，请注意"),
    FLASHING_YELLOW("黄灯闪烁", "黄灯闪烁，请谨慎通过"),
    UNKNOWN("未知", "请观察后通行")
}
