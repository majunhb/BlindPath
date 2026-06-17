package com.blindpath.base.coroutine

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers

/**
 * 应用级协程调度器
 * 便于测试时替换为TestDispatcher
 */
object AppDispatchers {
    val Main: CoroutineDispatcher = Dispatchers.Main
    val MainImmediate: CoroutineDispatcher = Dispatchers.Main.immediate
    val IO: CoroutineDispatcher = Dispatchers.IO
    val Default: CoroutineDispatcher = Dispatchers.Default
    val Unconfined: CoroutineDispatcher = Dispatchers.Unconfined
}
