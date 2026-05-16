package com.blindpath.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.People
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import com.blindpath.module_settings.ui.SettingsScreen
import com.blindpath.module_community.ui.CommunityScreen

/**
 * 主界面 - 视障友好设计
 * - 大按钮（便于触摸）
 * - 高对比度颜色
 * - 所有元素有语音标签
 */
@Composable
fun MainScreen(
    navController: NavController? = null,
    onObstacleDetectionClick: () -> Unit = {},
    onLocationClick: () -> Unit = {},
    onSosClick: () -> Unit = {}
) {
    var showSettings by remember { mutableStateOf(false) }
    var showCommunity by remember { mutableStateOf(false) }

    when {
        showSettings -> {
            SettingsScreen(onBackClick = { showSettings = false })
        }
        showCommunity -> {
            CommunityScreen(onBackClick = { showCommunity = false })
        }
        else -> {
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
                text = "BlindPath",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "BlindPath 版本 1.0"
                }
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 功能按钮区域
            FeatureButton(
                label = "障碍物检测",
                description = "开启摄像头，实时检测前方障碍物",
                onClick = onObstacleDetectionClick,
                containerColor = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(24.dp))

            FeatureButton(
                label = "位置播报",
                description = "播报当前位置和周边地标",
                onClick = onLocationClick,
                containerColor = MaterialTheme.colorScheme.secondary
            )

            Spacer(modifier = Modifier.height(24.dp))

            FeatureButton(
                label = "紧急求助",
                description = "一键联系紧急联系人",
                onClick = onSosClick,
                containerColor = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(48.dp))

            // 状态提示
            Text(
                text = "轻触按钮获取语音提示",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    contentDescription = "提示：轻触按钮获取语音提示"
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 设置按钮
            OutlinedButton(
                onClick = { showSettings = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .semantics {
                        contentDescription = "设置按钮，打开应用设置页面"
                    },
                shape = RoundedCornerShape(16.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "设置",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 社区按钮
            OutlinedButton(
                onClick = { showCommunity = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .semantics {
                        contentDescription = "社区互助按钮，寻找志愿者陪伴出行"
                    },
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    contentColor = MaterialTheme.colorScheme.tertiary
                )
            ) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "社区互助",
                    style = MaterialTheme.typography.titleMedium
                )
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
            .height(80.dp)
            .semantics {
                contentDescription = combinedDescription
            },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Column {
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
