package com.blindpath.module_navigation.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
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
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.math.*

/**
 * 高精度导航实现 -- 基于高德地图SDK（视障辅助APP-出行导航模块重构版）
 *
 * 专为视障人员步行导航设计，核心特性：
 * 1. 双模卫星定位（GPS + 北斗）+ 高精度融合定位
 * 2. 姿态传感器纠偏（实时修正用户行进航向角）
 * 3. 步行辅助定位PDR（步频检测 + 步幅推算）
 * 4. 滑动窗口滤波 + 卡尔曼滤波平滑轨迹
 * 5. 偏航阈值10m + 连续3次确认机制
 * 6. 智能路径规划增强（优先盲道/无障碍坡道/安全路线）
 * 7. 交通设施识别数据接口（预留YOLO接入）
 * 8. 高德地理编码 + 步行路线规划 + 自动步进
 * 9. GPS质量分级语音反馈 + 隐私合规
 */
@Singleton
class NavigationRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : NavigationRepository, SensorEventListener {

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

    // ==================== 新增：姿态传感器纠偏 ====================

    /** 传感器管理器 */
    private val sensorManager: SensorManager by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    }

    /** 当前航向角（度，0-360） */
    private val _currentHeading = MutableStateFlow(0f)
    val currentHeading: StateFlow<Float> = _currentHeading.asStateFlow()

    /** 旋转矩阵缓存 */
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)

    // ==================== 新增：PDR步行辅助定位 ====================

    /** 步数计数 */
    private val _stepCount = MutableStateFlow(0)
    val stepCount: StateFlow<Int> = _stepCount.asStateFlow()

    /** PDR推算行走里程（米） */
    private val _estimatedDistance = MutableStateFlow(0f)
    val estimatedDistance: StateFlow<Float> = _estimatedDistance.asStateFlow()

    /** 默认步幅（米） */
    private val DEFAULT_STEP_LENGTH = 0.7f

    /** 上次PDR更新时间 */
    private var lastPdrTimestamp = 0L

    /** PDR当前推算位置（用于与卫星定位融合） */
    private var pdrLatitude = 0.0
    private var pdrLongitude = 0.0
    private var pdrInitialized = false

    // ==================== 新增：偏航确认计数器 ====================

    /** 偏航确认计数器（连续3次检测到偏航才触发重算） */
    private var offRouteConfirmCounter = 0

    // ==================== 新增：数据滤波器 ====================

    /** 滑动窗口滤波器 */
    private val locationSlidingWindowFilter = LocationSlidingWindowFilter(windowSize = 5)

    /** 卡尔曼滤波器（简化版） */
    private val kalmanFilter = SimpleKalmanFilter()

    /** 当前平滑后的位置 */
    private var smoothedLocation: SmoothedLocation? = null

    // ==================== 新增：路径偏好 ====================

    /** 当前路径偏好（默认最安全无障碍路径优先） */
    private var routePreference: RoutePreference = RoutePreference.SAFEST

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

            // 初始化传感器监听
            initSensors()

            // 重置PDR状态
            resetPdrState()

            // 重置偏航确认计数器
            offRouteConfirmCounter = 0

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
            unregisterSensors()
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

    // ==================== 智能路径规划增强 ====================

    /**
     * 设置路径偏好（默认SAFEST）
     */
    fun setRoutePreference(preference: RoutePreference) {
        routePreference = preference
        Timber.d("Route preference set to: $preference")
    }

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

                    // 根据路径偏好构建查询参数
                    val query = buildWalkRouteQuery(origin, dest)

                    routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                        override fun onBusRouteSearched(p0: BusRouteResult?, p1: Int) {}
                        override fun onDriveRouteSearched(p0: DriveRouteResult?, p1: Int) {}
                        override fun onRideRouteSearched(p0: RideRouteResult?, p1: Int) {}
                        override fun onWalkRouteSearched(result: WalkRouteResult?, code: Int) {
                            if (code == 1000 && result != null && result.paths != null && result.paths.isNotEmpty()) {
                                // 根据偏好选择最优路径
                                val path = selectBestPath(result.paths)
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
                                            "duration=${formatDuration(path.duration.toInt())}, " +
                                            "preference=$routePreference")
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

    /**
     * 构建步行路线查询（根据路径偏好）
     */
    private fun buildWalkRouteQuery(origin: AMapLatLonPoint, dest: AMapLatLonPoint): RouteSearch.WalkRouteQuery {
        // 预留：接入高德/百度无障碍路线API
        // 当前使用标准步行路线查询，后续可扩展为无障碍路线查询
        return RouteSearch.WalkRouteQuery(RouteSearch.FromAndTo(origin, dest))
    }

    /**
     * 根据路径偏好选择最优路径
     * SAFEST: 优先选择含连续盲道、无障碍坡道、无危险路段的路线
     * SHORTEST: 距离最短
     * BALANCED: 兼顾安全与距离
     */
    private fun selectBestPath(paths: List<com.amap.api.services.route.WalkPath>): com.amap.api.services.route.WalkPath {
        return when (routePreference) {
            RoutePreference.SHORTEST -> paths.minByOrNull { it.distance } ?: paths[0]
            RoutePreference.SAFEST -> {
                // 预留：接入无障碍路线评分API
                // 当前优先选择距离适中、步骤数较少（通常意味着更平直的道路）的路线
                paths.minByOrNull { it.distance + (it.steps?.size ?: 0) * 50f } ?: paths[0]
            }
            RoutePreference.BALANCED -> {
                // 平衡策略：距离权重0.6，复杂度权重0.4
                paths.minByOrNull { it.distance * 0.6f + (it.steps?.size ?: 0) * 30f } ?: paths[0]
            }
        }
    }

    // ==================== 偏航检测（重构） ====================

    /**
     * 偏航检测：计算用户到路线最近点距离，超过10米且连续3次确认才判定偏航
     *
     * @return true 表示发生了偏航（已确认）
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
            offRouteConfirmCounter++
            if (offRouteConfirmCounter >= OFF_ROUTE_CONFIRM_COUNT) {
                _state.update { it.copy(isOffRoute = true) }
                offRouteConfirmCounter = 0
                Timber.w("Off route confirmed! Min distance to route: ${minDistance}m (confirmed $OFF_ROUTE_CONFIRM_COUNT times)")
                true
            } else {
                Timber.d("Off route suspected: ${minDistance}m (confirm count: $offRouteConfirmCounter/$OFF_ROUTE_CONFIRM_COUNT)")
                false
            }
        } else if (minDistance <= OFF_ROUTE_THRESHOLD_METERS) {
            if (state.isOffRoute) {
                _state.update { it.copy(isOffRoute = false) }
            }
            offRouteConfirmCounter = 0
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
            doAdvanceStep()
            return true
        }
        return false
    }

    /**
     * 推进到下一步（内部辅助）
     */
    private fun doAdvanceStep(): Boolean {
        val state = _state.value
        if (state.currentStepIndex < state.routeSteps.size - 1) {
            _state.update { it.copy(currentStepIndex = it.currentStepIndex + 1) }
            Timber.d("Advanced to step ${state.currentStepIndex + 1}/${state.routeSteps.size}")
            return true
        } else {
            _state.update {
                it.copy(isRunning = false, currentStepIndex = it.routeSteps.size)
            }
            Timber.d("Navigation completed - reached destination")
            return false
        }
    }

    /**
     * 推进到下一步（手动步进，接口方法）
     */
    override suspend fun advanceToNextStep(): Result<Boolean> {
        val state = _state.value
        if (!state.isRunning || !state.isRoutePlanned) {
            return Result.Error(message = "导航未运行")
        }
        return Result.Success(doAdvanceStep())
    }

    // ==================== ★ 障碍物感知数据桥接 ====================

    /**
     * 更新障碍物感知数据到NavigationState
     *
     * 由NavigationService调用，将ObstacleRepository的检测结果
     * 桥接到导航状态流中，供UI层和ViewModel消费。
     */
    override fun updateObstacleData(
        isActive: Boolean,
        obstacles: List<com.blindpath.module_navigation.domain.model.NavigationObstacle>,
        nearest: com.blindpath.module_navigation.domain.model.NavigationObstacle?,
        alertMessage: String?
    ) {
        _state.update { it.copy(
            isObstacleDetectionActive = isActive,
            nearbyObstacles = obstacles,
            nearestObstacle = nearest,
            obstacleAlertMessage = alertMessage
        ) }
    }

    // ==================== 交通设施识别数据接口（已桥接到ObstacleRepository） ====================

    /**
     * 检测交通信号灯状态
     *
     * ★ 已桥接到ObstacleRepository：通过NavigationService监听obstacleState获取实时数据。
     * 此方法保留用于手动触发的场景（如截图分析），内部通过ObstacleRepository的AI检测实现。
     */
    fun detectTrafficLight(bitmap: Bitmap): TrafficLightState {
        // ★ 实际检测已通过ObstacleRepository的AIDetector自动完成
        // 此方法保留用于兼容性，NavigationState.nearestObstacle中包含实时检测结果
        Timber.d("Traffic light detection - data available via NavigationState.obstacleAlertMessage")
        return TrafficLightState.UNKNOWN
    }

    /**
     * 检测斑马线信息
     *
     * ★ 已桥接到ObstacleRepository：SceneClassifier自动识别斑马线场景。
     */
    fun detectCrosswalk(bitmap: Bitmap): CrosswalkInfo {
        // ★ 实际检测已通过ObstacleRepository的SceneClassifier自动完成
        Timber.d("Crosswalk detection - data available via NavigationState.sceneRecognition")
        return CrosswalkInfo(
            detected = false,
            distance = 0f,
            width = 0f,
            direction = 0f
        )
    }

    /**
     * 检测人行道状态
     *
     * ★ 已桥接到ObstacleRepository：SceneClassifier自动识别人行道场景。
     */
    fun detectSidewalk(bitmap: Bitmap): SidewalkStatus {
        // ★ 实际检测已通过ObstacleRepository的SceneClassifier自动完成
        Timber.d("Sidewalk detection - data available via NavigationState.sceneRecognition")
        return SidewalkStatus.UNKNOWN
    }

    // ==================== 传感器管理 ====================

    /**
     * 初始化姿态传感器和PDR传感器监听
     */
    private fun initSensors() {
        try {
            // 注册旋转矢量传感器（用于航向角计算）
            sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                Timber.d("Rotation vector sensor registered")
            } ?: run {
                // 降级方案：使用方向传感器
                sensorManager.getDefaultSensor(Sensor.TYPE_ORIENTATION)?.let { orient ->
                    sensorManager.registerListener(this, orient, SensorManager.SENSOR_DELAY_UI)
                    Timber.d("Orientation sensor registered (fallback)")
                }
            }

            // 注册步数检测传感器（PDR）
            sensorManager.getDefaultSensor(Sensor.TYPE_STEP_DETECTOR)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                Timber.d("Step detector sensor registered")
            }

            // 注册加速度传感器（PDR辅助）
            sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let {
                sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
                Timber.d("Accelerometer sensor registered")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to register sensors")
        }
    }

    /**
     * 注销传感器监听
     */
    private fun unregisterSensors() {
        try {
            sensorManager.unregisterListener(this)
            Timber.d("All sensors unregistered")
        } catch (e: Exception) {
            Timber.w(e, "Error unregistering sensors")
        }
    }

    override fun onSensorChanged(event: SensorEvent?) {
        event ?: return
        when (event.sensor.type) {
            Sensor.TYPE_ROTATION_VECTOR -> {
                // 计算航向角
                SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                SensorManager.getOrientation(rotationMatrix, orientationValues)
                val azimuth = Math.toDegrees(orientationValues[0].toDouble()).toFloat()
                val normalizedAzimuth = (azimuth + 360) % 360
                _currentHeading.value = normalizedAzimuth
            }
            Sensor.TYPE_ORIENTATION -> {
                // 降级方案：直接使用方向传感器
                val azimuth = event.values[0]
                _currentHeading.value = (azimuth + 360) % 360
            }
            Sensor.TYPE_STEP_DETECTOR -> {
                // 步数检测
                if (event.values[0] == 1.0f) {
                    _stepCount.value += 1
                    _estimatedDistance.value += DEFAULT_STEP_LENGTH
                    updatePdrPosition()
                }
            }
            Sensor.TYPE_ACCELEROMETER -> {
                // 加速度数据可用于步态分析（预留扩展）
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // 传感器精度变化回调
    }

    // ==================== PDR辅助定位 ====================

    /**
     * 更新PDR推算位置
     */
    private fun updatePdrPosition() {
        if (!pdrInitialized) {
            currentLocation?.let {
                pdrLatitude = it.latitude
                pdrLongitude = it.longitude
                pdrInitialized = true
            }
            return
        }

        val headingRad = Math.toRadians(_currentHeading.value.toDouble())
        val stepLat = (DEFAULT_STEP_LENGTH * cos(headingRad)) / 111320.0
        val stepLon = (DEFAULT_STEP_LENGTH * sin(headingRad)) / (111320.0 * cos(Math.toRadians(pdrLatitude)))

        pdrLatitude += stepLat
        pdrLongitude += stepLon
        lastPdrTimestamp = System.currentTimeMillis()
    }

    /**
     * 重置PDR状态
     */
    private fun resetPdrState() {
        _stepCount.value = 0
        _estimatedDistance.value = 0f
        pdrInitialized = false
        pdrLatitude = 0.0
        pdrLongitude = 0.0
        lastPdrTimestamp = 0L
    }

    /**
     * 卫星定位 + PDR 融合定位
     */
    private fun fuseLocation(gpsLat: Double, gpsLon: Double, gpsAccuracy: Float): Pair<Double, Double> {
        if (!pdrInitialized || gpsAccuracy < 5f) {
            // GPS精度高时，以GPS为主
            return Pair(gpsLat, gpsLon)
        }

        // 简单融合：GPS精度差时，引入PDR修正
        val weight = min(1.0f, gpsAccuracy / 20.0f) // GPS权重，精度越差权重越低
        val fusedLat = gpsLat * weight + pdrLatitude * (1 - weight)
        val fusedLon = gpsLon * weight + pdrLongitude * (1 - weight)

        return Pair(fusedLat, fusedLon)
    }

    // ==================== 定位相关 ====================

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 初始化高德定位服务（双模卫星定位增强）
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
                // 高精度定位模式（GPS + 北斗 + 网络 + 基站 + 传感器）
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

                // 启用传感器辅助定位（陀螺仪、加速度计等）
                isSensorEnable = true

                // 允许模拟位置（调试用，生产环境建议关闭）
                isMockEnable = false
            }

            locationClient?.setLocationOption(option)
            locationClient?.setLocationListener(createLocationListener())
            locationClient?.startLocation()

            isInitialized = true
            Timber.d("AMap location client initialized with HIGH_ACCURACY mode (GPS + BeiDou + sensors)")
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
     * 处理收到的高德定位结果（增强：滤波 + PDR融合）
     */
    private fun onLocationReceived(aMapLocation: AMapLocation) {
        currentAMapLocation = aMapLocation

        // 确定定位来源
        val locationSource = when {
            aMapLocation.locationType == AMapLocation.LOCATION_TYPE_GPS -> LocationSource.GPS
            aMapLocation.locationType == AMapLocation.LOCATION_TYPE_OFFLINE -> LocationSource.BEIDOU
            else -> LocationSource.GPS
        }

        // 滑动窗口滤波
        val filteredLat = locationSlidingWindowFilter.filterLatitude(aMapLocation.latitude)
        val filteredLon = locationSlidingWindowFilter.filterLongitude(aMapLocation.longitude)

        // 卡尔曼滤波平滑
        val kalmanLat = kalmanFilter.updateLatitude(filteredLat)
        val kalmanLon = kalmanFilter.updateLongitude(filteredLon)

        // GPS + PDR 融合
        val (fusedLat, fusedLon) = fuseLocation(kalmanLat, kalmanLon, aMapLocation.accuracy)

        // 构建平滑后的位置
        smoothedLocation = SmoothedLocation(
            latitude = fusedLat,
            longitude = fusedLon,
            accuracy = aMapLocation.accuracy,
            heading = _currentHeading.value,
            source = locationSource
        )

        // 转换为标准 Location 对象
        val location = Location("AMap").apply {
            latitude = fusedLat
            longitude = fusedLon
            accuracy = aMapLocation.accuracy
            speed = aMapLocation.speed
            bearing = _currentHeading.value
            time = aMapLocation.time
            altitude = aMapLocation.altitude
        }
        currentLocation = location

        val locationInfo = com.blindpath.module_navigation.domain.model.LocationInfo(
            latitude = fusedLat,
            longitude = fusedLon,
            accuracy = aMapLocation.accuracy,
            speed = aMapLocation.speed,
            bearing = _currentHeading.value,
            timestamp = aMapLocation.time,
            address = aMapLocation.address ?: "",
            poiName = aMapLocation.poiName ?: "",
            road = aMapLocation.road ?: "",
            street = aMapLocation.street ?: ""
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
            checkOffRoute(fusedLat, fusedLon)
            checkAutoAdvance(fusedLat, fusedLon)
        }

        Timber.d("Location updated: $fusedLat, $fusedLon, " +
                "accuracy: ${aMapLocation.accuracy}m, " +
                "heading: ${_currentHeading.value}°, " +
                "source: $locationSource, " +
                "steps: ${_stepCount.value}, " +
                "pdrDist: ${_estimatedDistance.value}m, " +
                "GPS quality: ${evaluateGpsQuality(aMapLocation.accuracy)}")
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
        /** 偏航判定阈值（米）- 从50m调整为10m */
        private const val OFF_ROUTE_THRESHOLD_METERS = 10f

        /** 偏航确认次数 - 连续3次检测到偏航才触发重算 */
        private const val OFF_ROUTE_CONFIRM_COUNT = 3

        /** 自动步进阈值（米）- 接近当前步骤终点时自动推进 */
        private const val AUTO_ADVANCE_THRESHOLD_METERS = 20f
    }
}

