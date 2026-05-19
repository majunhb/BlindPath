package com.blindpath.base.ui.animation

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt

/**
 * 动画配置
 */
object AnimationConfig {
    // 时长
    const val DURATION_SHORT = 150
    const val DURATION_MEDIUM = 300
    const val DURATION_LONG = 500
    
    // 延迟
    const val DELAY_SHORT = 50
    const val DELAY_MEDIUM = 100
    const val DELAY_LONG = 200
    
    // 弹簧参数
    const val STIFFNESS_LOW = 100f
    const val STIFFNESS_MEDIUM = 200f
    const val STIFFNESS_HIGH = 400f
    
    const val DAMPING_RATIO_LOW = 0.5f
    const val DAMPING_RATIO_MEDIUM = 0.7f
    const val DAMPING_RATIO_HIGH = 1f
}

/**
 * 预设动画规格
 */
object AnimationSpecs {
    /**
     * 快速弹性动画
     */
    fun <T> fastSpring(): SpringSpec<T> = spring(
        dampingRatio = Spring.DampingRatioMediumBouncy,
        stiffness = Spring.StiffnessLow
    )
    
    /**
     * 平滑动画
     */
    fun <T> smooth(): TweenSpec<T> = tween(
        durationMillis = AnimationConfig.DURATION_MEDIUM,
        easing = FastOutSlowInEasing
    )
    
    /**
     * 进入动画
     */
    fun <T> enter(): TweenSpec<T> = tween(
        durationMillis = AnimationConfig.DURATION_MEDIUM,
        easing = LinearOutSlowInEasing
    )
    
    /**
     * 退出动画
     */
    fun <T> exit(): TweenSpec<T> = tween(
        durationMillis = AnimationConfig.DURATION_SHORT,
        easing = FastOutLinearInEasing
    )
}

/**
 * 透明度动画
 */
@Composable
fun Modifier.animatedAlpha(
    visible: Boolean,
    durationMs: Int = AnimationConfig.DURATION_MEDIUM
): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMs),
        label = "alpha"
    )
    return this.alpha(alpha)
}

/**
 * 缩放动画
 */
@Composable
fun Modifier.animatedScale(
    visible: Boolean,
    durationMs: Int = AnimationConfig.DURATION_MEDIUM
): Modifier {
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.8f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessLow
        ),
        label = "scale"
    )
    return this.scale(scale)
}

/**
 * 滑动进入动画
 */
@Composable
fun Modifier.slideInFromBottom(
    visible: Boolean,
    distance: Float = 100f
): Modifier {
    val offsetY by animateIntAsState(
        targetValue = if (visible) 0 else distance.roundToInt(),
        animationSpec = AnimationSpecs.smooth(),
        label = "slideY"
    )
    return this.offset { IntOffset(0, offsetY) }
}

/**
 * 滑动进入动画（从左侧）
 */
@Composable
fun Modifier.slideInFromLeft(
    visible: Boolean,
    distance: Float = 100f
): Modifier {
    val offsetX by animateIntAsState(
        targetValue = if (visible) 0 else -distance.roundToInt(),
        animationSpec = AnimationSpecs.smooth(),
        label = "slideX"
    )
    return this.offset { IntOffset(offsetX, 0) }
}

/**
 * 滑动进入动画（从右侧）
 */
@Composable
fun Modifier.slideInFromRight(
    visible: Boolean,
    distance: Float = 100f
): Modifier {
    val offsetX by animateIntAsState(
        targetValue = if (visible) 0 else distance.roundToInt(),
        animationSpec = AnimationSpecs.smooth(),
        label = "slideX"
    )
    return this.offset { IntOffset(offsetX, 0) }
}

/**
 * 组合进入动画（淡入 + 缩放）
 */
@Composable
fun Modifier.animatedEnter(
    visible: Boolean
): Modifier {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = AnimationSpecs.enter(),
        label = "enterAlpha"
    )
    
    val scale by animateFloatAsState(
        targetValue = if (visible) 1f else 0.9f,
        animationSpec = AnimationSpecs.fastSpring(),
        label = "enterScale"
    )
    
    return this
        .alpha(alpha)
        .scale(scale)
}

/**
 * 脉冲动画（用于注意力吸引）
 */
@Composable
fun Modifier.pulseAnimation(
    isPlaying: Boolean,
    minScale: Float = 1f,
    maxScale: Float = 1.1f
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    
    val scale by infiniteTransition.animateFloat(
        initialValue = minScale,
        targetValue = maxScale,
        animationSpec = infiniteRepeatable(
            animation = tween(500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulseScale"
    )
    
    return if (isPlaying) {
        this.scale(scale)
    } else {
        this
    }
}

/**
 * 呼吸动画（用于状态指示）
 */
@Composable
fun Modifier.breathAnimation(
    isPlaying: Boolean
): Modifier {
    val infiniteTransition = rememberInfiniteTransition(label = "breath")
    
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.5f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "breathAlpha"
    )
    
    return if (isPlaying) {
        this.alpha(alpha)
    } else {
        this
    }
}

/**
 * 振动动画（用于错误提示）
 */
@Composable
fun Modifier.shakeAnimation(
    trigger: Boolean,
    onFinished: () -> Unit = {}
): Modifier {
    val offsetX by animateFloatAsState(
        targetValue = if (trigger) 0f else 0f,
        animationSpec = keyframes {
            durationMillis = 400
            0f at 0
            -10f at 50
            10f at 100
            -10f at 150
            10f at 200
            -5f at 250
            5f at 300
            0f at 350
        },
        label = "shake"
    )
    
    if (!trigger) {
        SideEffect { onFinished() }
    }
    
    return this.offset { IntOffset(offsetX.roundToInt(), 0) }
}

/**
 * 预定义的页面过渡动画
 */
object PageTransitions {
    /**
     * 淡入淡出过渡
     */
    fun fadeInOut(): EnterExitTransition {
        return fadeIn(animationSpec = tween(300)) togetherWith fadeOut(animationSpec = tween(300))
    }
    
    /**
     * 滑动过渡
     */
    fun slideHorizontally(): EnterExitTransition {
        return slideInHorizontally(
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            initialOffsetX = { fullWidth -> fullWidth / 2 }
        ) togetherWith slideOutHorizontally(
            animationSpec = tween(300, easing = FastOutSlowInEasing),
            targetOffsetX = { fullWidth -> -fullWidth / 2 }
        )
    }
    
    /**
     * 缩放过渡
     */
    fun scaleInOut(): EnterExitTransition {
        return scaleIn(
            animationSpec = tween(300),
            initialScale = 0.9f
        ) togetherWith scaleOut(
            animationSpec = tween(300),
            targetScale = 1.1f
        )
    }
    
    /**
     * 组合过渡（淡入 + 滑动）
     */
    fun combinedTransition(): EnterExitTransition {
        return fadeIn(animationSpec = tween(300)) + slideInVertically(
            animationSpec = tween(300),
            initialOffsetY = { it / 4 }
        ) togetherWith fadeOut(animationSpec = tween(300)) + slideOutVertically(
            animationSpec = tween(300),
            targetOffsetY = { -it / 4 }
        )
    }
}
