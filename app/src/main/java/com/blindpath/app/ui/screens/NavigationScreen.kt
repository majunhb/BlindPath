package com.blindpath.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 智能导航界面 - 模拟版本
 * 支持输入目的地、路线规划、语音导航
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    onBackClick: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var destination by remember { mutableStateOf("") }
    var isNavigating by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf(0) }
    var routeSteps by remember { mutableStateOf<List<NavigationStep>>(emptyList()) }
    var announcement by remember { mutableStateOf("请输入目的地开始导航") }

    fun startNavigation() {
        if (destination.isBlank()) return
        scope.launch {
            announcement = "正在规划路线..."
            delay(1500)
            routeSteps = generateMockRoute()
            isNavigating = true
            currentStep = 0
            announcement = "路线规划完成，全程${routeSteps.size}个步骤，预计15分钟到达${destination}"
        }
    }

    fun nextStep() {
        if (currentStep < routeSteps.size - 1) {
            currentStep++
            val step = routeSteps[currentStep]
            announcement = "${step.instruction}，距离${step.distance}"
        } else {
            announcement = "已到达目的地${destination}，导航结束"
            isNavigating = false
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("智能导航") },
                navigationIcon = {
                    IconButton(onClick = {
                        isNavigating = false
                        onBackClick()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
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
            if (!isNavigating) {
                // 目的地输入
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        Text(
                            text = "请输入目的地",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        OutlinedTextField(
                            value = destination,
                            onValueChange = { destination = it },
                            label = { Text("目的地") },
                            placeholder = { Text("例如：天安门、故宫、地铁站") },
                            modifier = Modifier
                                .fillMaxWidth()
                                .semantics {
                                    contentDescription = "目的地输入框"
                                },
                            keyboardOptions = KeyboardOptions(
                                imeAction = ImeAction.Done,
                                keyboardType = KeyboardType.Text
                            ),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Button(
                            onClick = { startNavigation() },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(56.dp),
                            enabled = destination.isNotBlank()
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "开始导航",
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // 常用目的地
                        Text(
                            text = "常用目的地：",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            listOf("家", "公司", "地铁站", "医院").forEach { preset ->
                                AssistChip(
                                    onClick = { destination = preset },
                                    label = { Text(preset) }
                                )
                            }
                        }
                    }
                }
            } else {
                // 导航中界面
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp)
                    ) {
                        // 当前导航指令
                        Row(
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Navigation,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(48.dp)
                            )
                            Spacer(modifier = Modifier.width(16.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "第 ${currentStep + 1}/${routeSteps.size} 步",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = routeSteps.getOrNull(currentStep)?.instruction ?: "已到达",
                                    style = MaterialTheme.typography.headlineSmall,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 距离和预计时间
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            routeSteps.getOrNull(currentStep)?.let { step ->
                                NavigationInfoItem("剩余距离", step.distance)
                                NavigationInfoItem("预计时间", step.duration)
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 语音播报
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
                            text = "🎙️ 导航播报",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = announcement,
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 导航控制按钮
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    Button(
                        onClick = { nextStep() },
                        modifier = Modifier.weight(1f),
                        enabled = currentStep < routeSteps.size
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("下一步")
                    }

                    OutlinedButton(
                        onClick = {
                            isNavigating = false
                            announcement = "导航已取消"
                        },
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("结束导航")
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 路线步骤列表
                Text(
                    text = "完整路线",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Column {
                    routeSteps.forEachIndexed { index, step ->
                        RouteStepItem(
                            step = step,
                            isCurrent = index == currentStep,
                            isCompleted = index < currentStep
                        )
                        if (index < routeSteps.size - 1) {
                            Divider(modifier = Modifier.padding(start = 24.dp, end = 24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavigationInfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun RouteStepItem(
    step: NavigationStep,
    isCurrent: Boolean,
    isCompleted: Boolean
) {
    val backgroundColor = when {
        isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f)
        isCompleted -> Color.Gray.copy(alpha = 0.1f)
        else -> MaterialTheme.colorScheme.surface
    }

    val iconTint = when {
        isCurrent -> MaterialTheme.colorScheme.primary
        isCompleted -> Color.Gray
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(backgroundColor)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = Icons.Default.LocationOn,
            contentDescription = null,
            tint = iconTint,
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = step.instruction,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = "${step.distance} · ${step.duration}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .background(
                        MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(4.dp)
                    )
                    .padding(horizontal = 8.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "当前",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        } else if (isCompleted) {
            Text(
                text = "✓",
                color = Color.Green,
                fontSize = 20.sp
            )
        }
    }
}

// 导航步骤数据类
data class NavigationStep(
    val instruction: String,
    val distance: String,
    val duration: String,
    val type: StepType
)

enum class StepType {
    START, STRAIGHT, TURN_LEFT, TURN_RIGHT, CROSSWALK, DESTINATION
}

// 生成模拟路线
private fun generateMockRoute(): List<NavigationStep> {
    return listOf(
        NavigationStep("从当前位置出发，面向东北方向", "0米", "0分钟", StepType.START),
        NavigationStep("直行，沿中山路向前走", "200米", "3分钟", StepType.STRAIGHT),
        NavigationStep("前方路口，左转进入解放大道", "50米", "1分钟", StepType.TURN_LEFT),
        NavigationStep("直行，经过公交站", "300米", "4分钟", StepType.STRAIGHT),
        NavigationStep("前方斑马线，注意来往车辆", "80米", "1分钟", StepType.CROSSWALK),
        NavigationStep("右转进入人民路", "30米", "1分钟", StepType.TURN_RIGHT),
        NavigationStep("直行，目的地在前方右侧", "150米", "2分钟", StepType.STRAIGHT),
        NavigationStep("到达目的地", "0米", "0分钟", StepType.DESTINATION)
    )
}
