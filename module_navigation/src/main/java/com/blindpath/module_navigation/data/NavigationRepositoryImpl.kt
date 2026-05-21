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
import com.blindpath.base.common.NavigationInfo
import com.blindpath.base.common.Result
import com.blindpath.base.config.AppConfig
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_navigation.domain.model.NavigationState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 高精度定位实现 — 基于高德地图SDK
 * 
 * 专为视障人员步行导航设计，核心特性：
 * 1. 高德融合定位（GPS + 网络 + 基站 + 传感器）
 * 2. 定位精度可达 0.5~3 米（高端手机）
 * 3. 连续定位模式，实时更新位置
 * 4. GPS 质量分级语音反馈
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
                
                // 最小位移距离（米）
                isLocationCacheEnable = true
                
                // 返回地址信息
                isNeedAddress = true
                
                // 返回逆地理编码
                isGeoLanguage = AMapLocationClientOption.GeoLanguage.DEFAULT
                
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
            kotlin.math.abs(angleDiff) < 30 -> "直行${distance.toInt()}米"
            angleDiff > 30 && angleDiff < 90 -> "前方右转"
            angleDiff > 90 -> "右转后直行"
            angleDiff < -30 && angleDiff > -90 -> "前方左转"
            else -> "左转后直行"
        }
    }
}

/**
 * 坐标点
 */
class LatLonPoint(val latitude: Double, val longitude: Double)
