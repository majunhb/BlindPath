package com.blindpath.module_settings.ui

import com.blindpath.module_settings.data.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: SettingsRepository
    private lateinit var settingsFlow: MutableStateFlow<AppSettings>
    private lateinit var viewModel: SettingsViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        settingsFlow = MutableStateFlow(AppSettings())
        repository = mockk(relaxed = true) {
            every { settings } returns settingsFlow
        }
        viewModel = SettingsViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state should show loading`() {
        val state = viewModel.uiState.value
        assertTrue("Initial state should be loading", state.isLoading)
        assertEquals(AppSettings(), state.settings)
    }

    @Test
    fun `after settings flow emits, loading should be false`() = runTest {
        assertEquals(AppSettings(), viewModel.uiState.value.settings)
        assertTrue(viewModel.uiState.value.isLoading)

        testDispatcher.scheduler.advanceUntilIdle()

        assertFalse("Loading should be false after collecting flow", viewModel.uiState.value.isLoading)
    }

    @Test
    fun `updateEmergencyContact should delegate to repository`() = runTest {
        viewModel.updateEmergencyContact("张三", "13800138000")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateEmergencyContact("张三", "13800138000") }
    }

    @Test
    fun `updateSpeechRate should delegate to repository`() = runTest {
        viewModel.updateSpeechRate(1.5f)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateSpeechRate(1.5f) }
    }

    @Test
    fun `updateSpeechPitch should delegate to repository`() = runTest {
        viewModel.updateSpeechPitch(0.8f)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateSpeechPitch(0.8f) }
    }

    @Test
    fun `updateVoiceEnabled should delegate to repository`() = runTest {
        viewModel.updateVoiceEnabled(false)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateVoiceEnabled(false) }
    }

    @Test
    fun `updateVibrationEnabled should delegate to repository`() = runTest {
        viewModel.updateVibrationEnabled(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateVibrationEnabled(true) }
    }

    @Test
    fun `updateVibrationIntensity should delegate to repository`() = runTest {
        viewModel.updateVibrationIntensity(VibrationIntensity.HIGH)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateVibrationIntensity(VibrationIntensity.HIGH) }
    }

    @Test
    fun `updateDetectionSensitivity should delegate to repository`() = runTest {
        viewModel.updateDetectionSensitivity(DetectionSensitivity.LOW)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateDetectionSensitivity(DetectionSensitivity.LOW) }
    }

    @Test
    fun `updateDetectionDistance should delegate to repository`() = runTest {
        viewModel.updateDetectionDistance(3)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateDetectionDistance(3) }
    }

    @Test
    fun `updateAutoLocationShare should delegate to repository`() = runTest {
        viewModel.updateAutoLocationShare(true)
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateAutoLocationShare(true) }
    }

    @Test
    fun `setTestSpeechText should update ui state immediately`() {
        viewModel.setTestSpeechText("测试语音")

        assertEquals("测试语音", viewModel.uiState.value.testSpeechText)
    }

    @Test
    fun `settings flow update should reflect in ui State`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val updated = AppSettings(speechRate = 1.5f, voiceEnabled = false)
        settingsFlow.value = updated
        testDispatcher.scheduler.advanceUntilIdle()

        assertEquals(1.5f, viewModel.uiState.value.settings.speechRate)
        assertFalse(viewModel.uiState.value.settings.voiceEnabled)
    }
}

class AppSettingsTest {

    @Test
    fun `default AppSettings should have sensible values`() {
        val settings = AppSettings()
        assertEquals("", settings.emergencyContact)
        assertEquals("", settings.emergencyName)
        assertEquals(1.0f, settings.speechRate)
        assertEquals(1.0f, settings.speechPitch)
        assertTrue(settings.voiceEnabled)
        assertTrue(settings.vibrationEnabled)
        assertEquals(VibrationIntensity.MEDIUM, settings.vibrationIntensity)
        assertEquals(DetectionSensitivity.MEDIUM, settings.detectionSensitivity)
        assertEquals(5, settings.detectionDistance)
        assertFalse(settings.autoLocationShare)
    }
}

class VibrationIntensityTest {

    @Test
    fun `values should match order`() {
        val intensities = VibrationIntensity.entries
        assertEquals(1, intensities[0].value)
        assertEquals(2, intensities[1].value)
        assertEquals(3, intensities[2].value)
    }

    @Test
    fun `display names should be in Chinese`() {
        assertEquals("轻柔", VibrationIntensity.LOW.displayName)
        assertEquals("中等", VibrationIntensity.MEDIUM.displayName)
        assertEquals("强烈", VibrationIntensity.HIGH.displayName)
    }
}

class DetectionSensitivityTest {

    @Test
    fun `values should be meaningful`() {
        assertTrue(DetectionSensitivity.LOW.value < DetectionSensitivity.MEDIUM.value)
        assertTrue(DetectionSensitivity.MEDIUM.value < DetectionSensitivity.HIGH.value)
    }

    @Test
    fun `display names should be in Chinese`() {
        assertEquals("低（远距离检测）", DetectionSensitivity.LOW.displayName)
        assertEquals("中（标准）", DetectionSensitivity.MEDIUM.displayName)
        assertEquals("高（近距离敏感）", DetectionSensitivity.HIGH.displayName)
    }
}