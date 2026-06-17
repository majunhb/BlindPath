package com.blindpath.base.ui.ext

import android.os.Build
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 扩展函数：添加圆角阴影
 */
fun Modifier.shadow(
    color: Color = Color.Black.copy(alpha = 0.1f),
    borderRadius: Dp = 0.dp,
    blurRadius: Dp = 8.dp,
    offsetY: Dp = 2.dp,
    offsetX: Dp = 0.dp
) = composed {
    val paint = remember { Paint() }
    
    // API 29 (Android 10) 及以上才支持 setShadowLayer
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        drawBehind {
            drawIntoCanvas { canvas ->
                paint.color = color
                val frameworkPaint = paint.asFrameworkPaint()
                
                frameworkPaint.setShadowLayer(
                    blurRadius.toPx(),
                    offsetX.toPx(),
                    offsetY.toPx(),
                    color.value.toLong()
                )
                
                canvas.drawRoundRect(
                    0f,
                    0f,
                    size.width,
                    size.height,
                    borderRadius.toPx(),
                    borderRadius.toPx(),
                    paint
                )
            }
        }
    } else {
        // 低版本回退：不做阴影处理
        this
    }
}

/**
 * 扩展函数：添加高亮边框（用于可点击元素）
 */
fun Modifier.highlightBorder(
    color: Color,
    width: Dp = 2.dp,
    radius: Dp = 8.dp
) = composed {
    this.clip(RoundedCornerShape(radius))
        .padding(width)
        .padding(-width)
}

/**
 * 扩展函数：条件性应用修饰符
 */
inline fun Modifier.conditional(
    condition: Boolean,
    modifier: Modifier.() -> Modifier
): Modifier = if (condition) {
    then(modifier())
} else {
    this
}

/**
 * 扩展函数：添加底部安全区域内边距
 */
fun Modifier.bottomSafePadding(): Modifier = composed {
    val padding = WindowInsets.navigationBars.asPaddingValues()
    padding(bottom = padding.calculateBottomPadding())
}

/**
 * 扩展函数：添加顶部状态栏内边距
 */
fun Modifier.topSafePadding(): Modifier = composed {
    val padding = WindowInsets.statusBars.asPaddingValues()
    padding(top = padding.calculateTopPadding())
}

/**
 * 扩展函数：添加所有安全区域内边距
 */
fun Modifier.safePadding(): Modifier = composed {
    val statusBarPadding = WindowInsets.statusBars.asPaddingValues()
    val navBarPadding = WindowInsets.navigationBars.asPaddingValues()
    padding(
        top = statusBarPadding.calculateTopPadding(),
        bottom = navBarPadding.calculateBottomPadding()
    )
}

/**
 * 扩展函数：添加无障碍焦点边框
 */
@Composable
fun Modifier.accessibilityFocusBorder(
    isFocused: Boolean,
    color: Color = MaterialTheme.colorScheme.primary,
    width: Dp = 3.dp
): Modifier {
    return if (isFocused) {
        this.drawBehind {
            drawRect(
                color = color,
                size = size
            )
        }
    } else {
        this
    }
}
