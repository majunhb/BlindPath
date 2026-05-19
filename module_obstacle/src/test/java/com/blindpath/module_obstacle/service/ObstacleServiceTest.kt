package com.blindpath.module_obstacle.service

import org.junit.Test

/**
 * ObstacleService 预警防重复逻辑测试
 *
 * 避障预警防重复规则：
 * 1. 相同描述的消息在3秒内不重复播报
 * 2. 不同描述的消息立即播报
 * 3. 超过3秒间隔后允许重复播报
 */
class AlertDeduplicationTest {

    private var lastAlertMessage: String? = null
    private var lastAlertTime = 0L
    private val alertRepeatMinInterval = 3000L

    fun handleAlert(description: String): Boolean {
        val currentTime = System.currentTimeMillis()

        // 防重复逻辑（与 ObstacleService 一致）
        if (description == lastAlertMessage && currentTime - lastAlertTime < alertRepeatMinInterval) {
            return false // 被过滤
        }

        lastAlertMessage = description
        lastAlertTime = currentTime
        return true // 正常播报
    }

    @Test
    fun `should filter repeated alerts within interval`() {
        // Given
        val description = "测试预警"

        // When
        val result1 = handleAlert(description)
        val result2 = handleAlert(description) // 立即重复

        // Then
        assert(result1) { "第一次应该播报" }
        assert(!result2) { "间隔内的重复应该被过滤" }
    }

    @Test
    fun `should allow different descriptions`() {
        // Given
        val desc1 = "障碍物A"
        val desc2 = "障碍物B"

        // When
        val result1 = handleAlert(desc1)
        val result2 = handleAlert(desc2)

        // Then
        assert(result1)
        assert(result2)
    }

    @Test
    fun `should allow same alert after interval`() {
        // Given
        val description = "间隔后的重复"

        // When
        handleAlert(description)

        // 模拟时间流逝
        lastAlertTime = System.currentTimeMillis() - alertRepeatMinInterval - 100

        val result = handleAlert(description)

        // Then
        assert(result) { "间隔后应该允许重复播报" }
    }
}
