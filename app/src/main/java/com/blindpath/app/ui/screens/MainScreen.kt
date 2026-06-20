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
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Call
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
import androidx.compose.material3.TopAppBarDefaults
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
import androidx.compose.ui.unit.sp
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
import com.blindpath.app.voice.BlindPathNavigationExecutor
import com.blindpath.app.voice.BlindPathSceneExecutor
import com.blindpath.app.voice.BlindPathSosExecutor
import com.blindpath.app.voice.BlindPathVoiceControlExecutor
import com.blindpath.module_voice.domain.model.NluResult
import com.blindpath.module_voice.domain.model.VoiceIntent
import com.blindpath.module_voice.domain.model.VoiceCommandIntentBridge
import com.blindpath.module_voice.viewmodel.VoiceInteractionViewModel
import com.blindpath.app.ui.theme.ModuleIndoor
import com.blindpath.app.ui.theme.ModuleIndoorBg
import com.blindpath.app.ui.theme.ModuleNavigation
import com.blindpath.app.ui.theme.ModuleNavigationBg
import com.blindpath.app.ui.theme.ModuleScene
import com.blindpath.app.ui.theme.ModuleSceneBg
import com.blindpath.app.ui.theme.ModuleSos
import com.blindpath.app.ui.theme.ModuleSosBg
import timber.log.Timber

