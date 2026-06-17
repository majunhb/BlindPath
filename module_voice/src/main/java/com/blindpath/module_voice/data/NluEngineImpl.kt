package com.blindpath.module_voice.data

import com.blindpath.module_voice.domain.NluEngine
import com.blindpath.module_voice.domain.model.NluResult
import com.blindpath.module_voice.domain.model.VoiceIntent
import com.blindpath.module_voice.domain.model.VoiceSlot
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * NLU语义理解引擎实现 — 基于规则的本地引擎
 *
 * PRD四层架构第三层，将ASR文本解析为意图+槽位。
 * 不依赖网络，全部本地规则匹配，确保弱网环境可用。
 *
 * 匹配优先级：
 * 1. 精确匹配（整句完全匹配触发词）
 * 2. 前缀匹配（"我要去" + 目的地）
 * 3. 关键词匹配（包含核心关键词）
 * 4. 模糊匹配（编辑距离容错，可选）
 *
 * 槽位提取：
 * - destination: 从"我要去XX"/"导航去XX"/"导航到XX"中提取XX
 * - 支持追问机制：如果只有动作没有目的地，返回needsFollowUp=true
 */
@Singleton
class NluEngineImpl @Inject constructor() : NluEngine {

    // ===== 匹配规则表 =====
    // 每条规则：(意图, 匹配模式列表, 是否需要提取槽位)
    private val rules = listOf(
        // 1. 导航与查询类
        Rule(VoiceIntent.NAVIGATE_TO, listOf(
            "我要去", "导航去", "导航到", "去", "走到", "走到", "帮我导航到", "带我去"
        ), extractDestination = true),
        Rule(VoiceIntent.NAVIGATE_HOME, listOf(
            "导航回家", "我要回家", "带我回家", "回家", "回我家"
        )),
        Rule(VoiceIntent.QUERY_DISTANCE, listOf(
            "还有多远", "还有多远到", "距离多远", "还有多久", "多久到", "还远吗", "快到了吗"
        )),
        Rule(VoiceIntent.QUERY_LOCATION, listOf(
            "我在哪", "我在哪里", "这是哪里", "什么地方", "我在什么地方", "告诉我位置"
        )),
        Rule(VoiceIntent.REPEAT, listOf(
            "重复", "重复一遍", "再说一遍", "再说一次", "重新说", "刚才说什么"
        )),

        // 2. 模式与控制类
        Rule(VoiceIntent.SWITCH_VOICE_MODE, listOf(
            "切换到语音模式", "切换语音模式", "语音模式", "关闭摄像头", "关闭AR"
        )),
        Rule(VoiceIntent.SWITCH_AR_MODE, listOf(
            "切换到实景模式", "切换到AR模式", "切换实景", "实景模式", "AR模式",
            "开启摄像头", "打开摄像头"
        )),
        Rule(VoiceIntent.VOLUME_UP, listOf(
            "大声一点", "大声点", "音量大一点", "大点声", "声音大一点", "大点声"
        )),
        Rule(VoiceIntent.VOLUME_DOWN, listOf(
            "小声一点", "小声点", "音量小一点", "小点声", "声音小一点"
        )),
        Rule(VoiceIntent.SPEED_UP, listOf(
            "语速快一点", "说快一点", "快一点", "语速快"
        )),
        Rule(VoiceIntent.SPEED_DOWN, listOf(
            "语速慢一点", "说慢一点", "慢一点", "语速慢"
        )),

        // 3. 紧急安全类
        Rule(VoiceIntent.SOS, listOf(
            "紧急求助", "救命", "我摔倒了", "摔倒了", "帮帮我", "救我", "出事了",
            "不好了", "危险", "我不行了"
        )),
        Rule(VoiceIntent.STOP_NAVIGATION, listOf(
            "停止导航", "取消导航", "关闭导航", "结束导航", "不去了", "不用导航了"
        )),

        // 4. 场景识别类
        Rule(VoiceIntent.LOOK_AHEAD, listOf(
            "看看前面有什么", "前面有什么", "这是什么", "看看周围", "周围有什么",
            "前面有什么东西", "识别一下"
        )),
        Rule(VoiceIntent.START_DETECTION, listOf(
            "开启障碍物检测", "打开检测", "开始检测", "检测障碍物"
        )),
        Rule(VoiceIntent.STOP_DETECTION, listOf(
            "关闭障碍物检测", "关闭检测", "停止检测"
        )),

        // 5. 室内导航类
        Rule(VoiceIntent.INDOOR_NAVIGATE, listOf(
            "室内导航去", "找", "找一下"
        ), extractDestination = true),
        Rule(VoiceIntent.QUERY_FLOOR, listOf(
            "我在几楼", "几楼", "这是几楼", "哪层"
        )),

        // 6. 全局控制类
        Rule(VoiceIntent.HELP, listOf(
            "帮助", "你能做什么", "有什么功能", "使用说明", "怎么用"
        ))
    )

    // 上下文（连续对话支持）
    @Volatile
    private var contextResult: NluResult? = null

