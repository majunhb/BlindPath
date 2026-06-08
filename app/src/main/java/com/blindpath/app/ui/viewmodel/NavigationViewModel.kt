package com.blindpath.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindpath.base.common.Result
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_navigation.domain.model.LatLonPoint
import com.blindpath.module_navigation.domain.model.NavigationState
import com.blindpath.module_navigation.domain.model.RouteStep
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.VoiceType
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 导航 ViewModel -- MVVM 架构中的表现层
 *
 * 职责：
 * 1. 持有 UI 状态（NavigationState）
 * 2. 协调 NavigationRepository 与 VoiceRepository
 * 3. 处理用户交互事件（开始/停止导航）
 * 4. 语音播报协调
 */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val navigationRepository: NavigationRepository,
    private val voiceRepository: VoiceRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(NavigationState())
    val uiState: StateFlow<NavigationState> = _uiState.asStateFlow()

    private val _destinationText = MutableStateFlow("")
    val destinationText: StateFlow<String> = _destinationText.asStateFlow()

    private val _isPlanning = MutableStateFlow(false)
    val isPlanning: StateFlow<Boolean> = _isPlanning.asStateFlow()

    private val _announcement = MutableStateFlow("")
    val announcement: StateFlow<String> = _announcement.asStateFlow()

    init {
        viewModelScope.launch {
            navigationRepository.navigationState.collect { state ->
                val prevState = _uiState.value
                _uiState.value = state

                // 自动步进时播报
                if (state.currentStepIndex != prevState.currentStepIndex && state.routeSteps.isNotEmpty()) {
                    if (state.currentStepIndex < state.routeSteps.size) {
                        val step = state.routeSteps[state.currentStepIndex]
                        val msg = "${step.instruction}。距离${step.distance}，${step.duration}后到达下一步。"
                        _announcement.value = msg
                        voiceRepository.announce(msg, VoiceType.NAVIGATION_TURN)
                    } else {
                        // 导航完成
                        val destName = state.destinationName ?: "目的地"
                        val msg = "已到达${destName}附近，导航结束。"
                        _announcement.value = msg
                        voiceRepository.announce(msg, VoiceType.NAVIGATION_ARRIVE)
                    }
                }

                // 偏航检测时播报
                if (state.isOffRoute && !prevState.isOffRoute) {
                    _announcement.value = "您已偏离路线，正在重新规划..."
                    voiceRepository.announce("您已偏离路线，正在重新规划", VoiceType.SYSTEM_STATUS)
                }
            }
        }
    }

    fun updateDestination(text: String) {
        _destinationText.value = text
    }

    fun startNavigation() {
        val destText = _destinationText.value.trim()
        if (destText.length < 2) {
            viewModelScope.launch {
                voiceRepository.announce("目的地名称太短，请输入更详细的地址", VoiceType.SYSTEM_STATUS)
            }
            return
        }

        viewModelScope.launch {
            _isPlanning.value = true
            voiceRepository.announce("正在规划路线，请稍候", VoiceType.SYSTEM_STATUS)

            // 1. 获取当前位置
            val location = navigationRepository.getCurrentLocation()
            if (location == null) {
                voiceRepository.announce("无法获取当前位置，请检查定位权限", VoiceType.SYSTEM_STATUS)
                _isPlanning.value = false
                return@launch
            }

            // 2. 地理编码目的地
            val geoResult = navigationRepository.geocodeDestination(destText)
            if (geoResult is Result.Error) {
                voiceRepository.announce("无法识别目的地地址，请重新输入", VoiceType.SYSTEM_STATUS)
                _isPlanning.value = false
                return@launch
            }
            val destPoint = (geoResult as Result.Success).data

            // 3. 设置目的地
            navigationRepository.setDestination(destPoint.latitude, destPoint.longitude, destText)

            // 4. 规划路线
            val routeResult = navigationRepository.planRoute(
                location.latitude, location.longitude,
                destPoint.latitude, destPoint.longitude
            )
            if (routeResult is Result.Error) {
                voiceRepository.announce("路线规划失败，请稍后重试", VoiceType.SYSTEM_STATUS)
                _isPlanning.value = false
                return@launch
            }

            // 5. 开始导航
            navigationRepository.startNavigation()
            _isPlanning.value = false

            val state = _uiState.value
            val msg = "路线规划完成，全程${state.totalDistance}，预计${state.totalDuration}。开始导航。"
            _announcement.value = msg
            voiceRepository.announce(msg, VoiceType.NAVIGATION_PROGRESS)
        }
    }

    fun stopNavigation() {
        viewModelScope.launch {
            navigationRepository.stopNavigation()
            navigationRepository.clearDestination()
            voiceRepository.announce("导航已取消", VoiceType.SYSTEM_STATUS)
        }
    }

    fun exitNavigation() {
        stopNavigation()
    }
}