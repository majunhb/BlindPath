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
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
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
            MainContentV5(
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
 * 主界面内容 - v5.0 设计
 * 
 * 布局结构：
 * ┌─────────────────────────┐
 * │  [消息] 智行助盲         │ ← 顶部导航栏
 * ├─────────────────────────┤
 * │  [小智] 我在外出...      │ ← 语音状态标签
 * ├─────────────────────────┤
 * │  ┌───────────────────┐  │
 * │  │   摄像头实时预览  │  │ ← 上半部分（环境感知）
 * │  │   [安全/危险提示] │  │
 * ├─────────────────────────┤
 * │  ┌───────────────────┐  │
 * │  │   地图预览        │  │ ← 下半部分（位置感知）
 * │  │   [当前位置信息]  │  │
 * ├─────────────────────────┤
 * │ [唤醒小智] [导航] [SOS]│ ← 底部三按钮
 * └─────────────────────────┘
 */
@Composable
private fun MainContentV5(
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
    
    // 定位权限状态（新增）
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }
    
    // 定位权限请求器（新增）
    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true || 
                              permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
    }
    
    // 首次进入自动请求权限（修改：同时请求相机和定位权限）
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
    
    Scaffold(
        containerColor = Color.White,
        topBar = {
            MainTopBar(
                onSettingsClick = onSettingsClick
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 语音状态标签
            VoiceStatusTag(
                isListening = isListening,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
            )
            
            // 中间区域：上半摄像头 + 下半地图
            // 修复问题3：当 showObstacleDetection = true 时，直接在主页面显示环境感知
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                if (showObstacleDetection) {
                    // 直接在主页面显示环境感知功能（连接真实AI检测数据）
                    ObstacleDetectionContent(
                        hasPermission = hasCameraPermission,
                        onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        lifecycleOwner = lifecycleOwner,
                        obstacleRepository = obstacleRepository,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 正常显示：上半摄像头预览 + 下半地图预览
                    Column(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        // 上半：摄像头预览（点击开启环境感知）
                        CameraPreviewCard(
                            hasPermission = hasCameraPermission,
                            onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                            onClick = onObstacleToggle,  // 点击切换环境感知状态
                            lifecycleOwner = lifecycleOwner,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                        )
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // 下半：地图预览（显示实时位置）
                        MapPreviewCard(
                            onClick = onLocationClick,
                            navigationRepository = navigationRepository,
                            modifier = Modifier
                                .fillMaxWidth()
                                .weight(1f)
                                .clip(RoundedCornerShape(16.dp))
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 底部三按钮
            BottomThreeActions(
                isListening = isListening,
                onWakeUpClick = {
                    if (isListening) onStopListening() else onStartListening()
                },
                onNavigationClick = onNavigationClick,
                onSosClick = onSosClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp)
            )
        }
    }
}

/**
 * 摄像头预览卡片 - 首页上半部分
 * 点击跳转到完整的障碍物检测界面
 */
@Composable
private fun CameraPreviewCard(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onClick: () -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF1A1A2E))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "环境感知，点击开启障碍物检测"
            }
    ) {
        if (hasPermission) {
            // 显示预览画面缩略图
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
                            Timber.e(e, "MainScreen: Camera preview failed")
                        }
                    }, ContextCompat.getMainExecutor(previewView.context))
                }
            )
            
            // 点击提示叠加层
            Surface(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(12.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xCC1E90FF)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Videocam,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "点击开启环境感知",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        } else {
            // 未授权时显示授权提示
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
                    text = "点击授权摄像头",
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
}

/**
 * 地图预览卡片 - 首页下半部分
 * 点击跳转到定位界面查看完整地图
 */
