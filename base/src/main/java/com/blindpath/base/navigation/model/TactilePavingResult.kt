package com.blindpath.base.navigation.model

/**
 * 盲道检测结果
 * 共享模型，供 base 和 module_obstacle 共同使用
 */
data class TactilePavingResult(
    val detected: Boolean,
    val confidence: Float,           // 置信度 0-1
    val direction: Float,            // 盲道走向角度（弧度）
    val offsetFromCenter: Float,     // 偏离中心线距离（归一化，-1 到 1）
    val pavingRatio: Float           // 黄色区域占比
)
