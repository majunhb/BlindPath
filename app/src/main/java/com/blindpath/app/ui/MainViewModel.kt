package com.blindpath.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindpath.module_navigation.data.NavigationRepositoryImpl
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * MainScreen 的 ViewModel
 * 管理 UI 状态和业务逻辑，遵循 MVVM 架构
 */
@HiltViewModel
class MainViewModel @Inject constructor(
    private val voiceRepository: VoiceRepository,
    private val navigationRepository: NavigationRepositoryImpl
) : ViewModel() {

    // UI 状态
    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    init {
        initializeVoice()
    }

    /**
     * 初始化语音引擎
     */
    private fun initializeVoice() {
        viewModelScope.launch {
            voiceRepository.initialize()
            voiceRepository.speak("智行助盲应用已启动", queueMode = false)
        }
    }

    /**
     * 执行动作（权限授予后）
     */
    fun performAction(action: String) {
        viewModelScope.launch {
            when (action) {
                "start_obstacle" -> startObstacleService()
                "start_navigation" -> startNavigationService()
                "stop_obstacle" -> stopObstacleService()
                "stop_navigation" -> stopNavigationService()
            }
            _uiState.value = _uiState.value.copy(pendingAction = null)
        }
    }

    /**
     * 启动障碍物检测服务
     */
    private suspend fun startObstacleService() {
        _uiState.value = _uiState.value.copy(isObstacleRunning = true)
        // 实际启动服务逻辑由 Activity/Service 处理
    }

    /**
     * 停止障碍物检测服务
     */
    private suspend fun stopObstacleService() {
        _uiState.value = _uiState.value.copy(isObstacleRunning = false)
    }

    /**
     * 启动导航服务
     */
    private suspend fun startNavigationService() {
        _uiState.value = _uiState.value.copy(isNavigationRunning = true)
    }

    /**
     * 停止导航服务
     */
    private suspend fun stopNavigationService() {
        _uiState.value = _uiState.value.copy(isNavigationRunning = false)
    }

    /**
     * 发送 SOS
     */
    fun sendSos(latitude: Double?, longitude: Double?) {
        viewModelScope.launch {
            val locationText = if (latitude != null && longitude != null) {
                "，当前位置：纬度${latitude}，经度${longitude}"
            } else {
                ""
            }
            voiceRepository.speak("正在发送求救信号$locationText", queueMode = false)
        }
    }

    /**
     * 获取当前位置（在线程中执行）
     */
    fun getCurrentLocation(onResult: (Double?, Double?) -> Unit) {
        viewModelScope.launch {
            try {
                val location = withContext(Dispatchers.IO) {
                    // 在 IO 线程中执行位置获取
                    navigationRepository.getCurrentLocation()
                }
                onResult(location?.latitude, location?.longitude)
            } catch (e: Exception) {
                onResult(null, null)
            }
        }
    }

    /**
     * 设置待执行的动作
     */
    fun setPendingAction(action: String?) {
        _uiState.value = _uiState.value.copy(pendingAction = action)
    }

    /**
     * 清除错误状态
     */
    fun clearError() {
        _uiState.value = _uiState.value.copy(errorMessage = null)
    }

    override fun onCleared() {
        super.onCleared()
        voiceRepository.release()
    }
}

/**
 * MainScreen UI 状态数据类
 */
data class MainUiState(
    val isObstacleRunning: Boolean = false,
    val isNavigationRunning: Boolean = false,
    val pendingAction: String? = null,
    val errorMessage: String? = null
)
