package com.blindpath.base.cache

import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * 缓存管理器单元测试
 */
class CacheManagerTest {
    
    @Before
    fun setup() {
        // 重置缓存管理器
        // 注意：实际测试需要Mock Context
    }
    
    @Test
    fun `CacheKeys should generate correct navigation route key`() {
        val routeId = "route_123"
        val key = CacheKeys.navigationRoute(routeId)
        
        assertEquals("nav_route_route_123", key)
    }
    
    @Test
    fun `CacheKeys should generate correct POI key`() {
        val lat = 39.9042
        val lng = 116.4074
        val key = CacheKeys.navigationPoi(lat, lng)
        
        assertTrue(key.startsWith("nav_poi_"))
        assertTrue(key.contains("39.9042"))
        assertTrue(key.contains("116.4074"))
    }
    
    @Test
    fun `CacheKeys should generate correct community posts key`() {
        val page = 1
        val key = CacheKeys.communityPosts(page)
        
        assertEquals("community_posts_1", key)
    }
    
    @Test
    fun `CacheEntry should detect expired entries`() {
        // 未过期的条目
        val notExpiredEntry = CacheManager.CacheEntry(
            data = "test",
            timestamp = System.currentTimeMillis(),
            ttl = Long.MAX_VALUE
        )
        assertFalse(notExpiredEntry.isExpired())
        
        // 已过期的条目
        val expiredEntry = CacheManager.CacheEntry(
            data = "test",
            timestamp = System.currentTimeMillis() - 5000,
            ttl = 1000
        )
        assertTrue(expiredEntry.isExpired())
    }
    
    @Test
    fun `CacheStats should calculate correct percentages`() {
        val stats = CacheManager.CacheStats(
            memoryCount = 25,
            memoryMaxSize = 50,
            diskSize = 25 * 1024 * 1024, // 25MB
            diskMaxSize = 50 * 1024 * 1024 // 50MB
        )
        
        assertEquals(50f, stats.memoryUsagePercent, 0.01f)
        assertEquals(50f, stats.diskUsagePercent, 0.01f)
        assertEquals(25f, stats.diskSizeMB, 0.01f)
    }
}
