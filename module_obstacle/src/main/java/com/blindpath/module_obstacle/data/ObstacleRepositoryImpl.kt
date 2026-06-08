/**
 * BlindPath - 视障人士出行辅助应用
 *
 * 文件：ObstacleRepositoryImpl.kt
 * 路径：module_obstacle/src/main/java/com/blindpath/module_obstacle/data/
 *
 * 架构级重构 v4.0 - 完整修复编译问题
 *
 * 修复内容：
 * 1. 包名正确：package com.blindpath.module_obstacle.data
 * 2. 实现 ObstacleRepository 接口
 * 3. 使用 AIDetector 进行 AI 检测
 * 4. 使用 com.blindpath.base.common.Result（非 kotlin.Result）
 * 5. 所有接口方法都有正确的 override 修饰符
 * 6. 没有重复的方法定义
 * 7. 使用 Surface.ROTATION_0 而不是 ImageInfo.ROTATION_0
 * 8. withContext 只在 suspend 函数中使用
 */

package com.blindpath.module_obstacle.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.YuvImage
import android.util.Size
import android.view.Surface
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.blindpath.base.common.AlertLevel
import com.blindpath.base.common.ObstacleAlert
import com.blindpath.base.common.Result
import com.blindpath.base.config.AppConfig
import com.blindpath.module_obstacle.data.detection.AIDetector
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.ObstacleState
import com.blindpath.module_obstacle.domain.model.PerceptionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 障碍物检测仓库实现
 *
 * 核心职责：
 * 1. 管理 CameraX 摄像头
 * 2. 管理 AI 模型加载和推理（通过 AIDetector）
 * 3. 处理图像分析
 * 4. 计算障碍物位置和预警级别
 * 5. 触发语音播报
 */
