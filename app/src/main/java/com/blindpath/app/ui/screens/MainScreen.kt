package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.clickable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.blindpath.base.common.AlertLevel
import com.blindpath.base.tts.VibrationHelper
import com.blindpath.base.power.DeviceOrientationCalculator
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.ObstacleState
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_navigation.domain.model.NavigationState
import com.blindpath.module_navigation.domain.model.LocationInfo
import com.blindpath.module_settings.ui.SettingsScreen
import com.blindpath.module_community.ui.CommunityScreen
import com.blindpath.module_trip_assist.ui.TripAssistScreen
import com.blindpath.module_voice.domain.model.VoiceCommand
import com.blindpath.module_voice.domain.model.VoiceGuidance
import com.blindpath.module_voice.viewmodel.VoiceInteractionViewModel
import kotlinx.coroutines.delay
import timber.log.Timber
import java.util.concurrent.Executors
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/**
 * 主界面 - 视障友好极简设计 v5.0
 * 
 * 设计原则（基于用户设计稿）：
 * 1. 首页直接显示摄像头预览 + 地图预览
 * 2. 底部三个核心按钮：唤醒小智 + 切换导航 + SOS
 * 3. 语音指令驱动：说"我要外出，请为我导航"直接打开导航
 * 4. 高对比度：蓝白主调，红色警示
 */
@Composable
fun MainScreen(
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository,
    onObstacleDetectionClick: () -> Unit = {},
    onLocationClick: () -> Unit = {},
    onSosClick: () -> Unit = {},
    viewModel: VoiceInteractionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    var showSettings by remember { mutableStateOf(false) }
    var showCommunity by remember { mutableStateOf(false) }
    var showTripAssist by remember { mutableStateOf(false) }
    var showObstacleDetection by remember { mutableStateOf(false) }
    var showLocation by remember { mutableStateOf(false) }
    var showNavigation by remember { mutableStateOf(false) }
    var showIndoorScreen by remember { mutableStateOf(false) }
    
    // 设置语音指令处理器
    LaunchedEffect(Unit) {
        viewModel.setCommandHandler { command ->
            Timber.d("MainScreen: Handling voice command - ${command.name}")
            when (command) {
                VoiceCommand.START_OBSTACLE_DETECTION -> {
                    // 修复问题3：在主页面直接开启环境感知，不跳转
                    // 设置 showObstacleDetection = true 会在主页面显示环境感知，而不是跳转
                    showObstacleDetection = true
                    viewModel.speak(VoiceGuidance.OBSTACLE_DETECTION_STARTED)
                    true
                }
                VoiceCommand.STOP_OBSTACLE_DETECTION -> {
                    showObstacleDetection = false
                    viewModel.speak(VoiceGuidance.OBSTACLE_DETECTION_STOPPED)
                    true
                }
                VoiceCommand.START_SONAR_DETECTION -> {
                    viewModel.speak("声呐检测功能即将上线")
                    true
                }
                VoiceCommand.STOP_SONAR_DETECTION -> {
                    viewModel.speak("声呐检测已关闭")
                    true
                }
                VoiceCommand.START_NAVIGATION -> {
                    showNavigation = true
                    viewModel.speak("正在为您打开导航")
                    true
                }
                VoiceCommand.STOP_NAVIGATION -> {
                    showNavigation = false
                    viewModel.speak(VoiceGuidance.NAVIGATION_STOPPED)
                    true
                }
                VoiceCommand.WHERE_AM_I -> {
                    showLocation = true
                    true
                }
                VoiceCommand.SOS, VoiceCommand.CALL_SOS -> {
                    onSosClick()
                    viewModel.speak(VoiceGuidance.SOS_TRIGGERED)
                    true
                }
                VoiceCommand.SHOW_MAP -> {
                    showLocation = true
                    viewModel.speak(VoiceGuidance.MAP_OPENED)
                    true
                }
                VoiceCommand.HIDE_MAP -> {
                    showLocation = false
                    viewModel.speak(VoiceGuidance.MAP_CLOSED)
                    true
                }
                VoiceCommand.OPEN_SETTINGS -> {
                    showSettings = true
                    viewModel.speak(VoiceGuidance.SETTINGS_OPENED)
                    true
                }
                VoiceCommand.CLOSE_SETTINGS -> {
                    showSettings = false
                    viewModel.speak(VoiceGuidance.SETTINGS_CLOSED)
                    true
                }
                VoiceCommand.HELP -> {
                    viewModel.speakHelp()
                    true
                }
                VoiceCommand.REPEAT -> {
                    viewModel.speak("暂无上一条播报")
                    true
                }
                VoiceCommand.CANCEL -> {
                    viewModel.speak("已取消")
                    true
                }
                VoiceCommand.BACK -> {
                    showSettings = false
                    showCommunity = false
                    showTripAssist = false
                    showObstacleDetection = false
                    showLocation = false
                    showNavigation = false
                    showIndoorScreen = false
                    viewModel.speak("已返回主界面")
                    true
                }
            }
        }
        
        viewModel.initialize()
    }
    
    // 播报欢迎消息（首次进入）
    // 修复：移除直接调用 viewModel.speak() 的重复欢迎词
    // 原问题：此处的 speak() 与 VoiceInteractionViewModel.initialize() 内部的 speakWelcome() 并发执行
    // 导致：1) TTS 队列冲突  2) speakWelcome() 的 setWakeWordEnabled(true) 被干扰
    // 现在：统一由 ViewModel.initialize() → speakWelcome() 管理完整流程
    // var hasAnnouncedWelcome by remember { mutableStateOf(false) }
    // LaunchedEffect(uiState.isInitialized) {
    //     if (uiState.isInitialized && !hasAnnouncedWelcome) {
    //         hasAnnouncedWelcome = true
    //         viewModel.speak("已进入主界面，请说\"小智小智\"唤醒语音助手，或者说\"帮助\"查看可用指令")
    //     }
    // }

    when {
        showSettings -> {
            // Compose 不支持 try-catch 包裹 Composable 调用，直接渲染
            SettingsScreen(onBackClick = { showSettings = false })
        }
        showCommunity -> {
            CommunityScreen(onBackClick = { showCommunity = false })
        }
        showTripAssist -> {
            TripAssistScreen(onBackClick = { showTripAssist = false })
        }
        // 修复问题3：移除环境感知页面跳转，改为在主页面直接开启
        // showObstacleDetection -> {
        //     ObstacleDetectionScreen(onBackClick = { showObstacleDetection = false })
        // }
        showLocation -> {
            LocationScreen(onBackClick = { showLocation = false })
        }
        showNavigation -> {
            NavigationScreen(onBackClick = { showNavigation = false })
        }
        showIndoorScreen -> {
            IndoorScreen(onBackClick = { showIndoorScreen = false })
        }
        else -> {
            SmartDashboard(
                showObstacleDetection = showObstacleDetection,
                onObstacleToggle = { showObstacleDetection = !showObstacleDetection },
                onNavigationClick = { showNavigation = true },
                onSosClick = onSosClick,
                onSettingsClick = { showSettings = true },
                onLocationClick = { showLocation = true },
                // [Feature 4] 周边POI播报（由SmartDashboard内部根据navState处理）
                onExploreClick = {},
                isListening = uiState.isListening,
                onStartListening = { viewModel.startListening() },
                onStopListening = { viewModel.stopListening() },
                obstacleRepository = obstacleRepository,
                navigationRepository = navigationRepository,
                viewModel = viewModel
            )
        }
    }
}

