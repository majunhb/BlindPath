package com.blindpath.base.navigation.model

/**
 * 障碍物方向
 * 共享模型，供 base 和 module_obstacle 共同使用
 */
enum class Direction {
    LEFT,       // 左侧
    LEFT_FRONT, // 左前方
    CENTER,     // 正前方
    RIGHT_FRONT,// 右前方
    RIGHT,      // 右侧
    BACK;       // 后方

    /**
     * 获取方位的中文描述
     */
    fun getChineseName(): String {
        return when (this) {
            LEFT -> "左侧"
            LEFT_FRONT -> "左前方"
            CENTER -> "正前方"
            RIGHT_FRONT -> "右前方"
            RIGHT -> "右侧"
            BACK -> "后方"
        }
    }
}
