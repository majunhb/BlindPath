package com.blindpath.module_trip_assist.domain.model

/**
 * 交通方式枚举
 */
enum class TransportMode(
    val displayName: String,
    val voiceDescription: String
) {
    WALKING("步行", "步行"),
    BUS("公交", "乘坐公交车"),
    SUBWAY("地铁", "乘坐地铁"),
    BUS_SUBWAY("公交+地铁", "公交地铁换乘"),
    DRIVING("驾车", "驾车"),
    RIDING("骑行", "骑行");

    companion object {
        /**
         * 获取视障用户推荐的交通方式列表
         * 优先推荐地铁（有盲道和语音提示），其次公交
         */
        fun getRecommendedModes(): List<TransportMode> = listOf(
            SUBWAY, BUS, BUS_SUBWAY, WALKING
        )
    }
}

/**
 * 路线步骤（单个导航指令）
 */
data class RouteStep(
    val instruction: String,          // 导航指令文本
    val voiceInstruction: String,     // 语音播报指令
    val distance: Float,              // 本段距离（米）
    val duration: Int,                // 本段预计时间（秒）
    val transportMode: TransportMode, // 交通方式
    val startPoint: String = "",      // 起点名称
    val endPoint: String = "",        // 终点名称
    val lineNumber: String = "",      // 公交/地铁线路号
    val stationCount: Int = 0,        // 经过站点数
    val isAccessible: Boolean = false // 是否无障碍友好
)

/**
 * 路线规划结果
 */
data class RoutePlan(
    val origin: String,               // 起点名称
    val destination: String,          // 终点名称
    val totalDistance: Float,          // 总距离（米）
    val totalDuration: Int,           // 总时间（秒）
    val transportMode: TransportMode, // 交通方式
    val steps: List<RouteStep>,       // 路线步骤列表
    val isAccessibleRoute: Boolean = false, // 是否无障碍路线
    val accessibilityNotes: List<String> = emptyList() // 无障碍提示
) {
    /**
     * 生成视障友好的路线概览语音文本
     */
    fun toOverviewVoiceText(): String {
        val distanceText = when {
            totalDistance < 1000f -> "${totalDistance.toInt()}米"
            else -> String.format("%.1f公里", totalDistance / 1000)
        }
        val durationText = formatDuration(totalDuration)
        val accessibleNote = if (isAccessibleRoute) "，该路线为无障碍友好路线" else ""

        return "已为您规划${transportMode.voiceDescription}路线，" +
                "全程${distanceText}，预计需要${durationText}${accessibleNote}。"
    }

    /**
     * 生成逐步导航语音文本列表
     */
    fun toStepVoiceTexts(): List<String> {
        return steps.mapIndexed { index, step ->
            val stepNum = index + 1
            val distanceText = when {
                step.distance < 1000f -> "${step.distance.toInt()}米"
                else -> String.format("%.1f公里", step.distance / 1000)
            }

            val accessibleNote = if (step.isAccessible) "（无障碍）" else ""

            "第${stepNum}步，${step.voiceInstruction}，" +
                    "距离${distanceText}${accessibleNote}。"
        }
    }

    private fun formatDuration(seconds: Int): String {
        val hours = seconds / 3600
        val minutes = (seconds % 3600) / 60
        return when {
            hours > 0 && minutes > 0 -> "${hours}小时${minutes}分钟"
            hours > 0 -> "${hours}小时"
            minutes > 0 -> "${minutes}分钟"
            else -> "${seconds}秒"
        }
    }
}

/**
 * 无障碍设施类型
 */
enum class FacilityType(
    val displayName: String,
    val voiceDescription: String,
    val iconDescription: String
) {
    TACTILE_PAVING("盲道", "盲道", "地面盲道指引"),
    ACCESSIBLE_ELEVATOR("无障碍电梯", "无障碍电梯", "无障碍电梯"),
    ACCESSIBLE_TOILET("无障碍卫生间", "无障碍卫生间", "无障碍卫生间"),
    AUDIO_SIGNAL("语音信号灯", "语音信号灯", "带语音提示的交通信号灯"),
    TACTILE_MAP("触觉地图", "触觉地图", "触摸式地图"),
    GUIDE_DOG_AREA("导盲犬区域", "导盲犬区域", "允许导盲犬进入的区域"),
    ACCESSIBLE_ENTRANCE("无障碍入口", "无障碍入口", "无障碍通道入口"),
    BRAILLE_SIGN("盲文标识", "盲文标识", "盲文指示标识"),
    HANDRAIL("扶手", "扶手", "安全扶手"),
    RAMP("坡道", "坡道", "无障碍坡道");

    companion object {
        /**
         * 获取视障用户最关心的设施类型（按优先级排序）
         */
        fun getPriorityFacilities(): List<FacilityType> = listOf(
            TACTILE_PAVING,
            AUDIO_SIGNAL,
            ACCESSIBLE_ELEVATOR,
            ACCESSIBLE_TOILET,
            BRAILLE_SIGN,
            RAMP,
            ACCESSIBLE_ENTRANCE,
            TACTILE_MAP,
            GUIDE_DOG_AREA,
            HANDRAIL
        )
    }
}

/**
 * 无障碍设施信息
 */
data class AccessibleFacility(
    val name: String,
    val type: FacilityType,
    val distance: Float,              // 距当前位置距离（米）
    val direction: String,            // 方向描述（如"前方50米"）
    val address: String,              // 详细地址
    val isOpen: Boolean = true,       // 是否开放
    val description: String = ""      // 补充描述
) {
    /**
     * 生成视障友好的语音播报文本
     */
    fun toVoiceText(): String {
        val statusText = if (isOpen) "" else "（当前未开放）"
        val distanceText = when {
            distance < 100f -> "${distance.toInt()}米"
            else -> String.format("%.1f公里", distance / 1000)
        }
        return "${direction}约${distanceText}处有${type.voiceDescription}：" +
                "${name}${statusText}。"
    }
}
