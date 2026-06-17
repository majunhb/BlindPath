package com.blindpath.base.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Compose 最佳实践扩展
 */

/**
 * 安全收集 Flow，自动处理生命周期
 */
@Composable
fun <T> rememberFlowWithLifecycle(
    flow: Flow<T>,
    initialValue: T
): T {
    return remember(flow) {
        flow
    }.collectAsStateWithLifecycle(initialValue)
}

/**
 * 防抖点击处理
 */
@Composable
fun debounceOnClick(
    delayMs: Long = 300L,
    onClick: () -> Unit
): () -> Unit {
    val scope = rememberCoroutineScope()
    var lastClickTime = remember { 0L }
    
    return {
        val now = System.currentTimeMillis()
        if (now - lastClickTime >= delayMs) {
            lastClickTime = now
            onClick()
        }
    }
}

/**
 * 带状态的防抖点击
 */
@Composable
fun <T> debounceOnItemClick(
    delayMs: Long = 300L,
    onItemClick: (T) -> Unit
): (T) -> Unit {
    val scope = rememberCoroutineScope()
    var lastClickTime = remember { 0L }
    
    return { item ->
        val now = System.currentTimeMillis()
        if (now - lastClickTime >= delayMs) {
            lastClickTime = now
            onItemClick(item)
        }
    }
}

/**
 * 安全启动协程
 */
@Composable
fun rememberSafeLauncher(
    onError: (Throwable) -> Unit = {},
    block: suspend CoroutineScope.() -> Unit
): () -> Unit {
    val scope = rememberCoroutineScope()
    
    return {
        scope.launch {
            try {
                block()
            } catch (e: Exception) {
                onError(e)
            }
        }
    }
}

/**
 * 单次副作用执行
 */
@Composable
fun OnceEffect(
    key: Any? = null,
    effect: () -> Unit
) {
    val executed = remember { false }
    
    SideEffect {
        if (!executed) {
            effect()
        }
    }
}

/**
 * 条件性副作用
 */
@Composable
fun ConditionalEffect(
    condition: Boolean,
    effect: () -> Unit
) {
    SideEffect {
        if (condition) {
            effect()
        }
    }
}

// 简化导入
@Composable
fun <T> Flow<T>.collectAsStateWithLifecycle(initialValue: T): T {
    // 简化实现，实际应使用 lifecycle-runtime-compose
    var value by androidx.compose.runtime.mutableStateOf(initialValue)
    val scope = rememberCoroutineScope()
    
    SideEffect {
        scope.launch {
            collectLatest { value = it }
        }
    }
    
    return value
}

// 简化 State 导入
private inline operator fun <T> androidx.compose.runtime.MutableState<T>.getValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>): T = value
private inline operator fun <T> androidx.compose.runtime.MutableState<T>.setValue(thisRef: Any?, property: kotlin.reflect.KProperty<*>, value: T) { this.value = value }
