package com.blindpath.base.common

import org.junit.Test
import org.junit.Assert.*

/**
 * AlertLevel 和 ObstacleAlert 测试
 * 
 * 测试预警级别和障碍物预警信息
 */
class AlertModelsTest {

    // ============ AlertLevel 测试 ============

    @Test
    fun `all alert levels should have display name`() {
        AlertLevel.values().forEach { level ->
            assertTrue(
                "Level $level should have non-empty display name",
                level.displayName.isNotEmpty()
            )
        }
    }

    @Test
    fun `DANGER should have correct display name`() {
        assertEquals("危险", AlertLevel.DANGER.displayName)
    }

    @Test
    fun `WARNING should have correct display name`() {
        assertEquals("提醒", AlertLevel.WARNING.displayName)
    }

    @Test
    fun `SAFE should have correct display name`() {
        assertEquals("安全", AlertLevel.SAFE.displayName)
    }

    @Test
    fun `alert levels should be in order DANGER WARNING SAFE`() {
        val expected = listOf(AlertLevel.DANGER, AlertLevel.WARNING, AlertLevel.SAFE)
        assertEquals(expected, AlertLevel.values().toList())
    }

    // ============ ObstacleAlert 测试 ============

    @Test
    fun `ObstacleAlert should store all properties correctly`() {
        val alert = ObstacleAlert(
            level = AlertLevel.DANGER,
            description = "前方有车辆",
            distance = 1.5f,
            direction = "正前方"
        )

        assertEquals(AlertLevel.DANGER, alert.level)
        assertEquals("前方有车辆", alert.description)
        assertEquals(1.5f, alert.distance, 0.01f)
        assertEquals("正前方", alert.direction)
    }

    @Test
    fun `ObstacleAlert should support copy with different values`() {
        val original = ObstacleAlert(
            level = AlertLevel.WARNING,
            description = "行人",
            distance = 3.0f,
            direction = "左侧"
        )

        val copied = original.copy(distance = 2.0f)

        assertEquals(2.0f, copied.distance, 0.01f)
        assertEquals(original.level, copied.level)
        assertEquals(original.description, copied.description)
    }

    @Test
    fun `ObstacleAlert should be comparable by distance`() {
        val alert1 = ObstacleAlert(
            level = AlertLevel.DANGER,
            description = "A",
            distance = 1.0f,
            direction = ""
        )
        val alert2 = ObstacleAlert(
            level = AlertLevel.WARNING,
            description = "B",
            distance = 5.0f,
            direction = ""
        )

        assertTrue("Alert1 should be closer", alert1.distance < alert2.distance)
    }

    // ============ NavigationInfo 测试 ============

    @Test
    fun `NavigationInfo should store all properties correctly`() {
        val info = NavigationInfo(
            instruction = "前方左转",
            remainingDistance = 100,
            remainingTime = 120
        )

        assertEquals("前方左转", info.instruction)
        assertEquals(100, info.remainingDistance)
        assertEquals(120, info.remainingTime)
    }

    @Test
    fun `NavigationInfo should support copy`() {
        val original = NavigationInfo(
            instruction = "直行",
            remainingDistance = 50,
            remainingTime = 60
        )

        val updated = original.copy(remainingDistance = 40)

        assertEquals(40, updated.remainingDistance)
        assertEquals(original.instruction, updated.instruction)
        assertEquals(original.remainingTime, updated.remainingTime)
    }

    // ============ 综合测试 ============

    @Test
    fun `danger alert should indicate immediate action needed`() {
        val alert = ObstacleAlert(
            level = AlertLevel.DANGER,
            description = "紧急：前方有车辆",
            distance = 0.5f,
            direction = "正前方"
        )

        assertEquals(AlertLevel.DANGER, alert.level)
        assertTrue("Distance should be very close", alert.distance < 1.0f)
    }

    @Test
    fun `safe alert should indicate clear path`() {
        val alert = ObstacleAlert(
            level = AlertLevel.SAFE,
            description = "前方道路畅通",
            distance = 10.0f,
            direction = ""
        )

        assertEquals(AlertLevel.SAFE, alert.level)
        assertTrue("Distance should be far", alert.distance > 5.0f)
    }
}
