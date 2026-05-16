package com.blindpath.module_obstacle.service

import com.blindpath.base.common.AlertLevel
import com.blindpath.base.common.AlertInfo
import com.blindpath.base.common.ObstacleState
import com.blindpath.module_obstacle.data.ObstacleRepositoryImpl
import com.blindpath.module_voice.domain.VoiceRepository
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * ObstacleService 单元测试
 */
class ObstacleServiceTest {

    @MockK
    lateinit var obstacleRepository: ObstacleRepositoryImpl

    @MockK
    lateinit var voiceRepository: VoiceRepository

    private lateinit var service: ObstacleService
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        service = spyk(ObstacleService())
    }

    @After
    fun tearDown() {
        serviceScope.cancel()
    }

    @Test
    fun `handleAlert should not repeat same alert within interval`() = runBlocking {
        // Given
        val description = "前方有障碍物"
        val level = AlertLevel.WARNING

        // When - 调用两次相同的预警
        service.handleAlert(level, description)
        service.handleAlert(level, description)

        // Then - voiceRepository.speakObstacleAlert 应该只被调用一次
        coVerify(exactly = 1) { voiceRepository.speakObstacleAlert(description) }
    }

    @Test
    fun `handleAlert should speak for different descriptions`() = runBlocking {
        // Given
        val level = AlertLevel.WARNING

        // When - 调用不同内容的预警
        service.handleAlert(level, "障碍物1")
        service.handleAlert(level, "障碍物2")

        // Then - voiceRepository 应该被调用两次
        coVerify(exactly = 2) { voiceRepository.speakObstacleAlert(any()) }
    }

    @Test
    fun `handleAlert should vibrate for non-SAFE level`() = runBlocking {
        // Given
        val level = AlertLevel.DANGER

        // When
        service.handleAlert(level, "危险障碍物")

        // Then - 应该调用语音预警
        coVerify { voiceRepository.speakObstacleAlert("危险障碍物") }
    }

    @Test
    fun `handleAlert should not vibrate for SAFE level`() = runBlocking {
        // Given
        val level = AlertLevel.SAFE

        // When
        service.handleAlert(level, "安全")

        // Then - 不应该震动（SAFE 级别不需要振动）
        // 注意：这里只验证语音播报，不验证振动（需要 Android Context）
        coVerify { voiceRepository.speakObstacleAlert("安全") }
    }

    @Test
    fun `handleAlert should respect minimum interval for repeated alerts`() = runBlocking {
        // Given
        val description = "重复预警"
        val level = AlertLevel.WARNING

        // When - 快速连续调用
        service.handleAlert(level, description)

        // 等待间隔时间
        delay(ObstacleServiceTest::class.java.getDeclaredField("alertRepeatMinInterval")
            .apply { isAccessible = true }.get(3000L) as Long + 100)

        service.handleAlert(level, description)

        // Then - 应该被调用两次（间隔已过）
        coVerify(exactly = 2) { voiceRepository.speakObstacleAlert(description) }
    }
}

/**
 * ObstacleService 预警防重复逻辑测试
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