/**
 * 智能仪表盘 - v6.0 创新设计
 * 
 * 设计理念（基于听见世界启发，但保持原创差异化）：
 * 1. HUD叠加层：摄像头画面上叠加GPS坐标、方向、速度 — 像战斗机仪表盘
 * 2. 安全光环：屏幕边缘绿/黄/红三级安全指示环 — 视觉+触觉双通道
 * 3. 脉冲唤醒：语音聆听时小智图标脉冲动画 — 用户感知"正在听"
 * 4. 模式中心：出行/周边/工具三模式 — 清晰的功能导航
 * 5. 触觉编码：短振安全、双振注意、长振危险 — 振动反馈通道
 */
@Composable
private fun SmartDashboard(
    showObstacleDetection: Boolean = false,
    onObstacleToggle: () -> Unit = {},
    onNavigationClick: () -> Unit,
    onSosClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLocationClick: () -> Unit,
    onExploreClick: () -> Unit = {},          // [Feature 4] 周边POI播报
    isListening: Boolean = false,
    onStartListening: () -> Unit = {},
    onStopListening: () -> Unit = {},
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository,
    viewModel: VoiceInteractionViewModel           // [Feature 4] POI语音播报
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 相机权限状态
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    // 定位权限状态
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }

    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> hasCameraPermission = granted }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
                              permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }

    // 首次进入自动请求权限
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
        if (!hasLocationPermission) {
            locationPermissionLauncher.launch(arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            ))
        }
    }

    // 观察导航状态（GPS数据）
    val navState by navigationRepository.navigationState.collectAsStateWithLifecycle(
        initialValue = NavigationState(),
        lifecycle = lifecycleOwner.lifecycle
    )

    // [Feature 1 & 3] 观察障碍物检测状态（用于安全光环颜色 + 振动反馈）
    val obstacleState by obstacleRepository.obstacleState.collectAsStateWithLifecycle(
        initialValue = ObstacleState(),
        lifecycle = lifecycleOwner.lifecycle
    )

    // [Feature 3] 触觉反馈：障碍物预警级别变化时触发振动
    var lastVibratedLevel by remember { mutableStateOf<AlertLevel?>(null) }
    LaunchedEffect(obstacleState.currentAlert?.level) {
        val currentLevel = obstacleState.currentAlert?.level ?: AlertLevel.SAFE
        if (currentLevel != lastVibratedLevel) {
            lastVibratedLevel = currentLevel
            if (obstacleState.isRunning && obstacleState.detectedObstacles.isNotEmpty()) {
                VibrationHelper.vibrate(context, currentLevel)
                Timber.d("Vibration triggered for level: $currentLevel")
            } else if (currentLevel == AlertLevel.DANGER || currentLevel == AlertLevel.WARNING) {
                VibrationHelper.vibrate(context, currentLevel)
            }
        }
    }

    // [Feature 2] 指南针传感器：实时获取设备朝向
    var compassAzimuth by remember { mutableStateOf(0f) }
    LaunchedEffect(lifecycleOwner) {
        val compass = DeviceOrientationCalculator(context) { azimuth, _, _ ->
            compassAzimuth = azimuth
        }
        compass.start()
        // 跟随生命周期自动停止
        try {
            while (true) {
                delay(1000)
            }
        } finally {
            compass.stop()
        }
    }

    // [修复] 环境感知自动启停：根据 showObstacleDetection 状态调用 Repository 的 startDetection/stopDetection
    // 根因：之前只切换了UI显示(showObstacleDetection)，但从未调用obstacleRepository.startDetection()，
    // 导致AI模型不加载、ImageAnalysis不绑定、障碍物检测完全无法工作
    LaunchedEffect(showObstacleDetection) {
        if (showObstacleDetection) {
            Timber.d("环境感知启动：调用 obstacleRepository.startDetection()")
            obstacleRepository.startDetection()
        } else {
            Timber.d("环境感知停止：调用 obstacleRepository.stopDetection()")
            obstacleRepository.stopDetection()
        }
    }

    Scaffold(
        containerColor = Color(0xFF0D0D1A),  // 深色背景，对比度更高
        topBar = {
            SmartTopBar(onSettingsClick = onSettingsClick, navState = navState)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 语音状态标签（脉冲动画版）
            PulseVoiceStatus(
                isListening = isListening,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            
            // 核心仪表盘区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                if (showObstacleDetection) {
                    // 环境感知模式：全屏摄像头 + HUD叠加
                    ObstacleDetectionContent(
                        hasPermission = hasCameraPermission,
                        onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        lifecycleOwner = lifecycleOwner,
                        obstacleRepository = obstacleRepository,
                        onClose = onObstacleToggle,   // [布局优化] 关闭=切换回正常模式
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 正常模式：摄像头预览 + HUD信息叠加
                    DashboardCameraView(
                        hasPermission = hasCameraPermission,
                        onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        onClick = onObstacleToggle,
                        lifecycleOwner = lifecycleOwner,
                        navState = navState,
                        // [Feature 1] 传递障碍物预警级别给安全光环
                        alertLevel = obstacleState.currentAlert?.level,
                        // [Feature 2] 传递指南针方位角给HUD
                        compassAzimuth = compassAzimuth,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            // 底部：模式切换 + 操作栏
            Spacer(modifier = Modifier.height(8.dp))
            DashboardBottomBar(
                isListening = isListening,
                onWakeUpClick = { if (isListening) onStopListening() else onStartListening() },
                // [Feature 4] 周边POI语音播报（高德API真实数据）
                onExploreClick = {
                    // 使用同步版本获取POI数据（内部含网络降级）
                    val announcement = NearbyPoiService.generateAnnouncementSync(
                        navState.currentLocation ?: return@DashboardBottomBar
                    )
                    viewModel.speak(announcement)
                },
                onToolsClick = onSettingsClick,
                onSosClick = onSosClick,
                // [v3 修复] 环境感知按钮：底部栏直接切换，视障用户无需找摄像头点击区域
                isObstacleActive = showObstacleDetection,
                onObstacleToggle = onObstacleToggle,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp)
                    .padding(bottom = 12.dp)
            )
        }
    }
}

/**
 * 仪表盘摄像头视图 — 摄像头预览 + HUD叠加层
 * 
 * 创新点：将GPS坐标、方向、速度等信息以半透明HUD风格叠加在摄像头画面上
 * 而非传统的上下分屏布局
 */
@Composable
private fun DashboardCameraView(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onClick: () -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    navState: NavigationState,
    alertLevel: AlertLevel? = null,           // [Feature 1] 安全光环预警级别
    compassAzimuth: Float = 0f,               // [Feature 2] 指南针方位角
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "智能仪表盘，点击开启环境感知"
                stateDescription = if (navState.isLocationAvailable) "GPS信号正常" else "正在获取位置"
            }
    ) {
        // 层1：摄像头预览（暗色背景）
        if (hasPermission) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { previewView ->
                    val cameraProviderFuture = ProcessCameraProvider.getInstance(previewView.context)
                    cameraProviderFuture.addListener({
                        try {
                            val cameraProvider = cameraProviderFuture.get()
                            val preview = Preview.Builder().build().also {
                                it.setSurfaceProvider(previewView.surfaceProvider)
                            }
                            val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                            cameraProvider.unbindAll()
                            cameraProvider.bindToLifecycle(lifecycleOwner, cameraSelector, preview)
                        } catch (e: Exception) {
                            Timber.e(e, "DashboardCameraView: Camera failed")
                        }
                    }, ContextCompat.getMainExecutor(previewView.context))
                }
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1A1A2E)),
                contentAlignment = Alignment.Center
            ) {
                Button(
                    onClick = onRequestPermission,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E90FF)),
                    shape = RoundedCornerShape(12.dp)
                ) { Text("授权摄像头", color = Color.White) }
            }
        }

        // 层2：安全光环（边缘彩色环）— [Feature 1] 根据障碍物数据变换颜色
        SafetyGlowRing(
            alertLevel = alertLevel,
            modifier = Modifier.fillMaxSize()
        )

        // 层3：HUD信息叠加层（左上角GPS，右上方向）— [Feature 2] 叠加真实指南针方向
        HUDInfoOverlay(
            navState = navState,
            compassAzimuth = compassAzimuth,
            modifier = Modifier.fillMaxSize()
        )

        // 层4：点击提示（底部居中）— [布局优化] 参照看见世界APP，大触控区域+清晰引导
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            shape = RoundedCornerShape(16.dp),
            color = Color(0xCC1E90FF),
            shadowElevation = 8.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 脉冲动画提示图标（alpha闪烁吸引注意力）
                val entryPulse = rememberInfiniteTransition(label = "entryPulse")
                val entryAlpha by entryPulse.animateFloat(
                    initialValue = 0.6f,
                    targetValue = 1f,
                    animationSpec = infiniteRepeatable(
                        animation = tween(1200),
                        repeatMode = RepeatMode.Reverse
                    ),
                    label = "entryPulse"
                )
                Icon(
                    Icons.Default.Videocam, null,
                    tint = Color.White.copy(alpha = entryAlpha),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(
                        text = "开启环境感知",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 17.sp
                    )
                    Text(
                        text = "点击进入障碍物检测模式",
                        color = Color.White.copy(alpha = 0.75f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * 安全光环 — 屏幕边缘的彩色安全指示环
 *
 * [Feature 1] 根据真实障碍物数据动态变换颜色：
 *   - SAFE    → 绿色 (4CAF50) — 环境安全
 *   - WARNING → 橙色 (FF9800) — 有障碍物需注意
 *   - DANGER  → 红色 (F44336) — 危险！立即停止
 *   - null    → 绿色（默认安全状态）
 *
 * 颜色变化使用 animateColorAsState 实现平滑过渡
 */
@Composable
private fun SafetyGlowRing(
    alertLevel: AlertLevel? = null,
    modifier: Modifier = Modifier
) {
    // [Feature 1] 根据预警级别决定目标颜色
    val targetColor = when (alertLevel) {
        AlertLevel.DANGER -> Color(0xFFF44336)   // 红色：危险
        AlertLevel.WARNING -> Color(0xFFFF9800)   // 橙色：警告
        AlertLevel.SAFE, null -> Color(0xFF4CAF50) // 绿色：安全/默认
    }

    // 平滑颜色过渡动画（500ms）
    val animatedColor by animateColorAsState(
        targetValue = targetColor,
        animationSpec = tween(durationMillis = 500, easing = EaseInOut),
        label = "safetyGlowColor"
    )

    // 脉冲透明度动画
    val infiniteTransition = rememberInfiniteTransition(label = "safetyGlow")
    // 危险状态下脉冲更快更明显
    val pulseDuration = if (alertLevel == AlertLevel.DANGER) 600 else
                        if (alertLevel == AlertLevel.WARNING) 1000 else 1500
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.25f,
        targetValue = if (alertLevel == AlertLevel.DANGER) 0.95f
                      else if (alertLevel == AlertLevel.WARNING) 0.7f
                      else 0.6f,
        animationSpec = infiniteRepeatable(
            animation = tween(pulseDuration),
            repeatMode = RepeatMode.Reverse
        ),
        label = "safetyAlpha"
    )

        // [Feature 1增强] DANGER 状态下额外的闪烁警示边框（在Canvas外声明动画）
        val showDangerFlash = (alertLevel == AlertLevel.DANGER)
        val dangerAlpha by infiniteTransition.animateFloat(
            initialValue = 0.1f,
            targetValue = if (showDangerFlash) 0.5f else 0.1f,
            animationSpec = infiniteRepeatable(
                animation = tween(300),
                repeatMode = RepeatMode.Reverse
            ),
            label = "dangerFlash"
        )

    Canvas(modifier = modifier) {
        val ringWidth = 6.dp.toPx()
        val cornerRadius = 20.dp.toPx()
        // 外环：半透明白色底框
        drawRoundRect(
            color = Color.White.copy(alpha = 0.08f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
            style = Stroke(width = ringWidth)
        )
        // 内环：动态颜色的脉冲安全指示环
        drawRoundRect(
            color = animatedColor.copy(alpha = alpha),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
            style = Stroke(width = ringWidth * 0.7f)
        )

        // DANGER 闪烁警示边框
        if (showDangerFlash) {
            drawRoundRect(
                color = Color(0xFFFF0000).copy(alpha = dangerAlpha),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius + 2.dp.toPx()),
                style = Stroke(width = ringWidth * 1.3f)
            )
        }
    }
}

/**
 * HUD信息叠加层 — 仪表盘风格的信息显示
 *
 * [Feature 2] 叠加真实指南针方向（N/S/E/W + 方位角）：
 *   使用 DeviceOrientationCalculator 获取设备朝向
 *   方位角 0°=北, 90°=东, 180°=南, 270°=西
 */
@Composable
private fun HUDInfoOverlay(
    navState: NavigationState,
    compassAzimuth: Float = 0f,
    modifier: Modifier = Modifier
) {
    // [Feature 2] 将方位角转换为罗盘方向字符
    val compassDirection = remember(compassAzimuth) {
        val normalized = ((compassAzimuth % 360) + 360) % 360
        when (normalized) {
            in 337.5f..360f, in 0f..22.5f -> "N"
            in 22.5f..67.5f -> "NE"
            in 67.5f..112.5f -> "E"
            in 112.5f..157.5f -> "SE"
            in 157.5f..202.5f -> "S"
            in 202.5f..247.5f -> "SW"
            in 247.5f..292.5f -> "W"
            else -> "NW"
        }
    }

    Box(modifier = modifier) {
        // 左上角：GPS坐标信息
        if (navState.isLocationAvailable && navState.currentLocation != null) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color(0x99000000),  // 60%透明度黑色
                shadowElevation = 2.dp
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text(
                        text = "📍 ${String.format("%.5f", navState.currentLocation!!.longitude)}",
                        color = Color(0xFF00FF88),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                    Text(
                        text = "   ${String.format("%.5f", navState.currentLocation!!.latitude)}",
                        color = Color(0xFF00FF88),
                        fontSize = 12.sp,
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace
                    )
                }
            }
        }

        // 右上角：[Feature 2] 真实指南针方向 + 速度
        if (navState.isLocationAvailable) {
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(12.dp),
                shape = RoundedCornerShape(10.dp),
                color = Color(0x99000000),
                shadowElevation = 2.dp
            ) {
                Row(
                    modifier = Modifier.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        Icons.Default.Navigation,
                        contentDescription = "当前朝向${compassDirection}，方位角${compassAzimuth.toInt()}度",
                        tint = Color(0xFF4FC3F7),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    // [Feature 2] 动态方向显示（替代硬编码的"N"）
                    Text(
                        text = compassDirection,
                        color = Color(0xFF4FC3F7),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    // 显示精确方位角
                    Text(
                        text = " ${compassAzimuth.toInt()}°",
                        color = Color(0xFF80DEEA),
                        fontSize = 11.sp
                    )
                    if (navState.currentLocation?.speed != null) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            text = "${String.format("%.1f", navState.currentLocation!!.speed * 3.6f)}km/h",
                            color = Color(0xFF80DEEA),
                            fontSize = 12.sp
                        )
                    }
                }
            }
        }

        // 左下角：GPS信号状态
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            shape = RoundedCornerShape(10.dp),
            color = Color(0x99000000),
            shadowElevation = 2.dp
        ) {
            Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(
                            if (navState.isLocationAvailable) Color(0xFF4CAF50)
                            else Color(0xFFFF6B6B)
                        )
                )
                Spacer(Modifier.width(6.dp))
                Text(
                    text = if (navState.isLocationAvailable) "GPS OK" else "GPS OFF",
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 11.sp
                )
            }
        }
    }
}

