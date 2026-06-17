package com.blindpath.base.error

import org.junit.Assert.*
import org.junit.Test

/**
 * 错误处理相关单元测试
 */
class ErrorHandlingTest {
    
    @Test
    fun `BlindPathError ModelNotFoundError should have correct message`() {
        val error = BlindPathError.ModelNotFoundError("yolov8n.tflite")
        
        assertEquals("AI模型文件 yolov8n.tflite 不存在", error.message)
    }
    
    @Test
    fun `BlindPathError GpsSignalWeak should include accuracy`() {
        val error = BlindPathError.GpsSignalWeak(15.5f)
        
        assertTrue(error.message.contains("15"))
        assertTrue(error.message.contains("米"))
    }
    
    @Test
    fun `ErrorMessageResolver should identify recoverable errors`() {
        // 可恢复的错误
        assertTrue(ErrorMessageResolver.isRecoverable(BlindPathError.ModelLoadError("test")))
        assertTrue(ErrorMessageResolver.isRecoverable(BlindPathError.NetworkUnavailable))
        assertTrue(ErrorMessageResolver.isRecoverable(BlindPathError.CameraBusy))
        
        // 不可恢复的错误
        assertFalse(ErrorMessageResolver.isRecoverable(BlindPathError.CameraPermissionDenied))
        assertFalse(ErrorMessageResolver.isRecoverable(BlindPathError.UnknownError()))
    }
    
    @Test
    fun `ErrorMessageResolver should identify immediate attention errors`() {
        // 需要立即处理
        assertTrue(ErrorMessageResolver.requiresImmediateAttention(BlindPathError.CameraPermissionDenied))
        assertTrue(ErrorMessageResolver.requiresImmediateAttention(BlindPathError.GpsDisabled))
        
        // 不需要立即处理
        assertFalse(ErrorMessageResolver.requiresImmediateAttention(BlindPathError.NetworkUnavailable))
        assertFalse(ErrorMessageResolver.requiresImmediateAttention(BlindPathError.ModelNotFoundError("test")))
    }
    
    @Test
    fun `ErrorMessageResolver should return action suggestion for permission errors`() {
        val suggestion = ErrorMessageResolver.getActionSuggestion(BlindPathError.CameraPermissionDenied)
        
        assertNotNull(suggestion)
        assertEquals("去设置", suggestion?.buttonText)
        assertEquals(ActionType.OPEN_APP_SETTINGS, suggestion?.action)
    }
    
    @Test
    fun `ErrorMessageResolver should return null action suggestion for non-actionable errors`() {
        val suggestion = ErrorMessageResolver.getActionSuggestion(BlindPathError.NetworkUnavailable)
        
        assertNull(suggestion)
    }
}
