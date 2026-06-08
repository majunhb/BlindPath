package com.blindpath.app.ui.screens

import com.blindpath.module_obstacle.domain.model.ObstacleState

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
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
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.*
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.launch

/**
 * 障碍物检测界面 - 实景应用级
 * 1. CameraX 后置摄像头实时预览（由 Repository 管理）
 * 2. 通过 obstacleRepository.obstacleState 监听 AI 检测结果
 * 3. 检测结果通过 VoiceRepository TTS 实时语音播报
 * 4. 分级预警：危险(<1m) / 提醒(1-2m) / 安全(>2m)
 *
 * 关键修复：Screen 不再自己管理 CameraX 和 AI 推理，完全依赖 Repository
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
    val obstacleRepository = remember {
        EntryPointAccessors.fromApplication(
            appContext,
            ObstacleDetectionEntryPoint::class.java
        ).obstacleRepository()
    }

    // 关键修复：通过 Repository 的 StateFlow 监听检测结果
    val obstacleState by obstacleRepository.obstacleState.collectAsState(initial = ObstacleState())

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var isDetecting by remember { mutableStateOf(false) }
    var isModelLoading by remember { mutableStateOf(false) }
    var lastAnnouncement by remember { mutableStateOf("请授权相机权限后开始检测") }

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

    // 关键修复：设置 LifecycleOwner 和 SurfaceProvider
    DisposableEffect(lifecycleOwner) {
        obstacleRepository.setLifecycleOwner(lifecycleOwner)
        onDispose { }
    }

    // 关键修复：检测控制由 Repository 统一管理
    LaunchedEffect(isDetecting) {
        if (isDetecting) {
            isModelLoading = true
            lastAnnouncement = "正在加载AI检测模型..."

            val initResult = obstacleRepository.initialize()
            val startResult = obstacleRepository.startDetection()

            isModelLoading = false

            if (startResult is com.blindpath.base.common.Result.Success) {
                val announcement = if (obstacleState.isModelLoaded) "AI检测模型已加载，环境感知已启动" else "辅助检测模式已启动，检测能力有限"
                lastAnnouncement = announcement
                voiceRepository.speak(announcement, queueMode = false)
            } else {
                lastAnnouncement = "障碍物检测启动失败，请检查设备后重试"
                voiceRepository.speak("障碍物检测启动失败，请检查设备后重试", queueMode = false)
                isDetecting = false
            }
        } else {
            obstacleRepository.stopDetection()
            lastAnnouncement = "障碍物检测已停止"
        }
    }

    // 关键修复：监听 obstacleState 变化更新 UI
    LaunchedEffect(obstacleState.currentAlert) {
        obstacleState.currentAlert?.let { alert ->
            lastAnnouncement = alert.description
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("障碍物检测", fontWeight = FontWeight.Bold) },
                navigationIcon = {
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
            // 相机预览区域（由 Repository 提供 SurfaceProvider）
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
                            // 关键修复：将 SurfaceProvider 交给 Repository 管理
                            obstacleRepository.setPreviewSurfaceProvider(previewView.surfaceProvider)

                            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()
                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }
                                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                try {
                                    cameraProvider.unbindAll()
                                    // 关键修复：预览只绑定 Preview，不绑定 ImageAnalysis
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner, cameraSelector, preview
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
                                        when (obstacleState.currentAlert?.level) {
                                            AlertLevel.DANGER -> Color.Red.copy(alpha = 0.9f)
                                            AlertLevel.WARNING -> Color(0xFFFFA500).copy(alpha = 0.9f)
                                            AlertLevel.SAFE -> Color.Green.copy(alpha = 0.8f)
                                            AlertLevel.UNKNOWN -> Color.Gray.copy(alpha = 0.8f)
                                            else -> Color.Gray.copy(alpha = 0.8f)
                                        },
                                        RoundedCornerShape(8.dp)
                                    )
                                    .padding(horizontal = 16.dp, vertical = 8.dp)
                            ) {
                                Text(
                                    text = when (obstacleState.currentAlert?.level) {
                                        AlertLevel.DANGER -> "⚠ 危险！前方有障碍物"
                                        AlertLevel.WARNING -> "⚡ 注意！前方有障碍物"
                                        AlertLevel.SAFE -> "● 安全，前方畅通"
                                        AlertLevel.UNKNOWN -> "○ 检测能力有限，请谨慎通行"
                                        else -> "○ 等待检测..."
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
                        containerColor = when (obstacleState.currentAlert?.level) {
                            AlertLevel.DANGER -> Color.Red.copy(alpha = 0.15f)
                            AlertLevel.WARNING -> Color(0xFFFFA500).copy(alpha = 0.15f)
                            AlertLevel.SAFE -> MaterialTheme.colorScheme.primaryContainer
                            AlertLevel.UNKNOWN -> MaterialTheme.colorScheme.surfaceVariant
                            else -> MaterialTheme.colorScheme.surfaceVariant
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
                                text = when (obstacleState.currentAlert?.level) {
                                    AlertLevel.DANGER -> "危险"
                                    AlertLevel.WARNING -> "提醒"
                                    AlertLevel.SAFE -> "安全"
                                    AlertLevel.UNKNOWN -> "未知"
                                    else -> "等待中"
                                },
                                color = when (obstacleState.currentAlert?.level) {
                                    AlertLevel.DANGER -> Color.Red
                                    AlertLevel.WARNING -> Color(0xFFFFA500)
                                    AlertLevel.SAFE -> Color.Green
                                    AlertLevel.UNKNOWN -> Color.Gray
                                    else -> Color.Gray
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
                    text = "实时检测结果 (${obstacleState.detectedObstacles.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(8.dp))

                if (obstacleState.detectedObstacles.isEmpty()) {
                    Text(
                        text = if (isDetecting) "正在扫描前方环境..." else "未开始检测",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column {
                        obstacleState.detectedObstacles.forEach { obstacle ->
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
 * Hilt EntryPoint - 用于在 Composable 中获取依赖
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ObstacleDetectionEntryPoint {
    fun voiceRepository(): VoiceRepository
    fun obstacleRepository(): ObstacleRepository
}


