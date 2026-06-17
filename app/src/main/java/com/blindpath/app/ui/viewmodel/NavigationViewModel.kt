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
import com.blindpath.base.navigation.NavigationMode
import com.blindpath.base.navigation.NavigationModeManager
import com.blindpath.base.navigation.BlindPathGuidanceEngine
import com.blindpath.base.navigation.TrafficLightAnnouncer
import com.blindpath.base.perception.LowLightDetector
import com.blindpath.base.navigation.model.TactilePavingResult
import com.blindpath.base.navigation.model.TrafficLightState
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
 * 交通灯状态枚举 — 统一使用 module_obstacle 定义
 * @see com.blindpath.module_obstacle.data.detection.TrafficLightState
 */

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

    // ==================== PRD V2.0：导航模式管理器 ====================

    /**
     * 导航模式管理器 - 统一管理语音/AR模式切换
     * 提供 StateFlow<NavigationMode> 暴露当前模式
     * 提供 switchMode() 方法，带过渡控制（淡入淡出 ≤ 1秒）
     */
    val modeManager = NavigationModeManager()

    /** 当前导航模式的便捷访问 */
    val currentNavigationMode = modeManager.currentMode
    val isModeTransitioning = modeManager.isTransitioning

    // ==================== PRD V2.0 第二期：盲道引导引擎 ====================

    /** 盲道实时引导引擎 — 接收检测结果，生成语音引导 */
    val blindPathGuidance = BlindPathGuidanceEngine()

    /** 盲道引导状态流 */
    val blindPathGuidanceState = blindPathGuidance.state

    // ==================== PRD V2.0 第二期：红绿灯定时播报 ====================

    /** 红绿灯定时播报器 — 红灯10秒重复播报，绿灯即时播报 */
    val trafficLightAnnouncer = TrafficLightAnnouncer()

    /** 红绿灯播报状态流 */
    val trafficLightAnnouncerState = trafficLightAnnouncer.state

    // ==================== PRD V2.0 第二期：弱光检测 ====================

    /** 弱光检测器 */
    val lowLightDetector = LowLightDetector()

    /** 弱光状态流 */
    private val _lowLightState = MutableStateFlow(LowLightDetector.LowLightState())
    val lowLightState: StateFlow<LowLightDetector.LowLightState> = _lowLightState.asStateFlow()

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
        // PRD V2.0 第二期：初始化红绿灯播报器回调
        trafficLightAnnouncer.setAnnounceCallback { state, message ->
            viewModelScope.launch {
                val voiceType = when (state) {
                    TrafficLightState.GREEN -> VoiceType.NAVIGATION_TURN
                    else -> VoiceType.SYSTEM_STATUS
                }
                voiceRepository.announce(message, voiceType)
            }
        }

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

    /**
     * 公共导航启动方法 — 消除 startNavigation/selectSearchResult 中的重复路线规划逻辑
     * @return true 表示导航成功启动，false 表示失败（已播报错误信息）
     */
    private suspend fun navigateTo(
        lat: Double, lon: Double, name: String, location: android.location.Location
    ): Boolean {
        navigationRepository.setDestination(lat, lon, name)
        _destinationText.value = name

        val routeResult = navigationRepository.planRoute(
            location.latitude, location.longitude, lat, lon
        )
        if (routeResult is com.blindpath.base.common.Result.Error) {
            voiceRepository.announce("路线规划失败，请稍后重试", VoiceType.SYSTEM_STATUS)
            _isPlanning.value = false
            return false
        }

        navigationRepository.startNavigation()
        _isPlanning.value = false
        _isNavigating.value = true

        val state = _uiState.value
        val msg = "路线规划完成，全程${state.totalDistance}，预计${state.totalDuration}。开始导航。"
        _announcement.value = msg
        voiceRepository.announce(msg, VoiceType.NAVIGATION_PROGRESS)
        return true
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

            // 3. 规划路线 + 开始导航
            navigateTo(destPoint.latitude, destPoint.longitude, destText, location)
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
                is Result.Loading -> {}
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
                is Result.Loading -> {}
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

            // 2. 规划路线 + 开始导航
            if (!navigateTo(item.latitude, item.longitude, item.name, location)) return@launch
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
     * 根据障碍物距离评估危险等级
     *
     * ★ PRD V2.0 对齐阈值：
     * - 距离 > 3m：不预警 (LOW)
     * - 1.5m ≤ 距离 ≤ 3m：语音提示"前方有障碍物" (HIGH)
     * - 距离 < 1.5m：语音 + 连续震动，紧急提示"立即停止" (CRITICAL)
     *
     * 同时考虑移动速度作为辅助判断（保留原有逻辑的合理部分）
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
                // ★ PRD 紧急：距离 < 1.5m（或 TTC < 2秒的移动障碍物）
                obstacle.distance < 1.5f || obstacle.ttc < 2f -> DangerLevel.CRITICAL
                // ★ PRD 预警：1.5m ≤ 距离 ≤ 3m（或移动障碍物距离 < 5m）
                obstacle.distance <= 3f || (obstacle.distance < 5f && obstacle.speed > 1f) -> DangerLevel.HIGH
                // 中：5m-10m 有移动障碍物
                obstacle.distance < 10f && obstacle.speed > 0.5f -> DangerLevel.MEDIUM
                // ★ PRD：> 3m 静态障碍物 → 不预警
                else -> DangerLevel.LOW
            }

            if (level.ordinal > maxLevel.ordinal) {
                maxLevel = level
            }
        }

        val prevLevel = _dangerLevel.value
        _dangerLevel.value = maxLevel

        // 危险等级变化时语音告警（PRD 对齐）
        if (maxLevel.ordinal > prevLevel.ordinal) {
            viewModelScope.launch {
                when (maxLevel) {
                    DangerLevel.CRITICAL -> voiceRepository.announce("立即停止！前方有障碍物，距离不足1.5米！", VoiceType.SYSTEM_STATUS)
                    DangerLevel.HIGH -> voiceRepository.announce("前方有障碍物，请注意安全。", VoiceType.SYSTEM_STATUS)
                    DangerLevel.MEDIUM -> voiceRepository.announce("注意，前方有障碍物。", VoiceType.SYSTEM_STATUS)
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
        Timber.w("handleVoiceCommand() called with [" + command + "] - deprecated, use VoiceInteractionPipeline")
        viewModelScope.launch {
            voiceRepository.announce("语音指令已由系统接管，请使用唤醒词", VoiceType.SYSTEM_STATUS)
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

    // ==================== 6. 导航模式切换（PRD V2.0） ====================

    /**
     * 切换到 AR 实景导航模式
     * 保持当前导航状态不丢失（路线、目的地等保留）
     *
     * @param onMidTransition 过渡中间回调，用于语音播报切换提示
     */
    fun switchToArMode(onMidTransition: (() -> Unit)? = null) {
        viewModelScope.launch {
            modeManager.switchMode(NavigationMode.AR) {
                viewModelScope.launch {
                    voiceRepository.announce("正在切换到AR实景导航模式", VoiceType.SYSTEM_STATUS)
                }
                onMidTransition?.invoke()
            }
        }
    }

    /**
     * 切换到语音导航模式
     * 保持当前导航状态不丢失
     */
    fun switchToVoiceMode(onMidTransition: (() -> Unit)? = null) {
        viewModelScope.launch {
            modeManager.switchMode(NavigationMode.VOICE) {
                viewModelScope.launch {
                    voiceRepository.announce("正在切换到语音导航模式", VoiceType.SYSTEM_STATUS)
                }
                onMidTransition?.invoke()
            }
        }
    }

    /**
     * 立即设置模式（无过渡动画）
     * 用于初始化或状态恢复
     */
    fun setNavigationModeImmediately(mode: NavigationMode) {
        modeManager.setModeImmediately(mode)
    }

    // ==================== PRD V2.0 第二期：盲道检测接入导航主流程 ====================

    /**
     * 处理盲道检测结果，生成实时语音引导
     *
     * 由相机帧处理循环调用，每次获得 TactilePavingDetector 结果时调用。
     * 内部通过 BlindPathGuidanceEngine 生成引导指令，
     * 当 shouldSpeak=true 时自动触发语音播报。
     *
     * @param result 盲道检测结果，null表示未检测到
     */
    fun processBlindPathDetection(result: TactilePavingResult?) {
        val guidanceState = blindPathGuidance.processFrame(result)

        if (guidanceState.shouldSpeak && guidanceState.instruction.isNotEmpty()) {
            viewModelScope.launch {
                voiceRepository.announce(
                    guidanceState.instruction,
                    VoiceType.NAVIGATION_TURN
                )
            }
        }

        // 更新盲道可见状态到人行道状态
        updateSidewalkStatus(
            SidewalkStatus(
                isOnSidewalk = guidanceState.isBlindPathVisible,
                confidence = guidanceState.confidence,
                distanceToBreak = if (!guidanceState.isBlindPathVisible) 0f else null
            )
        )
    }

    // ==================== PRD V2.0 第二期：红绿灯定时播报接入 ====================

    /**
     * 处理红绿灯分类结果
     *
     * 替代原有的 updateTrafficLightState，增加定时播报逻辑：
     * - 红灯/黄灯：每10秒重复播报
     * - 绿灯：即时播报
     * - 未知：停止播报
     *
     * @param state 红绿灯分类结果
     */
    fun processTrafficLightDetection(state: TrafficLightState) {
        // 更新原有状态
        _trafficLightState.value = state

        // 触发定时播报器
        trafficLightAnnouncer.updateState(state)

        // 更新过街决策
        evaluateCrossingStatus()
    }

    // ==================== PRD V2.0 第二期：弱光检测接入 ====================

    /**
     * 处理弱光检测结果
     *
     * 由相机帧处理循环调用，在检测障碍物之前先检测环境光线。
     * 当弱光环境检测到时，UI层会自动启用屏幕补光。
     *
     * @param bitmap 相机帧
     * @return 弱光状态
     */
    fun processLowLightDetection(bitmap: android.graphics.Bitmap): LowLightDetector.LowLightState {
        val wasLowLight = _lowLightState.value.isLowLight
        val state = lowLightDetector.detect(bitmap)
        _lowLightState.value = state

        // 弱光状态变化时语音提醒
        if (state.isLowLight && !wasLowLight) {
            viewModelScope.launch {
                voiceRepository.announce("环境光线较暗，已开启屏幕补光", VoiceType.SYSTEM_STATUS)
            }
        } else if (!state.isLowLight && wasLowLight) {
            viewModelScope.launch {
                voiceRepository.announce("环境光线恢复正常，已关闭屏幕补光", VoiceType.SYSTEM_STATUS)
            }
        }

        return state
    }

    /**
     * 清理资源 — Activity/Fragment onDestroy 时调用
     */
    fun cleanup() {
        trafficLightAnnouncer.stopAnnouncing()
        blindPathGuidance.reset()
        lowLightDetector.reset()
    }

    override fun onCleared() {
        super.onCleared()
        cleanup()
    }
}
