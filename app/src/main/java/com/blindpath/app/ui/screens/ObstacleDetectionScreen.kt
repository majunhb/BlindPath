package com.blindpath.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 障碍物检测界面 - 模拟版本
 * 包含相机预览区域和障碍物检测结果显示
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ObstacleDetectionScreen(
    onBackClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isDetecting by remember { mutableStateOf(false) }
    var detectedObstacles by remember { mutableStateOf<List<DetectedObstacle>>(emptyList()) }
    var lastAnnouncement by remember { mutableStateOf("正在启动相机...") }

    // 模拟障碍物检测
    LaunchedEffect(isDetecting) {
        if (isDetecting) {
            lastAnnouncement = "相机已启动，正在检测前方障碍物"
            while (isDetecting) {
                delay(3000) // 每3秒检测一次
                detectedObstacles = generateMockObstacles()
                if (detectedObstacles.isNotEmpty()) {
                    val obstacle = detectedObstacles.first()
                    lastAnnouncement = "检测到${obstacle.name}，距离${obstacle.distance}，${obstacle.direction}"
                } else {
                    lastAnnouncement = "前方道路畅通，未发现障碍物"
                }
            }
        } else {
            detectedObstacles = emptyList()
            lastAnnouncement = "检测已停止"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("障碍物检测") },
                navigationIcon = {
                    IconButton(onClick = {
                        isDetecting = false
                        onBackClick()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    Button(
                        onClick = { isDetecting = !isDetecting },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isDetecting) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
                        )
                    ) {
                        Text(if (isDetecting) "停止检测" else "开始检测")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 相机预览区域（模拟）
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color.Black)
                    .semantics {
                        contentDescription = "相机预览区域，显示前方实景画面"
                    },
                contentAlignment = Alignment.Center
            ) {
                if (isDetecting) {
                    // 模拟相机画面
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "📷 相机预览",
                            color = Color.White,
                            fontSize = 24.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "实时采集前方画面...",
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        // 模拟检测框
                        Box(
                            modifier = Modifier
                                .size(200.dp)
                                .background(Color.Green.copy(alpha = 0.2f))
                        ) {
                            Text(
                                text = "检测区域",
                                color = Color.Green,
                                modifier = Modifier.align(Alignment.Center)
                            )
                        }
                    }
                } else {
                    Text(
                        text = "点击开始检测按钮\n启动相机",
                        color = Color.White,
                        fontSize = 18.sp
                    )
                }
            }

            // 检测结果区域
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surface)
                    .padding(16.dp)
            ) {
                // 语音播报状态
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp)
                    ) {
                        Text(
                            text = "🎙️ 语音播报",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = lastAnnouncement,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 检测到的障碍物列表
                Text(
                    text = "检测到的障碍物 (${detectedObstacles.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (detectedObstacles.isEmpty()) {
                    Text(
                        text = if (isDetecting) "正在扫描..." else "未开始检测",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Column {
                        detectedObstacles.forEach { obstacle ->
                            ObstacleItem(obstacle)
                            Spacer(modifier = Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ObstacleItem(obstacle: DetectedObstacle) {
    val dangerColor = when (obstacle.dangerLevel) {
        3 -> Color.Red
        2 -> Color(0xFFFFA500) // Orange
        else -> Color.Yellow
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = dangerColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Warning,
                contentDescription = null,
                tint = dangerColor,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = obstacle.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "距离: ${obstacle.distance} | 方位: ${obstacle.direction}",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            // 危险等级标签
            Box(
                modifier = Modifier
                    .background(dangerColor, RoundedCornerShape(4.dp))
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "${obstacle.dangerLevel}级",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}

// 模拟障碍物数据类
data class DetectedObstacle(
    val name: String,
    val distance: String,
    val direction: String,
    val dangerLevel: Int // 1-3, 3为最高危险
)

// 生成模拟障碍物
private fun generateMockObstacles(): List<DetectedObstacle> {
    val obstacles = listOf(
        DetectedObstacle("前方行人", "3米", "正前方", 2),
        DetectedObstacle("路沿台阶", "1.5米", "右前方", 3),
        DetectedObstacle("停放自行车", "2米", "左侧", 1),
        DetectedObstacle("电线杆", "4米", "正前方", 1),
        DetectedObstacle("施工围挡", "5米", "右侧", 2)
    )
    // 随机返回1-3个障碍物
    return obstacles.shuffled().take((1..3).random())
}
