package com.blindpath.base.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * 导航历史 DAO
 */
@Dao
interface NavigationHistoryDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(history: NavigationHistoryEntity): Long
    
    @Query("SELECT * FROM navigation_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<NavigationHistoryEntity>>
    
    @Query("SELECT * FROM navigation_history ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecentHistory(limit: Int): List<NavigationHistoryEntity>
    
    @Query("SELECT * FROM navigation_history WHERE destination LIKE :query ORDER BY timestamp DESC")
    suspend fun searchHistory(query: String): List<NavigationHistoryEntity>
    
    @Query("SELECT COUNT(*) FROM navigation_history")
    suspend fun getTotalCount(): Int
    
    @Query("DELETE FROM navigation_history WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOldHistory(beforeTimestamp: Long): Int
    
    @Query("DELETE FROM navigation_history")
    suspend fun deleteAll()
}

/**
 * SOS 联系人 DAO
 */
@Dao
interface SosContactDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(contact: SosContactEntity): Long
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(contacts: List<SosContactEntity>)
    
    @Update
    suspend fun update(contact: SosContactEntity)
    
    @Query("SELECT * FROM sos_contacts WHERE isEnabled = 1 ORDER BY priority ASC")
    fun getEnabledContacts(): Flow<List<SosContactEntity>>
    
    @Query("SELECT * FROM sos_contacts ORDER BY priority ASC")
    suspend fun getAllContacts(): List<SosContactEntity>
    
    @Query("SELECT * FROM sos_contacts WHERE id = :id")
    suspend fun getById(id: Long): SosContactEntity?
    
    @Query("DELETE FROM sos_contacts WHERE id = :id")
    suspend fun deleteById(id: Int)
    
    @Query("DELETE FROM sos_contacts")
    suspend fun deleteAll()
}

/**
 * 用户偏好设置 DAO
 */
@Dao
interface UserPreferenceDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun setPreference(preference: UserPreferenceEntity)
    
    @Query("SELECT value FROM user_preferences WHERE key = :key")
    suspend fun getPreference(key: String): String?
    
    @Query("SELECT * FROM user_preferences")
    suspend fun getAllPreferences(): List<UserPreferenceEntity>
    
    @Query("DELETE FROM user_preferences WHERE key = :key")
    suspend fun deletePreference(key: String)
    
    @Query("DELETE FROM user_preferences")
    suspend fun deleteAll()
}

/**
 * 离线地图区域 DAO
 */
@Dao
interface OfflineMapRegionDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(region: OfflineMapRegionEntity): Long
    
    @Update
    suspend fun update(region: OfflineMapRegionEntity)
    
    @Query("SELECT * FROM offline_map_regions WHERE downloadStatus = 'COMPLETED'")
    suspend fun getDownloadedRegions(): List<OfflineMapRegionEntity>
    
    @Query("SELECT * FROM offline_map_regions")
    fun getAllRegions(): Flow<List<OfflineMapRegionEntity>>
    
    @Query("SELECT * FROM offline_map_regions WHERE id = :id")
    suspend fun getById(id: Long): OfflineMapRegionEntity?
    
    @Query("UPDATE offline_map_regions SET downloadStatus = :status, downloadedAt = :timestamp WHERE id = :id")
    suspend fun updateDownloadStatus(id: Long, status: String, timestamp: Long?)
    
    @Query("DELETE FROM offline_map_regions WHERE id = :id")
    suspend fun deleteById(id: Long)
}

/**
 * 障碍物检测历史 DAO
 */
@Dao
interface ObstacleDetectionDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(detection: ObstacleDetectionEntity): Long
    
    @Query("SELECT * FROM obstacle_detection_history ORDER BY timestamp DESC")
    fun getAllDetections(): Flow<List<ObstacleDetectionEntity>>
    
    @Query("SELECT * FROM obstacle_detection_history WHERE timestamp >= :startTime AND timestamp <= :endTime ORDER BY timestamp DESC")
    suspend fun getDetectionsInRange(startTime: Long, endTime: Long): List<ObstacleDetectionEntity>
    
    @Query("SELECT obstacleType, COUNT(*) as count FROM obstacle_detection_history GROUP BY obstacleType ORDER BY count DESC")
    suspend fun getObstacleTypeStats(): List<ObstacleTypeStat>
    
    @Query("DELETE FROM obstacle_detection_history WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOldDetections(beforeTimestamp: Long): Int
}

/**
 * 应用使用统计 DAO
 */
@Dao
interface AppUsageStatsDao {
    
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOrUpdate(stat: AppUsageStatEntity)
    
    @Query("SELECT * FROM app_usage_stats WHERE date = :date")
    suspend fun getStatsForDate(date: String): List<AppUsageStatEntity>
    
    @Query("SELECT feature, SUM(usageCount) as totalCount FROM app_usage_stats GROUP BY feature ORDER BY totalCount DESC")
    suspend fun getFeatureUsageStats(): List<FeatureUsageStat>
    
    @Query("DELETE FROM app_usage_stats WHERE date < :beforeDate")
    suspend fun deleteOldStats(beforeDate: String): Int
}

// 统计结果数据类
data class ObstacleTypeStat(
    val obstacleType: String,
    val count: Int
)

data class FeatureUsageStat(
    val feature: String,
    val totalCount: Int
)
