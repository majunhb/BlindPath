package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.blindpath.module_indoor.data.IndoorDetector
import com.blindpath.module_indoor.domain.model.*
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.Direction
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.viewmodel.VoiceInteractionViewModel
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * 室内感知屏幕 - 技术方案 v1.0 实现
 *
 * 核心功能：
 * 1. 高精度室内定位（多源融合）
 * 2. 全维度障碍物感知与分级预警
 * 3. 室内地标语义认知与空间结构理解
 * 4. 全模态智能交互与反馈
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndoorPerceptionScreen(
    obstacleRepository: ObstacleRepository,
    navigationRepository: com.blindpath.module_navigation.domain.NavigationRepository,
    onBack: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // 通过 Hilt EntryPoint 获取依赖
    val appContext = context.applicationContext
    val voiceRepository = remember {
        EntryPointAccessors.fromApplication(
            appContext,
            IndoorPerceptionEntryPoint::class.java
        ).voiceRepository()
    }
    val indoorDetector = remember {
        EntryPointAccessors.fromApplication(
            appContext,
            IndoorPerceptionEntryPoint::class.java
        ).indoorDetector()
    }

    // 权限状态
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        )
    }

    // 检测状态
    var isDetecting by remember { mutableStateOf(false) }
    var isModelLoading by remember { mutableStateOf(false) }
    var isModelReady by remember { mutableStateOf(false) }
    var currentScene by remember { mutableStateOf<IndoorScene?>(null) }
    var detectedObstacles by remember { mutableStateOf<List<DetectedIndoorObstacle>>(emptyList()) }
    var lastAnnouncement by remember { mutableStateOf("请授权相机权限后开始室内感知") }
    var lastRoomType by remember { mutableStateOf<RoomType?>(null) }
    var lastAlertTime by remember { mutableStateOf(0L) }
    var lastObstacleAlertTime by remember { mutableStateOf(0L) }

    // 模型加载重试相关状态
    var modelLoadAttempts by remember { mutableStateOf(0) }
    var showModelRetryButton by remember { mutableStateOf(false) }
    val maxModelLoadAttempts = 3

    // 功能模式切换
    var currentMode by remember { mutableStateOf(IndoorPerceptionMode.ENVIRONMENT) }

    // 帧处理计数器（跳帧）
    var frameSkipCounter by remember { mutableStateOf(0) }
    val processEveryNFrames = 3

    // 用于AI推理的Channel和协程作用域
    val frameChannel = remember { Channel<Bitmap>(Channel.CONFLATED) }
    val detectionScope = rememberCoroutineScope()

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            lastAnnouncement = "相机权限已获取，点击开始室内感知"
            viewModel.speak("相机权限已获取")
        } else {
            lastAnnouncement = "相机权限被拒绝，无法进行室内感知"
            viewModel.speak("需要相机权限才能使用室内感知功能")
        }
    }

    // 模型加载函数（支持重试）
    suspend fun loadModelWithRetry(): Boolean {
        isModelLoading = true
        showModelRetryButton = false
        modelLoadAttempts++
        
        Timber.d("IndoorPerceptionScreen 开始加载模型，第 $modelLoadAttempts 次尝试")
        lastAnnouncement = "正在加载AI检测模型..."
        viewModel.speak("正在加载室内感知模型")

        return try {
            val loaded = indoorDetector.loadModel()
            isModelLoading = false
            isModelReady = loaded

            if (loaded) {
                modelLoadAttempts = 0
                val isAiLoaded = indoorDetector.isAiModelLoaded()
                val isMlKitLoaded = indoorDetector.isMlKitInitialized()
                
                if (isAiLoaded) {
                    lastAnnouncement = "室内感知已启动，正在扫描周围环境"
                    viewModel.speak("室内感知已启动，正在扫描周围环境")
                } else {
                    lastAnnouncement = "室内感知已启动（基础模式），仅支持场景识别"
                    viewModel.speak("室内感知已启动基础模式，支持场景识别")
                }
                Timber.i("模型加载成功: AI=$isAiLoaded, MLKit=$isMlKitLoaded")
            } else {
                if (modelLoadAttempts < maxModelLoadAttempts) {
                    lastAnnouncement = "模型加载失败，可点击重试"
                    showModelRetryButton = true
                    Timber.w("模型加载失败，第 $modelLoadAttempts 次尝试，显示重试按钮")
                } else {
                    lastAnnouncement = "模型加载多次失败，请检查网络或设备存储空间"
                    viewModel.speak("模型加载失败，请检查设备后重试")
                    showModelRetryButton = true
                    Timber.e("模型加载 $maxModelLoadAttempts 次均失败")
                }
                isDetecting = false
            }
            loaded
        } catch (e: Exception) {
            Timber.e(e, "模型加载异常: ${e.message}")
            isModelLoading = false
            isModelReady = false
            
            if (modelLoadAttempts < maxModelLoadAttempts) {
                lastAnnouncement = "模型加载异常，可点击重试"
                showModelRetryButton = true
            } else {
                lastAnnouncement = "模型加载多次异常，请检查设备"
                showModelRetryButton = true
            }
            isDetecting = false
            false
        }
    }

    // 检测状态切换
    LaunchedEffect(isDetecting) {
        if (isDetecting) {
            loadModelWithRetry()
        } else {
            indoorDetector.unloadModel()
            isModelReady = false
            currentScene = null
            lastRoomType = null
            detectedObstacles = emptyList()
            modelLoadAttempts = 0
            showModelRetryButton = false
            viewModel.speak("室内感知已停止")
        }
    }

    // AI推理消费循环
    LaunchedEffect(isDetecting, isModelReady) {
        if (isDetecting && isModelReady) {
            for (bitmap in frameChannel) {
                if (!isActive || !isDetecting) break
                try {
                    val scene = indoorDetector.detect(bitmap)

                    withContext(Dispatchers.Main) {
                        currentScene = scene
                        detectedObstacles = scene.obstacles

                        // 场景变化播报
                        val now = System.currentTimeMillis()
                        if (scene.roomType != lastRoomType && now - lastAlertTime > 3000) {
                            lastAlertTime = now
                            lastRoomType = scene.roomType
                            val announcement = scene.getEntryAnnouncement()
                            lastAnnouncement = announcement
                            scope.launch {
                                voiceRepository.speak(announcement, queueMode = false)
                            }
                        }

                        // 高优先级障碍物预警
                        val highPriorityObstacles = scene.obstacles.filter {
                            it.type.priority >= 3 || it.distance < 2f
                        }

                        if (highPriorityObstacles.isNotEmpty() && now - lastObstacleAlertTime > 2000) {
                            lastObstacleAlertTime = now
                            val nearest = highPriorityObstacles.minByOrNull { it.distance }
                            nearest?.let { obstacle ->
                                val alertMsg = obstacle.type.getAlertMessage(
                                    obstacle.distance,
                                    obstacle.direction
                                )
                                lastAnnouncement = alertMsg
                                scope.launch {
                                    voiceRepository.speak(alertMsg, queueMode = true)
                                }
                            }
                        }

                        // 空间结构描述（每10秒一次）
                        if (now - lastAlertTime > 10000 && scene.roomType != RoomType.UNKNOWN) {
                            lastAlertTime = now
                            val spatialDesc = generateSpatialDescription(scene)
                            lastAnnouncement = spatialDesc
                            scope.launch {
                                voiceRepository.speak(spatialDesc, queueMode = false)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Timber.e(e, "室内感知推理异常")
                }
            }
        }
    }

    // 清理
    DisposableEffect(Unit) {
        onDispose {
            detectionScope.cancel()
            frameChannel.close()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "室内感知",
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E90FF)
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            isDetecting = false
                            scope.launch {
                                voiceRepository.speak("室内感知已关闭", queueMode = false)
                            }
                            onBack()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    if (!hasCameraPermission) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("授权相机")
                        }
                    } else {
                        Button(
                            onClick = { isDetecting = !isDetecting },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDetecting) MaterialTheme.colorScheme.error
                                else Color(0xFF1E90FF)
                            )
                        ) {
                            Text(if (isDetecting) "停止" else "开始")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF1E90FF).copy(alpha = 0.1f)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 功能模式选择
            IndoorModeSelector(
                currentMode = currentMode,
                onModeChange = { currentMode = it }
            )

            // 相机预览区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .semantics {
                        contentDescription = "相机预览区域，显示室内环境画面"
                    },
                contentAlignment = Alignment.Center
            ) {
                if (hasCameraPermission) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { previewView ->
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()

                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                    .setTargetResolution(android.util.Size(480, 640))
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(
                                            Executors.newSingleThreadExecutor()
                                        ) { imageProxy ->
                                            frameSkipCounter++
                                            if (frameSkipCounter % processEveryNFrames != 0) {
                                                imageProxy.close()
                                                return@setAnalyzer
                                            }

                                            if (isDetecting && isModelReady) {
                                                try {
                                                    val bitmap = imageProxy.toBitmap()
                                                    val rotatedBitmap = rotateBitmap(
                                                        bitmap,
                                                        imageProxy.imageInfo.rotationDegrees.toFloat()
                                                    )
                                                    frameChannel.trySend(rotatedBitmap)
                                                } catch (e: Exception) {
                                                    Timber.e(e, "帧处理异常")
                                                }
                                            }
                                            imageProxy.close()
                                        }
                                    }

                                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner, cameraSelector, preview, imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    Timber.e(e, "相机绑定失败")
                                }
                            }, ContextCompat.getMainExecutor(context))
                        }
                    )

                    // 场景类型叠加层
                    if (isDetecting && currentScene != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 32.dp)
                                .background(
                                    Color(0xFF1E90FF).copy(alpha = 0.9f),
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(horizontal = 32.dp, vertical = 16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Home,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = currentScene?.roomType?.chineseName ?: "识别中...",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 32.sp
                                )
                            }
                        }
                    }

                    // 模型加载中
                    if (isModelLoading) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("正在加载AI模型...", color = Color.White, fontSize = 16.sp)
                            }
                        }
                    }

                    // 模型加载失败重试按钮
                    if (showModelRetryButton && !isModelLoading) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFFFA500),
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    "模型加载失败",
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    "已尝试 $modelLoadAttempts/$maxModelLoadAttempts 次",
                                    color = Color.White.copy(alpha = 0.7f),
                                    fontSize = 14.sp
                                )
                                Spacer(modifier = Modifier.height(16.dp))
                                Button(
                                    onClick = {
                                        scope.launch {
                                            loadModelWithRetry()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFF1E90FF)
                                    )
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("重新加载模型")
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.background(Color.Black)
                    ) {
                        Text(text = "需要相机权限", color = Color.White, fontSize = 24.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("授权相机权限")
                        }
                    }
                }
            }

            // 检测结果区域
            IndoorResultsPanel(
                currentScene = currentScene,
                detectedObstacles = detectedObstacles,
                lastAnnouncement = lastAnnouncement,
                isDetecting = isDetecting
            )
        }
    }
}

