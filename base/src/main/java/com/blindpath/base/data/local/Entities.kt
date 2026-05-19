package com.blindpath.base.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 导航历史记录实体
 */
@Entity(tableName = "navigation_history")
data class NavigationHistoryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val destination: String,
    val destinationLat: Double,
    val destinationLng: Double,
    val startLat: Double?,
    val startLng: Double?,
    val distance: Float,
    val duration: Long,
    val timestamp: Long,
    val isCompleted: Boolean = true,
    val routeData: String? = null // JSON 序列化的路线数据
)

/**
 * SOS 联系人实体
 */
@Entity(tableName = "sos_contacts")
data class SosContactEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val phone: String,
    val relationship: String? = null,
    val priority: Int = 0,
    val isEnabled: Boolean = true,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 用户偏好设置实体
 */
@Entity(tableName = "user_preferences")
data class UserPreferenceEntity(
    @PrimaryKey
    val key: String,
    val value: String,
    val updatedAt: Long = System.currentTimeMillis()
)

/**
 * 离线地图区域实体
 */
@Entity(tableName = "offline_map_regions")
data class OfflineMapRegionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String,
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val zoomLevel: Int,
    val downloadStatus: String, // DOWNLOADING, COMPLETED, FAILED
    val downloadedAt: Long? = null,
    val sizeBytes: Long = 0
)

/**
 * 障碍物检测历史实体
 */
@Entity(tableName = "obstacle_detection_history")
data class ObstacleDetectionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val obstacleType: String,
    val confidence: Float,
    val distance: Float,
    val alertLevel: String,
    val latitude: Double?,
    val longitude: Double?,
    val timestamp: Long,
    val imagePath: String? = null
)

/**
 * 应用使用统计实体
 */
@Entity(tableName = "app_usage_stats")
data class AppUsageStatEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: String, // YYYY-MM-DD
    val feature: String,
    val usageCount: Int = 1,
    val totalDurationMs: Long = 0
)