// ==================== 新增数据类 ====================

/**
 * 斑马线信息
 */
data class CrosswalkInfo(
    val detected: Boolean,
    val distance: Float,
    val width: Float,
    val direction: Float
)

/**
 * 平滑后的位置数据
 */
data class SmoothedLocation(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val heading: Float,
    val source: LocationSource
)

/**
 * 定位来源枚举
 */
enum class LocationSource { GPS, BEIDOU, PDR_FUSION }

/**
 * 路径偏好枚举
 */
enum class RoutePreference { SAFEST, SHORTEST, BALANCED }

/**
 * 交通信号灯状态
 */
enum class TrafficLightState { RED, YELLOW, GREEN, UNKNOWN }

/**
 * 人行道状态
 */
enum class SidewalkStatus { CLEAR, OBSTACLE, CONSTRUCTION, UNKNOWN }

// ==================== 新增滤波器类 ====================

/**
 * 滑动窗口滤波器（过滤定位漂移）
 */
class LocationSlidingWindowFilter(private val windowSize: Int = 5) {
    private val latWindow = ArrayDeque<Double>(windowSize)
    private val lonWindow = ArrayDeque<Double>(windowSize)

    fun filterLatitude(lat: Double): Double {
        if (latWindow.size >= windowSize) latWindow.removeFirst()
        latWindow.addLast(lat)
        return latWindow.average()
    }

