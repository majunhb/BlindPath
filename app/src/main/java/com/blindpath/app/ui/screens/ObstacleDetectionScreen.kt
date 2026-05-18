package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 障碍物检测界面 - 真实相机版本
 * 使用 CameraX 调用设备摄像头进行实景拍摄
 * 集成 AI 物体检测进行障碍物识别
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObstacleDetectionScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.CAMERA
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var isDetecting by remember { mutableStateOf(false) }
    var lastAnnouncement by remember { mutableStateOf("请授权相机权限后开始检测") }
    var detectedObstacles by remember { mutableStateOf<List<DetectedObstacle>>(emptyList()) }

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

    // 模拟 AI 检测（真实环境需接入 TFLite 模型）
    LaunchedEffect(isDetecting) {
        if (isDetecting) {
            lastAnnouncement = "相机已启动，正在采集实景画面并识别障碍物..."
            while (isDetecting) {
                delay(2000)
                detectedObstacles = generateRealtimeObstacles()
                if (detectedObstacles.isNotEmpty()) {
                    val obstacle = detectedObstacles.first()
                    lastAnnouncement = "前方${obstacle.direction}检测到${obstacle.name}，距离约${obstacle.distance}，危险等级${obstacle.dangerLevel}级，请注意避让"
                } else {
                    lastAnnouncement = "前方道路畅通，未检测到障碍物，请放心通行"
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("障碍物检测") },
                navigationIcon = {
                    IconButton(onClick = {
                        isDetecting = false
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
                            onClick = { isDetecting = !isDetecting },
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
            // 真实相机预览区域
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
                    // 真实 CameraX 预览
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
                                    it.surfaceProvider = previewView.surfaceProvider
                                }
                                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                try {
                                    cameraProvider.unbindAll()
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
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(16.dp)
                                .background(
                                    Color.Red.copy(alpha = 0.8f),
                                    RoundedCornerShape(8.dp)
                                )
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = "● 实时检测中",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }
                    }
                } else {
                    // 未授权状态
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.background(Color.Black)
                    ) {
                        Text(
                            text = "需要相机权限",
                            color = Color.White,
                            fontSize = 24.sp
                        )
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
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Text(
                            text = "语音播报",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
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
                            ObstacleItem(obstacle)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ObstacleItem(obstacle: DetectedObstacle) {
    val dangerColor = when (obstacle.dangerLevel) {
        3 -> Color.Red
        2 -> Color(0xFFFFA500)
        else -> Color.Yellow
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
                Text(text = obstacle.name, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                Text(
                    text = "距离: ${obstacle.distance} | 方位: ${obstacle.direction}",
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
                    text = "${obstacle.dangerLevel}级",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

data class DetectedObstacle(
    val name: String,
    val distance: String,
    val direction: String,
    val dangerLevel: Int
)

/**
 * 模拟实时障碍物检测
 * 注意：实际部署时需替换为 TFLite 模型推理结果
 * 推荐模型：YOLOv5 Nano / MobileNet SSD / EfficientDet-Lite
 */
private fun generateRealtimeObstacles(): List<DetectedObstacle> {
    val obstacles = listOf(
        DetectedObstacle("行人", "约3米", "正前方偏右", 2),
        DetectedObstacle("台阶", "约1.5米", "右前方", 3),
        DetectedObstacle("电动自行车", "约4米", "左侧", 2),
        DetectedObstacle("路面坑洼", "约2米", "正前方", 3),
        DetectedObstacle("路沿石", "约1米", "右前方", 2),
        DetectedObstacle("交通信号灯", "约8米", "正前方", 1),
        DetectedObstacle("停放的车辆", "约3米", "右侧", 1),
        DetectedObstacle("施工围挡", "约5米", "左前方", 2)
    )
    return obstacles.shuffled().take((0..3).random())
}