/**
 * 主界面 - 高对比无障碍设计
 * 设计原则：WCAG AAA (7:1+对比度) + 大字号 + 鲜明功能色
 * 三大模块入口：室内感知 / 出行导航 / 场景感知
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository,
    indoorDetector: IndoorDetector,
    sceneClassifier: SceneClassifier,
    navExecutor: BlindPathNavigationExecutor,
    sceneExec: BlindPathSceneExecutor,
    voiceCtrlExec: BlindPathVoiceControlExecutor,
    sosExec: BlindPathSosExecutor,
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

    // ★ NLU四层架构：注入Executor + 设置意图动作回调
    LaunchedEffect(Unit) {
        viewModel.setExecutors(navExecutor, sceneExec, voiceCtrlExec, sosExec)

        viewModel.setIntentActionHandler { intent, nluResult ->
            Timber.d("MainScreen: NLU意图动作 → ${intent.id}")
            handleVoiceIntent(intent, nluResult,
                onShowIndoor = { showIndoorPerception = true },
                onShowOutdoor = { showOutdoorNavigation = true },
                onShowAr = { showArNavigation = true },
                onShowScene = { showScenePerception = true },
                onHideIndoor = { showIndoorPerception = false },
                onHideOutdoor = { showOutdoorNavigation = false },
                onHideAr = { showArNavigation = false },
                onHideScene = { showScenePerception = false },
                onSos = onSosClick,
                onLocation = onLocationClick,
                viewModel = viewModel
            )
        }

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
                    showOutdoorNavigation = false
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
                VoiceCommand.QUERY_LOCATION -> {
                    onLocationClick()
                    true
                }
                VoiceCommand.SOS -> {
                    onSosClick()
                    true
                }
                else -> false
            }
        }
    }

    // === 子页面全屏覆盖 ===
    if (showSettings) {
        SettingsScreen(onBack = { showSettings = false })
        return
    }
    if (showCommunity) {
        CommunityScreen(onBack = { showCommunity = false })
        return
    }
    if (showTripAssist) {
        TripAssistScreen(onBack = { showTripAssist = false })
        return
    }
    if (showIndoorPerception) {
        IndoorPerceptionScreen(
            indoorDetector = indoorDetector,
            onBack = { showIndoorPerception = false },
            voiceViewModel = viewModel
        )
        return
    }
    if (showOutdoorNavigation) {
        OutdoorNavigationScreen(
            navigationRepository = navigationRepository,
            onBack = { showOutdoorNavigation = false },
            onSwitchToAr = {
                showOutdoorNavigation = false
                showArNavigation = true
            },
            viewModel = viewModel
        )
        return
    }
    if (showArNavigation) {
        ARNavigationScreen(
            obstacleRepository = obstacleRepository,
            navigationRepository = navigationRepository,
            onBack = {
                showArNavigation = false
                showOutdoorNavigation = true
            },
            viewModel = viewModel
        )
        return
    }
    if (showScenePerception) {
        ScenePerceptionScreen(
            sceneClassifier = sceneClassifier,
            onBack = { showScenePerception = false },
            voiceViewModel = viewModel
        )
        return
    }
    if (showNavigationGuide) {
        NavigationGuideScreen(onBack = { showNavigationGuide = false })
        return
    }

    // === 主界面 ===
    MainScreenContent(
        uiState = uiState,
        onStartListening = { viewModel.startListening() },
        onStopListening = { viewModel.stopListening() },
        onIndoorPerceptionClick = { showIndoorPerception = true },
        onOutdoorNavigationClick = { showOutdoorNavigation = true },
        onScenePerceptionClick = { showScenePerception = true },
        onSosClick = onSosClick,
        onSettingsClick = { showSettings = true },
        onCommunityClick = { showCommunity = true }
    )
}

/**
 * 主界面内容 - 高对比无障碍布局
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MainScreenContent(
    uiState: com.blindpath.module_voice.viewmodel.VoiceUiState,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    onIndoorPerceptionClick: () -> Unit,
    onOutdoorNavigationClick: () -> Unit,
    onScenePerceptionClick: () -> Unit,
    onSosClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCommunityClick: () -> Unit
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
                .padding(horizontal = 20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(20.dp))

            // 欢迎语 - 大号高对比
            Text(
                text = "您好，我是小智",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface  // 白色(暗)/深色(亮)
            )
            Text(
                text = "请说\"小智小智\"唤醒我",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurfaceVariant  // 亮灰(暗)/深灰(亮)
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 分区标题
            SectionTitle(text = "选择功能模块")

            Spacer(modifier = Modifier.height(14.dp))

            // ★ 三大核心模块 — 实色卡片 + 白字
            ModuleCard(
                title = "室内感知",
                subtitle = "室内导航 · 障碍物检测 · 空间理解",
                icon = Icons.Default.Home,
                accentColor = ModuleIndoor,
                cardBgColor = ModuleIndoorBg,
                onClick = onIndoorPerceptionClick,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            ModuleCard(
                title = "出行导航",
                subtitle = "路线规划 · 盲道引导 · 交通辅助",
                icon = Icons.Default.Place,
                accentColor = ModuleNavigation,
                cardBgColor = ModuleNavigationBg,
                onClick = onOutdoorNavigationClick,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(14.dp))

            ModuleCard(
                title = "场景感知",
                subtitle = "物体识别 · 场景描述 · 文字朗读",
                icon = Icons.Default.Search,
                accentColor = ModuleScene,
                cardBgColor = ModuleSceneBg,
                onClick = onScenePerceptionClick,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 快速功能
            SectionTitle(text = "快速功能")

            Spacer(modifier = Modifier.height(14.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                QuickActionButton(
                    icon = Icons.Default.LocationOn,
                    label = "我的位置",
                    accentColor = ModuleIndoor,
                    cardBgColor = ModuleIndoorBg,
                    onClick = { /* TODO */ },
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    icon = Icons.Default.Call,
                    label = "紧急求助",
                    accentColor = ModuleSos,
                    cardBgColor = ModuleSosBg,
                    onClick = onSosClick,
                    modifier = Modifier.weight(1f)
                )
            }

            Spacer(modifier = Modifier.height(28.dp))

            // 语音唤醒按钮 - 大号醒目
            VoiceWakeUpButton(
                isListening = uiState.isListening,
                onClick = { if (uiState.isListening) onStopListening() else onStartListening() },
                modifier = Modifier.size(88.dp)
            )

            Spacer(modifier = Modifier.height(10.dp))

            Text(
                text = if (uiState.isListening) "正在聆听..." else "点击唤醒",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                color = if (uiState.isListening) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(28.dp))
        }
    }
}

/**
 * 分区标题 - 高对比
 */
