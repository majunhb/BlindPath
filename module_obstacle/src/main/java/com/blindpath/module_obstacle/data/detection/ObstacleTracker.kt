package com.blindpath.module_obstacle.data.detection

import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.ObstacleType
import timber.log.Timber
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * 障碍物追踪器 - 跨帧匹配同一物体，避免重复播报
 *
 * 核心功能：
 * 1. 为每个检测到的障碍物分配唯一追踪ID
 * 2. 使用IoU + 距离 + 类型匹配跨帧的同一物体
 * 3. 新物体首次出现时才标记为"需要播报"
 * 4. 消失超过一定时间的物体从追踪列表中移除
 *
 * 匹配策略（优先级排序）：
 * 1. 类型相同
 * 2. 边界框IoU > 0.3（或中心点距离 < 阈值）
 * 3. 距离变化 < 2m（防止远距离误匹配）
 */
class ObstacleTracker {

    /** 活跃追踪列表：追踪ID -> 追踪对象 */
    private val activeTracks = mutableMapOf<Int, ObstacleTrack>()

    /** 下一个可用的追踪ID */
    private var nextTrackId = 1

    /** 匹配阈值：IoU > 此值视为同一物体 */
    private val iouThreshold = 0.3f

    /** 中心点距离阈值（像素归一化坐标，0-1范围） */
    private val centerDistanceThreshold = 0.15f

    /** 距离变化阈值（米），超过此值不视为同一物体 */
    private val distanceChangeThreshold = 2.0f

    /** 物体消失超时（毫秒），超过此时间未匹配则移除 */
    private val trackTimeoutMs = 3000L

    /** 新物体首次出现后的保护期（毫秒），此期间不重复播报 */
    private val announceProtectionMs = 5000L

    companion object {
        /** 单例实例 */
        @Volatile
        private var instance: ObstacleTracker? = null

        fun getInstance(): ObstacleTracker {
            return instance ?: synchronized(this) {
                instance ?: ObstacleTracker().also { instance = it }
            }
        }
    }

    /**
     * 更新追踪列表，输入新一帧的检测结果
     *
     * @param detections 当前帧检测到的障碍物
     * @return 追踪结果，包含每个障碍物是否需要播报
     */
    fun update(detections: List<DetectedObstacle>): List<TrackedObstacle> {
        val now = System.currentTimeMillis()

        // 1. 清理超时追踪
        activeTracks.entries.removeIf { (_, track) ->
            now - track.lastSeen > trackTimeoutMs
        }

        // 2. 匹配当前检测结果与已有追踪
        val matchedTracks = mutableSetOf<Int>()
        val results = mutableListOf<TrackedObstacle>()

        for (detection in detections) {
            val bestMatch = findBestMatch(detection, matchedTracks)

            if (bestMatch != null) {
                // 更新已有追踪
                val track = activeTracks[bestMatch]!!
                track.update(detection, now)
                matchedTracks.add(bestMatch)

                results.add(TrackedObstacle(
                    obstacle = detection,
                    trackId = bestMatch,
                    isNew = false,
                    shouldAnnounce = track.shouldAnnounce(now, announceProtectionMs),
                    age = now - track.firstSeen
                ))
            } else {
                // 创建新追踪
                val newId = nextTrackId++
                val newTrack = ObstacleTrack(newId, detection, now)
                activeTracks[newId] = newTrack

                results.add(TrackedObstacle(
                    obstacle = detection,
                    trackId = newId,
                    isNew = true,
                    shouldAnnounce = true,  // 新物体首次出现，需要播报
                    age = 0
                ))
            }
        }

        // 3. 标记未匹配的追踪为"未看到"
        activeTracks.values.forEach { track ->
            if (track.id !in matchedTracks) {
                track.markNotSeen(now)
            }
        }

        Timber.d("ObstacleTracker: ${detections.size} detections, ${activeTracks.size} active tracks, ${results.count { it.isNew }} new")
        return results
    }

