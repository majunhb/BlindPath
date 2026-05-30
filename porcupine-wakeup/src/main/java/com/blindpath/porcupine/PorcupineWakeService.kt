package com.blindpath.porcupine

import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Porcupine 唤醒服务（内置关键词版本）
 * 
 * 使用 Porcupine SDK 内置关键词，无需下载 .ppn 文件。
 * 
 * 内置关键词列表：
 * - porcupine（默认）
 * - alexa
 * - hey google
 * - hey siri
 * - bumblebee
 * - computer
 * - jarvis
 * 
 * 使用步骤：
 * 1. 从 https://console.picovoice.ai/ 获取 Access Key
 * 2. 配置到 local.properties 或 credentials.properties
 * 3. 调用 initialize() 初始化
 * 4. 调用 startListening() 开始监听
 * 5. 说唤醒词触发 onWakeWordDetected 回调
 */
class PorcupineWakeService(
    private val context: Context
) {
    companion object {
        // 内置关键词列表
        val BUILT_IN_KEYWORDS = listOf(
            "porcupine", "alexa", "hey google", "hey siri",
            "bumblebee", "computer", "jarvis"
        )
        
        // 默认唤醒词
        const val DEFAULT_KEYWORD = "porcupine"
    }

    private var porcupineManager: PorcupineManager? = null
    private var isInitialized = false
    private var isListening = false
    private var currentKeyword = DEFAULT_KEYWORD
    
    // 协程作用域
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // 回调
    var onWakeWordDetected: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onStateChanged: ((Boolean) -> Unit)? = null

    /**
     * 初始化唤醒服务
     * 
     * @param accessKey Picovoice Access Key（从 Console 获取）
     * @param keyword 内置关键词（默认 porcupine）
     * @return 初始化是否成功
     */
    fun initialize(
        accessKey: String,
        keyword: String = DEFAULT_KEYWORD
    ): Boolean {
        if (isInitialized) {
            Timber.w("PorcupineWakeService: 已初始化")
            return true
        }

        // 验证 Access Key
        if (accessKey.isBlank()) {
            val errorMsg = "Access Key 为空，请从 https://console.picovoice.ai/ 获取"
            Timber.e("PorcupineWakeService: $errorMsg")
            onError?.invoke(errorMsg)
            return false
        }

        // 验证关键词
        currentKeyword = keyword.lowercase()
        if (!BUILT_IN_KEYWORDS.contains(currentKeyword)) {
            Timber.w("PorcupineWakeService: '$keyword' 不是内置关键词，使用默认 '$DEFAULT_KEYWORD'")
            currentKeyword = DEFAULT_KEYWORD
        }

        return try {
            // 创建 PorcupineManager（使用内置关键词）
            porcupineManager = PorcupineManager.fromBuiltInKeywords(
                context,
                accessKey,
                listOf(currentKeyword),
                { keywordIndex ->
                    handleWakeWordDetected(keywordIndex)
                }
            )

            isInitialized = true
            Timber.i("PorcupineWakeService: 初始化成功，关键词='$currentKeyword'")
            true
        } catch (e: Exception) {
            val errorMsg = "初始化失败: ${e.message}"
            Timber.e(e, "PorcupineWakeService: $errorMsg")
            onError?.invoke(errorMsg)
            false
        }
    }

    /**
     * 从多个来源获取 Access Key
     * 
     * 来源优先级：
     * 1. BuildConfig（CI 构建）
     * 2. assets/credentials.properties
     * 3. 默认空值（需要手动配置）
     */
    fun getAccessKeyFromSources(): String {
        // 1. BuildConfig
        try {
            val key = com.blindpath.porcupine.BuildConfig.PORCUPINE_ACCESS_KEY
            if (key.isNotBlank()) return key
        } catch (e: Exception) {
            Timber.d("PorcupineWakeService: BuildConfig 未找到")
        }

        // 2. assets
        try {
            val props = java.util.Properties()
            context.assets.open("credentials.properties").use { props.load(it) }
            val key = props.getProperty("PORCUPINE_ACCESS_KEY", "")
            if (key.isNotBlank()) return key
        } catch (e: Exception) {
            Timber.d("PorcupineWakeService: assets 未找到")
        }

        return ""
    }

    /**
     * 开始监听唤醒词
     */
    fun startListening(): Boolean {
        if (!isInitialized) {
            Timber.w("PorcupineWakeService: 未初始化")
            onError?.invoke("请先调用 initialize()")
            return false
        }

        if (isListening) {
            Timber.d("PorcupineWakeService: 已在监听中")
            return true
        }

        return try {
            porcupineManager?.start()
            isListening = true
            onStateChanged?.invoke(true)
            Timber.i("PorcupineWakeService: 开始监听 '$currentKeyword'")
            true
        } catch (e: Exception) {
            val errorMsg = "启动监听失败: ${e.message}"
            Timber.e(e, "PorcupineWakeService: $errorMsg")
            onError?.invoke(errorMsg)
            false
        }
    }

    /**
     * 停止监听
     */
    fun stopListening() {
        if (!isListening) return

        try {
            porcupineManager?.stop()
            isListening = false
            onStateChanged?.invoke(false)
            Timber.i("PorcupineWakeService: 已停止监听")
        } catch (e: Exception) {
            Timber.e(e, "PorcupineWakeService: 停止监听异常")
        }
    }

    /**
     * 处理唤醒词检测
     */
    private fun handleWakeWordDetected(keywordIndex: Int) {
        Timber.i("PorcupineWakeService: 检测到唤醒词 '$currentKeyword' (index=$keywordIndex)")
        
        // 触发回调
        onWakeWordDetected?.invoke(currentKeyword)
        
        // 短暂停止监听（避免连续触发）
        serviceScope.launch {
            delay(500)
            // 自动恢复监听
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopListening()
        serviceScope.cancel()
        
        try {
            porcupineManager?.delete()
            Timber.i("PorcupineWakeService: 已释放")
        } catch (e: Exception) {
            Timber.e(e, "PorcupineWakeService: 释放异常")
        }
        
        porcupineManager = null
        isInitialized = false
    }

    /**
     * 获取监听状态
     */
    fun isListening(): Boolean = isListening

    /**
     * 获取当前关键词
     */
    fun getCurrentKeyword(): String = currentKeyword
}