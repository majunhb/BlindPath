package com.blindpath.module_navigation.domain

import android.location.Location
import com.blindpath.base.common.Result
import com.blindpath.module_navigation.domain.model.LatLonPoint
import com.blindpath.module_navigation.domain.model.NavigationObstacle
import com.blindpath.module_navigation.domain.model.NavigationState
import kotlinx.coroutines.flow.Flow

interface NavigationRepository {
    val navigationState: Flow<NavigationState>

    suspend fun startNavigation(): Result<Boolean>
    suspend fun stopNavigation(): Result<Boolean>
    suspend fun setDestination(latitude: Double, longitude: Double, name: String): Result<Boolean>
    suspend fun clearDestination(): Result<Boolean>
    fun getCurrentLocation(): Location?
    fun isLocationAvailable(): Boolean

    // 路线规划
    suspend fun planRoute(originLat: Double, originLon: Double, destLat: Double, destLon: Double): Result<Boolean>
    suspend fun geocodeDestination(text: String): Result<LatLonPoint>

    // 步进导航
    suspend fun advanceToNextStep(): Result<Boolean>

    // ★ 障碍物感知数据桥接（由NavigationService调用）
    fun updateObstacleData(
        isActive: Boolean,
        obstacles: List<NavigationObstacle>,
        nearest: NavigationObstacle?,
        alertMessage: String?
    )
}