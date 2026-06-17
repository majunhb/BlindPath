package com.blindpath.module_voice.domain.model

import org.junit.Assert.*
import org.junit.Test

/**
 * VoicePriority 优先级逻辑单元测试
 * 
 * 验证 4 级优先级体系的核心规则：
 * - EMERGENCY 可打断一切
 * - IMPORTANT 等待当前句子完成
 * - NORMAL/BACKGROUND 仅排队
 */
class VoicePriorityTest {

    @Test
    fun `EMERGENCY should interrupt current speech`() {
        assertTrue(VoicePriority.EMERGENCY.shouldInterrupt())
    }

    @Test
    fun `IMPORTANT should NOT interrupt but wait for sentence`() {
        assertFalse(VoicePriority.IMPORTANT.shouldInterrupt())
        assertTrue(VoicePriority.IMPORTANT.shouldWaitForSentence())
    }

    @Test
    fun `NORMAL should neither interrupt nor wait for sentence`() {
        assertFalse(VoicePriority.NORMAL.shouldInterrupt())
        assertFalse(VoicePriority.NORMAL.shouldWaitForSentence())
    }

    @Test
    fun `BACKGROUND should neither interrupt nor wait for sentence`() {
        assertFalse(VoicePriority.BACKGROUND.shouldInterrupt())
        assertFalse(VoicePriority.BACKGROUND.shouldWaitForSentence())
    }

    @Test
    fun `cooldown should increase with lower priority`() {
        val cooldowns = listOf(
            VoicePriority.EMERGENCY.getCooldownMs(),
            VoicePriority.IMPORTANT.getCooldownMs(),
            VoicePriority.NORMAL.getCooldownMs(),
            VoicePriority.BACKGROUND.getCooldownMs()
        )
        // 冷却时间应随优先级降低而增加
        for (i in 0 until cooldowns.size - 1) {
            assertTrue(
                "Cooldown for ${VoicePriority.entries[i]} (${cooldowns[i]}ms) should be <= ${VoicePriority.entries[i+1]} (${cooldowns[i+1]}ms)",
                cooldowns[i] <= cooldowns[i + 1]
            )
        }
    }

    @Test
    fun `priority level order should match definition`() {
        assertEquals(0, VoicePriority.EMERGENCY.level)
        assertEquals(1, VoicePriority.IMPORTANT.level)
        assertEquals(2, VoicePriority.NORMAL.level)
        assertEquals(3, VoicePriority.BACKGROUND.level)
    }
}

/**
 * VoiceType 与 VoicePriority 映射测试
 */
class VoiceTypeTest {

    @Test
    fun `danger obstacle type should be EMERGENCY priority`() {
        assertEquals(VoicePriority.EMERGENCY, VoiceType.OBSTACLE_DANGER.priority)
        assertEquals(VoicePriority.EMERGENCY, VoiceType.FALL_DETECTED.priority)
        assertEquals(VoicePriority.EMERGENCY, VoiceType.SOS_TRIGGERED.priority)
    }

    @Test
    fun `navigation types should be IMPORTANT priority`() {
        assertEquals(VoicePriority.IMPORTANT, VoiceType.NAVIGATION_TURN.priority)
        assertEquals(VoicePriority.IMPORTANT, VoiceType.NAVIGATION_ARRIVE.priority)
        assertEquals(VoicePriority.IMPORTANT, VoiceType.TRAFFIC_LIGHT.priority)
    }

    @Test
    fun `normal obstacle types should be NORMAL priority`() {
        assertEquals(VoicePriority.NORMAL, VoiceType.OBSTACLE_NORMAL.priority)
        assertEquals(VoicePriority.NORMAL, VoiceType.OBSTACLE_LOW.priority)
    }

