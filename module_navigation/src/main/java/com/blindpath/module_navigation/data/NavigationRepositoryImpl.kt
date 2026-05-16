package com.blindpath.module_navigation.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.blindpath.base.common.NavigationInfo
import com.blindpath.base.common.Result
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

@Singleton
class NavigationRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : NavigationRepository {

    private val _state = MutableStateFlow(NavigationState())
    override val navigationState: StateFlow<NavigationState> = _state.asStateFlow()

    private var locationManager: LocationManager? = null
    private var currentLocation: Location? = null
    private var locationListener: LocationListener? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 目的地（示例）
    private var destination: LatLonPoint? = null
    
    private var isInitialized = false

    override suspend fun startNavigation(): Result<Boolean> {
        return try {
            Timber.d("Starting navigation...")
            
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
     * 安全初始化定位服务
     */
    private fun initLocationSafe(): Boolean {
        if (isInitialized) {
            Timber.d("Location already initialized")
            return true
        }
        
        try {
            // 获取LocationManager
            val lm = context.getSystemService(Context.LOCATION_SERVICE)
            if (lm == null || lm !is LocationManager) {
                Timber.e("Failed to get LocationManager")
                _state.update { it.copy(lastError = "无法获取定位服务") }
                return false
            }
            locationManager = lm

            // 检查 GPS 是否开启
            val gpsEnabled = try {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER)
            } catch (e: Exception) {
                Timber.w(e, "GPS provider not available")
                false
            }

            val networkEnabled = try {
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            } catch (e: Exception) {
                Timber.w(e, "Network provider not available")
                false
            }

            if (!gpsEnabled && !networkEnabled) {
                Timber.w("GPS and Network providers are disabled")
                _state.update { it.copy(lastError = "请开启定位服务（设置 > 位置信息）") }
                // 继续尝试，不返回
            }

            // 创建位置监听器
            locationListener = createLocationListener()

            // 请求位置更新
            var registered = false
            
            if (hasLocationPermission()) {
                // 尝试GPS
                if (gpsEnabled) {
                    try {
                        lm.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            2000L, // 2秒更新一次
                            1f,
                            locationListener!!,
                            Looper.getMainLooper()
                        )
                        registered = true
                        Timber.d("GPS provider registered")
                    } catch (e: SecurityException) {
                        Timber.e(e, "SecurityException registering GPS")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to register GPS")
                    }
                }

                // 尝试网络定位
                if (networkEnabled) {
                    try {
                        lm.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            2000L,
                            1f,
                            locationListener!!,
                            Looper.getMainLooper()
                        )
                        registered = true
                        Timber.d("Network provider registered")
                    } catch (e: SecurityException) {
                        Timber.e(e, "SecurityException registering Network")
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to register Network")
                    }
                }

                // 如果都没成功，尝试手动获取上次已知位置
                if (!registered) {
                    try {
                        val lastLocation = lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                            ?: lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
                        
                        if (lastLocation != null) {
                            onLocationReceived(lastLocation)
                            registered = true
                            Timber.d("Using last known location")
                        }
                    } catch (e: Exception) {
                        Timber.w(e, "Failed to get last known location")
                    }
                }
            }

            isInitialized = true
            return registered
        } catch (e: SecurityException) {
            Timber.e(e, "Location permission denied")
            _state.update { it.copy(lastError = "定位权限被拒绝，请在设置中授权") }
            return false
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize location")
            _state.update { it.copy(lastError = "定位初始化失败: ${e.message}") }
            return false
        }
    }

    private fun createLocationListener(): LocationListener {
        return object : LocationListener {
            override fun onLocationChanged(location: Location) {
                try {
                    onLocationReceived(location)
                } catch (e: Exception) {
                    Timber.e(e, "Error processing location update")
                }
            }

            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {
                Timber.d("Provider status changed: $provider, status: $status")
            }

            override fun onProviderEnabled(provider: String) {
                Timber.d("Provider enabled: $provider")
                _state.update { it.copy(lastError = null) }
            }

            override fun onProviderDisabled(provider: String) {
                Timber.w("Provider disabled: $provider")
            }
        }
    }

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

        Timber.d("Location updated: ${location.latitude}, ${location.longitude}, accuracy: ${location.accuracy}m")
    }

    private fun stopLocationUpdates() {
        try {
            locationListener?.let { listener ->
                locationManager?.removeUpdates(listener)
            }
        } catch (e: Exception) {
            Timber.w(e, "Error stopping location updates")
        }
        locationListener = null
        isInitialized = false
    }

    private fun updateNavigationInfo(location: Location, destination: LatLonPoint) {
        try {
            // 计算距离和方向
            val results = FloatArray(2)
            LocationUtils.calculateLineDistance(
                LatLonPoint(location.latitude, location.longitude),
                destination,
                results
            )

            // 简化计算
            val distance = results[0]
            val bearing = results[1]

            // 估算剩余时间（假设步行速度1.2m/s）
            val remainingSeconds = if (distance > 0) (distance / 1.2f).toInt() else 0

            // 生成导航指令
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
