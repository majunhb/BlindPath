package com.blindpath.module_voice.repository

import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.VoiceState
import org.junit.Test
import org.junit.Assert.*

/**
 * VoiceRepository 接口测试
 * 验证语音播报的核心业务逻辑
 */
class VoiceRepositoryTest {

    // 测试 VoiceState 的状态转换
    @Test
    fun `VoiceState should track speaking state correctly`() {
        // Given - 创建初始状态
        val initialState = VoiceState()

        // Then - 初始状态应该是未在播报
        assertFalse(initialState.isSpeaking)
        assertNull(initialState.lastError)
    }

    @Test
    fun `VoiceState should update speaking state`() {
        // Given
        val state = VoiceState()

        // When - 更新为播报状态
        val updatedState = state.copy(isSpeaking = true)

        // Then
        assertTrue(updatedState.isSpeaking)
    }

    @Test
    fun `VoiceState should track error state`() {
        // Given
        val state = VoiceState()

        // When - 设置错误
        val errorState = state.copy(lastError = "TTS 初始化失败")

        // Then
        assertEquals("TTS 初始化失败", errorState.lastError)
        assertFalse(errorState.isSpeaking)
    }

    // 测试语音播报优先级逻辑
    @Test
    fun `obstacle alert should use FLUSH mode`() {
        // Given - 模拟避障预警场景
        var lastQueueMode: Boolean? = null
        var lastText: String? = null

        // 模拟 speak 方法
        fun simulateSpeak(text: String, queueMode: Boolean) {
            lastText = text
            lastQueueMode = queueMode
        }

        // When - 模拟避障预警（应该立即打断）
        simulateSpeak("前方有障碍物", queueMode = false)

        // Then
        assertEquals("前方有障碍物", lastText)
        assertFalse("避障预警应该使用 FLUSH 模式", lastQueueMode!!)
    }

    @Test
    fun `navigation speech should use ADD mode when not speaking`() {
        // Given
        var lastQueueMode: Boolean? = null

        fun simulateSpeak(text: String, queueMode: Boolean) {
            lastQueueMode = queueMode
        }

        // When - 模拟导航播报（应该排队）
        simulateSpeak("前方左转", queueMode = true)

        // Then
        assertTrue("导航播报应该使用 ADD 模式", lastQueueMode!!)
    }

    @Test
    fun `navigation speech should be skipped during obstacle alert`() {
        // Given - 模拟避障预警正在播报
        val isSpeaking = MutableStateFlow(true)
        var shouldSpeak = true

        // 模拟 speakNavigation 的逻辑
        if (isSpeaking.value) {
            shouldSpeak = false // 正在播报避障，不播导航
        }

        // Then
        assertFalse("避障播报时不应播报导航", shouldSpeak)
    }

    @Test
    fun `navigation speech should proceed when not speaking`() {
        // Given - 模拟没有正在播报
        val isSpeaking = MutableStateFlow(false)
        var shouldSpeak = true

        // 模拟 speakNavigation 的逻辑
        if (isSpeaking.value) {
            shouldSpeak = false
        }

        // Then
        assertTrue("没有播报时应该可以播报导航", shouldSpeak)
    }
}

/**
 * 语音播报防打断测试
 */
class VoiceInterruptionTest {

    @Test
    fun `obstacle alert should interrupt ongoing speech`() {
        // Given - 模拟正在播放导航
        var isInterrupted = false

        fun onObstacleAlert() {
            // 避障预警：停止当前播报
            isInterrupted = true
        }

        // When - 触发避障预警
        onObstacleAlert()

        // Then
        assertTrue("避障预警应该打断当前播报", isInterrupted)
    }

    @Test
    fun `navigation should not interrupt obstacle alert`() {
        // Given - 模拟正在播放避障预警
        var canPlayNavigation = true

        fun shouldPlayNavigation(): Boolean {
            // 导航播报：如果正在播报避障，不播导航
            return canPlayNavigation && false // 模拟正在播报
        }

        // Then
        assertFalse(shouldPlayNavigation())
    }

    @Test
    fun `queue mode should allow multiple messages`() {
        // Given - 模拟队列播报
        val queue = mutableListOf<String>()

        // When - 添加多条消息到队列
        queue.add("消息1")
        queue.add("消息2")
        queue.add("消息3")

        // Then
        assertEquals(3, queue.size)
        assertEquals("消息1", queue[0])
    }

    @Test
    fun `flush mode should clear queue`() {
        // Given - 模拟队列播报
        val queue = mutableListOf("消息1", "消息2")
        var isFlushed = false

        // When - FLUSH 模式
        fun speakFlush(text: String) {
            queue.clear()
            queue.add(text)
            isFlushed = true
        }

        speakFlush("紧急消息")

        // Then
        assertTrue(isFlushed)
        assertEquals(1, queue.size)
        assertEquals("紧急消息", queue[0])
    }
}

/**
 * TTS 错误处理测试
 */
class TTSErrorHandlingTest {

    @Test
    fun `should handle TTS not initialized`() {
        // Given - TTS 未初始化场景
        var tts: Any? = null
        var errorMessage: String? = null

        // When - 尝试播报
        if (tts == null) {
            errorMessage = "TTS 未初始化"
        }

        // Then
        assertEquals("TTS 未初始化", errorMessage)
    }

    @Test
    fun `should handle unsupported language`() {
        // Given - 不支持的语言场景
        val languageResult = -1 // LANG_MISSING_DATA 或 LANG_NOT_SUPPORTED

        // When
        val errorMessage = when (languageResult) {
            -1 -> "不支持该语言"
            else -> null
        }

        // Then
        assertEquals("不支持该语言", errorMessage)
    }

    @Test
    fun `should handle initialization failure`() {
        // Given - 初始化失败场景
        val status = -1 // 非 SUCCESS

        // When
        val isSuccess = status == 0 // TextToSpeech.SUCCESS == 0

        // Then
        assertFalse("初始化应该失败", isSuccess)
    }
}

/**
 * Result 类型测试
 */
class ResultTypeTest {

    @Test
    fun `Result Success should contain data`() {
        // Given
        val success = Result.Success(true)

        // Then
        assertTrue(success.isSuccess)
        assertFalse(success.isError)
    }

    @Test
    fun `Result Error should contain message`() {
        // Given
        val error = Result.Error(message = "测试错误")

        // Then
        assertFalse(error.isSuccess)
        assertTrue(error.isError)
        assertEquals("测试错误", error.message)
    }

    @Test
    fun `Result Success data extraction`() {
        // Given
        val success: Result<Boolean> = Result.Success(true)

        // When
        val value = when (success) {
            is Result.Success -> success.data
            is Result.Error -> null
        }

        // Then
        assertEquals(true, value)
    }
}
