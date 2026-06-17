package com.blindpath.module_voice.domain

import com.blindpath.module_voice.domain.model.NluResult
import com.blindpath.module_voice.domain.model.VoiceIntent

/**
 * NLU语义理解引擎接口 — PRD四层架构第三层
 *
 * 核心职责：
 * 1. 将ASR识别文本解析为意图+槽位
 * 2. 支持上下文记忆（连续对话）
 * 3. 支持模糊匹配和容错
 * 4. 追问机制（信息不足时主动追问）
 *
 * 设计原则：
 * - 本地规则引擎（不依赖网络，视障用户户外弱网场景）
 * - 优先级：精确匹配 > 关键词匹配 > 模糊匹配
 * - 可扩展：后续可接入讯飞NLU/大模型API增强
 */
interface NluEngine {

    /**
     * 解析用户语音文本
     *
     * @param text ASR识别的原始文本
     * @return NLU解析结果（意图+槽位+置信度）
     */
    fun parse(text: String): NluResult

    /**
     * 设置上下文（用于连续对话）
     *
     * 例如用户先说"我要去火车站"，系统追问"哪个火车站"，用户回答"北京站"
     * 第二轮需要知道上下文意图是 NAVIGATE_TO
     *
     * @param previousResult 上一轮NLU结果
     */
    fun setContext(previousResult: NluResult)

    /**
     * 清除上下文
     */
    fun clearContext()

    /**
     * 获取当前上下文
     */
    fun getCurrentContext(): NluResult?

    /**
     * 注册自定义意图匹配规则
     *
     * @param intent 意图
     * @param patterns 匹配模式列表（正则或关键词）
     */
    fun registerPattern(intent: VoiceIntent, patterns: List<String>)

    /**
     * 获取支持的意图列表
     */
    fun getSupportedIntents(): List<VoiceIntent>
}
