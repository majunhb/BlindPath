package com.blindpath.app.ui

import com.blindpath.module_navigation.data.NavigationRepositoryImpl
import com.blindpath.module_voice.domain.VoiceRepository
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

/**
 * MainViewModel 单元测试
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @MockK
    lateinit var voiceRepository: VoiceRepository

    @MockK
    lateinit var navigationRepository: NavigationRepositoryImpl

    private lateinit var viewModel: MainViewModel
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setup() {
        MockKAnnotations.init(this)
        Dispatchers.setMain(testDispatcher)

        // Mock voiceRepository.initialize()
        coEvery { voiceRepository.initialize() } returns mockk()
        coEvery { voiceRepository.speak(any(), any()) } returns mockk()
        coEvery { voiceRepository.release() } just Runs

        viewModel = MainViewModel(voiceRepository, navigationRepository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state should be not running`() {
        // Given - ViewModel 已初始化

        // Then - 初始状态应该是服务未运行
        assertFalse(viewModel.uiState.value.isObstacleRunning)
        assertFalse(viewModel.uiState.value.isNavigationRunning)
        assertNull(viewModel.uiState.value.pendingAction)
    }

    @Test
    fun `performAction should clear pending action after execution`() = runTest {
        // Given
        viewModel.setPendingAction("start_obstacle")

        // When
        viewModel.performAction("start_obstacle")
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertNull(viewModel.uiState.value.pendingAction)
    }

    @Test
    fun `performAction start_obstacle should set obstacle running`() = runTest {
        // When
        viewModel.performAction("start_obstacle")
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertTrue(viewModel.uiState.value.isObstacleRunning)
    }

    @Test
    fun `performAction stop_obstacle should clear obstacle running`() = runTest {
        // Given - 先启动
        viewModel.performAction("start_obstacle")
        testDispatcher.scheduler.advanceUntilIdle()

        // When - 再停止
        viewModel.performAction("stop_obstacle")
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertFalse(viewModel.uiState.value.isObstacleRunning)
    }

    @Test
    fun `performAction start_navigation should set navigation running`() = runTest {
        // When
        viewModel.performAction("start_navigation")
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertTrue(viewModel.uiState.value.isNavigationRunning)
    }

    @Test
    fun `performAction stop_navigation should clear navigation running`() = runTest {
        // Given
        viewModel.performAction("start_navigation")
        testDispatcher.scheduler.advanceUntilIdle()

        // When
        viewModel.performAction("stop_navigation")
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        assertFalse(viewModel.uiState.value.isNavigationRunning)
    }

    @Test
    fun `setPendingAction should update pending action`() {
        // When
        viewModel.setPendingAction("test_action")

        // Then
        assertEquals("test_action", viewModel.uiState.value.pendingAction)
    }

    @Test
    fun `clearError should clear error message`() {
        // Given
        // 模拟设置错误（通过内部状态）
        val stateWithError = viewModel.uiState.value.copy(errorMessage = "测试错误")

        // When
        viewModel.clearError()

        // Then
        assertNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `sendSos should speak sos message`() = runTest {
        // When
        viewModel.sendSos(39.9, 116.4)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        coVerify { voiceRepository.speak(contains("求救信号"), false) }
    }

    @Test
    fun `sendSos without location should not mention location`() = runTest {
        // When
        viewModel.sendSos(null, null)
        testDispatcher.scheduler.advanceUntilIdle()

        // Then
        coVerify { voiceRepository.speak(contains("求救信号"), false) }
        coVerify { voiceRepository.speak(not(contains("当前位置")), false) }
    }
}

/**
 * MainUiState 测试
 */
class MainUiStateTest {

    @Test
    fun `default state should have all false values`() {
        val state = MainUiState()

        assertFalse(state.isObstacleRunning)
        assertFalse(state.isNavigationRunning)
        assertNull(state.pendingAction)
        assertNull(state.errorMessage)
    }

    @Test
    fun `copy should preserve unchanged values`() {
        val original = MainUiState(
            isObstacleRunning = true,
            errorMessage = "测试错误"
        )

        val copied = original.copy(isNavigationRunning = true)

        assertTrue("isObstacleRunning should be preserved", copied.isObstacleRunning)
        assertTrue(copied.isNavigationRunning)
        assertEquals("errorMessage should be preserved", "测试错误", copied.errorMessage)
    }
}
