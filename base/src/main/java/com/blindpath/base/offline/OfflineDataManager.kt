package com.blindpath.base.offline

import android.content.Context
import android.content.SharedPreferences
import com.blindpath.base.cache.CacheKeys
import com.blindpath.base.cache.CacheManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * 离线数据管理器
 * 管理离线所需的数据存储和读取
 */
class OfflineDataManager private constructor(
    private val context: Context,
    private val cacheManager: CacheManager
) {
    
    companion object {
        private const val PREFS_NAME = "offline_data"
        private const val KEY_OFFLINE_MODE = "offline_mode_enabled"
        private const val KEY_LAST_SYNC_TIME = "last_sync_time"
        private const val KEY_CACHED_AREAS = "cached_areas"
        
        @Volatile
        private var instance: OfflineDataManager? = null
        
        fun getInstance(context: Context): OfflineDataManager {
            return instance ?: synchronized(this) {
                instance ?: OfflineDataManager(
                    context.applicationContext,
                    CacheManager.getInstance(context)
                ).also { instance = it }
            }
        }
    }
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val offlineDir: File by lazy {
        File(context.filesDir, "offline_data").apply {
            if (!exists()) mkdirs()
        }
    }
    
    /**
     * 离线模式状态
     */
    var isOfflineModeEnabled: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_MODE, false)
        set(value) {
            prefs.edit().putBoolean(KEY_OFFLINE_MODE, value).apply()
            Timber.d("Offline mode ${if (value) "enabled" else "disabled"}")
        }
    
    /**
     * 最后同步时间
     */
    var lastSyncTime: Long
        get() = prefs.getLong(KEY_LAST_SYNC_TIME, 0)
        set(value) {
            prefs.edit().putLong(KEY_LAST_SYNC_TIME, value).apply()
        }
    
    /**
     * 检查是否有离线数据
     */
    fun hasOfflineData(): Boolean {
        return offlineDir.listFiles()?.isNotEmpty() == true
    }
    
    /**
     * 获取离线数据大小
     */
    fun getOfflineDataSize(): Long {
        return offlineDir.walkTopDown()
            .filter { it.isFile }
            .map { it.length() }
            .sum()
    }
    
    /**
     * 缓存导航路线
     */
    suspend fun cacheNavigationRoute(
        routeId: String,
        routeData: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            val key = CacheKeys.navigationRoute(routeId)
            val success = cacheManager.putDiskString(key, routeData)
            
            if (success) {
                // 记录已缓存的路线
                val cachedRoutes = getCachedRoutes().toMutableSet()
                cachedRoutes.add(routeId)
                prefs.edit().putStringSet("cached_routes", cachedRoutes).apply()
                
                Timber.d("Route cached: $routeId")
            }
            
            Result.success(success)
        } catch (e: Exception) {
            Timber.e(e, "Failed to cache route: $routeId")
            Result.failure(e)
        }
    }
    
    /**
     * 获取缓存的导航路线
     */
    suspend fun getCachedNavigationRoute(routeId: String): String? = withContext(Dispatchers.IO) {
        val key = CacheKeys.navigationRoute(routeId)
        cacheManager.getDiskString(key)
    }
    
    /**
     * 获取所有已缓存的路线ID
     */
    fun getCachedRoutes(): Set<String> {
        return prefs.getStringSet("cached_routes", emptySet()) ?: emptySet()
    }
    
    /**
     * 删除缓存的路线
     */
    fun deleteCachedRoute(routeId: String) {
        val key = CacheKeys.navigationRoute(routeId)
        cacheManager.removeDisk(key)
        
        val cachedRoutes = getCachedRoutes().toMutableSet()
        cachedRoutes.remove(routeId)
        prefs.edit().putStringSet("cached_routes", cachedRoutes).apply()
        
        Timber.d("Route deleted: $routeId")
    }
    
    /**
     * 缓存POI数据
     */
    suspend fun cachePoiData(lat: Double, lng: Double, poiData: String): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                val key = CacheKeys.navigationPoi(lat, lng)
                val success = cacheManager.putDiskString(key, poiData)
                
                if (success) {
                    // 记录已缓存的POI区域
                    addCachedArea(lat, lng)
                }
                
                Result.success(success)
            } catch (e: Exception) {
                Timber.e(e, "Failed to cache POI data")
                Result.failure(e)
            }
        }
    }
    
    /**
     * 获取缓存的POI数据
     */
    suspend fun getCachedPoiData(lat: Double, lng: Double): String? {
        return withContext(Dispatchers.IO) {
            val key = CacheKeys.navigationPoi(lat, lng)
            cacheManager.getDiskString(key)
        }
    }
    
    /**
     * 添加已缓存的区域
     */
    private fun addCachedArea(lat: Double, lng: Double) {
        // 将坐标转换为区域网格（约1km x 1km）
        val gridLat = (lat * 10).toInt()
        val gridLng = (lng * 10).toInt()
        val areaKey = "${gridLat}_$gridLng"
        
        val cachedAreas = getCachedAreas().toMutableSet()
        cachedAreas.add(areaKey)
        prefs.edit().putStringSet(KEY_CACHED_AREAS, cachedAreas).apply()
    }
    
    /**
     * 获取已缓存的区域
     */
    fun getCachedAreas(): Set<String> {
        return prefs.getStringSet(KEY_CACHED_AREAS, emptySet()) ?: emptySet()
    }
    
    /**
     * 检查某区域是否有缓存
     */
    fun hasCachedArea(lat: Double, lng: Double): Boolean {
        val gridLat = (lat * 10).toInt()
        val gridLng = (lng * 10).toInt()
        val areaKey = "${gridLat}_$gridLng"
        
        return getCachedAreas().contains(areaKey)
    }
    
    /**
     * 保存最后已知位置
     */
    fun saveLastKnownLocation(lat: Double, lng: Double, accuracy: Float) {
        prefs.edit()
            .putFloat("last_lat", lat.toFloat())
            .putFloat("last_lng", lng.toFloat())
            .putFloat("last_accuracy", accuracy)
            .putLong("last_location_time", System.currentTimeMillis())
            .apply()
    }
    
    /**
     * 获取最后已知位置
     */
    fun getLastKnownLocation(): LastKnownLocation? {
        val lat = prefs.getFloat("last_lat", Float.MIN_VALUE)
        val lng = prefs.getFloat("last_lng", Float.MIN_VALUE)
        
        if (lat == Float.MIN_VALUE || lng == Float.MIN_VALUE) return null
        
        return LastKnownLocation(
            latitude = lat.toDouble(),
            longitude = lng.toDouble(),
            accuracy = prefs.getFloat("last_accuracy", 0f),
            timestamp = prefs.getLong("last_location_time", 0)
        )
    }
    
    /**
     * 清除所有离线数据
     */
    fun clearAllOfflineData() {
        // 清除缓存
        cacheManager.clearDisk()
        
        // 清除离线目录
        offlineDir.listFiles()?.forEach { it.deleteRecursively() }
        
        // 清除偏好设置
        prefs.edit().clear().apply()
        
        Timber.d("All offline data cleared")
    }
    
    /**
     * 最后已知位置
     */
    data class LastKnownLocation(
        val latitude: Double,
        val longitude: Double,
        val accuracy: Float,
        val timestamp: Long
    )
}
