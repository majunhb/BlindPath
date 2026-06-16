package com.blindpath.app.ui.components

import android.graphics.Bitmap
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.blindpath.base.common.AlertLevel
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.ObstacleType
import timber.log.Timber
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

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
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    val frameChannel = remember { Channel<Bitmap>(Channel.CONFLATED) }

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
        // 第1层：CameraX 实时预览（背景）
        // ============================================================
        AndroidView(
            factory = { ctx ->
                PreviewView(ctx).apply {
                    scaleType = PreviewView.ScaleType.FILL_CENTER
                    implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                }
            },
            modifier = Modifier.fillMaxSize(),
            update = { previewView ->
                if (!isActive) return@AndroidView

                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    try {
                        // Preview
                        val preview = Preview.Builder().build().also {
                            it.setSurfaceProvider(previewView.surfaceProvider)
                        }

                        // ImageAnalysis
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetResolution(android.util.Size(480, 640))
                            .build()
                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            val bitmap = imageProxyToBitmap(imageProxy)
                            if (bitmap != null) {
                                frameChannel.trySend(bitmap)
                            }
                            imageProxy.close()
                        }

                        provider.unbindAll()
                        provider.bindToLifecycle(
                            lifecycleOwner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            preview,
                            imageAnalysis
                        )
                    } catch (e: Exception) {
                        Timber.w(e, "ArNav: CameraX bind failed")
                    }
                }, ContextCompat.getMainExecutor(context))
            }
        )

        // 处理帧回调
        LaunchedEffect(isActive) {
            if (!isActive) return@LaunchedEffect
            var frameCount = 0
            while (!frameChannel.isClosedForReceive) {
                val bitmap = frameChannel.receiveCatching().getOrNull() ?: continue
                frameCount++
                if (frameCount % 5 == 0) {
                    onFrameProcessed(bitmap)
                } else {
                    bitmap.recycle()
                }
            }
        }

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
                                val left = box.x * size.width
                                val top = box.y * size.height
                                val boxWidth = box.width * size.width
                                val boxHeight = box.height * size.height

                                val color = when (obstacle.type.severity) {
                                    3 -> Color.Red
                                    2 -> Color(0xFFFFA500)
                                    else -> Color.Yellow
                                }

                                // 绘制包围框
                                drawRect(
                                    color = color.copy(alpha = 0.6f),
                                    topLeft = Offset(left, top),
                                    size = Size(boxWidth, boxHeight),
                                    style = Stroke(width = 3f)
                                )

                                // 绘制标签背景
                                val label = "${obstacle.type.chineseName} ${obstacle.distanceMeters}m"
                                drawContext.canvas.nativeCanvas.apply {
                                    val paint = android.graphics.Paint().apply {
                                        color = color.toArgb()
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
                                            color = color.toArgb()
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
        // 第3层：顶部预警条（高对比度，黑底 + 彩色文字）
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
        // 第4层：底部导航信息栏（高对比度）
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
        // 第5层：状态指示器（右上角）
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

    // CameraX 生命周期管理
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) {
                cameraExecutor.shutdownNow()
                frameChannel.close()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            cameraExecutor.shutdownNow()
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

/**
 * 将 ImageProxy 转换为 ARGB_8888 Bitmap
 */
private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    return try {
        val yuvImage = android.graphics.YuvImage(
            imageProxy.planes[0].buffer.array(),
            android.graphics.ImageFormat.NV21,
            imageProxy.width,
            imageProxy.height,
            null
        )
        val out = java.io.ByteArrayOutputStream()
        yuvImage.compressToJpeg(android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height), 80, out)
        val jpegBytes = out.toByteArray()
        android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
    } catch (e: Exception) {
        Timber.w(e, "ArNav: imageProxyToBitmap failed")
        null
    }
}