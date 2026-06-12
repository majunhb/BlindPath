package com.blindpath.module_navigation.data

import com.blindpath.base.data.local.NavigationHistoryDao
import com.blindpath.base.data.local.NavigationHistoryEntity
import com.blindpath.module_navigation.domain.model.LatLonPoint
import com.blindpath.module_navigation.domain.model.RouteStep
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 导航路径缓存管理器
 *
 * 离线优先策略:
 * 1. 每次路径规划成功后，缓存到 Room DB（保留最近3条）
 * 2. 网络不可用时，从缓存加载最近的路径
 * 3. 缓存包含完整步骤和 polyline 数据
 *
 * 利用 NavigationHistoryEntity 现有字段（destination, destinationLat,
 * destinationLng, startLat, startLng, distance, duration）存储基础数据，
 * 通过 routeData JSON 字段存储扩展数据（originName, steps, polylinePoints,
 * 格式化后的距离/时长字符串）。
 */
@Singleton
class NavigationCacheManager @Inject constructor(
    private val navigationHistoryDao: NavigationHistoryDao
) {
    companion object {
        private const val MAX_CACHED_ROUTES = 3
        private const val TAG = "NavigationCache"
    }

    /**
     * 缓存路径规划结果
     *
     * @param originName 起点名称
     * @param originLat 起点纬度
     * @param originLon 起点经度
     * @param destName 目的地名称
     * @param destLat 目的地纬度
     * @param destLon 目的地经度
     * @param steps 路线步骤列表（含格式化后的距离/时长字符串）
     * @param totalDistanceMeters 总距离（原始数值，米）
     * @param totalDurationSeconds 总时长（原始数值，秒）
     * @param polylinePoints 所有步骤的 polyline 坐标点（扁平化列表）
     * @param totalDistanceFormatted 总距离（格式化字符串，如 "500米"）
     * @param totalDurationFormatted 总时长（格式化字符串，如 "5分钟"）
     */
    suspend fun cacheRoute(
        originName: String,
        originLat: Double,
        originLon: Double,
        destName: String,
        destLat: Double,
        destLon: Double,
        steps: List<RouteStep>,
        totalDistanceMeters: Float,
        totalDurationSeconds: Long,
        polylinePoints: List<LatLonPoint>,
        totalDistanceFormatted: String = "${totalDistanceMeters.toInt()}米",
        totalDurationFormatted: String = ""
    ) = withContext(Dispatchers.IO) {
        try {
            // 将步骤列表序列化为 JSON
            val stepsJson = JSONArray().apply {
                steps.forEach { step ->
                    put(JSONObject().apply {
                        put("instruction", step.instruction)
                        put("distance", step.distance)
                        put("duration", step.duration)
                        put("type", step.type)
                        put("road", step.road)
                        // 每步的 polyline 坐标点
                        put("polyline", JSONArray().apply {
                            step.polyline.forEach { pt ->
                                put(JSONObject().apply {
                                    put("lat", pt.latitude)
                                    put("lon", pt.longitude)
                                })
                            }
                        })
                    })
                }
            }

            // 将扁平化的 polyline 坐标点序列化为 JSON
            val polylineJson = JSONArray().apply {
                polylinePoints.forEach { point ->
                    put(JSONObject().apply {
                        put("lat", point.latitude)
                        put("lon", point.longitude)
                    })
                }
            }

            // 构建 routeData JSON，存储扩展信息
            val routeData = JSONObject().apply {
                put("originName", originName)
                put("steps", stepsJson)
                put("polylinePoints", polylineJson)
                put("totalDistanceFormatted", totalDistanceFormatted)
                put("totalDurationFormatted", totalDurationFormatted)
            }.toString()

            val entity = NavigationHistoryEntity(
                id = 0,
                destination = destName,
                destinationLat = destLat,
                destinationLng = destLon,
                startLat = originLat,
                startLng = originLon,
                distance = totalDistanceMeters,
                duration = totalDurationSeconds,
                timestamp = System.currentTimeMillis(),
                isCompleted = true,
                routeData = routeData
            )

            navigationHistoryDao.insert(entity)
            Timber.i("$TAG: Route cached for $originName -> $destName")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to cache route")
        }
    }

    /**
     * 加载最近的缓存路径
     * 按时间倒序获取，尝试匹配目的地（默认 100m 容差内）
     *
     * @param destLat 目标目的地纬度
     * @param destLon 目标目的地经度
     * @param toleranceMeters 目的地匹配容差（米）
     * @return 匹配的缓存路径，若无匹配则返回 null
     */
    suspend fun getCachedRoute(
        destLat: Double,
        destLon: Double,
        toleranceMeters: Float = 100f
    ): CachedNavigationRoute? = withContext(Dispatchers.IO) {
        try {
            val history = navigationHistoryDao.getAllHistoryList()
            if (history.isEmpty()) return@withContext null

            // 按时间倒序排列
            val sortedHistory = history.sortedByDescending { it.timestamp }

            // 尝试精确匹配目的地（容差范围内）
            val match = sortedHistory.firstOrNull { entity ->
                val dist = haversineDistance(
                    destLat, destLon,
                    entity.destinationLat, entity.destinationLng
                )
                dist <= toleranceMeters
            }

            if (match != null) {
                Timber.i("$TAG: Found cached route for destination within ${toleranceMeters}m")
                return@withContext match.toCachedRoute()
            }

            // 降级：返回最近一次导航记录
            val latest = sortedHistory.first()
            Timber.i("$TAG: No exact match, returning latest cached route")
            latest.toCachedRoute()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to load cached route")
            null
        }
    }

    /**
     * 获取所有缓存路径（用于离线状态下的路径选择）
     */
    suspend fun getAllCachedRoutes(): List<CachedNavigationRoute> = withContext(Dispatchers.IO) {
        try {
            val history = navigationHistoryDao.getAllHistoryList()
            history.sortedByDescending { it.timestamp }.map { it.toCachedRoute() }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to load cached routes")
            emptyList()
        }
    }

    /**
     * 获取缓存路径数量
     */
    suspend fun getCachedRouteCount(): Int = withContext(Dispatchers.IO) {
        try {
            navigationHistoryDao.getTotalCount()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to get cached route count")
            0
        }
    }

    /**
     * 清理超过 MAX_CACHED_ROUTES 条的旧缓存
     */
    suspend fun trimCache() = withContext(Dispatchers.IO) {
        try {
            val history = navigationHistoryDao.getAllHistoryList()
            if (history.size > MAX_CACHED_ROUTES) {
                val toDelete = history.sortedBy { it.timestamp }
                    .take(history.size - MAX_CACHED_ROUTES)
                toDelete.forEach { entity ->
                    navigationHistoryDao.delete(entity)
                }
                Timber.i("$TAG: Trimmed ${toDelete.size} old cached routes")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to trim cache")
        }
    }

    // ==================== 工具方法 ====================

    /**
     * Haversine 公式计算两点之间的球面距离（米）
     */
    private fun haversineDistance(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Float {
        val R = 6371000f // 地球半径（米）
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (R * c).toFloat()
    }

    /**
     * 将 NavigationHistoryEntity 转换为 CachedNavigationRoute
     */
    private fun NavigationHistoryEntity.toCachedRoute(): CachedNavigationRoute {
        val routeDataJson = try {
            if (!this.routeData.isNullOrEmpty()) JSONObject(this.routeData) else JSONObject()
        } catch (e: Exception) {
            JSONObject()
        }

        // 从 routeData JSON 中恢复 originName
        val originName = routeDataJson.optString("originName", "Current Location")

        // 恢复格式化字符串
        val totalDistanceFormatted = routeDataJson.optString(
            "totalDistanceFormatted",
            "${this.distance.toInt()}米"
        )
        val totalDurationFormatted = routeDataJson.optString("totalDurationFormatted", "")

        // 从 routeData JSON 中恢复步骤
        val steps = try {
            val stepsArray = routeDataJson.optJSONArray("steps") ?: JSONArray()
            (0 until stepsArray.length()).map { i ->
                val obj = stepsArray.getJSONObject(i)
                // 恢复每步的 polyline 坐标点
                val stepPolyline = try {
                    val polyArray = obj.optJSONArray("polyline") ?: JSONArray()
                    (0 until polyArray.length()).map { j ->
                        val pt = polyArray.getJSONObject(j)
                        LatLonPoint(
                            latitude = pt.optDouble("lat", 0.0),
                            longitude = pt.optDouble("lon", 0.0)
                        )
                    }
                } catch (e: Exception) {
                    emptyList()
                }

                RouteStep(
                    instruction = obj.optString("instruction", ""),
                    distance = obj.optString("distance", "0米"),
                    duration = obj.optString("duration", "0秒"),
                    type = obj.optString("type", "straight"),
                    road = obj.optString("road", ""),
                    polyline = stepPolyline
                )
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse cached steps")
            emptyList()
        }

        // 从 routeData JSON 中恢复扁平化 polyline 坐标点
        val polylinePoints = try {
            val polyArray = routeDataJson.optJSONArray("polylinePoints") ?: JSONArray()
            (0 until polyArray.length()).map { i ->
                val obj = polyArray.getJSONObject(i)
                LatLonPoint(
                    latitude = obj.optDouble("lat", 0.0),
                    longitude = obj.optDouble("lon", 0.0)
                )
            }
        } catch (e: Exception) {
            emptyList()
        }

        return CachedNavigationRoute(
            originName = originName,
            originLat = this.startLat ?: 0.0,
            originLon = this.startLng ?: 0.0,
            destName = this.destination,
            destLat = this.destinationLat,
            destLon = this.destinationLng,
            steps = steps,
            totalDistanceMeters = this.distance,
            totalDurationSeconds = this.duration,
            totalDistanceFormatted = totalDistanceFormatted,
            totalDurationFormatted = totalDurationFormatted,
            polylinePoints = polylinePoints,
            cachedAt = this.timestamp
        )
    }
}

/**
 * 缓存的导航路径数据
 *
 * @property totalDistanceMeters 总距离（原始数值，米）
 * @property totalDurationSeconds 总时长（原始数值，秒）
 * @property totalDistanceFormatted 总距离（格式化字符串）
 * @property totalDurationFormatted 总时长（格式化字符串）
 */
data class CachedNavigationRoute(
    val originName: String,
    val originLat: Double,
    val originLon: Double,
    val destName: String,
    val destLat: Double,
    val destLon: Double,
    val steps: List<RouteStep>,
    val totalDistanceMeters: Float,
    val totalDurationSeconds: Long,
    val totalDistanceFormatted: String,
    val totalDurationFormatted: String,
    val polylinePoints: List<LatLonPoint>,
    val cachedAt: Long
)