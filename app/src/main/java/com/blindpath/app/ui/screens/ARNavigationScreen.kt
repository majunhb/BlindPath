package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MyLocationStyle
import com.blindpath.app.ui.ar.*
import com.blindpath.app.ui.viewmodel.NavigationViewModel
import com.blindpath.module_navigation.domain.model.NavigationState
import com.blindpath.module_obstacle.data.detection.SceneClassifier
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_voice.viewmodel.VoiceInteractionViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.Executors

/**
 * AR实景导航屏幕 - 真正的增强现实导航体验
 * 
 * 功能：
 * 1. 摄像头实时画面
 * 2. AR导航箭头叠加
 * 3. 障碍物实时检测与标注
 * 4. 盲道状态实时指示
 * 5. 语音全程导航
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ARNavigationScreen(
    obstacleRepository: ObstacleRepository,
    viewModel: VoiceInteractionViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navViewModel: NavigationViewModel = hiltViewModel()

    // 状态收集
    val uiState by navViewModel.uiState.collectAsStateWithLifecycle()
    val destinationText by navViewModel.destinationText.collectAsStateWithLifecycle()
    val isPlanning by navViewModel.isPlanning.collectAsStateWithLifecycle()
    val announcement by navViewModel.announcement.collectAsStateWithLifecycle()

    // 权限
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
    }

    LaunchedEffect(Unit) {
        val needed = mutableListOf<String>()
        if (!hasLocationPermission) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!hasCameraPermission) needed.add(Manifest.permission.CAMERA)
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    // ===== AR状态 =====
    var isOnSidewalk by remember { mutableStateOf(true) }
    var arObstacles by remember { mutableStateOf(listOf<ARObstacle>()) }
    var dangerLevel by remember { mutableStateOf(ARDangerLevel.LOW) }
    
    // 导航状态（转换为AR格式）
    var arNavState by remember { mutableStateOf(ARNavigationState()) }

    // CameraX
    val frameChannel = remember { Channel<Bitmap>(Channel.CONFLATED) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var isDetectionActive by remember { mutableStateOf(false) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var sceneClassifierRef by remember { mutableStateOf<SceneClassifier?>(null) }

    // 加载模型
    LaunchedEffect(Unit) {
        val modelResult = obstacleRepository.loadModel()
        if (modelResult !is com.blindpath.base.common.Result.Error) {
            isDetectionActive = true
            Timber.i("ARNav: 障碍物检测模型加载成功")
        }
    }

    // 初始化SceneClassifier
    LaunchedEffect(Unit) {
        try {
            sceneClassifierRef = SceneClassifier(context)
            Timber.i("ARNav: SceneClassifier初始化成功")
        } catch (e: Exception) {
            Timber.w(e, "ARNav: SceneClassifier初始化失败")
        }
    }

    // 绑定CameraX
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    cameraProviderRef = provider
                    try {
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetResolution(android.util.Size(480, 640))
                            .build()
                        
                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            val bitmap = imageProxyToBitmap(imageProxy)
                            if (bitmap != null) {
                                frameChannel.trySend(bitmap)
                            }
                            imageProxy.close()
                        }

                        provider.bindToLifecycle(
                            owner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            imageAnalysis
                        )
                        Timber.i("ARNav: CameraX已启动")
                    } catch (e: Exception) {
                        Timber.w(e, "ARNav: CameraX启动失败")
                    }
                }, ContextCompat.getMainExecutor(context))
            }
            override fun onDestroy(owner: LifecycleOwner) {
                cameraProviderRef?.unbindAll()
                cameraExecutor.shutdownNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // 处理帧数据
    LaunchedEffect(isDetectionActive) {
        if (!isDetectionActive) return@LaunchedEffect
        var frameCount = 0
        var lastAnnounceTime = 0L
        
        while (!frameChannel.isClosedForReceive) {
            val result = frameChannel.receiveCatching()
            val bitmap = result.getOrNull() ?: continue
            frameCount++
            
            if (frameCount % 4 != 0) {
                bitmap.recycle()
                continue
            }
            
            try {
                // AI障碍物检测
                val bytes = ByteArray(bitmap.width * bitmap.height * 4)
                bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
                val obstacles = try {
                    obstacleRepository.processFrame(bytes, bitmap.width, bitmap.height)
                } catch (e: Exception) { emptyList() }
                
                // 更新AR障碍物列表
                if (obstacles.isNotEmpty()) {
                    val phoneHeading = 0f  // TODO: 从传感器获取
                    arObstacles = obstacles.map { obs ->
                        val bearing = obs.direction.getBearing()
                        val (sx, sy) = calculateObstacleScreenPosition(phoneHeading, bearing, obs.distance)
                        ARObstacle(
                            screenX = sx,
                            screenY = sy,
                            distance = obs.distance,
                            type = obs.type.chineseName,
                            dangerLevel = when {
                                obs.distance < 1f -> ARDangerLevel.CRITICAL
                                obs.distance < 2f -> ARDangerLevel.HIGH
                                obs.distance < 4f -> ARDangerLevel.MEDIUM
                                else -> ARDangerLevel.LOW
                            }
                        )
                    }
                } else {
                    arObstacles = emptyList()
                }
                
                // 场景识别
                sceneClassifierRef?.recognizeScene(bitmap, obstacles)?.let { sceneResult ->
                    isOnSidewalk = when (sceneResult.sceneType) {
                        com.blindpath.module_obstacle.domain.model.SceneType.SIDEWALK -> true
                        com.blindpath.module_obstacle.domain.model.SceneType.ROAD -> false
                        else -> isOnSidewalk
                    }
                }
                
                // 更新危险等级
                val criticalObs = obstacles.any { it.distance < 1f }
                val highObs = obstacles.any { it.distance < 3f }
                dangerLevel = when {
                    criticalObs -> ARDangerLevel.CRITICAL
                    highObs -> ARDangerLevel.HIGH
                    !isOnSidewalk -> ARDangerLevel.MEDIUM
                    else -> ARDangerLevel.LOW
                }
                
                // 语音播报（每10秒或重要事件）
                val now = System.currentTimeMillis()
                if (obstacles.any { it.distance < 2f } || now - lastAnnounceTime > 10000L) {
                    lastAnnounceTime = now
                    
                    // 障碍物预警
                    obstacles.firstOrNull { it.distance < 3f }?.let { obs ->
                        val dir = obs.direction.getChineseName()
                        viewModel.speak("注意${obs.type.chineseName}在$dir${obs.distance.toInt()}米")
                    }
                    
                    // 盲道状态
                    if (!isOnSidewalk) {
                        viewModel.speak("已偏离盲道，请回到盲道上")
                    }
                }
                
                bitmap.recycle()
            } catch (e: Exception) {
                Timber.w(e, "ARNav: 帧处理失败")
                try { bitmap.recycle() } catch (_: Exception) {}
            }
        }
    }

    // 更新AR导航状态
    LaunchedEffect(uiState) {
        if (uiState.isRunning && uiState.routeSteps.isNotEmpty()) {
            val currentStep = uiState.routeSteps.getOrNull(uiState.currentStepIndex)
            val nextStep = uiState.routeSteps.getOrNull(uiState.currentStepIndex + 1)
            
            val arDir = when {
                currentStep?.instruction?.contains("左") == true && 
                currentStep.instruction.contains("前方") -> ARDirection.SHARP_LEFT
                currentStep?.instruction?.contains("右") == true && 
                currentStep.instruction.contains("前方") -> ARDirection.SHARP_RIGHT
                currentStep?.instruction?.contains("左转") == true -> ARDirection.LEFT
                currentStep?.instruction?.contains("右转") == true -> ARDirection.RIGHT
                currentStep?.instruction?.contains("掉头") == true -> ARDirection.U_TURN
                currentStep?.instruction?.contains("到达") == true -> ARDirection.ARRIVE
                else -> ARDirection.STRAIGHT
            }
            
            arNavState = ARNavigationState(
                isNavigating = true,
                nextDirection = arDir,
                distanceToNextTurn = currentStep?.distance?.replace("[^0-9.]".toRegex(), "")?.toFloatOrNull() ?: 0f,
                routeAngle = 0f,
                currentRoadName = currentStep?.instruction ?: "",
                nextRoadName = nextStep?.instruction ?: ""
            )
        } else {
            arNavState = ARNavigationState(isNavigating = false)
        }
    }

    // ===== UI =====
    Box(modifier = modifier.fillMaxSize()) {
        // 顶部状态栏
        TopAppBar(
            title = { Text("AR实景导航", fontWeight = FontWeight.Bold) },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                }
            },
            actions = {
                // 帮助按钮
                IconButton(onClick = { /* TODO: 帮助 */ }) {
                    Icon(Icons.Default.Info, contentDescription = "帮助")
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Black.copy(alpha = 0.6f),
                titleContentColor = Color.White
            )
        )

        // AR叠加层
        AROverlay(
            navigationState = arNavState,
            obstacles = arObstacles,
            isOnSidewalk = isOnSidewalk,
            dangerLevel = dangerLevel,
            modifier = Modifier.fillMaxSize()
        )

        // 底部导航信息面板
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(
                    Color.Black.copy(alpha = 0.7f),
                    RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                )
                .padding(16.dp)
        ) {
            // 盲道状态
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (isOnSidewalk) Icons.Default.CheckCircle else Icons.Default.Warning,
                    contentDescription = null,
                    tint = if (isOnSidewalk) Color(0xFF4CAF50) else Color(0xFFFF9800),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isOnSidewalk) "正在盲道上" else "已偏离盲道",
                    color = Color.White,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                
                // 危险等级指示
                val dangerColor = when (dangerLevel) {
                    ARDangerLevel.CRITICAL -> Color(0xFFF44336)
                    ARDangerLevel.HIGH -> Color(0xFFFF9800)
                    ARDangerLevel.MEDIUM -> Color(0xFFFFC107)
                    ARDangerLevel.LOW -> Color(0xFF4CAF50)
                }
                Box(
                    modifier = Modifier
                        .background(dangerColor, RoundedCornerShape(4.dp))
                        .padding(horizontal = 8.dp, vertical = 4.dp)
                ) {
                    Text(
                        when (dangerLevel) {
                            ARDangerLevel.CRITICAL -> "⚠️ 紧急"
                            ARDangerLevel.HIGH -> "⚠️ 高风险"
                            ARDangerLevel.MEDIUM -> "⚡ 注意"
                            ARDangerLevel.LOW -> "✓ 安全"
                        },
                        color = Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 障碍物信息
            if (arObstacles.isNotEmpty()) {
                arObstacles.take(2).forEach { obs ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("⚠️", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            "${obs.type} ${obs.distance.toInt()}米",
                            color = Color.White.copy(alpha = 0.9f),
                            fontSize = 13.sp
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 语音播报
            if (announcement.isNotEmpty()) {
                Text(
                    announcement,
                    color = Color(0xFF4CAF50),
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                if (!uiState.isRunning && !isPlanning) {
                    // 目的地输入
                    OutlinedTextField(
                        value = destinationText,
                        onValueChange = { navViewModel.updateDestination(it) },
                        label = { Text("目的地") },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.outlinedTextFieldColors(
                            focusedBorderColor = Color(0xFF4CAF50),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f)
                        ),
                        textStyle = LocalTextStyle.current.copy(color = Color.White)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            navViewModel.updateDestination(destinationText)
                            navViewModel.startNavigation()
                        },
                        enabled = destinationText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Icon(Icons.Default.Navigation, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("开始")
                    }
                } else if (uiState.isRunning) {
                    Button(
                        onClick = { navViewModel.exitNavigation() },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("结束AR导航", fontWeight = FontWeight.Bold)
                    }
                } else {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = Color(0xFF4CAF50)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("正在规划路线...", color = Color.White)
                }
            }
        }
    }
}

/** Bitmap转换 */
private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    return try {
        val buffer = imageProxy.planes[0].buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    } catch (e: Exception) {
        Timber.w(e, "imageProxyToBitmap失败")
        null
    }
}

/** 方向转换为方位角 */
private fun com.blindpath.module_obstacle.domain.model.Direction.getBearing(): Float {
    return when (this) {
        com.blindpath.module_obstacle.domain.model.Direction.CENTER -> 0f
        com.blindpath.module_obstacle.domain.model.Direction.LEFT_FRONT -> -45f
        com.blindpath.module_obstacle.domain.model.Direction.LEFT -> -90f
        com.blindpath.module_obstacle.domain.model.Direction.BACK -> 180f
        com.blindpath.module_obstacle.domain.model.Direction.RIGHT -> 90f
        com.blindpath.module_obstacle.domain.model.Direction.RIGHT_FRONT -> 45f
    }
}

/** 方向获取中文名称 */
private fun com.blindpath.module_obstacle.domain.model.Direction.getChineseName(): String {
    return when (this) {
        com.blindpath.module_obstacle.domain.model.Direction.CENTER -> "正前方"
        com.blindpath.module_obstacle.domain.model.Direction.LEFT_FRONT -> "左前方"
        com.blindpath.module_obstacle.domain.model.Direction.LEFT -> "左侧"
        com.blindpath.module_obstacle.domain.model.Direction.BACK -> "后方"
        com.blindpath.module_obstacle.domain.model.Direction.RIGHT -> "右侧"
        com.blindpath.module_obstacle.domain.model.Direction.RIGHT_FRONT -> "右前方"
    }
}