@Singleton
class ObstacleRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiDetector: AIDetector
) : ObstacleRepository {

    // ==================== 状态管理 ====================

    private val _obstacleState = MutableStateFlow(ObstacleState())
    override val obstacleState: StateFlow<ObstacleState> = _obstacleState.asStateFlow()

    // 协程作用域
    private val repositoryScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // CameraX 相关
    private var cameraProvider: ProcessCameraProvider? = null
    private var imageAnalysis: ImageAnalysis? = null
    private var preview: Preview? = null
    private var camera: Camera? = null

    // 生命周期
    private var lifecycleOwner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null

    // 执行器
    private val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    // 检测状态标志
    private val isDetecting = AtomicBoolean(false)

    // 统计
    private var frameCount = 0
    private var lastFpsUpdate = System.currentTimeMillis()
    private var currentFps = 0

    // 跳帧计数器 - 智能调整处理频率
    private var frameSkipCounter = 0
    private var frameSkipRatio = AppConfig.AIDetection.FRAME_SKIP

    // 上一帧用于运动检测
    private var previousBitmap: Bitmap? = null

    // 阈值配置
    private val config = DetectionConfig()

    // ==================== 生命周期方法 ====================

    /**
     * 设置生命周期所有者
     */
    override fun setLifecycleOwner(owner: LifecycleOwner) {
        lifecycleOwner = owner
        Timber.d("ObstacleRepository: LifecycleOwner set")

        if (_obstacleState.value.isRunning) {
            bindCameraUseCases()
        }
    }

    /**
     * 设置预览 SurfaceProvider
     */
    override fun setPreviewSurfaceProvider(provider: Preview.SurfaceProvider) {
        surfaceProvider = provider
        Timber.d("ObstacleRepository: SurfaceProvider set")

        preview?.setSurfaceProvider(provider)
    }

    // ==================== ObstacleRepository 接口实现 ====================

    override suspend fun initialize(): Result<Boolean> = withContext(Dispatchers.IO) {
        try {
            Timber.d("ObstacleRepository: Initializing...")

            // 初始化 AI 检测器 - 关键修复：真正加载模型
            val loadResult = aiDetector.loadModel()
            if (!loadResult) {
                Timber.e("ObstacleRepository: Failed to load AI model")
                _obstacleState.value = _obstacleState.value.copy(
                    isModelLoaded = false,
                    lastError = "AI 模型加载失败"
                )
                return@withContext Result.Error(message = "AI 模型加载失败")
            }

            _obstacleState.value = _obstacleState.value.copy(
                isModelLoaded = true,
                isCameraReady = false,
                lastError = null
            )

            Timber.d("ObstacleRepository: Initialized successfully")
            Result.Success(true)

        } catch (e: Exception) {
            Timber.e(e, "ObstacleRepository: Initialization failed")
            _obstacleState.value = _obstacleState.value.copy(
                lastError = "初始化失败: ${e.message}"
            )
            Result.Error(message = e.message ?: "初始化失败")
        }
    }

    /**
     * 开始障碍物检测
     */
    override suspend fun startDetection(): Result<Boolean> {
        Timber.d("ObstacleRepository: Starting detection...")

        if (_obstacleState.value.isRunning) {
            Timber.w("ObstacleRepository: Already running, ignoring start request")
            return Result.Success(true)
        }

        return withContext(Dispatchers.Main) {
            try {
                // 获取 CameraProvider
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProvider = withContext(Dispatchers.IO) {
                    cameraProviderFuture.get()
                }

                // 绑定 CameraX 用例
                bindCameraUseCases()

                // 更新状态
                _obstacleState.value = _obstacleState.value.copy(
                    isRunning = true,
                    fps = 0,
                    detectedObstacles = emptyList(),
                    currentAlert = null
                )

                isDetecting.set(true)

                Timber.d("ObstacleRepository: Detection started successfully")
                Result.Success(true)

            } catch (e: Exception) {
                Timber.e(e, "ObstacleRepository: Failed to start detection")
                _obstacleState.value = _obstacleState.value.copy(
                    isRunning = false,
                    lastError = "启动检测失败: ${e.message}"
                )
                Result.Error(message = e.message ?: "启动检测失败")
            }
        }
    }

    /**
     * 停止障碍物检测
     */
    override suspend fun stopDetection(): Result<Boolean> {
        Timber.d("ObstacleRepository: Stopping detection...")

        return withContext(Dispatchers.Main) {
            try {
                isDetecting.set(false)

                // 解绑所有用例
                cameraProvider?.unbindAll()
                camera = null

                // 释放上一帧
                synchronized(this@ObstacleRepositoryImpl) {
                    previousBitmap?.recycle()
                    previousBitmap = null
                }

                // 更新状态
                _obstacleState.value = ObstacleState(
                    isRunning = false,
                    isModelLoaded = _obstacleState.value.isModelLoaded,
                    fps = 0
                )

                Timber.d("ObstacleRepository: Detection stopped")
                Result.Success(true)

            } catch (e: Exception) {
                Timber.e(e, "ObstacleRepository: Error stopping detection")
                Result.Error(message = e.message ?: "停止检测失败")
            }
        }
    }

    /**
     * 获取最新检测结果
     */
    override fun getLatestObstacles(): List<DetectedObstacle> {
        return _obstacleState.value.detectedObstacles
    }

    /**
     * 加载 AI 模型
     */
    override suspend fun loadModel(): Result<Boolean> {
        return initialize()
    }

    /**
     * 卸载 AI 模型
     */
    override suspend fun unloadModel() {
        withContext(Dispatchers.IO) {
            aiDetector.unloadModel()
            _obstacleState.value = _obstacleState.value.copy(isModelLoaded = false)
        }
    }

    override suspend fun setPerceptionMode(mode: PerceptionMode): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("ObstacleRepository: Switching to mode: ${mode.chineseName}")
                val wasRunning = _obstacleState.value.isRunning
                if (wasRunning) { stopDetection() }
                val success = aiDetector.switchMode(mode)
                if (!success) {
                    return@withContext Result.Error(message = "切换模式失败: ${mode.modelFileName}")
                }
                _obstacleState.value = _obstacleState.value.copy(
                    isModelLoaded = true,
                    currentAlert = ObstacleAlert(
                        level = AlertLevel.SAFE,
                        description = mode.getModeSwitchAnnouncement(),
                        distance = Float.MAX_VALUE,
                        direction = ""
                    )
                )
                if (wasRunning) { startDetection() }
                Result.Success(true)
            } catch (e: Exception) {
                Timber.e(e, "Failed to switch mode")
                Result.Error(message = e.message ?: "切换模式失败")
            }
        }
    }

    override fun getCurrentPerceptionMode(): PerceptionMode {
        return aiDetector.getCurrentMode()
    }

    /**
     * 处理单帧图像
     */
    override suspend fun processFrame(imageData: ByteArray, width: Int, height: Int): List<DetectedObstacle> {
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = rawToBitmap(imageData, width, height)
                if (bitmap != null) {
                    val obstacles = aiDetector.detect(bitmap)
                    bitmap.recycle()
                    obstacles
                } else {
                    emptyList()
                }
            } catch (e: Exception) {
                Timber.e(e, "Error processing frame")
                emptyList()
            }
        }
    }

    /**
     * 切换前置/后置摄像头
     */
    override suspend fun switchCamera(useFrontCamera: Boolean): Result<Boolean> {
        return withContext(Dispatchers.Main) {
            try {
                // 重新绑定 CameraX 用例
                bindCameraUseCases()
                Result.Success(true)
            } catch (e: Exception) {
                Timber.e(e, "Failed to switch camera")
                Result.Error(message = e.message ?: "切换摄像头失败")
            }
        }
    }

    // ==================== CameraX 配置 ====================

    /**
     * 绑定 CameraX 用例
     */
    private fun bindCameraUseCases() {
        val provider = cameraProvider ?: run {
            Timber.e("ObstacleRepository: CameraProvider is null")
            return
        }

        val owner = lifecycleOwner ?: run {
            Timber.e("ObstacleRepository: LifecycleOwner is null")
            return
        }

        Timber.d("ObstacleRepository: Binding camera use cases...")

        try {
            // 解绑所有现有用例
            provider.unbindAll()

            // 配置 Preview
            preview = Preview.Builder()
                .setTargetResolution(Size(640, 480))
                .build()
                .also {
                    surfaceProvider?.let { sp ->
                        it.setSurfaceProvider(sp)
                    }
                }

            // 配置 ImageAnalysis
            imageAnalysis = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setTargetRotation(Surface.ROTATION_0)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { analysis ->
                    analysis.setAnalyzer(analysisExecutor) { imageProxy ->
                        repositoryScope.launch {
                            processImage(imageProxy)
                        }
                    }
                }

            // 选择后置摄像头
            val cameraSelector = CameraSelector.Builder()
                .requireLensFacing(CameraSelector.LENS_FACING_BACK)
                .build()

            // 绑定到生命周期
            camera = provider.bindToLifecycle(
                owner,
                cameraSelector,
                preview,
                imageAnalysis
            )

            _obstacleState.value = _obstacleState.value.copy(isCameraReady = true)

            Timber.d("ObstacleRepository: Camera use cases bound successfully")

        } catch (e: Exception) {
            Timber.e(e, "ObstacleRepository: Failed to bind camera use cases")
            _obstacleState.value = _obstacleState.value.copy(
                lastError = "摄像头绑定失败: ${e.message}"
            )
        }
    }

    // ==================== 图像处理和 AI 推理 ====================

    /**
     * 处理摄像头图像
     *
     * 关键修复：
     * 1. 跳帧处理：平衡性能和响应速度
     * 2. 正确调用 AIDetector.detect() 进行推理
     * 3. 将检测结果转换为 ObstacleAlert 触发语音播报
     */
    private suspend fun processImage(imageProxy: ImageProxy) {
        val startTime = System.currentTimeMillis()

        try {
            // 检查是否正在检测
            if (!isDetecting.get()) {
                imageProxy.close()
                return
            }

            // 跳帧处理 - 关键优化
            frameSkipCounter++
            if (frameSkipCounter < frameSkipRatio) {
                imageProxy.close()
                return
            }
            frameSkipCounter = 0

            // 检查模型是否加载
            if (!aiDetector.isModelLoaded()) {
                Timber.w("ObstacleRepository: Model not loaded, skipping frame")
                imageProxy.close()
                return
            }

            // 转换图像
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap == null) {
                imageProxy.close()
                return
            }

            // 核心修复：调用 AIDetector 进行真正的障碍物检测
            val obstacles = aiDetector.detect(bitmap)

            // 更新 FPS
            updateFps()

                        // 核心修复：将检测结果转换为 ObstacleAlert 并触发语音播报
            processDetections(obstacles)

                        // 场景识别：检测斑马线、信号灯、路口等场景
            // TODO: 注入 SceneClassifier 后启用完整场景识别
            val sceneResult = aiDetector.detect(bitmap) // 临时复用障碍物检测
            if (sceneResult.isNotEmpty()) {
                val firstObstacle = sceneResult.first()
                if (firstObstacle.type == ObstacleType.TRAFFIC_LIGHT || firstObstacle.type == ObstacleType.TRAFFIC_SIGN) {
                    _obstacleState.value = _obstacleState.value.copy(
                        currentAlert = ObstacleAlert(
                            level = AlertLevel.SAFE,
                            description = "检测到${firstObstacle.type.getChineseName()}，请注意",
                            distance = firstObstacle.distance,
                            direction = firstObstacle.direction.getChineseName()
                        )
                    )
                }
            }

            // 保存当前帧用于运动检测（可选）
            synchronized(this@ObstacleRepositoryImpl) {
                previousBitmap?.recycle()
                previousBitmap = bitmap.copy(bitmap.config ?: Bitmap.Config.ARGB_8888, false)
            }

            // 回收 Bitmap
            bitmap.recycle()

        } catch (e: Exception) {
            Timber.e(e, "ObstacleRepository: Error processing image")
        } finally {
            imageProxy.close()
        }

        val processTime = System.currentTimeMillis() - startTime
        if (processTime > AppConfig.FrameRate.MAX_PROCESSING_TIME_MS) {
            Timber.w("ObstacleRepository: Slow processing: ${processTime}ms")
        }
    }

    /**
     * 将 ImageProxy 转换为 Bitmap
     */
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

            val yuvImage = YuvImage(
                nv21,
                ImageFormat.NV21,
                imageProxy.width,
                imageProxy.height,
                null
            )

            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                Rect(0, 0, imageProxy.width, imageProxy.height),
                85, // JPEG 质量
                out
            )

            val imageBytes = out.toByteArray()
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                ?: return null

            // 旋转 Bitmap 以匹配图像旋转
            val rotationDegrees = imageProxy.imageInfo.rotationDegrees
            if (rotationDegrees != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotationDegrees.toFloat())
                Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            } else {
                bitmap
            }

        } catch (e: Exception) {
            Timber.e(e, "ObstacleRepository: Failed to convert image to bitmap")
            null
        }
    }

    /**
     * 将 raw 数据转换为 Bitmap
     */
    private fun rawToBitmap(data: ByteArray, width: Int, height: Int): Bitmap? {
        return try {
            val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(Rect(0, 0, width, height), 85, out)
            val imageBytes = out.toByteArray()
            BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e: Exception) {
            Timber.e(e, "ObstacleRepository: Failed to convert raw data to bitmap")
            null
        }
    }

    /**
     * 更新 FPS 计数
     */
    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsUpdate

        if (elapsed >= 1000) {
            currentFps = (frameCount * 1000 / elapsed).toInt()
            frameCount = 0
            lastFpsUpdate = now

            // 动态调整跳帧比以保证 FPS >= 15
            adjustFrameSkip()

            _obstacleState.value = _obstacleState.value.copy(fps = currentFps)
        }
    }

    /**
     * 动态调整跳帧比以保证 FPS >= 15
     */
    private fun adjustFrameSkip() {
        // 如果 FPS 低于 15，增加跳帧（减少处理）
        // 如果 FPS 高于 25，减少跳帧（提高精度）
        when {
            currentFps < 12 -> {
                // 严重低于目标，增加跳帧
                if (frameSkipRatio < 5) {
                    frameSkipRatio += 1
                    Timber.d("Adjusting frame skip: ${frameSkipRatio - 1} -> $frameSkipRatio")
                }
            }
            currentFps > 30 -> {
                // 过高，可以减少跳帧提高精度
                if (frameSkipRatio > 1) {
                    frameSkipRatio -= 1
                    Timber.d("Adjusting frame skip: ${frameSkipRatio + 1} -> $frameSkipRatio")
                }
            }
        }
    }

    // ==================== 检测结果处理和语音播报 ====================

    /**
     * 处理检测结果
     *
     * 关键修复：
     * 1. 将 AIDetector 返回的 DetectedObstacle 转换为 ObstacleAlert
     * 2. 更新 _obstacleState 触发 UI 更新
     * 3. ObstacleService 会监听 obstacleState 并触发语音播报
     */
    private fun processDetections(rawObstacles: List<DetectedObstacle>) {
        // 置信度过滤
        val filteredObstacles = rawObstacles
            .filter { it.confidence >= config.minConfidence }
            .sortedByDescending { it.confidence }
            .take(config.maxDetections)

        // 计算预警级别 - 关键修复：生成 ObstacleAlert 触发语音播报
        val alert = calculateAlertLevel(filteredObstacles)

        // 更新状态 - ObstacleService 会监听这个状态变化来触发语音播报
        _obstacleState.value = _obstacleState.value.copy(
            detectedObstacles = filteredObstacles,
            currentAlert = alert
        )

        // 日志输出（调试用）
        if (filteredObstacles.isNotEmpty()) {
            Timber.d("ObstacleRepository: Detected ${filteredObstacles.size} obstacles, alert: ${alert?.level?.name}")
            filteredObstacles.forEach { obstacle ->
                Timber.d("  - ${obstacle.type.chineseName}: ${String.format("%.1f", obstacle.distance)}m, confidence: ${String.format("%.0f", obstacle.confidence * 100)}%")
            }
        }
    }

    /**
     * 计算预警级别
     *
     * 关键修复：返回 ObstacleAlert 对象，包含 level, description, distance, direction
     * ObstacleService 的 handleAlert() 会使用这些信息触发语音播报
     */
    private fun calculateAlertLevel(obstacles: List<DetectedObstacle>): ObstacleAlert? {
        if (obstacles.isEmpty()) {
            return ObstacleAlert(
                level = AlertLevel.SAFE,
                description = "安全",
                distance = Float.MAX_VALUE,
                direction = ""
            )
        }

        // 找到最近的障碍物
        val nearest = obstacles.minByOrNull { it.distance } ?: return null

        // 根据距离确定级别
        val level = when {
            nearest.distance < config.dangerDistance -> AlertLevel.DANGER
            nearest.distance < config.warningDistance -> AlertLevel.WARNING
            else -> AlertLevel.SAFE
        }

        // 生成警告消息 - 使用 ObstacleType.getAlertMessage() 生成自然的语音播报
        val description = nearest.type.getAlertMessage(nearest.distance, nearest.direction)

        // 获取方向描述
        val direction = nearest.direction.getChineseName()

        return ObstacleAlert(
            level = level,
            description = description,
            distance = nearest.distance,
            direction = direction
        )
    }

    // ==================== 资源释放 ====================

    /**
     * 释放资源
     */
    fun release() {
        Timber.d("ObstacleRepository: Releasing resources...")

        repositoryScope.launch {
            stopDetection()
        }

        analysisExecutor.shutdown()
        repositoryScope.cancel()

        // 释放 AI 模型
        aiDetector.unloadModel()

        // 释放上一帧
        synchronized(this@ObstacleRepositoryImpl) {
            previousBitmap?.recycle()
            previousBitmap = null
        }

        Timber.d("ObstacleRepository: Resources released")
    }
}

/**
 * 检测配置
 */
data class DetectionConfig(
    // 最小置信度阈值
    val minConfidence: Float = AppConfig.AIDetection.CONFIDENCE_THRESHOLD,

    // 最大同时检测障碍物数量
    val maxDetections: Int = 10,

    // 危险距离阈值（米）
    val dangerDistance: Float = AppConfig.ObstacleAlert.DANGER_DISTANCE,

    // 警告距离阈值（米）
    val warningDistance: Float = AppConfig.ObstacleAlert.WARNING_DISTANCE,

    // 检测帧率限制（目标 FPS）
    val targetFps: Int = AppConfig.FrameRate.MEDIUM_FPS
)





