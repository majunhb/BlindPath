package com.blindpath.module_navigation.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.services.core.LatLonPoint as AMapLatLonPoint
import com.amap.api.services.geocoder.GeocodeQuery
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.route.BusRouteResult
import com.amap.api.services.route.DriveRouteResult
import com.amap.api.services.route.RideRouteResult
import com.amap.api.services.route.RouteSearch
import com.amap.api.services.route.WalkRouteResult
import com.blindpath.base.common.NavigationInfo
import com.blindpath.base.common.Result
import com.blindpath.base.config.AppConfig
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_navigation.domain.model.LatLonPoint
import com.blindpath.module_navigation.domain.model.RouteStep
import com.blindpath.module_navigation.domain.model.NavigationState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import javax.inject.Inject
import kotlin.math.*
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.*

/**
 * 高精度导航实现 -- 基于高德地图SDK
 *
 * 专为视障人员步行导航设计，核心特性：
 * 1. 高德融合定位（GPS + 网络 + 基站 + 传感器）
 * 2. 定位精度可达 0.5~3 米（高端手机）
 * 3. 连续定位模式，实时更新位置
 * 4. GPS 质量分级语音反馈
 * 5. 高德地理编码（目的地文本 -> 坐标）
 * 6. 高德步行路线规划
 * 7. 偏航检测与自动步进
 */
@Singleton
class NavigationRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : NavigationRepository {

    private val _state = MutableStateFlow(NavigationState())
    override val navigationState: StateFlow<NavigationState> = _state.asStateFlow()

    /** 高德定位客户端 */
    private var locationClient: AMapLocationClient? = null

    /** 当前定位结果 */
    private var currentLocation: Location? = null
    private var currentAMapLocation: AMapLocation? = null

    /** 是否已初始化 */
    private var isInitialized = false

    /** 目的地 */
    private var destination: LatLonPoint? = null

    // ==================== 导航生命周期 ====================

    override suspend fun startNavigation(): Result<Boolean> {
        return try {
            Timber.d("Starting AMap navigation...")

            // 检查定位权限
            if (!hasLocationPermission()) {
                _state.update { it.copy(lastError = "缺少定位权限，请在设置中授权") }
                return Result.Error(message = "缺少定位权限")
            }

            // 初始化高德定位
            val initSuccess = initAMapLocation()

            _state.update {
                it.copy(
                    isRunning = true,
                    isLocationAvailable = currentLocation != null,
                    lastError = if (initSuccess) null else "定位服务启动失败"
                )
            }

            Timber.d("Navigation started, location available: ${currentLocation != null}")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to start navigation")
            _state.update { it.copy(lastError = e.message) }
            Result.Error(message = e.message ?: "导航启动失败")
        }
    }

    override suspend fun stopNavigation(): Result<Boolean> {
        return try {
            stopLocationUpdates()
            _state.update {
                it.copy(
                    isRunning = false,
                    isLocationAvailable = false,
                    currentInfo = null
                )
            }
            Timber.d("Navigation stopped")
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(message = e.message ?: "导航停止失败")
        }
    }

    override suspend fun setDestination(latitude: Double, longitude: Double, name: String): Result<Boolean> {
        destination = LatLonPoint(latitude, longitude)
        _state.update {
            it.copy(
                destinationName = name,
                destinationPoint = LatLonPoint(latitude, longitude)
            )
        }
        Timber.d("Destination set: $name ($latitude, $longitude)")
        return Result.Success(true)
    }

    override suspend fun clearDestination(): Result<Boolean> {
        destination = null
        _state.update {
            it.copy(
                destinationName = null,
                destinationPoint = null,
                currentInfo = null,
                routeSteps = emptyList(),
                currentStepIndex = 0,
                routePolylines = emptyList(),
                isOffRoute = false,
                totalDistance = "",
                totalDuration = "",
                isRoutePlanned = false
            )
        }
        return Result.Success(true)
    }

    override fun getCurrentLocation(): Location? = currentLocation

    override fun isLocationAvailable(): Boolean = currentLocation != null

    // ==================== 高德地理编码 ====================

