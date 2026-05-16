package com.blindpath.module_navigation.data

/**
 * GPS 信号质量分级
 * 用于给视障用户提供清晰的语音反馈
 *
 * 分级标准（基于 accuracy 精度值）：
 * - EXCELLENT: ≤1m  — 信号优秀，可安全导航
 * - GOOD:      1~3m — 信号良好
 * - FAIR:      3~10m — 信号一般
 * - POOR:      >10m  — 信号弱，需重新定位
 */
enum class GpsQuality(val description: String, val announcement: String) {
    EXCELLENT(
        description = "信号优秀",
        announcement = "信号优秀，可安全导航"
    ),
    GOOD(
        description = "信号良好",
        announcement = "信号良好"
    ),
    FAIR(
        description = "信号一般",
        announcement = "信号一般，请注意安全"
    ),
    POOR(
        description = "信号弱",
        announcement = "信号弱，请在开阔地带重新定位"
    );

    companion object {
        /**
         * 根据 GPS 精度值评估信号质量
         * @param accuracy GPS 精度（米）- 来自 Location.accuracy
         */
        fun fromAccuracy(accuracy: Float): GpsQuality {
            return when {
                accuracy <= 1f -> EXCELLENT
                accuracy <= 3f -> GOOD
                accuracy <= 10f -> FAIR
                else -> POOR
            }
        }
    }
}