@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = text }
    )
}

/**
 * 功能模块卡片 - 实色背景 + 白字 + 高对比
 * 设计：深色实底卡片，左侧亮色图标区，右侧白色标题+亮色副标题
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ModuleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accentColor: Color,
    cardBgColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .height(108.dp)
            .semantics {
                contentDescription = "$title，$subtitle"
            },
        shape = RoundedCornerShape(18.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(18.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 左侧图标区 - 亮色方块
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(accentColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(34.dp)
                )
            }

            Spacer(modifier = Modifier.width(18.dp))

            // 右侧文字
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color.White  // 纯白，对比度最高
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    color = accentColor  // 亮色副标题，与图标色呼应
                )
            }

            // 右箭头
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowForward,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(26.dp)
            )
        }
    }
}

/**
 * 快速操作按钮 - 实色底 + 白字
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    accentColor: Color,
    cardBgColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .height(88.dp)
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = cardBgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accentColor,
                modifier = Modifier.size(34.dp)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 语音唤醒按钮 - 高对比大号
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
        targetValue = if (isListening) 1.08f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )

    val buttonColor = if (isListening) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .scale(pulseScale)
            .clip(CircleShape)
            .background(buttonColor)
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (isListening) "正在聆听，点击停止" else "点击唤醒语音助手" },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isListening) Icons.Default.Home else Icons.Default.Search,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(42.dp)
        )
    }
}

/**
 * 顶部栏 - 适配主题色
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
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        },
        actions = {
            IconButton(onClick = onCommunityClick) {
                Icon(
                    imageVector = Icons.Default.Place,
                    contentDescription = "社区",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "设置",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            actionIconContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    )
}

/**
 * 处理NLU意图 → UI动作映射
 */
private fun handleVoiceIntent(
    intent: VoiceIntent,
    nluResult: NluResult,
    onShowIndoor: () -> Unit,
    onShowOutdoor: () -> Unit,
    onShowAr: () -> Unit,
    onShowScene: () -> Unit,
    onHideIndoor: () -> Unit,
    onHideOutdoor: () -> Unit,
    onHideAr: () -> Unit,
    onHideScene: () -> Unit,
    onSos: () -> Unit,
    onLocation: () -> Unit,
    viewModel: VoiceInteractionViewModel
): Boolean {
    return when (intent) {
        VoiceIntent.NAVIGATE_TO -> {
            onShowOutdoor()
            true
        }
        VoiceIntent.NAVIGATE_HOME -> {
            onShowOutdoor()
            true
        }
        VoiceIntent.STOP_NAVIGATION -> {
            onHideOutdoor()
            onHideAr()
            true
        }
        VoiceIntent.SWITCH_AR_MODE -> {
            onHideOutdoor()
            onShowAr()
            true
        }
        VoiceIntent.SWITCH_VOICE_MODE -> {
            onHideAr()
            onShowOutdoor()
            true
        }
        VoiceIntent.LOOK_AHEAD -> {
            onShowScene()
            true
        }
        VoiceIntent.START_DETECTION -> {
            onShowScene()
            true
        }
        VoiceIntent.STOP_DETECTION -> {
            onHideScene()
            true
        }
        VoiceIntent.INDOOR_NAVIGATE -> {
            onShowIndoor()
            true
        }
        VoiceIntent.SOS -> {
            onSos()
            true
        }
        VoiceIntent.QUERY_LOCATION -> {
            onLocation()
            true
        }
        VoiceIntent.VOLUME_UP,
        VoiceIntent.VOLUME_DOWN,
        VoiceIntent.SPEED_UP,
        VoiceIntent.SPEED_DOWN,
        VoiceIntent.QUERY_DISTANCE,
        VoiceIntent.QUERY_FLOOR,
        VoiceIntent.REPEAT -> {
            true
        }
        VoiceIntent.HELP -> {
            true
        }
        VoiceIntent.UNKNOWN -> {
            Timber.w("MainScreen: 未知意图 → ${nluResult.rawText}")
            false
        }
    }
}