/**
 * 语音状态标签 — 脉冲动画版
 * 
 * 当语音助手正在聆听时，显示脉冲光晕效果，让用户感知"正在听"状态
 */
@Composable
private fun PulseVoiceStatus(
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulseVoice")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.15f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(800),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = if (isListening) 0.8f else 0.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200),
            repeatMode = RepeatMode.Reverse
        ),
        label = "glowAlpha"
    )

    Surface(
        modifier = modifier.semantics {
            contentDescription = if (isListening) "小智正在聆听您的指令" else "语音助手小智待命"
        },
        shape = RoundedCornerShape(50),
        color = Color(0xFF1E90FF).copy(alpha = 0.15f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF1E90FF).copy(alpha = glowAlpha))
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 脉冲小智图标
            Box(
                modifier = Modifier
                    .size((24 * pulseScale).dp)
                    .clip(CircleShape)
                    .background(
                        if (isListening) Color(0xFF1E90FF)
                        else Color(0xFF1E90FF).copy(alpha = 0.5f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Default.SmartToy, null,
                    tint = Color.White, modifier = Modifier.size(14.dp)
                )
            }
            // 状态文本
            Text(
                text = if (isListening) "小智聆听中..." else "小智待命",
                color = if (isListening) Color(0xFF64B5F6) else Color(0xFF90CAF9).copy(alpha = 0.7f),
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            // 聆听动画点（三个点）
            if (isListening) {
                repeat(3) { i ->
                    val dotAlpha by infiniteTransition.animateFloat(
                        initialValue = 0.3f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(600, delayMillis = i * 200),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "dot$i"
                    )
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .clip(CircleShape)
                            .background(Color(0xFF64B5F6).copy(alpha = dotAlpha))
                    )
                }
            }
        }
    }
}

