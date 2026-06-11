/**
 * BlindPath - 视障人士出行辅助应用
 * 
 * 文件：MainScreen.kt
 * 路径：app/src/main/java/com/blindpath/app/ui/screens/
 * 
 * 版本 v3.0 - 首页重构
 * 
 * 重构内容：
 * 1. 移除"环境感知"入口
 * 2. 首页直接显示三大模块：室内感知、出行导航、场景感知
 * 3. 每个模块独立入口，功能更加清晰
 * 
 * 三大核心模块：
 * - 室内感知：室内导航、障碍物检测、空间理解
 * - 出行导航：室外导航、盲道引导、交通辅助
 * - 场景感知：物体识别、场景描述、文字朗读
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
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
 * 主界面 - 视障友好极简设计 v6.0
 * 
 * 设计原则：
 * 1. 首页三大模块入口：室内感知 / 出行导航 / 场景感知
 * 2. 每个模块功能独立清晰
 * 3. 语音指令驱动
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
    var showIndoorPerception by remember { mutableStateOf(false) }
    var showOutdoorNavigation by remember { mutableStateOf(false) }
    var showScenePerception by remember { mutableStateOf(false) }

    // 收集障碍物状态
    val obstacleState by obstacleRepository.obstacleState.collectAsStateWithLifecycle(
        initialValue = ObstacleState()
    )

    val commandScope = rememberCoroutineScope()

    // 设置语音指令处理器
    LaunchedEffect(Unit) {
        viewModel.setCommandHandler { command ->
            Timber.d("MainScreen: Handling voice command - ${command.name}")
            when (command) {
                VoiceCommand.START_INDOOR_PERCEPTION -> {
                    showIndoorPerception = true
                    viewModel.speak("室内感知已启动")
                    true
                }
                VoiceCommand.STOP_INDOOR_PERCEPTION -> {
                    showIndoorPerception = false
                    viewModel.speak("室内感知已停止")
                    true
                }
                VoiceCommand.START_OUTDOOR_NAVIGATION -> {
                    showOutdoorNavigation = true
                    viewModel.speak("出行导航已启动")
                    true
                }
                VoiceCommand.STOP_OUTDOOR_NAVIGATION -> {
                    showOutdoorNavigation = false
                    viewModel.speak("出行导航已停止")
                    true
                }
                VoiceCommand.START_SCENE_PERCEPTION -> {
                    showScenePerception = true
                    viewModel.speak("场景感知已启动")
                    true
                }
                VoiceCommand.STOP_SCENE_PERCEPTION -> {
                    showScenePerception = false
                    viewModel.speak("场景感知已停止")
                    true
                }
                VoiceCommand.WHERE_AM_I -> {
                    onLocationClick()
                    true
                }
                VoiceCommand.SOS, VoiceCommand.CALL_SOS -> {
                    onSosClick()
                    viewModel.speak(VoiceGuidance.SOS_TRIGGERED)
                    true
                }
                else -> false
            }
        }
    }

    // 页面路由
    when {
        showSettings -> {
            SettingsScreen(
                onBack = { showSettings = false },
                modifier = Modifier.fillMaxSize()
            )
            return
        }
        showCommunity -> {
            CommunityScreen(
                onBack = { showCommunity = false },
                modifier = Modifier.fillMaxSize()
            )
            return
        }
        showTripAssist -> {
            TripAssistScreen(
                onBack = { showTripAssist = false },
                modifier = Modifier.fillMaxSize()
            )
            return
        }
        showIndoorPerception -> {
            IndoorPerceptionScreen(
                obstacleRepository = obstacleRepository,
                navigationRepository = navigationRepository,
                onBack = { showIndoorPerception = false },
                viewModel = viewModel
            )
            return
        }
        showOutdoorNavigation -> {
            OutdoorNavigationScreen(
                obstacleRepository = obstacleRepository,
                navigationRepository = navigationRepository,
                onBack = { showOutdoorNavigation = false },
                viewModel = viewModel
            )
            return
        }
        showScenePerception -> {
            ScenePerceptionScreen(
                obstacleRepository = obstacleRepository,
                onBack = { showScenePerception = false },
                viewModel = viewModel
            )
            return
        }
    }

    // 主界面
    MainScreenContent(
        uiState = uiState,
        obstacleState = obstacleState,
        onIndoorPerceptionClick = { showIndoorPerception = true },
        onOutdoorNavigationClick = { showOutdoorNavigation = true },
        onScenePerceptionClick = { showScenePerception = true },
        onSettingsClick = { showSettings = true },
        onCommunityClick = { showCommunity = true },
        onTripAssistClick = { showTripAssist = true },
        onSosClick = onSosClick,
        onStartListening = { viewModel.startListening() },
        onStopListening = { viewModel.stopListening() },
        viewModel = viewModel
    )
}

/**
 * 主界面内容 - 三大模块入口
 */
