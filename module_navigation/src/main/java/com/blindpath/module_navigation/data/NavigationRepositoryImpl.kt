package com.blindpath.module_navigation.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
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

    // 目的地（示例）
    private var destination: LatLonPoint? = null

    override suspend fun startNavigation(): Result<Boolean> {
        return try {
            // 检查定位权限
            if (!hasLocationPermission()) {
                _state.update { it.copy(lastError = "缺少定位权限") }
                return Result.Error(message = "缺少定位权限")
            }

            // 初始化定位
            initLocation()

            _state.update {
                it.copy(
                    isRunning = true,
                    isLocationAvailable = currentLocation != null,
                    lastError = null
                )
            }

            Timber.d("Navigation started")
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

    private fun initLocation() {
        try {
            locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

            // 检查 GPS 是否开启
            val gpsEnabled = locationManager?.isProviderEnabled(LocationManager.GPS_PROVIDER) ?: false
            val networkEnabled = locationManager?.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ?: false

            if (!gpsEnabled && !networkEnabled) {
                Timber.w("GPS and Network providers are disabled")
                _state.update { it.copy(lastError = "请开启定位服务（设置 > 位置信息）") }
                // 仍然继续，让系统处理
            }

            locationListener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    currentLocation = location

                    // 更新状态
                    _state.update {
                        it.copy(
                            currentLocation = com.blindpath.module_navigation.domain.model.LocationInfo(
                                latitude = location.latitude,
                                longitude = location.longitude,
                                accuracy = location.accuracy,
                                speed = location.speed,
                                bearing = location.bearing,
                                timestamp = location.time
                            ),
                            isLocationAvailable = true,
                            lastError = null
                        )
                    }

                    // 如果有目的地，计算导航信息
                    destination?.let { dest ->
                        updateNavigationInfo(location, dest)
                    }

                    Timber.d("Location updated: ${location.latitude}, ${location.longitude}")
                }

                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }

            // 请求位置更新 - 优先使用 GPS，如果没有则使用网络定位
            if (hasLocationPermission()) {
                if (gpsEnabled) {
                    locationManager?.requestLocationUpdates(
                        LocationManager.GPS_PROVIDER,
                        2000L, // 2秒更新一次
                        1f,
                        locationListener!!
                    )
                } else if (networkEnabled) {
                    locationManager?.requestLocationUpdates(
                        LocationManager.NETWORK_PROVIDER,
                        2000L,
                        1f,
                        locationListener!!
                    )
                } else {
                    // 尝试两个都注册
                    try {
                        locationManager?.requestLocationUpdates(
                            LocationManager.GPS_PROVIDER,
                            2000L, 1f, locationListener!!
                        )
                    } catch (e: Exception) {
                        Timber.w("GPS provider not available: ${e.message}")
                    }
                    try {
                        locationManager?.requestLocationUpdates(
                            LocationManager.NETWORK_PROVIDER,
                            2000L, 1f, locationListener!!
                        )
                    } catch (e: Exception) {
                        Timber.w("Network provider not available: ${e.message}")
                    }
                }
            }
        } catch (e: SecurityException) {
            Timber.e(e, "Location permission denied")
            _state.update { it.copy(lastError = "定位权限被拒绝") }
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize location")
            _state.update { it.copy(lastError = "定位初始化失败: ${e.message}") }
        }
    }

    private fun stopLocationUpdates() {
        locationListener?.let {
            locationManager?.removeUpdates(it)
        }
        locationListener = null
    }

    private fun updateNavigationInfo(location: Location, destination: LatLonPoint) {
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