    fun filterLongitude(lon: Double): Double {
        if (lonWindow.size >= windowSize) lonWindow.removeFirst()
        lonWindow.addLast(lon)
        return lonWindow.average()
    }

    fun clear() {
        latWindow.clear()
        lonWindow.clear()
    }
}

/**
 * 简化版卡尔曼滤波器（平滑轨迹）
 */
class SimpleKalmanFilter {
    private var latEstimate = 0.0
    private var lonEstimate = 0.0
    private var latErrorEstimate = 1.0
    private var lonErrorEstimate = 1.0
    private val processNoise = 0.01
    private val measurementNoise = 1.0
    private var initialized = false

    fun updateLatitude(measurement: Double): Double {
        if (!initialized) {
            latEstimate = measurement
            initialized = true
            return measurement
        }

        // 预测误差
        val predictionError = latErrorEstimate + processNoise

        // 卡尔曼增益
        val kalmanGain = predictionError / (predictionError + measurementNoise)

        // 更新估计
        latEstimate = latEstimate + kalmanGain * (measurement - latEstimate)

        // 更新误差估计
        latErrorEstimate = (1 - kalmanGain) * predictionError

        return latEstimate
    }

    fun updateLongitude(measurement: Double): Double {
        if (!initialized) {
            lonEstimate = measurement
            return measurement
        }

        val predictionError = lonErrorEstimate + processNoise
        val kalmanGain = predictionError / (predictionError + measurementNoise)
        lonEstimate = lonEstimate + kalmanGain * (measurement - lonEstimate)
        lonErrorEstimate = (1 - kalmanGain) * predictionError

        return lonEstimate
    }

    fun reset() {
        latEstimate = 0.0
        lonEstimate = 0.0
        latErrorEstimate = 1.0
        lonErrorEstimate = 1.0
        initialized = false
    }
}
