package com.blindpath.module_obstacle.data.detection

import com.blindpath.base.navigation.model.Direction
import com.blindpath.module_obstacle.domain.model.ObstacleType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ObstacleClassifier 单元测试
 * 验证 COCO 分类映射、距离估算和方向判定的正确性
 */
class ObstacleClassifierTest {

    private lateinit var classifier: ObstacleClassifier

    @Before
    fun setup() {
        classifier = ObstacleClassifier()
    }

    // ============ COCO 分类映射测试 ============

    @Test
    fun `classifyByCocoId should map person correctly`() {
        assertEquals(ObstacleType.PERSON, classifier.classifyByCocoId(0))
    }

    @Test
    fun `classifyByCocoId should map vehicle correctly`() {
        assertEquals(ObstacleType.VEHICLE, classifier.classifyByCocoId(2))
    }

    @Test
    fun `classifyByCocoId should map chair correctly`() {
        assertEquals(ObstacleType.CHAIR, classifier.classifyByCocoId(56))
    }

    @Test
    fun `classifyByCocoId should return null for unknown label`() {
        assertNull(classifier.classifyByCocoId(999))
    }

    @Test
    fun `classifyByCocoId should return null for negative label`() {
        assertNull(classifier.classifyByCocoId(-1))
    }

    // ============ 中文名称测试 ============

    @Test
    fun `getChineseName should return correct name for person`() {
        assertEquals("行人", classifier.getChineseName(ObstacleType.PERSON))
    }

    @Test
    fun `getChineseName should return correct name for vehicle`() {
        assertEquals("车辆", classifier.getChineseName(ObstacleType.VEHICLE))
    }

    @Test
    fun `getChineseName should return default for obstacle`() {
        assertEquals("障碍物", classifier.getChineseName(ObstacleType.OBSTACLE))
    }

    // ============ 距离估算测试 ============

    @Test
    fun `estimateDistance should calculate positive value for known type`() {
        val dist = classifier.estimateDistance(ObstacleType.PERSON, 200f, 320f)
        assertTrue("Distance should be positive", dist > 0f)
        assertTrue("Distance should be < 50m", dist < 50f)
    }

    @Test
    fun `estimateDistance should handle zero pixel height`() {
        val dist = classifier.estimateDistance(ObstacleType.PERSON, 0f, 320f)
        assertTrue("Should be >= 0", dist >= 0f)
    }

    // ============ 方向判定测试 ============

    @Test
    fun `calculateDirection should return LEFT for left-side`() {
        assertEquals(Direction.LEFT, classifier.calculateDirection(80f, 320f))
    }

    @Test
    fun `calculateDirection should return RIGHT for right-side`() {
        assertEquals(Direction.RIGHT, classifier.calculateDirection(240f, 320f))
    }

    @Test
    fun `calculateDirection should return CENTER for middle`() {
        assertEquals(Direction.CENTER, classifier.calculateDirection(160f, 320f))
    }

    // ============ 已知高度测试 ============

    @Test
    fun `getKnownHeight should return valid height for person`() {
        val h = classifier.getKnownHeight(ObstacleType.PERSON)
        assertTrue("Height > 1m", h > 1.0f)
        assertTrue("Height < 2.5m", h < 2.5f)
    }

    @Test
    fun `getKnownHeight should return default for unknown`() {
        assertTrue("Default > 0", classifier.getKnownHeight(ObstacleType.OBSTACLE) > 0f)
    }

    // ============ 边界测试 ============

    @Test
    fun `should handle all 80 COCO labels without exception`() {
        for (i in 0..79) {
            classifier.classifyByCocoId(i) // 不应抛异常
        }
    }

    @Test
    fun `getChineseName should handle all ObstacleType values`() {
        for (type in ObstacleType.values()) {
            val name = classifier.getChineseName(type)
            assertNotNull(name)
            assertTrue(name.isNotEmpty())
        }
    }
}