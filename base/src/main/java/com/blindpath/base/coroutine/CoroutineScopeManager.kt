package com.blindpath.base.coroutine

import com.blindpath.base.common.BlindPathLog
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 应用级协程作用域管理器
 * 
 * 提供统一的作用域管理，支持：
 * - 异常隔离
 * - 生命周期感知
 * - 取消策略
 */
class CoroutineScopeManager {
    
    private val tag = "CoroutineScope"
    
    // 主作用域 - 用于UI相关协程
    private val mainScope = CoroutineScope(
        SupervisorJob() + AppDispatchers.Main + createExceptionHandler("MainScope")
    )
    
    // IO作用域 - 用于IO操作
    private val ioScope = CoroutineScope(
        SupervisorJob() + AppDispatchers.IO + createExceptionHandler("IOScope")
    )
    
    // 后台作用域 - 用于后台服务
    private val backgroundScope = CoroutineScope(
        SupervisorJob() + AppDispatchers.Default + createExceptionHandler("BackgroundScope")
    )
    
    /**
     * 获取主作用域
     * 用于UI更新、Compose重组等
     */
    fun getMainScope(): CoroutineScope = mainScope
    
    /**
     * 获取IO作用域
     * 用于网络请求、数据库操作、文件读写等
     */
    fun getIOScope(): CoroutineScope = ioScope
    
    /**
     * 获取后台作用域
     * 用于计算密集型任务、AI推理等
     */
    fun getBackgroundScope(): CoroutineScope = backgroundScope
    
    /**
     * 在主线程执行
     */
    fun launchMain(block: suspend CoroutineScope.() -> Unit) {
        mainScope.launch { block() }
    }
    
    /**
     * 在IO线程执行
     */
    fun launchIO(block: suspend CoroutineScope.() -> Unit) {
        ioScope.launch { block() }
    }
    
    /**
     * 在后台线程执行
     */
    fun launchBackground(block: suspend CoroutineScope.() -> Unit) {
        backgroundScope.launch { block() }
    }
    
    /**
     * 安全启动协程 - 自动捕获异常
     */
    fun safeLaunch(
        scope: CoroutineScope = mainScope,
        onError: ((Throwable) -> Unit)? = null,
        block: suspend CoroutineScope.() -> Unit
    ) {
        scope.launch(
            CoroutineExceptionHandler { _, throwable ->
                BlindPathLog.e(tag, mapOf(
                    "event" to "coroutine_error",
                    "error" to (throwable.message ?: "unknown")
                ), throwable)
                onError?.invoke(throwable)
            }
        ) {
            block()
        }
    }
    
    /**
     * 取消所有作用域
     */
    fun cancelAll() {
        mainScope.cancel("Application shutting down")
        ioScope.cancel("Application shutting down")
        backgroundScope.cancel("Application shutting down")
        BlindPathLog.i(tag, mapOf("event" to "all_scopes_cancelled"))
    }
    
    private fun createExceptionHandler(scopeName: String): CoroutineExceptionHandler {
        return CoroutineExceptionHandler { _, throwable ->
            BlindPathLog.e(tag, mapOf(
                "event" to "uncaught_exception",
                "scope" to scopeName,
                "error" to (throwable.message ?: "unknown"),
                "error_type" to throwable::class.simpleName
            ), throwable)
        }
    }
}
