package com.blindpath.app.ui.screens

import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.People
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.blindpath.app.ui.theme.BlindPathTheme
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.BoundingBox
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_settings.ui.SettingsScreen
import com.blindpath.module_community.ui.CommunityScreen
import timber.log.Timber
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
                onPreviewReady = { previewView ->
                    // 当 PreviewView 准备好后，设置 SurfaceProvider
                    obstacleRepository.setPreviewSurfaceProvider(previewView.surfaceProvider)
                    Timber.d("Camera preview SurfaceProvider set")
                }
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
    onPreviewReady: (PreviewView) -> Unit
) {
    val context = LocalContext.current

    AndroidView(
        factory = { ctx ->
            PreviewView(ctx).apply {
                scaleType = PreviewView.ScaleType.FIT_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }
        },
        modifier = modifier,
        update = { previewView ->
            onPreviewReady(previewView)
        }
    )
}

/**
 * 检测框组件 - 将归一化坐标转换为实际像素并绘制
 */
@Composable
fun DetectionBox(
    boundingBox: BoundingBox,
    obstacleType: String,
    confidence: Float,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val labelText = "$obstacleType ${(confidence * 100).toInt()}%"

    Canvas(modifier = modifier) {
        val canvasWidth = size.width
        val canvasHeight = size.height

        // 将归一化坐标(0-1)转换为实际像素坐标
        val left = boundingBox.x1 * canvasWidth
        val top = boundingBox.y1 * canvasHeight
        val right = boundingBox.x2 * canvasWidth
        val bottom = boundingBox.y2 * canvasHeight

        val rectWidth = right - left
        val rectHeight = bottom - top

        // 绘制检测框（红色边框）
        drawRect(
            color = Color.Red,
            topLeft = Offset(left, top),
            size = Size(rectWidth, rectHeight),
            style = Stroke(width = 3.dp.toPx())
        )

        // 测量文本尺寸
        val textLayoutResult = textMeasurer.measure(
            text = androidx.compose.ui.text.AnnotatedString(labelText),
            style = TextStyle(
                color = Color.White,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )
        )

        // 绘制标签背景
        drawRect(
            color = Color.Red.copy(alpha = 0.7f),
            topLeft = Offset(left, top - textLayoutResult.size.height - 4.dp.toPx()),
            size = Size(
                textLayoutResult.size.width + 8.dp.toPx(),
                textLayoutResult.size.height + 4.dp.toPx()
            )
        )

        // 绘制标签文本
        drawText(
            textLayoutResult = textLayoutResult,
            topLeft = Offset(
                left + 4.dp.toPx(),
                top - textLayoutResult.size.height - 2.dp.toPx()
            )
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
