package com.blindpath.base.data.local

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

/**
 * BlindPath 本地数据库
 * 
 * 包含以下表：
 * - navigation_history: 导航历史记录
 * - sos_contacts: SOS 紧急联系人
 * - user_preferences: 用户偏好设置
 * - offline_map_regions: 离线地图区域
 * - obstacle_detection_history: 障碍物检测历史
 * - app_usage_stats: 应用使用统计
 */
@Database(
    entities = [
        NavigationHistoryEntity::class,
        SosContactEntity::class,
        UserPreferenceEntity::class,
        OfflineMapRegionEntity::class,
        ObstacleDetectionEntity::class,
        AppUsageStatEntity::class
    ],
    version = 1,
    exportSchema = true
)
@TypeConverters(RoomTypeConverters::class)
abstract class BlindPathDatabase : RoomDatabase() {
    
    abstract fun navigationHistoryDao(): NavigationHistoryDao
    abstract fun sosContactDao(): SosContactDao
    abstract fun userPreferenceDao(): UserPreferenceDao
    abstract fun offlineMapRegionDao(): OfflineMapRegionDao
    abstract fun obstacleDetectionDao(): ObstacleDetectionDao
    abstract fun appUsageStatsDao(): AppUsageStatsDao
}
