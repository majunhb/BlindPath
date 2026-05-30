package com.blindpath.module_trip_assist.data

import android.content.Context
import com.blindpath.base.common.Result
import com.blindpath.base.common.safeApiCall
import com.blindpath.module_trip_assist.domain.TripAssistRepository
import com.blindpath.module_trip_assist.domain.TripAssistState
import com.blindpath.module_trip_assist.domain.TripAssistTab
import com.blindpath.module_trip_assist.domain.model.AccessibleFacility
import com.blindpath.module_trip_assist.domain.model.FacilityType
import com.blindpath.module_trip_assist.domain.model.RoutePlan
import com.blindpath.module_trip_assist.domain.model.RouteStep
import com.blindpath.module_trip_assist.domain.model.TransportMode
import com.blindpath.module_trip_assist.domain.model.WeatherCondition
import com.blindpath.module_trip_assist.domain.model.WeatherInfo
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 出行辅助 Repository 实现
 * 包含天气查询、路线规划、无障碍设施查询三大功能
 */
@Singleton
class TripAssistRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceRepository: VoiceRepository
) : TripAssistRepository {

    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private val _state = MutableStateFlow(TripAssistState())
    override val tripAssistState: StateFlow<TripAssistState> = _state.asStateFlow()

    // 天气 API
    private var weatherApiService: WeatherApiService? = null
    private var cachedWeather: WeatherInfo? = null
    private var weatherCacheTime: Long = 0L

    // 路线规划
    private var currentRoutePlan: RoutePlan? = null

    init {
        initWeatherApi()
    }

    /**
     * 初始化天气 API（和风天气）
     */
    private fun initWeatherApi() {
        try {
            val retrofit = Retrofit.Builder()
                .baseUrl(WEATHER_API_BASE_URL)
                .addConverterFactory(GsonConverterFactory.create())
                .build()
            weatherApiService = retrofit.create(WeatherApiService::class.java)
            Timber.d("Weather API initialized successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize Weather API")
        }
    }

    // ==================== 天气播报 ====================

    override suspend fun getWeather(latitude: Double, longitude: Double): Result<WeatherInfo> {
        // 检查缓存
        if (cachedWeather != null &&
            System.currentTimeMillis() - weatherCacheTime < TripAssistRepository.WEATHER_CACHE_DURATION_MS
        ) {
            Timber.d("Returning cached weather data")
            return Result.Success(cachedWeather!!)
        }

        return safeApiCall {
            val apiService = weatherApiService
                ?: return@safeApiCall createMockWeather("当前位置")

            val location = "${longitude},${latitude}"
            val response = apiService.getWeatherNow(
                location = location,
                key = WEATHER_API_KEY
            )

            if (response.code != "200" || response.now == null) {
                Timber.w("Weather API returned error: code=${response.code}")
                return@safeApiCall createMockWeather("当前位置")
            }

            val now = response.now

            // 尝试获取空气质量
            var aqi = -1
            var aqiLevel = "未知"
            try {
                val airResponse = apiService.getAirQuality(location, WEATHER_API_KEY)
                if (airResponse.code == "200" && airResponse.now != null) {
                    aqi = airResponse.now.aqi.toIntOrNull() ?: -1
                    aqiLevel = airResponse.now.category
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to get air quality")
            }

            val weatherInfo = WeatherInfo(
                cityName = "当前位置",
                temperature = now.temp.toFloatOrNull() ?: 0f,
                feelsLike = now.feelsLike.toFloatOrNull() ?: 0f,
                humidity = now.humidity.toIntOrNull() ?: 0,
                windSpeed = now.windSpeed.toFloatOrNull() ?: 0f,
                windDirection = now.windDir,
                visibility = now.vis.toFloatOrNull() ?: 10f,
                condition = WeatherCondition.fromDescription(now.text),
                description = now.text,
                sunriseTime = "",
                sunsetTime = "",
                aqi = aqi,
                aqiLevel = aqiLevel
            )

            // 更新缓存
            cachedWeather = weatherInfo
            weatherCacheTime = System.currentTimeMillis()

            // 更新状态
            _state.update { it.copy(weatherInfo = weatherInfo) }

            weatherInfo
        }
    }

    override suspend fun getWeatherByCity(cityName: String): Result<WeatherInfo> {
        // 城市名查询暂用模拟数据，后续可接入高德地理编码 API
        return safeApiCall {
            val weatherInfo = createMockWeather(cityName)
            cachedWeather = weatherInfo
            weatherCacheTime = System.currentTimeMillis()
            _state.update { it.copy(weatherInfo = weatherInfo) }
            weatherInfo
        }
    }

    override suspend fun announceWeather(weather: WeatherInfo): Result<Boolean> {
        return try {
            val voiceText = weather.toVoiceText()
            voiceRepository.speakNavigation(voiceText)
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to announce weather")
            Result.Error(message = "天气播报失败：${e.message}")
        }
    }

    // ==================== 路线规划 ====================

    override suspend fun planRoute(
        origin: String,
        destination: String,
        mode: TransportMode
    ): Result<RoutePlan> {
        return safeApiCall {
            // 当前使用模拟路线规划，后续接入高德路线规划 API
            val route = createMockRoute(origin, destination, mode)
            currentRoutePlan = route
            _state.update {
                it.copy(
                    currentRoute = route,
                    currentStepIndex = 0
                )
            }
            route
        }
    }

    override fun getCurrentRoute(): RoutePlan? = currentRoutePlan

    override suspend fun announceRouteOverview(route: RoutePlan): Result<Boolean> {
        return try {
            val voiceText = route.toOverviewVoiceText()
            voiceRepository.speakNavigation(voiceText)
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to announce route overview")
            Result.Error(message = "路线播报失败：${e.message}")
        }
    }

    override suspend fun announceRouteStep(stepIndex: Int): Result<Boolean> {
        val route = currentRoutePlan
            ?: return Result.Error(message = "没有当前路线")

        if (stepIndex < 0 || stepIndex >= route.steps.size) {
            return Result.Error(message = "步骤索引越界")
        }

        return try {
            val voiceTexts = route.toStepVoiceTexts()
            val voiceText = voiceTexts[stepIndex]
            voiceRepository.speakNavigation(voiceText)
            _state.update { it.copy(currentStepIndex = stepIndex) }
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to announce route step")
            Result.Error(message = "步骤播报失败：${e.message}")
        }
    }

    // ==================== 无障碍设施查询 ====================

    override suspend fun searchNearbyFacilities(
        latitude: Double,
        longitude: Double,
        radius: Float,
        facilityTypes: List<FacilityType>
    ): Result<List<AccessibleFacility>> {
        return safeApiCall {
            // 当前使用模拟数据，后续接入高德 POI 搜索 API
            val facilities = createMockFacilities()
            val filtered = if (facilityTypes.isEmpty()) {
                facilities
            } else {
                facilities.filter { it.type in facilityTypes }
            }.take(TripAssistRepository.MAX_FACILITY_RESULTS)

            _state.update { it.copy(nearbyFacilities = filtered) }
            filtered
        }
    }

    override suspend fun announceNearbyFacilities(
        facilities: List<AccessibleFacility>
    ): Result<Boolean> {
        return try {
            if (facilities.isEmpty()) {
                voiceRepository.speakNavigation("附近未找到无障碍设施")
                return Result.Success(true)
            }

            val overview = "附近共找到${facilities.size}个无障碍设施。"
            voiceRepository.speakNavigation(overview)

            // 逐一播报前 5 个
            facilities.take(5).forEach { facility ->
                voiceRepository.speakNavigation(facility.toVoiceText())
            }

            if (facilities.size > 5) {
                voiceRepository.speakNavigation(
                    "还有${facilities.size - 5}个设施，可通过触摸屏幕查看详情。"
                )
            }

            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to announce facilities")
            Result.Error(message = "设施播报失败：${e.message}")
        }
    }

    override fun release() {
        cachedWeather = null
        weatherCacheTime = 0L
        currentRoutePlan = null
        _state.update { TripAssistState() }
    }

    // ==================== 模拟数据（开发阶段使用） ====================

    private fun createMockWeather(cityName: String): WeatherInfo {
        return WeatherInfo(
            cityName = cityName,
            temperature = 24f,
            feelsLike = 26f,
            humidity = 65,
            windSpeed = 3.5f,
            windDirection = "东南风",
            visibility = 8.0f,
            condition = WeatherCondition.CLEAR,
            description = "晴",
            sunriseTime = "06:15",
            sunsetTime = "18:45",
            aqi = 52,
            aqiLevel = "良"
        )
    }

    private fun createMockRoute(
        origin: String,
        destination: String,
        mode: TransportMode
    ): RoutePlan {
        val steps = when (mode) {
            TransportMode.SUBWAY -> listOf(
                RouteStep(
                    instruction = "从$origin 出发，向东南方向步行",
                    voiceInstruction = "从${origin}出发，向东南方向步行",
                    distance = 350f,
                    duration = 300,
                    transportMode = TransportMode.WALKING,
                    startPoint = origin,
                    endPoint = "地铁站入口",
                    isAccessible = true
                ),
                RouteStep(
                    instruction = "进入地铁站，乘坐1号线",
                    voiceInstruction = "进入地铁站，乘坐1号线向东方行驶",
                    distance = 5200f,
                    duration = 780,
                    transportMode = TransportMode.SUBWAY,
                    startPoint = origin + "地铁站",
                    endPoint = destination + "地铁站",
                    lineNumber = "1号线",
                    stationCount = 6,
                    isAccessible = true
                ),
                RouteStep(
                    instruction = "从地铁站出站，步行至目的地",
                    voiceInstruction = "从地铁站出站，沿盲道步行约200米到达${destination}",
                    distance = 200f,
                    duration = 180,
                    transportMode = TransportMode.WALKING,
                    startPoint = destination + "地铁站",
                    endPoint = destination,
                    isAccessible = true
                )
            )
            TransportMode.BUS -> listOf(
                RouteStep(
                    instruction = "从$origin 步行至公交站",
                    voiceInstruction = "从${origin}出发，步行至公交站",
                    distance = 200f,
                    duration = 180,
                    transportMode = TransportMode.WALKING,
                    startPoint = origin,
                    endPoint = "公交站",
                    isAccessible = false
                ),
                RouteStep(
                    instruction = "乘坐42路公交车",
                    voiceInstruction = "乘坐42路公交车，经过8站",
                    distance = 4500f,
                    duration = 1200,
                    transportMode = TransportMode.BUS,
                    lineNumber = "42路",
                    stationCount = 8,
                    isAccessible = false
                ),
                RouteStep(
                    instruction = "下车步行至目的地",
                    voiceInstruction = "下车后步行约150米到达${destination}",
                    distance = 150f,
                    duration = 120,
                    transportMode = TransportMode.WALKING,
                    endPoint = destination,
                    isAccessible = true
                )
            )
            else -> listOf(
                RouteStep(
                    instruction = "从$origin 步行至$destination",
                    voiceInstruction = "从${origin}出发，沿人行道步行前往${destination}",
                    distance = 1500f,
                    duration = 1200,
                    transportMode = TransportMode.WALKING,
                    startPoint = origin,
                    endPoint = destination,
                    isAccessible = true
                )
            )
        }

        val totalDistance = steps.sumOf { it.distance.toDouble() }.toFloat()
        val totalDuration = steps.sumOf { it.duration }

        return RoutePlan(
            origin = origin,
            destination = destination,
            totalDistance = totalDistance,
            totalDuration = totalDuration,
            transportMode = mode,
            steps = steps,
            isAccessibleRoute = mode == TransportMode.SUBWAY,
            accessibilityNotes = if (mode == TransportMode.SUBWAY) {
                listOf("该路线全程无障碍", "地铁站配备盲道和语音提示")
            } else {
                listOf("部分路段可能缺少盲道", "建议使用导盲杖辅助")
            }
        )
    }

    private fun createMockFacilities(): List<AccessibleFacility> {
        return listOf(
            AccessibleFacility(
                name = "中心广场盲道",
                type = FacilityType.TACTILE_PAVING,
                distance = 50f,
                direction = "前方",
                address = "中心广场南侧人行道",
                isOpen = true,
                description = "连续盲道，长约200米"
            ),
            AccessibleFacility(
                name = "地铁A口无障碍电梯",
                type = FacilityType.ACCESSIBLE_ELEVATOR,
                distance = 120f,
                direction = "右前方",
                address = "地铁站A出入口",
                isOpen = true,
                description = "连接地面与站厅层"
            ),
            AccessibleFacility(
                name = "语音信号灯",
                type = FacilityType.AUDIO_SIGNAL,
                distance = 80f,
                direction = "前方",
                address = "中山路与文化路交叉口",
                isOpen = true,
                description = "按下按钮后有语音提示"
            ),
            AccessibleFacility(
                name = "公共卫生间（无障碍）",
                type = FacilityType.ACCESSIBLE_TOILET,
                distance = 200f,
                direction = "左前方",
                address = "中心公园东门",
                isOpen = true,
                description = "配备扶手和呼叫按钮"
            ),
            AccessibleFacility(
                name = "触觉地图",
                type = FacilityType.TACTILE_MAP,
                distance = 150f,
                direction = "右方",
                address = "市民服务中心一楼大厅",
                isOpen = true,
                description = "覆盖周边2公里范围"
            ),
            AccessibleFacility(
                name = "无障碍坡道",
                type = FacilityType.RAMP,
                distance = 300f,
                direction = "前方",
                address = "图书馆正门",
                isOpen = true,
                description = "坡度平缓，两侧有扶手"
            ),
            AccessibleFacility(
                name = "盲文标识牌",
                type = FacilityType.BRAILLE_SIGN,
                distance = 100f,
                direction = "前方",
                address = "医院门诊大厅",
                isOpen = true,
                description = "各科室盲文指引"
            ),
            AccessibleFacility(
                name = "导盲犬休息区",
                type = FacilityType.GUIDE_DOG_AREA,
                distance = 250f,
                direction = "右前方",
                address = "购物中心北门",
                isOpen = true,
                description = "提供饮水设施"
            )
        )
    }

    companion object {
        /** 和风天气 API 基础 URL */
        private const val WEATHER_API_BASE_URL = "https://devapi.qweather.com/"

        /**
         * 和风天气 API Key
         * 从 BuildConfig 读取，配置方式：
         * 1. 在项目根目录的 local.properties 中添加：
         *    WEATHER_API_KEY=your_api_key_here
         * 2. 获取 API Key：https://dev.qweather.com/
         * 
         * 免费版限制：1000次/天，足够个人使用
         */
        private val WEATHER_API_KEY: String
            get() = com.blindpath.module_trip_assist.BuildConfig.WEATHER_API_KEY
    }
}