    @Test
    fun `system status types should be BACKGROUND priority`() {
        assertEquals(VoicePriority.BACKGROUND, VoiceType.SYSTEM_STATUS.priority)
        assertEquals(VoicePriority.BACKGROUND, VoiceType.BATTERY_LOW.priority)
        assertEquals(VoicePriority.BACKGROUND, VoiceType.MODE_CHANGE.priority)
    }
}

/**
 * VoiceRequest 构造默认值测试
 */
class VoiceRequestTest {

    @Test
    fun `default priority should match type priority`() {
        val request = VoiceRequest(text = "前方有人", type = VoiceType.OBSTACLE_DANGER)
        assertEquals(VoicePriority.EMERGENCY, request.priority)
    }

    @Test
    fun `explicit priority should override type priority`() {
        val request = VoiceRequest(
            text = "提示",
            type = VoiceType.SYSTEM_STATUS,
            priority = VoicePriority.EMERGENCY
        )
        assertEquals(VoicePriority.EMERGENCY, request.priority)
    }

    @Test
    fun `interruptCurrent should default to priority shouldInterrupt`() {
        val emergency = VoiceRequest(text = "危险", type = VoiceType.OBSTACLE_DANGER)
        assertTrue(emergency.interruptCurrent)

        val normal = VoiceRequest(text = "正常", type = VoiceType.OBSTACLE_NORMAL)
        assertFalse(normal.interruptCurrent)
    }

    @Test
    fun `deduplicationKey should default to null`() {
        val request = VoiceRequest(text = "test", type = VoiceType.SYSTEM_STATUS)
        assertNull(request.deduplicationKey)
    }

    @Test
    fun `timestamp should be set automatically`() {
        val before = System.currentTimeMillis()
        val request = VoiceRequest(text = "test", type = VoiceType.SYSTEM_STATUS)
        val after = System.currentTimeMillis()
        assertTrue(request.timestamp in before..after)
    }

    @Test
    fun `equals should work correctly for identical requests`() {
        val r1 = VoiceRequest(text = "test", type = VoiceType.SYSTEM_STATUS)
        val r2 = r1.copy()
        assertEquals(r1, r2)
    }
}

/**
 * VoiceState 状态转换测试
 */
class VoiceStateTest {

    @Test
    fun `default state should have all false values`() {
        val state = VoiceState()
        assertFalse(state.isAvailable)
        assertFalse(state.isSpeaking)
        assertFalse(state.isListening)
        assertFalse(state.isWakeUp)
        assertNull(state.currentPriority)
        assertEquals(0, state.queueSize)
        assertNull(state.lastError)
    }

    @Test
    fun `copy should preserve unchanged values`() {
        val original = VoiceState(isSpeaking = true, queueSize = 3, lastError = "test error")
        val copied = original.copy(isListening = true)

        assertTrue(copied.isSpeaking)
        assertTrue(copied.isListening)
        assertEquals(3, copied.queueSize)
        assertEquals("test error", copied.lastError)
    }

    @Test
    fun `error should not clear speaking state`() {
        val original = VoiceState(isSpeaking = true)
        val withError = original.copy(lastError = "TTS error")
        assertTrue("Speaking state should survive error", withError.isSpeaking)
    }
}

/**
 * VoiceStatistics 累计测试
 */
class VoiceStatisticsTest {

    @Test
    fun `default statistics should be all zeros`() {
        val stats = VoiceStatistics()
        assertEquals(0, stats.totalAnnouncements)
        assertEquals(0, stats.emergencyCount)
        assertEquals(0, stats.deduplicatedCount)
        assertEquals(0, stats.interruptedCount)
    }

    @Test
    fun `copy should allow incremental updates`() {
        var stats = VoiceStatistics()
        stats = stats.copy(totalAnnouncements = stats.totalAnnouncements + 1)
        stats = stats.copy(emergencyCount = stats.emergencyCount + 1)

        assertEquals(1, stats.totalAnnouncements)
        assertEquals(1, stats.emergencyCount)
        assertEquals(0, stats.normalCount)
    }
}