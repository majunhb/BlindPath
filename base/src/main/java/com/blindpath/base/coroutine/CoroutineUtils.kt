package com.blindpath.base.coroutine

import com.blindpath.base.common.BlindPathLog
import com.blindpath.base.common.Result
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout

/**
 * 协程工具扩展函数
 */

const val COROUTINE_UTILS_TAG = "CoroutineUtils"

/**
 * 安全执行协程，返回 Result 类型
 */
suspend inline fun <T> safeSuspend(
    crossinline block: suspend () -> T
): Result<T> {
    return try {
        Result.Success(block())
    } catch (e: Exception) {
        BlindPathLog.e(COROUTINE_UTILS_TAG, mapOf(
            "event" to "safe_suspend_error",
            "error" to (e.message ?: "unknown")
        ), e)
        Result.Error(message = e.message ?: "Unknown error")
    }
}

/**
 * 带超时的安全执行
 */
suspend inline fun <T> safeSuspendWithTimeout(
    timeoutMs: Long,
    crossinline block: suspend () -> T
): Result<T> {
    return try {
        withTimeout(timeoutMs) {
            block()
        }.let { Result.Success(it) }
    } catch (e: TimeoutCancellationException) {
        BlindPathLog.w(COROUTINE_UTILS_TAG, mapOf(
            "event" to "timeout",
            "timeout_ms" to timeoutMs
        ))
        Result.Error(message = "Operation timed out after ${timeoutMs}ms")
    } catch (e: Exception) {
        BlindPathLog.e(COROUTINE_UTILS_TAG, mapOf(
            "event" to "safe_suspend_error",
            "error" to (e.message ?: "unknown")
        ), e)
        Result.Error(message = e.message ?: "Unknown error")
    }
}

/**
 * 重试执行
 */
suspend inline fun <T> retrySuspend(
    times: Int = 3,
    initialDelayMs: Long = 1000,
    maxDelayMs: Long = 10000,
    crossinline block: suspend () -> T
): Result<T> {
    var currentDelay = initialDelayMs
    var lastException: Exception? = null
    
    repeat(times) { attempt ->
        try {
            return Result.Success(block())
        } catch (e: Exception) {
            lastException = e
            BlindPathLog.w(COROUTINE_UTILS_TAG, mapOf(
                "event" to "retry_attempt",
                "attempt" to (attempt + 1),
                "max_attempts" to times,
                "error" to (e.message ?: "unknown")
            ))
            
            if (attempt < times - 1) {
                kotlinx.coroutines.delay(currentDelay)
                currentDelay = minOf(currentDelay * 2, maxDelayMs)
            }
        }
    }
    
    return Result.Error(message = lastException?.message ?: "Retry failed after $times attempts")
}

/**
 * 带指数退避的重试
 */
suspend inline fun <T> retryWithExponentialBackoff(
    maxAttempts: Int = 3,
    initialDelayMs: Long = 1000,
    factor: Double = 2.0,
    maxDelayMs: Long = 30000,
    crossinline block: suspend () -> T
): Result<T> {
    var currentDelay = initialDelayMs
    var lastException: Exception? = null
    
    repeat(maxAttempts) { attempt ->
        try {
            return Result.Success(block())
        } catch (e: Exception) {
            lastException = e
            
            if (attempt < maxAttempts - 1) {
                kotlinx.coroutines.delay(currentDelay)
                currentDelay = minOf((currentDelay * factor).toLong(), maxDelayMs)
            }
        }
    }
    
    return Result.Error(message = lastException?.message ?: "Retry failed")
}

/**
 * 结果映射扩展
 */
inline fun <T, R> Result<T>.map(transform: (T) -> R): Result<R> {
    return when (this) {
        is Result.Success -> Result.Success(transform(data))
        is Result.Error -> Result.Error(code, message)
        is Result.Loading -> Result.Loading
    }
}

/**
 * 结果扁平化扩展
 */
inline fun <T, R> Result<T>.flatMap(transform: (T) -> Result<R>): Result<R> {
    return when (this) {
        is Result.Success -> transform(data)
        is Result.Error -> Result.Error(code, message)
        is Result.Loading -> Result.Loading
    }
}

/**
 * 结果合并扩展
 */
inline fun <T, R> Result<T>.onSuccess(action: (T) -> R): Result<T> {
    if (this is Result.Success) {
        action(data)
    }
    return this
}

inline fun <T> Result<T>.onError(action: (Int, String) -> Unit): Result<T> {
    if (this is Result.Error) {
        action(code, message)
    }
    return this
}

inline fun <T> Result<T>.onLoading(action: () -> Unit): Result<T> {
    if (this is Result.Loading) {
        action()
    }
    return this
}
