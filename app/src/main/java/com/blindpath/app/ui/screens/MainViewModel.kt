package com.blindpath.app.ui.screens

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class MainViewModel @Inject constructor(
    private val obstacleRepository: ObstacleRepository,
    private val navigationRepository: NavigationRepository,
    private val voiceRepository: VoiceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        // 监听避障状态
        viewModelScope.launch {
            obstacleRepository.obstacleState.collect { state ->
                _uiState.update {
                    it.copy(
                        isObstacleRunning = state.isRunning,
                        currentAlert = state.currentAlert
                    )
                }
            }
        }

        // 监听导航状态
        viewModelScope.launch {
            navigationRepository.navigationState.collect { state ->
                _uiState.update {
                    it.copy(
                        isNavigationRunning = state.isRunning,
                        navigationInfo = state.currentInfo,
                        isLocationAvailable = state.isLocationAvailable
                    )
                }
            }
        }

        // 监听语音状态
        viewModelScope.launch {
            voiceRepository.voiceState.collect { state ->
                _uiState.update {
                    it.copy(isVoiceAvailable = state.isAvailable)
                }
            }
        }
    }

    fun startObstacleDetection() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(errorMessage = null) }
                obstacleRepository.startDetection()
                voiceRepository.speak("避障功能已开启")
            } catch (e: Exception) {
                Timber.e(e, "启动避障失败")
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun stopObstacleDetection() {
        viewModelScope.launch {
            try {
                obstacleRepository.stopDetection()
                voiceRepository.speak("避障功能已关闭")
            } catch (e: Exception) {
                Timber.e(e, "停止避障失败")
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun startNavigation() {
        viewModelScope.launch {
            try {
                _uiState.update { it.copy(errorMessage = null) }
                navigationRepository.startNavigation()
                voiceRepository.speak("导航功能已开启，请说出去哪里")
            } catch (e: Exception) {
                Timber.e(e, "启动导航失败")
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun stopNavigation() {
        viewModelScope.launch {
            try {
                navigationRepository.stopNavigation()
                voiceRepository.speak("导航功能已关闭")
            } catch (e: Exception) {
                Timber.e(e, "停止导航失败")
                _uiState.update { it.copy(errorMessage = e.message) }
            }
        }
    }

    fun testVoice() {
        viewModelScope.launch {
            voiceRepository.speak("您好，我是智行助盲APP，正在为您服务")
        }
    }
}

data class MainUiState(
    val isObstacleRunning: Boolean = false,
    val isNavigationRunning: Boolean = false,
    val isLocationAvailable: Boolean = false,
    val isVoiceAvailable: Boolean = false,
    val currentAlert: ObstacleAlert? = null,
    val navigationInfo: NavigationInfo? = null,
    val errorMessage: String? = null
)
