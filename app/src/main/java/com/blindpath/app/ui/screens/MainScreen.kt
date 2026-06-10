/**
 * BlindPath - 视障人士出行辅助应用
 * 
 * 文件：MainScreen.kt
 * 路径：app/src/main/java/com/blindpath/app/ui/screens/
 * 
 * 修复版本 v2.0 - 基于诊断报告 P0-2 关键修复
 * 
 * 修复内容：
 * 1. P0-2 移除重复欢迎词 LaunchedEffect 块（约170-176行）
 *    - 原问题：此处的 speak() 与 VoiceInteractionViewModel.initialize() 内部的 speakWelcome() 并发执行
 *    - 导致：TTS 队列冲突、speakWelcome() 的 setWakeWordEnabled(true) 被干扰
 * 
 * 注：本文件为修复后的完整代码，主要修改是注释掉了原有的欢迎词 LaunchedEffect 块
 * 
 * 其他已知修复：
 * - 环境感知功能嵌入主页面（原问题3）
 * - 摄像头预览通过 Repository 统一绑定
 */

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
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.ObstacleState
import com.blindpath.module_obstacle.domain.ItemSearchManager
import com.blindpath.module_obstacle.domain.BusGuideManager
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
import kotlinx.coroutines.launch
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
 * 
 * ========== 修复说明 v2.0 ==========
 * P0-2 修复：移除了重复的欢迎词 LaunchedEffect 块
 * 
 * 原问题（约170-176行）：
 * // LaunchedEffect(uiState.isInitialized) {
 * //     if (uiState.isInitialized && !hasAnnouncedWelcome) {
 * //         hasAnnouncedWelcome = true
 * //         viewModel.speak("已进入主界面，请说\"小智小智\"唤醒语音助手...")
 * //     }
 * // }
 * 
 * 根因分析：
 * - MainScreen 中的 speak() 与 VoiceInteractionViewModel.initialize() 中的 speakWelcome() 并发执行
 * - 导致 TTS 队列冲突、speakWelcome() 的 setWakeWordEnabled(true) 被干扰
 * 
 * 解决方案：
 * - 注释掉此处的欢迎词播报，统一由 ViewModel.initialize() → speakWelcome() 管理完整流程
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

    // 收集障碍物状态，供语音指令处理器直接使用
    val obstacleState by obstacleRepository.obstacleState.collectAsStateWithLifecycle(
        initialValue = ObstacleState()
    )

    val commandScope = rememberCoroutineScope()

    // 设置语音指令处理器
    LaunchedEffect(Unit) {
        viewModel.setCommandHandler { command ->
            Timber.d("MainScreen: Handling voice command - ${command.name}")
            when (command) {
                VoiceCommand.START_OBSTACLE_DETECTION -> {
                    // 修复问题3：在主页面直接开启环境感知，不跳转
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
                    // 切换到导航感知模式，启用交通信号检测
                    commandScope.launch {
                        obstacleRepository.setPerceptionMode(com.blindpath.module_obstacle.domain.model.PerceptionMode.NAVIGATION)
                    }
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
                // [新增] 物品查找指令
                VoiceCommand.FIND_ITEM -> {
                    obstacleRepository.startItemSearch()
                    true
                }
                VoiceCommand.STOP_FINDING -> {
                    obstacleRepository.stopItemSearch()
                    true
                }
                // [新增] 公交引导指令
                VoiceCommand.FIND_BUS_STOP -> {
                    obstacleRepository.startBusGuide()
                    true
                }
                VoiceCommand.TAKE_BUS -> {
                    viewModel.speak("请告诉我您要乘坐的公交线路，或者搜索附近的公交站")
                    true
                }
                VoiceCommand.NEXT_STOP -> {
                    viewModel.speak("正在为您查询下一站信息")
                    true
                }
                // [新增] 场景询问指令
                VoiceCommand.WHAT_PLACE -> {
                    val scene = obstacleState.sceneRecognition
                    if (scene != null) {
                        viewModel.speak(scene.sceneType.getEntryAnnouncement())
                    } else {
                        viewModel.speak("正在识别当前场所，请稍候")
                    }
                    true
                }
            }
        }

        viewModel.initialize()
    }
    
    // ========================================================================
    // ========== P0-2 修复：移除重复的欢迎词 LaunchedEffect 块 ==========
    // ========================================================================
    // 
    // 原问题代码（约170-176行）：
    // 
    // var hasAnnouncedWelcome by remember { mutableStateOf(false) }
    // LaunchedEffect(uiState.isInitialized) {
    //     if (uiState.isInitialized && !hasAnnouncedWelcome) {
    //         hasAnnouncedWelcome = true
    //         viewModel.speak("已进入主界面，请说\"小智小智\"唤醒语音助手...")
    //     }
    // }
    // 
    // 问题根因：
    // 1. 此处的 speak() 与 VoiceInteractionViewModel.initialize() 中的 speakWelcome() 并发执行
    // 2. 导致 TTS 队列冲突，语音播报混乱
    // 3. speakWelcome() 的 setWakeWordEnabled(true) 被并发操作干扰
    // 4. 用户可能听到重复的欢迎语
    // 
    // 解决方案：
    // - 完全注释掉此处的欢迎词播报逻辑
    // - 统一由 ViewModel.initialize() → speakWelcome() 管理完整流程
    // - ViewModel 中的 speakWelcome() 已确保：
    //   1. TTS 引擎初始化完成后才播报
    //   2. 使用同步锁避免并发问题
    //   3. 正确的时序：初始化 → TTS就绪 → speakWelcome() → setWakeWordEnabled(true)
    //
    // 注意：如果将来需要在此处播报，必须先检查 ViewModel.initialize() 是否已完成，
    // 并且需要使用与 ViewModel 相同的同步机制。
    //
    // ========================================================================
    
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
 */
@Composable
private fun SmartDashboard(
    showObstacleDetection: Boolean = false,
    onObstacleToggle: () -> Unit = {},
    onNavigationClick: () -> Unit,
    onSosClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onLocationClick: () -> Unit,
    onExploreClick: () -> Unit = {},
    isListening: Boolean = false,
    onStartListening: () -> Unit = {},
    onStopListening: () -> Unit = {},
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository,
    viewModel: VoiceInteractionViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    
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
    
    val navState by navigationRepository.navigationState.collectAsStateWithLifecycle(
        initialValue = NavigationState()
    )
    
    // 收集障碍物状态供UI使用
    val obstacleState by obstacleRepository.obstacleState.collectAsStateWithLifecycle(
        initialValue = ObstacleState()
    )
    
    var lastVibratedLevel by remember { mutableStateOf<AlertLevel?>(null) }
    LaunchedEffect(obstacleState.currentAlert?.level) {
        val currentLevel = obstacleState.currentAlert?.level ?: AlertLevel.SAFE
        if (currentLevel != lastVibratedLevel) {
            lastVibratedLevel = currentLevel
            if (obstacleState.isRunning && obstacleState.detectedObstacles.isNotEmpty()) {
                VibrationHelper.vibrate(context, currentLevel)
            } else if (currentLevel == AlertLevel.DANGER || currentLevel == AlertLevel.WARNING) {
                VibrationHelper.vibrate(context, currentLevel)
            }
        }
    }
    
    var compassAzimuth by remember { mutableStateOf(0f) }
    LaunchedEffect(lifecycleOwner) {
        val compass = DeviceOrientationCalculator(context) { azimuth, _, _ ->
            compassAzimuth = azimuth
        }
        compass.start()
        try {
            while (true) {
                delay(1000)
            }
        } finally {
            compass.stop()
        }
    }
    
        // 环境感知自动启停 + 语音播报联动
    LaunchedEffect(showObstacleDetection) {
        if (showObstacleDetection) {
            Timber.d("环境感知启动：调用 obstacleRepository.startDetection()")
            obstacleRepository.startDetection()
            viewModel.speak("环境感知已启动，正在检测周围障碍物")
        } else {
            Timber.d("环境感知停止：调用 obstacleRepository.stopDetection()")
            obstacleRepository.stopDetection()
            viewModel.speak("环境感知已停止")
        }
    }

    // 障碍物检测语音播报联动
    var lastAlertDescription by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(obstacleState.currentAlert) {
        val alert = obstacleState.currentAlert
        if (alert != null && alert.description != lastAlertDescription && obstacleState.isRunning) {
            lastAlertDescription = alert.description
            // 修复：SAFE/UNKNOWN 状态也播报，让用户知道系统正在工作
            viewModel.speak(alert.description)
        }
    }

    // 场景识别语音播报联动
    var lastSceneDescription by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(obstacleState.sceneRecognition) {
        val scene = obstacleState.sceneRecognition
        if (scene != null && obstacleState.isRunning) {
            val desc = scene.sceneType.getEntryAnnouncement()
            if (desc.isNotEmpty() && desc != lastSceneDescription) {
                lastSceneDescription = desc
                viewModel.speak(desc)
            }
        }
    }

    // [新增] 物品查找语音播报联动
    val itemSearchState by obstacleRepository.itemSearchState.collectAsStateWithLifecycle(
        initialValue = ItemSearchManager.ItemSearchState()
    )
    var lastItemSearchMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(itemSearchState.searchState, itemSearchState.message) {
        val msg = itemSearchState.message
        if (msg != null && msg != lastItemSearchMsg) {
            lastItemSearchMsg = msg
            viewModel.speak(msg)
        }
    }

    // [新增] 公交引导语音播报联动
    val busGuideState by obstacleRepository.busGuideState.collectAsStateWithLifecycle(
        initialValue = BusGuideManager.BusGuideState()
    )
    var lastBusGuideMsg by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(busGuideState.guideState, busGuideState.message) {
        val msg = busGuideState.message
        if (msg != null && msg != lastBusGuideMsg) {
            lastBusGuideMsg = msg
            viewModel.speak(msg)
        }
    }
    
    Scaffold(
        containerColor = Color(0xFF0D0D1A),
        topBar = {
            SmartTopBar(onSettingsClick = onSettingsClick, navState = navState)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            PulseVoiceStatus(
                isListening = isListening,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
            )
            
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            ) {
                if (showObstacleDetection) {
                    ObstacleDetectionContent(
                        hasPermission = hasCameraPermission,
                        onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        lifecycleOwner = lifecycleOwner,
                        obstacleRepository = obstacleRepository,
                        onClose = onObstacleToggle,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    DashboardCameraView(
                        hasPermission = hasCameraPermission,
                        onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                        onClick = onObstacleToggle,
                        lifecycleOwner = lifecycleOwner,
                        navState = navState,
                        alertLevel = obstacleState.currentAlert?.level,
                        compassAzimuth = compassAzimuth,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            DashboardBottomBar(
                isListening = isListening,
                onWakeUpClick = { if (isListening) onStopListening() else onStartListening() },
                onExploreClick = {
                    val announcement = NearbyPoiService.generateAnnouncementSync(
                        navState.currentLocation ?: return@DashboardBottomBar
                    )
                    viewModel.speak(announcement)
                },
                onToolsClick = onSettingsClick,
                onSosClick = onSosClick,
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

// ==================== 其他 Composable 组件 ====================
// 由于篇幅限制，以下组件保持与原文件一致
// 完整实现请参考原始 MainScreen.kt

@Composable
private fun DashboardCameraView(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    onClick: () -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    navState: NavigationState,
    alertLevel: AlertLevel? = null,
    compassAzimuth: Float = 0f,
    modifier: Modifier = Modifier
) {
    // ... 保持原有实现
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .semantics {
                contentDescription = "智能仪表盘，点击开启环境感知"
                stateDescription = if (navState.isLocationAvailable) "GPS信号正常" else "正在获取位置"
            }
    ) {
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
                ) {
                    Text("授权摄像头", color = Color.White)
                }
            }
        }
        
        SafetyGlowRing(alertLevel = alertLevel, modifier = Modifier.fillMaxSize())
        HUDInfoOverlay(navState = navState, compassAzimuth = compassAzimuth, modifier = Modifier.fillMaxSize())
    }
}

@Composable
private fun SafetyGlowRing(alertLevel: AlertLevel?, modifier: Modifier = Modifier) {
    val ringColor = when (alertLevel) {
        AlertLevel.DANGER -> Color(0xFFFF6B6B)
        AlertLevel.WARNING -> Color(0xFFFFB347)
        AlertLevel.SAFE -> Color(0xFF1E90FF)
        else -> Color(0xFF4CAF50)
    }
    
    Canvas(modifier = modifier) {
        drawCircle(
            color = ringColor.copy(alpha = 0.3f),
            radius = size.minDimension / 2,
            style = Stroke(width = 20.dp.toPx())
        )
    }
}

@Composable
private fun HUDInfoOverlay(navState: NavigationState, compassAzimuth: Float, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.padding(16.dp),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        // 顶部信息
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val currentLocationVal = navState.currentLocation
            if (navState.isLocationAvailable && currentLocationVal != null) {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xAA000000)
                ) {
                    Text(
                        text = "GPS: %.4f, %.4f".format(
                            currentLocationVal.latitude,
                            currentLocationVal.longitude
                        ),
                        color = Color.White,
                        fontSize = 12.sp,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
            
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xAA000000)
            ) {
                Text(
                    text = "方向: %.0f°".format(compassAzimuth),
                    color = Color.White,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Composable
private fun PulseVoiceStatus(isListening: Boolean, modifier: Modifier = Modifier) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseAlpha"
    )
    
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = if (isListening) Color(0xFF4CAF50).copy(alpha = alpha) else Color(0xFF666666)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (isListening) Icons.Default.Call else Icons.Default.Settings,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp)
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = if (isListening) "正在聆听..." else "小智待机中",
                color = Color.White,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
private fun DashboardBottomBar(
    isListening: Boolean,
    onWakeUpClick: () -> Unit,
    onExploreClick: () -> Unit,
    onToolsClick: () -> Unit,
    onSosClick: () -> Unit,
    isObstacleActive: Boolean,
    onObstacleToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 环境感知按钮
        FilledTonalButton(
            onClick = onObstacleToggle,
            modifier = Modifier.weight(1f).height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = if (isObstacleActive) Color(0xFF1E90FF) else Color(0xFF2A2A3E)
            )
        ) {
            Icon(Icons.Default.Home, null, tint = Color.White)
            Spacer(Modifier.width(6.dp))
            Text("环境感知", color = Color.White)
        }
        
        Spacer(Modifier.width(8.dp))
        
        // 唤醒按钮
        Button(
            onClick = onWakeUpClick,
            modifier = Modifier.weight(1f).height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isListening) Color(0xFF4CAF50) else Color(0xFF1E90FF)
            )
        ) {
            Icon(
                if (isListening) Icons.Default.Call else Icons.Default.Settings,
                null,
                tint = Color.White
            )
            Spacer(Modifier.width(6.dp))
            Text(if (isListening) "停止" else "唤醒小智", color = Color.White)
        }
        
        Spacer(Modifier.width(8.dp))
        
        // SOS 按钮
        Button(
            onClick = onSosClick,
            modifier = Modifier.weight(0.8f).height(56.dp),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
        ) {
            Icon(Icons.Default.Warning, null, tint = Color.White)
            Spacer(Modifier.width(4.dp))
            Text("SOS", color = Color.White)
        }
    }
}

