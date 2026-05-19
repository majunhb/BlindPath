package com.blindpath.module_trip_assist.domain

import com.blindpath.base.common.Result
import com.blindpath.module_trip_assist.domain.model.AccessibleFacility
import com.blindpath.module_trip_assist.domain.model.FacilityType
import com.blindpath.module_trip_assist.domain.model.RoutePlan
import com.blindpath.module_trip_assist.domain.model.TransportMode
import com.blindpath.module_trip_assist.domain.model.WeatherInfo
import kotlinx.coroutines.flow.Flow

/**
 * 出行辅助 Repository 接口
 * 定义在 domain 层，遵循 Clean Architecture 原则
 */
interface TripAssistRepository {

    /**
     * 出行辅助模块的 UI 状态流
     */
    val tripAssistState: Flow<TripAssistState>

    // ==================== 天气播报 ====================

    /**
     * 获取当前位置的天气信息
     * @param latitude 纬度
     * @param longitude 经度
     * @return 天气信息
     */
    suspend fun getWeather(latitude: Double, longitude: Double): Result<WeatherInfo>

    /**
     * 获取指定城市的天气信息
     * @param cityName 城市名称
     * @return 天气信息
     */
    suspend fun getWeatherByCity(cityName: String): Result<WeatherInfo>

    /**
     * 语音播报天气信息
     * @param weather 天气信息
     */
    suspend fun announceWeather(weather: WeatherInfo): Result<Boolean>

    // ==================== 路线规划 ====================

    /**
     * 规划路线
     * @param origin 起点名称或坐标
     * @param destination 终点名称或坐标
     * @param mode 交通方式
     * @return 路线规划结果
     */
    suspend fun planRoute(
        origin: String,
        destination: String,
        mode: TransportMode = TransportMode.SUBWAY
    ): Result<RoutePlan>

    /**
     * 获取上一次规划的路线
     * @return 上次路线，如果没有返回 null
     */
    fun getCurrentRoute(): RoutePlan?

    /**
     * 语音播报路线概览
     * @param route 路线信息
     */
    suspend fun announceRouteOverview(route: RoutePlan): Result<Boolean>

    /**
     * 语音播报指定步骤
     * @param stepIndex 步骤索引（从 0 开始）
     * @return 是否播报成功
     */
    suspend fun announceRouteStep(stepIndex: Int): Result<Boolean>

    // ==================== 无障碍设施查询 ====================

    /**
     * 查询附近的无障碍设施
     * @param latitude 当前纬度
     * @param longitude 当前经度
     * @param radius 搜索半径（米），默认 1000 米
     * @param facilityTypes 要搜索的设施类型，为空则搜索所有类型
     * @return 附近的无障碍设施列表
     */
    suspend fun searchNearbyFacilities(
        latitude: Double,
        longitude: Double,
        radius: Float = DEFAULT_SEARCH_RADIUS,
        facilityTypes: List<FacilityType> = emptyList()
    ): Result<List<AccessibleFacility>>

    /**
     * 语音播报附近设施概览
     * @param facilities 设施列表
     */
    suspend fun announceNearbyFacilities(facilities: List<AccessibleFacility>): Result<Boolean>

    /**
     * 释放资源
     */
    fun release()

    companion object {
        /** 默认搜索半径：1000 米 */
        const val DEFAULT_SEARCH_RADIUS = 1000f

        /** 天气缓存有效期：30 分钟 */
        const val WEATHER_CACHE_DURATION_MS = 30 * 60 * 1000L

        /** 设施搜索最大结果数 */
        const val MAX_FACILITY_RESULTS = 20
    }
}

/**
 * 出行辅助模块 UI 状态
 */
data class TripAssistState(
    val isLoading: Boolean = false,
    val weatherInfo: WeatherInfo? = null,
    val currentRoute: RoutePlan? = null,
    val currentStepIndex: Int = 0,
    val nearbyFacilities: List<com.blindpath.module_trip_assist.domain.model.AccessibleFacility> = emptyList(),
    val error: String? = null,
    val activeTab: TripAssistTab = TripAssistTab.WEATHER
)

/**
 * 出行辅助页面 Tab 枚举
 */
enum class TripAssistTab(val displayName: String) {
    WEATHER("天气播报"),
    ROUTE("路线规划"),
    FACILITY("无障碍设施")
}