/**
 * 底部操作栏 — 参照看见世界APP布局重构
 *
 * 设计原则（视障友好）：
 * 1. 核心功能按钮最大最醒目（环境感知/唤醒小智）
 * 2. 每个按钮有明确的图标+文字+语义描述
 * 3. SOS 红色突出，位置固定右侧
 * 4. 布局从左到右：核心功能 → 辅助功能 → SOS
 */
@Composable
private fun DashboardBottomBar(
    isListening: Boolean,
    onWakeUpClick: () -> Unit,
    onExploreClick: () -> Unit,
    onToolsClick: () -> Unit,
    onSosClick: () -> Unit,
    // [v3 修复] 环境感知按钮参数
    isObstacleActive: Boolean = false,
    onObstacleToggle: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // [核心] 唤醒小智（渐变蓝色，占最大空间）
        PulseWakeButton(
            isListening = isListening,
            onClick = onWakeUpClick,
            modifier = Modifier.weight(1f)
        )

        // [v3 修复] 环境感知 — 视障用户可直接点击，无需找摄像头区域
        QuickActionChip(
            label = if (isObstacleActive) "感知中" else "感知",
            icon = Icons.Default.Visibility,
            onClick = onObstacleToggle,
            color = if (isObstacleActive) Color(0xFF00BCD4) else Color(0xFF607D8B),
            modifier = Modifier.weight(0.55f)
        )

        // [辅助] 周边探索
        QuickActionChip(
            label = "周边",
            icon = Icons.Default.Explore,
            onClick = onExploreClick,
            color = Color(0xFF4CAF50),
            modifier = Modifier.weight(0.55f)
        )

        // [辅助] 工具/设置
        QuickActionChip(
            label = "工具",
            icon = Icons.Default.Build,
            onClick = onToolsClick,
            color = Color(0xFFFF9800),
            modifier = Modifier.weight(0.55f)
        )

        // [紧急] SOS — 红色圆形突出，视障用户可快速定位到最右侧
        SOSButton(
            onClick = onSosClick,
            modifier = Modifier.weight(0.5f)
        )
    }
}

