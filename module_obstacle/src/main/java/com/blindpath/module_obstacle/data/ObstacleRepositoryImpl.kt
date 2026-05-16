package com.blindpath.module_obstacle.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.blindpath.base.common.AlertLevel
import com.blindpath.base.common.ObstacleAlert
import com.blindpath.base.common.Result
import com.blindpath.module_obstacle.data.detection.AIDetector
import com.blindpath.module_obstacle.data.detection.SceneClassifier
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ObstacleRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiDetector: AIDetector,
    private val sceneClassifier: SceneClassifier
) : ObstacleRepository {

    private val _state = MutableStateFlow(ObstacleState())
    override val obstacleState: StateFlow<ObstacleState> = _state.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var analysisJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    private var latestObstacles: List<DetectedObstacle> = emptyList()
    private var useFrontCamera = false
    private var lastAlertTime = 0L
    private var lastSceneAnnouncementTime = 0L
    private val alertCooldown = 2000L // 预警冷却时间（毫秒）
    private val sceneCooldown = 8000L // 场景播报冷却时间

    // ============ 多障碍物播报队列 ============
    private var lastMultiObstacleAnnouncement = 0L
    private val multiObstacleCooldown = 3000L // 多障碍物播报间隔

    private var isCameraStarting = false
    private var isCameraStarted = false

    override suspend fun startDetection(): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Starting obstacle detection")

                // 加载模型（失败也继续，使用演示模式）
                val modelLoaded = aiDetector.loadModel()
                if (!modelLoaded) {
                    Timber.w("AI模型加载失败，将使用演示模式")
                    _state.update { it.copy(lastError = "AI模型加载失败，将使用演示模式") }
                }

                _state.update { it.copy(isModelLoaded = true) }

                // 启动摄像头（同步等待完成）
                val cameraStarted = startCameraSync()

                if (!cameraStarted) {
                    _state.update { it.copy(lastError = "摄像头启动失败，请检查摄像头权限并确保其他应用未占用摄像头") }
                    return@withContext Result.Error(message = "摄像头启动失败")
                }

                // 重置场景识别器
                sceneClassifier.reset()

                _state.update {
                    it.copy(
                        isRunning = true,
                        isCameraReady = true,
                        lastError = null
                    )
                }

                Result.Success(true)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start detection")
                _state.update { it.copy(lastError = "启动失败: ${e.message}") }
                Result.Error(message = e.message ?: "启动失败")
            }
        }
    }

    override suspend fun stopDetection(): Result<Boolean> {
        return try {
            Timber.d("Stopping obstacle detection")

            // 停止摄像头
            stopCamera()

            // 取消分析任务
            analysisJob?.cancel()
            analysisJob = null

            // 卸载模型
            aiDetector.unloadModel()

            // 重置场景识别器
            sceneClassifier.reset()

            _state.update {
                it.copy(
                    isRunning = false,
                    isCameraReady = false,
                    isModelLoaded = false,
                    currentAlert = null,
                    detectedObstacles = emptyList(),
                    sceneRecognition = null
                )
            }

            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to stop detection")
            Result.Error(message = e.message ?: "停止失败")
        }
    }

    override fun getLatestObstacles(): List<DetectedObstacle> = latestObstacles

    override fun getAlertLevel(distance: Float): AlertLevel {
        return when {
            distance < 0.5f -> AlertLevel.DANGER
            distance < 1.5f -> AlertLevel.WARNING
            else -> AlertLevel.SAFE
        }
    }

    override suspend fun loadModel(): Result<Boolean> {
        return if (aiDetector.loadModel()) {
            _state.update { it.copy(isModelLoaded = true) }
            Result.Success(true)
        } else {
            Result.Error(message = "模型加载失败")
        }
    }

    override suspend fun unloadModel() {
        aiDetector.unloadModel()
        _state.update { it.copy(isModelLoaded = false) }
    }

    override suspend fun processFrame(
        imageData: ByteArray,
        width: Int,
        height: Int
    ): List<DetectedObstacle> {
        return try {
            val bitmap = yuvToBitmap(imageData, width, height)
            val obstacles = aiDetector.detect(bitmap)
            latestObstacles = obstacles

            // 更新状态
            _state.update {
                it.copy(detectedObstacles = obstacles)
            }

            // 处理预警
            processAlert(obstacles)

            obstacles
        } catch (e: Exception) {
            Timber.e(e, "Frame processing failed")
            emptyList()
        }
    }

    override suspend fun switchCamera(useFront: Boolean): Result<Boolean> {
        if (useFrontCamera != useFront) {
            useFrontCamera = useFront
            if (_state.value.isRunning) {
                stopCamera()
                startCameraSync()
            }
        }
        return Result.Success(true)
    }

    /**
     * 同步启动摄像头，等待完成
     */
    private suspend fun startCameraSync(): Boolean {
        if (isCameraStarting || isCameraStarted) {
            Timber.d("Camera already starting or started")
            return isCameraStarted
        }

        isCameraStarting = true

        return withContext(Dispatchers.Main) {
            try {
                Timber.d("Starting camera...")

                // 先停止之前的摄像头
                stopCameraUnsafe()

                cameraExecutor = Executors.newSingleThreadExecutor()

                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

                // 等待CameraProvider准备完成
                val provider = try {
                    withContext(Dispatchers.IO) {
                        cameraProviderFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
                    }
                } catch (e: Exception) {
                    Timber.e(e, "Failed to get camera provider")
                    _state.update { it.copy(lastError = "无法获取摄像头: ${e.message}") }
                    isCameraStarting = false
                    return@withContext false
                }

                if (provider == null) {
                    Timber.e("CameraProvider is null")
                    _state.update { it.copy(lastError = "摄像头不可用") }
                    isCameraStarting = false
                    return@withContext false
                }

                cameraProvider = provider

                val cameraSelector = if (useFrontCamera) {
                    CameraSelector.DEFAULT_FRONT_CAMERA
                } else {
                    CameraSelector.DEFAULT_BACK_CAMERA
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                val executor = cameraExecutor!!
                imageAnalysis.setAnalyzer(executor) { imageProxy ->
                    try {
                        processImage(imageProxy)
                    } catch (e: Exception) {
                        Timber.e(e, "Image analysis error")
                        imageProxy.close()
                    }
                }

                // 使用 ProcessLifecycleOwner 绑定生命周期
                try {
                    // 先解绑所有之前的绑定
                    cameraProvider?.unbindAll()
                    
                    cameraProvider?.bindToLifecycle(
                        androidx.lifecycle.ProcessLifecycleOwner.get(),
                        cameraSelector,
                        imageAnalysis
                    )

                    isCameraStarted = true
                    isCameraStarting = false
                    _state.update { it.copy(isCameraReady = true) }
                    Timber.d("Camera started successfully")
                    true
                } catch (e: Exception) {
                    Timber.e(e, "Camera binding failed: ${e.javaClass.simpleName}: ${e.message}")
                    _state.update { it.copy(lastError = "摄像头启动失败: ${e.message}") }
                    isCameraStarting = false
                    false
                }
            } catch (e: Exception) {
                Timber.e(e, "Camera start failed: ${e.javaClass.simpleName}")
                _state.update { it.copy(lastError = "摄像头启动失败: ${e.message}") }
                isCameraStarting = false
                false
            }
        }
    }

    private fun stopCamera() {
        try {
            stopCameraUnsafe()
            isCameraStarted = false
            isCameraStarting = false
        } catch (e: Exception) {
            Timber.w(e, "Error stopping camera")
        }
    }

    private fun stopCameraUnsafe() {
        try {
            cameraProvider?.unbindAll()
        } catch (e: Exception) {
            Timber.w(e, "Failed to unbind camera")
        }
        try {
            cameraExecutor?.shutdown()
        } catch (e: Exception) {
            Timber.w(e, "Failed to shutdown executor")
        }
        cameraExecutor = null
    }

    private fun processImage(imageProxy: ImageProxy) {
        analysisJob?.cancel()
        analysisJob = scope.launch {
            try {
                val bitmap = imageProxyToBitmap(imageProxy)
                if (bitmap != null) {
                    // AI目标检测
                    val obstacles = aiDetector.detect(bitmap)
                    latestObstacles = obstacles

                    // 场景识别
                    val sceneResult = sceneClassifier.recognizeScene(bitmap, obstacles)

                    // 更新状态
                    _state.update {
                        it.copy(
                            detectedObstacles = obstacles,
                            sceneRecognition = sceneResult,
                            fps = try {
                                (1000 / (imageProxy.imageInfo.timestamp / 1_000_000)).toInt().coerceIn(0, 60)
                            } catch (e: Exception) {
                                30
                            }
                        )
                    }

                    // 处理预警
                    processAlert(obstacles)

                    // 处理场景变化
                    processSceneChange(sceneResult)
                }
            } catch (e: Exception) {
                Timber.e(e, "Image processing failed")
            } finally {
                try {
                    imageProxy.close()
                } catch (e: Exception) {
                    Timber.w(e, "Failed to close imageProxy")
                }
            }
        }
    }

    /**
     * 处理障碍物预警
     */
    private suspend fun processAlert(obstacles: List<DetectedObstacle>) {
        if (obstacles.isEmpty()) {
            _state.update { it.copy(currentAlert = null) }
            return
        }

        val currentTime = System.currentTimeMillis()

        // ============ 按距离和危险级别排序 ============
        val sortedObstacles = obstacles
            .sortedWith(compareBy(
                { it.distance }, // 先按距离
                { -it.type.severity } // 同距离按危险程度
            ))

        // ============ 获取最紧急的障碍物 ============
        val mostUrgent = sortedObstacles.firstOrNull { it.distance < 3f }

        mostUrgent?.let { obstacle ->
            // 检查冷却期
            if (currentTime - lastAlertTime < alertCooldown) {
                return
            }

            val alertLevel = getAlertLevel(obstacle.distance)
            val message = obstacle.type.getAlertMessage(obstacle.distance, obstacle.direction)

            // 创建UI预警
            val uiAlert = ObstacleAlert(
                level = alertLevel,
                description = message,
                distance = obstacle.distance,
                direction = obstacle.direction.getChineseName()
            )

            _state.update { it.copy(currentAlert = uiAlert) }
            lastAlertTime = currentTime

            Timber.d("Alert: ${alertLevel.name} - $message (${obstacle.distance}m)")
        }

        // ============ 多障碍物播报（当有多个近距离障碍物时） ============
        val nearObstacles = sortedObstacles.filter { it.distance < 2f }
        if (nearObstacles.size > 1 && currentTime - lastMultiObstacleAnnouncement > multiObstacleCooldown) {
            val uniqueTypes = nearObstacles.map { it.type }.distinct()
            if (uniqueTypes.size > 1) {
                // 有多种类型的近距离障碍物，生成综合播报
                val multiAlertMessage = generateMultiObstacleMessage(nearObstacles)
                Timber.d("Multi-obstacle alert: $multiAlertMessage")
                lastMultiObstacleAnnouncement = currentTime
            }
        }
    }

    /**
     * 生成多障碍物综合播报消息
     */
    private fun generateMultiObstacleMessage(obstacles: List<DetectedObstacle>): String {
        val messages = mutableListOf<String>()

        // 按类型分组
        val byType = obstacles.groupBy { it.type }

        for ((type, items) in byType) {
            if (items.size == 1) {
                messages.add("${items[0].direction.getChineseName()}${type.chineseName}")
            } else {
                messages.add("${items.size}个${type.chineseName}")
            }
        }

        return "注意，" + messages.take(3).joinToString("、") // 最多播报3种障碍物
    }

    /**
     * 处理场景变化
     */
    private suspend fun processSceneChange(sceneResult: SceneRecognitionResult?) {
        val currentTime = System.currentTimeMillis()

        if (sceneResult != null &&
            sceneResult.sceneType != SceneType.UNKNOWN &&
            currentTime - lastSceneAnnouncementTime > sceneCooldown) {

            val announcement = sceneResult.sceneType.getEntryAnnouncement()
            if (announcement.isNotEmpty()) {
                Timber.d("Scene announcement: $announcement")
                lastSceneAnnouncementTime = currentTime
            }
        }
    }

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, imageProxy.width, imageProxy.height), 80, out)
            val imageBytes = out.toByteArray()

            var bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)

            // 旋转角度
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0 && bitmap != null) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                bitmap = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            bitmap
        } catch (e: Exception) {
            Timber.e(e, "ImageProxy to Bitmap failed")
            null
        }
    }

    private fun yuvToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }
}
