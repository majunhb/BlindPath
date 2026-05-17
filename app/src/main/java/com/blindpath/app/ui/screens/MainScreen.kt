package com.blindpath.app.ui.screens

import android.content.Context
import android.view.SurfaceView
import android.view.TextureView
import android.widget.FrameLayout
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessLifecycleOwner
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.blindpath.app.ui.theme.BlindPathTheme
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.BoundingBox
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_settings.ui.SettingsScreen
import com.blindpath.module_community.ui.CommunityScreen
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

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
    var isDetecting by remember { mutableStateOf(false) }

    when {
        showSettings -> {
            SettingsScreen(onBackClick = { showSettings = false })
        }
        showCommunity -> {
            CommunityScreen(onBackClick = { showCommunity = false })
        }
        else -> {
            MainContent(
                obstacleRepository = obstacleRepository,
                isDetecting = isDetecting,
                onObstacleDetectionClick = {
                    isDetecting = true
                    onObstacleDetectionClick()
                },
                onLocationClick = onLocationClick,
                onSosClick = onSosClick,
                onSettingsClick = { showSettings = true },
                onCommunityClick = { showCommunity = true },
                onStopDetection = {
                    isDetecting = false
                }
            )
        }
    }
}

@Composable
private fun MainContent(
    obstacleRepository: ObstacleRepository,
    isDetecting: Boolean,
    onObstacleDetectionClick: () -> Unit,
    onLocationClick: () -> Unit,
    onSosClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCommunityClick: () -> Unit,
    onStopDetection: () -> Unit
) {
    val obstacleState by obstacleRepository.obstacleState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        if (isDetecting) {
            // 1. 相机预览
            CameraPreview(
                modifier = Modifier.fillMaxSize(),
                cameraRepository = obstacleRepository as? android x.camera.core.Camera ?: return@Box
            )

            // 2. 检测框叠加层
            obstacleState.detectedObstacles?.forEach { obstacle ->
                DetectionBox(
                    boundingBox = obstacle.boundingBox,
                    obstacleType = obstacle.type.chineseName,
                    confidence = obstacle.confidence,
                    modifier = Modifier.fillMaxSize()
                )
            }

            // 3. 停止检测按钮
            Button(
                onClick = onStopDetection,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.error
                )
            ) {
                Text("停止检测")
            }
        }

        // 4. 主控制按钮（非检测模式）or 半透明悬浮（检测模式）
        if (!isDetecting) {
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

                // 设置按钮
                OutlinedButton(
                    onClick = onSettingsClick,
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
                    onClick = onCommunityClick,
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
}

/**
 * 相机预览组件
 */
@Composable
fun CameraPreview(
    modifier: Modifier = Modifier,
    cameraRepository: Any? = null
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                // 绑定到CameraX
                // 注意：实际的CameraX绑定应该在Repository中完成
                // 这里只显示预览
            }
        },
        modifier = modifier,
        update = { view ->
            // 更新预览（如果有新的相机提供者）
        }
    )
}

/**
 * 检测框组件
 */
@Composable
fun DetectionBox(
    boundingBox: BoundingBox,
    obstacleType: String,
    confidence: Float,
    modifier: Modifier = Modifier
) {
    // 注意：BoundingBox的值是0-1标准化坐标，需要转换为实际像素
    // 这里简化为显示文本标签
    Box(
        modifier = modifier
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = "$obstacleType ${(confidence * 100).toInt()}%",
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier
                .background(Color.Red.copy(alpha = 0.7f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
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
