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
import com.blindpath.module_obstacle.data.detection.AIDetectionStrategy
import com.blindpath.module_obstacle.data.detection.AIDetector
import com.blindpath.module_obstacle.data.detection.CompositeDetectionPipeline
import com.blindpath.module_obstacle.data.detection.SceneClassifier
import com.blindpath.module_obstacle.domain.BusGuideManager
import com.blindpath.module_obstacle.domain.ItemSearchManager
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.ObstacleType
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
import com.blindpath.base.reliability.LatencyTracker
import com.blindpath.base.reliability.ReliabilityLogger
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
    private val aiDetector: AIDetector,
    private val aiDetectionStrategy: AIDetectionStrategy,
    private val compositeDetectionPipeline: CompositeDetectionPipeline,
    private val sceneClassifier: SceneClassifier,
    private val itemSearchManager: ItemSearchManager,
    private val busGuideManager: BusGuideManager
) : ObstacleRepository {

    // ==================== 状态管理 ====================

    private val _obstacleState = MutableStateFlow(ObstacleState())
    override val obstacleState: StateFlow<ObstacleState> = _obstacleState.asStateFlow()
    override val itemSearchState = itemSearchManager.state
    override val busGuideState = busGuideManager.state

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

    // 所有输入帧的计数器（用于 FPS 显示）
    private var totalInputFrames = 0

    // 统计
    private var frameCount = 0
    private var lastFpsUpdate = System.currentTimeMillis()
    private var currentFps = 0

    // 跳帧计数器 - 智能调整处理频率
    private var frameSkipCounter = 0
    private var frameSkipRatio = AppConfig.AIDetection.FRAME_SKIP

    // 上一帧用于运动检测
    private var previousBitmap: Bitmap? = null

    // 播报去重：记录最近播报的障碍物，避免短时间内重复播报
    private val lastAnnouncedObstacles = mutableMapOf<String, Long>()  // dedupKey -> lastAnnounceTime
    private val announceCooldownMs = 10_000L  // 10秒冷却

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
                Timber.w("ObstacleRepository: AI model not available, using assisted detection")
                ReliabilityLogger.logFallback("ai_model_load", "AI model not available, using assisted detection")
                _obstacleState.value = _obstacleState.value.copy(
                    isModelLoaded = false,
                    isModelInitComplete = false,  // 未完成，等后面的 copy 统一设置
                    lastError = null
                )
                // 不返回 Error，允许继续使用辅助检测
            }

            _obstacleState.value = _obstacleState.value.copy(
                isModelLoaded = aiDetector.isModelLoaded(),
                isModelInitComplete = true,
                isCameraReady = false,
                lastError = null
            )

            // 同步 AI 检测策略的可用状态
            if (aiDetector.isModelLoaded()) {
                aiDetectionStrategy.markAvailable()
            } else {
                aiDetectionStrategy.markUnavailable()
            }

            Timber.d("ObstacleRepository: Initialized successfully")
            Result.Success(true)

        } catch (e: Exception) {
            Timber.e(e, "ObstacleRepository: Initialization failed")
            ReliabilityLogger.logFallback("ai_model_load", e.message)
            _obstacleState.value = _obstacleState.value.copy(
                isModelInitComplete = true,
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

        // 【关键修复】立即启用辅助检测，确保摄像头帧能被处理
        // TFLite 模型将在后台异步加载，成功后自动切换到模型推理
        aiDetector.forceAssistedDetection()

        // 后台异步加载 TFLite 模型（不阻塞摄像头启动）
        repositoryScope.launch {
            try {
                val loaded = aiDetector.loadModel()
                _obstacleState.value = _obstacleState.value.copy(isModelLoaded = loaded)
                // 同步 AI 检测策略的可用状态
                if (loaded) {
                    aiDetectionStrategy.markAvailable()
                } else {
                    aiDetectionStrategy.markUnavailable()
                }
                Timber.d("ObstacleRepository: Model load result: $loaded")
            } catch (e: Exception) {
                Timber.e(e, "ObstacleRepository: Model load failed, continuing with assisted detection")
                aiDetectionStrategy.markUnavailable()
            }
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
                    isModelInitComplete = _obstacleState.value.isModelInitComplete,
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
            aiDetectionStrategy.markUnavailable()
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

    // ============ 物品查找委托方法 ============
    override fun startItemSearch() = itemSearchManager.startSearch()
    override fun setItemTarget(spokenName: String) = itemSearchManager.setTarget(spokenName)
    override fun stopItemSearch() = itemSearchManager.stopSearch()
    override fun continueItemSearch() = itemSearchManager.continueSearch()

    // ============ 公交引导委托方法 ============
    override fun startBusGuide() = busGuideManager.startSearchBusStop()
    override fun stopBusGuide() = busGuideManager.stopGuide()

    /**
     * 处理单帧图像
     */
    override suspend fun processFrame(imageData: ByteArray, width: Int, height: Int): List<DetectedObstacle> {
        return withContext(Dispatchers.IO) {
            try {
                val bitmap = rawToBitmap(imageData, width, height)
                if (bitmap != null) {
                    val obstacles = compositeDetectionPipeline.detect(bitmap)
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
            totalInputFrames++  // 统计所有输入帧
            frameSkipCounter++
            if (frameSkipCounter < frameSkipRatio) {
                imageProxy.close()
                return
            }
            frameSkipCounter = 0

            // 转换图像
            val bitmap = imageProxyToBitmap(imageProxy)
            if (bitmap == null) {
                imageProxy.close()
                return
            }

            // 核心修复：通过组合检测管道执行障碍物检测（三层降级链）
            LatencyTracker.beginSpan("detection")
            val obstacles = compositeDetectionPipeline.detect(bitmap)
            LatencyTracker.endSpan("detection", LatencyTracker.DETECTION_BUDGET_MS)

            // 更新 FPS
            updateFps()

            // 核心修复：将检测结果转换为 ObstacleAlert 并触发语音播报
            processDetections(obstacles)

            // 场景识别：检测斑马线、信号灯、路口、积水、道牙等
            val sceneResult = sceneClassifier.recognizeScene(bitmap, obstacles)
            if (sceneResult != null) {
                _obstacleState.value = _obstacleState.value.copy(
                    sceneRecognition = sceneResult
                )
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

    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            // 使用更快的 Bitmap 创建方式，避免 JPEG 压缩/解压的往返开销
            val width = imageProxy.width
            val height = imageProxy.height
            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)

            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val yRowStride = imageProxy.planes[0].rowStride
            val uvRowStride = imageProxy.planes[1].rowStride
            val uvPixelStride = imageProxy.planes[1].pixelStride

            val pixels = IntArray(width * height)
            for (y in 0 until height) {
                val yRow = y * yRowStride
                val uvRow = (y / 2) * uvRowStride
                for (x in 0 until width) {
                    val yVal = (yBuffer[yRow + x].toInt() and 0xFF)
                    val uvPos = uvRow + (x / 2) * uvPixelStride
                    val uVal = (uBuffer[uvPos].toInt() and 0xFF) - 128
                    val vVal = (vBuffer[uvPos].toInt() and 0xFF) - 128

                    // YUV to RGB
                    var r = yVal + (1.402 * vVal)
                    var g = yVal - (0.344 * uVal) - (0.714 * vVal)
                    var b = yVal + (1.772 * uVal)
                    r = r.coerceIn(0.0, 255.0)
                    g = g.coerceIn(0.0, 255.0)
                    b = b.coerceIn(0.0, 255.0)

                    pixels[y * width + x] = (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
                }
            }
            bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
            bitmap
        } catch (e: Exception) {
            Timber.e(e, "Failed to convert image to bitmap")
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
     * 【修复】使用 totalInputFrames 统计输入帧率，更准确地反映摄像头帧率
     */
    private fun updateFps() {
        frameCount++
        val now = System.currentTimeMillis()
        val elapsed = now - lastFpsUpdate

        if (elapsed >= 1000) {
            // 显示的是实际处理的帧率，而不是输入帧率
            currentFps = (frameCount * 1000 / elapsed).toInt()
            frameCount = 0
            
            // 同时统计输入帧率
            val inputFps = (totalInputFrames * 1000 / elapsed).toInt()
            totalInputFrames = 0
            
            lastFpsUpdate = now

            // 动态调整跳帧比以保证 FPS >= 15
            adjustFrameSkip()

            _obstacleState.value = _obstacleState.value.copy(fps = if (currentFps > 0) currentFps else inputFps)
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
     * 判断障碍物是否应该播报（去重+冷却时间）
     * 相同类型和方向的障碍物在冷却时间内不会重复播报
     */
    private fun shouldAnnounce(obstacle: DetectedObstacle): Boolean {
        val key = obstacle.type.getDeduplicationKey(obstacle.direction)
        val now = System.currentTimeMillis()
        val lastTime = lastAnnouncedObstacles[key] ?: 0L
        if (now - lastTime < announceCooldownMs) return false
        lastAnnouncedObstacles[key] = now
        // 清理过期记录（超过30秒的记录）
        lastAnnouncedObstacles.entries.removeIf { now - it.value > 30_000L }
        return true
    }

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

        // [修复] 危险时减少跳帧，避免漏检突发危险
        val hasDanger = filteredObstacles.any { it.distance < config.dangerDistance }
        if (hasDanger && frameSkipRatio > 1) {
            frameSkipRatio = 1
            Timber.d("ObstacleRepository: Danger detected, reducing frame skip to 1")
        }

        // 关键修复：基于所有检测到的障碍物计算预警，而不是去重后的
        val alert = calculateAlertLevel(filteredObstacles)

        // 更新状态 - ObstacleService 会监听这个状态变化来触发语音播报
        _obstacleState.value = _obstacleState.value.copy(
            detectedObstacles = filteredObstacles,
            currentAlert = alert
        )

        // [集成] 物品查找检查
        itemSearchManager.checkDetectionResult(filteredObstacles)

        // [集成] 公交引导检查
        busGuideManager.checkDetectionResult(filteredObstacles)

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
            // 模型未加载时，不应报告"安全"（虚假安全感）
            if (!_obstacleState.value.isModelLoaded) {
                return ObstacleAlert(
                    level = AlertLevel.UNKNOWN,
                    description = "AI模型未加载，检测能力有限，请谨慎通行",
                    distance = Float.MAX_VALUE,
                    direction = ""
                )
            }
            return ObstacleAlert(
                level = AlertLevel.SAFE,
                description = "前方道路畅通，未检测到障碍物",
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
        aiDetectionStrategy.markUnavailable()

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
