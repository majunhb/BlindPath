package com.blindpath.module_obstacle.domain

import com.blindpath.base.common.AlertLevel
import com.blindpath.base.common.Result
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.ObstacleState
import kotlinx.coroutines.flow.Flow

/**
 * 避障模块对外接口
 */
interface ObstacleRepository {

    /**
     * 避障状态Flow
     */
    val obstacleState: Flow<ObstacleState>

    /**
     * 启动避障检测
     */
    suspend fun startDetection(): Result<Boolean>

    /**
     * 停止避障检测
     */
    suspend fun stopDetection(): Result<Boolean>

    /**
     * 获取最新检测结果
     */
    fun getLatestObstacles(): List<DetectedObstacle>

    /**
     * 加载AI模型
     */
    suspend fun loadModel(): Result<Boolean>

    /**
     * 释放AI模型
     */
    suspend fun unloadModel()

    /**
     * 处理单帧图像
     */
    suspend fun processFrame(imageData: ByteArray, width: Int, height: Int): List<DetectedObstacle>

    /**
     * 切换前置/后置摄像头
     */
    suspend fun switchCamera(useFrontCamera: Boolean): Result<Boolean>

    /**
     * 获取预警级别
     */
    fun getAlertLevel(distance: Float): AlertLevel {
        return when {
            distance < 0.5f -> AlertLevel.DANGER
            distance < 1.0f -> AlertLevel.WARNING
            else -> AlertLevel.SAFE
        }
    }
}