@Composable
private fun MainScreenContent(
    uiState: VoiceInteractionUiState,
    obstacleState: ObstacleState,
    onIndoorPerceptionClick: () -> Unit,
    onOutdoorNavigationClick: () -> Unit,
    onScenePerceptionClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCommunityClick: () -> Unit,
    onTripAssistClick: () -> Unit,
    onSosClick: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // 权限状态
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == 
            PackageManager.PERMISSION_GRANTED
        )
    }
    
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            viewModel.speak("相机权限已获取")
        } else {
            viewModel.speak("需要相机权限才能使用视觉功能")
        }
    }

    // 导航状态
    val navState by navigationRepository.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            MainTopBar(
                onSettingsClick = onSettingsClick,
                onCommunityClick = onCommunityClick
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // 欢迎语
            Text(
                text = "您好，我是小智",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A3E)
            )
            Text(
                text = "请说\"小智小智\"唤醒我",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF666666),
                modifier = Modifier.padding(top = 4.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 三大核心模块入口
            Text(
                text = "选择功能模块",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A3E),
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 模块 1：室内感知
            ModuleCard(
                title = "室内感知",
                subtitle = "室内导航 · 障碍物检测 · 空间理解",
                icon = Icons.Default.Home,
                backgroundColor = Color(0xFF1E90FF),
                onClick = onIndoorPerceptionClick,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 模块 2：出行导航
            ModuleCard(
                title = "出行导航",
                subtitle = "路线规划 · 盲道引导 · 交通辅助",
                icon = Icons.Default.Navigation,
                backgroundColor = Color(0xFF4CAF50),
                onClick = onOutdoorNavigationClick,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 模块 3：场景感知
            ModuleCard(
                title = "场景感知",
                subtitle = "物体识别 · 场景描述 · 文字朗读",
                icon = Icons.Default.Visibility,
                backgroundColor = Color(0xFFFF9800),
                onClick = onScenePerceptionClick,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 快速功能
            Text(
                text = "快速功能",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A3E),
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionButton(
                    icon = Icons.Default.LocationOn,
                    label = "我的位置",
                    onClick = { /* TODO */ },
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    icon = Icons.Default.Phone,
                    label = "紧急求助",
                    onClick = onSosClick,
                    backgroundColor = Color(0xFFE53935),
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 语音唤醒按钮
            VoiceWakeUpButton(
                isListening = uiState.isListening,
                onClick = { if (uiState.isListening) onStopListening() else onStartListening() },
                modifier = Modifier.size(80.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = if (uiState.isListening) "正在聆听..." else "点击唤醒",
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.isListening) Color(0xFF4CAF50) else Color(0xFF666666)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * 模块卡片
 */
@Composable
private fun ModuleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .height(100.dp)
            .semantics { 
                contentDescription = "$title，$subtitle"
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 图标
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(backgroundColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // 文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A3E)
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF666666)
                )
            }
            
            // 箭头
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = backgroundColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * 快速操作按钮
 */
@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    backgroundColor: Color = Color(0xFF1E90FF),
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .height(80.dp)
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = backgroundColor,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = backgroundColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 语音唤醒按钮
 */
@Composable
private fun VoiceWakeUpButton(
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    Box(
        modifier = modifier
            .scale(scale)
            .clip(CircleShape)
            .background(
                if (isListening) Color(0xFF4CAF50) else Color(0xFF1E90FF)
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (isListening) "正在聆听，点击停止" else "点击唤醒语音助手" }
            .then(Modifier),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicNone,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp)
        )
    }
}

/**
 * 顶部栏
 */
@Composable
private fun MainTopBar(
    onSettingsClick: () -> Unit,
    onCommunityClick: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = "智行助盲",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        actions = {
            IconButton(onClick = onCommunityClick) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = "社区"
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "设置"
                )
            }
        }
    )
}

// ==================== 三大模块屏幕 ====================

/**
 * 室内感知屏幕
 */
@Composable
private fun IndoorPerceptionScreen(
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository,
    onBack: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
    // TODO: 实现室内感知功能
    ModuleScreenTemplate(
        title = "室内感知",
        onBack = onBack,
        viewModel = viewModel
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color(0xFF1E90FF)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "室内感知功能",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "室内导航 · 障碍物检测 · 空间理解",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF666666)
            )
        }
    }
}

/**
 * 出行导航屏幕
 */
@Composable
private fun OutdoorNavigationScreen(
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository,
    onBack: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
    // TODO: 实现出行导航功能
    ModuleScreenTemplate(
        title = "出行导航",
        onBack = onBack,
        viewModel = viewModel
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Navigation,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color(0xFF4CAF50)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "出行导航功能",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "路线规划 · 盲道引导 · 交通辅助",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF666666)
            )
        }
    }
}

/**
 * 场景感知屏幕
 */
@Composable
private fun ScenePerceptionScreen(
    obstacleRepository: ObstacleRepository,
    onBack: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
    // TODO: 实现场景感知功能
    ModuleScreenTemplate(
        title = "场景感知",
        onBack = onBack,
        viewModel = viewModel
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color(0xFFFF9800)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "场景感知功能",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "物体识别 · 场景描述 · 文字朗读",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF666666)
            )
        }
    }
}

/**
 * 模块屏幕模板
 */
@Composable
private fun ModuleScreenTemplate(
    title: String,
    onBack: () -> Unit,
    viewModel: VoiceInteractionViewModel,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            content()
        }
    }
}
