package com.blindpath.porcupine

import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineException
import android.content.Context
import kotlinx.coroutines.*
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream

/**
 * Porcupine 离线唤醒引擎
 * 
 * 基于 Picovoice Porcupine SDK 实现，特点：
 * - 完全离线运行，无需网络
 * - 低延迟（<100ms）
 * - 低功耗，适合 always-on 场景
 * - 华为设备完美兼容（不依赖 Google Play 服务）
 * 
 * @param context 应用上下文
 * @param accessKey Picovoice Access Key（免费注册获取）
 * @param keywordPath 唤醒词模型文件路径（.ppn 文件）
 * @param modelPath 声学模型路径（.pv 文件，可选）
 */
class PorcupineWakeWordEngine(
    private val context: Context,
    private val accessKey: String,
    private val keywordPath: String,
    private val modelPath: String? = null
) {
    companion object {
        // Porcupine 要求的音频参数
        const val SAMPLE_RATE = 16000        // 采样率：16kHz
        const val FRAME_LENGTH = 512         // 帧长：512样本（32ms）
        const val BUFFER_SIZE = FRAME_LENGTH * 2  // 16bit = 2 bytes per sample
    }

    private var porcupine: Porcupine? = null
    private var isInitialized = false
    
    // 协程作用域，用于异步处理
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    // 唤醒回调
    var onWakeWordDetected: ((keywordIndex: Int) -> Unit)? = null
    var onError: ((Throwable) -> Unit)? = null

    /**
     * 初始化 Porcupine 引擎
     * 
     * @return 初始化结果，成功返回 true
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            Timber.w("PorcupineEngine: Already initialized")
            return true
        }

        return try {
            // 验证 Access Key
            if (accessKey.isBlank()) {
                throw IllegalArgumentException("Access Key 不能为空，请从 https://picovoice.ai/console/ 获取")
            }

            // 验证唤醒词文件
            if (!File(keywordPath).exists()) {
                throw IllegalArgumentException("唤醒词文件不存在: $keywordPath")
            }

            // 创建 Porcupine 实例
            porcupine = if (modelPath != null) {
                Porcupine.Builder()
                    .setAccessKey(accessKey)
                    .setKeywordPath(keywordPath)
                    .setModelPath(modelPath)
                    .build(context)
            } else {
                Porcupine.Builder()
                    .setAccessKey(accessKey)
                    .setKeywordPath(keywordPath)
                    .build(context)
            }

            isInitialized = true
            Timber.i("PorcupineEngine: 初始化成功，采样率=${porcupine?.sampleRate}, 帧长=${porcupine?.frameLength}")
            true
        } catch (e: PorcupineException) {
            Timber.e(e, "PorcupineEngine: 初始化失败 - ${e.message}")
            onError?.invoke(e)
            false
        } catch (e: Exception) {
            Timber.e(e, "PorcupineEngine: 初始化异常")
            onError?.invoke(e)
            false
        }
    }

    /**
     * 处理音频帧，检测唤醒词
     * 
     * @param pcmData 16bit PCM 音频数据，长度必须为 FRAME_LENGTH
     * @return 检测结果：>=0 表示检测到唤醒词（返回值是关键词索引），-1 表示未检测到
     */
    fun process(pcmData: ShortArray): Int {
        if (!isInitialized || porcupine == null) {
            Timber.w("PorcupineEngine: 未初始化，无法处理音频")
            return -1
        }

        // 验证数据长度
        if (pcmData.size != FRAME_LENGTH) {
            Timber.w("PorcupineEngine: 音频帧长度不匹配，期望 $FRAME_LENGTH，实际 ${pcmData.size}")
            return -1
        }

        return try {
            val keywordIndex = porcupine!!.process(pcmData)
            if (keywordIndex >= 0) {
                Timber.i("PorcupineEngine: 检测到唤醒词，索引=$keywordIndex")
                onWakeWordDetected?.invoke(keywordIndex)
            }
            keywordIndex
        } catch (e: PorcupineException) {
            Timber.e(e, "PorcupineEngine: 处理音频失败")
            onError?.invoke(e)
            -1
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        engineScope.cancel()
        try {
            porcupine?.delete()
            Timber.i("PorcupineEngine: 已释放")
        } catch (e: Exception) {
            Timber.e(e, "PorcupineEngine: 释放资源异常")
        }
        porcupine = null
        isInitialized = false
    }

    /**
     * 获取版本信息
     */
    fun getVersion(): String {
        return try {
            Porcupine.VERSION
        } catch (e: Exception) {
            "unknown"
        }
    }
}

/**
 * Porcupine 引擎配置
 * 
 * @param accessKey Picovoice Access Key
 * @param keywordAssetPath assets 中的唤醒词文件路径（如 "keywords/hey-assistant_android.ppn"）
 * @param modelAssetPath assets 中的模型文件路径（可选）
 */
data class PorcupineConfig(
    val accessKey: String,
    val keywordAssetPath: String,
    val modelAssetPath: String? = null
) {
    companion object {
        /**
         * 从 assets 复制文件到缓存目录
         * Porcupine 需要文件系统路径，不能直接读取 assets
         * 
         * @param context 应用上下文
         * @param assetPath assets 中的文件路径
         * @return 缓存目录中的文件绝对路径
         */
        fun extractAsset(context: Context, assetPath: String): String {
            val fileName = assetPath.substringAfterLast("/")
            val cacheFile = File(context.cacheDir, fileName)
            
            if (cacheFile.exists()) {
                return cacheFile.absolutePath
            }

            context.assets.open(assetPath).use { input ->
                FileOutputStream(cacheFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            Timber.d("PorcupineConfig: 已提取 asset 到 ${cacheFile.absolutePath}")
            return cacheFile.absolutePath
        }
    }
}