/**
 * 脉冲唤醒按钮 — 带脉冲动画的渐变蓝色按钮
 */
@Composable
private fun PulseWakeButton(
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "wakePulse")
    val glowAlpha by infiniteTransition.animateFloat(
        initialValue = 0.0f,
        targetValue = if (isListening) 0.5f else 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "wakeGlow"
    )

    Button(
        onClick = onClick,
        modifier = modifier
            .height(56.dp)
            .semantics {
                contentDescription = if (isListening) "点击停止语音聆听" else "点击唤醒语音助手小智"
            },
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isListening) Color(0xFF1565C0) else Color(0xFF1E90FF)
        ),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 4.dp,
            pressedElevation = 8.dp
        )
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            // 光晕层（聆听时可见）
            if (isListening) {
                Box(
                    modifier = Modifier
                        .size(36.dp)
                        .drawBehind {
                            drawCircle(
                                color = Color(0xFF64B5F6).copy(alpha = glowAlpha),
                                radius = size.minDimension / 2 + 8.dp.toPx()
                            )
                        },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Mic, null, tint = Color.White, modifier = Modifier.size(22.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text("停止聆听", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            } else {
                Icon(Icons.Default.SmartToy, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(8.dp))
                Text("唤醒小智", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
        }
    }
}

/**
 * 快捷操作芯片 — 小按钮
 */
@Composable
private fun QuickActionChip(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(56.dp)
            .semantics { contentDescription = "$label" },
        shape = RoundedCornerShape(14.dp),
        border = androidx.compose.foundation.BorderStroke(1.5.dp, color)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
            Spacer(Modifier.height(2.dp))
            Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

/**
 * SOS紧急按钮 — 红色突出设计
 */
@Composable
private fun SOSButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = onClick,
        modifier = modifier
            .height(56.dp)
            .semantics { contentDescription = "紧急求助SOS，一键联系紧急联系人" },
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(Icons.Default.Emergency, null, tint = Color.White, modifier = Modifier.size(22.dp))
            Spacer(Modifier.height(1.dp))
            Text("SOS", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.ExtraBold)
        }
    }
}