    /**
     * 查找最佳匹配追踪
     *
     * 匹配条件（必须全部满足）：
     * 1. 类型相同
     * 2. IoU > 阈值 或 中心点距离 < 阈值
     * 3. 距离变化 < 阈值
     */
    private fun findBestMatch(
        detection: DetectedObstacle,
        alreadyMatched: Set<Int>
    ): Int? {
        var bestId: Int? = null
        var bestScore = 0f

        for ((id, track) in activeTracks) {
            if (id in alreadyMatched) continue
            if (track.obstacle.type != detection.type) continue

            // 计算IoU
            val iou = calculateIoU(track.obstacle, detection)

            // 计算中心点距离
            val centerDist = calculateCenterDistance(track.obstacle, detection)

            // 计算距离变化
            val distanceChange = abs(track.obstacle.distance - detection.distance)

            // 匹配条件
            val isMatch = (iou > iouThreshold || centerDist < centerDistanceThreshold)
                    && distanceChange < distanceChangeThreshold

            if (isMatch) {
                // 使用IoU作为匹配分数，越高越好
                val score = iou + (1f - centerDist) * 0.5f
                if (score > bestScore) {
                    bestScore = score
                    bestId = id
                }
            }
        }

        return bestId
    }

    /**
     * 计算两个障碍物的边界框IoU（使用归一化坐标）
     */
    private fun calculateIoU(a: DetectedObstacle, b: DetectedObstacle): Float {
        val boxA = a.boundingBox
        val boxB = b.boundingBox

        val interLeft = max(boxA.left, boxB.left)
        val interTop = max(boxA.top, boxB.top)
        val interRight = min(boxA.right, boxB.right)
        val interBottom = min(boxA.bottom, boxB.bottom)

        val interWidth = max(0f, interRight - interLeft)
        val interHeight = max(0f, interBottom - interTop)
        val interArea = interWidth * interHeight

        val areaA = (boxA.right - boxA.left) * (boxA.bottom - boxA.top)
        val areaB = (boxB.right - boxB.left) * (boxB.bottom - boxB.top)

        val unionArea = areaA + areaB - interArea
        return if (unionArea > 0) interArea / unionArea else 0f
    }

    /**
     * 计算两个障碍物的中心点距离（归一化坐标）
     */
    private fun calculateCenterDistance(a: DetectedObstacle, b: DetectedObstacle): Float {
        val boxA = a.boundingBox
        val boxB = b.boundingBox

        val cxA = (boxA.left + boxA.right) / 2
        val cyA = (boxA.top + boxA.bottom) / 2
        val cxB = (boxB.left + boxB.right) / 2
        val cyB = (boxB.top + boxB.bottom) / 2

        return sqrt((cxA - cxB) * (cxA - cxB) + (cyA - cyB) * (cyA - cyB))
    }

    /**
     * 重置追踪器（如切换摄像头、重新初始化等场景）
     */
    fun reset() {
        activeTracks.clear()
        nextTrackId = 1
        Timber.d("ObstacleTracker reset")
    }

    /**
     * 获取当前活跃追踪数量
     */
    fun getActiveTrackCount(): Int = activeTracks.size
}

/**
 * 追踪对象内部类 - 维护单个障碍物的追踪状态
 */
private class ObstacleTrack(
    val id: Int,
    var obstacle: DetectedObstacle,
    val firstSeen: Long
) {
    /** 最后一次看到的时间 */
    var lastSeen: Long = firstSeen

    /** 最后一次播报的时间 */
    var lastAnnounced: Long = 0L

    /** 连续未匹配的帧数 */
    var missedFrames: Int = 0

    /** 更新追踪状态 */
    fun update(newObstacle: DetectedObstacle, now: Long) {
        obstacle = newObstacle
        lastSeen = now
        missedFrames = 0
    }

    /** 标记当前帧未匹配到 */
    fun markNotSeen(now: Long) {
        missedFrames++
    }

    /**
     * 判断是否应该播报
     *
     * 条件：
     * 1. 首次出现（lastAnnounced == 0）
     * 2. 距离上次播报超过保护期
     * 3. 距离变化显著（> 0.5m）
     */
    fun shouldAnnounce(now: Long, protectionMs: Long): Boolean {
        // 首次出现
        if (lastAnnounced == 0L) {
            lastAnnounced = now
            return true
        }

        // 冷却期检查
        if (now - lastAnnounced < protectionMs) {
            return false
        }

        // 距离变化显著时才重新播报
        // 注意：这里需要保存上一次播报时的距离，简化处理：总是允许重新播报
        lastAnnounced = now
        return true
    }
}

/**
 * 追踪结果数据类
 */
data class TrackedObstacle(
    val obstacle: DetectedObstacle,
    val trackId: Int,
    val isNew: Boolean,
    val shouldAnnounce: Boolean,
    val age: Long  // 存活时间（毫秒）
)
