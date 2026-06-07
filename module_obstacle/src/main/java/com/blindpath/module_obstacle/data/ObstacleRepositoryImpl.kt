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
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import com.blindpath.base.common.AlertLevel
import com.blindpath.base.common.ObstacleAlert
import com.blindpath.base.common.Result
import com.blindpath.base.config.AppConfig
import com.blindpath.module_obstacle.data.detection.AIDetector
import com.blindpath.module_obstacle.data.detection.SceneClassifier
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.*
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.VoiceType
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
    private val sceneClassifier: SceneClassifier,
    private val voiceRepository: VoiceRepository
) : ObstacleRepository {

    private val _state = MutableStateFlow(ObstacleState())
    override val obstacleState: StateFlow<ObstacleState> = _state.asStateFlow()

    private var cameraProvider: ProcessCameraProvider? = null
    private var cameraExecutor: ExecutorService? = null
    private var analysisJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())

    // [修复] 预览相关：存储外部传入的SurfaceProvider，与ImageAnalysis统一绑定
    private var previewProvider: androidx.camera.core.Preview.SurfaceProvider? = null
    private var previewUseCase: Preview? = null

    private var latestObstacles: List<DetectedObstacle> = emptyList()
    private var useFrontCamera = false
    private var lastAlertTime = 0L
    private var lastSceneAnnouncementTime = 0L
    private var lastFrameTimestamp = 0L
    private val alertCooldown = AppConfig.ObstacleAlert.ALERT_COOLDOWN_MS
    private val sceneCooldown = AppConfig.ObstacleAlert.SCENE_COOLDOWN_MS

    // ============ 多障碍物播报队列 ============
    private var lastMultiObstacleAnnouncement = 0L
    private val multiObstacleCooldown = AppConfig.ObstacleAlert.MULTI_OBSTACLE_COOLDOWN_MS

    private var isCameraStarting = false
    private var isCameraStarted = false
    
    // [修复] 使用外部传入的生命周期，而不是 ProcessLifecycleOwner
    // 这样 Preview 和 ImageAnalysis 绑定到同一个生命周期，避免竞争
    private var lifecycleOwner: androidx.lifecycle.LifecycleOwner? = null

    override suspend fun initialize(): Result<Boolean> {
        return try {
            Timber.d("Initializing obstacle module")
            val modelLoaded = aiDetector.loadModel()
            if (modelLoaded) {
                _state.update { it.copy(isModelLoaded = true) }
                Result.Success(true)
            } else {
                Result.Error(message = "AI模型加载失败")
            }
        } catch (e: Exception) {
            Timber.e(e, "Initialize failed")
            Result.Error(message = e.message ?: "初始化失败")
        }
    }

    override suspend fun startDetection(): Result<Boolean> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("Starting obstacle detection")

                // TTS 预初始化，确保危险播报立即可用
                try { voiceRepository.initialize() } catch (_: Exception) {}

                // 加载模型（避免重复加载）
                val modelLoaded = if (aiDetector.isModelLoaded()) true else aiDetector.loadModel()
                if (!modelLoaded) {
                    Timber.w("AI模型加载失败，尝试使用演示数据")
                    _state.update { it.copy(isModelLoaded = false, lastError = "AI模型未加载，障碍物检测精度受限") }
                    // 不返回Error，允许继续启动摄像头并收集数据，后续可能会下载成功
                }

                _state.update { it.copy(isModelLoaded = modelLoaded) }

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
    
    /**
     * [增强] 场景识别 - 基于检测到的物体组合和图像特征推断场景
     */
    private fun recognizeScene(bitmap: Bitmap, obstacles: List<DetectedObstacle>): SceneType {
        // 基于检测到的物体类型推断场景
        val types = obstacles.map { it.type }.toSet()
        
        return when {
            // 医院场景：病床 + 轮椅 + 白大褂人员
            types.contains(ObstacleType.BED) && types.contains(ObstacleType.PERSON) -> SceneType.HOSPITAL_AREA
            
            // 银行场景：柜台 + 座椅 + 人员
            types.contains(ObstacleType.CHAIR) && types.contains(ObstacleType.TABLE) && 
            obstacles.count { it.type == ObstacleType.PERSON } > 3 -> SceneType.BANK_AREA
            
            // 学校场景：多人 + 桌椅
            obstacles.count { it.type == ObstacleType.PERSON } > 5 -> SceneType.SCHOOL_AREA
            
            // 商场场景：多人 + 商品/设施
            types.contains(ObstacleType.POTTED_PLANT) && obstacles.count { it.type == ObstacleType.PERSON } > 3 -> SceneType.SHOPPING_MALL
            
            // 餐厅场景：桌椅 + 少量人员
            types.contains(ObstacleType.CHAIR) && types.contains(ObstacleType.TABLE) -> SceneType.RESTAURANT
            
            // 楼梯间
            types.contains(ObstacleType.STAIRS) || types.contains(ObstacleType.STEP_UP) -> SceneType.INDOOR_STAIRS
            
            // 路口：红绿灯 + 斑马线
            types.contains(ObstacleType.TRAFFIC_LIGHT) && types.contains(ObstacleType.ZEBRA_CROSSING) -> SceneType.INTERSECTION
            
            // 斑马线区域
            types.contains(ObstacleType.ZEBRA_CROSSING) -> SceneType.CROSSWALK
            
            // 人行道：路沿 + 行人
            types.contains(ObstacleType.CURB) && types.contains(ObstacleType.PERSON) -> SceneType.SIDEWALK
            
            // 默认
            else -> SceneType.UNKNOWN
        }
    }

    /**
     * [修复] 设置生命周期所有者，确保 Preview 和 ImageAnalysis 绑定到同一个生命周期
     */
    override fun setLifecycleOwner(owner: androidx.lifecycle.LifecycleOwner) {
        lifecycleOwner = owner
        Timber.d("LifecycleOwner set to: $owner")
    }

    override fun setPreviewSurfaceProvider(provider: androidx.camera.core.Preview.SurfaceProvider) {
        previewProvider = provider
        Timber.d("Preview surface provider set (isCameraStarted=$isCameraStarted)")
        
        // [关键修复] 如果摄像头已经在运行但没有 Preview，需要重启摄像头
        // 使用 restartCamera 而不是 rebindCameraWithPreview，避免 unbindAll 中断分析
        if (isCameraStarted && provider != null && previewUseCase == null) {
            Timber.d("Camera running without preview, restarting with preview...")
            scope.launch(Dispatchers.IO) {
                stopCamera()
                startCameraSync()
            }
        }
    }

    /**
     * [修复] 重新绑定摄像头，将Preview和ImageAnalysis一起绑定
     * 解决ObstacleDetectionContent独立绑定Preview导致的竞争条件
     */
    private suspend fun rebindCameraWithPreview() {
        if (cameraProvider == null || previewProvider == null) return
        try {
            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(previewProvider!!)
            previewUseCase = preview
            
            // [关键修复] 真正重新绑定 Preview + ImageAnalysis
            cameraProvider?.unbindAll()
            
            val targetLifecycle = lifecycleOwner ?: androidx.lifecycle.ProcessLifecycleOwner.get()
            val cameraSelector = if (useFrontCamera) {
                CameraSelector.DEFAULT_FRONT_CAMERA
            } else {
                CameraSelector.DEFAULT_BACK_CAMERA
            }
            
            // 重新创建 ImageAnalysis（因为之前的已经被 unbind）
            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .build()
            
            val executor = cameraExecutor ?: Executors.newSingleThreadExecutor().also { cameraExecutor = it }
            imageAnalysis.setAnalyzer(executor) { imageProxy ->
                try {
                    processImage(imageProxy)
                } catch (e: Exception) {
                    Timber.e(e, "Image analysis error")
                    imageProxy.close()
                }
            }
            
            cameraProvider?.bindToLifecycle(
                targetLifecycle,
                cameraSelector,
                preview,
                imageAnalysis
            )
            
            Timber.d("Preview + ImageAnalysis rebound successfully")
        } catch (e: Exception) {
            Timber.e(e, "Failed to rebind camera with preview")
        }
    }

    override fun getAlertLevel(distance: Float): AlertLevel {
        return when {
            distance < AppConfig.ObstacleAlert.DANGER_DISTANCE -> AlertLevel.DANGER
            distance < AppConfig.ObstacleAlert.WARNING_DISTANCE -> AlertLevel.WARNING
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

        // [关键修复] 等待 previewProvider 就绪（最多2秒）
        // AndroidView 的 update 块会在 Compose 首帧后设置 SurfaceProvider
        // 如果此时 previewProvider 为 null，先等待一下
        if (previewProvider == null) {
            Timber.d("Waiting for previewProvider...")
            var waited = 0
            while (previewProvider == null && waited < 2000) {
                delay(100)
                waited += 100
            }
            if (previewProvider == null) {
                Timber.w("previewProvider not available after 2s, starting without preview")
            }
        }

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

                // [修复] 创建Preview用例（如果有SurfaceProvider的话）
                val preview = if (previewProvider != null) {
                    Preview.Builder().build().also {
                        it.setSurfaceProvider(previewProvider!!)
                        previewUseCase = it
                        Timber.d("Preview use case created with SurfaceProvider")
                    }
                } else {
                    null
                }

                // 使用 ProcessLifecycleOwner 绑定生命周期
                try {
                    // 先解绑所有之前的绑定
                    cameraProvider?.unbindAll()

                    // [修复] 将 Preview 和 ImageAnalysis 绑定到同一生命周期
                    // 解决：之前只绑 ImageAnalysis，而 ObstacleDetectionContent 单独绑 Preview
                    // 导致两者互相 unbindAll() 竞争
                    val useCases = if (preview != null) {
                        arrayOf(preview, imageAnalysis)
                    } else {
                        arrayOf(imageAnalysis)
                    }

                    // [修复] 使用外部传入的生命周期，确保 Preview 和 ImageAnalysis 绑定一致
                    val targetLifecycle = lifecycleOwner ?: androidx.lifecycle.ProcessLifecycleOwner.get()
                    cameraProvider?.bindToLifecycle(
                        targetLifecycle,
                        cameraSelector,
                        *useCases
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
                    val aiObstacles = aiDetector.detect(bitmap)
                    
                    // 场景识别
                    val sceneResult = sceneClassifier.recognizeScene(bitmap, aiObstacles)
                    
                    // ============ 将场景检测结果转换为障碍物对象 ============
                    val sceneObstacles = mutableListOf<DetectedObstacle>()
                    
                    // 检测到路沿/道牙
                    if (sceneResult?.sceneType == SceneType.CURB) {
                        sceneObstacles.add(
                            DetectedObstacle(
                                type = ObstacleType.CURB,
                                confidence = sceneResult.confidence,
                                distance = estimateCurbDistance(bitmap),
                                direction = Direction.CENTER, // 路沿通常在正前方
                                boundingBox = BoundingBox(0.3f, 0.8f, 0.7f, 1.0f) // 底部区域
                            )
                        )
                        Timber.d("Curb detected by scene classifier, confidence=${sceneResult.confidence}")
                    }
                    
                    // 合并 AI 检测和场景检测的障碍物
                    val allObstacles = aiObstacles + sceneObstacles
                    latestObstacles = allObstacles

                    // 更新状态
                    _state.update {
                        it.copy(
                            detectedObstacles = allObstacles,
                            sceneRecognition = sceneResult,
                            fps = calculateFps(imageProxy.imageInfo.timestamp)
                        )
                    }

                    // 处理预警
                    processAlert(allObstacles)

                    // 处理场景变化
                    processSceneChange(sceneResult)
                    
                    // [规范] 障碍物语音播报统一由 processAlert() 处理
                    // 不在此处直接播报，避免重复播报
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

            // [关键修复] 语音播报 - 让视障用户听到预警
            val voiceType = when (alertLevel) {
                AlertLevel.DANGER -> VoiceType.OBSTACLE_DANGER
                AlertLevel.WARNING -> VoiceType.OBSTACLE_NORMAL
                AlertLevel.SAFE -> VoiceType.NAVIGATION_TURN
            }
            voiceRepository.announce(message, voiceType)

            Timber.d("Alert: ${alertLevel.name} - $message (${obstacle.distance}m)")
        }

        // ============ 多障碍物播报（当有多个近距离障碍物时） ============
        val nearObstacles = sortedObstacles.filter { it.distance < 2f }
        if (nearObstacles.size > 1 && currentTime - lastMultiObstacleAnnouncement > multiObstacleCooldown) {
            val uniqueTypes = nearObstacles.map { it.type }.distinct()
            if (uniqueTypes.size > 1) {
                // 有多种类型的近距离障碍物，生成综合播报
                val multiAlertMessage = generateMultiObstacleMessage(nearObstacles)
                // [关键修复] 语音播报多障碍物提示
                voiceRepository.announce(multiAlertMessage, VoiceType.OBSTACLE_NORMAL)
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

    /**
     * 估算路沿距离（基于图像位置）
     * 路沿通常在图像底部，距离估算基于其在图像中的垂直位置
     */
    private fun estimateCurbDistance(bitmap: Bitmap): Float {
        // 简化估算：路沿在图像底部70-100%区域，对应0.5-2米距离
        // 实际应用中可以使用更精确的单目测距算法
        return 1.5f // 默认1.5米，提示用户注意脚下
    }
    
    /**
     * 将 CameraX ImageProxy 转为 Bitmap
     * 使用直接 YUV->RGB 转换，避免 JPEG 编码/解码的 GC 开销
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

            // 直接 YUV->RGB 转换，避免 JPEG 编解码
            val bitmap = Bitmap.createBitmap(imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888)
            val pixels = IntArray(imageProxy.width * imageProxy.height)
            decodeYUV420ToRGB(nv21, pixels, imageProxy.width, imageProxy.height)
            bitmap.setPixels(pixels, 0, imageProxy.width, 0, 0, imageProxy.width, imageProxy.height)

            // 旋转角度
            val rotation = imageProxy.imageInfo.rotationDegrees
            if (rotation != 0) {
                val matrix = Matrix()
                matrix.postRotate(rotation.toFloat())
                return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            }

            bitmap
        } catch (e: Exception) {
            Timber.e(e, "ImageProxy to Bitmap failed")
            null
        }
    }

    /**
     * YUV420 NV21 转 RGB 像素数组（避免 JPEG 编解码）
     * 直接颜色空间转换，内存分配更少、速度更快
     */
    private fun decodeYUV420ToRGB(nv21: ByteArray, pixels: IntArray, width: Int, height: Int) {
        val frameSize = width * height
        var pixelIndex = 0
        for (y in 0 until height) {
            val uvRowIndex = y shr 1
            for (x in 0 until width) {
                val uvColIndex = x shr 1
                val yIndex = y * width + x
                val uvIndex = frameSize + uvRowIndex * width + uvColIndex * 2

                val Y = nv21[yIndex].toInt() and 0xFF
                val U = nv21[uvIndex].toInt() and 0xFF
                val V = nv21[uvIndex + 1].toInt() and 0xFF

                // YUV -> RGB 转换（BT.601 标准）
                var r = Y + 1.402f * (V - 128)
                var g = Y - 0.344f * (U - 128) - 0.714f * (V - 128)
                var b = Y + 1.772f * (U - 128)

                r = r.coerceIn(0f, 255f)
                g = g.coerceIn(0f, 255f)
                b = b.coerceIn(0f, 255f)

                pixels[pixelIndex++] = (0xFF shl 24) or ((r.toInt()) shl 16) or ((g.toInt()) shl 8) or (b.toInt())
            }
        }
    }

    private fun yuvToBitmap(data: ByteArray, width: Int, height: Int): Bitmap {
        val yuvImage = YuvImage(data, ImageFormat.NV21, width, height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, width, height), 80, out)
        return BitmapFactory.decodeByteArray(out.toByteArray(), 0, out.size())
    }

    /**
     * 基于帧间时间戳差值计算实时 FPS
     */
    private fun calculateFps(timestampNs: Long): Int {
        return try {
            if (lastFrameTimestamp > 0) {
                val frameIntervalMs = (timestampNs - lastFrameTimestamp) / 1_000_000
                val fps = if (frameIntervalMs > 0) (1000f / frameIntervalMs).toInt() else 60
                fps.coerceIn(0, 60)
            } else {
                0 // 第一帧，无历史数据
            }
        } catch (_: Exception) {
            30
        } finally {
            lastFrameTimestamp = timestampNs
        }
    }
}
