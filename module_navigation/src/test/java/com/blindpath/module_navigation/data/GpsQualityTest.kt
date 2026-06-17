package com.blindpath.module_navigation.data

import org.junit.Test
import org.junit.Assert.*

/**
 * GpsQuality 枚举测试
 * 
 * 测试 GPS 信号质量分级逻辑
 * 分级标准：EXCELLENT(≤1m), GOOD(1-3m), FAIR(3-10m), POOR(>10m)
 */
class GpsQualityTest {

    // ============ fromAccuracy() 测试 ============

    @Test
    fun `fromAccuracy should return EXCELLENT for accuracy <= 1`() {
        // Given
        val accuracies = listOf(0.5f, 0.8f, 1.0f)

        // When & Then
        accuracies.forEach { accuracy ->
            assertEquals(
                "Accuracy $accuracy should be EXCELLENT",
                GpsQuality.EXCELLENT,
                GpsQuality.fromAccuracy(accuracy)
            )
        }
    }

    @Test
    fun `fromAccuracy should return GOOD for accuracy 1-3m`() {
        // Given
        val accuracies = listOf(1.1f, 2.0f, 2.5f, 3.0f)

        // When & Then
        accuracies.forEach { accuracy ->
            assertEquals(
                "Accuracy $accuracy should be GOOD",
                GpsQuality.GOOD,
                GpsQuality.fromAccuracy(accuracy)
            )
        }
    }

    @Test
    fun `fromAccuracy should return FAIR for accuracy 3-10m`() {
        // Given
        val accuracies = listOf(3.1f, 5.0f, 7.5f, 10.0f)

        // When & Then
        accuracies.forEach { accuracy ->
            assertEquals(
                "Accuracy $accuracy should be FAIR",
                GpsQuality.FAIR,
                GpsQuality.fromAccuracy(accuracy)
            )
        }
    }

    @Test
    fun `fromAccuracy should return POOR for accuracy > 10m`() {
        // Given
        val accuracies = listOf(10.1f, 15.0f, 50.0f, 100.0f)

        // When & Then
        accuracies.forEach { accuracy ->
            assertEquals(
                "Accuracy $accuracy should be POOR",
                GpsQuality.POOR,
                GpsQuality.fromAccuracy(accuracy)
            )
        }
    }

    // ============ 边界值测试 ============

    @Test
    fun `boundary test - exactly 1m should be EXCELLENT`() {
        assertEquals(GpsQuality.EXCELLENT, GpsQuality.fromAccuracy(1.0f))
    }

    @Test
    fun `boundary test - slightly above 1m should be GOOD`() {
        assertEquals(GpsQuality.GOOD, GpsQuality.fromAccuracy(1.001f))
    }

    @Test
    fun `boundary test - exactly 3m should be GOOD`() {
        assertEquals(GpsQuality.GOOD, GpsQuality.fromAccuracy(3.0f))
    }

    @Test
    fun `boundary test - exactly 10m should be FAIR`() {
        assertEquals(GpsQuality.FAIR, GpsQuality.fromAccuracy(10.0f))
    }

    @Test
    fun `boundary test - slightly above 10m should be POOR`() {
        assertEquals(GpsQuality.POOR, GpsQuality.fromAccuracy(10.001f))
    }

    // ============ 属性测试 ============

    @Test
    fun `all GpsQuality values should have description`() {
        GpsQuality.values().forEach { quality ->
            assertTrue(
                "Quality $quality should have non-empty description",
                quality.description.isNotEmpty()
            )
        }
    }

    @Test
    fun `all GpsQuality values should have announcement`() {
        GpsQuality.values().forEach { quality ->
            assertTrue(
                "Quality $quality should have non-empty announcement",
                quality.announcement.isNotEmpty()
            )
        }
    }

    @Test
    fun `EXCELLENT quality should indicate safe navigation`() {
        assertTrue(GpsQuality.EXCELLENT.announcement.contains("安全"))
    }

    @Test
    fun `POOR quality should indicate weak signal`() {
        assertTrue(GpsQuality.POOR.announcement.contains("弱"))
    }

    // ============ 顺序测试 ============

    @Test
    fun `quality order should be EXCELLENT GOOD FAIR POOR`() {
        val expected = listOf(
            GpsQuality.EXCELLENT,
            GpsQuality.GOOD,
            GpsQuality.FAIR,
            GpsQuality.POOR
        )
        assertEquals(expected, GpsQuality.values().toList())
    }
}