/**
 * 室内感知模式选择器
 */
@Composable
private fun IndoorModeSelector(
    currentMode: IndoorPerceptionMode,
    onModeChange: (IndoorPerceptionMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        IndoorPerceptionMode.values().forEach { mode ->
            FilterChip(
                selected = currentMode == mode,
                onClick = { onModeChange(mode) },
                label = { Text(mode.chineseName) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 室内感知模式枚举
 */
enum class IndoorPerceptionMode(val chineseName: String) {
    ENVIRONMENT("环境识别"),
    OBSTACLE("障碍物检测"),
    NAVIGATION("室内导航"),
    OCR("文字识别")
}

/**
 * 检测结果面板
 */
@Composable
private fun IndoorResultsPanel(
    currentScene: IndoorScene?,
    detectedObstacles: List<DetectedIndoorObstacle>,
    lastAnnouncement: String,
    isDetecting: Boolean
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 语音播报状态
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (currentScene?.roomType == RoomType.UNKNOWN || currentScene == null)
                    MaterialTheme.colorScheme.surfaceVariant
                else
                    Color(0xFF1E90FF).copy(alpha = 0.1f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "语音播报",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (currentScene != null && currentScene.roomType != RoomType.UNKNOWN) {
                        Text(
                            text = "置信度 ${(currentScene.confidence * 100).toInt()}%",
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFF1E90FF)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = lastAnnouncement,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 检测到的障碍物列表
        Text(
            text = "检测到的障碍物 (${detectedObstacles.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (detectedObstacles.isEmpty()) {
            Text(
                text = if (isDetecting) "正在扫描室内环境..." else "未开始检测",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column {
                detectedObstacles.take(5).forEach { obstacle ->
                    IndoorObstacleResultItem(obstacle)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * 室内障碍物结果项
 */
@Composable
private fun IndoorObstacleResultItem(obstacle: DetectedIndoorObstacle) {
    val priorityColor = when (obstacle.type.priority) {
        5 -> Color.Red
        4 -> Color(0xFFFF4444)
        3 -> Color(0xFFFFA500)
        2 -> Color(0xFFFFD700)
        else -> Color.Green
    }
    val priorityText = when (obstacle.type.priority) {
        5 -> "极高"
        4 -> "高"
        3 -> "中"
        2 -> "低"
        else -> "普通"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = priorityColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(priorityColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = obstacle.type.chineseName.first().toString(),
                    fontWeight = FontWeight.Bold,
                    color = priorityColor,
                    fontSize = 18.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = obstacle.type.chineseName,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "距离: ${obstacle.distance.toInt()}米 | 方位: ${obstacle.direction.getChineseName()} | 置信度: ${(obstacle.confidence * 100).toInt()}%",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Box(
                modifier = Modifier
                    .background(priorityColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = priorityText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 生成空间结构描述
 */
private fun generateSpatialDescription(scene: IndoorScene): String {
    val roomName = scene.roomType.chineseName
    val obstacleCount = scene.obstacles.size

    return if (obstacleCount > 0) {
        val nearestObstacle = scene.obstacles.minByOrNull { it.distance }
        val obstacleDesc = nearestObstacle?.let {
            "前方${it.distance.toInt()}米处有${it.type.chineseName}"
        } ?: ""
        "当前位于$roomName，$obstacleDesc，共检测到$obstacleCount 个物品"
    } else {
        "当前位于$roomName，环境开阔，未检测到障碍物"
    }
}

/**
 * 旋转 Bitmap
 */
private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees)
    return Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
    )
}

/**
 * Hilt EntryPoint
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface IndoorPerceptionEntryPoint {
    fun voiceRepository(): VoiceRepository
    fun indoorDetector(): IndoorDetector
}
