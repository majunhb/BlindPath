package com.blindpath.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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

/**
 * 主界面 - 视障友好极简设计 v4.0
 * 
 * 设计原则（基于用户设计稿）：
 * 1. 垂直信息流：状态 → 反馈 → 核心操作
 * 2. 大按钮设计：唤醒按钮占据近1/4屏幕，便于盲操作
 * 3. 高对比度：蓝白主调，红色警示
 * 4. 底部双入口：切换导航 + 紧急求助
 * 5. AI人格化："小智"作为具象化助手
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
                    viewModel.speak(VoiceGuidance.NAVIGATION_STARTED)
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
            MainContentV4(
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
 * 主界面内容 - v4.0 设计
 * 
 * 布局结构：
 * ┌─────────────────────────┐
 * │  顶部导航栏              │
 * │  [消息] 智行助盲         │
 * ├─────────────────────────┤
 * │  语音状态标签            │
 * │  [小智] 我在外出...      │
 * ├─────────────────────────┤
 * │  AI状态反馈              │
 * │  系统提示信息            │
 * ├─────────────────────────┤
 * │  核心操作按钮            │
 * │  [唤醒小智] (大按钮)     │
 * ├─────────────────────────┤
 * │  底部快捷入口            │
 * │  [切换导航] [紧急求助]   │
 * └─────────────────────────┘
 */
@Composable
private fun MainContentV4(
    onNavigationClick: () -> Unit,
    onSosClick: () -> Unit,
    onSettingsClick: () -> Unit,
    isListening: Boolean = false,
    onStartListening: () -> Unit = {},
    onStopListening: () -> Unit = {}
) {
    // 渐变蓝色定义
    val gradientBlue = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF4FACFE),  // 浅蓝
            Color(0xFF00F2FE)   // 青蓝
        )
    )
    
    // 主品牌色
    val primaryBlue = Color(0xFF1E90FF)
    val alertRed = Color(0xFFFF6B6B)
    
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
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 语音状态标签区域
            VoiceStatusTag(
                isListening = isListening,
                modifier = Modifier.padding(vertical = 8.dp)
            )
            
            // AI状态反馈文字
            AiStatusFeedback(
                isListening = isListening,
                modifier = Modifier.padding(vertical = 12.dp)
            )
            
            Spacer(modifier = Modifier.weight(1f))
            
            // 核心操作按钮 - 唤醒小智（大面积渐变按钮）
            WakeUpButton(
                isListening = isListening,
                onClick = {
                    if (isListening) {
                        onStopListening()
                    } else {
                        onStartListening()
                    }
                },
                modifier = Modifier
                    .fillMaxWidth(0.9f)
                    .height(120.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 底部快捷入口 - 双按钮布局
            BottomQuickActions(
                onNavigationClick = onNavigationClick,
                onSosClick = onSosClick,
                modifier = Modifier.fillMaxWidth()
            )
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
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 机器人图标（简化）
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(Color.White),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = Color(0xFF1E90FF),
                    modifier = Modifier.size(16.dp)
                )
            }
            
            Text(
                text = statusText,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
        }
    }
}

/**
 * AI状态反馈文字
 */
@Composable
private fun AiStatusFeedback(
    isListening: Boolean,
    modifier: Modifier = Modifier
) {
    val feedbackText = if (isListening) {
        "请说出您的指令，例如：开启导航、我在哪里、紧急求助"
    } else {
        "点击下方按钮唤醒小智，或说\"小智小智\""
    }
    
    Text(
        text = feedbackText,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.Normal,
        color = Color(0xFF666666),
        textAlign = TextAlign.Center,
        modifier = modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = feedbackText
            }
    )
}

/**
 * 唤醒小智按钮 - 大面积渐变蓝色按钮
 */
@Composable
private fun WakeUpButton(
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val gradientBlue = Brush.horizontalGradient(
        colors = listOf(
            Color(0xFF4FACFE),
            Color(0xFF00F2FE)
        )
    )
    
    val buttonText = if (isListening) "停止聆听" else "唤醒小智"
    val buttonDesc = if (isListening) {
        "点击停止语音聆听"
    } else {
        "点击唤醒语音助手小智，开始语音交互"
    }
    
    Button(
        onClick = onClick,
        modifier = modifier
            .semantics {
                contentDescription = buttonDesc
                stateDescription = if (isListening) "正在聆听" else "待命状态"
            },
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent
        ),
        contentPadding = PaddingValues(0.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradientBlue, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 文字
                Text(
                    text = buttonText,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    fontSize = 22.sp,
                    color = Color.White
                )
                
                // 机器人图标
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

/**
 * 底部快捷入口 - 双按钮布局
 */
@Composable
private fun BottomQuickActions(
    onNavigationClick: () -> Unit,
    onSosClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // 切换导航按钮
        QuickActionButton(
            label = "切换导航",
            description = "切换导航模式，规划出行路线",
            icon = Icons.Default.Map,
            onClick = onNavigationClick,
            color = Color(0xFF1E90FF),
            modifier = Modifier.weight(1f)
        )
        
        // 紧急求助按钮（红色强调）
        QuickActionButton(
            label = "紧急求助",
            description = "紧急求助，一键联系紧急联系人",
            icon = Icons.Default.Emergency,
            onClick = onSosClick,
            color = Color(0xFFFF6B6B),
            modifier = Modifier.weight(1f)
        )
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
            .height(64.dp)
            .semantics {
                contentDescription = "$label，$description"
            },
        shape = RoundedCornerShape(16.dp),
        border = androidx.compose.foundation.BorderStroke(2.dp, color)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = color,
                modifier = Modifier.size(24.dp)
            )
            
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                color = color,
                fontSize = 16.sp
            )
        }
    }
}

// ============ 兼容旧版本的组件 ============

/**
 * 大功能按钮组件 - 视障友好设计（兼容旧版本）
 */
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
            .semantics {
                contentDescription = combinedDescription
                stateDescription = "可点击按钮"
            },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor
        ),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
        elevation = ButtonDefaults.buttonElevation(
            defaultElevation = 4.dp,
            pressedElevation = 8.dp
        )
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.onPrimary
            )
            
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                fontSize = 18.sp,
                textAlign = TextAlign.Center,
                maxLines = 1
            )
        }
    }
}

/**
 * 可访问性文本按钮 - 辅助功能（兼容旧版本）
 */
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
            .semantics {
                contentDescription = combinedDescription
                stateDescription = "可点击按钮"
            },
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp,
                maxLines = 1
            )
        }
    }
}

/**
 * 功能按钮组件 - 兼容旧版本
 * @deprecated 请使用 LargeFeatureButton
 */
@Composable
fun FeatureButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    containerColor: Color
) {
    LargeFeatureButton(
        label = label,
        description = description,
        icon = Icons.Default.Star,
        onClick = onClick,
        containerColor = containerColor
    )
}