    override suspend fun geocodeDestination(text: String): Result<LatLonPoint> {
        return withTimeoutOrNull(20000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    Timber.d("Geocoding destination: $text")
                    val search = GeocodeSearch(context)
                    search.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                        override fun onRegeocodeSearched(p0: RegeocodeResult?, p1: Int) {}
                        override fun onGeocodeSearched(result: GeocodeResult?, code: Int) {
                            if (code == 1000 && result != null && result.geocodeAddressList.isNotEmpty()) {
                                val address = result.geocodeAddressList[0]
                                val point = address.latLonPoint
                                Timber.d("Geocode success: (${point.latitude}, ${point.longitude})")
                                cont.resume(Result.Success(LatLonPoint(point.latitude, point.longitude)))
                            } else {
                                Timber.e("Geocode failed: code=$code, result=$result, query=$text")
                                cont.resume(Result.Error(message = "地理编码失败: 无法识别地址 '$text'"))
                            }
                        }
                    })

                    // 先尝试不带城市参数
                    val query = GeocodeQuery(text, "")
                    search.getFromLocationNameAsyn(query)

                    cont.invokeOnCancellation {
                        Timber.d("Geocode cancelled: $text")
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Geocode exception for: $text")
                    cont.resume(Result.Error(message = e.message ?: "地理编码异常"))
                }
            }
        } ?: Result.Error(message = "地理编码超时")
    }

    // ==================== 高德步行路线规划 ====================

    override suspend fun planRoute(
        originLat: Double, originLon: Double,
        destLat: Double, destLon: Double
    ): Result<Boolean> {
        return withTimeoutOrNull(15000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    val origin = AMapLatLonPoint(originLat, originLon)
                    val dest = AMapLatLonPoint(destLat, destLon)
                    val routeSearch = RouteSearch(context)
                    val query = RouteSearch.WalkRouteQuery(RouteSearch.FromAndTo(origin, dest))

                    routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                        override fun onBusRouteSearched(p0: BusRouteResult?, p1: Int) {}
                        override fun onDriveRouteSearched(p0: DriveRouteResult?, p1: Int) {}
                        override fun onRideRouteSearched(p0: RideRouteResult?, p1: Int) {}
                        override fun onWalkRouteSearched(result: WalkRouteResult?, code: Int) {
                            if (code == 1000 && result != null && result.paths != null && result.paths.isNotEmpty()) {
                                val path = result.paths[0]
                                val steps = path.steps
                                if (steps != null && steps.isNotEmpty()) {
                                    val navSteps = steps.map { s ->
                                        RouteStep(
                                            instruction = s.instruction ?: "继续前行",
                                            distance = "${s.distance.toInt()}米",
                                            duration = formatDuration(s.duration.toInt()),
                                            type = parseStepType(s.action),
                                            road = s.road ?: "",
                                            polyline = (s.polyline?.map {
                                                LatLonPoint(it.latitude, it.longitude)
                                            } ?: emptyList())
                                        )
                                    }

                                    // 保存每步的 polyline 坐标点列表
                                    val polylines = steps.map { step ->
                                        step.polyline?.map {
                                            LatLonPoint(it.latitude, it.longitude)
                                        } ?: emptyList()
                                    }

                                    _state.update {
                                        it.copy(
                                            routeSteps = navSteps,
                                            currentStepIndex = 0,
                                            routePolylines = polylines,
                                            isRoutePlanned = true,
                                            totalDistance = "${path.distance.toInt()}米",
                                            totalDuration = formatDuration(path.duration.toInt())
                                        )
                                    }

                                    Timber.d("Route planned: ${navSteps.size} steps, " +
                                            "distance=${path.distance.toInt()}m, " +
                                            "duration=${formatDuration(path.duration.toInt())}")
                                    cont.resume(Result.Success(true))
                                } else {
                                    Timber.w("Route plan returned empty steps")
                                    cont.resume(Result.Error(message = "路线规划结果为空"))
                                }
                            } else {
                                Timber.e("Walk route search failed: code=$code")
                                cont.resume(Result.Error(message = "路线规划失败，请检查网络或目的地"))
                            }
                        }
                    })
                    routeSearch.calculateWalkRouteAsyn(query)
                    cont.invokeOnCancellation {}
                } catch (e: Exception) {
                    Timber.e(e, "Route planning exception")
                    cont.resume(Result.Error(message = e.message ?: "路线规划异常"))
                }
            }
        } ?: Result.Error(message = "路线规划超时")
    }

    // ==================== 偏航检测 ====================

    /**
     * 偏航检测：计算用户到路线最近点距离，超过50米判定偏航
     *
     * @return true 表示发生了偏航
     */
    fun checkOffRoute(userLat: Double, userLon: Double): Boolean {
        val state = _state.value
        if (!state.isRunning || state.routePolylines.isEmpty()) return false

        var minDistance = Float.MAX_VALUE
        for (stepPoints in state.routePolylines) {
            for (point in stepPoints) {
                val d = haversineDistance(userLat, userLon, point.latitude, point.longitude)
                if (d < minDistance) minDistance = d
            }
        }

        return if (minDistance > OFF_ROUTE_THRESHOLD_METERS && !state.isOffRoute) {
            _state.update { it.copy(isOffRoute = true) }
            Timber.w("Off route detected! Min distance to route: ${minDistance}m")
            true
        } else if (minDistance <= OFF_ROUTE_THRESHOLD_METERS) {
            if (state.isOffRoute) {
                _state.update { it.copy(isOffRoute = false) }
            }
            false
        } else {
            false
        }
    }

    /**
     * 自动步进：当用户接近当前步骤终点时自动推进到下一步
     *
     * @return true 表示发生了步进
     */
    fun checkAutoAdvance(userLat: Double, userLon: Double): Boolean {
        val state = _state.value
        if (!state.isRunning || state.currentStepIndex >= state.routeSteps.size) return false

        val stepPoints = state.routePolylines.getOrNull(state.currentStepIndex)
        if (stepPoints.isNullOrEmpty()) return false

        val endPoint = stepPoints.last()
        val distance = haversineDistance(userLat, userLon, endPoint.latitude, endPoint.longitude)

        if (distance < AUTO_ADVANCE_THRESHOLD_METERS) {
            advanceToNextStep()
            return true
        }
        return false
    }

    /**
     * 推进到下一步
     */
    private fun advanceToNextStep() {
        val state = _state.value
        if (state.currentStepIndex < state.routeSteps.size - 1) {
            _state.update { it.copy(currentStepIndex = it.currentStepIndex + 1) }
            Timber.d("Auto advanced to step ${state.currentStepIndex + 1}/${state.routeSteps.size}")
        } else {
            // 已到达最后一步，导航完成
            _state.update {
                it.copy(
                    isRunning = false,
                    currentStepIndex = it.routeSteps.size
                )
            }
            Timber.d("Navigation completed - reached destination")
        }
    }

    // ==================== 定位相关 ====================

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 初始化高德定位服务
     */
    private fun initAMapLocation(): Boolean {
        if (isInitialized) {
            Timber.d("Location already initialized")
            return true
        }

        try {
            // 设置隐私合规（高德SDK要求）
            AMapLocationClient.updatePrivacyShow(context, true, true)
            AMapLocationClient.updatePrivacyAgree(context, true)

            // 创建定位客户端
            locationClient = AMapLocationClient(context)

            // 配置定位参数
            val option = AMapLocationClientOption().apply {
                // 高精度定位模式（GPS + 网络 + 基站）
                locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy

                // 连续定位
                locationPurpose = AMapLocationClientOption.AMapLocationPurpose.Transport

                // 定位间隔（毫秒）
                interval = AppConfig.Navigation.LOCATION_UPDATE_INTERVAL_MS

                // 返回地址信息
                isNeedAddress = true

                // 返回逆地理编码
                geoLanguage = AMapLocationClientOption.GeoLanguage.DEFAULT

                // 缓存定位
                isLocationCacheEnable = true

                // 超时时间
                httpTimeOut = 20000

                // 关闭单次定位
                isOnceLocation = false
            }

            locationClient?.setLocationOption(option)
            locationClient?.setLocationListener(createLocationListener())
            locationClient?.startLocation()

            isInitialized = true
            Timber.d("AMap location client initialized with HIGH_ACCURACY mode")
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize AMap location client")
            _state.update { it.copy(lastError = "定位初始化失败: ${e.message}") }
            return false
        }
    }

    /**
     * 创建高德定位监听器
     */
    private fun createLocationListener(): AMapLocationListener {
        return AMapLocationListener { aMapLocation ->
            if (aMapLocation != null) {
                if (aMapLocation.errorCode == 0) {
                    // 定位成功
                    onLocationReceived(aMapLocation)
                } else {
                    // 定位失败
                    Timber.e("AMap location failed: ${aMapLocation.errorCode} - ${aMapLocation.errorInfo}")
                    _state.update {
                        it.copy(lastError = "定位失败: ${aMapLocation.errorInfo}")
                    }
                }
            }
        }
    }

    /**
     * 处理收到的高德定位结果
     */
    private fun onLocationReceived(aMapLocation: AMapLocation) {
        currentAMapLocation = aMapLocation

        // 转换为标准 Location 对象
        val location = Location("AMap").apply {
            latitude = aMapLocation.latitude
            longitude = aMapLocation.longitude
            accuracy = aMapLocation.accuracy
            speed = aMapLocation.speed
            bearing = aMapLocation.bearing
            time = aMapLocation.time
            altitude = aMapLocation.altitude
        }
        currentLocation = location

        val locationInfo = com.blindpath.module_navigation.domain.model.LocationInfo(
            latitude = aMapLocation.latitude,
            longitude = aMapLocation.longitude,
            accuracy = aMapLocation.accuracy,
            speed = aMapLocation.speed,
            bearing = aMapLocation.bearing,
            timestamp = aMapLocation.time,
            address = aMapLocation.address ?: "",
            poiName = aMapLocation.poiName ?: ""
        )

        _state.update {
            it.copy(
                currentLocation = locationInfo,
                isLocationAvailable = true,
                lastError = null
            )
        }

        // 如果有目的地，计算导航信息
        destination?.let { dest ->
            updateNavigationInfo(location, dest)
        }

        // 偏航检测与自动步进
        if (_state.value.isRoutePlanned) {
            checkOffRoute(aMapLocation.latitude, aMapLocation.longitude)
            checkAutoAdvance(aMapLocation.latitude, aMapLocation.longitude)
        }

        Timber.d("Location updated: ${aMapLocation.latitude}, ${aMapLocation.longitude}, " +
                "accuracy: ${aMapLocation.accuracy}m, GPS quality: ${evaluateGpsQuality(aMapLocation.accuracy)}")
    }

    /**
     * 评估 GPS 信号质量
     */
    private fun evaluateGpsQuality(accuracy: Float): GpsQuality {
        return GpsQuality.fromAccuracy(accuracy)
    }

    /**
     * 停止定位更新
     */
    private fun stopLocationUpdates() {
        try {
            locationClient?.stopLocation()
            locationClient?.onDestroy()
        } catch (e: Exception) {
            Timber.w(e, "Error stopping location updates")
        }
        locationClient = null
        isInitialized = false
    }

    /**
     * 计算并更新导航信息
     */
    private fun updateNavigationInfo(location: Location, destination: LatLonPoint) {
        try {
            val results = FloatArray(2)
            Location.distanceBetween(
                location.latitude, location.longitude,
                destination.latitude, destination.longitude,
                results
            )

            val distance = results[0]
            val bearing = results[1]

            // 假设步行速度 1.2m/s
            val remainingSeconds = if (distance > 0) (distance / 1.2f).toInt() else 0

            val instruction = generateInstruction(location.bearing, bearing, distance)

            _state.update {
                it.copy(
                    currentInfo = NavigationInfo(
                        instruction = instruction,
                        remainingDistance = distance.toInt(),
                        remainingTime = remainingSeconds
                    )
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "Error updating navigation info")
        }
    }

    /**
     * 生成导航指令
     */
    private fun generateInstruction(currentBearing: Float, targetBearing: Float, distance: Float): String {
        val angleDiff = targetBearing - currentBearing
        return when {
            distance < 5f -> "即将到达目的地"
            distance < 20f -> "目的地在前方${distance.toInt()}米"
            abs(angleDiff) < 30 -> "直行${distance.toInt()}米"
            angleDiff > 30 && angleDiff < 90 -> "前方右转"
            angleDiff > 90 -> "右转后直行"
            angleDiff < -30 && angleDiff > -90 -> "前方左转"
            else -> "左转后直行"
        }
    }

    // ==================== 工具方法 ====================

    /**
     * Haversine 公式计算两点之间的球面距离（米）
     *
     * @param lat1 第一个点的纬度
     * @param lon1 第一个点的经度
     * @param lat2 第二个点的纬度
     * @param lon2 第二个点的经度
     * @return 两点之间的距离（米）
     */
    private fun haversineDistance(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Float {
        val R = 6371000.0 // 地球平均半径（米）
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (R * c).toFloat()
    }

    /**
     * 格式化时长
     */
    private fun formatDuration(seconds: Int): String {
        val minutes = seconds / 60
        return if (minutes > 0) "${minutes}分钟" else "${seconds}秒"
    }

    /**
     * 解析步骤类型
     */
    private fun parseStepType(action: String): String = when {
        action.contains("左转") -> "左转"
        action.contains("右转") -> "右转"
        action.contains("直行") -> "直行"
        action.contains("到达") -> "到达"
        else -> "前行"
    }

    companion object {
        /** 偏航判定阈值（米） */
        private const val OFF_ROUTE_THRESHOLD_METERS = 50f

        /** 自动步进阈值（米）- 接近当前步骤终点时自动推进 */
        private const val AUTO_ADVANCE_THRESHOLD_METERS = 20f
    }
}

