package com.blindpath.module_obstacle.data.detection

import android.content.Context
import com.blindpath.base.config.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * 模型管理器 - 负责TFLite模型的加载、卸载、下载和缓存管理
 *
 * 从 AIDetector 拆分出的单一职责组件：
 * - 多源模型加载（文件系统、Assets、网络下载）
 * - 模型生命周期管理
 * - 线程安全（ReentrantReadWriteLock）
 * - 自动回退到辅助检测模式
 *
 * 构造函数注入 ApplicationContext
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** TFLite 解释器实例 */
    private var interpreter: Interpreter? = null

    /** 当前加载的模型文件 */
    private var modelFile: File? = null

    /** 模型是否已加载 */
    private var isLoaded = false

    /** 是否已尝试加载（防止重复下载） */
    private var isLoadAttempted = false

    /** 是否启用辅助检测模式 */
    private var useAssistedDetection = false

    /** 标定焦距，由 AIDetector 外部设置 */
    var calibratedFocalLength: Float? = null

    // 线程安全锁
    private val lock = ReentrantReadWriteLock()

    // 模型下载镜像列表（按优先级排列）
    private val modelBaseUrls = listOf(
        "https://github.com/majunhb/BlindPath/releases/download/models-v1/",
        "https://ghfast.top/https://github.com/majunhb/BlindPath/releases/download/models-v1/",
        "https://mirror.ghproxy.com/https://github.com/majunhb/BlindPath/releases/download/models-v1/",
    )

    companion object {
        /** 模型文件名 */
        const val MODEL_FILENAME = "yolov8n.tflite"

        /** 模型输入尺寸（正方形） */
        const val MODEL_INPUT_SIZE = 320

        /** 检测锚点数量（YOLOv8输出维度） */
        const val NUM_ANCHORS = 8400

        /** 输出类别数（80类 + 4个坐标 = 84） */
        const val NUM_OUTPUTS = 84

        /** 每个通道的字节数（Float32 = 4字节） */
        const val NUM_BYTES_PER_CHANNEL = 4

        /** 最小有效模型文件大小（字节） */
        private const val MIN_VALID_MODEL_SIZE = 1024L
    }

    // 模型配置
    private val inputSize = AppConfig.AIDetection.INPUT_SIZE
    private val numThreads = AppConfig.AIDetection.NUM_THREADS
    val inputShape: Int get() = inputSize
    val numBytesPerChannel: Int get() = NUM_BYTES_PER_CHANNEL

    /**
     * 加载指定模式的模型
     *
     * 加载策略（按优先级）：
     * 1. 从本地文件系统加载
     * 2. 从 Assets 加载
     * 3. 从网络下载并加载
     * 4. 全部失败则启用辅助检测模式
     *
     * @param modelFileName 模型文件名（如 "yolo_traffic.tflite"）
     * @return true 表示模型加载成功
     */
    suspend fun loadModel(modelFileName: String): Boolean {
        // 防止重复下载尝试
        if (isLoadAttempted) {
            return lock.read { isLoaded }
        }

        return try {
            val options = Interpreter.Options().apply {
                numThreads = this@ModelManager.numThreads
            }

            val foundFile = getModelFile(modelFileName)

            if (foundFile != null && foundFile.exists() && foundFile.length() >= MIN_VALID_MODEL_SIZE) {
                lock.write {
                    modelFile = foundFile
                    interpreter = Interpreter(foundFile, options)
                    isLoaded = true
                    useAssistedDetection = false
                }
                Timber.d("Model loaded: $modelFileName from ${foundFile.absolutePath}")
                return true
            }

            // 尝试从assets加载
            if (loadFromAssets(modelFileName, options)) return true

            // 尝试下载
            if (downloadAndLoad(modelFileName, options)) return true

            // 启用辅助检测
            lock.write {
                isLoaded = false
                useAssistedDetection = true
            }
            Timber.w("Model $modelFileName load failed, using assisted detection")
            isLoadAttempted = true
            false
        } catch (e: Exception) {
            Timber.e(e, "Failed to load model")
            lock.write {
                isLoaded = false
                useAssistedDetection = true
            }
            isLoadAttempted = true
            false
        }
    }

    /**
     * 卸载当前模型并释放资源
     */
    fun unloadModel() {
        lock.write { unloadModelLocked() }
    }

    private fun unloadModelLocked() {
        interpreter?.close()
        interpreter = null
        modelFile = null
        isLoaded = false
        Timber.d("Model unloaded")
    }

    /**
     * 在文件系统中查找模型文件
     */
    private fun getModelFile(modelFileName: String): File? {
        listOf(
            File(context.filesDir, modelFileName),
            File(context.getExternalFilesDir(null), modelFileName),
            File(context.cacheDir, modelFileName)
        ).forEach { file ->
            if (file.exists() && file.length() > 0) return file
        }
        return null
    }

    /**
     * 从 Assets 加载模型
     */
    private fun loadFromAssets(modelFileName: String, options: Interpreter.Options): Boolean {
        val paths = listOf(modelFileName, "module_obstacle/$modelFileName")
        for (path in paths) {
            try {
                val afd = context.assets.openFd(path)
                val size = afd.length
                afd.close()
                if (size >= MIN_VALID_MODEL_SIZE) {
                    val buffer = FileUtil.loadMappedFile(context, path)
                    lock.write {
                        interpreter = Interpreter(buffer.asReadOnlyBuffer(), options)
                        isLoaded = true
                        useAssistedDetection = false
                    }
                    Timber.d("Model loaded from assets: $path")
                    return true
                }
            } catch (_: Exception) { }
        }
        return false
    }

    /**
     * 从网络下载模型并加载
     */
    private suspend fun downloadAndLoad(modelFileName: String, options: Interpreter.Options): Boolean {
        return withContext(Dispatchers.IO) {
            for ((index, baseUrl) in modelBaseUrls.withIndex()) {
                val url = "$baseUrl$modelFileName"
                Timber.d("Downloading model from mirror #${index + 1}: $url")
                val file = downloadModel(url, modelFileName)
                if (file != null && file.exists() && file.length() >= MIN_VALID_MODEL_SIZE) {
                    lock.write {
                        modelFile = file
                        interpreter = Interpreter(file, options)
                        isLoaded = true
                        useAssistedDetection = false
                    }
                    Timber.d("Model downloaded and loaded: $modelFileName")
                    return@withContext true
                }
            }
            false
        }
    }

    /**
     * 计算文件的SHA-256哈希
     */
    private fun calculateSha256(file: File): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(8192)
            var read: Int
            while (input.read(buffer).also { read = it } > 0) {
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 下载单个模型文件
     */
    private suspend fun downloadModel(url: String, fileName: String): File? {
        return withContext(Dispatchers.IO) {
            var outputFile: File? = null
            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Timber.w("Download failed: HTTP ${response.code()}")
                    return@withContext null
                }

                val body = response.body() ?: return@withContext null
                outputFile = File(context.filesDir, fileName)
                FileOutputStream(outputFile).use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
                }

                // SHA-256 校验
                val expectedHash = AppConfig.AIDetection.MODEL_SHA256[fileName]
                if (!expectedHash.isNullOrBlank()) {
                    val actualHash = calculateSha256(outputFile)
                    if (actualHash != expectedHash) {
                        Timber.e("SHA-256 mismatch for $fileName: expected=$expectedHash, actual=$actualHash")
                        outputFile.delete()
                        return@withContext null
                    }
                    Timber.d("SHA-256 verified for $fileName")
                } else {
                    Timber.w("No SHA-256 hash configured for $fileName, skipping verification")
                }

                outputFile
            } catch (e: Exception) {
                Timber.e(e, "Download failed")
                outputFile?.delete()
                null
            }
        }
    }

    /**
     * 模型是否已加载且可用
     */
    fun isModelLoaded(): Boolean = lock.read { isLoaded && interpreter != null }

    /**
     * 是否启用了辅助检测模式
     */
    fun isAssistedDetectionEnabled(): Boolean = useAssistedDetection

    /**
     * 获取 TFLite 解释器（线程安全读取）
     */
    fun getInterpreter(): Interpreter? = lock.read { interpreter }

    /**
     * 重置加载尝试标志，允许重新尝试加载模型
     * 在模式切换后调用
     */
    fun resetLoadAttempt() {
        isLoadAttempted = false
    }
}