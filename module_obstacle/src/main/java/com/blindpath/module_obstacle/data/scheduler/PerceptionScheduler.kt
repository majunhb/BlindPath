package com.blindpath.module_obstacle.data.scheduler

import android.graphics.Bitmap
import com.blindpath.module_obstacle.data.detection.AIDetector
import com.blindpath.module_obstacle.data.scene.QwenSceneDescriptor
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.SceneRecognitionResult
import com.blindpath.module_obstacle.domain.model.SceneType
import kotlinx.coroutines.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [感知层调度器] 协调 YOLO 实时检测和 Qwen-VL 场景理解
 * 
 * 调度策略：
 * - 实时模式（100-300ms）：YOLO-seg 检测障碍物、盲道、斑马线
 * - 场景模式（2-3秒）：Qwen-VL 生成场景语义描述
 * - 触发模式（用户主动）：拍照后详细分析
 * 
 * 状态机：
 * IDLE -> REALTIME_DETECTING -> SCENE_ANALYZING -> ALERTING -> IDLE
 */
@Singleton
class PerceptionScheduler @Inject constructor(
    private val aiDetector: AIDetector,
    private val sceneDescriptor: QwenSceneDescriptor
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // 调度配置
    private val REALTIME_INTERVAL_MS = 300L      // YOLO 检测间隔
    private val SCENE_INTERVAL_MS = 3000L        // 场景分析间隔
    private val ALERT_COOLDOWN_MS = 2000L        // 告警冷却
    
    // 状态
    private var isRunning = false
    private var lastSceneAnalysis = 0L
    private var lastAlertTime = 0L
    private var frameCounter = 0
    
    // 回调
    var onObstaclesDetected: ((List<DetectedObstacle>) -> Unit)? = null
    var onSceneDescribed: ((SceneRecognitionResult, String) -> Unit)? = null
    var onAlert: ((String) -> Unit)? = null
    
    /**
     * 启动感知调度
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        Timber.i("PerceptionScheduler: Started")
        
        scope.launch {
            while (isActive && isRunning) {
                delay(REALTIME_INTERVAL_MS)
            }
        }
    }
    
    /**
     * 停止感知调度
     */
    fun stop() {
        isRunning = false
        scope.cancel()
        Timber.i("PerceptionScheduler: Stopped")
    }
    
    /**
     * 处理一帧图像（由摄像头回调触发）
     */
    suspend fun processFrame(bitmap: Bitmap) {
        if (!isRunning) return
        
        frameCounter++
        
        // === 实时检测（每帧都运行，但YOLO内部有跳帧）===
        val obstacles = aiDetector.detect(bitmap)
        
        if (obstacles.isNotEmpty()) {
            onObstaclesDetected?.invoke(obstacles)
            
            // 危险障碍物立即告警
            val dangerObstacles = obstacles.filter { it.type.severity >= 2 && it.distance < 2f }
            if (dangerObstacles.isNotEmpty()) {
                triggerAlert(dangerObstacles)
            }
        }
        
        // === 场景分析（每3秒一次）===
        val now = System.currentTimeMillis()
        if (now - lastSceneAnalysis >= SCENE_INTERVAL_MS) {
            lastSceneAnalysis = now
            
            // 在后台线程运行场景描述
            scope.launch(Dispatchers.IO) {
                try {
                    val sceneResult = sceneDescriptor.describeScene(bitmap)
                    val obstacleNames = obstacles.map { it.type.chineseName }.distinct()
                    val description = sceneDescriptor.generateDescription(sceneResult.sceneType, obstacleNames)
                    
                    onSceneDescribed?.invoke(sceneResult, description)
                    
                    // 场景变化时播报
                    if (sceneResult.sceneType != SceneType.UNKNOWN) {
                        onAlert?.invoke(description)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Scene analysis failed")
                }
            }
        }
    }
    
    /**
     * 用户主动触发详细分析
     */
    suspend fun analyzeOnDemand(bitmap: Bitmap): String {
        Timber.i("PerceptionScheduler: On-demand analysis triggered")
        
        // 1. 运行完整检测
        val obstacles = aiDetector.detect(bitmap)
        
        // 2. 运行场景描述
        val sceneResult = sceneDescriptor.describeScene(bitmap)
        
        // 3. 生成综合描述
        val obstacleNames = obstacles.map { it.type.chineseName }.distinct()
        return sceneDescriptor.generateDescription(sceneResult.sceneType, obstacleNames)
    }
    
    /**
     * 触发告警（带冷却）
     */
    private fun triggerAlert(obstacles: List<DetectedObstacle>) {
        val now = System.currentTimeMillis()
        if (now - lastAlertTime < ALERT_COOLDOWN_MS) return
        
        lastAlertTime = now
        
        // 生成告警消息
        val message = obstacles.take(2).joinToString("，") { obstacle ->
            "${obstacle.direction.getChineseName()}${obstacle.distance.toInt()}米有${obstacle.type.chineseName}"
        }
        
        onAlert?.invoke("注意：$message")
    }
    
    /**
     * 获取当前状态
     */
    fun getStatus(): SchedulerStatus {
        return SchedulerStatus(
            isRunning = isRunning,
            frameCount = frameCounter,
            lastSceneAnalysis = lastSceneAnalysis,
            modelLoaded = aiDetector.isModelLoaded()
        )
    }
    
    data class SchedulerStatus(
        val isRunning: Boolean,
        val frameCount: Int,
        val lastSceneAnalysis: Long,
        val modelLoaded: Boolean
    )
}
