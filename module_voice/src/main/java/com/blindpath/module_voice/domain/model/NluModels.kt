package com.blindpath.module_voice.domain.model

/**
 * 语音意图定义 — PRD语音助手四层架构第三层（NLU语义理解）
 *
 * 将用户自然语言文本解析为结构化意图+槽位，支持：
 * 1. 导航与查询类意图
 * 2. 模式与控制类意图
 * 3. 紧急安全类意图
 * 4. 场景识别类意图
 * 5. 室内导航类意图
 * 6. 全局控制类意图
 */
enum class VoiceIntent(
    val id: String,
    val description: String,
    val slotNames: List<String> = emptyList()
) {
    // ===== 1. 导航与查询类 =====
    /** "我要去火车站" / "导航去超市" */
    NAVIGATE_TO("navigate_to", "导航到目的地", listOf("destination")),
    /** "导航回家" */
    NAVIGATE_HOME("navigate_home", "导航回家"),
    /** "还有多远" */
    QUERY_DISTANCE("query_distance", "查询剩余距离"),
    /** "我在哪" */
    QUERY_LOCATION("query_location", "查询当前位置"),
    /** "重复一遍" */
    REPEAT("repeat", "重复上一条播报"),

    // ===== 2. 模式与控制类 =====
    /** "切换到语音模式" */
    SWITCH_VOICE_MODE("switch_voice_mode", "切换到语音导航模式"),
    /** "切换到实景模式" / "切换到AR模式" */
    SWITCH_AR_MODE("switch_ar_mode", "切换到AR实景导航模式"),
    /** "大声一点" */
    VOLUME_UP("volume_up", "增大TTS音量"),
    /** "小声一点" */
    VOLUME_DOWN("volume_down", "减小TTS音量"),
    /** "语速快一点" */
    SPEED_UP("speed_up", "加快TTS语速"),
    /** "语速慢一点" */
    SPEED_DOWN("speed_down", "减慢TTS语速"),

    // ===== 3. 紧急安全类 =====
    /** "紧急求助" / "救命" / "我摔倒了" */
    SOS("sos", "紧急求助"),
    /** "停止导航" / "取消导航" */
    STOP_NAVIGATION("stop_navigation", "停止导航"),

    // ===== 4. 场景识别类 =====
    /** "看看前面有什么" / "这是什么" */
    LOOK_AHEAD("look_ahead", "识别前方场景"),
    /** "开启障碍物检测" */
    START_DETECTION("start_detection", "开启障碍物检测"),
    /** "关闭障碍物检测" */
    STOP_DETECTION("stop_detection", "关闭障碍物检测"),

    // ===== 5. 室内导航类 =====
    /** "我要去三楼洗手间" */
    INDOOR_NAVIGATE("indoor_navigate", "室内导航", listOf("destination")),
    /** "我在几楼" */
    QUERY_FLOOR("query_floor", "查询当前楼层"),

    // ===== 6. 全局控制类 =====
    /** "帮助" / "你能做什么" */
    HELP("help", "播报帮助信息"),
    /** 未识别的意图 */
    UNKNOWN("unknown", "未知意图");

    companion object {
        fun fromId(id: String): VoiceIntent {
            return values().find { it.id == id } ?: UNKNOWN
        }
    }
}

/**
 * 槽位值 — 意图的参数
 *
 * 例如 NAVIGATE_TO 意图需要 destination 槽位
 * 用户说"我要去火车站" → destination="火车站"
 */
data class VoiceSlot(
    val name: String,
    val value: String,
    val confidence: Float = 1.0f
)

/**
 * NLU解析结果
 *
 * 完整的语义理解输出，包含：
 * - intent: 识别出的意图
 * - slots: 提取的槽位
 * - rawText: 原始识别文本
 * - confidence: 整体置信度
 * - needsFollowUp: 是否需要追问（例如"导航去"但没有说目的地）
 * - followUpPrompt: 追问提示
 */
data class NluResult(
    val intent: VoiceIntent,
    val slots: List<VoiceSlot> = emptyList(),
    val rawText: String = "",
    val confidence: Float = 0.8f,
    val needsFollowUp: Boolean = false,
    val followUpPrompt: String? = null
) {
    /** 获取指定名称的槽位值 */
    fun getSlot(name: String): String? = slots.find { it.name == name }?.value

    /** 是否包含指定名称的槽位 */
    fun hasSlot(name: String): Boolean = slots.any { it.name == name }
}