@Composable
private fun MapPreviewCard(
    onClick: () -> Unit,
    navigationRepository: NavigationRepository,
    modifier: Modifier = Modifier
) {
    val navState by navigationRepository.navigationState.collectAsStateWithLifecycle(
        initialValue = NavigationState(),
        lifecycle = LocalLifecycleOwner.current.lifecycle
    )
    
    Box(
        modifier = modifier
            .background(Color(0xFFF5F5F5))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "位置感知，${if (navState.isLocationAvailable) "当前位置可用" else "正在获取位置"}"
            }
    ) {
        if (navState.isLocationAvailable && navState.currentLocation != null) {
            // 显示实时位置信息
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // GPS 信号图标
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(40.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "GPS 信号正常",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50),
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "经度: ${String.format("%.4f", navState.currentLocation!!.longitude)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF666666)
                )
                Text(
                    text = "纬度: ${String.format("%.4f", navState.currentLocation!!.latitude)}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFF666666)
                )
            }
        } else {
            // 未获取到位置时显示等待状态
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.Map,
                    contentDescription = null,
                    tint = Color(0xFF1E90FF),
                    modifier = Modifier.size(48.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "正在获取位置...",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF666666)
                )
            }
        }
        
        // 点击查看详情的提示
        Surface(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(12.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color(0xCC1E90FF)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "查看详情",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

/**
 * 顶部导航栏
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainTopBar(
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = "智行助盲",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1E90FF),
                modifier = Modifier.semantics {
                    contentDescription = "智行助盲，视障人士出行辅助应用"
                }
            )
        },
        navigationIcon = {
            IconButton(
                onClick = onSettingsClick,
                modifier = Modifier.semantics {
                    contentDescription = "消息和设置入口"
                }
            ) {
                Icon(
                    imageVector = Icons.Outlined.ChatBubbleOutline,
                    contentDescription = null,
                    tint = Color(0xFF1E90FF),
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.White,
            titleContentColor = Color(0xFF1E90FF)
        )
    )
}

/**
 * 语音状态标签 - 蓝色胶囊标签
 */
@Composable
private fun VoiceStatusTag(
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val statusText = if (isListening) "正在聆听..." else "小智"
    
    Surface(
        modifier = modifier
            .semantics {
                contentDescription = if (isListening) "语音助手正在聆听您的指令" else "语音助手小智待命"
            },
        shape = RoundedCornerShape(50),
        color = Color(0xFF1E90FF)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(18.dp)
                    .clip(RoundedCornerShape(50))
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = Color(0xFF1E90FF),
                    modifier = Modifier.size(14.dp)
                )
            }
            
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * 底部三按钮布局：唤醒小智 + 切换导航 + SOS
 */
@Composable
private fun BottomThreeActions(
    isListening: Boolean,
    onWakeUpClick: () -> Unit,
    onNavigationClick: () -> Unit,
    onSosClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 唤醒小智（渐变蓝色大按钮）
        WakeUpButton(
            isListening = isListening,
            onClick = onWakeUpClick,
            modifier = Modifier.weight(1f)
        )
        
        // 切换导航
        QuickActionButton(
            label = "切换导航",
            description = "切换导航模式，规划出行路线",
            icon = Icons.Default.Navigation,
            onClick = onNavigationClick,
            color = Color(0xFF1E90FF),
            modifier = Modifier.weight(0.7f)
        )
        
        // 紧急求助（红色）
        QuickActionButton(
            label = "SOS",
            description = "紧急求助，一键联系紧急联系人",
            icon = Icons.Default.Emergency,
            onClick = onSosClick,
            color = Color(0xFFFF6B6B),
            modifier = Modifier.weight(0.6f)
        )
    }
}

/**
 * 唤醒小智按钮 - 渐变蓝色
 */
@Composable
private fun WakeUpButton(
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientBlue = Brush.horizontalGradient(
        colors = listOf(Color(0xFF4FACFE), Color(0xFF00F2FE))
    )
    
    val buttonText = if (isListening) "停止聆听" else "唤醒小智"
    val buttonDesc = if (isListening) "点击停止语音聆听" else "点击唤醒语音助手小智"
    
    Button(
        onClick = onClick,
        modifier = modifier
            .height(56.dp)
            .semantics {
                contentDescription = buttonDesc
                stateDescription = if (isListening) "正在聆听" else "待命状态"
            },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBlue, RoundedCornerShape(16.dp)),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    color = Color.White
                )
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

/**
 * 快捷操作按钮
 */
@Composable
private fun QuickActionButton(
    label: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    color: Color,
    modifier: Modifier = Modifier
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier
            .height(56.dp)
            .semantics {
                contentDescription = "$label，$description"
            },
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, color)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(22.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = color,
                fontSize = 14.sp
            )
        }
    }
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
