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
    val timestamp: Long,
    val address: String = "",   // 地址信息
    val poiName: String = "",   // POI名称
    val road: String = "",      // 道路名称
    val street: String = ""     // 街道名称
)

/**
 * 导航状态
 */
data class NavigationState(
    val isRunning: Boolean = false,
    val isLocationAvailable: Boolean = false,
    val currentLocation: LocationInfo? = null,
    val destinationName: String? = null,
    val destinationPoint: LatLonPoint? = null,
    val currentInfo: NavigationInfo? = null,
    val lastError: String? = null,
    // 路线规划相关
    val routeSteps: List<RouteStep> = emptyList(),
    val currentStepIndex: Int = 0,
    val routePolylines: List<List<LatLonPoint>> = emptyList(),
    val isOffRoute: Boolean = false,
    val totalDistance: String = "",
    val totalDuration: String = "",
    val isRoutePlanned: Boolean = false,
    // ★ 障碍物感知数据（由ObstacleRepository桥接）
    val isObstacleDetectionActive: Boolean = false,
    val nearbyObstacles: List<NavigationObstacle> = emptyList(),
    val nearestObstacle: NavigationObstacle? = null,
    val obstacleAlertMessage: String? = null
)

/**
 * 导航级障碍物信息（从ObstacleRepository桥接的精简数据）
 */
data class NavigationObstacle(
    val type: String,           // 障碍物类型中文名（如"行人"、"车辆"、"台阶"）
    val distance: Float,        // 估算距离（米）
    val direction: String,      // 方向（如"正前方"、"左侧"、"右前方"）
    val confidence: Float,      // 置信度 0-1
    val isDangerous: Boolean,   // 是否危险（距离 < 1m）
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 路线步骤
 */
data class RouteStep(
    val instruction: String,
    val distance: String,
    val duration: String,
    val type: String = "",
    val road: String = "",
    val polyline: List<LatLonPoint> = emptyList()
)

/**
 * 离线地图状态
 */
data class OfflineMapState(
    val isDownloading: Boolean = false,
    val currentDownloadCity: String? = null,
    val downloadProgress: Int = 0,          // 0-100
    val hasUpdateAvailable: Boolean = false,
    val lastDownloadSuccess: Boolean? = null,
    val lastError: String? = null
)

/**
 * 离线地图城市信息
 */
data class OfflineMapCityInfo(
    val cityCode: String,
    val cityName: String,
    val province: String,
    val size: Long,                          // 地图大小（字节）
    val isDownloaded: Boolean = false,
    val isDownloading: Boolean = false,
    val progress: Int = 0,                   // 下载进度 0-100
    val hasUpdate: Boolean = false
) {
    /**
     * 获取格式化的大小字符串
     */
    fun getFormattedSize(): String {
        val mb = size / (1024.0 * 1024.0)
        return if (mb >= 1024) {
            String.format("%.1f GB", mb / 1024)
        } else {
            String.format("%.1f MB", mb)
        }
    }
}

/**
 * 离线地图下载状态
 */
enum class OfflineMapDownloadStatus {
    NOT_STARTED,    // 未开始
    DOWNLOADING,    // 下载中
    PAUSED,         // 已暂停
    COMPLETED,      // 已完成
    FAILED,         // 失败
    UPDATING        // 更新中
}

/**
 * 经纬度坐标点
 */
data class LatLonPoint(val latitude: Double, val longitude: Double)

