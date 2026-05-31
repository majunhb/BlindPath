package com.blindpath.module_voice.domain.model

/**
 * 唤醒词统一配置
 *
 * 所有模块引用同一个唤醒词，避免各文件各说各话。
 * 修改唤醒词只需改此处。
 */
object WakeWordConfig {
    /** 默认唤醒词 - 修改为"小智小智"（用户实际使用的唤醒词）*/
    const val DEFAULT_WAKE_WORD = "小智小智"
    
    /** 百度唤醒词模型文件（assets 目录） */
    const val BAIDU_WAKE_WORD_ASSET = "WakeUp_xiaozhi.bin"
    
    /** 科大讯飞唤醒词资源文件（assets/ivw/ 目录，格式: {appid}.jet） */
    const val XF_WAKE_THRESHOLD = 1450

    /**
     * 唤醒词别名集合 —— SpeechRecognizer 可能将同一句话识别为不同文本，
     * 把所有合理变体都纳入匹配范围，降低漏触发率。
     */
    val WAKE_WORD_ALIASES: Set<String> = setOf(
        DEFAULT_WAKE_WORD,      // "小智小智"
        "小智同学",               // 原默认词，保持兼容
        "小智",                   // 简称
        "晓得同学",               // 近音
        "小智同窗"                // 近音
    )

    /**
     * 检查文本是否包含唤醒词（含别名匹配）
     */
    fun containsWakeWord(text: String): Boolean {
        return WAKE_WORD_ALIASES.any { text.contains(it) }
    }
}
