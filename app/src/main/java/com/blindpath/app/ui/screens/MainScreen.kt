package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindpath.module_obstacle.data.detection.SceneClassifier
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.ObstacleState
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_settings.ui.SettingsScreen
import com.blindpath.module_community.ui.CommunityScreen
import com.blindpath.module_trip_assist.ui.TripAssistScreen
import com.blindpath.module_voice.domain.model.VoiceCommand
import com.blindpath.module_indoor.data.IndoorDetector
import com.blindpath.module_voice.domain.model.VoiceGuidance
import com.blindpath.module_voice.viewmodel.VoiceInteractionViewModel
import timber.log.Timber

/**
 * 主界面 - 视障友好极简设计
 * 三大模块入口：室内感知 / 出行导航 / 场景感知
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository,
    indoorDetector: IndoorDetector,
    sceneClassifier: SceneClassifier,
    cameraXManager: com.blindpath.app.ui.camera.CameraXManager,
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
    var showArNavigation by remember { mutableStateOf(false) }
    var showScenePerception by remember { mutableStateOf(false) }
    var showNavigationGuide by remember { mutableStateOf(false) }

    val obstacleState by obstacleRepository.obstacleState.collectAsStateWithLifecycle(
        initialValue = ObstacleState()
    )

    // 语音指令处理器
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
                VoiceCommand.START_AR_NAVIGATION -> {
                    showArNavigation = true
                    viewModel.speak("AR实景导航已启动")
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

    // 页面路由 - 使用 when 表达式避免 return 导致不可达代码
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
        showIndoorPerception -> {
            IndoorPerceptionScreen(
                obstacleRepository = obstacleRepository,
                navigationRepository = navigationRepository,
                onBack = { showIndoorPerception = false },
                viewModel = viewModel
            )
        }
        showOutdoorNavigation -> {
            OutdoorNavigationScreen(
                obstacleRepository = obstacleRepository,
                navigationRepository = navigationRepository,
                sceneClassifier = sceneClassifier,
                onBack = { showOutdoorNavigation = false },
                onHelpClick = { showNavigationGuide = true },
                viewModel = viewModel
            )
        }
        showArNavigation -> {
            ArNavigationScreen(
                cameraXManager = cameraXManager,
                onNavigateBack = { showArNavigation = false },
                onEmergencyCall = onSosClick
            )
        }
        showNavigationGuide -> {
            NavigationGuideScreen(onBack = { showNavigationGuide = false })
        }
        showScenePerception -> {
            ScenePerceptionScreen(
                obstacleRepository = obstacleRepository,
                indoorDetector = indoorDetector,
                sceneClassifier = sceneClassifier,
                onBack = { showScenePerception = false },
                viewModel = viewModel
            )
        }
        else -> {
            // 主界面内容
            MainScreenContent(
                uiState = uiState,
        obstacleState = obstacleState,
        onIndoorPerceptionClick = { showIndoorPerception = true },
        onOutdoorNavigationClick = { showOutdoorNavigation = true },
        onScenePerceptionClick = { showScenePerception = true },
        onSettingsClick = { showSettings = true },
        onCommunityClick = { showCommunity = true },
        onTripAssistClick = { showTripAssist = true },
        onArNavigationClick = { showArNavigation = true },
        onSosClick = onSosClick,
        onStartListening = { viewModel.startListening() },
        onStopListening = { viewModel.stopListening() },
        viewModel = viewModel
    )
        }
    }
}

/**
 * 主界面内容
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(
    uiState: com.blindpath.module_voice.viewmodel.VoiceInteractionUiState,
    obstacleState: ObstacleState,
    onIndoorPerceptionClick: () -> Unit,
    onOutdoorNavigationClick: () -> Unit,
    onScenePerceptionClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCommunityClick: () -> Unit,
    onTripAssistClick: () -> Unit,
    onArNavigationClick: () -> Unit,
    onSosClick: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
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

            // 模块 2：AR 实景导航（新增）
            ModuleCard(
                title = "AR 实景导航",
                subtitle = "摄像头实时识别 · 障碍物预警 · 语音播报",
                icon = Icons.Default.CameraAlt,
                backgroundColor = Color(0xFFE91E63),
                onClick = onArNavigationClick,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 模块 3：出行导航
            ModuleCard(
                title = "出行导航",
                subtitle = "路线规划 · 盲道引导 · 交通辅助",
                icon = Icons.Default.Place,
                backgroundColor = Color(0xFF4CAF50),
                onClick = onOutdoorNavigationClick,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 模块 4：场景感知
            ModuleCard(
                title = "场景感知",
                subtitle = "物体识别 · 场景描述 · 文字朗读",
                icon = Icons.Default.Search,
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
                    icon = Icons.Default.Call,
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
@OptIn(ExperimentalMaterial3Api::class)
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

            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
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
@OptIn(ExperimentalMaterial3Api::class)
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
    val pulseScale by infiniteTransition.animateFloat(
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
            .scale(pulseScale)
            .clip(CircleShape)
            .background(
                if (isListening) Color(0xFF4CAF50) else Color(0xFF1E90FF)
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (isListening) "正在聆听，点击停止" else "点击唤醒语音助手" },
        contentAlignment = Alignment.Center
    ) {
        // 使用 Home 图标代替 Mic（避免 material-icons-extended 依赖问题）
        Icon(
            imageVector = if (isListening) Icons.Default.Home else Icons.Default.Search,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp)
        )
    }
}

/**
 * 顶部栏
 */
@OptIn(ExperimentalMaterial3Api::class)
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
                    imageVector = Icons.Default.Place,
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
