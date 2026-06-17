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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindpath.app.ui.ar.*
import com.blindpath.app.ui.viewmodel.NavigationViewModel
import com.blindpath.base.navigation.NavigationMode
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
 * AR实景导航屏幕 - PRD V2.0 整合版
 *
 * 功能：
 * 1. 摄像头实时画面
 * 2. AR导航箭头叠加
 * 3. 障碍物实时检测与标注
 * 4. 盲道状态实时指示
 * 5. 语音全程导航
 * 6. ★ 模式切换（AR → 语音导航）淡入淡出 ≤ 1秒
 *
 * PRD 障碍物预警规则（已对齐）：
 * - 距离 > 3m：不预警
 * - 1.5m ≤ 距离 ≤ 3m：语音提示"前方有障碍物"
 * - 距离 < 1.5m：语音 + 连续震动，紧急提示"立即停止"
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ARNavigationScreen(
    obstacleRepository: ObstacleRepository,
    viewModel: VoiceInteractionViewModel,
    onBack: () -> Unit,
    onSwitchToVoice: () -> Unit = {},
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

    // ★ 模式切换过渡状态
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // ★ 震动控制器（紧急预警用）
    val vibratorManager = remember {
        context.getSystemService(android.os.VibratorManager::class.java)
            ?: context.getSystemService(android.os.VibratorService::class.java)
    }
    val vibrator = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            (vibratorManager as? android.os.VibratorManager)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(android.os.Vibrator::class.java)
        }
    }

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

    // 处理帧数据 — ★ 障碍物预警规则已对齐 PRD
    LaunchedEffect(isDetectionActive) {
        if (!isDetectionActive) return@LaunchedEffect
        var frameCount = 0
        var lastAnnounceTime = 0L
        var lastEmergencyVibrateTime = 0L
        
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
                            // ★ PRD 对齐：基于距离的危险等级
                            dangerLevel = when {
                                obs.distance < 1.5f -> ARDangerLevel.CRITICAL   // < 1.5m → 紧急
                                obs.distance <= 3f -> ARDangerLevel.HIGH        // 1.5m-3m → 高风险
                                else -> ARDangerLevel.LOW                       // > 3m → 安全/不预警
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
                
                // ★ PRD 障碍物预警规则对齐
                // - 距离 > 3m：不预警
                // - 1.5m ≤ 距离 ≤ 3m：语音提示"前方有障碍物"
                // - 距离 < 1.5m：语音 + 连续震动，紧急提示"立即停止"
                val criticalObs = obstacles.firstOrNull { it.distance < 1.5f }
                val warningObs = obstacles.firstOrNull { it.distance in 1.5f..3f }

                dangerLevel = when {
                    criticalObs != null -> ARDangerLevel.CRITICAL
                    warningObs != null -> ARDangerLevel.HIGH
                    !isOnSidewalk -> ARDangerLevel.MEDIUM
                    else -> ARDangerLevel.LOW
                }

                // 语音播报
                val now = System.currentTimeMillis()
                when {
                    // ★ < 1.5m：语音 + 连续震动，"立即停止"
                    criticalObs != null -> {
                        // 连续震动（每 300ms 重复）
                        if (now - lastEmergencyVibrateTime > 300L) {
                            lastEmergencyVibrateTime = now
                            tryVibrate(vibrator, longArrayOf(0, 100, 50, 100))
                        }
                        // 语音播报（2秒冷却）
                        if (now - lastAnnounceTime > 2000L) {
                            lastAnnounceTime = now
                            viewModel.speak("立即停止！前方${criticalObs.type.chineseName}距离${String.format("%.1f", criticalObs.distance)}米")
                        }
                    }
                    // ★ 1.5m-3m：语音提示"前方有障碍物"
                    warningObs != null -> {
                        if (now - lastAnnounceTime > 5000L) {
                            lastAnnounceTime = now
                            viewModel.speak("前方有障碍物，${warningObs.type.chineseName}在${warningObs.direction.getChineseName()}${warningObs.distance.toInt()}米")
                        }
                    }
                    // ★ > 3m：不预警
                    else -> {}
                }
                
                // 盲道状态播报
                if (!isOnSidewalk && now - lastAnnounceTime > 8000L) {
                    lastAnnounceTime = now
                    viewModel.speak("已偏离盲道，请回到盲道上")
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

    // ===== UI：带淡入淡出过渡 =====
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(450)),
        exit = fadeOut(animationSpec = tween(450)),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            // 顶部状态栏
            TopAppBar(
                title = { Text("AR实景导航", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = {
                        isVisible = false
                        onBack()
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    // ★ 切换语音模式按钮
                    IconButton(onClick = {
                        isVisible = false
                        onSwitchToVoice()
                    }) {
                        Icon(
                            Icons.Default.RecordVoiceOver,
                            contentDescription = "切换语音模式",
                            tint = Color.White
                        )
                    }
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
                // 盲道状态 + 危险等级
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

                // ★ 模式切换按钮（底部大按钮）
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            isVisible = false
                            onSwitchToVoice()
                        },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)
                    ) {
                        Icon(Icons.Default.RecordVoiceOver, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("切换语音模式", fontWeight = FontWeight.Bold)
                    }

                    if (uiState.isRunning) {
                        Button(
                            onClick = { navViewModel.exitNavigation() },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336))
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("结束导航", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 辅助函数
// ============================================================================

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

/**
 * 触发震动（兼容不同 API 级别）
 */
@Suppress("DEPRECATION", "MissingPermission")
private fun tryVibrate(vibrator: Any?, pattern: LongArray) {
    try {
        when {
            vibrator is android.os.Vibrator -> {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, 0))
                } else {
                    vibrator.vibrate(pattern, 0)
                }
            }
        }
    } catch (e: Exception) {
        Timber.w(e, "Vibration failed")
    }
}
