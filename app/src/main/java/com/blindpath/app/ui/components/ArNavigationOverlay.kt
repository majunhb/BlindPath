package com.blindpath.app.ui.components

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.ObstacleType
import kotlin.math.abs

/**
 * 视障人士 AR 导航叠加层
 *
 * 对应 PRD 模块一：实景智能识别
 * - 相机预览作为背景
 * - 障碍物彩色包围框叠加
 * - 高对比度预警文字
 * - 导航方向指示
 *
 * 核心理念：不做花哨的界面，只做保命的功能
 */
@Composable
fun ArNavigationOverlay(
    modifier: Modifier = Modifier,
    onFrameProcessed: (Bitmap) -> Unit = {},
    obstacles: List<DetectedObstacle> = emptyList(),
    dangerLevel: DangerLevel = DangerLevel.LOW,
    warningText: String = "",
    navigationDirection: String = "",
    remainingDistance: String = "",
    isActive: Boolean = true,
    onGestureTap: () -> Unit = {},
    onGestureDoubleTap: () -> Unit = {},
    onGestureLongPress: () -> Unit = {},
    // Phase 2: 盲道叠加层
    pavingOffset: Float = 0f,       // 盲道偏离 [-1, 1]，0=在中心
    pavingDirection: Float = 0f,    // 盲道走向角度（弧度）
    pavingVisible: Boolean = false, // 是否显示盲道叠加层
) {
    // 危险等级对应的颜色
    val dangerColor by animateColorAsState(
        targetValue = when (dangerLevel) {
            DangerLevel.CRITICAL -> Color(0xFFF44336)
            DangerLevel.HIGH -> Color(0xFFFF9800)
            DangerLevel.MEDIUM -> Color(0xFFFFC107)
            DangerLevel.LOW -> Color(0xFF2196F3)
        },
        animationSpec = tween(300)
    )

    // 紧急闪烁动画
    val infiniteTransition = rememberInfiniteTransition()
    val flashAlpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        )
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .semantics { contentDescription = "AR实景导航画面" }
    ) {
        // ============================================================
        // 第1层：背景（由父组件提供 CameraX PreviewView）
        // ============================================================

        // ============================================================
        // 第2层：障碍物包围框绘制
        // ============================================================
        if (obstacles.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        obstacles.forEach { obstacle ->
                            val box = obstacle.boundingBox
                            if (box != null) {
                                val left = box.left * size.width
                                val top = box.top * size.height
                                val boxWidth = box.width * size.width
                                val boxHeight = box.height * size.height

                                val boxColor = when (obstacle.type.severity) {
                                    3 -> Color.Red
                                    2 -> Color(0xFFFFA500)
                                    else -> Color.Yellow
                                }

                                // 绘制包围框
                                drawRect(
                                    color = boxColor.copy(alpha = 0.6f),
                                    topLeft = Offset(left, top),
                                    size = Size(boxWidth, boxHeight),
                                    style = Stroke(width = 3f)
                                )

                                // 绘制标签背景
                                val label = "${obstacle.type.chineseName} ${obstacle.distance.toInt()}m"
                                drawContext.canvas.nativeCanvas.apply {
                                    val paint = android.graphics.Paint().apply {
                                        this.color = boxColor.toArgb()
                                        alpha = 200
                                        textSize = 40f
                                        isAntiAlias = true
                                    }
                                    val textWidth = paint.measureText(label)
                                    drawRoundRect(
                                        left - 2f, top - 45f,
                                        left + textWidth + 10f, top - 5f,
                                        8f, 8f,
                                        android.graphics.Paint().apply {
                                            this.color = boxColor.toArgb()
                                            alpha = 180
                                        }
                                    )
                                    paint.color = android.graphics.Color.WHITE
                                    drawText(label, left + 3f, top - 15f, paint)
                                }
                            }
                        }
                    }
            )
        }

        // ============================================================
        // 第3层：盲道叠加层（绿色导向线 + 偏离指示器）
        // ============================================================
        if (pavingVisible) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        val centerX = size.width / 2f
                        val bottomY = size.height * 0.85f
                        val topY = size.height * 0.35f

                        // 盲道中心线（绿色虚线）
                        val offsetX = centerX + (pavingOffset * centerX * 0.5f)
                        val lineColor = when {
                            abs(pavingOffset) < 0.2f -> Color.Green // 在盲道上
                            abs(pavingOffset) < 0.4f -> Color.Yellow // 略有偏离
                            else -> Color.Red // 严重偏离
                        }

                        // 绘制盲道导向线
                        for (i in 0 until 10) {
                            val segmentStart = topY + (bottomY - topY) * i / 10f
                            val segmentEnd = topY + (bottomY - topY) * (i + 0.5f) / 10f
                            drawLine(
                                color = lineColor.copy(alpha = 0.8f),
                                start = Offset(offsetX, segmentStart),
                                end = Offset(offsetX, segmentEnd),
                                strokeWidth = 4f
                            )
                        }

                        // 绘制偏离指示器（底部圆点）
                        val indicatorY = size.height * 0.88f
                        drawCircle(
                            color = lineColor.copy(alpha = 0.9f),
                            radius = 12f,
                            center = Offset(offsetX, indicatorY)
                        )
                        // 外圈
                        drawCircle(
                            color = Color.White.copy(alpha = 0.6f),
                            radius = 16f,
                            center = Offset(offsetX, indicatorY),
                            style = Stroke(width = 2f)
                        )

                        // 画面中心参考线（半透明灰色，帮助用户对齐）
                        drawLine(
                            color = Color.Gray.copy(alpha = 0.15f),
                            start = Offset(centerX, topY),
                            end = Offset(centerX, bottomY),
                            strokeWidth = 1f
                        )
                    }
            )
        }

        // ============================================================
        // 第4层：顶部预警条（高对比度，黑底 + 彩色文字）
        // ============================================================
        if (warningText.isNotEmpty()) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 48.dp, start = 16.dp, end = 16.dp)
                    .background(
                        Color.Black.copy(alpha = 0.85f),
                        RoundedCornerShape(12.dp)
                    )
                    .border(
                        width = 2.dp,
                        color = dangerColor.copy(
                            alpha = if (dangerLevel == DangerLevel.CRITICAL) flashAlpha else 0.8f
                        ),
                        shape = RoundedCornerShape(12.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 14.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    if (dangerLevel == DangerLevel.CRITICAL || dangerLevel == DangerLevel.HIGH) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = dangerColor,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    Text(
                        text = warningText,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }

        // ============================================================
        // 第5层：底部导航信息栏（高对比度）
        // ============================================================
        if (navigationDirection.isNotEmpty() || remainingDistance.isNotEmpty()) {
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 32.dp, start = 16.dp, end = 16.dp)
                    .fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // 方向指示
                if (navigationDirection.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .background(
                                Color(0xFF1976D2).copy(alpha = 0.9f),
                                RoundedCornerShape(16.dp)
                            )
                            .padding(horizontal = 24.dp, vertical = 12.dp)
                    ) {
                        Text(
                            text = navigationDirection,
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 24.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 剩余距离
                if (remainingDistance.isNotEmpty()) {
                    Box(
                        modifier = Modifier
                            .background(
                                Color.Black.copy(alpha = 0.75f),
                                RoundedCornerShape(12.dp)
                            )
                            .padding(horizontal = 20.dp, vertical = 8.dp)
                    ) {
                        Text(
                            text = remainingDistance,
                            color = Color.White,
                            fontWeight = FontWeight.Medium,
                            fontSize = 18.sp
                        )
                    }
                }
            }
        }

        // ============================================================
        // 第6层：状态指示器（右上角）
        // ============================================================
        if (isActive) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(top = 48.dp, end = 16.dp)
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        when (dangerLevel) {
                            DangerLevel.CRITICAL -> Color.Red.copy(alpha = flashAlpha)
                            DangerLevel.HIGH -> Color(0xFFFF9800)
                            DangerLevel.MEDIUM -> Color(0xFFFFC107)
                            DangerLevel.LOW -> Color(0xFF4CAF50)
                        }
                    )
            )
        }
    }
}

/**
 * 危险等级枚举
 */
enum class DangerLevel(val label: String, val color: Color) {
    LOW("低风险", Color(0xFF2196F3)),
    MEDIUM("中风险", Color(0xFFFFC107)),
    HIGH("高风险", Color(0xFFFF9800)),
    CRITICAL("紧急风险", Color(0xFFF44336))
}