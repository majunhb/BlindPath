package com.blindpath.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.blindpath.app.ui.theme.DangerColor
import com.blindpath.app.ui.theme.SafeColor
import com.blindpath.app.ui.theme.WarningColor
import com.blindpath.base.common.AlertLevel
import com.blindpath.base.common.NavigationInfo
import com.blindpath.base.common.ObstacleAlert

@Composable
fun MainScreen(
    viewModel: MainViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 状态显示（供明眼人辅助调试用）
            Text(
                text = "智行助盲",
                style = MaterialTheme.typography.displayMedium,
                color = MaterialTheme.colorScheme.primary
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 系统状态卡片
            StatusCard(uiState = uiState)

            Spacer(modifier = Modifier.height(32.dp))

            // 操作按钮（供明眼人辅助设置用）
            ActionButtons(
                onStartObstacle = viewModel::startObstacleDetection,
                onStopObstacle = viewModel::stopObstacleDetection,
                onStartNavigation = viewModel::startNavigation,
                onStopNavigation = viewModel::stopNavigation,
                onTestVoice = viewModel::testVoice,
                isObstacleRunning = uiState.isObstacleRunning,
                isNavigationRunning = uiState.isNavigationRunning
            )

            Spacer(modifier = Modifier.height(32.dp))

            // 避障预警显示
            uiState.currentAlert?.let { alert ->
                AlertDisplay(alert = alert)
            }

            // 导航状态显示
            uiState.navigationInfo?.let { info ->
                NavigationDisplay(info = info)
            }

            // 语音指令
            Text(
                text = "语音唤醒词：\"小行小行\"",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
private fun StatusCard(uiState: MainUiState) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                StatusItem("避障", uiState.isObstacleRunning, SafeColor, DangerColor)
                StatusItem("导航", uiState.isNavigationRunning, SafeColor, DangerColor)
                StatusItem("定位", uiState.isLocationAvailable, SafeColor, WarningColor)
                StatusItem("语音", uiState.isVoiceAvailable, SafeColor, WarningColor)
            }

            if (uiState.errorMessage != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "错误: ${uiState.errorMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = DangerColor
                )
            }
        }
    }
}

@Composable
private fun StatusItem(
    label: String,
    isActive: Boolean,
    activeColor: androidx.compose.ui.graphics.Color,
    inactiveColor: androidx.compose.ui.graphics.Color
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = if (isActive) "●" else "○",
            style = MaterialTheme.typography.headlineMedium,
            color = if (isActive) activeColor else inactiveColor
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun ActionButtons(
    onStartObstacle: () -> Unit,
    onStopObstacle: () -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    onTestVoice: () -> Unit,
    isObstacleRunning: Boolean,
    isNavigationRunning: Boolean
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 避障控制
        Button(
            onClick = if (isObstacleRunning) onStopObstacle else onStartObstacle,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isObstacleRunning) DangerColor else SafeColor
            )
        ) {
            Text(
                text = if (isObstacleRunning) "停止避障" else "开启避障",
                style = MaterialTheme.typography.titleMedium
            )
        }

        // 导航控制
        Button(
            onClick = if (isNavigationRunning) onStopNavigation else onStartNavigation,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isNavigationRunning) DangerColor else MaterialTheme.colorScheme.primary
            )
        ) {
            Text(
                text = if (isNavigationRunning) "停止导航" else "开启导航",
                style = MaterialTheme.typography.titleMedium
            )
        }

        // 测试语音
        OutlinedButton(
            onClick = onTestVoice,
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp)
        ) {
            Text("测试语音", style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun AlertDisplay(alert: ObstacleAlert) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when (alert.level) {
                AlertLevel.DANGER -> DangerColor.copy(alpha = 0.2f)
                AlertLevel.WARNING -> WarningColor.copy(alpha = 0.2f)
                AlertLevel.SAFE -> SafeColor.copy(alpha = 0.2f)
            }
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "⚠ ${alert.level.displayName}",
                style = MaterialTheme.typography.headlineMedium,
                color = when (alert.level) {
                    AlertLevel.DANGER -> DangerColor
                    AlertLevel.WARNING -> WarningColor
                    AlertLevel.SAFE -> SafeColor
                }
            )
            Text(
                text = alert.description,
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = "距离: ${alert.distance}m",
                style = MaterialTheme.typography.titleMedium
            )
        }
    }
}

@Composable
private fun NavigationDisplay(info: NavigationInfo) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "📍 ${info.instruction}",
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center
            )
            Text(
                text = "距离: ${info.remainingDistance}m | 预计${info.remainingTime}秒",
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }
}