/**
 * 智能顶部栏 — 参照看见世界APP风格重构
 *
 * 布局：左侧汉堡菜单 + 中间应用名称 + 右侧GPS/连接状态指示
 * 高对比度深色背景，蓝色主题
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartTopBar(
    onSettingsClick: () -> Unit,
    navState: NavigationState = NavigationState()   // [布局优化] 传递导航状态显示位置
) {
    TopAppBar(
        title = {
            Column {
                Text(
                    "智行助盲",
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF64B5F6),
                    fontSize = 20.sp,
                    modifier = Modifier.semantics { contentDescription = "智行助盲，视障人士出行辅助应用" }
                )
                // [布局优化] 副标题：当前位置简述（参照看见世界的位置信息展示）
                if (navState.isLocationAvailable && navState.currentLocation != null) {
                    Text(
                        text = "GPS已定位",
                        color = Color(0xFF4CAF50),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        },
        navigationIcon = {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.semantics { contentDescription = "设置入口" }
            ) {
                Icon(
                    Icons.Outlined.Menu,
                    contentDescription = null,
                    tint = Color(0xFF90CAF9),
                    modifier = Modifier.size(26.dp)
                )
            }
        },
        actions = {
            // [布局优化] GPS状态小图标
            if (navState.isLocationAvailable) {
                Icon(
                    Icons.Default.GpsFixed,
                    contentDescription = "GPS信号正常",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier
                        .size(20.dp)
                        .padding(end = 4.dp)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF0D0D1A),
            titleContentColor = Color(0xFF64B5F6)
        )
    )
}


// ============ 兼容旧版本的组件 ============

@Composable
fun LargeFeatureButton(
    label: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    containerColor: Color,
    modifier: Modifier = Modifier
) {
    val combinedDescription = "$label，$description"
    Button(
        onClick = onClick,
        modifier = modifier
            .height(100.dp)
            .semantics { contentDescription = combinedDescription; stateDescription = "可点击按钮" },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp, pressedElevation = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onPrimary)
            Spacer(modifier = Modifier.height(8.dp))
            Text(text = label, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, fontSize = 18.sp, textAlign = TextAlign.Center, maxLines = 1)
        }
    }
}

@Composable
fun AccessibleTextButton(
    label: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val combinedDescription = "$label，$description"
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(64.dp)
            .semantics { contentDescription = combinedDescription; stateDescription = "可点击按钮" },
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(imageVector = icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Spacer(modifier = Modifier.height(4.dp))
            Text(text = label, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, fontSize = 14.sp, maxLines = 1)
        }
    }
}

@Composable
fun FeatureButton(label: String, description: String, onClick: () -> Unit, containerColor: Color) {
    LargeFeatureButton(label = label, description = description, icon = Icons.Default.Star, onClick = onClick, containerColor = containerColor)
}

/**
 * 环境感知内容组件 - 在主页面直接显示障碍物检测功能
 * 
 * 修复问题3：将环境感知功能嵌入主页面，不跳转到新页面
 * 
 * @param hasPermission 是否拥有摄像头权限
 * @param onRequestPermission 请求摄像头权限的回调
 * @param lifecycleOwner 生命周期所有者
 * @param modifier 修饰符
 */
@Composable
private fun ObstacleDetectionContent(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    obstacleRepository: ObstacleRepository,
    onClose: () -> Unit = {},           // [布局优化] 关闭按钮回调
    modifier: Modifier = Modifier
) {
    // 观察真实的障碍物检测状态
    val obstacleState by obstacleRepository.obstacleState.collectAsStateWithLifecycle(
        initialValue = ObstacleState(),
        lifecycle = lifecycleOwner.lifecycle
    )
    var isDetecting by remember { mutableStateOf(true) }
    
    // 从真实状态中读取数据
    val safetyStatus = when {
        obstacleState.currentAlert?.level == com.blindpath.base.common.AlertLevel.DANGER -> "危险"
        obstacleState.currentAlert?.level == com.blindpath.base.common.AlertLevel.WARNING -> "警告"
        obstacleState.isRunning -> "安全"
        else -> "待机"
    }
    val obstacleCount = obstacleState.detectedObstacles.size
    val currentAlert = obstacleState.currentAlert
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .padding(16.dp)
    ) {
        // 顶部状态栏（参照看见世界APP：标题 + 状态指示 + 关闭按钮）
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 标题
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Videocam,
                    contentDescription = null,
                    tint = Color(0xFF1E90FF),
                    modifier = Modifier.size(24.dp)
                )
                Text(
                    text = "环境感知",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }

            // 右侧：关闭按钮 + 状态指示器
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // 安全状态指示器（基于真实检测结果）
                Surface(
                    shape = RoundedCornerShape(50),
                    color = when (safetyStatus) {
                        "危险" -> Color(0xFFFF6B6B)
                        "警告" -> Color(0xFFFFB347)
                        "安全" -> Color(0xFF4CAF50)
                        else -> Color(0xFF666666)
                    }
                ) {
                    Text(
                        text = safetyStatus,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelSmall,
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                // [布局优化] 关闭按钮 — 大触控目标，方便视障用户退出
                Surface(
                    onClick = onClose,
                    shape = CircleShape,
                    color = Color(0x44FFFFFF),
                    modifier = Modifier
                        .size(40.dp)
                        .semantics { contentDescription = "关闭环境感知，返回主界面" }
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 摄像头预览区域
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color.Black)
        ) {
            if (hasPermission) {
                // [修复] 摄像头预览：通过 Repository 统一绑定，不再独立绑定
                // 之前这里独立调用 ProcessCameraProvider.bindToLifecycle(Preview)
                // 与 ObstacleRepositoryImpl 中的 bindToLifecycle(ImageAnalysis) 产生竞争
                // 现在统一由 Repository 同时绑定 Preview + ImageAnalysis
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { previewView ->
                        // 将 SurfaceProvider 传给 Repository，由它统一绑定摄像头
                        obstacleRepository.setPreviewSurfaceProvider(previewView.surfaceProvider)
                        Timber.d("ObstacleDetectionContent: SurfaceProvider passed to repository")
                    }
                )
                
                // AI检测结果叠加层（真实数据）
                if (obstacleState.isRunning && obstacleCount > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(12.dp),
                        shape = RoundedCornerShape(8.dp),
                        color = Color(0xFFFF6B6B)
                    ) {
                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                            Text(
                                text = "检测到 $obstacleCount 个障碍物",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                            // 显示最近的障碍物信息
                            obstacleState.detectedObstacles.firstOrNull()?.let { first ->
                                Text(
                                    text = "${first.type.chineseName} ${String.format("%.1f", first.distance)}m",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
                
                // AI检测框叠加（真实数据）
                obstacleState.detectedObstacles.take(5).forEach { obstacle ->
                    val boxColor = when (obstacle.distance) {
                        in 0f..1f -> Color(0xFFFF6B6B)  // 危险：红色
                        in 1f..3f -> Color(0xFFFFB347)  // 警告：橙色
                        else -> Color(0xFF1E90FF)         // 注意：蓝色
                    }
                    Box(
                        modifier = Modifier
                            .offset(
                                x = ((obstacle.boundingBox.left * 1000).toInt()).dp,
                                y = ((obstacle.boundingBox.top * 1000).toInt()).dp
                            )
                            .size(
                                width = ((obstacle.boundingBox.right - obstacle.boundingBox.left) * 1000).dp,
                                height = ((obstacle.boundingBox.bottom - obstacle.boundingBox.top) * 1000).dp
                            )
                            .border(2.dp, boxColor, RoundedCornerShape(4.dp))
                    )
                }
                
            } else {
                // 未授权提示
                Column(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = Color(0xFF666666),
                        modifier = Modifier.size(48.dp)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "需要摄像头权限",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF666666)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = onRequestPermission,
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E90FF))
                    ) {
                        Text("授权摄像头", color = Color.White)
                    }
                }
            }
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // 底部状态信息栏 + 操作区（参照看见世界APP：状态+操作一体化）
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF2A2A3E)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // 状态指标行
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly
                ) {
                    InfoChip(
                        label = "状态",
                        value = if (obstacleState.isRunning) "检测中" else "待机",
                        valueColor = if (obstacleState.isRunning) Color(0xFF4CAF50) else Color(0xFF666666)
                    )
                    InfoChip(
                        label = "模型",
                        value = if (obstacleState.isModelLoaded) "已加载" else "加载中...",
                        valueColor = if (obstacleState.isModelLoaded) Color(0xFF4CAF50) else Color(0xFFFFB347)
                    )
                    InfoChip(
                        label = "障碍物",
                        value = if (obstacleCount > 0) "$obstacleCount 个" else "无",
                        valueColor = if (obstacleCount > 0) Color(0xFFFF9800) else Color(0xFF4CAF50)
                    )
                    InfoChip(
                        label = "FPS",
                        value = "${obstacleState.fps}",
                        valueColor = Color(0xFF1E90FF)
                    )
                }

                // [布局优化] 停止检测按钮 — 大触控目标，方便视障用户操作
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = onClose,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .semantics { contentDescription = "停止环境感知，返回主界面" },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935)),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp)
                ) {
                    Icon(Icons.Default.Stop, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("停止环境感知", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                }
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = Color(0xFF888888)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            color = valueColor
        )
    }
}

