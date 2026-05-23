package com.blindpath.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
 * 主界面 - 视障友好极简设计 v3.0
 * 
 * 设计原则：
 * 1. 极简导航：核心功能一屏展示，减少层级跳转
 * 2. 大按钮设计：最小触摸区域 80dp，符合 WCAG 2.1 标准
 * 3. 高对比度：使用 Material 3 高对比度配色
 * 4. 语音反馈：所有按钮添加详细语义描述
 * 5. 一键操作：减少复杂交互，支持长按、双击等辅助手势
 * 6. 语音交互：支持语音指令控制所有功能
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
                    // TODO: 实现声呐检测
                    viewModel.speak("声呐检测功能即将上线")
                    true
                }
                VoiceCommand.STOP_SONAR_DETECTION -> {
                    // TODO: 实现声呐检测
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
                    // TODO: 实现重复上一条播报
                    viewModel.speak("暂无上一条播报")
                    true
                }
                VoiceCommand.CANCEL -> {
                    viewModel.speak("已取消")
                    true
                }
                VoiceCommand.BACK -> {
                    // 关闭所有子界面
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
        
        // 初始化语音交互
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
            MainContent(
                onObstacleDetectionClick = { showObstacleDetection = true },
                onLocationClick = { showLocation = true },
                onNavigationClick = { showNavigation = true },
                onSosClick = onSosClick,
                onSettingsClick = { showSettings = true },
                onCommunityClick = { showCommunity = true },
                onTripAssistClick = { showTripAssist = true },
                onIndoorClick = { showIndoorScreen = true },
                isListening = uiState.isListening,
                onStartListening = { viewModel.startListening() },
                onStopListening = { viewModel.stopListening() }
            )
        }
    }
}

@Composable
private fun MainContent(
    onObstacleDetectionClick: () -> Unit,
    onLocationClick: () -> Unit,
    onNavigationClick: () -> Unit,
    onSosClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCommunityClick: () -> Unit,
    onTripAssistClick: () -> Unit,
    onIndoorClick: () -> Unit,
    isListening: Boolean = false,
    onStartListening: () -> Unit = {},
    onStopListening: () -> Unit = {}
) {
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 应用标题区域
            AppHeader()

            Spacer(modifier = Modifier.height(24.dp))

            // 核心功能按钮区域（大按钮，一屏展示）
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // 第一行：障碍物检测 + 实时定位
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LargeFeatureButton(
                        label = "障碍物检测",
                        description = "开启摄像头，实时检测前方障碍物，语音播报预警信息。语音指令：开启障碍物检测",
                        icon = Icons.Default.Warning,
                        onClick = onObstacleDetectionClick,
                        containerColor = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )

                    LargeFeatureButton(
                        label = "实时定位",
                        description = "获取当前位置，播报道路名称和方位信息。语音指令：我在哪里",
                        icon = Icons.Default.LocationOn,
                        onClick = onLocationClick,
                        containerColor = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 第二行：智能导航 + 紧急求助
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    LargeFeatureButton(
                        label = "智能导航",
                        description = "输入目的地，规划路线，语音引导前行。语音指令：开启导航",
                        icon = Icons.Default.Navigation,
                        onClick = onNavigationClick,
                        containerColor = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.weight(1f)
                    )

                    LargeFeatureButton(
                        label = "紧急求助",
                        description = "一键联系紧急联系人，发送位置信息，拨打急救电话。语音指令：紧急救援",
                        icon = Icons.Default.Emergency,
                        onClick = onSosClick,
                        containerColor = MaterialTheme.colorScheme.error,
                        modifier = Modifier.weight(1f)
                    )
                }

                // 第三行：室内模式
                LargeFeatureButton(
                    label = "室内模式",
                    description = "识别室内房间类型和家具障碍物，适合家庭环境使用。语音指令：开启室内模式",
                    icon = Icons.Default.Home,
                    onClick = onIndoorClick,
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 辅助功能按钮行（底部）
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                AccessibleTextButton(
                    label = "设置",
                    description = "应用设置，调整语音、导航、安全等选项。语音指令：设置",
                    icon = Icons.Default.Settings,
                    onClick = onSettingsClick,
                    modifier = Modifier.weight(1f)
                )

                AccessibleTextButton(
                    label = "社区",
                    description = "社区互助，获取志愿者帮助和分享出行经验",
                    icon = Icons.Default.People,
                    onClick = onCommunityClick,
                    modifier = Modifier.weight(1f)
                )

                AccessibleTextButton(
                    label = "出行",
                    description = "出行辅助，查看行程记录和常用地点",
                    icon = Icons.Default.DirectionsWalk,
                    onClick = onTripAssistClick,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * 应用标题区域
 */
@Composable
private fun AppHeader() {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = "智行助盲",
            style = MaterialTheme.typography.displayMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics {
                contentDescription = "智行助盲，视障人士出行辅助应用"
            }
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "BlindPath v3.5",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics {
                contentDescription = "BlindPath 版本 3.5"
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "点击按钮开始使用",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics {
                contentDescription = "提示：点击下方按钮开始使用各项功能"
            }
        )
    }
}

/**
 * 大功能按钮组件 - 视障友好设计
 * 
 * 特性：
 * - 最小高度 100dp，符合 WCAG 2.1 触摸目标标准
 * - 高对比度配色
 * - 详细语义描述
 * - 图标 + 文字双重提示
 */
@Composable
fun LargeFeatureButton(
    label: String,
    description: String,
    icon: ImageVector,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    val combinedDescription = "$label，$description"

    Button(
        onClick = onClick,
        modifier = modifier
            .height(100.dp)
            .semantics {
                contentDescription = combinedDescription
                // 添加状态描述，方便屏幕阅读器播报
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
 * 可访问性文本按钮 - 辅助功能
 * 
 * 特性：
 * - 高度 64dp
 * - 图标 + 文字标签
 * - 详细语义描述
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
    containerColor: androidx.compose.ui.graphics.Color
) {
    LargeFeatureButton(
        label = label,
        description = description,
        icon = Icons.Default.Star,
        onClick = onClick,
        containerColor = containerColor
    )
}