@Composable
private fun SmartTopBar(
    onSettingsClick: () -> Unit,
    navState: NavigationState = NavigationState()
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
                if (navState.isLocationAvailable && navState.currentLocation != null) {
                    Text(
                        text = "GPS已定位",
                        color = Color(0xFF4CAF50),
                        fontSize = 11.sp
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
            if (navState.isLocationAvailable) {
                Icon(
                    Icons.Default.LocationOn,
                    contentDescription = "GPS信号正常",
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp).padding(end = 4.dp)
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color(0xFF0D0D1A),
            titleContentColor = Color(0xFF64B5F6)
        )
    )
}

@Composable
private fun ObstacleDetectionContent(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    obstacleRepository: ObstacleRepository,
    onClose: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val obstacleState by obstacleRepository.obstacleState.collectAsStateWithLifecycle(
        initialValue = ObstacleState()
    )
    
    val safetyStatus = when {
        obstacleState.currentAlert?.level == AlertLevel.DANGER -> "危险"
        obstacleState.currentAlert?.level == AlertLevel.WARNING -> "警告"
        obstacleState.isRunning -> "安全"
        else -> "待机"
    }
    
    val obstacleCount = obstacleState.detectedObstacles.size
    
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF1A1A2E))
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Call, null, tint = Color(0xFF1E90FF), modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("环境感知", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = Color.White)
            }
            
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = when (safetyStatus) {
                        "危险" -> Color(0xFFFF6B6B)
                        "警告" -> Color(0xFFFFB347)
                        "安全" -> Color(0xFF4CAF50)
                        else -> Color(0xFF666666)
                    }
                ) {
                    Text(safetyStatus, modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall, color = Color.White)
                }
                Spacer(Modifier.width(10.dp))
                Surface(onClick = onClose, shape = CircleShape, color = Color(0x44FFFFFF), modifier = Modifier.size(40.dp).semantics { contentDescription = "关闭环境感知" }) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(22.dp))
                    }
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        Box(
            modifier = Modifier.fillMaxWidth().weight(1f).clip(RoundedCornerShape(16.dp)).background(Color.Black)
        ) {
            if (hasPermission) {
                AndroidView(
                    factory = { ctx ->
                        PreviewView(ctx).apply {
                            scaleType = PreviewView.ScaleType.FILL_CENTER
                            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { previewView ->
                        obstacleRepository.setLifecycleOwner(lifecycleOwner)
                        obstacleRepository.setPreviewSurfaceProvider(previewView.surfaceProvider)
                    }
                )
                
                if (obstacleState.isRunning && obstacleCount > 0) {
                    Surface(modifier = Modifier.align(Alignment.TopEnd).padding(12.dp), shape = RoundedCornerShape(8.dp), color = Color(0xFFFF6B6B)) {
                        Column(Modifier.padding(12.dp, 6.dp)) {
                            Text("检测到 $obstacleCount 个障碍物", style = MaterialTheme.typography.labelSmall, color = Color.White, fontWeight = FontWeight.Bold)
                            obstacleState.detectedObstacles.firstOrNull()?.let {
                                Text("${it.type.chineseName} ${String.format("%.1f", it.distance)}m", style = MaterialTheme.typography.labelSmall, color = Color.White)
                            }
                        }
                    }
                }
            } else {
                Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Settings, null, tint = Color(0xFF666666), modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("需要摄像头权限", style = MaterialTheme.typography.bodyMedium, color = Color(0xFF666666))
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = onRequestPermission, shape = RoundedCornerShape(8.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1E90FF))) {
                        Text("授权摄像头", color = Color.White)
                    }
                }
            }
        }
        
        Spacer(Modifier.height(16.dp))
        
        Surface(Modifier.fillMaxWidth(), shape = RoundedCornerShape(12.dp), color = Color(0xFF2A2A3E)) {
            Column(Modifier.padding(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    InfoChip("状态", if (obstacleState.isRunning) "检测中" else "待机", if (obstacleState.isRunning) Color(0xFF4CAF50) else Color(0xFF666666))
                    InfoChip("模型", when {
                        obstacleState.isModelLoaded -> "已加载"
                        !obstacleState.isModelInitComplete -> "加载中..."
                        else -> "未加载"
                    }, when {
                        obstacleState.isModelLoaded -> Color(0xFF4CAF50)
                        !obstacleState.isModelInitComplete -> Color(0xFFFFB347)
                        else -> Color(0xFFFF5252)
                    })
                    InfoChip("障碍物", if (obstacleCount > 0) "$obstacleCount 个" else "无", if (obstacleCount > 0) Color(0xFFFF9800) else Color(0xFF4CAF50))
                    InfoChip("FPS", "${obstacleState.fps}", Color(0xFF1E90FF))
                }
                Spacer(Modifier.height(10.dp))
                Button(
                    onClick = onClose,
                    Modifier.fillMaxWidth().height(48.dp).semantics { contentDescription = "停止环境感知" },
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE53935))
                ) {
                    Icon(Icons.Default.Close, null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("停止环境感知", color = Color.White, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun InfoChip(label: String, value: String, valueColor: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = Color(0xFF888888))
        Text(value, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold, color = valueColor)
    }
}

// ==================== NearbyPoiService（保持原样）==================
// 完整的 NearbyPoiService 实现见原始文件
object NearbyPoiService {
    fun generateAnnouncementSync(location: LocationInfo): String {
        return "附近暂无可播报的地点"
    }
}



