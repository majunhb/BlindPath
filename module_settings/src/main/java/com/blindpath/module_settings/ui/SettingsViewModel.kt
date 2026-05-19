package com.blindpath.module_settings.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindpath.module_settings.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val isLoading: Boolean = true,
    val testSpeechText: String = ""
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SettingsUiState())
    val uiState: StateFlow<SettingsUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepository.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings, isLoading = false) }
            }
        }
    }

    fun updateEmergencyContact(name: String, phone: String) {
        viewModelScope.launch {
            settingsRepository.updateEmergencyContact(name, phone)
        }
    }

    fun updateSpeechRate(rate: Float) {
        viewModelScope.launch {
            settingsRepository.updateSpeechRate(rate)
        }
    }

    fun updateSpeechPitch(pitch: Float) {
        viewModelScope.launch {
            settingsRepository.updateSpeechPitch(pitch)
        }
    }

    fun updateVoiceEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateVoiceEnabled(enabled)
        }
    }

    fun updateVibrationEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateVibrationEnabled(enabled)
        }
    }

    fun updateVibrationIntensity(intensity: VibrationIntensity) {
        viewModelScope.launch {
            settingsRepository.updateVibrationIntensity(intensity)
        }
    }

    fun updateDetectionSensitivity(sensitivity: DetectionSensitivity) {
        viewModelScope.launch {
            settingsRepository.updateDetectionSensitivity(sensitivity)
        }
    }

    fun updateDetectionDistance(distance: Int) {
        viewModelScope.launch {
            settingsRepository.updateDetectionDistance(distance)
        }
    }

    fun updateAutoLocationShare(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.updateAutoLocationShare(enabled)
        }
    }

    fun setTestSpeechText(text: String) {
        _uiState.update { it.copy(testSpeechText = text) }
    }
}
