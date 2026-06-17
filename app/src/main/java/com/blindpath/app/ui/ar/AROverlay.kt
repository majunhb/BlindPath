package com.blindpath.app.ui.ar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.unit.dp
import kotlin.math.*

/**
 * AR实景叠加层 - 在摄像头画面上绘制导航元素
 * 
 * 功能：
 * 1. 导航箭头引导 - 在画面中央或前方显示转向箭头
 * 2. 障碍物标注 - 在检测到的障碍物位置显示警示框
 * 3. 盲道状态指示 - 显示用户是否在盲道上
 * 4. 危险区域标注 - 标注需要特别注意的区域
 */
@Composable
fun AROverlay(
    modifier: Modifier = Modifier,
    // 导航状态
    navigationState: ARNavigationState,
    // 障碍物列表（相对于屏幕坐标 0-1）
    obstacles: List<ARObstacle>,
    // 盲道状态
    isOnSidewalk: Boolean,
    // 危险等级
    dangerLevel: ARDangerLevel
) {
    Canvas(modifier = modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        
        // 1. 绘制中心引导区域
        drawCenterGuide(
            width = width,
            height = height,
            isOnSidewalk = isOnSidewalk,
            dangerLevel = dangerLevel
        )
        
        // 2. 绘制导航箭头
        navigationState.nextDirection?.let { direction ->
            drawNavigationArrow(
                direction = direction,
                distance = navigationState.distanceToNextTurn,
                width = width,
                height = height
            )
        }
        
        // 3. 绘制障碍物标注
        obstacles.forEach { obstacle ->
            drawObstacleMarker(
                obstacle = obstacle,
                width = width,
                height = height
            )
        }
        
        // 4. 绘制AR导航线
        if (navigationState.isNavigating) {
            drawARNavigationLine(
                width = width,
                height = height,
                routeAngle = navigationState.routeAngle
            )
        }
        
        // 5. 绘制危险区域高亮
        if (dangerLevel != ARDangerLevel.LOW) {
            drawDangerOverlay(
                width = width,
                height = height,
                dangerLevel = dangerLevel
            )
        }
    }
}

/** AR导航状态 */
data class ARNavigationState(
    val isNavigating: Boolean = false,
    val nextDirection: ARDirection? = null,
    val distanceToNextTurn: Float = 0f,
    val routeAngle: Float = 0f,
    val currentRoadName: String = "",
    val nextRoadName: String = ""
)

/** AR方向 */
enum class ARDirection(val label: String, val angle: Float) {
    STRAIGHT("直行", 0f),
    SLIGHT_LEFT("偏左", -30f),
    LEFT("左转", -60f),
    SHARP_LEFT("向左前方", -90f),
    SLIGHT_RIGHT("偏右", 30f),
    RIGHT("右转", 60f),
    SHARP_RIGHT("向右前方", 90f),
    U_TURN("掉头", 180f),
    ARRIVE("到达", 0f)
}

/** AR障碍物 */
data class ARObstacle(
    val screenX: Float,
    val screenY: Float,
    val distance: Float,
    val type: String,
    val dangerLevel: ARDangerLevel
)

/** 危险等级 */
enum class ARDangerLevel {
    LOW, MEDIUM, HIGH, CRITICAL
}

/** 绘制中心引导区域 */
private fun DrawScope.drawCenterGuide(
    width: Float, height: Float,
    isOnSidewalk: Boolean, dangerLevel: ARDangerLevel
) {
    val centerX = width / 2
    val centerY = height * 0.6f
    
    val guideColor = when {
        !isOnSidewalk -> Color(0xFFFF9800)
        dangerLevel == ARDangerLevel.CRITICAL -> Color(0xFFF44336)
        dangerLevel == ARDangerLevel.HIGH -> Color(0xFFFF9800)
        else -> Color(0xFF4CAF50)
    }
    
    // 椭圆形引导区
    drawOval(
        color = guideColor.copy(alpha = 0.15f),
        topLeft = Offset(centerX - width * 0.35f, centerY - height * 0.12f),
        size = Size(width * 0.7f, height * 0.24f)
    )
    drawOval(
        color = guideColor.copy(alpha = 0.6f),
        topLeft = Offset(centerX - width * 0.35f, centerY - height * 0.12f),
        size = Size(width * 0.7f, height * 0.24f),
        style = Stroke(width = 3.dp.toPx())
    )
    
    // 十字准星
    val crossSize = 20.dp.toPx()
    drawLine(Color(0xFFFF9800), Offset(centerX - crossSize, centerY), Offset(centerX + crossSize, centerY), strokeWidth = 2.dp.toPx())
    drawLine(Color(0xFFFF9800), Offset(centerX, centerY - crossSize), Offset(centerX, centerY + crossSize), strokeWidth = 2.dp.toPx())
}

