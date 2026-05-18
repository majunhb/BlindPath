package com.blindpath.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
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
 * 实时定位界面 - 模拟版本
 * 显示当前位置、道路信息、方位等
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    onBackClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var isLocating by remember { mutableStateOf(false) }
    var locationInfo by remember { mutableStateOf<LocationInfo?>(null) }
    var lastAnnouncement by remember { mutableStateOf("点击开始定位按钮获取位置信息") }

    // 模拟定位
    fun startLocation() {
        scope.launch {
            isLocating = true
            lastAnnouncement = "正在获取位置信息，请稍候..."
            delay(2000) // 模拟定位耗时
            locationInfo = generateMockLocation()
            lastAnnouncement = "定位成功，当前位于${locationInfo?.road}，朝向${locationInfo?.direction}"
            isLocating = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实时定位") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { startLocation() },
                        enabled = !isLocating
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新定位")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 定位按钮
            if (locationInfo == null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = { startLocation() },
                            enabled = !isLocating,
                            modifier = Modifier
                                .size(200.dp)
                                .semantics {
                                    contentDescription = "开始定位按钮，获取当前位置信息"
                                },
                            shape = RoundedCornerShape(100.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = if (isLocating) "定位中..." else "开始定位",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            } else {
                // 显示定位信息
                locationInfo?.let { info ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(20.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = "当前位置",
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Spacer(modifier = Modifier.height(16.dp))

                            LocationInfoRow("所在道路", info.road)
                            LocationInfoRow("行进方位", info.direction)
                            LocationInfoRow("当前坐标", info.coordinates)
                            LocationInfoRow("定位精度", info.accuracy)
                            LocationInfoRow("周边地标", info.landmarks)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 语音播报区域
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp)
                        ) {
                            Text(
                                text = "🎙️ 位置播报",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "您当前位于${info.road}，面向${info.direction}。前方${info.landmarks}。",
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 定位状态
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.Green.copy(alpha = 0.1f)
                        )
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(Color.Green, RoundedCornerShape(100.dp))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "定位正常 | 北斗+GPS双模定位",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            // 底部提示
            Text(
                text = "提示：定位功能需要开启位置权限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun LocationInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
    ) {
        Text(
            text = "$label：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium
        )
    }
}

// 位置信息数据类
data class LocationInfo(
    val road: String,
    val direction: String,
    val coordinates: String,
    val accuracy: String,
    val landmarks: String
)

// 生成模拟位置信息
private fun generateMockLocation(): LocationInfo {
    val roads = listOf("中山路", "解放大道", "人民路", "建设大街", "和平路")
    val directions = listOf("东北方向", "东南方向", "正东", "正西", "正南", "正北")
    val landmarks = listOf(
        "100米处有地铁站",
        "左侧有便利店",
        "前方有十字路口",
        "右侧有公交站",
        "前方200米处有商场"
    )

    return LocationInfo(
        road = roads.random(),
        direction = directions.random(),
        coordinates = "${(39..40).random()}.${(1000..9999).random()}°N, ${(116..117).random()}.${(1000..9999).random()}°E",
        accuracy = "±${(3..15).random()}米",
        landmarks = landmarks.random()
    )
}
