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
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
    var hasAnnouncedWelcome by remember { mutableStateOf(false) }
    LaunchedEffect(uiState.isInitialized) {
        if (uiState.isInitialized && !hasAnnouncedWelcome) {
            hasAnnouncedWelcome = true
            viewModel.speak("已进入主界面，请说\"小智小智\"唤醒语音助手，或者说\"帮助\"查看可用指令")
        }
    }

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
        showObstacleDetection -> {
            ObstacleDetectionScreen(onBackClick = { showObstacleDetection = false })
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
            MainContentV5(
                onNavigationClick = { showNavigation = true },
                onSosClick = onSosClick,
                onSettingsClick = { showSettings = true },
                isListening = uiState.isListening,
                onStartListening = { viewModel.startListening() },
                onStopListening = { viewModel.stopListening() }
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
    onNavigationClick: () -> Unit,
    onSosClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isListening: Boolean = false,
    onStartListening: () -> Unit = {},
    onStopListening: () -> Unit = {}
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // 相机权限状态
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }
    
    // 首次进入自动请求相机权限
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
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
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(horizontal = 12.dp)
            ) {
                // 上半：摄像头预览
                CameraPreviewCard(
                    hasPermission = hasCameraPermission,
                    onRequestPermission = { cameraPermissionLauncher.launch(Manifest.permission.CAMERA) },
                    lifecycleOwner = lifecycleOwner,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                // 下半：地图预览（简化版）
                MapPreviewCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(16.dp))
                )
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
 */
@Composable
private fun CameraPreviewCard(
    hasPermission: Boolean,
    onRequestPermission: () -> Unit,
    lifecycleOwner: androidx.lifecycle.LifecycleOwner,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFF1A1A2E))
            .semantics {
                contentDescription = "摄像头实时预览，显示前方环境画面"
            }
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
            
            // 安全状态叠加层
            Surface(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
                shape = RoundedCornerShape(8.dp),
                color = Color(0xCC4CAF50) // 绿色安全
            ) {
                Text(
                    text = "环境安全",
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
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
 * 地图预览卡片 - 首页下半部分（简化版）
 */
@Composable
private fun MapPreviewCard(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .background(Color(0xFFF5F5F5))
            .semantics {
                contentDescription = "地图预览，显示当前位置和周边信息"
            }
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = Icons.Default.LocationOn,
                contentDescription = null,
                tint = Color(0xFF1E90FF),
                modifier = Modifier.size(48.dp)
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "地图加载中...",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF666666)
            )
        }
        
        // 位置信息叠加层
        Surface(
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
            shape = RoundedCornerShape(8.dp),
            color = Color.White.copy(alpha = 0.9f)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.MyLocation,
                    contentDescription = null,
                    tint = Color(0xFF1E90FF),
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "正在获取位置...",
                    style = MaterialTheme.typography.labelMedium,
                    color = Color(0xFF333333)
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
