package com.blindpath.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_settings.ui.SettingsScreen
import com.blindpath.module_community.ui.CommunityScreen
import com.blindpath.module_trip_assist.ui.TripAssistScreen

/**
 * 主界面 - 视障友好设计
 * - 大按钮（便于触摸）
 * - 高对比度颜色
 * - 所有元素有语音标签
 */
@Composable
fun MainScreen(
    obstacleRepository: ObstacleRepository,
    onObstacleDetectionClick: () -> Unit = {},
    onLocationClick: () -> Unit = {},
    onSosClick: () -> Unit = {}
) {
    var showSettings by remember { mutableStateOf(false) }
    var showCommunity by remember { mutableStateOf(false) }
    var showTripAssist by remember { mutableStateOf(false) }
    var showObstacleDetection by remember { mutableStateOf(false) }
    var showLocation by remember { mutableStateOf(false) }
    var showNavigation by remember { mutableStateOf(false) }

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
        else -> {
            MainContent(
                onObstacleDetectionClick = { showObstacleDetection = true },
                onLocationClick = { showLocation = true },
                onNavigationClick = { showNavigation = true },
                onSosClick = onSosClick,
                onSettingsClick = { showSettings = true },
                onCommunityClick = { showCommunity = true },
                onTripAssistClick = { showTripAssist = true }
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
    onTripAssistClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 应用标题
            Text(
                text = "智行助盲",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics {
                    contentDescription = "智行助盲，视障人士出行辅助应用"
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "BlindPath v3.0",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "BlindPath 版本 1.0"
                }
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 核心功能按钮区域
            FeatureButton(
                label = "障碍物检测",
                description = "开启摄像头，实时检测前方障碍物",
                onClick = onObstacleDetectionClick,
                containerColor = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            FeatureButton(
                label = "实时定位",
                description = "获取当前位置，播报道路和方位信息",
                onClick = onLocationClick,
                containerColor = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(16.dp))

            FeatureButton(
                label = "智能导航",
                description = "输入目的地，规划路线，语音引导前行",
                onClick = onNavigationClick,
                containerColor = MaterialTheme.colorScheme.tertiary
            )

            Spacer(modifier = Modifier.height(16.dp))

            FeatureButton(
                label = "紧急求助",
                description = "一键联系紧急联系人，发送位置信息",
                onClick = onSosClick,
                containerColor = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 辅助功能按钮行
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 设置按钮
                OutlinedButton(
                    onClick = onSettingsClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .semantics {
                            contentDescription = "设置按钮"
                        },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("设置")
                }

                // 社区按钮
                OutlinedButton(
                    onClick = onCommunityClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .semantics {
                            contentDescription = "社区互助按钮"
                        },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.People,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("社区")
                }

                // 出行辅助按钮
                OutlinedButton(
                    onClick = onTripAssistClick,
                    modifier = Modifier
                        .weight(1f)
                        .height(56.dp)
                        .semantics {
                            contentDescription = "出行辅助按钮"
                        },
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Navigation,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("出行")
                }
            }
        }
    }
}

/**
 * 功能按钮组件 - 视障友好设计
 */
@Composable
fun FeatureButton(
    label: String,
    description: String,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color
) {
    val combinedDescription = "$label，$description"

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .semantics {
                contentDescription = combinedDescription
            },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 12.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.Start,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 20.sp
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            )
        }
    }
}
