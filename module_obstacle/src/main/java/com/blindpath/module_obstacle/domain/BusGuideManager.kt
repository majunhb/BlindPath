package com.blindpath.module_obstacle.domain

import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.ObstacleType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 公交乘车引导管理器
 *
 * 功能：
 * 1. 检测公交车（基于AI模型BUS类别）
 * 2. 引导用户走向公交站台
 * 3. 检测到公交车后播报线路信息
 * 4. 引导用户上车（车门方向、距离提示）
 */
@Singleton
class BusGuideManager @Inject constructor() {

    enum class GuideState {
        IDLE,           // 空闲
        SEARCHING_STOP, // 搜索公交站
        NEAR_STOP,      // 接近公交站
        WAITING_BUS,    // 等待公交车
        BUS_APPROACHING,// 公交车接近
        BOARDING_GUIDE  // 上车引导
    }

    data class BusGuideState(
        val guideState: GuideState = GuideState.IDLE,
        val busDetected: DetectedObstacle? = null,
        val busDistance: Float = Float.MAX_VALUE,
        val busDirection: String = "",
        val message: String? = null,
        val hasAnnouncedBoarding: Boolean = false
    )

    private val _state = MutableStateFlow(BusGuideState())
    val state: StateFlow<BusGuideState> = _state.asStateFlow()

    companion object {
        const val BUS_APPROACH_DISTANCE = 15f     // 公交车接近距离（米）
        const val BOARDING_DISTANCE = 5f         // 上车引导距离（米）
        const val BOARDING_DIRECTION_THRESHOLD = 0.3f  // 方向偏差阈值
    }

    /**
     * 开始搜索公交站
     */
    fun startSearchBusStop() {
        _state.value = BusGuideState(
            guideState = GuideState.SEARCHING_STOP,
            message = "正在为您搜索附近的公交站，请沿道路方向行走"
        )
        Timber.d("BusGuide: 开始搜索公交站")
    }

    /**
     * 处理检测结果，检查公交车
     */
    fun checkDetectionResult(obstacles: List<DetectedObstacle>) {
        val currentState = _state.value
        if (currentState.guideState == GuideState.IDLE) return

        // 检测公交车
        val bus = obstacles.firstOrNull { it.type == ObstacleType.BUS }
        if (bus != null) {
            when {
                bus.distance <= BOARDING_DISTANCE && !currentState.hasAnnouncedBoarding -> {
                    _state.value = currentState.copy(
                        guideState = GuideState.BOARDING_GUIDE,
                        busDetected = bus,
                        busDistance = bus.distance,
                        busDirection = bus.direction.name,
                        hasAnnouncedBoarding = true,
                        message = "公交车就在${bus.direction.getChineseName()}${bus.distance.toInt()}米处，请准备上车。车门通常在右侧，请注意台阶高度"
                    )
                }
                bus.distance <= BUS_APPROACH_DISTANCE -> {
                    _state.value = currentState.copy(
                        guideState = GuideState.BUS_APPROACHING,
                        busDetected = bus,
                        busDistance = bus.distance,
                        busDirection = bus.direction.name,
                        message = "检测到公交车在${bus.direction.getChineseName()}${bus.distance.toInt()}米处正在靠近"
                    )
                }
                else -> {
                    _state.value = currentState.copy(
                        guideState = GuideState.WAITING_BUS,
                        busDetected = bus,
                        busDistance = bus.distance,
                        message = "远处有公交车驶来，距离约${bus.distance.toInt()}米"
                    )
                }
            }
            Timber.d("BusGuide: 检测到公交车，距离 ${bus.distance}米，方向 ${bus.direction}")
        } else if (currentState.guideState == GuideState.BUS_APPROACHING ||
                   currentState.guideState == GuideState.BOARDING_GUIDE) {
            // 公交车离开视野
            _state.value = currentState.copy(
                guideState = GuideState.WAITING_BUS,
                busDetected = null,
                message = "公交车已驶离，继续等待下一班"
            )
        }
    }

    /**
     * 停止公交引导
     */
    fun stopGuide() {
        _state.value = BusGuideState(
            message = "已停止公交引导"
        )
        Timber.d("BusGuide: 引导已停止")
    }
}
