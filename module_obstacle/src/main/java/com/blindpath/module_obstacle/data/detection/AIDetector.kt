package com.blindpath.module_obstacle.data.detection

import android.content.Context
import android.graphics.Bitmap
import com.blindpath.base.config.AppConfig
import com.blindpath.module_obstacle.domain.model.*
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.max
import kotlin.math.min

/**
 * 多模型AI目标检测器 - 支持室内/导航/场景三模式动态切换
 *
 * 架构设计：
 * - 不同时加载多个模型（内存限制）
 * - 切换模式时释放旧模型、加载新模型
 * - 使用 ReentrantReadWriteLock 保证线程安全
 */
@Singleton
class AIDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var interpreter: Interpreter? = null
    private var isLoaded = false
    private var currentMode: PerceptionMode = PerceptionMode.NAVIGATION

    // 线程安全锁
    private val lock = ReentrantReadWriteLock()

    // 复用缓冲区
    private var inputBuffer: ByteBuffer? = null
    private var scaledBitmap: Bitmap? = null

    // 辅助检测模式
    private var useAssistedDetection = false
    private var lastFrame: Bitmap? = null
    private var frameCounter = 0
    private val ASSIST_FRAME_SKIP = 2

    // 模型配置
    private val inputSize = AppConfig.AIDetection.INPUT_SIZE
    private val numThreads = AppConfig.AIDetection.NUM_THREADS
    private val minValidModelSize = 1024

    // 模型下载镜像
    private val modelBaseUrls = listOf(
        "https://github.com/majunhb/BlindPath/releases/download/models/",
        "https://ghfast.top/https://github.com/majunhb/BlindPath/releases/download/models/",
        "https://mirror.ghproxy.com/https://github.com/majunhb/BlindPath/releases/download/models/",
    )

    // ============ COCO 80类映射到障碍物类型 ============
    private val cocoToObstacle = mapOf(
        0 to ObstacleType.PERSON,
        1 to ObstacleType.BICYCLE, 2 to ObstacleType.VEHICLE, 3 to ObstacleType.MOTORCYCLE,
        5 to ObstacleType.BUS, 7 to ObstacleType.TRUCK,
        9 to ObstacleType.TRAFFIC_LIGHT, 10 to ObstacleType.TRAFFIC_SIGN, 11 to ObstacleType.TRAFFIC_SIGN,
        13 to ObstacleType.BENCH,
        15 to ObstacleType.CAT, 16 to ObstacleType.DOG,
        24 to ObstacleType.BACKPACK, 25 to ObstacleType.UMBRELLA, 26 to ObstacleType.HANDBAG,
        28 to ObstacleType.SUITCASE,
        32 to ObstacleType.SPORTS_BALL,
        36 to ObstacleType.SKATEBOARD,
        39 to ObstacleType.BOTTLE, 40 to ObstacleType.WINE_GLASS, 41 to ObstacleType.CUP,
        42 to ObstacleType.FORK, 43 to ObstacleType.KNIFE, 44 to ObstacleType.SPOON, 45 to ObstacleType.BOWL,
        46 to ObstacleType.BANANA, 47 to ObstacleType.APPLE,
        56 to ObstacleType.CHAIR, 57 to ObstacleType.SOFA, 58 to ObstacleType.POTTED_PLANT,
        59 to ObstacleType.BED, 60 to ObstacleType.TABLE,
        61 to ObstacleType.SINK, 62 to ObstacleType.TV,
        63 to ObstacleType.LAPTOP, 64 to ObstacleType.MOUSE_DEVICE, 65 to ObstacleType.REMOTE,
        66 to ObstacleType.KEYBOARD, 67 to ObstacleType.PHONE,
        68 to ObstacleType.MICROWAVE, 69 to ObstacleType.OVEN, 70 to ObstacleType.TOASTER,
        71 to ObstacleType.SINK, 72 to ObstacleType.REFRIGERATOR,
        73 to ObstacleType.BOOK, 74 to ObstacleType.CLOCK, 75 to ObstacleType.VASE,
        76 to ObstacleType.SCISSORS, 77 to ObstacleType.TEDDY_BEAR, 78 to ObstacleType.HAIR_DRYER,
        79 to ObstacleType.TOOTHBRUSH
    )

    // COCO中文名称
    private val cocoChineseNames = mapOf(
        0 to "人", 1 to "自行车", 2 to "汽车", 3 to "摩托车",
        5 to "公交车", 7 to "卡车",
        9 to "红绿灯", 10 to "消防栓", 11 to "停车标志", 13 to "长椅",
        15 to "猫", 16 to "狗",
        24 to "背包", 25 to "雨伞", 26 to "手提包", 28 to "行李箱",
        32 to "运动球", 36 to "滑板",
        39 to "瓶子", 40 to "酒杯", 41 to "杯子", 42 to "叉子", 43 to "刀",
        44 to "勺子", 45 to "碗", 46 to "香蕉", 47 to "苹果",
        56 to "椅子", 57 to "沙发", 58 to "盆栽", 59 to "床", 60 to "餐桌",
        61 to "马桶", 62 to "电视",
        63 to "笔记本", 64 to "鼠标", 65 to "遥控器", 66 to "键盘", 67 to "手机",
        68 to "微波炉", 69 to "烤箱", 70 to "烤面包机", 71 to "水槽", 72 to "冰箱",
        73 to "书", 74 to "时钟", 75 to "花瓶", 76 to "剪刀", 77 to "玩具熊",
        78 to "吹风机", 79 to "牙刷"
    )

    // 障碍物已知高度（米）
    private val obstacleKnownHeights = mapOf(
        ObstacleType.PERSON to 1.7f,
        ObstacleType.VEHICLE to 1.5f, ObstacleType.BUS to 3.0f, ObstacleType.TRUCK to 2.5f,
        ObstacleType.MOTORCYCLE to 1.2f, ObstacleType.BICYCLE to 1.3f,
        ObstacleType.TRAFFIC_LIGHT to 0.6f, ObstacleType.TRAFFIC_SIGN to 0.6f,
        ObstacleType.PILLAR to 0.3f, ObstacleType.BENCH to 0.8f,
        ObstacleType.STEP_UP to 0.2f, ObstacleType.STEP_DOWN to 0.2f,
        ObstacleType.STAIRS to 0.18f, ObstacleType.CURB to 0.15f,
        ObstacleType.CHAIR to 0.9f, ObstacleType.SOFA to 0.8f,
        ObstacleType.POTTED_PLANT to 0.5f, ObstacleType.BED to 0.5f, ObstacleType.TABLE to 0.75f,
        ObstacleType.CAT to 0.3f, ObstacleType.DOG to 0.5f,
        ObstacleType.REFRIGERATOR to 1.8f, ObstacleType.TV to 0.6f,
        ObstacleType.BOOK to 0.03f, ObstacleType.VASE to 0.4f, ObstacleType.CLOCK to 0.3f
    )

    // 当前模式的类别白名单
    private var currentWhitelist: Set<ObstacleType>? = null

    // 焦距
    private var calibratedFocalLength: Float? = null

    companion object {
        const val DANGER_DISTANCE = 1.5f
        const val WARNING_DISTANCE = 3.0f
        const val CONF_DANGER = 0.28f
        const val CONF_WARNING = 0.45f
        const val CONF_IGNORE = 0.7f
    }

    /**
     * 获取当前感知模式
     */
    fun getCurrentMode(): PerceptionMode = lock.read { currentMode }

    /**
     * 切换感知模式 - 自动卸载旧模型并加载新模型
     */
    suspend fun switchMode(mode: PerceptionMode): Boolean {
        lock.write {
            if (currentMode == mode && isLoaded) {
                Timber.d("Already in mode: $mode")
                return@write true
            }

            // 1. 卸载旧模型
            unloadModelLocked()

            // 2. 更新模式
            currentMode = mode
            currentWhitelist = mode.getObstacleTypeWhitelist()

            Timber.d("Switched to mode: $mode, model: ${mode.modelFileName}")
        }

        // 3. 加载新模型（在锁外执行，避免阻塞）
        return loadModel()
    }

    /**
     * 加载模型（根据当前模式）
     */
    suspend fun loadModel(): Boolean {
        return try {
            val options = Interpreter.Options().apply {
                numThreads = numThreads
            }

            val modelFileName = lock.read { currentMode.modelFileName }
            val modelFile = getModelFile(modelFileName)

            if (modelFile != null && modelFile.exists() && modelFile.length() >= minValidModelSize) {
                lock.write {
                    interpreter = Interpreter(modelFile, options)
                    isLoaded = true
                    useAssistedDetection = false
                }
                Timber.d("Model loaded: $modelFileName from ${modelFile.absolutePath}")
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
            false
        } catch (e: Exception) {
            Timber.e(e, "Failed to load model")
            false
        }
    }

    /**
     * 卸载模型
     */
    fun unloadModel() {
        lock.write { unloadModelLocked() }
    }

    private fun unloadModelLocked() {
        interpreter?.close()
        interpreter = null
        inputBuffer = null
        scaledBitmap = null
        isLoaded = false
        Timber.d("Model unloaded")
    }

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

    private fun loadFromAssets(modelFileName: String, options: Interpreter.Options): Boolean {
        val paths = listOf(modelFileName, "module_obstacle/$modelFileName")
        for (path in paths) {
            try {
                val afd = context.assets.openFd(path)
                val size = afd.length
                afd.close()
                if (size >= minValidModelSize) {
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

    private suspend fun downloadAndLoad(modelFileName: String, options: Interpreter.Options): Boolean {
        return withContext(Dispatchers.IO) {
            for ((index, baseUrl) in modelBaseUrls.withIndex()) {
                val url = "$baseUrl$modelFileName"
                Timber.d("Downloading model from mirror #${index + 1}: $url")
                val file = downloadModel(url, modelFileName)
                if (file != null && file.exists() && file.length() >= minValidModelSize) {
                    lock.write {
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
                    Timber.w("Download failed: HTTP ${response.code}")
                    return@withContext null
                }

                val body = response.body ?: return@withContext null
                outputFile = File(context.filesDir, fileName)
                FileOutputStream(outputFile).use { output ->
                    body.byteStream().use { input ->
                        input.copyTo(output)
                    }
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
     * 检测障碍物
     */
    suspend fun detect(bitmap: Bitmap): List<DetectedObstacle> {
        frameCounter++

        lock.read {
            if (!isLoaded || interpreter == null) {
                return@read null
            }
        } ?: return if (useAssistedDetection) assistedDetect(bitmap) else emptyList()

        // 跳帧处理
        if (frameCounter % ASSIST_FRAME_SKIP != 0) return emptyList()

        return try {
            val inputBuffer = preprocessImage(bitmap)
            val outputBuffer = Array(1) { Array(84) { FloatArray(8400) } }

            lock.read {
                interpreter?.runForMultipleInputsOutputs(
                    arrayOf<Any>(inputBuffer),
                    mapOf(0 to outputBuffer)
                )
            }

            postProcess(outputBuffer[0], bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Timber.e(e, "Detection failed")
            emptyList()
        }
    }

    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4)
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            byteBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
            byteBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)
            byteBuffer.putFloat((pixel and 0xFF) / 255.0f)
        }
        byteBuffer.rewind()
        return byteBuffer
    }

    private fun postProcess(output: Array<FloatArray>, imageWidth: Int, imageHeight: Int): List<DetectedObstacle> {
        val results = mutableListOf<DetectedObstacle>()
        val whitelist = lock.read { currentWhitelist }
        val mode = lock.read { currentMode }

        for (i in 0 until 8400) {
            var maxScore = 0f
            var maxClass = -1
            for (j in 4 until 84) {
                val score = output[j][i]
                if (score > maxScore) {
                    maxScore = score
                    maxClass = j - 4
                }
            }

            if (maxScore < mode.confidenceThreshold) continue

            val obstacleType = cocoToObstacle[maxClass] ?: continue

            // 白名单过滤
            if (whitelist != null && obstacleType !in whitelist) continue

            val cx = output[0][i] / inputSize * imageWidth
            val cy = output[1][i] / inputSize * imageHeight
            val w = output[2][i] / inputSize * imageWidth
            val h = output[3][i] / inputSize * imageHeight

            val left = (cx - w / 2).coerceIn(0f, imageWidth.toFloat())
            val top = (cy - h / 2).coerceIn(0f, imageHeight.toFloat())
            val right = (cx + w / 2).coerceIn(0f, imageWidth.toFloat())
            val bottom = (cy + h / 2).coerceIn(0f, imageHeight.toFloat())

            val distance = estimateDistance(obstacleType, h, imageHeight.toFloat())

            // 距离分段置信过滤
            val confThreshold = when {
                distance < DANGER_DISTANCE -> CONF_DANGER
                distance < WARNING_DISTANCE -> CONF_WARNING
                else -> CONF_IGNORE
            }
            if (maxScore < confThreshold) continue
            if (distance > WARNING_DISTANCE) continue

            results.add(DetectedObstacle(
                type = obstacleType,
                confidence = maxScore,
                distance = distance,
                direction = calculateDirection(cx, imageWidth.toFloat()),
                boundingBox = BoundingBox(
                    left / imageWidth, top / imageHeight,
                    right / imageWidth, bottom / imageHeight
                )
            ))
        }

        return nonMaxSuppression(results, mode.nmsThreshold)
    }

    private fun estimateDistance(type: ObstacleType, pixelHeight: Float, imageHeight: Float): Float {
        val knownHeight = obstacleKnownHeights[type] ?: 1.0f
        val focalLength = calibratedFocalLength ?: 800f
        val distance = if (pixelHeight > 0) knownHeight * focalLength / pixelHeight else 10f

        return when (type) {
            ObstacleType.STEP_UP, ObstacleType.STEP_DOWN, ObstacleType.CURB, ObstacleType.PIT ->
                distance.coerceIn(0.3f, 5f)
            ObstacleType.PERSON -> distance.coerceIn(0.5f, 15f)
            ObstacleType.VEHICLE, ObstacleType.BUS, ObstacleType.TRUCK ->
                distance.coerceIn(1f, 30f)
            ObstacleType.TRAFFIC_LIGHT -> distance.coerceIn(1f, 50f)
            else -> distance.coerceIn(0.3f, 10f)
        }
    }

    private fun calculateDirection(centerX: Float, imageWidth: Float): Direction {
        val ratio = centerX / imageWidth
        return when {
            ratio < 0.33f -> Direction.LEFT
            ratio < 0.66f -> Direction.CENTER
            else -> Direction.RIGHT
        }
    }

    private fun nonMaxSuppression(boxes: List<DetectedObstacle>, iouThreshold: Float): List<DetectedObstacle> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val keep = mutableListOf<DetectedObstacle>()
        while (sorted.isNotEmpty()) {
            val current = sorted.removeAt(0)
            keep.add(current)
            sorted.removeAll { calculateIoU(current.boundingBox, it.boundingBox) > iouThreshold && it.type == current.type }
        }
        return keep
    }

    private fun calculateIoU(a: BoundingBox, b: BoundingBox): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = min(a.right, b.right)
        val interBottom = min(a.bottom, b.bottom)
        val interArea = max(0f, interRight - interLeft) * max(0f, interBottom - interTop)
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)
        return interArea / (aArea + bArea - interArea)
    }

    // ====== 辅助检测模式 ======
    private fun assistedDetect(bitmap: Bitmap): List<DetectedObstacle> {
        val results = mutableListOf<DetectedObstacle>()
        try {
            lastFrame?.let { last ->
                if (last.width == bitmap.width && last.height == bitmap.height) {
                    results.addAll(detectMotion(last, bitmap))
                }
            }
            results.addAll(detectEdges(bitmap))
            lastFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Timber.w(e, "Assisted detection failed")
        }
        return results
    }

    private fun detectMotion(prev: Bitmap, curr: Bitmap): List<DetectedObstacle> {
        // 简化版运动检测
        return emptyList()
    }

    private fun detectEdges(bitmap: Bitmap): List<DetectedObstacle> {
        // 简化版边缘检测
        return emptyList()
    }

    fun isModelLoaded(): Boolean = lock.read { isLoaded && interpreter != null }
    fun setCalibratedFocalLength(focalLength: Float) {
        calibratedFocalLength = focalLength
    }
}
