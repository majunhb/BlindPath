package com.blindpath.module_navigation.domain.model

import com.blindpath.base.common.NavigationInfo

/**
 * 位置信息
 */
data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,    // 精度（米）
    val speed: Float,       // 速度（m/s）
    val bearing: Float,     // 方向（度）
    val timestamp: Long
)

/**
 * 导航状态
 */
data class NavigationState(
    val isRunning: Boolean = false,
    val isLocationAvailable: Boolean = false,
    val currentLocation: LocationInfo? = null,
    val destinationName: String? = null,
    val currentInfo: NavigationInfo? = null,
    val lastError: String? = null
)
