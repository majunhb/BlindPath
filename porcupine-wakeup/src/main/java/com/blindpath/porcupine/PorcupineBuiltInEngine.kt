package com.blindpath.porcupine

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import ai.picovoice.porcupine.PorcupineManager
import android.content.Context
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Porcupine 内置关键词唤醒引擎
 * 
 * 使用 Porcupine SDK 内置关键词，无需下载 .ppn 文件：
 * - porcupine（默认）
 * - alexa
 * - hey google
 * - hey siri
 * - bumblebee
 * - computer
 * - jarvis
 * 
 * @param context 应用上下文
 * @param accessKey Picovoice Access Key（从 https://console.picovoice.ai/ 获取）
 * @param keyword 内置关键词（默认 porcupine）
 */
class PorcupineBuiltInEngine(
    private val context: Context,
    private val accessKey: String,
    private val keyword: String = "porcupine"
) {
    companion object {
        // Porcupine 要求的音频参数
        const val SAMPLE_RATE = 16000
        const val FRAME_LENGTH = 512
        
        // 可用的内置关键词列表
        val BUILT_IN_KEYWORDS = listOf(
            "porcupine", "alexa", "hey google", "hey siri",
            "bumblebee", "computer", "jarvis"
        )
    }

    private var porcupineManager: PorcupineManager? = null
    private var isInitialized = false
    private var isListening = false
    
    // 唤醒回调
    var onWakeWordDetected: ((keyword: String) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    /**
     * 初始化 Porcupine 引擎（使用内置关键词）
     * 
     * @return 初始化结果
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            Timber.w("PorcupineBuiltIn: Already initialized")
            return true
        }

        // 验证 Access Key
        if (accessKey.isBlank()) {
            val error = IllegalArgumentException("Access Key 不能为空")
            Timber.e("PorcupineBuiltIn: ${error.message}")
            onError?.invoke(error)
            return false
        }

        // 验证关键词
        if (!BUILT_IN_KEYWORDS.contains(keyword.lowercase())) {
            Timber.w("PorcupineBuiltIn: 关键词 '$keyword' 可能不是内置关键词")
        }

        return try {
            // 使用 PorcupineManager（内置关键词）
            porcupineManager = PorcupineManager.fromBuiltInKeywords(
                context,
                accessKey,
                listOf(keyword.lowercase()),
                { keywordIndex ->
                    Timber.i("PorcupineBuiltIn: 检测到唤醒词 '$keyword' (index=$keywordIndex)")
                    onWakeWordDetected?.invoke(keyword)
                }
            )

            isInitialized = true
            Timber.i("PorcupineBuiltIn: 初始化成功，关键词='$keyword'")
            true
        } catch (e: PorcupineException) {
            Timber.e(e, "PorcupineBuiltIn: 初始化失败 - ${e.message}")
            onError?.invoke(e)
            false
        } catch (e: Exception) {
            Timber.e(e, "PorcupineBuiltIn: 初始化异常")
            onError?.invoke(e)
            false
        }
    }

    /**
     * 开始监听唤醒词
     */
    fun startListening(): Boolean {
        if (!isInitialized) {
            Timber.w("PorcupineBuiltIn: 未初始化")
            return false
        }

        if (isListening) {
            return true
        }

        return try {
            porcupineManager?.start()
            isListening = true
            Timber.i("PorcupineBuiltIn: 开始监听唤醒词 '$keyword'")
            true
        } catch (e: Exception) {
            Timber.e(e, "PorcupineBuiltIn: 启动监听失败")
            onError?.invoke(e)
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
            Timber.i("PorcupineBuiltIn: 已停止监听")
        } catch (e: Exception) {
            Timber.e(e, "PorcupineBuiltIn: 停止监听异常")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopListening()
        try {
            porcupineManager?.delete()
            Timber.i("PorcupineBuiltIn: 已释放")
        } catch (e: Exception) {
            Timber.e(e, "PorcupineBuiltIn: 释放资源异常")
        }
        porcupineManager = null
        isInitialized = false
    }

    /**
     * 获取当前监听状态
     */
    fun isListening(): Boolean = isListening

    /**
     * 获取当前关键词
     */
    fun getKeyword(): String = keyword
}

/**
 * Porcupine 内置关键词配置
 * 
 * @param accessKey Picovoice Access Key
 * @param keyword 内置关键词（默认 porcupine）
 */
data class PorcupineBuiltInConfig(
    val accessKey: String,
    val keyword: String = "porcupine"
) {
    /**
     * 验证配置是否有效
     */
    fun isValid(): Boolean {
        return accessKey.isNotBlank() && 
               PorcupineBuiltInEngine.BUILT_IN_KEYWORDS.contains(keyword.lowercase())
    }
}