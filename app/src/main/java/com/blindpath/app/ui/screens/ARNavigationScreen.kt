package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.view.PreviewView
import android.view.ViewGroup
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindpath.app.ui.ar.*
import com.blindpath.app.ui.viewmodel.NavigationViewModel
import com.blindpath.base.navigation.BlindPathGuidanceEngine

import com.blindpath.module_obstacle.data.detection.TactilePavingDetector
import com.blindpath.base.navigation.model.TactilePavingResult
import com.blindpath.module_obstacle.data.detection.TrafficLightClassifier
import com.blindpath.base.navigation.model.TrafficLightState
import com.blindpath.base.navigation.model.Direction
import com.blindpath.module_obstacle.data.detection.SceneClassifier
import com.blindpath.module_obstacle.domain.ObstacleRepository

import com.blindpath.module_voice.viewmodel.VoiceInteractionViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * AR实景导航屏幕 - PRD V2.0 对齐版
 *
 * 界面布局（对齐PRD文档）：
 * ┌─────────────────────────────────────────┐
 * │  【状态栏层】顶部半透明                    │
 * │  距目的地 XX米 | 预计 X分钟 | 模式切换    │
 * ├─────────────────────────────────────────┤
 * │                                         │
 * │  【全屏摄像头画面】+ AI识别叠加层          │
 * │  盲道引导线 / 障碍物框 / 红绿灯标注       │
 * │  斑马线标注 / 路牌识别 / 路沿标注         │
 * │                                         │
 * ├─────────────────────────────────────────┤
 * │  【底部操作栏】半透明                      │
 * │  SOS紧急求助(红) | 语音指令(绿)          │
 * └─────────────────────────────────────────┘
 *
 * 设计原则：
 * - 摄像头画面即"眼睛"：全屏实时显示
 * - AI识别结果叠加在画面上
 * - 语音+震动是主要反馈方式
 * - 操作极简：语音指令+物理按键
 *
 * 障碍物预警规则（PRD对齐）：
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
    val announcement by navViewModel.announcement.collectAsStateWithLifecycle()

    // ★ 模式切换过渡状态
    var isVisible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { isVisible = true }

    // ★ 震动控制器
    val vibrator = remember {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(android.os.VibratorManager::class.java)
            vibratorManager?.defaultVibrator
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

    // ===== AR检测状态 =====
    var isOnSidewalk by remember { mutableStateOf(true) }
    var arObstacles by remember { mutableStateOf(listOf<ARObstacle>()) }
    var dangerLevel by remember { mutableStateOf(ARDangerLevel.LOW) }
    var arNavState by remember { mutableStateOf(ARNavigationState()) }

    // ★ PRD新增：红绿灯/斑马线/路沿 可视化状态
    var detectedTrafficLight by remember { mutableStateOf<TrafficLightState?>(null) }
    var detectedCrosswalk by remember { mutableStateOf(false) }
    var detectedCurb by remember { mutableStateOf(false) }

    // CameraX
    val frameChannel = remember { Channel<Bitmap>(Channel.CONFLATED) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var isDetectionActive by remember { mutableStateOf(false) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    var previewView by remember { mutableStateOf<PreviewView?>(null) }
    var sceneClassifierRef by remember { mutableStateOf<SceneClassifier?>(null) }

    // ★ PRD V2.0 第三期：盲道/红绿灯专用检测器
    val tactilePavingDetector = remember { TactilePavingDetector() }
    val trafficLightClassifier = remember { TrafficLightClassifier() }

    // ★ PRD V2.0 第三期：盲道引导状态 + 弱光状态
    val blindPathGuidanceState by navViewModel.blindPathGuidanceState.collectAsStateWithLifecycle()
    val lowLightState by navViewModel.lowLightState.collectAsStateWithLifecycle()

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

    // ★ 绑定CameraX生命周期 — 仅获取CameraProvider，不做绑定
    // 统一在 LaunchedEffect 中绑定，避免双重绑定冲突
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    cameraProviderRef = provider
                    Timber.i("ARNav: CameraProvider已就绪，等待PreviewView")
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

    // ★ CameraX 统一绑定：PreviewView + CameraProvider 都就绪后绑定 Preview + ImageAnalysis
    LaunchedEffect(previewView, cameraProviderRef) {
        val pv = previewView ?: return@LaunchedEffect
        val provider = cameraProviderRef ?: return@LaunchedEffect
        try {
            // 先解绑所有用例，避免冲突
            provider.unbindAll()

            val imageAnalysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setTargetResolution(android.util.Size(640, 480))
                .build()

            imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                val bitmap = imageProxyToBitmap(imageProxy)
                if (bitmap != null) {
                    frameChannel.trySend(bitmap)
                }
                imageProxy.close()
            }

            val preview = Preview.Builder().build()
            preview.setSurfaceProvider(pv.surfaceProvider)

            provider.bindToLifecycle(
                lifecycleOwner,
                CameraSelector.DEFAULT_BACK_CAMERA,
                preview,
                imageAnalysis
            )
            Timber.i("ARNav: CameraX已启动 (Preview+ImageAnalysis)")
        } catch (e: Exception) {
            Timber.w(e, "ARNav: CameraX绑定失败")
        }
    }

    // ★ 帧处理管道 — PRD多路识别并行架构
    // 优先级：紧急危险 > 交通信号 > 道路变化 > 一般提示
    LaunchedEffect(isDetectionActive) {
        if (!isDetectionActive) return@LaunchedEffect
        var frameCount = 0
        var lastAnnounceTime = 0L
        var lastEmergencyVibrateTime = 0L

        while (!frameChannel.isClosedForReceive) {
            val result = frameChannel.receiveCatching()
            val bitmap = result.getOrNull() ?: continue
            frameCount++

            // 每4帧处理一次（平衡性能与实时性）
            if (frameCount % 4 != 0) {
                bitmap.recycle()
                continue
            }

            try {
                // ★ 帧处理顺序（PRD第三期）：弱光→障碍物→盲道→场景→红绿灯，bitmap最后回收
                // 1. 弱光检测（优先，不回收bitmap）
                navViewModel.processLowLightDetection(bitmap)

                // 2. AI障碍物检测
                val bytes = ByteArray(bitmap.width * bitmap.height * 4)
                java.nio.ByteBuffer.wrap(bytes).let { buf ->
                    bitmap.copyPixelsToBuffer(buf)
                }
                val obstacles = try {
                    obstacleRepository.processFrame(bytes, bitmap.width, bitmap.height)
                } catch (e: Exception) { emptyList() }

                // 3. 盲道检测（CV方案）
                val tactileResult: TactilePavingResult? = try {
                    tactilePavingDetector.detect(bitmap)
                } catch (e: Exception) { null }
                navViewModel.processBlindPathDetection(tactileResult)

                // 4. 场景识别
                sceneClassifierRef?.recognizeScene(bitmap, obstacles)?.let { sceneResult ->
                    isOnSidewalk = when (sceneResult.sceneType) {
                        com.blindpath.module_obstacle.domain.model.SceneType.SIDEWALK -> true
                        com.blindpath.module_obstacle.domain.model.SceneType.ROAD -> false
                        else -> isOnSidewalk
                    }

                    // ★ 斑马线检测
                    detectedCrosswalk = sceneResult.sceneType ==
                        com.blindpath.module_obstacle.domain.model.SceneType.CROSSWALK

                    // ★ 路沿检测
                    detectedCurb = sceneResult.sceneType ==
                        com.blindpath.module_obstacle.domain.model.SceneType.CURB

                    // 5. 红绿灯分类
                    val trafficLightObs = obstacles.firstOrNull {
                        it.type == com.blindpath.module_obstacle.domain.model.ObstacleType.TRAFFIC_LIGHT
                    }
                    if (trafficLightObs != null) {
                        val tlState = trafficLightClassifier.classify(
                            bitmap,
                            trafficLightObs.boundingBox.left,
                            trafficLightObs.boundingBox.top,
                            trafficLightObs.boundingBox.right,
                            trafficLightObs.boundingBox.bottom
                        )
                        navViewModel.processTrafficLightDetection(tlState)
                        detectedTrafficLight = tlState
                    } else {
                        navViewModel.processTrafficLightDetection(TrafficLightState.UNKNOWN)
                        detectedTrafficLight = null
                    }
                }

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
                                obs.distance < 1.5f -> ARDangerLevel.CRITICAL
                                obs.distance <= 3f -> ARDangerLevel.HIGH
                                else -> ARDangerLevel.LOW
                            }
                        )
                    }
                } else {
                    arObstacles = emptyList()
                }

                // ★ 危险等级评估（PRD对齐）
                val criticalObs = obstacles.firstOrNull { it.distance < 1.5f }
                val warningObs = obstacles.firstOrNull { it.distance in 1.5f..3f }

                dangerLevel = when {
                    criticalObs != null -> ARDangerLevel.CRITICAL
                    warningObs != null -> ARDangerLevel.HIGH
                    detectedCrosswalk -> ARDangerLevel.MEDIUM
                    !isOnSidewalk -> ARDangerLevel.MEDIUM
                    else -> ARDangerLevel.LOW
                }

                // ★ 预警优先级仲裁（PRD对齐）：
                // 1. 紧急危险（<1.5m）→ 立即打断，"立即停止！"
                // 2. 交通信号（绿灯变绿）→ 立即播报
                // 3. 道路变化（斑马线/路口/盲道转向）→ 提前3~5米播报
                // 4. 一般提示 → 空闲时播报
                val now = System.currentTimeMillis()
                when {
                    // ★ 优先级1：< 1.5m 紧急 → 语音+连续震动
                    criticalObs != null -> {
                        if (now - lastEmergencyVibrateTime > 300L) {
                            lastEmergencyVibrateTime = now
                            tryVibrate(vibrator, longArrayOf(0, 100, 50, 100))
                        }
                        if (now - lastAnnounceTime > 2000L) {
                            lastAnnounceTime = now
                            viewModel.speak("立即停止！前方${criticalObs.type.chineseName}距离${String.format("%.1f", criticalObs.distance)}米")
                        }
                    }
                    // ★ 优先级2：绿灯亮起 → 立即播报 + 一次长震
                    detectedTrafficLight == TrafficLightState.GREEN -> {
                        if (now - lastAnnounceTime > 5000L) {
                            lastAnnounceTime = now
                            tryVibrate(vibrator, longArrayOf(0, 500))
                            viewModel.speak("绿灯亮起，可以通行")
                        }
                    }
                    // ★ 优先级2：红灯 → 播报等待
                    detectedTrafficLight == TrafficLightState.RED -> {
                        if (now - lastAnnounceTime > 10000L) {
                            lastAnnounceTime = now
                            viewModel.speak("红灯，请等待")
                        }
                    }
                    // ★ 优先级3：斑马线到达
                    detectedCrosswalk -> {
                        if (now - lastAnnounceTime > 8000L) {
                            lastAnnounceTime = now
                            tryVibrate(vibrator, longArrayOf(0, 100, 100, 100))
                            viewModel.speak("前方到达斑马线，请准备过马路")
                        }
                    }
                    // ★ 优先级3：路沿检测
                    detectedCurb -> {
                        if (now - lastAnnounceTime > 8000L) {
                            lastAnnounceTime = now
                            viewModel.speak("前方有路沿，请小心台阶")
                        }
                    }
                    // ★ 1.5m-3m 预警
                    warningObs != null -> {
                        if (now - lastAnnounceTime > 5000L) {
                            lastAnnounceTime = now
                            viewModel.speak("前方有障碍物，${warningObs.type.chineseName}在${warningObs.direction.getChineseName()}${warningObs.distance.toInt()}米")
                        }
                    }
                    // 盲道偏离
                    !isOnSidewalk && now - lastAnnounceTime > 10000L -> {
                        lastAnnounceTime = now
                        viewModel.speak("已偏离盲道，请回到盲道上")
                    }
                    else -> {}
                }

                // bitmap 最后回收
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

    // ★ PRD：弱光自动亮度调节
    val activity = context as? android.app.Activity
    LaunchedEffect(lowLightState.isLowLight) {
        activity?.window?.let { window ->
            val layoutParams = window.attributes
            layoutParams.screenBrightness = if (lowLightState.isLowLight) 1.0f else -1.0f
            window.attributes = layoutParams
        }
    }

    // ===== UI：PRD对齐布局 =====
    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(animationSpec = tween(450)),
        exit = fadeOut(animationSpec = tween(450)),
        modifier = modifier
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            // ★ 第1层：全屏摄像头实时画面（底层）
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        layoutParams = ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                    }.also { pv ->
                        // ★ 仅设置引用，不在factory中绑定！统一在LaunchedEffect中绑定
                        previewView = pv
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // ★ 第2层：AI识别叠加层
            AROverlay(
                navigationState = arNavState,
                obstacles = arObstacles,
                isOnSidewalk = isOnSidewalk,
                dangerLevel = dangerLevel,
                blindPathGuidanceState = blindPathGuidanceState,
                modifier = Modifier.fillMaxSize()
            )

            // ★ 第3层：顶部状态栏（半透明，不遮挡主画面）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(Color.Black.copy(alpha = 0.6f))
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 返回按钮
                IconButton(
                    onClick = { isVisible = false; onBack() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, "返回", tint = Color.White)
                }

                Spacer(modifier = Modifier.width(8.dp))

                // 导航信息
                if (uiState.isRunning) {
                    Text(
                        "距目的地 ${uiState.totalDistance}",
                        color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "预计 ${uiState.totalDuration}",
                        color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp
                    )
                } else {
                    Text(
                        "AR实景导航",
                        color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                // ★ 危险等级指示灯
                val dangerColor = when (dangerLevel) {
                    ARDangerLevel.CRITICAL -> Color(0xFFF44336)
                    ARDangerLevel.HIGH -> Color(0xFFFF9800)
                    ARDangerLevel.MEDIUM -> Color(0xFFFFC107)
                    ARDangerLevel.LOW -> Color(0xFF4CAF50)
                }
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(dangerColor)
                )

                Spacer(modifier = Modifier.width(8.dp))

                // 切换语音模式
                IconButton(
                    onClick = { isVisible = false; onSwitchToVoice() },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(Icons.Default.RecordVoiceOver, "切换语音模式", tint = Color.White)
                }
            }

            // ★ 红绿灯状态悬浮指示（画面中上方）
            detectedTrafficLight?.let { tlState ->
                val tlColor = when (tlState) {
                    TrafficLightState.RED -> Color(0xFFF44336)
                    TrafficLightState.GREEN -> Color(0xFF4CAF50)
                    TrafficLightState.YELLOW -> Color(0xFFFFC107)
                    else -> Color.Transparent
                }
                if (tlColor != Color.Transparent) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.TopCenter)
                            .padding(top = 80.dp)
                            .size(48.dp)
                            .clip(CircleShape)
                            .background(tlColor.copy(alpha = 0.8f))
                            .padding(4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            Icons.Default.Traffic,
                            contentDescription = when (tlState) {
                                TrafficLightState.RED -> "红灯"
                                TrafficLightState.GREEN -> "绿灯"
                                TrafficLightState.YELLOW -> "黄灯"
                                else -> "信号灯"
                            },
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            // ★ 斑马线提示浮层
            if (detectedCrosswalk) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = 80.dp, end = 12.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFFC107).copy(alpha = 0.85f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.DirectionsWalk, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("斑马线", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ★ 路沿提示浮层
            if (detectedCurb) {
                Surface(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(top = if (detectedCrosswalk) 120.dp else 80.dp, end = 12.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFFFF9800).copy(alpha = 0.85f)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, null, tint = Color.White, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("路沿", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // ★ 第4层：底部操作栏（PRD核心 — SOS + 语音指令）
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Color.Black.copy(alpha = 0.7f),
                        RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    .navigationBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                // ★ 检测状态信息条
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 盲道状态
                    Icon(
                        if (isOnSidewalk) Icons.Default.CheckCircle else Icons.Default.Warning,
                        contentDescription = null,
                        tint = if (isOnSidewalk) Color(0xFF4CAF50) else Color(0xFFFF9800),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        if (isOnSidewalk) "盲道" else "偏离",
                        color = Color.White, fontSize = 12.sp
                    )

                    Spacer(modifier = Modifier.width(12.dp))

                    // 障碍物信息
                    if (arObstacles.isNotEmpty()) {
                        arObstacles.take(2).forEach { obs ->
                            Text(
                                "⚠ ${obs.type} ${obs.distance.toInt()}m",
                                color = Color.White.copy(alpha = 0.9f),
                                fontSize = 12.sp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // 危险等级标签
                    val (dangerText, dangerBg) = when (dangerLevel) {
                        ARDangerLevel.CRITICAL -> "⚠ 紧急" to Color(0xFFF44336)
                        ARDangerLevel.HIGH -> "⚠ 注意" to Color(0xFFFF9800)
                        ARDangerLevel.MEDIUM -> "⚡ 注意" to Color(0xFFFFC107)
                        ARDangerLevel.LOW -> "✓ 安全" to Color(0xFF4CAF50)
                    }
                    Box(
                        modifier = Modifier
                            .background(dangerBg, RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Text(dangerText, color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }

                // 语音播报
                if (announcement.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        announcement,
                        color = Color(0xFF4CAF50),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ★ PRD核心按钮：SOS + 语音 + 模式切换
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // SOS紧急求助（大红色按钮，PRD要求大尺寸）
                    Button(
                        onClick = {
                            viewModel.speak("SOS紧急求助已触发，正在发送位置给紧急联系人")
                            navViewModel.handleVolumeDownLongPress()
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF44336)),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.Default.Emergency,
                            contentDescription = "SOS紧急求助",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // 语音指令按钮（大绿色，长按说话）
                    var isVoicePressed by remember { mutableStateOf(false) }
                    Button(
                        onClick = {
                            viewModel.speak("请说出指令，例如：我在哪、还有多远、切换到语音模式")
                        },
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isVoicePressed) Color(0xFF2E7D32) else Color(0xFF4CAF50)
                        ),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.Default.Mic,
                            contentDescription = "语音指令",
                            tint = Color.White,
                            modifier = Modifier.size(28.dp)
                        )
                    }

                    // 切换语音模式按钮
                    OutlinedButton(
                        onClick = {
                            isVisible = false
                            onSwitchToVoice()
                        },
                        modifier = Modifier.size(56.dp),
                        shape = CircleShape,
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                        contentPadding = PaddingValues(0.dp)
                    ) {
                        Icon(
                            Icons.Default.RecordVoiceOver,
                            contentDescription = "切换语音导航",
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    // 结束导航按钮（导航中显示）
                    if (uiState.isRunning) {
                        Button(
                            onClick = {
                                navViewModel.exitNavigation()
                                isVisible = false
                                onBack()
                            },
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF757575)),
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "结束导航",
                                tint = Color.White,
                                modifier = Modifier.size(24.dp)
                            )
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

/**
 * ★ CameraX 1.3.1 原生 Bitmap 转换
 *
 * 使用 ImageProxy.toBitmap() 方法（CameraX 1.3.0+ 支持），
 * 正确处理 YUV_420_888 格式，替代之前错误的 JPEG 解码方式。
 *
 * 之前的问题：直接读取 planes[0].buffer 按 JPEG 解码 → 返回 null
 * 修复方式：使用 CameraX 内置转换 → 正确生成 Bitmap
 */
private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    return try {
        // CameraX 1.3.0+ 内置方法，正确处理 YUV_420_888 → ARGB Bitmap
        imageProxy.toBitmap()
    } catch (e: Exception) {
        Timber.w(e, "imageProxyToBitmap: toBitmap()失败，尝试手动转换")
        try {
            // 降级方案：手动 YUV → NV21 → JPEG → Bitmap
            val image = imageProxy.image ?: return null
            val yBuffer = image.planes[0].buffer
            val uBuffer = image.planes[1].buffer
            val vBuffer = image.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = android.graphics.YuvImage(
                nv21, android.graphics.ImageFormat.NV21,
                image.width, image.height, null
            )
            val out = java.io.ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, image.width, image.height), 90, out
            )
            val imageBytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        } catch (e2: Exception) {
            Timber.w(e2, "imageProxyToBitmap: 手动转换也失败")
            null
        }
    }
}

/** 方向转换为方位角 */
private fun Direction.getBearing(): Float {
    return when (this) {
        Direction.CENTER -> 0f
        Direction.LEFT_FRONT -> -45f
        Direction.LEFT -> -90f
        Direction.BACK -> 180f
        Direction.RIGHT -> 90f
        Direction.RIGHT_FRONT -> 45f
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
                    vibrator.vibrate(android.os.VibrationEffect.createWaveform(pattern, -1))
                } else {
                    vibrator.vibrate(pattern, -1)
                }
            }
        }
    } catch (e: Exception) {
        Timber.w(e, "Vibration failed")
    }
}
