package com.blindpath.module_obstacle.data.detection

import com.blindpath.base.navigation.model.Direction
import com.blindpath.module_obstacle.domain.model.ObstacleType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * AIDetector Facade 测试
 * 验证门面正确委托各组件，向后兼容性良好
 */
class AIDetectorTest {

    private lateinit var modelManager: ModelManager
    private lateinit var assistedDetector: AssistedDetector
    private lateinit var classifier: ObstacleClassifier
    private lateinit var aiDetector: AIDetector

    @Before
    fun setup() {
        modelManager = mockk(relaxed = true)
        assistedDetector = mockk(relaxed = true)
        classifier = ObstacleClassifier()
        aiDetector = AIDetector(
            mockk(relaxed = true), // context
            modelManager,
            assistedDetector,
            classifier
        )
    }

    // ============ 模型加载委托测试 ============

    @Test
    fun `isModelLoaded should delegate to ModelManager`() {
        every { modelManager.isModelLoaded() } returns true
        assertTrue(aiDetector.isModelLoaded())
        verify { modelManager.isModelLoaded() }
    }

    @Test
    fun `isModelLoaded should return false when ModelManager says so`() {
        every { modelManager.isModelLoaded() } returns false
        assertFalse(aiDetector.isModelLoaded())
    }

    @Test
    fun `isAssistedDetectionEnabled should delegate to ModelManager`() {
        every { modelManager.isAssistedDetectionEnabled() } returns true
        assertTrue(aiDetector.isAssistedDetectionEnabled())
    }

    @Test
    fun `resetLoadAttempt should delegate to ModelManager`() {
        aiDetector.resetLoadAttempt()
        verify { modelManager.resetLoadAttempt() }
    }

    // ============ 模式切换测试 ============

    @Test
    fun `getCurrentMode should return initial mode`() {
        // 默认模式应该存在
        assertNotNull(aiDetector.getCurrentMode())
    }
}