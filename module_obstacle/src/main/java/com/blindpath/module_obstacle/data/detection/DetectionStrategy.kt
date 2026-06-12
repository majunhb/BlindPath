package com.blindpath.module_obstacle.data.detection

import android.graphics.Bitmap
import com.blindpath.module_obstacle.domain.model.DetectedObstacle

/**
 * 检测策略接口
 * 所有检测器实现此接口，支持可插拔的降级链
 */
interface DetectionStrategy {
    /**
     * 检测策略名称
     */
    val name: String

    /**
     * 当前是否可用
     */
    val isAvailable: Boolean

    /**
     * 执行检测
     * @return 检测到的障碍物列表，失败返回空列表
     */
    fun detect(bitmap: Bitmap): List<DetectedObstacle>
}
