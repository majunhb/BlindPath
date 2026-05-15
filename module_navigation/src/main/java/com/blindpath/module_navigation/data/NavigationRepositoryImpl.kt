package com.blindpath.module_navigation.data

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.amap.api.location.*
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

    private var aMapLocationClient: AMapLocationClient? = null
    private var aMapLocationListener: AMapLocationListener? = null
    private var currentLocation: AMapLocation? = null

    // 目的地（示例）
    private var destination: LatLonPoint? = null

    override suspend fun startNavigation(): Result<Boolean> {
        return try {
            // 检查定位权限
            if (!hasLocationPermission()) {
                _state.update { it.copy(lastError = "缺少定位权限") }
                return Result.Error(message = "缺少定位权限")
            }

            // 初始化高德定位
            initLocation()

            // 启动定位
            aMapLocationClient?.startLocation()

            _state.update {
                it.copy(
                    isRunning = true,
                    isLocationAvailable = true,
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
            aMapLocationClient?.stopLocation()
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
        aMapLocationClient = AMapLocationClient(context).apply {
            aMapLocationListener = AMapLocationListener { location ->
                if (location != null && location.errorCode == 0) {
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
                            isLocationAvailable = true
                        )
                    }

                    // 如果有目的地，计算导航信息
                    destination?.let { dest ->
                        updateNavigationInfo(location, dest)
                    }

                    Timber.d("Location updated: ${location.latitude}, ${location.longitude}")
                } else {
                    Timber.w("Location error: ${location?.errorInfo}")
                    _state.update {
                        it.copy(
                            isLocationAvailable = false,
                            lastError = location?.errorInfo
                        )
                    }
                }
            }

            locationOption = AMapLocationClientOption().apply {
                locationMode = AMapLocationMode.Hight_Accuracy
                isNeedAddress = false
                isOnceLocation = false
                interval = 2000 // 2秒更新一次
                isOpenGps = true
                isMockEnable = false
            }

            setLocationListener(aMapLocationListener)
        }
    }

    private fun updateNavigationInfo(location: AMapLocation, destination: LatLonPoint) {
        // 计算距离和方向
        val results = FloatArray(2)
        LocationUtils.calculateLineDistance(
            LatLonPoint(location.latitude, location.longitude),
            destination
        )

        // 简化计算（实际应该用高德路径规划API）
        val distance = results[0]
        val bearing = results[1]

        // 估算剩余时间（假设步行速度1.2m/s）
        val remainingSeconds = (distance / 1.2f).toInt()

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
    fun calculateLineDistance(from: LatLonPoint, to: LatLonPoint): Float {
        val results = FloatArray(1)
        android.location.Location.distanceBetween(
            from.latitude, from.longitude,
            to.latitude, to.longitude,
            results
        )
        return results[0]
    }
}
