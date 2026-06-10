package com.blindpath.module_obstacle.domain

import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import com.blindpath.base.common.AlertLevel
import com.blindpath.base.common.Result
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.ObstacleState
import com.blindpath.module_obstacle.domain.model.PerceptionMode
import kotlinx.coroutines.flow.Flow

interface ObstacleRepository {
    val obstacleState: Flow<ObstacleState>
    val itemSearchState: Flow<com.blindpath.module_obstacle.domain.ItemSearchManager.ItemSearchState>
    val busGuideState: Flow<com.blindpath.module_obstacle.domain.BusGuideManager.BusGuideState>
    suspend fun initialize(): Result<Boolean>
    suspend fun startDetection(): Result<Boolean>
    suspend fun stopDetection(): Result<Boolean>
    fun getLatestObstacles(): List<DetectedObstacle>
    suspend fun loadModel(): Result<Boolean>
    suspend fun unloadModel()
    suspend fun processFrame(imageData: ByteArray, width: Int, height: Int): List<DetectedObstacle>
    suspend fun switchCamera(useFrontCamera: Boolean): Result<Boolean>
    fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider)
    fun setLifecycleOwner(owner: LifecycleOwner)
    suspend fun setPerceptionMode(mode: PerceptionMode): Result<Boolean>
    fun getCurrentPerceptionMode(): PerceptionMode
    fun startItemSearch()
    fun setItemTarget(spokenName: String): Boolean
    fun stopItemSearch()
    fun continueItemSearch()
    fun startBusGuide()
    fun stopBusGuide()
    fun getAlertLevel(distance: Float): AlertLevel {
        return when {
            distance < 0.5f -> AlertLevel.DANGER
            distance < 1.0f -> AlertLevel.WARNING
            else -> AlertLevel.SAFE
        }
    }
}