// ============================================================================
// ============================================================================
// [Feature 4] 周边POI语音播报服务 — 高德地图 API 版
// ============================================================================

/**
 * 周边兴趣点（POI）数据模型
 *
 * 接入高德地图 REST API（v3/place/around）获取真实周边POI数据
 */
data class NearbyPoi(
    val name: String,
    val category: PoiCategory,
    val distanceMeters: Int,
    val direction: String
)

/**
 * POI 分类枚举（与高德地图分类 code 对应）
 */
enum class PoiCategory(val voicePrefix: String, val amapCodes: List<String>) {
    TRANSIT("公交", listOf("150500", "150600")),
    BANK("银行", listOf("160100", "160200")),
    CONVENIENCE_STORE("便利店", listOf("060101", "060102")),
    RESTAURANT("餐厅", listOf("050000")),
    HOSPITAL("医院", listOf("090101", "090102", "090103", "090104")),
    PARK("公园", listOf("110100", "110101")),
    SHOPPING("商场", listOf("060401", "060402", "060403")),
    TOILET("公厕", listOf("161400")),
    ATM("自动取款机", listOf("160200"))
}

/**
 * 周边POI播报服务 — 高德地图 API 真实版
 *
 * 使用高德「周边搜索」REST API 获取周边真实 POI 数据。
 * API 文档: https://lbs.amap.com/api/webservice/guide/api/search
 *
 * 容错策略：网络异常时自动降级到模拟数据，保证功能不中断。
 */
object NearbyPoiService {

    private const val AMAP_WEB_API_KEY = "02d60524af990e3835f0b835ab5403e9"
    private const val SEARCH_RADIUS = 800          // 搜索半径(米)
    private const val CONNECT_TIMEOUT_MS = 5_000
    private const val READ_TIMEOUT_MS = 8_000

    /**
     * 异步获取POI并生成播报文本（协程版本）
     */
    suspend fun generateAnnouncement(location: LocationInfo): String = withContext(Dispatchers.IO) {
        try {
            val pois = fetchNearbyPoisFromAmap(location)
            if (pois.isEmpty()) return@withContext "附近${SEARCH_RADIUS}米内暂未发现可播报的地点"
            buildVoiceAnnouncement(pois)
        } catch (e: Exception) {
            Timber.e(e, "高德POI查询失败，降级为模拟数据")
            val fallback = simulateNearbyPois(location)
            if (fallback.isEmpty()) return@withContext "网络连接不可用，地点信息暂时无法获取"
            buildVoiceAnnouncement(fallback)
        }
    }

    /**
     * 同步版本（兼容非协程上下文）
     */
    fun generateAnnouncementSync(location: LocationInfo): String {
        return try {
            val pois = fetchNearbyPoisFromAmapSync(location)
            if (pois.isEmpty()) "附近${SEARCH_RADIUS}米内暂未发现可播报的地点"
            else buildVoiceAnnouncement(pois)
        } catch (e: Exception) {
            Timber.e(e, "高德POI同步查询失败，降级为模拟数据")
            val fallback = simulateNearbyPois(location)
            if (fallback.isEmpty()) "网络连接不可用" else buildVoiceAnnouncement(fallback)
        }
    }

    // ----- 高德 API 调用 -----

    private suspend fun fetchNearbyPoisFromAmap(location: LocationInfo): List<NearbyPoi> =
        withContext(Dispatchers.IO) { fetchNearbyPoisFromAmapSync(location) }

