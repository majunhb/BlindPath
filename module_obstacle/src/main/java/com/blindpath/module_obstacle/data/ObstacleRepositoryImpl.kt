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
    private val aiDetector: AIDetector
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
    private val alertCooldown = 1500L // 1.5秒内不重复播报同类预警
    
    private var isCameraStarting = false
    private var isCameraStarted = false

    override suspend fun startDetection(): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Starting obstacle detection")

                // 加载模型
                if (!aiDetector.loadModel()) {
                    _state.update { it.copy(lastError = "AI模型加载失败") }
                    return@withContext Result.Error(message = "AI模型加载失败")
                }

                _state.update { it.copy(isModelLoaded = true) }

                // 启动摄像头（同步等待完成）
                val cameraStarted = startCameraSync()
                
                if (!cameraStarted) {
                    _state.update { it.copy(lastError = "摄像头启动失败，请检查摄像头权限") }
                    return@withContext Result.Error(message = "摄像头启动失败")
                }

                _state.update {
                    it.copy(
                        isRunning = true,
                        lastError = null
                    )
                }

                Result.Success(true)
            } catch (e: Exception) {
                Timber.e(e, "Failed to start detection")
                _state.update { it.copy(lastError = e.message) }
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

            _state.update {
                it.copy(
                    isRunning = false,
                    isCameraReady = false,
                    isModelLoaded = false,
                    currentAlert = null,
                    detectedObstacles = emptyList()
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
            distance < 1.0f -> AlertLevel.WARNING
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
                    kotlinx.coroutines.guava.await(cameraProviderFuture)
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
                    Timber.e(e, "Camera binding failed")
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
                    val obstacles = aiDetector.detect(bitmap)
                    latestObstacles = obstacles

                    _state.update {
                        it.copy(
                            detectedObstacles = obstacles,
                            fps = try {
                                (1000 / (imageProxy.imageInfo.timestamp / 1_000_000)).toInt().coerceIn(0, 60)
                            } catch (e: Exception) {
                                30
                            }
                        )
                    }

                    processAlert(obstacles)
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

    private suspend fun processAlert(obstacles: List<DetectedObstacle>) {
        if (obstacles.isEmpty()) {
            _state.update { it.copy(currentAlert = null) }
            return
        }

        // 获取最近最危险的障碍物
        val mostDangerous = obstacles
            .filter { it.distance < 2.0f } // 只考虑2米以内的
            .minByOrNull { it.distance }

        mostDangerous?.let { obstacle ->
            val currentTime = System.currentTimeMillis()
            if (currentTime - lastAlertTime < alertCooldown) {
                return // 冷却期内不重复播报
            }

            val alertLevel = getAlertLevel(obstacle.distance)
            val message = obstacle.type.getAlertMessage(obstacle.distance)

            // 创建UI预警
            val uiAlert = ObstacleAlert(
                level = alertLevel,
                description = message,
                distance = obstacle.distance,
                direction = obstacle.direction.name
            )

            _state.update { it.copy(currentAlert = uiAlert) }
            lastAlertTime = currentTime

            Timber.d("Alert: ${alertLevel.name} - $message (${obstacle.distance}m)")
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
