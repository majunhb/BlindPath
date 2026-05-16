package com.blindpath.module_navigation.data

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.os.Looper
import androidx.core.content.ContextCompat
import com.blindpath.base.common.NavigationInfo
import com.blindpath.base.common.Result
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_navigation.domain.model.NavigationState
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 高精度定位实现 — 专为视障人员步行导航设计
 *
 * 核心改进（Phase 1）：
 * 1. FusedLocationProviderClient（替代 LocationManager）— 自动融合 GPS、传感器、网络定位
 * 2. PRIORITY_HIGH_ACCURACY — 最高精度模式
 * 3. setMinUpdateDistanceMeters(0.5f) — 最小位移 0.5 米
 * 4. setMinUpdateIntervalMillis(1000L) — 最快 1 秒更新一次
 * 5. 实际精度由手机 GNSS 芯片决定，一般高端手机可达到 0.5~3 米
 */
@Singleton
class NavigationRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : NavigationRepository {

    private val _state = MutableStateFlow(NavigationState())
    override val navigationState: StateFlow<NavigationState> = _state.asStateFlow()

    /** Google 高精度定位客户端 */
    private lateinit var fusedLocationClient: FusedLocationProviderClient

    /** 当前定位结果 */
    private var currentLocation: Location? = null

    /** 定位回调 */
    private var locationCallback: LocationCallback? = null

    /** 是否已初始化 */
    private var isInitialized = false

    /** 目的地 */
    private var destination: LatLonPoint? = null

    /** GPS 状态分级（用于语音播报） */
    enum class GpsQuality { EXCELLENT, GOOD, FAIR, POOR }

    override suspend fun startNavigation(): Result<Boolean> {
        return try {
            Timber.d("Starting high-accuracy navigation...")

            // 检查定位权限
            if (!hasLocationPermission()) {
                _state.update { it.copy(lastError = "缺少定位权限，请在设置中授权") }
                return Result.Error(message = "缺少定位权限")
            }

            // 初始化定位
            val initSuccess = initLocationSafe()

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
            it.copy(destinationName = name)
        }
        Timber.d("Destination set: $name ($latitude, $longitude)")
        return Result.Success(true)
    }

    override suspend fun clearDestination(): Result<Boolean> {
        destination = null
        _state.update {
            it.copy(
                destinationName = null,
                currentInfo = null
            )
        }
        return Result.Success(true)
    }

    override fun getCurrentLocation(): Location? = currentLocation

    override fun isLocationAvailable(): Boolean = currentLocation != null

    private fun hasLocationPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 安全初始化高精度定位服务
     * 使用 FusedLocationProviderClient 替代 LocationManager
     */
    @SuppressLint("MissingPermission")
    private fun initLocationSafe(): Boolean {
        if (isInitialized) {
            Timber.d("Location already initialized")
            return true
        }

        try {
            // 获取 FusedLocationProviderClient
            fusedLocationClient = LocationServices.getFusedLocationProviderClient(context)

            // 构建高精度定位请求
            // 关键参数：
            // - Priority.PRIORITY_HIGH_ACCURACY: 优先使用 GPS（精度 0.5~3 米）
            // - setMinUpdateDistanceMeters(0.5f): 最小移动 0.5 米才触发更新
            // - setMinUpdateIntervalMillis(1000L): 最快每秒更新一次
            val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 1000L)
                .setMinUpdateDistanceMeters(0.5f)   // ★ 核心：0.5 米精度
                .setMaxUpdateDelayMillis(3000L)     // 网络延迟容忍
                .build()

            // 创建定位回调
            locationCallback = createLocationCallback()

            // 请求位置更新
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback!!,
                Looper.getMainLooper()
            )

            // 尝试获取上次已知位置（快速响应）
            try {
                fusedLocationClient.lastLocation.addOnSuccessListener { location ->
                    if (location != null && currentLocation == null) {
                        onLocationReceived(location)
                        Timber.d("Using last known location: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m")
                    }
                }
            } catch (e: Exception) {
                Timber.w(e, "Failed to get last known location")
            }

            isInitialized = true
            Timber.d("FusedLocationProviderClient initialized with HIGH_ACCURACY + 0.5m")
            return true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize FusedLocationProviderClient")
            _state.update { it.copy(lastError = "定位初始化失败: ${e.message}") }
            return false
        }
    }

    /**
     * 创建定位回调 — 处理位置更新
     */
    private fun createLocationCallback(): LocationCallback {
        return object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                locationResult.lastLocation?.let { location ->
                    onLocationReceived(location)
                }
            }

            @Deprecated("Deprecated in Java")
            override fun onLocationChanged(location: Location) {
                onLocationReceived(location)
            }

            override fun onLocationAvailability(availability: com.google.android.gms.location.LocationAvailability) {
                if (!availability.isLocationAvailable) {
                    Timber.w("Location not available")
                    _state.update {
                        it.copy(lastError = "GPS 信号弱，请在开阔地带重新定位")
                    }
                } else {
                    _state.update { it.copy(lastError = null) }
                }
            }
        }
    }

    /**
     * 处理收到的新位置
     */
    private fun onLocationReceived(location: Location) {
        currentLocation = location

        val locationInfo = com.blindpath.module_navigation.domain.model.LocationInfo(
            latitude = location.latitude,
            longitude = location.longitude,
            accuracy = location.accuracy,
            speed = location.speed,
            bearing = location.bearing,
            timestamp = location.time
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

        Timber.d("Location updated: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m, GPS quality: ${evaluateGpsQuality(location.accuracy)}")
    }

    /**
     * 评估 GPS 信号质量
     * 用于给视障用户提供清晰的语音反馈
     */
    private fun evaluateGpsQuality(accuracy: Float): GpsQuality {
        return when {
            accuracy <= 1f -> GpsQuality.EXCELLENT   // ≤1米：优秀
            accuracy <= 3f -> GpsQuality.GOOD        // 1~3米：良好
            accuracy <= 10f -> GpsQuality.FAIR       // 3~10米：一般
            else -> GpsQuality.POOR                   // >10米：弱
        }
    }

    /**
     * 停止定位更新
     */
    private fun stopLocationUpdates() {
        try {
            locationCallback?.let { callback ->
                fusedLocationClient.removeLocationUpdates(callback)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error stopping location updates")
        }
        locationCallback = null
        isInitialized = false
    }

    /**
     * 计算并更新导航信息
     */
    private fun updateNavigationInfo(location: Location, destination: LatLonPoint) {
        try {
            val results = FloatArray(2)
            LocationUtils.calculateLineDistance(
                LatLonPoint(location.latitude, location.longitude),
                destination,
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
            kotlin.math.abs(angleDiff) < 30 -> "直行${distance.toInt()}米"
            angleDiff > 30 && angleDiff < 90 -> "前方右转"
            angleDiff > 90 -> "右转后直行"
            angleDiff < -30 && angleDiff > -90 -> "前方左转"
            else -> "左转后直行"
        }
    }
}

// 高德坐标点
class LatLonPoint(val latitude: Double, val longitude: Double)

// 距离计算工具
object LocationUtils {
    fun calculateLineDistance(from: LatLonPoint, to: LatLonPoint, results: FloatArray) {
        android.location.Location.distanceBetween(
            from.latitude, from.longitude,
            to.latitude, to.longitude,
            results
        )
    }
}
