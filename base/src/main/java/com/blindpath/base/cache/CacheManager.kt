package com.blindpath.base.cache

import android.content.Context
import timber.log.Timber
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * 通用缓存管理器
 * 支持内存缓存和磁盘缓存
 */
class CacheManager private constructor(
    private val context: Context,
    private val memoryCacheMaxSize: Int = DEFAULT_MEMORY_CACHE_SIZE,
    private val diskCacheMaxSize: Long = DEFAULT_DISK_CACHE_SIZE
) {
    
    companion object {
        private const val DEFAULT_MEMORY_CACHE_SIZE = 50 // 最多缓存50个对象
        private const val DEFAULT_DISK_CACHE_SIZE = 50L * 1024 * 1024 // 50MB
        private const val CACHE_DIR_NAME = "blindpath_cache"
        
        @Volatile
        private var instance: CacheManager? = null
        
        /**
         * 获取缓存管理器实例
         */
        fun getInstance(context: Context): CacheManager {
            return instance ?: synchronized(this) {
                instance ?: CacheManager(context.applicationContext).also { instance = it }
            }
        }
    }
    
    // 内存缓存
    private val memoryCache = ConcurrentHashMap<String, CacheEntry>()
    
    // 磁盘缓存目录
    private val diskCacheDir: File by lazy {
        File(context.cacheDir, CACHE_DIR_NAME).apply {
            if (!exists()) mkdirs()
        }
    }
    
    /**
     * 缓存条目
     */
    data class CacheEntry(
        val data: Any,
        val timestamp: Long = System.currentTimeMillis(),
        val ttl: Long = Long.MAX_VALUE // 过期时间，默认永不过期
    ) {
        fun isExpired(): Boolean {
            return ttl != Long.MAX_VALUE && System.currentTimeMillis() - timestamp > ttl
        }
    }
    
    /**
     * 存入内存缓存
     */
    fun putMemory(key: String, data: Any, ttl: Long = Long.MAX_VALUE) {
        // 如果超过最大数量，移除最旧的
        if (memoryCache.size >= memoryCacheMaxSize) {
            val oldestKey = memoryCache.entries.minByOrNull { it.value.timestamp }?.key
            oldestKey?.let { memoryCache.remove(it) }
        }
        
        memoryCache[key] = CacheEntry(data, ttl = ttl)
        Timber.d("Memory cache put: $key")
    }
    
    /**
     * 从内存缓存获取
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> getMemory(key: String): T? {
        val entry = memoryCache[key] ?: return null
        
        if (entry.isExpired()) {
            memoryCache.remove(key)
            Timber.d("Memory cache expired: $key")
            return null
        }
        
        return entry.data as? T
    }
    
    /**
     * 存入磁盘缓存
     */
    fun putDisk(key: String, data: ByteArray): Boolean {
        return try {
            val file = getDiskCacheFile(key)
            
            // 检查磁盘缓存大小
            cleanupDiskCacheIfNeeded(data.size.toLong())
            
            file.writeBytes(data)
            Timber.d("Disk cache put: $key, size: ${data.size}")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to write disk cache: $key")
            false
        }
    }
    
    /**
     * 从磁盘缓存获取
     */
    fun getDisk(key: String): ByteArray? {
        return try {
            val file = getDiskCacheFile(key)
            if (!file.exists()) return null
            
            file.readBytes().also {
                Timber.d("Disk cache get: $key, size: ${it.size}")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to read disk cache: $key")
            null
        }
    }
    
    /**
     * 存入磁盘缓存（字符串）
     */
    fun putDiskString(key: String, data: String): Boolean {
        return putDisk(key, data.toByteArray(Charsets.UTF_8))
    }
    
    /**
     * 从磁盘缓存获取（字符串）
     */
    fun getDiskString(key: String): String? {
        return getDisk(key)?.toString(Charsets.UTF_8)
    }
    
    /**
     * 检查缓存是否存在
     */
    fun containsMemory(key: String): Boolean {
        val entry = memoryCache[key] ?: return false
        if (entry.isExpired()) {
            memoryCache.remove(key)
            return false
        }
        return true
    }
    
    fun containsDisk(key: String): Boolean {
        return getDiskCacheFile(key).exists()
    }
    
    /**
     * 移除缓存
     */
    fun removeMemory(key: String) {
        memoryCache.remove(key)
        Timber.d("Memory cache removed: $key")
    }
    
    fun removeDisk(key: String) {
        getDiskCacheFile(key).delete()
        Timber.d("Disk cache removed: $key")
    }
    
    /**
     * 清除所有缓存
     */
    fun clearMemory() {
        memoryCache.clear()
        Timber.d("Memory cache cleared")
    }
    
    fun clearDisk() {
        diskCacheDir.listFiles()?.forEach { it.delete() }
        Timber.d("Disk cache cleared")
    }
    
    fun clearAll() {
        clearMemory()
        clearDisk()
    }
    
    /**
     * 获取缓存大小
     */
    fun getDiskCacheSize(): Long {
        return diskCacheDir.listFiles()?.sumOf { it.length() } ?: 0L
    }
    
    /**
     * 获取缓存统计
     */
    fun getStats(): CacheStats {
        return CacheStats(
            memoryCount = memoryCache.size,
            memoryMaxSize = memoryCacheMaxSize,
            diskSize = getDiskCacheSize(),
            diskMaxSize = diskCacheMaxSize
        )
    }
    
    private fun getDiskCacheFile(key: String): File {
        val hashKey = md5(key)
        return File(diskCacheDir, hashKey)
    }
    
    private fun md5(input: String): String {
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }
    
    private fun cleanupDiskCacheIfNeeded(newDataSize: Long) {
        var totalSize = getDiskCacheSize() + newDataSize
        
        if (totalSize > diskCacheMaxSize) {
            // 按最后修改时间排序，删除最旧的
            val files = diskCacheDir.listFiles()?.sortedBy { it.lastModified() } ?: return
            
            for (file in files) {
                if (totalSize <= diskCacheMaxSize * 0.8) break // 清理到80%以下
                totalSize -= file.length()
                file.delete()
                Timber.d("Cleaned up old cache file: ${file.name}")
            }
        }
    }
    
    /**
     * 缓存统计
     */
    data class CacheStats(
        val memoryCount: Int,
        val memoryMaxSize: Int,
        val diskSize: Long,
        val diskMaxSize: Long
    ) {
        val memoryUsagePercent: Float
            get() = memoryCount.toFloat() / memoryMaxSize * 100
        
        val diskUsagePercent: Float
            get() = diskSize.toFloat() / diskMaxSize * 100
        
        val diskSizeMB: Float
            get() = diskSize.toFloat() / 1024 / 1024
    }
}

/**
 * 缓存键定义
 */
object CacheKeys {
    // 模型相关
    const val MODEL_YOLOV8N = "model_yolov8n"
    const val MODEL_YOLOV8N_METADATA = "model_yolov8n_metadata"
    
    // 导航相关
    const val NAVIGATION_ROUTE_PREFIX = "nav_route_"
    const val NAVIGATION_POI_PREFIX = "nav_poi_"
    const val LAST_KNOWN_LOCATION = "last_known_location"
    
    // 用户设置
    const val USER_SETTINGS = "user_settings"
    const val VOICE_SETTINGS = "voice_settings"
    
    // 社区相关
    const val COMMUNITY_POSTS_PREFIX = "community_posts_"
    const val USER_PROFILE_PREFIX = "user_profile_"
    
    fun navigationRoute(routeId: String) = "${NAVIGATION_ROUTE_PREFIX}$routeId"
    fun navigationPoi(lat: Double, lng: Double) = "${NAVIGATION_POI_PREFIX}${lat}_$lng"
    fun communityPosts(page: Int) = "${COMMUNITY_POSTS_PREFIX}$page"
    fun userProfile(userId: String) = "${USER_PROFILE_PREFIX}$userId"
}