    // 自定义规则
    private val customPatterns = mutableMapOf<VoiceIntent, MutableList<String>>()

    override fun parse(text: String): NluResult {
        val normalizedText = text.trim()
            .replace(" ", "")
            .replace("　", "")
            .replace("。", "")
            .replace("，", "")
            .replace("？", "")
            .replace("！", "")
            .replace(".", "")
            .replace(",", "")

        if (normalizedText.isBlank()) {
            return NluResult(intent = VoiceIntent.UNKNOWN, rawText = text, confidence = 0.1f)
        }

        // 1. 优先检查上下文（连续对话）
        val ctx = contextResult
        if (ctx != null && ctx.needsFollowUp) {
            // 用户可能在补充信息（如补充目的地）
            val contextIntent = ctx.intent
            if (contextIntent.slotNames.contains("destination")) {
                // 将整个文本作为槽位值
                val result = NluResult(
                    intent = contextIntent,
                    slots = listOf(VoiceSlot("destination", text.trim())),
                    rawText = text,
                    confidence = 0.85f
                )
                clearContext() // 补充完毕，清除上下文
                Timber.i("NluEngine: 上下文补充 → ${result.intent.id} slot=destination:\"${text.trim()}\"")
                return result
            }
        }

        // 2. 自定义规则匹配
        for ((intent, patterns) in customPatterns) {
            for (pattern in patterns) {
                if (normalizedText.contains(pattern)) {
                    val result = NluResult(
                        intent = intent,
                        rawText = text,
                        confidence = 0.9f
                    )
                    Timber.i("NluEngine: 自定义规则匹配 → ${intent.id} (pattern=\"$pattern\")")
                    return result
                }
            }
        }

        // 3. 内置规则匹配（按规则表顺序，优先匹配更具体的意图）
        // 先匹配SOS等高优先级
        val sortedRules = rules.sortedByDescending { it.intent.ordinal }

        for (rule in sortedRules) {
            for (pattern in rule.patterns) {
                val normalizedPattern = pattern.trim().replace(" ", "")
                if (normalizedText.contains(normalizedPattern)) {
                    val slots = mutableListOf<VoiceSlot>()

                    // 提取槽位
                    if (rule.extractDestination) {
                        val dest = extractDestination(normalizedText, normalizedPattern)
                        if (dest != null) {
                            slots.add(VoiceSlot("destination", dest))
                        }
                    }

                    val result = NluResult(
                        intent = rule.intent,
                        slots = slots,
                        rawText = text,
                        confidence = 0.9f,
                        needsFollowUp = rule.extractDestination && slots.isEmpty(),
                        followUpPrompt = if (rule.extractDestination && slots.isEmpty()) {
                            "请问您要去哪里？"
                        } else null
                    )

                    // 如果需要追问，保存上下文
                    if (result.needsFollowUp) {
                        contextResult = result
                    }

                    Timber.i("NluEngine: 匹配 → ${rule.intent.id}" +
                        (if (slots.isNotEmpty()) " slots=${slots.map { "${it.name}=\"${it.value}\"" }}" else "") +
                        (if (result.needsFollowUp) " [需追问]" else ""))
                    return result
                }
            }
        }

        // 4. 无匹配
        Timber.w("NluEngine: 未匹配 → \"$text\"")
        return NluResult(
            intent = VoiceIntent.UNKNOWN,
            rawText = text,
            confidence = 0.2f,
            needsFollowUp = false,
            followUpPrompt = "没有听清，请您大声再说一遍"
        )
    }

    /**
     * 从文本中提取目的地槽位
     *
     * 示例：
     * "我要去火车站" + "我要去" → "火车站"
     * "导航去北京站" + "导航去" → "北京站"
     */
    private fun extractDestination(normalizedText: String, pattern: String): String? {
        val normalizedPattern = pattern.trim().replace(" ", "")
        val idx = normalizedText.indexOf(normalizedPattern)
        if (idx >= 0) {
            val dest = normalizedText.substring(idx + normalizedPattern.length)
            if (dest.isNotBlank()) {
                return dest
            }
        }
        return null
    }

    override fun setContext(previousResult: NluResult) {
        contextResult = previousResult
        Timber.d("NluEngine: 上下文设置 → ${previousResult.intent.id}")
    }

    override fun clearContext() {
        contextResult = null
        Timber.d("NluEngine: 上下文清除")
    }

    override fun getCurrentContext(): NluResult? = contextResult

    override fun registerPattern(intent: VoiceIntent, patterns: List<String>) {
        customPatterns.getOrPut(intent) { mutableListOf() }.addAll(patterns)
        Timber.i("NluEngine: 注册自定义规则 → ${intent.id} (${patterns.size} patterns)")
    }

    override fun getSupportedIntents(): List<VoiceIntent> = VoiceIntent.values().toList()

    /**
     * 内部匹配规则
     */
    private data class Rule(
        val intent: VoiceIntent,
        val patterns: List<String>,
        val extractDestination: Boolean = false
    )
}
