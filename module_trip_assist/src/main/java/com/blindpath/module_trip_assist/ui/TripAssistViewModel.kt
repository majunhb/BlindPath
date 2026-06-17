package com.blindpath.module_trip_assist.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindpath.base.common.Result
import com.blindpath.module_trip_assist.domain.TripAssistRepository
import com.blindpath.module_trip_assist.domain.TripAssistState
import com.blindpath.module_trip_assist.domain.TripAssistTab
import com.blindpath.module_trip_assist.domain.model.FacilityType
import com.blindpath.module_trip_assist.domain.model.TransportMode
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

/**
 * 出行辅助 ViewModel
 * 管理 UI 状态，协调 Repository 调用
 */
@HiltViewModel
class TripAssistViewModel @Inject constructor(
    private val tripAssistRepository: TripAssistRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(TripAssistState())
    val uiState: StateFlow<TripAssistState> = _uiState.asStateFlow()

    // 路线规划输入状态
    val originText = MutableStateFlow("")
    val destinationText = MutableStateFlow("")
    val selectedTransportMode = MutableStateFlow(TransportMode.SUBWAY)

    init {
        // 收集 Repository 状态
        viewModelScope.launch {
            tripAssistRepository.tripAssistState.collect { state ->
                _uiState.update { state }
            }
        }
    }

    // ==================== Tab 切换 ====================

    fun switchTab(tab: TripAssistTab) {
        _uiState.update { it.copy(activeTab = tab) }
    }

    // ==================== 天气播报 ====================

    /**
     * 获取当前位置天气并播报
     */
    fun fetchAndAnnounceWeather(latitude: Double = 39.9042, longitude: Double = 116.4074) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = tripAssistRepository.getWeather(latitude, longitude)) {
                is Result.Success -> {
                    tripAssistRepository.announceWeather(result.data)
                    Timber.d("Weather fetched and announced: ${result.data.description}")
                }
                is Result.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                    Timber.e("Failed to fetch weather: ${result.message}")
                }
                is Result.Loading -> { /* no-op */ }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * 按城市名获取天气
     */
    fun fetchWeatherByCity(cityName: String) {
        if (cityName.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = tripAssistRepository.getWeatherByCity(cityName)) {
                is Result.Success -> {
                    tripAssistRepository.announceWeather(result.data)
                }
                is Result.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                }
                is Result.Loading -> { /* no-op */ }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * 重新播报天气
     */
    fun replayWeather() {
        val weather = _uiState.value.weatherInfo ?: return
        viewModelScope.launch {
            tripAssistRepository.announceWeather(weather)
        }
    }

    // ==================== 路线规划 ====================

    /**
     * 规划路线并播报概览
     */
    fun planRouteAndAnnounce() {
        val origin = originText.value.trim()
        val destination = destinationText.value.trim()

        if (origin.isBlank() || destination.isBlank()) {
            _uiState.update { it.copy(error = "请输入起点和终点") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = tripAssistRepository.planRoute(
                origin = origin,
                destination = destination,
                mode = selectedTransportMode.value
            )) {
                is Result.Success -> {
                    tripAssistRepository.announceRouteOverview(result.data)
                    Timber.d("Route planned: ${result.data.steps.size} steps")
                }
                is Result.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                    Timber.e("Failed to plan route: ${result.message}")
                }
                is Result.Loading -> { /* no-op */ }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * 播报指定步骤
     */
    fun announceStep(stepIndex: Int) {
        viewModelScope.launch {
            tripAssistRepository.announceRouteStep(stepIndex)
        }
    }

    /**
     * 播报下一步
     */
    fun announceNextStep() {
        val route = _uiState.value.currentRoute ?: return
        val nextIndex = _uiState.value.currentStepIndex + 1
        if (nextIndex < route.steps.size) {
            announceStep(nextIndex)
        }
    }

    /**
     * 播报上一步
     */
    fun announcePreviousStep() {
        val prevIndex = _uiState.value.currentStepIndex - 1
        if (prevIndex >= 0) {
            announceStep(prevIndex)
        }
    }

    /**
     * 重新播报路线概览
     */
    fun replayRouteOverview() {
        val route = _uiState.value.currentRoute ?: return
        viewModelScope.launch {
            tripAssistRepository.announceRouteOverview(route)
        }
    }

    /**
     * 设置交通方式
     */
    fun setTransportMode(mode: TransportMode) {
        selectedTransportMode.value = mode
    }

    // ==================== 无障碍设施查询 ====================

    /**
     * 搜索附近设施并播报
     */
    fun searchAndAnnounceFacilities(
        latitude: Double = 39.9042,
        longitude: Double = 116.4074
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = tripAssistRepository.searchNearbyFacilities(
                latitude = latitude,
                longitude = longitude
            )) {
                is Result.Success -> {
                    tripAssistRepository.announceNearbyFacilities(result.data)
                    Timber.d("Found ${result.data.size} facilities")
                }
                is Result.Error -> {
                    _uiState.update { it.copy(error = result.message) }
                    Timber.e("Failed to search facilities: ${result.message}")
                }
                is Result.Loading -> { /* no-op */ }
            }
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    /**
     * 播报单个设施详情
     */
    fun announceFacilityDetail(index: Int) {
        val facilities = _uiState.value.nearbyFacilities
        if (index < 0 || index >= facilities.size) return

        viewModelScope.launch {
            val facility = facilities[index]
            tripAssistRepository.announceNearbyFacilities(listOf(facility))
        }
    }

    /**
     * 清除错误信息
     */
    fun clearError() {
        _uiState.update { it.copy(error = null) }
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("TripAssistViewModel cleared")
    }
}
