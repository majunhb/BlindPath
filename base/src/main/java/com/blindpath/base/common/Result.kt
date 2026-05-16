package com.blindpath.base.common

/**
 * 统一的API返回结果封装
 * 所有模块的Repository都应返回此类型
 */
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val code: Int = -1, val message: String) : Result<Nothing>()
    data object Loading : Result<Nothing>()

    val isSuccess: Boolean get() = this is Success
    val isError: Boolean get() = this is Error
    val isLoading: Boolean get() = this is Loading

    fun getOrNull(): T? = (this as? Success)?.data
    fun getOrDefault(default: @UnsafeVariance T): T = getOrNull() ?: default

    inline fun <R> map(transform: (T) -> R): Result<R> = when (this) {
        is Success -> Success(transform(data))
        is Error -> this
        is Loading -> Loading
    }

    inline fun onSuccess(action: (T) -> Unit): Result<T> {
        if (this is Success) action(data)
        return this
    }

    inline fun onError(action: (Int, String) -> Unit): Result<T> {
        if (this is Error) action(code, message)
        return this
    }
}

/**
 * Suspend版本的Result扩展
 */
suspend inline fun <T> safeApiCall(
    crossinline apiCall: suspend () -> T
): Result<T> {
    return try {
        Result.Success(apiCall())
    } catch (e: Exception) {
        Result.Error(message = e.message ?: "Unknown error")
    }
}

/**
 * 格式化距离显示
 */
fun Float.formatDistance(): String {
    return when {
        this < 1f -> "${(this * 100).toInt()}厘米"
        this < 1000f -> "${this.toInt()}米"
        else -> String.format("%.1f公里", this / 1000)
    }
}
