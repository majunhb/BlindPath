package com.blindpath.module_voice.domain.model

import java.text.Normalizer

/**
 * 语音指令类型
 *
 * 支持的语音指令：
 * - 功能开启/关闭
 * - 导航控制
 * - 紧急救援
 * - 系统设置
 */
enum class VoiceCommand(
    val spokenText: String,
    val description: String,
    val keywords: List<String> = emptyList()
) {
    // 障碍物检测
    START_OBSTACLE_DETECTION("开启障碍物检测", "启动障碍物检测功能"),
    STOP_OBSTACLE_DETECTION("关闭障碍物检测", "停止障碍物检测功能"),

    // 声呐检测
    START_SONAR_DETECTION("开启声呐检测", "启动声呐检测功能"),
    STOP_SONAR_DETECTION("关闭声呐检测", "停止声呐检测功能"),

    // 导航
    START_NAVIGATION("开启导航", "启动导航功能"),
    STOP_NAVIGATION("关闭导航", "停止导航功能"),
    WHERE_AM_I("我在哪里", "播报当前位置"),

    // 紧急救援
    SOS("紧急救援", "触发紧急救援"),
    CALL_SOS("呼叫救援", "拨打紧急救援电话"),

    // 地图
    SHOW_MAP("地图", "显示地图界面"),
    HIDE_MAP("关闭地图", "关闭地图界面"),

    // 设置
    OPEN_SETTINGS("设置", "打开设置界面"),
    CLOSE_SETTINGS("关闭设置", "关闭设置界面"),

    // 通用
    HELP("帮助", "播报帮助信息"),
    REPEAT("重复", "重复上一条播报"),
    CANCEL("取消", "取消当前操作"),
    BACK("返回", "返回上一界面"),

    // 物品查找
    FIND_ITEM("找东西", "查找指定物品", listOf("找", "寻找", "帮我找", "在哪里", "在哪")),
    STOP_FINDING("停止查找", "停止物品查找", listOf("不找了", "停止查找", "取消查找")),

    // 公交引导
    FIND_BUS_STOP("找公交站", "查找附近公交站", listOf("公交站", "公交车站", "车站在哪")),
    TAKE_BUS("坐公交", "查询公交线路", listOf("坐几路", "坐公交", "乘公交", "怎么坐车")),
    NEXT_STOP("下一站", "播报下一站信息", listOf("下一站", "还有几站", "到站了没")),

    // 场景询问
    WHAT_PLACE("这是什么地方", "识别当前场所", listOf("这是什么地方", "我在哪", "什么地方", "哪里")),

    // ========== 三大核心模块 v3.0 ==========
    // 室内感知模块
    START_INDOOR_PERCEPTION("开启室内感知", "启动室内感知功能", listOf("室内感知", "室内导航", "室内模式")),
    STOP_INDOOR_PERCEPTION("关闭室内感知", "停止室内感知功能", listOf("关闭室内", "退出室内")),

    // 出行导航模块
    START_OUTDOOR_NAVIGATION("开启出行导航", "启动出行导航功能", listOf("出行导航", "室外导航", "出门导航", "导航出行")),
    STOP_OUTDOOR_NAVIGATION("关闭出行导航", "停止出行导航功能", listOf("关闭导航", "退出导航")),

    // 场景感知模块
    START_SCENE_PERCEPTION("开启场景感知", "启动场景感知功能", listOf("场景感知", "环境感知", "识别场景", "看看周围")),
    STOP_SCENE_PERCEPTION("关闭场景感知", "停止场景感知功能", listOf("关闭场景", "退出场景"));

    companion object {
        /**
         * Unicode NFC 规范化文本
         *
         * 解决不同设备 SpeechRecognizer 返回不同 Unicode 形式的问题。
         */
        private fun normalizeText(text: String): String {
            return Normalizer.normalize(text.trim(), Normalizer.Form.NFC)
                .replace(" ", "")           // 移除半角空格
                .replace("　", "")      // 移除全角空格
                .replace("​", "")      // 移除零宽空格
                .replace("﻿", "")      // 移除 BOM
        }

        /**
         * 从语音文本解析指令
         *
         * 匹配优先级：
         * 1. spokenText 精确包含匹配（原有逻辑）
         * 2. keywords 关键词匹配（新增指令使用）
         *
         * 使用 Unicode NFC 规范化确保不同设备的识别结果一致性匹配
         */
        fun fromSpokenText(text: String): VoiceCommand? {
            val normalizedText = normalizeText(text)
            return values().find { command ->
                // 优先匹配 spokenText
                if (normalizedText.contains(normalizeText(command.spokenText))) {
                    return@find true
                }
                // 其次匹配 keywords
                if (command.keywords.isNotEmpty()) {
                    command.keywords.any { keyword ->
                        normalizedText.contains(normalizeText(keyword))
                    }
                } else {
                    false
                }
            }
        }

        /**
         * 获取所有指令的语音文本列表
         */
        fun getAllSpokenTexts(): List<String> = values().map { it.spokenText }
    }
}

