package com.blindpath.module_navigation.service

import io.mockk.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * NavigationService 导航播报防重复逻辑测试
 */
class NavigationServiceTest {

    private var lastInstruction: String? = null
    private var lastKnownDistance = Int.MAX_VALUE

    /**
     * 播报导航指令（防重复逻辑）
     * 规则：
     * 1. 指令变化时播报
     * 2. 距离变化 >= 5 米时播报
     */
    fun shouldSpeak(instruction: String, remainingDistance: Int): Boolean {
        val distanceChanged = abs(remainingDistance - lastKnownDistance) >= 5

        if (lastInstruction != instruction || distanceChanged) {
            lastInstruction = instruction
            lastKnownDistance = remainingDistance
            return true
        }
        return false
    }

    @Test
    fun `should speak when instruction changes`() {
        // Given
        val instruction1 = "前方100米左转"
        val instruction2 = "前方50米右转"

        // When
        val result1 = shouldSpeak(instruction1, 100)
        val result2 = shouldSpeak(instruction2, 100) // 距离不变，但指令变了

        // Then
        assert(result1) { "第一次应该播报" }
        assert(result2) { "指令变化应该播报" }
        assert(lastKnownDistance == 100)
    }

    @Test
    fun `should speak when distance changes significantly`() {
        // Given
        val instruction = "直行"

        // When
        shouldSpeak(instruction, 100)
        val result = shouldSpeak(instruction, 95) // 变化5米

        // Then
        assert(result) { "距离变化5米应该播报" }
        assert(lastKnownDistance == 95)
    }

    @Test
    fun `should not speak for minor distance changes`() {
        // Given
        val instruction = "直行"

        // When
        shouldSpeak(instruction, 100)
        val result = shouldSpeak(instruction, 97) // 变化3米

        // Then
        assert(!result) { "距离变化小于5米不应该播报" }
        assert(lastKnownDistance == 100) { "距离应该保持不变" }
    }

    @Test
    fun `should not speak for same instruction and distance`() {
        // Given
        val instruction = "保持直行"
        val distance = 50

        // When - 多次调用相同的指令和距离
        shouldSpeak(instruction, distance)
        val result2 = shouldSpeak(instruction, distance)
        val result3 = shouldSpeak(instruction, distance)

        // Then
        assert(!result2)
        assert(!result3)
    }

    @Test
    fun `should speak when distance crosses threshold`() = runBlocking {
        // Given
        val instruction = "接近目标"

        // When - 距离从 95 变到 100（变化5米）
        shouldSpeak(instruction, 95)
        val result = shouldSpeak(instruction, 100)

        // Then
        assert(result) { "距离变化刚好5米应该播报" }
    }

    @Test
    fun `should handle decreasing distance`() {
        // Given - 模拟导航接近目的地的场景
        val instruction = "继续直行"

        // When - 距离递减
        shouldSpeak(instruction, 100)
        shouldSpeak(instruction, 90) // 变化10米
        shouldSpeak(instruction, 85) // 变化5米
        val result = shouldSpeak(instruction, 83) // 变化2米

        // Then
        assert(!result) { "小于5米的变化不应该播报" }
    }

    @Test
    fun `should handle increasing distance`() {
        // Given - 模拟走错路需要掉头的场景
        val instruction = "掉头"

        // When - 距离递增
        shouldSpeak(instruction, 50)
        shouldSpeak(instruction, 60) // 变化10米
        shouldSpeak(instruction, 68) // 变化8米
        val result = shouldSpeak(instruction, 71) // 变化3米

        // Then
        assert(!result) { "小于5米的变化不应该播报" }
    }

    @Test
    fun `should speak immediately when distance becomes zero`() {
        // Given - 到达目的地
        val instruction = "已到达目的地"

        // When
        shouldSpeak(instruction, 10)
        val result = shouldSpeak(instruction, 0)

        // Then
        assert(result) { "到达目的地应该播报" }
    }
}

/**
 * 导航指令格式化测试
 */
class NavigationInstructionFormatTest {

    @Test
    fun `should format distance in meters for small distances`() {
        val distance = 50
        val instruction = "前方${distance}米"
        assert(instruction == "前方50米")
    }

    @Test
    fun `should format distance in kilometers for large distances`() {
        val distanceInMeters = 1500
        val distanceInKm = distanceInMeters / 1000.0
        val instruction = "前方${String.format("%.1f", distanceInKm)}公里"
        assert(instruction == "前方1.5公里")
    }

    @Test
    fun `should format turning instructions correctly`() {
        val direction = "左"
        val distance = 100
        val instruction = "前方${distance}米${direction}转"
        assert(instruction == "前方100米左转")
    }
}
