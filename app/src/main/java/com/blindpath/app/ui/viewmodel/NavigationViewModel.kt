package com.blindpath.app.ui.viewmodel

import android.view.KeyEvent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindpath.base.common.Result
import com.blindpath.module_navigation.data.search.SearchResultItem
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
 * 障碍物信息数据类
 * @param type 障碍物类型
 * @param distance 距离（米）
 * @param speed 速度（m/s）
 * @param direction 相对于用户的方向角度（度）
 * @param ttc 碰撞时间 Time To Collision（秒）
 */
data class ObstacleInfo(
    val type: ObstacleType,
    val distance: Float,
    val speed: Float,
    val direction: Float,
    val ttc: Float
)

/**
 * 障碍物类型枚举
 */
enum class ObstacleType { VEHICLE, NON_VEHICLE, PEDESTRIAN, STATIC }

/**
 * 危险等级枚举
 */
enum class DangerLevel { CRITICAL, HIGH, MEDIUM, LOW }

/**
 * 过街状态枚举
 */
enum class CrossingStatus { CAN_CROSS, WAIT, DANGER, NONE }

/**
 * 交通灯状态枚举
 */
enum class TrafficLightState { RED, YELLOW, GREEN, UNKNOWN }

/**
 * 人行道状态数据类
 * @param isOnSidewalk 是否在人行道上
 * @param confidence 置信度（0-1）
 * @param distanceToBreak 距盲道断点距离（米），null表示无障碍
 */
data class SidewalkStatus(
    val isOnSidewalk: Boolean,
    val confidence: Float,
    val distanceToBreak: Float? = null
)

/**
 * 路面变化类型枚举
 */
enum class SurfaceChangeType { STEP_UP, STEP_DOWN, SLOPE, CURB }

/**
 * 路面变化信息数据类
 * @param type 变化类型
 * @param distance 距离（米）
 * @param heightDiff 高度差（米）
 */
data class SurfaceChangeInfo(
    val type: SurfaceChangeType,
    val distance: Float,
    val heightDiff: Float
)

/**
 * 导航 ViewModel -- MVVM 架构中的表现层
 *
 * 职责：
 * 1. 持有 UI 状态（NavigationState）
 * 2. 协调 NavigationRepository 与 VoiceRepository
 * 3. 处理用户交互事件（开始/停止导航、实体按键、语音指令）
 * 4. 语音播报协调
 * 5. 四级危险等级评估与智能过街决策
 * 6. 感知层数据融合
 */