/** 绘制导航箭头 */
private fun DrawScope.drawNavigationArrow(
    direction: ARDirection,
    distance: Float,
    width: Float,
    height: Float
) {
    val centerX = width / 2
    val arrowY = height * 0.35f
    val arrowSize = minOf(width, height) * 0.12f
    
    rotate(direction.angle, pivot = Offset(centerX, arrowY)) {
        val arrowPath = Path().apply {
            moveTo(centerX, arrowY - arrowSize)
            lineTo(centerX - arrowSize * 0.5f, arrowY + arrowSize * 0.4f)
            lineTo(centerX - arrowSize * 0.15f, arrowY + arrowSize * 0.4f)
            lineTo(centerX - arrowSize * 0.15f, arrowY + arrowSize * 0.7f)
            lineTo(centerX + arrowSize * 0.15f, arrowY + arrowSize * 0.7f)
            lineTo(centerX + arrowSize * 0.15f, arrowY + arrowSize * 0.4f)
            lineTo(centerX + arrowSize * 0.5f, arrowY + arrowSize * 0.4f)
            close()
        }
        
        drawPath(arrowPath, Color(0xFF2196F3).copy(alpha = 0.85f))
        drawPath(arrowPath, Color.White, style = Stroke(width = 3.dp.toPx()))
    }
}

/** 绘制障碍物标注 */
private fun DrawScope.drawObstacleMarker(
    obstacle: ARObstacle,
    width: Float,
    height: Float
) {
    val screenX = obstacle.screenX * width
    val screenY = obstacle.screenY * height
    
    val markerSize = 50.dp.toPx()
    val boxSize = markerSize * 1.8f
    
    val color = when (obstacle.dangerLevel) {
        ARDangerLevel.CRITICAL -> Color(0xFFF44336)
        ARDangerLevel.HIGH -> Color(0xFFFF9800)
        ARDangerLevel.MEDIUM -> Color(0xFFFFC107)
        ARDangerLevel.LOW -> Color(0xFF2196F3)
    }
    
    // 警示框
    drawRect(color, topLeft = Offset(screenX - boxSize/2, screenY - boxSize/2), size = Size(boxSize, boxSize), style = Stroke(width = 4.dp.toPx()))
    drawLine(color, Offset(screenX - boxSize/2, screenY - boxSize/2), Offset(screenX + boxSize/2, screenY + boxSize/2), 3.dp.toPx())
    drawLine(color, Offset(screenX + boxSize/2, screenY - boxSize/2), Offset(screenX - boxSize/2, screenY + boxSize/2), 3.dp.toPx())
    
    // 圆形背景
    drawCircle(color.copy(alpha = 0.3f), markerSize / 2, Offset(screenX, screenY))
    
    // 感叹号
    drawLine(Color.White, Offset(screenX, screenY - markerSize * 0.25f), Offset(screenX, screenY + markerSize * 0.1f), 4.dp.toPx())
    drawCircle(Color.White, 4.dp.toPx(), Offset(screenX, screenY + markerSize * 0.3f))
}

/** 绘制AR导航线 */
private fun DrawScope.drawARNavigationLine(
    width: Float,
    height: Float,
    routeAngle: Float
) {
    val centerX = width / 2
    val bottomY = height * 0.92f
    val topY = height * 0.35f
    
    rotate(routeAngle, pivot = Offset(centerX, bottomY)) {
        val navPath = Path().apply {
            moveTo(centerX - 25.dp.toPx(), bottomY)
            lineTo(centerX + 25.dp.toPx(), bottomY)
            lineTo(centerX + 80.dp.toPx(), topY)
            lineTo(centerX - 80.dp.toPx(), topY)
            close()
        }
        
        drawPath(navPath, Brush.verticalGradient(
            listOf(Color(0xFF4CAF50).copy(alpha = 0.7f), Color(0xFF4CAF50).copy(alpha = 0.2f), Color.Transparent),
            startY = topY, endY = bottomY
        ))
    }
}

/** 绘制危险区域高亮 */
private fun DrawScope.drawDangerOverlay(
    width: Float,
    height: Float,
    dangerLevel: ARDangerLevel
) {
    val (alpha, color) = when (dangerLevel) {
        ARDangerLevel.CRITICAL -> Pair(0.25f, Color(0xFFF44336))
        ARDangerLevel.HIGH -> Pair(0.12f, Color(0xFFFF9800))
        else -> return
    }
    
    drawRect(color.copy(alpha = alpha), Offset(0f, 0f), Size(width, 80.dp.toPx()))
    drawRect(color.copy(alpha = alpha), Offset(0f, height - 80.dp.toPx()), Size(width, 80.dp.toPx()))
    drawRect(color.copy(alpha = alpha * 0.6f), Offset(0f, 0f), Size(50.dp.toPx(), height))
    drawRect(color.copy(alpha = alpha * 0.6f), Offset(width - 50.dp.toPx(), 0f), Size(50.dp.toPx(), height))
}

/** 计算障碍物屏幕位置 */
fun calculateObstacleScreenPosition(
    phoneHeading: Float,
    obstacleBearing: Float,
    obstacleDistance: Float,
    cameraHorizontalFov: Float = 90f
): Pair<Float, Float> {
    val screenX = 0.5f + (obstacleBearing / cameraHorizontalFov) * 0.5f
    val screenY = 0.45f + ((1f / (obstacleDistance + 1f)) * 0.3f).coerceIn(0f, 0.25f)
    return Pair(screenX.coerceIn(0.05f, 0.95f), screenY.coerceIn(0.15f, 0.85f))
}
