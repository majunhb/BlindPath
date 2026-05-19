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
import androidx.compose.material.icons.filled.Home
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
import com.blindpath.module_indoor.domain.model.DetectedIndoorObstacle
import com.blindpath.module_indoor.domain.model.IndoorObstacleType
import com.blindpath.module_indoor.domain.model.IndoorScene
import com.blindpath.module_indoor.domain.model.RoomType
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import java.util.concurrent.Executors

/**
 * 室内环境识别界面
 * 1. CameraX 后置摄像头实时预览
 * 2. ImageAnalysis 每帧送入 IndoorDetector 进行场景和障碍物识别
 * 3. 显示当前房间类型（大字体）
 * 4. 显示检测到的室内障碍物列表
 * 5. 语音播报：场景变化时播报（如"进入客厅"），障碍物近距离预警
 * 6. 使用 Channel 架构（非阻塞）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun IndoorScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // 通过 Hilt EntryPoint 获取依赖
    val appContext = context.applicationContext
    val voiceRepository = remember {
        EntryPointAccessors.fromApplication(
            appContext,
            IndoorScreenEntryPoint::class.java
        ).voiceRepository()
    }
    val indoorDetector = remember {
        EntryPointAccessors.fromApplication(
            appContext,
            IndoorScreenEntryPoint::class.java
        ).indoorDetector()
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
    var currentScene by remember { mutableStateOf<IndoorScene?>(null) }
    var lastRoomType by remember { mutableStateOf<RoomType?>(null) }
    var detectedObstacles by remember { mutableStateOf<List<DetectedIndoorObstacle>>(emptyList()) }
    var lastAlertTime by remember { mutableStateOf(0L) }
    var lastObstacleAlertTime by remember { mutableStateOf(0L) }

    // 每帧都处理，Channel capacity=1会自动丢弃旧帧，不会堆积
    var frameSkipCounter by remember { mutableStateOf(0) }
    val processEveryNFrames = 1

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
            lastAnnouncement = "相机权限被拒绝，无法进行室内环境识别"
        }
    }

    // AI检测主循环
    LaunchedEffect(isDetecting) {
        if (isDetecting) {
            // 加载AI模型
            isModelLoading = true
            lastAnnouncement = "正在加载AI检测模型..."
            val loaded = indoorDetector.loadModel()
            isModelLoading = false
            isModelReady = loaded

            if (loaded) {
                lastAnnouncement = "室内环境识别已开启，正在扫描周围环境"
                // 语音播报
                voiceRepository.speak("室内环境识别已开启，正在扫描周围环境", queueMode = false)
            } else {
                lastAnnouncement = "室内环境识别启动失败，请检查设备后重试"
                voiceRepository.speak("室内环境识别启动失败，请检查设备后重试", queueMode = false)
            }
        } else {
            // 停止检测
            indoorDetector.unloadModel()
            isModelReady = false
            currentScene = null
            lastRoomType = null
            detectedObstacles = emptyList()
        }
    }

    // AI推理消费循环 - 从Channel接收帧并执行推理
    LaunchedEffect(isDetecting, isModelReady) {
        if (isDetecting && isModelReady) {
            for (bitmap in frameChannel) {
                if (!isActive || !isDetecting) break
                try {
                    val scene = indoorDetector.detect(bitmap)

                    // 回到主线程更新UI
                    withContext(Dispatchers.Main) {
                        currentScene = scene
                        detectedObstacles = scene.obstacles

                        // 检测场景变化并播报
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

                        // 检测高优先级障碍物并预警
                        val highPriorityObstacles = scene.obstacles.filter {
                            it.type.priority >= 2 || it.distance < 2f
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

                        // 如果场景未变化且没有障碍物，更新状态提示
                        if (scene.roomType == lastRoomType && highPriorityObstacles.isEmpty()) {
                            lastAnnouncement = "当前位于${scene.roomType.chineseName}，环境扫描中"
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
                title = { Text("室内环境识别") },
                navigationIcon = {
                    IconButton(onClick = {
                        isDetecting = false
                        scope.launch { voiceRepository.speak("室内环境识别已关闭", queueMode = false) }
                        onBackClick()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
                }
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

                                // 预览
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                // 帧分析 - 用于AI推理
                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                    .setTargetResolution(android.util.Size(480, 640))
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

                    // 当前场景类型叠加层（大字体显示）
                    if (isDetecting && currentScene != null) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 32.dp)
                                .background(
                                    MaterialTheme.colorScheme.primary.copy(alpha = 0.9f),
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
                        containerColor = if (currentScene?.roomType == RoomType.UNKNOWN || currentScene == null)
                            MaterialTheme.colorScheme.surfaceVariant
                        else
                            MaterialTheme.colorScheme.primaryContainer
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
                            if (currentScene != null && currentScene?.roomType != RoomType.UNKNOWN) {
                                Text(
                                    text = "置信度 ${(currentScene?.confidence?.times(100))?.toInt() ?: 0}%",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.primary
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
                    text = "检测到的物品 (${detectedObstacles.size})",
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
                            IndoorObstacleItem(obstacle)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun IndoorObstacleItem(obstacle: DetectedIndoorObstacle) {
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
interface IndoorScreenEntryPoint {
    fun voiceRepository(): VoiceRepository
    fun indoorDetector(): IndoorDetector
}