@HiltViewModel
class NavigationViewModel @Inject constructor(
    private val navigationRepository: NavigationRepository,
    private val voiceRepository: VoiceRepository
) : ViewModel() {

    // ==================== 原有状态 ====================

    private val _uiState = MutableStateFlow(NavigationState())
    val uiState: StateFlow<NavigationState> = _uiState.asStateFlow()

    private val _destinationText = MutableStateFlow("")
    val destinationText: StateFlow<String> = _destinationText.asStateFlow()

    private val _isPlanning = MutableStateFlow(false)
    val isPlanning: StateFlow<Boolean> = _isPlanning.asStateFlow()

    private val _announcement = MutableStateFlow("")
    val announcement: StateFlow<String> = _announcement.asStateFlow()

    // ==================== 新增：四级危险等级评估 ====================

    private val _dangerLevel = MutableStateFlow(DangerLevel.LOW)
    val dangerLevel: StateFlow<DangerLevel> = _dangerLevel.asStateFlow()

    private val _obstacles = MutableStateFlow<List<ObstacleInfo>>(emptyList())
    val obstacles: StateFlow<List<ObstacleInfo>> = _obstacles.asStateFlow()

    // ==================== 新增：智能过街决策 ====================

    private val _crossingStatus = MutableStateFlow(CrossingStatus.NONE)
    val crossingStatus: StateFlow<CrossingStatus> = _crossingStatus.asStateFlow()

    private val _isCrossingMode = MutableStateFlow(false)
    val isCrossingMode: StateFlow<Boolean> = _isCrossingMode.asStateFlow()

    // ==================== 搜索状态 ====================

    private val _searchResults = MutableStateFlow<List<SearchResultItem>>(emptyList())
    val searchResults: StateFlow<List<SearchResultItem>> = _searchResults.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    private val _searchError = MutableStateFlow<String?>(null)
    val searchError: StateFlow<String?> = _searchError.asStateFlow()

    // ==================== 新增：感知层数据融合 ====================

    private val _trafficLightState = MutableStateFlow(TrafficLightState.UNKNOWN)
    val trafficLightState: StateFlow<TrafficLightState> = _trafficLightState.asStateFlow()

    private val _sidewalkStatus = MutableStateFlow(SidewalkStatus(false, 0f))
    val sidewalkStatus: StateFlow<SidewalkStatus> = _sidewalkStatus.asStateFlow()

    private val _surfaceChangeAlert = MutableStateFlow<SurfaceChangeInfo?>(null)
    val surfaceChangeAlert: StateFlow<SurfaceChangeInfo?> = _surfaceChangeAlert.asStateFlow()

    private val _currentHeading = MutableStateFlow(0f)
    val currentHeading: StateFlow<Float> = _currentHeading.asStateFlow()

    // ==================== 内部辅助状态 ====================

    /** 上一次播报的导航指令，用于重复播报 */
    private var lastNavigationInstruction: String = ""

    /** 导航是否处于活跃状态 */
    private val _isNavigating = MutableStateFlow(false)
    val isNavigating: StateFlow<Boolean> = _isNavigating.asStateFlow()

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
                        lastNavigationInstruction = msg
                        _announcement.value = msg
                        voiceRepository.announce(msg, VoiceType.NAVIGATION_TURN)
                    } else {
                        // 导航完成
                        val destName = state.destinationName ?: "目的地"
                        val msg = "已到达${destName}附近，导航结束。"
                        _announcement.value = msg
                        voiceRepository.announce(msg, VoiceType.NAVIGATION_ARRIVE)
                        _isNavigating.value = false
                    }
                }

                // 偏航检测时播报并重新规划（阈值改为10米）
                if (state.isOffRoute && !prevState.isOffRoute) {
                    _announcement.value = "您已偏离路线，正在重新规划..."
                    voiceRepository.announce("您已偏离路线，正在重新规划", VoiceType.SYSTEM_STATUS)
                    // 真正重新规划路线
                    viewModelScope.launch {
                        val currentLocation = navigationRepository.getCurrentLocation()
                        val destination = state.destinationPoint
                        if (currentLocation != null && destination != null) {
                            val result = navigationRepository.planRoute(
                                currentLocation.latitude, currentLocation.longitude,
                                destination.latitude, destination.longitude
                            )
                            if (result is Result.Success) {
                                voiceRepository.announce("路线已重新规划，请按新路线行走", VoiceType.NAVIGATION_PROGRESS)
                            } else {
                                voiceRepository.announce("重新规划失败，请检查网络或位置", VoiceType.SYSTEM_STATUS)
                            }
                        }
                    }
                }
            }
        }
    }

    // ==================== 原有功能 ====================

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
            _isNavigating.value = true

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
            _isNavigating.value = false
        }
    }

    fun nextStep() {
        viewModelScope.launch {
            navigationRepository.advanceToNextStep()
        }
    }

    fun exitNavigation() {
        stopNavigation()
    }

    // ==================== 搜索功能 ====================

    /**
     * 输入联想提示
     * 用户输入至少2字后自动触发
     */
    fun searchInputTips(keywords: String) {
        if (keywords.length < 2) {
            _searchResults.value = emptyList()
            return
        }

        viewModelScope.launch {
            _isSearching.value = true
            _searchError.value = null

            when (val result = navigationRepository.getInputTips(keywords)) {
                is Result.Success -> {
                    _searchResults.value = result.data
                    if (result.data.isEmpty()) {
                        _searchError.value = "未找到匹配地址"
                    }
                }
                is Result.Error -> {
                    _searchResults.value = emptyList()
                    _searchError.value = result.message
                }
            }

            _isSearching.value = false
        }
    }

    /**
     * 关键词搜索
     */
    fun searchAddress(keywords: String) {
        if (keywords.length < 2) return

        viewModelScope.launch {
            _isSearching.value = true
            _searchError.value = null

            when (val result = navigationRepository.searchAddress(keywords)) {
                is Result.Success -> {
                    _searchResults.value = result.data
                    if (result.data.isEmpty()) {
                        _searchError.value = "未找到匹配结果"
                    }
                }
                is Result.Error -> {
                    _searchResults.value = emptyList()
                    _searchError.value = result.message
                }
            }

            _isSearching.value = false
        }
    }

    /**
     * 选中搜索结果 → 直接设目的地 + 规划路线 + 开始导航
     * 减少盲人用户操作步骤
     */
    fun selectSearchResult(item: SearchResultItem) {
        viewModelScope.launch {
            _isPlanning.value = true
            _searchResults.value = emptyList()
            voiceRepository.announce("选中${item.toAccessibilityText()}，正在规划路线", VoiceType.SYSTEM_STATUS)

            // 1. 获取当前位置
            val location = navigationRepository.getCurrentLocation()
            if (location == null) {
                voiceRepository.announce("无法获取当前位置，请检查定位权限", VoiceType.SYSTEM_STATUS)
                _isPlanning.value = false
                return@launch
            }

            // 2. 设置目的地
            _destinationText.value = item.name
            navigationRepository.setDestination(item.latitude, item.longitude, item.name)

            // 3. 规划路线
            val routeResult = navigationRepository.planRoute(
                location.latitude, location.longitude,
                item.latitude, item.longitude
            )
            if (routeResult is Result.Error) {
                voiceRepository.announce("路线规划失败，请稍后重试", VoiceType.SYSTEM_STATUS)
                _isPlanning.value = false
                return@launch
            }

            // 4. 开始导航
            navigationRepository.startNavigation()
            _isPlanning.value = false
            _isNavigating.value = true

            val state = uiState.value
            val msg = "路线规划完成，全程${state.totalDistance}，预计${state.totalDuration}。开始导航。"
            _announcement.value = msg
            voiceRepository.announce(msg, VoiceType.NAVIGATION_PROGRESS)
        }
    }

    /**
     * 清除搜索结果
     */
    fun clearSearchResults() {
        _searchResults.value = emptyList()
        _searchError.value = null
    }

    // ==================== 1. 四级危险等级评估 ====================

    /**
     * 更新障碍物列表并重新评估危险等级
     */
    fun updateObstacles(newObstacles: List<ObstacleInfo>) {
        _obstacles.value = newObstacles
        evaluateDangerLevel()
    }

    /**
     * 根据障碍物距离/速度/方向动态评估危险等级
     * - 紧急(CRITICAL)：距离 < 2m 且速度 > 5m/s 或 TTC < 2秒
     * - 高(HIGH)：距离 < 5m 且速度 > 2m/s 或 TTC < 5秒
     * - 中(MEDIUM)：距离 < 10m 且速度 > 0.5m/s
     * - 低(LOW)：其他情况
     */
    fun evaluateDangerLevel() {
        val obstacleList = _obstacles.value
        if (obstacleList.isEmpty()) {
            _dangerLevel.value = DangerLevel.LOW
            return
        }

        var maxLevel = DangerLevel.LOW

        for (obstacle in obstacleList) {
            val level = when {
                // 紧急：距离 < 2m 且速度 > 5m/s 或 TTC < 2秒
                (obstacle.distance < 2f && obstacle.speed > 5f) || obstacle.ttc < 2f -> DangerLevel.CRITICAL
                // 高：距离 < 5m 且速度 > 2m/s 或 TTC < 5秒
                (obstacle.distance < 5f && obstacle.speed > 2f) || obstacle.ttc < 5f -> DangerLevel.HIGH
                // 中：距离 < 10m 且速度 > 0.5m/s
                obstacle.distance < 10f && obstacle.speed > 0.5f -> DangerLevel.MEDIUM
                // 低：其他情况
                else -> DangerLevel.LOW
            }

            if (level.ordinal > maxLevel.ordinal) {
                maxLevel = level
            }
        }

        val prevLevel = _dangerLevel.value
        _dangerLevel.value = maxLevel

        // 危险等级变化时语音告警
        if (maxLevel.ordinal > prevLevel.ordinal) {
            viewModelScope.launch {
                when (maxLevel) {
                    DangerLevel.CRITICAL -> voiceRepository.announce("紧急危险！前方有快速接近障碍物，请立即避让！", VoiceType.SYSTEM_STATUS)
                    DangerLevel.HIGH -> voiceRepository.announce("高度危险！前方有移动障碍物接近，请注意避让。", VoiceType.SYSTEM_STATUS)
                    DangerLevel.MEDIUM -> voiceRepository.announce("中度危险，前方有移动障碍物，请注意。", VoiceType.SYSTEM_STATUS)
                    DangerLevel.LOW -> { /* 不播报 */ }
                }
            }
        }
    }

    // ==================== 2. 智能过街决策 ====================

    /**
     * 更新交通灯状态并重新评估过街状态
     */
    fun updateTrafficLightState(state: TrafficLightState) {
        _trafficLightState.value = state
        evaluateCrossingStatus()
    }

    /**
     * 评估过街状态
     * - 检测到斑马线 + 绿灯 = CAN_CROSS
     * - 检测到斑马线 + 红灯/黄灯 = WAIT
     * - 过街中 + 车辆逼近(< 10m, > 5m/s) = DANGER
     * - 无斑马线 = NONE
     */
    fun evaluateCrossingStatus() {
        val isCrossing = _isCrossingMode.value
        val trafficLight = _trafficLightState.value
        val obstacleList = _obstacles.value

        // 检查是否有车辆逼近（过街中且车辆逼近）
        val hasApproachingVehicle = obstacleList.any {
            it.type == ObstacleType.VEHICLE && it.distance < 10f && it.speed > 5f
        }

        val status = when {
            // 过街中 + 车辆逼近 = DANGER
            isCrossing && hasApproachingVehicle -> CrossingStatus.DANGER
            // 检测到斑马线 + 绿灯 = CAN_CROSS
            isCrossing && trafficLight == TrafficLightState.GREEN -> CrossingStatus.CAN_CROSS
            // 检测到斑马线 + 红灯/黄灯 = WAIT
            isCrossing && (trafficLight == TrafficLightState.RED || trafficLight == TrafficLightState.YELLOW) -> CrossingStatus.WAIT
            // 无斑马线或未开启过街模式 = NONE
            else -> CrossingStatus.NONE
        }

        val prevStatus = _crossingStatus.value
        _crossingStatus.value = status

        // 状态变化时语音播报
        if (status != prevStatus) {
            viewModelScope.launch {
                when (status) {
                    CrossingStatus.CAN_CROSS -> voiceRepository.announce("当前绿灯，可以安全过街。", VoiceType.SYSTEM_STATUS)
                    CrossingStatus.WAIT -> voiceRepository.announce("当前红灯，请等待。", VoiceType.SYSTEM_STATUS)
                    CrossingStatus.DANGER -> voiceRepository.announce("危险！有车辆快速接近，请停止过街！", VoiceType.SYSTEM_STATUS)
                    CrossingStatus.NONE -> { /* 不播报 */ }
                }
            }
        }
    }

    /**
     * 切换过街高敏模式（响应"我要过马路"语音指令）
     */
    fun toggleCrossingMode(enabled: Boolean) {
        _isCrossingMode.value = enabled
        evaluateCrossingStatus()
        viewModelScope.launch {
            if (enabled) {
                voiceRepository.announce("已开启过街高敏模式，正在检测交通信号。", VoiceType.SYSTEM_STATUS)
            } else {
                voiceRepository.announce("已关闭过街高敏模式。", VoiceType.SYSTEM_STATUS)
            }
        }
    }

    // ==================== 3. 实体按键处理 ====================

    /**
     * 处理音量上键双击 — 重复播报上一句导航指令
     */
    fun handleVolumeUpDoubleClick() {
        viewModelScope.launch {
            if (lastNavigationInstruction.isNotEmpty()) {
                voiceRepository.announce("重复播报：$lastNavigationInstruction", VoiceType.NAVIGATION_TURN)
            } else {
                voiceRepository.announce("暂无导航指令可重复播报", VoiceType.SYSTEM_STATUS)
            }
        }
    }

    /**
     * 处理音量下键长按 — 触发 SOS 紧急求助
     */
    fun handleVolumeDownLongPress() {
        viewModelScope.launch {
            voiceRepository.announce("正在发送 SOS 紧急求助信号，请保持冷静。", VoiceType.SYSTEM_STATUS)
            // TODO: 调用紧急求助服务发送当前位置给紧急联系人
            Timber.e("SOS 紧急求助已触发")
        }
    }

    /**
     * 处理电源键双击 — 快速开关导航
     */
    fun handlePowerDoubleClick() {
        if (_isNavigating.value) {
            stopNavigation()
        } else {
            startNavigation()
        }
    }

    /**
     * 分发 KeyEvent 到对应的处理方法
     * 由 Activity 调用，将按键事件分发到 ViewModel
     */
    fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) {
            return false
        }

        return when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                // 音量上键双击检测逻辑应由 Activity 实现，此处仅处理双击回调
                false
            }
            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                // 音量下键长按检测逻辑应由 Activity 实现，此处仅处理长按回调
                false
            }
            KeyEvent.KEYCODE_POWER -> {
                // 电源键双击检测逻辑应由 Activity 实现，此处仅处理双击回调
                false
            }
            else -> false
        }
    }

    // ==================== 4. 语音指令处理 ====================

    /**
     * 处理语音指令
     * - "开始导航到XXX" → 设置目的地并开始导航
     * - "我在哪" → 播报当前位置
     * - "附近有什么" → 播报周边50米设施
     * - "我要过马路" → 开启过街高敏模式
     * - "暂停导航" / "继续导航" / "结束导航"
     */
    fun handleVoiceCommand(command: String) {
        val normalized = command.trim()
        viewModelScope.launch {
            when {
                // 开始导航到XXX
                normalized.startsWith("开始导航到") || normalized.startsWith("导航到") -> {
                    val destination = normalized.removePrefix("开始导航到").removePrefix("导航到").trim()
                    if (destination.isNotEmpty()) {
                        _destinationText.value = destination
                        voiceRepository.announce("收到指令，开始导航到${destination}", VoiceType.SYSTEM_STATUS)
                        startNavigation()
                    } else {
                        voiceRepository.announce("请告诉我您要导航到哪里", VoiceType.SYSTEM_STATUS)
                    }
                }

                // 我在哪
                normalized.contains("我在哪") || normalized.contains("我的位置") -> {
                    val location = navigationRepository.getCurrentLocation()
                    if (location != null) {
                        // TODO: 逆地理编码获取地址名称
                        voiceRepository.announce(
                            "您当前位于纬度${location.latitude}，经度${location.longitude}附近",
                            VoiceType.SYSTEM_STATUS
                        )
                    } else {
                        voiceRepository.announce("无法获取当前位置，请检查定位权限", VoiceType.SYSTEM_STATUS)
                    }
                }

                // 附近有什么
                normalized.contains("附近有什么") || normalized.contains("周边") || normalized.contains("附近") -> {
                    // TODO: 调用 POI 搜索服务查询周边50米设施
                    voiceRepository.announce("正在查询周边50米内的设施，请稍候", VoiceType.SYSTEM_STATUS)
                    // 模拟播报结果
                    voiceRepository.announce("您附近有便利店、公交站和药店", VoiceType.SYSTEM_STATUS)
                }

                // 我要过马路
                normalized.contains("我要过马路") || normalized.contains("过街") || normalized.contains("过马路") -> {
                    toggleCrossingMode(true)
                }

                // 暂停导航
                normalized.contains("暂停导航") -> {
                    // TODO: 实现暂停导航逻辑
                    voiceRepository.announce("导航已暂停", VoiceType.SYSTEM_STATUS)
                }

                // 继续导航
                normalized.contains("继续导航") -> {
                    // TODO: 实现继续导航逻辑
                    voiceRepository.announce("导航已继续", VoiceType.SYSTEM_STATUS)
                }

                // 结束导航
                normalized.contains("结束导航") || normalized.contains("停止导航") || normalized.contains("取消导航") -> {
                    stopNavigation()
                }

                else -> {
                    voiceRepository.announce("未识别的指令，请重试", VoiceType.SYSTEM_STATUS)
                }
            }
        }
    }

    // ==================== 5. 感知层数据融合 ====================

    /**
     * 更新人行道状态
     */
    fun updateSidewalkStatus(status: SidewalkStatus) {
        _sidewalkStatus.value = status
        // 盲道断点预警
        if (status.distanceToBreak != null && status.distanceToBreak < 3f) {
            viewModelScope.launch {
                voiceRepository.announce("注意，前方${status.distanceToBreak}米处盲道有断点", VoiceType.SYSTEM_STATUS)
            }
        }
    }

    /**
     * 更新路面变化预警
     */
    fun updateSurfaceChangeAlert(info: SurfaceChangeInfo?) {
        _surfaceChangeAlert.value = info
        info?.let {
            viewModelScope.launch {
                val typeDesc = when (it.type) {
                    SurfaceChangeType.STEP_UP -> "台阶上行"
                    SurfaceChangeType.STEP_DOWN -> "台阶下行"
                    SurfaceChangeType.SLOPE -> "坡道"
                    SurfaceChangeType.CURB -> "路缘"
                }
                voiceRepository.announce("注意，前方${it.distance}米处有${typeDesc}，高度差${it.heightDiff}米", VoiceType.SYSTEM_STATUS)
            }
        }
    }

    /**
     * 更新当前航向角（来自电子罗盘）
     */
    fun updateCurrentHeading(heading: Float) {
        _currentHeading.value = heading
    }
}
