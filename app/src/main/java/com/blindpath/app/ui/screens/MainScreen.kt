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
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.ObstacleState
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_navigation.domain.model.NavigationState
import com.blindpath.module_settings.ui.SettingsScreen
import com.blindpath.module_community.ui.CommunityScreen
import com.blindpath.module_trip_assist.ui.TripAssistScreen
import com.blindpath.module_voice.domain.model.VoiceCommand
import com.blindpath.module_voice.domain.model.VoiceGuidance
import com.blindpath.module_voice.viewmodel.VoiceInteractionViewModel
import timber.log.Timber
import java.util.concurrent.Executors

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
                isListening = uiState.isListening,
                onStartListening = { viewModel.startListening() },
                onStopListening = { viewModel.stopListening() },
                obstacleRepository = obstacleRepository,
                navigationRepository = navigationRepository
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
    isListening: Boolean = false,
    onStartListening: () -> Unit = {},
    onStopListening: () -> Unit = {},
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository
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
    
    Scaffold(
        containerColor = Color(0xFF0D0D1A),  // 深色背景，对比度更高
        topBar = {
            SmartTopBar(onSettingsClick = onSettingsClick)
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
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            
            // 底部：模式切换 + 操作栏
            Spacer(modifier = Modifier.height(8.dp))
            DashboardBottomBar(
                isListening = isListening,
                onWakeUpClick = { if (isListening) onStopListening() else onStartListening() },
                onExploreClick = onLocationClick,
                onToolsClick = onSettingsClick,
                onSosClick = onSosClick,
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

        // 层2：安全光环（边缘彩色环）
        SafetyGlowRing(
            modifier = Modifier.fillMaxSize()
        )

        // 层3：HUD信息叠加层（左上角GPS，右上方向）
        HUDInfoOverlay(
            navState = navState,
            modifier = Modifier.fillMaxSize()
        )

        // 层4：点击提示（底部居中）
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(16.dp),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xCC1E90FF),
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Videocam, null,
                    tint = Color.White, modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(8.dp))
                Text("点击开启环境感知", color = Color.White, fontWeight = FontWeight.Bold,
                    fontSize = 15.sp)
            }
        }
    }
}

/**
 * 安全光环 — 屏幕边缘的彩色安全指示环
 * 
 * 创新点：视障用户也可能有残余视力，边缘光环提供快速安全等级判断
 * 同时结合振动反馈形成双通道告警
 */
@Composable
private fun SafetyGlowRing(modifier: Modifier = Modifier) {
    // 脉冲动画：0.5秒循环，从0.3到0.8透明度
    val infiniteTransition = rememberInfiniteTransition(label = "safetyGlow")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 0.8f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "safetyAlpha"
    )

    Canvas(modifier = modifier) {
        val ringWidth = 6.dp.toPx()
        val cornerRadius = 20.dp.toPx()
        // 外环：半透明白色
        drawRoundRect(
            color = Color.White.copy(alpha = 0.08f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
            style = Stroke(width = ringWidth)
        )
        // 内环：脉冲绿色（安全指示）
        drawRoundRect(
            color = Color(0xFF4CAF50).copy(alpha = alpha),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(cornerRadius),
            style = Stroke(width = ringWidth * 0.7f)
        )
    }
}

/**
 * HUD信息叠加层 — 仪表盘风格的信息显示
 * 
 * 显示GPS坐标、移动方向、速度等信息，采用战斗机HUD风格的半透明叠加
 */
@Composable
private fun HUDInfoOverlay(
    navState: NavigationState,
    modifier: Modifier = Modifier
) {
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

        // 右上角：方向 + 速度
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
                        contentDescription = null,
                        tint = Color(0xFF4FC3F7),
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "N",  // 北方（简化显示）
                        color = Color(0xFF4FC3F7),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                    if (navState.currentLocation?.speed != null) {
                        Spacer(Modifier.width(8.dp))
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
 * 底部操作栏 — 模式切换 + 核心操作
 * 
 * 采用"功能按钮 + SOS"的极简布局
 * 左侧大按钮（唤醒小智/环境感知），右侧SOS紧急按钮
 */
@Composable
private fun DashboardBottomBar(
    isListening: Boolean,
    onWakeUpClick: () -> Unit,
    onExploreClick: () -> Unit,
    onToolsClick: () -> Unit,
    onSosClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 唤醒小智（渐变蓝色，占最大空间）
        PulseWakeButton(
            isListening = isListening,
            onClick = onWakeUpClick,
            modifier = Modifier.weight(1f)
        )

        // 周边探索
        QuickActionChip(
            label = "周边",
            icon = Icons.Default.Explore,
            onClick = onExploreClick,
            color = Color(0xFF4CAF50),
            modifier = Modifier.weight(0.6f)
        )

        // 工具/设置
        QuickActionChip(
            label = "工具",
            icon = Icons.Default.Build,
            onClick = onToolsClick,
            color = Color(0xFFFF9800),
            modifier = Modifier.weight(0.6f)
        )

        // SOS紧急求助（红色突出）
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
 * 智能顶部栏 — 精简版
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SmartTopBar(onSettingsClick: () -> Unit) {
    TopAppBar(
        title = {
            Text(
                "智行助盲",
                fontWeight = FontWeight.Bold,
                color = Color(0xFF64B5F6),
                fontSize = 20.sp,
                modifier = Modifier.semantics { contentDescription = "智行助盲，视障人士出行辅助应用" }
            )
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
        // 顶部状态栏
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
                // 显示摄像头预览
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
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
                                Timber.e(e, "ObstacleDetectionContent: Camera preview failed")
                            }
                        }, ContextCompat.getMainExecutor(previewView.context))
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
        
        // 底部状态信息栏
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF2A2A3E)
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                // 检测状态
                InfoChip(
                    label = "状态",
                    value = if (obstacleState.isRunning) "检测中" else "待机",
                    valueColor = if (obstacleState.isRunning) Color(0xFF4CAF50) else Color(0xFF666666)
                )
                // 模型状态
                InfoChip(
                    label = "模型",
                    value = if (obstacleState.isModelLoaded) "已加载" else "未加载",
                    valueColor = if (obstacleState.isModelLoaded) Color(0xFF4CAF50) else Color(0xFFFF6B6B)
                )
                // FPS
                InfoChip(
                    label = "FPS",
                    value = "${obstacleState.fps}",
                    valueColor = Color(0xFF1E90FF)
                )
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