/**
 * 语音指令识别结果
 */
data class VoiceCommandResult(
    val command: VoiceCommand?,
    val confidence: Float,
    val rawText: String,
    val timestamp: Long = System.currentTimeMillis()
) {
    val isSuccess: Boolean
        get() = command != null && confidence > 0.7f

    val failureReason: String?
        get() = when {
            command == null -> "未识别的指令："
            confidence <= 0.7f -> "置信度过低："
            else -> null
        }
}

/**
 * 语音交互状态
 */
data class VoiceInteractionState(
    val isInitialized: Boolean = false,
    val isListening: Boolean = false,
    val isWakeWordEnabled: Boolean = true,
    val isWakeWordDetected: Boolean = false,  // 唤醒词是否被检测到
    val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD,  // 唤醒词（统一配置）
    val lastCommand: VoiceCommandResult? = null,
    val lastError: String? = null,
    val hasSpokenWelcome: Boolean = false  // 是否已播报欢迎语
)

/**
 * 语音引导内容
 */
object VoiceGuidance {
    const val WELCOME_MESSAGE = "欢迎来到智行AI导航，我是小智，我可以为您提供出行导航和环境感知障碍物识别。你呼唤小智小智语音唤醒我"
    const val WAKE_WORD_PROMPT = "请说\"小智小智\"唤醒语音助手，或者说帮助查看可用指令"

    val HELP_MESSAGE = """
        可用语音指令：
        开启障碍物检测、关闭障碍物检测
        开启声呐检测、关闭声呐检测
        开启导航、关闭导航
        紧急救援、呼叫救援
        地图、关闭地图
        设置、关闭设置
        我在哪里、帮助、重复、取消、返回
        找东西、停止查找
        找公交站、坐公交、下一站
        这是什么地方
        开启室内感知、关闭室内感知
        开启出行导航、关闭出行导航
        开启场景感知、关闭场景感知
    """.trimIndent()

    const val OBSTACLE_DETECTION_STARTED = "障碍物检测已开启，正在扫描前方环境"
    const val OBSTACLE_DETECTION_STOPPED = "障碍物检测已关闭"
    const val SONAR_DETECTION_STARTED = "声呐检测已开启"
    const val SONAR_DETECTION_STOPPED = "声呐检测已关闭"
    const val NAVIGATION_STARTED = "导航已开启"
    const val NAVIGATION_STOPPED = "导航已关闭"
    const val SOS_TRIGGERED = "紧急救援已触发，正在呼叫救援"
    const val SETTINGS_OPENED = "设置界面已打开"
    const val SETTINGS_CLOSED = "设置界面已关闭"
    const val MAP_OPENED = "地图界面已打开"
    const val MAP_CLOSED = "地图界面已关闭"
    const val COMMAND_NOT_RECOGNIZED = "未识别的指令，请说\"帮助\"查看可用指令"

    // 三大模块语音引导 v3.0
    const val INDOOR_PERCEPTION_STARTED = "室内感知已启动"
    const val INDOOR_PERCEPTION_STOPPED = "室内感知已停止"
    const val OUTDOOR_NAVIGATION_STARTED = "出行导航已启动"
    const val OUTDOOR_NAVIGATION_STOPPED = "出行导航已停止"
    const val SCENE_PERCEPTION_STARTED = "场景感知已启动"
    const val SCENE_PERCEPTION_STOPPED = "场景感知已停止"
}