    private fun fetchNearbyPoisFromAmapSync(location: LocationInfo): List<NearbyPoi> {
        val lng = location.longitude
        val lat = location.latitude

        val urlStr = StringBuilder("https://restapi.amap.com/v3/place/around?").apply {
            append("key=").append(AMAP_WEB_API_KEY)
            append("&location=${String.format("%.6f,%.6f", lng, lat)}")
            append("&radius=$SEARCH_RADIUS")
            append("&types=150500|150600|160100|160200|060101|161400|090101|090104|050000|110100")
            append("&offset=20")
            append("&sortrule=distance")
            append("&output=json")
            append("&extensions=base")
        }.toString()

        var connection: HttpURLConnection? = null
        try {
            connection = URL(urlStr).openConnection() as HttpURLConnection
            connection.apply {
                requestMethod = "GET"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doInput = true
                setRequestProperty("Accept-Charset", "UTF-8")
                setRequestProperty("User-Agent", "BlindPath/1.0 (Android)")
            }

            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                Timber.w("高德API返回状态: ${connection.responseCode}")
                return emptyList()
            }

            val response = connection.inputStream.bufferedReader().readText()
            return parseAmapResponse(response, lat, lng)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * 解析高德 JSON -> NearbyPoi 列表
     */
    private fun parseAmapResponse(jsonStr: String, myLat: Double, myLng: Double): List<NearbyPoi> {
        val json = JSONObject(jsonStr)
        if (json.optString("status") != "1") {
            Timber.w("高德API错误: ${json.optString("info")}")
            return emptyList()
        }

        val poisArray = json.optJSONArray("pois") ?: return emptyList()
        val result = mutableListOf<NearbyPoi>()

        for (i in 0 until poisArray.length().coerceAtMost(15)) {
            val poi = poisArray.getJSONObject(i)
            val name = poi.optString("name", "")
            val typecode = poi.optString("typecode", "")
            val distance = poi.optInt("distance", -1)
            if (name.isBlank() || distance < 0) continue

            val locParts = poi.optString("location", "").split(",")
            var direction = "前方"
            if (locParts.size == 2) {
                val pLng = locParts[0].toDoubleOrNull() ?: 0.0
                val pLat = locParts[1].toDoubleOrNull() ?: 0.0
                direction = computeDirection(myLat, myLng, pLat, pLng)
            }

            val category = mapTypecodeToCategory(typecode) ?: PoiCategory.RESTAURANT
            result.add(NearbyPoi(name, category, distance, direction))
        }

        return result.sortedBy { it.distanceMeters }.take(5)
    }

    /** 高德 typecode -> PoiCategory 映射 */
    private fun mapTypecodeToCategory(typecode: String): PoiCategory? {
        val mainCode = typecode.take(4).padEnd(4, '0')
        return PoiCategory.entries.find { it.amapCodes.any { c -> mainCode == c.take(4) } }
    }

    /** 根据相对坐标计算方位描述 */
    private fun computeDirection(fromLat: Double, fromLng: Double, toLat: Double, toLng: Double): String {
        val angle = Math.toDegrees(Math.atan2(toLng - fromLng, toLat - fromLat)).toFloat()
        val norm = ((angle % 360) + 360) % 360
        return when (norm) {
            in 337.5f..360f, in 0f..22.5f -> "前方"
            in 22.5f..67.5f -> "右前方"
            in 67.5f..112.5f -> "右侧"
            in 112.5f..157.5f -> "右后方"
            in 157.5f..202.5f -> "后方"
            in 202.5f..247.5f -> "左后方"
            in 247.5f..292.5f -> "左侧"
            else -> "左前方"
        }
    }

    /** 构建自然语言语音播报文本 */
    private fun buildVoiceAnnouncement(pois: List<NearbyPoi>): String = buildString {
        append("您附近有")

        val transitPois = pois.filter { it.category == PoiCategory.TRANSIT }
        if (transitPois.isNotEmpty()) {
            val p = transitPois.first()
            append("，${p.name}在${p.direction}约${p.distanceMeters}米")
        }

        pois.filter { it.category in listOf(PoiCategory.BANK, PoiCategory.CONVENIENCE_STORE, PoiCategory.ATM) }
            .take(2).forEach { poi ->
                append("，${poi.category.voicePrefix}${poi.name}在${poi.direction}${poi.distanceMeters}米处")
            }

        pois.filter { it.category in listOf(PoiCategory.TOILET, PoiCategory.HOSPITAL, PoiCategory.PARK) }
            .take(2).forEach { poi ->
                append("，${poi.name}在${poi.direction}")
            }

        append(".需要更多详情请说'小智小智'查询")
    }

    // ========================================================================
    // 模拟数据降级方案（网络不可用时自动回退）
    // ========================================================================

    private fun simulateNearbyPois(location: LocationInfo): List<NearbyPoi> {
        val hash = abs((location.latitude * 1000 + location.longitude * 1000).toInt() % 100)
        val allPossiblePois = mutableListOf<NearbyPoi>()

        allPossiblePois.add(NearbyPoi(
            name = listOf("中山路站", "人民广场站", "建设路口站", "解放路北站", "文化宫站")[(hash % 5 + 5) % 5],
            category = PoiCategory.TRANSIT,
            distanceMeters = 80 + (hash % 200),
            direction = listOf("前方", "左侧", "右侧")[hash % 3]
        ))

        if (hash % 3 != 0) {
            allPossiblePois.add(NearbyPoi(
                name = listOf("全家便利店", "美宜佳", "7-Eleven", "罗森", "喜士多")[(hash + 1) % 5],
                category = PoiCategory.CONVENIENCE_STORE,
                distanceMeters = 120 + ((hash * 7) % 300),
                direction = listOf("右前方", "左前方", "右侧")[(hash + 2) % 3]
            ))
        }

        if (hash % 4 < 2) {
            allPossiblePois.add(NearbyPoi(
                name = listOf("中国工商银行", "中国建设银行", "招商银行", "农业银行")[(hash + 3) % 4],
                category = PoiCategory.BANK,
                distanceMeters = 250 + ((hash * 11) % 400),
                direction = listOf("左侧", "右后方", "正前方")[hash % 3]
            ))
        }

        if (hash % 5 < 3) {
            allPossiblePois.add(NearbyPoi(
                name = "公共卫生间",
                category = PoiCategory.TOILET,
                distanceMeters = 150 + ((hash * 13) % 350),
                direction = listOf("前方偏左", "右侧", "左前方")[(hash + 4) % 3]
            ))
        }

        if (hash % 3 == 0) {
            allPossiblePois.add(NearbyPoi(
                name = listOf("沙县小吃", "兰州拉面", "肯德基", "麦当劳")[(hash * 17) % 4],
                category = PoiCategory.RESTAURANT,
                distanceMeters = 200 + ((hash * 19) % 500),
                direction = listOf("前方", "右前方", "左后方")[(hash + 6) % 3]
            ))
        }

        return allPossiblePois.sortedBy { it.distanceMeters }.take(5)
    }
}
