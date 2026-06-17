package com.blindpath.base.error

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 降级管理器单元测试
 */
class DegradationManagerTest {
    
    @Before
    fun setup() {
        // 重置所有降级状态
        DegradationManager.resetAll()
    }
    
    @Test
    fun `initial state should be normal for all features`() {
        assertEquals(
            DegradationManager.DegradationLevel.NORMAL,
            DegradationManager.getDegradationLevel(DegradationManager.Feature.AI_DETECTION)
        )
        assertEquals(
            DegradationManager.DegradationLevel.NORMAL,
            DegradationManager.getDegradationLevel(DegradationManager.Feature.GPS_NAVIGATION)
        )
    }
    
    @Test
    fun `isFeatureAvailable should return true for normal state`() {
        assertTrue(DegradationManager.isFeatureAvailable(DegradationManager.Feature.AI_DETECTION))
    }
    
    @Test
    fun `isFeatureAvailable should return false for disabled state`() {
        DegradationManager.setDegradationLevel(
            DegradationManager.Feature.CAMERA,
            DegradationManager.DegradationLevel.DISABLED
        )
        
        assertFalse(DegradationManager.isFeatureAvailable(DegradationManager.Feature.CAMERA))
    }
    
    @Test
    fun `setDegradationLevel should update state`() {
        DegradationManager.setDegradationLevel(
            DegradationManager.Feature.AI_DETECTION,
            DegradationManager.DegradationLevel.REDUCED,
            "Test reason"
        )
        
        assertEquals(
            DegradationManager.DegradationLevel.REDUCED,
            DegradationManager.getDegradationLevel(DegradationManager.Feature.AI_DETECTION)
        )
    }
    
    @Test
    fun `restoreFeature should reset to normal`() {
        // 先设置降级
        DegradationManager.setDegradationLevel(
            DegradationManager.Feature.TTS_VOICE,
            DegradationManager.DegradationLevel.DISABLED
        )
        
        // 恢复
        DegradationManager.restoreFeature(DegradationManager.Feature.TTS_VOICE)
        
        assertEquals(
            DegradationManager.DegradationLevel.NORMAL,
            DegradationManager.getDegradationLevel(DegradationManager.Feature.TTS_VOICE)
        )
    }
    
    @Test
    fun `handleDegradation should handle ModelLoadError correctly`() {
        DegradationManager.handleDegradation(BlindPathError.ModelLoadError("test error"))
        
        assertEquals(
            DegradationManager.DegradationLevel.REDUCED,
            DegradationManager.getDegradationLevel(DegradationManager.Feature.AI_DETECTION)
        )
    }
    
    @Test
    fun `handleDegradation should handle CameraPermissionDenied correctly`() {
        DegradationManager.handleDegradation(BlindPathError.CameraPermissionDenied)
        
        assertEquals(
            DegradationManager.DegradationLevel.DISABLED,
            DegradationManager.getDegradationLevel(DegradationManager.Feature.CAMERA)
        )
    }
    
    @Test
    fun `handleDegradation should handle NetworkUnavailable correctly`() {
        DegradationManager.handleDegradation(BlindPathError.NetworkUnavailable)
        
        assertEquals(
            DegradationManager.DegradationLevel.OFFLINE,
            DegradationManager.getDegradationLevel(DegradationManager.Feature.NETWORK)
        )
    }
    
    @Test
    fun `getDegradationSummary should show all normal when no degradation`() {
        val summary = DegradationManager.getDegradationSummary()
        
        assertEquals("所有功能正常", summary)
    }
    
    @Test
    fun `getDegradationSummary should show degraded features`() {
        DegradationManager.setDegradationLevel(
            DegradationManager.Feature.AI_DETECTION,
            DegradationManager.DegradationLevel.REDUCED
        )
        DegradationManager.setDegradationLevel(
            DegradationManager.Feature.NETWORK,
            DegradationManager.DegradationLevel.OFFLINE
        )
        
        val summary = DegradationManager.getDegradationSummary()
        
        assertTrue(summary.contains("AI检测"))
        assertTrue(summary.contains("网络"))
        assertTrue(summary.contains("受限"))
        assertTrue(summary.contains("离线"))
    }
    
    @Test
    fun `listener should be notified on degradation change`() {
        var notified = false
        val listener = object : DegradationManager.DegradationListener {
            override fun onDegradationChanged(
                feature: DegradationManager.Feature,
                previousLevel: DegradationManager.DegradationLevel,
                newLevel: DegradationManager.DegradationLevel,
                reason: String?
            ) {
                notified = true
                assertEquals(DegradationManager.Feature.GPS_NAVIGATION, feature)
                assertEquals(DegradationManager.DegradationLevel.NORMAL, previousLevel)
                assertEquals(DegradationManager.DegradationLevel.DISABLED, newLevel)
            }
        }
        
        DegradationManager.addListener(listener)
        DegradationManager.setDegradationLevel(
            DegradationManager.Feature.GPS_NAVIGATION,
            DegradationManager.DegradationLevel.DISABLED
        )
        
        assertTrue(notified)
        DegradationManager.removeListener(listener)
    }
}
