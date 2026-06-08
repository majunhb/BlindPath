package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
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
import com.blindpath.base.common.AlertLevel
import com.blindpath.module_obstacle.data.detection.AIDetector
import com.blindpath.module_obstacle.domain.model.*
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 障碍物检测界面 - 实景应用级
 * 1. CameraX 后置摄像头实时预览
 * 2. ImageAnalysis 每帧送入 AIDetector 进行 TFLite 推理
 * 3. 检测结果通过 VoiceRepository TTS 实时语音播报
 * 4. 分级预警：危险(<1m) / 提醒(1-2m) / 安全(>2m)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObstacleDetectionScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // 通过 Hilt EntryPoint 获取依赖（Composable 不支持直接 @Inject）
    val appContext = context.applicationContext
    val voiceRepository = remember {
        EntryPointAccessors.fromApplication(
            appContext,
            ObstacleDetectionEntryPoint::class.java
        ).voiceRepository()
    }
    val aiDetector = remember {
        EntryPointAccessors.fromApplication(
            appContext,
            ObstacleDetectionEntryPoint::class.java
        ).aiDetector()
    }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var isDetecting by remember { mutableStateOf(false) }
    var isModelLoading by remember { mutableStateOf(false) }
    var isModelReady by remember { mutableStateOf(false) }
    var lastAnnouncement by remember { mutableStateOf("请授权相机权限后开始检测") }
    var detectedObstacles by remember {
        mutableStateOf<List<com.blindpath.module_obstacle.domain.model.DetectedObstacle>>(emptyList())
    }
    var alertLevel by remember { mutableStateOf(AlertLevel.SAFE) }
    var detectionFps by remember { mutableStateOf(0) }
    var frameCount by remember { mutableStateOf(0) }
    var lastAlertTime by remember { mutableStateOf(0L) }

    // 播报去重：记录每个障碍物的最后播报时间，10秒内不重复
    val lastAnnouncedObstacles = remember { mutableMapOf<String, Long>() }

    // 跳帧处理：每3帧处理1帧，降低CPU负载
    var frameSkipCounter by remember { mutableStateOf(0) }
    val processEveryNFrames = 3

    // AI推理专用协程作用域和通道
    val detectionScope = remember { CoroutineScope(Dispatchers.IO + SupervisorJob()) }
    val frameChannel = remember { Channel<Bitmap>(capacity = 1) }

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            lastAnnouncement = "相机权限已获取，点击开始检测"
        } else {
            lastAnnouncement = "相机权限被拒绝，无法进行障碍物检测"
        }
    }

    // AI检测主循环
    LaunchedEffect(isDetecting) {
        if (isDetecting) {
            // 加载AI模型
            isModelLoading = true
            lastAnnouncement = "正在加载AI检测模型..."
            val loaded = aiDetector.loadModel()
            isModelLoading = false
            isModelReady = loaded

            if (loaded) {
                lastAnnouncement = "障碍物检测已开启，正在扫描前方环境"
                // 语音播报
                voiceRepository.speak("障碍物检测已开启，正在扫描前方环境", queueMode = false)
            } else {
                lastAnnouncement = "障碍物检测启动失败，请检查设备后重试"
                voiceRepository.speak("障碍物检测启动失败，请检查设备后重试", queueMode = false)
            }
        } else {
            // 停止检测
            aiDetector.unloadModel()
            isModelReady = false
        }
    }

    // AI推理消费循环 - 从Channel接收帧并执行推理
    LaunchedEffect(isDetecting, isModelReady) {
        if (isDetecting && isModelReady) {
            for (bitmap in frameChannel) {
                if (!isActive || !isDetecting) break
                try {
                    val results = aiDetector.detect(bitmap)

                    // 回到主线程更新UI
                    withContext(Dispatchers.Main) {
                        if (results.isNotEmpty()) {
                            // 按距离排序，最近的优先
                            val sorted = results.sortedBy { it.distance }
                            val nearest = sorted.first()

                            // 计算预警级别
                            val level = when {
                                nearest.distance < 1.0f -> AlertLevel.DANGER
                                nearest.distance < 2.0f -> AlertLevel.WARNING
                                else -> AlertLevel.SAFE
                            }

                            // 更新UI状态
                            detectedObstacles = sorted
                            alertLevel = level
                            frameCount++

                            // 生成播报文本
                            val alertMsg = nearest.type.getAlertMessage(
                                nearest.distance,
                                nearest.direction
                            )

                            // 仅播报 WARNING 和 DANGER 级别（去重：同一障碍物10秒内不重复）
                            val now = System.currentTimeMillis()
                            if (level == AlertLevel.DANGER || level == AlertLevel.WARNING) {
                                val dedupKey = nearest.type.getDeduplicationKey(nearest.direction)
                                val lastAnnounceTime = lastAnnouncedObstacles[dedupKey] ?: 0L
                                if (now - lastAnnounceTime > 10000) {
                                    lastAnnouncedObstacles[dedupKey] = now
                                    lastAlertTime = now
                                    lastAnnouncement = alertMsg

                                    // 使用分级播报系统
                                    val voiceType = when (level) {
                                        AlertLevel.DANGER -> com.blindpath.module_voice.domain.model.VoiceType.OBSTACLE_DANGER
                                        AlertLevel.WARNING -> com.blindpath.module_voice.domain.model.VoiceType.OBSTACLE_NORMAL
                                        else -> com.blindpath.module_voice.domain.model.VoiceType.OBSTACLE_LOW
                                    }

                                    scope.launch {
                                        val request = com.blindpath.module_voice.domain.model.VoiceRequest(
                                            text = alertMsg,
                                            type = voiceType,
                                            deduplicationKey = dedupKey
                                        )
                                        voiceRepository.announce(request)
                                    }
                                }
                            }
                        } else {
                            // 无障碍物
                            if (detectedObstacles.isNotEmpty()) {
                                detectedObstacles = emptyList()
                                alertLevel = AlertLevel.SAFE
                                lastAnnouncement = "前方道路畅通，未检测到障碍物"
                            }
                        }
                        Unit
                    }
                } catch (e: Exception) {
                    // 推理异常，继续下一帧
                }
            }
        }
    }

    // 清理协程作用域和通道
    DisposableEffect(Unit) {
        onDispose {
            detectionScope.cancel()
            frameChannel.close()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("障碍物检测", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    // 增强返回按钮视觉效果
                    TextButton(
                        onClick = {
                            isDetecting = false
                            scope.launch { voiceRepository.speak("障碍物检测已关闭", queueMode = false) }
                            onBackClick()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "返回主界面按钮"
                        }
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("返回", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    if (!hasCameraPermission) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("授权相机")
                        }
                    } else {
                        Button(
                            onClick = {
                                isDetecting = !isDetecting
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDetecting) MaterialTheme.colorScheme.error
                                else MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text(if (isDetecting) "停止检测" else "开始检测")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 真实相机预览 + AI帧分析
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .semantics {
                        contentDescription = "相机预览区域，显示前方实景画面"
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

                                // 预览
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                // 帧分析 - 用于AI推理
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(
                                            Executors.newSingleThreadExecutor()
                                        ) { imageProxy ->
                                            // 跳帧处理：每3帧处理1帧
                                            frameSkipCounter++
                                            if (frameSkipCounter % processEveryNFrames != 0) {
                                                imageProxy.close()
                                                return@setAnalyzer
                                            }

                                            if (isDetecting && isModelReady) {
                                                try {
                                                    // 将 ImageProxy 转为 Bitmap
                                                    val bitmap = imageProxy.toBitmap()
                                                    val rotatedBitmap = rotateBitmap(
                                                        bitmap,
                                                        imageProxy.imageInfo.rotationDegrees.toFloat()
                                                    )

                                                    // 通过Channel发送帧到AI推理协程（非阻塞）
                                                    frameChannel.trySend(rotatedBitmap)
                                                } catch (e: Exception) {
                                                    // 帧处理异常，继续下一帧
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
                                    // 相机绑定失败
                                }
                            }, ContextCompat.getMainExecutor(context))
                        }
                    )

                    // 检测状态叠加层
                    if (isDetecting) {
                        Column(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .background(
                                        when (alertLevel) {
                                            AlertLevel.DANGER -> Color.Red.copy(alpha = 0.9f)
                                            AlertLevel.WARNING -> Color(0xFFFFA500).copy(alpha = 0.9f)
                                            AlertLevel.SAFE -> Color.Green.copy(alpha = 0.8f)
                                            AlertLevel.UNKNOWN -> Color.Gray.copy(alpha = 0.8f)
                                        },
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = when (alertLevel) {
                                        AlertLevel.DANGER -> "⚠ 危险！前方有障碍物"
                                        AlertLevel.WARNING -> "⚡ 注意！前方有障碍物"
                                        AlertLevel.SAFE -> "● 安全，前方畅通"
                                        AlertLevel.UNKNOWN -> "○ 检测能力有限，请谨慎通行"
                                    },
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
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
                } else {
                    // 未授权状态
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                // 语音播报状态
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = when (alertLevel) {
                            AlertLevel.DANGER -> Color.Red.copy(alpha = 0.15f)
                            AlertLevel.WARNING -> Color(0xFFFFA500).copy(alpha = 0.15f)
                            AlertLevel.SAFE -> MaterialTheme.colorScheme.primaryContainer
                            AlertLevel.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
                        }
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
                            Text(
                                text = when (alertLevel) {
                                    AlertLevel.DANGER -> "危险"
                                    AlertLevel.WARNING -> "提醒"
                                    AlertLevel.SAFE -> "安全"
                                    AlertLevel.UNKNOWN -> "未知"
                                },
                                color = when (alertLevel) {
                                    AlertLevel.DANGER -> Color.Red
                                    AlertLevel.WARNING -> Color(0xFFFFA500)
                                    AlertLevel.SAFE -> Color.Green
                                    AlertLevel.UNKNOWN -> Color.Gray
                                }
                                color = when (alertLevel) {
                                    AlertLevel.DANGER -> Color.Red
                                    AlertLevel.WARNING -> Color(0xFFFFA500)
                                    AlertLevel.SAFE -> Color.Green
                                    AlertLevel.UNKNOWN -> Color.Gray
                                }
                            )
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
                    text = "实时检测结果 (${detectedObstacles.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (detectedObstacles.isEmpty()) {
                    Text(
                        text = if (isDetecting) "正在扫描前方环境..." else "未开始检测",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column {
                        detectedObstacles.forEach { obstacle ->
                            RealObstacleItem(obstacle)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun RealObstacleItem(obstacle: com.blindpath.module_obstacle.domain.model.DetectedObstacle) {
    val dangerColor = when {
        obstacle.distance < 1.0f -> Color.Red
        obstacle.distance < 2.0f -> Color(0xFFFFA500)
        else -> Color.Green
    }
    val levelText = when {
        obstacle.distance < 1.0f -> "危险"
        obstacle.distance < 2.0f -> "提醒"
        else -> "安全"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = dangerColor.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = dangerColor,
                modifier = Modifier.size(32.dp)
            )
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
                    .background(dangerColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = levelText,
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

/**
 * 旋转 Bitmap（摄像头图像需要根据传感器方向旋转）
 */
private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees)
    return Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
    )
}

/**
 * Hilt EntryPoint - 用于在 Composable 中获取依赖
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ObstacleDetectionEntryPoint {
    fun voiceRepository(): VoiceRepository
    fun aiDetector(): AIDetector
}
