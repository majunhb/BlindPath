package com.blindpath.module_obstacle.data.detection

import android.content.Context
import android.graphics.Bitmap
import com.blindpath.base.config.AppConfig
import com.blindpath.module_obstacle.domain.model.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import org.tensorflow.lite.gpu.GpuDelegate
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.FileChannel
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * 基于TensorFlow Lite的AI目标检测器
 * 使用YOLOv8模型进行端侧推理
 *
 * 支持的障碍物类型：
 * - 台阶、楼梯
 * - 水坑、坑洼
 * - 井盖
 * - 红绿灯
 * - 斑马线
 * - 行人、车辆、自行车
 * - 石墩、电线杆等
 */
@Singleton
class AIDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var interpreter: Interpreter? = null
    private var isLoaded = false
    private var mlKitDetector: com.google.mlkit.vision.objects.ObjectDetector? = null
    private var useMlKit = false

    // 模型配置 - 使用 AppConfig 集中管理
    private val modelPath = AppConfig.AIDetection.MODEL_NAME
    private val modelUrl = "https://github.com/ultralytics/assets/releases/download/v8.2.0/${AppConfig.AIDetection.MODEL_NAME}"
    private val inputSize = AppConfig.AIDetection.INPUT_SIZE
    private val numThreads = AppConfig.AIDetection.NUM_THREADS

    // ============ COCO 80类 到 视障障碍物类型 的映射 ============
    // COCO类别参考: https://cocodataset.org/#home
    // 只映射与视障导航相关的类别
    private val cocoToObstacle = mapOf(
        // 人物类
        0 to ObstacleType.PERSON,        // person

        // 交通工具类（对盲人威胁较大）
        1 to ObstacleType.BICYCLE,       // bicycle
        2 to ObstacleType.VEHICLE,       // car
        3 to ObstacleType.MOTORCYCLE,    // motorcycle
        5 to ObstacleType.VEHICLE,       // bus
        7 to ObstacleType.VEHICLE,       // truck

        // 【重要】COCO class 9 是 traffic light，映射到红绿灯
        9 to ObstacleType.TRAFFIC_LIGHT, // traffic light

        // 交通标志
        10 to ObstacleType.TRAFFIC_SIGN, // stop sign

        // 街道设施
        11 to ObstacleType.PILLAR,       // fire hydrant (归类为柱子/障碍)
        12 to ObstacleType.BENCH,        // bench

        // 家居物品（可能阻挡路径）
        56 to ObstacleType.CHAIR,        // chair
        57 to ObstacleType.SOFA,         // sofa
        58 to ObstacleType.POTTTED_PLANT, // potted plant
        59 to ObstacleType.BED,          // bed
        60 to ObstacleType.TABLE,        // dining table

        // 个人物品
        24 to ObstacleType.BACKPACK,     // backpack
        25 to ObstacleType.UMBRELLA,     // umbrella
        26 to ObstacleType.HANDBAG,     // handbag
        28 to ObstacleType.SUITCASE,     // suitcase

        // 电子设备
        39 to ObstacleType.BOTTLE,      // bottle
        63 to ObstacleType.LAPTOP,      // laptop
        67 to ObstacleType.PHONE        // cell phone
    )

    // ============ 障碍物已知高度（用于单目测距） ============
    // 单位：米（m）
    private val obstacleKnownHeights = mapOf(
        // 人物
        ObstacleType.PERSON to 1.7f,        // 成人平均身高 1.7m

        // 交通工具
        ObstacleType.VEHICLE to 1.5f,       // 轿车高度约1.5m
        ObstacleType.BUS to 3.0f,           // 公交车高度约3m
        ObstacleType.TRUCK to 2.5f,         // 卡车高度约2.5m
        ObstacleType.MOTORCYCLE to 1.2f,    // 摩托车高度约1.2m
        ObstacleType.BICYCLE to 1.3f,       // 自行车高度约1.3m

        // 交通设施
        ObstacleType.TRAFFIC_LIGHT to 0.6f, // 红绿灯高度约0.6m
        ObstacleType.TRAFFIC_SIGN to 0.6f,   // 交通标志高度约0.6m
        ObstacleType.PILLAR to 0.3f,        // 石墩直径约0.3m
        ObstacleType.BENCH to 0.8f,         // 长椅高度约0.8m

        // 地面障碍物
        ObstacleType.STEP_UP to 0.2f,       // 台阶高度约0.2m
        ObstacleType.STEP_DOWN to 0.2f,     // 下台阶同理
        ObstacleType.STAIRS to 0.18f,       // 楼梯台阶高度
        ObstacleType.CURB to 0.15f,         // 路沿高度约0.15m

        // 家居物品
        ObstacleType.CHAIR to 0.9f,         // 椅子高度约0.9m
        ObstacleType.SOFA to 0.8f,          // 沙发高度约0.8m
        ObstacleType.POTTTED_PLANT to 0.5f,  // 盆栽高度约0.5m
        ObstacleType.BED to 0.5f,           // 床高度约0.5m
        ObstacleType.TABLE to 0.75f         // 餐桌高度约0.75m
    )

    // 检测阈值 - 使用 AppConfig 集中管理
    private val confidenceThreshold = AppConfig.AIDetection.CONFIDENCE_THRESHOLD
    private val iouThreshold = AppConfig.AIDetection.IOU_THRESHOLD

    // 焦距（需根据实际摄像头参数校准）
    private var calibratedFocalLength: Float? = null

    /**
     * 加载模型（支持自动下载）
     */
    suspend fun loadModel(): Boolean {
        return try {
            // 优化：启用XNNPACK和GPU加速
            val options = Interpreter.Options().apply {
                numThreads = numThreads
                setUseXNNPACK(true)  // 启用XNNPACK加速

                // 尝试启用GPU加速（如果可用）
                // 注意：GPU Delegate 在某些设备上可能不可用，需要安全降级
                if (isGpuDelegateAvailable()) {
                    try {
                        val gpuDelegate = GpuDelegate()
                        addDelegate(gpuDelegate)
                        Timber.d("GPU acceleration enabled successfully")
                    } catch (e: Exception) {
                        Timber.w("GPU delegate creation failed, falling back to CPU: ${e.message}")
                    }
                } else {
                    Timber.d("GPU acceleration not available on this device, using CPU only")
                }
            }

            // 1. 先尝试从内部存储加载
            val modelFile = getModelFile()
            
            if (modelFile != null && modelFile.exists()) {
                // 从文件系统加载
                val modelBuffer = FileInputStream(modelFile).channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    0,
                    modelFile.length()
                )
                interpreter = Interpreter(modelBuffer, options)
                isLoaded = true
                Timber.d("YOLOv8 model loaded from file: ${modelFile.absolutePath}")
                return true
            }

            // 2. 尝试从assets加载
            try {
                val assetBuffer = FileUtil.loadMappedFile(context, modelPath)
                interpreter = Interpreter(assetBuffer.asReadOnlyBuffer(), options)
                isLoaded = true
                Timber.d("YOLOv8 model loaded from assets")
                return true
            } catch (e: Exception) {
                Timber.w("Model not found in assets, will try to download: ${e.message}")
            }

            // 3. 自动从网络下载
            Timber.d("Downloading model from: $modelUrl")
            val downloadedFile = downloadModel()
            
            if (downloadedFile != null && downloadedFile.exists()) {
                val modelBuffer = FileInputStream(downloadedFile).channel.map(
                    java.nio.channels.FileChannel.MapMode.READ_ONLY,
                    0,
                    downloadedFile.length()
                )
                interpreter = Interpreter(modelBuffer, options)
                isLoaded = true
                Timber.d("YOLOv8 model downloaded and loaded successfully")
                return true
            }

            // 4. 所有TFLite方法都失败，回退到ML Kit
            Timber.w("TFLite模型文件无法加载，尝试ML Kit回退")
            try {
                // 使用流式模式 + 分类，适合实时视频检测
                val options = ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                    .enableClassification()  // 启用物体分类（识别物体类型）
                    .enableMultipleObjects()  // 启用多物体检测
                    .build()
                mlKitDetector = ObjectDetection.getClient(options)
                useMlKit = true
                isLoaded = true
                Timber.i("ML Kit initialized successfully with STREAM_MODE + Classification")
                return true
            } catch (e: Exception) {
                Timber.e(e, "ML Kit回退也失败了")
                isLoaded = false
                return false
            }

        } catch (e: Exception) {
            Timber.e(e, "加载AI模型失败，尝试ML Kit回退")
            try {
                // 使用流式模式 + 分类，适合实时视频检测
                val options = ObjectDetectorOptions.Builder()
                    .setDetectorMode(ObjectDetectorOptions.STREAM_MODE)
                    .enableClassification()  // 启用物体分类（识别物体类型）
                    .enableMultipleObjects()  // 启用多物体检测
                    .build()
                mlKitDetector = ObjectDetection.getClient(options)
                useMlKit = true
                isLoaded = true
                Timber.i("ML Kit initialized successfully with STREAM_MODE + Classification (exception recovery)")
                return true
            } catch (e2: Exception) {
                Timber.e(e2, "ML Kit回退也失败了（异常恢复）")
                isLoaded = false
                return false
            }
        }
    }

    /**
     * 检查 GPU Delegate 是否可用
     * 某些设备可能不支持 GPU 加速，需要提前检查以避免崩溃
     */
    private fun isGpuDelegateAvailable(): Boolean {
        return try {
            // 尝试加载 GpuDelegate 类
            Class.forName("org.tensorflow.lite.gpu.GpuDelegate")
            // 尝试创建一个临时实例来验证
            val delegate = GpuDelegate()
            delegate.close() // 立即关闭测试实例
            true
        } catch (e: Throwable) {
            Timber.w("GPU Delegate not available: ${e.javaClass.simpleName}: ${e.message}")
            false
        }
    }

    /**
     * 卸载模型
     */
    fun unloadModel() {
        interpreter?.close()
        interpreter = null
        mlKitDetector = null
        useMlKit = false
        isLoaded = false
        Timber.d("AI model unloaded")
    }

    /**
     * 获取模型文件（优先从文件系统，备选assets）
     */
    private fun getModelFile(): File? {
        // 1. 检查内部存储
        val internalFile = File(context.filesDir, modelPath)
        if (internalFile.exists() && internalFile.length() > 0) {
            Timber.d("Found model in internal storage: ${internalFile.absolutePath}")
            return internalFile
        }

        // 2. 检查外部存储
        val externalDir = context.getExternalFilesDir(null)
        if (externalDir != null) {
            val externalFile = File(externalDir, modelPath)
            if (externalFile.exists() && externalFile.length() > 0) {
                Timber.d("Found model in external storage: ${externalFile.absolutePath}")
                return externalFile
            }
        }

        // 3. 检查缓存目录
        val cacheFile = File(context.cacheDir, modelPath)
        if (cacheFile.exists() && cacheFile.length() > 0) {
            Timber.d("Found model in cache: ${cacheFile.absolutePath}")
            return cacheFile
        }

        Timber.d("Model file not found in file system")
        return null
    }

    /**
     * 从网络下载模型文件
     */
    private suspend fun downloadModel(): File? {
        return withContext(Dispatchers.IO) {
            var outputFile: File? = null
            try {
                Timber.d("Starting model download from: $modelUrl")

                val client = OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(60, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(modelUrl)
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Timber.e("Download failed with code: ${response.code}")
                    return@withContext null
                }

                // 保存到内部存储
                outputFile = File(context.filesDir, modelPath)
                val inputStream = response.body?.byteStream()
                val outputStream = FileOutputStream(outputFile)

                val buffer = ByteArray(4096)
                var bytesRead: Int
                var totalBytes: Long = 0
                val fileSize = response.body?.contentLength() ?: -1

                while (true) {
                    bytesRead = inputStream?.read(buffer) ?: -1
                    if (bytesRead == -1) break
                    outputStream.write(buffer, 0, bytesRead)
                    totalBytes += bytesRead
                    if (fileSize > 0) {
                        val progress = (totalBytes * 100 / fileSize).toInt()
                        Timber.d("Download progress: $progress%")
                    }
                }

                outputStream.close()
                inputStream?.close()

                Timber.d("Model downloaded successfully: ${outputFile.absolutePath} (${outputFile.length()} bytes)")
                return@withContext outputFile

            } catch (e: Exception) {
                Timber.e(e, "Model download failed")
                // 清理不完整的文件
                outputFile?.delete()
                return@withContext null
            }
        }
    }

    /**
     * 设置摄像头焦距（用于更精确的距离估算）
     */
    fun setCalibratedFocalLength(focalLength: Float) {
        calibratedFocalLength = focalLength
        Timber.d("Calibrated focal length set to: $focalLength")
    }

    /**
     * 检测障碍物
     */
    suspend fun detect(bitmap: Bitmap): List<DetectedObstacle> {
        if (!isLoaded) {
            Timber.w("AIDetector: Model not loaded, cannot detect")
            return emptyList()
        }

        val startTime = System.currentTimeMillis()
        
        val results = if (useMlKit && interpreter == null) {
            Timber.d("AIDetector: Using ML Kit for detection")
            detectWithMlKit(bitmap)
        } else if (interpreter != null) {
            Timber.d("AIDetector: Using YOLOv8 TFLite for detection")
            try {
                // 预处理图像
                val inputBuffer = preprocessImage(bitmap)

                // 准备输出数组
                // YOLOv8输出形状: [1, 84, 8400] (84 = 4(bbox) + 80(classes))
                val outputBuffer = Array(1) { Array(84) { FloatArray(8400) } }

                // 推理
                val inputs = arrayOf<Any>(inputBuffer)
                val outputs = mapOf<Int, Any>(0 to outputBuffer)
                interpreter?.runForMultipleInputsOutputs(inputs, outputs)

                // 后处理
                postProcess(outputBuffer[0], bitmap.width, bitmap.height)
            } catch (e: Exception) {
                Timber.e(e, "YOLOv8 detection failed, falling back to ML Kit")
                // 如果 YOLOv8 失败，尝试 ML Kit
                if (mlKitDetector != null) {
                    useMlKit = true
                    detectWithMlKit(bitmap)
                } else {
                    emptyList()
                }
            }
        } else {
            Timber.w("AIDetector: No detector available (interpreter=$interpreter, mlKitDetector=$mlKitDetector, useMlKit=$useMlKit)")
            emptyList()
        }
        
        val elapsed = System.currentTimeMillis() - startTime
        Timber.d("AIDetector: Detection completed in ${elapsed}ms, found ${results.size} obstacles")
        
        return results
    }

    /**
     * 预处理图像
     */
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val byteBuffer = ByteBuffer.allocateDirect(1 * inputSize * inputSize * 3 * 4) // Float32
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            val r = ((pixel shr 16) and 0xFF) / 255.0f
            val g = ((pixel shr 8) and 0xFF) / 255.0f
            val b = (pixel and 0xFF) / 255.0f
            byteBuffer.putFloat(r)
            byteBuffer.putFloat(g)
            byteBuffer.putFloat(b)
        }

        byteBuffer.rewind()
        return byteBuffer
    }

    /**
     * 后处理检测结果
     */
    private fun postProcess(
        output: Array<FloatArray>,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedObstacle> {
        val results = mutableListOf<DetectedObstacle>()

        // YOLOv8输出格式: [84, 8400]
        // 每个检测 = 4(坐标) + 80(类别分数)
        for (i in 0 until 8400) {
            // 找到最大分数的类别
            var maxScore = 0f
            var maxClass = -1

            for (j in 4 until 84) {
                val score = output[j][i]
                if (score > maxScore) {
                    maxScore = score
                    maxClass = j - 4 // 类别索引 (0-79)
                }
            }

            // 检查置信度
            if (maxScore < confidenceThreshold) continue

            // 获取类别映射
            val obstacleType = cocoToObstacle[maxClass] ?: continue

            // 解析边界框
            val cx = output[0][i] / inputSize * imageWidth
            val cy = output[1][i] / inputSize * imageHeight
            val w = output[2][i] / inputSize * imageWidth
            val h = output[3][i] / inputSize * imageHeight

            val left = (cx - w / 2).coerceIn(0f, imageWidth.toFloat())
            val top = (cy - h / 2).coerceIn(0f, imageHeight.toFloat())
            val right = (cx + w / 2).coerceIn(0f, imageWidth.toFloat())
            val bottom = (cy + h / 2).coerceIn(0f, imageHeight.toFloat())

            // 计算距离（基于物体大小估算）
            val distance = estimateDistance(obstacleType, h, imageHeight.toFloat())

            // 计算方向
            val direction = calculateDirection(cx, imageWidth.toFloat())

            results.add(
                DetectedObstacle(
                    type = obstacleType,
                    confidence = maxScore,
                    distance = distance,
                    direction = direction,
                    boundingBox = BoundingBox(
                        left / imageWidth,
                        top / imageHeight,
                        right / imageWidth,
                        bottom / imageHeight
                    )
                )
            )
        }

        // NMS去重
        return nonMaxSuppression(results)
    }

    /**
     * 基于物体大小估算距离（单目测距）
     * 公式：距离 = 实际高度 × 焦距 / 像素高度
     */
    private fun estimateDistance(type: ObstacleType, pixelHeight: Float, imageHeight: Float): Float {
        // 获取已知高度
        val knownHeight = obstacleKnownHeights[type] ?: 1.0f

        // 获取焦距（优先使用校准值，否则使用默认值）
        val focalLength = calibratedFocalLength ?: 800f

        // 估算距离
        val distance = if (pixelHeight > 0) {
            knownHeight * focalLength / pixelHeight
        } else {
            10f // 无法估算时的默认值
        }

        // 根据物体类型调整估算
        val adjustedDistance = when (type) {
            // 地面物体（台阶、路沿、坑洼）- 通常更容易准确估算
            ObstacleType.STEP_UP, ObstacleType.STEP_DOWN, ObstacleType.CURB, ObstacleType.PIT -> {
                distance.coerceIn(0.3f, 5f)
            }
            // 人物 - 使用1.7m作为标准身高
            ObstacleType.PERSON -> {
                distance.coerceIn(0.5f, 15f)
            }
            // 交通工具
            ObstacleType.VEHICLE, ObstacleType.BUS, ObstacleType.TRUCK -> {
                distance.coerceIn(1f, 30f)
            }
            // 红绿灯等悬空物体
            ObstacleType.TRAFFIC_LIGHT -> {
                distance.coerceIn(1f, 50f)
            }
            else -> {
                distance.coerceIn(0.3f, 10f)
            }
        }

        return adjustedDistance
    }

    /**
     * 计算障碍物方向
     */
    private fun calculateDirection(centerX: Float, imageWidth: Float): Direction {
        val ratio = centerX / imageWidth
        return when {
            ratio < 0.15f -> Direction.LEFT
            ratio < 0.30f -> Direction.LEFT_FRONT
            ratio < 0.40f -> Direction.FRONT_LEFT
            ratio < 0.50f -> Direction.CENTER
            ratio < 0.60f -> Direction.CENTER
            ratio < 0.70f -> Direction.FRONT_RIGHT
            ratio < 0.85f -> Direction.RIGHT_FRONT
            else -> Direction.RIGHT
        }
    }

    /**
     * 非极大值抑制（NMS）- 去除重叠的检测框
     */
    private fun nonMaxSuppression(
        boxes: List<DetectedObstacle>,
        iouThreshold: Float = 0.45f
    ): List<DetectedObstacle> {
        if (boxes.isEmpty()) return emptyList()

        // 按置信度排序
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val keep = mutableListOf<DetectedObstacle>()

        while (sorted.isNotEmpty()) {
            val current = sorted.removeAt(0)
            keep.add(current)

            sorted.removeAll { box ->
                calculateIoU(current.boundingBox, box.boundingBox) > iouThreshold &&
                box.type == current.type // 只合并同类物体
            }
        }

        return keep
    }

    /**
     * 计算IoU（交并比）
     */
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

    /**
     * 使用ML Kit进行目标检测（TFLite不可用时的回退方案）
     */
    private suspend fun detectWithMlKit(bitmap: Bitmap): List<DetectedObstacle> {
        return withContext(Dispatchers.IO) {
            try {
                Timber.d("ML Kit: Starting detection on ${bitmap.width}x${bitmap.height} bitmap")
                
                val image = InputImage.fromBitmap(bitmap, 0)
                val detectedObjects = mlKitDetector?.process(image)?.await()
                
                Timber.d("ML Kit: Detected ${detectedObjects?.size ?: 0} objects")
                
                if (detectedObjects.isNullOrEmpty()) {
                    Timber.d("ML Kit: No objects detected")
                    return@withContext emptyList()
                }
                
                val results = mutableListOf<DetectedObstacle>()

                detectedObjects.forEach { obj ->
                    val labels = obj.labels
                    val bounds = obj.boundingBox
                    
                    Timber.d("ML Kit: Object detected - bounds: left=${bounds.left}, top=${bounds.top}, right=${bounds.right}, bottom=${bounds.bottom}")
                    Timber.d("ML Kit: Object has ${labels.size} labels")
                    
                    if (labels.isEmpty()) {
                        Timber.w("ML Kit: Object has no labels (classification may not be enabled)")
                        // 如果没有标签，使用通用障碍物类型
                        val distance = estimateDistance(ObstacleType.OBSTACLE, bounds.height().toFloat(), bitmap.height.toFloat())
                        val direction = calculateDirection(bounds.centerX().toFloat(), bitmap.width.toFloat())
                        
                        results.add(DetectedObstacle(
                            type = ObstacleType.OBSTACLE,
                            confidence = 0.5f,
                            distance = distance,
                            direction = direction,
                            boundingBox = BoundingBox(
                                left = bounds.left.toFloat() / bitmap.width,
                                top = bounds.top.toFloat() / bitmap.height,
                                right = bounds.right.toFloat() / bitmap.width,
                                bottom = bounds.bottom.toFloat() / bitmap.height
                            )
                        ))
                        return@forEach
                    }
                    
                    labels.forEach { label ->
                        Timber.d("ML Kit: Label='${label.text}', confidence=${label.confidence}, index=${label.index}")
                    }
                    
                    val label = labels.firstOrNull()?.text ?: "unknown"
                    val obstacleType = mlKitLabelToObstacle(label)
                    
                    if (obstacleType == null) {
                        Timber.w("ML Kit: No mapping for label '$label', using OBSTACLE type")
                        // 如果标签没有映射，使用通用障碍物类型
                        val distance = estimateDistance(ObstacleType.OBSTACLE, bounds.height().toFloat(), bitmap.height.toFloat())
                        val direction = calculateDirection(bounds.centerX().toFloat(), bitmap.width.toFloat())
                        
                        results.add(DetectedObstacle(
                            type = ObstacleType.OBSTACLE,
                            confidence = labels.firstOrNull()?.confidence ?: 0.5f,
                            distance = distance,
                            direction = direction,
                            boundingBox = BoundingBox(
                                left = bounds.left.toFloat() / bitmap.width,
                                top = bounds.top.toFloat() / bitmap.height,
                                right = bounds.right.toFloat() / bitmap.width,
                                bottom = bounds.bottom.toFloat() / bitmap.height
                            )
                        ))
                        return@forEach
                    }
                    
                    val confidence = labels.firstOrNull()?.confidence ?: 0f
                    
                    // 降低置信度阈值以便测试（从 0.5 降到 0.3）
                    if (confidence < 0.3f) {
                        Timber.d("ML Kit: Confidence $confidence below threshold 0.3, skipping")
                        return@forEach
                    }

                    val distance = estimateDistance(obstacleType, bounds.height().toFloat(), bitmap.height.toFloat())
                    val direction = calculateDirection(bounds.centerX().toFloat(), bitmap.width.toFloat())

                    Timber.i("ML Kit: Detected ${obstacleType.chineseName} at ${distance}m, direction=${direction.getChineseName()}, confidence=$confidence")

                    results.add(DetectedObstacle(
                        type = obstacleType,
                        confidence = confidence,
                        distance = distance,
                        direction = direction,
                        boundingBox = BoundingBox(
                            left = bounds.left.toFloat() / bitmap.width,
                            top = bounds.top.toFloat() / bitmap.height,
                            right = bounds.right.toFloat() / bitmap.width,
                            bottom = bounds.bottom.toFloat() / bitmap.height
                        )
                    ))
                }

                val finalResults = nonMaxSuppression(results)
                Timber.i("ML Kit: Returning ${finalResults.size} obstacles after NMS")
                finalResults
            } catch (e: Exception) {
                Timber.e(e, "ML Kit检测失败")
                emptyList()
            }
        }
    }

    // ============ ML Kit标签到ObstacleType的映射 ============
    // ML Kit 支持的完整标签列表（基于 COCO 数据集）
    private val mlKitLabelMap = mapOf(
        // 人物类
        "Person" to ObstacleType.PERSON,
        
        // 交通工具类（对视障用户威胁较大）
        "Bicycle" to ObstacleType.BICYCLE,
        "Car" to ObstacleType.VEHICLE,
        "Motorcycle" to ObstacleType.MOTORCYCLE,
        "Airplane" to ObstacleType.VEHICLE,
        "Bus" to ObstacleType.BUS,
        "Train" to ObstacleType.VEHICLE,
        "Truck" to ObstacleType.TRUCK,
        "Boat" to ObstacleType.VEHICLE,
        
        // 交通设施
        "Traffic Light" to ObstacleType.TRAFFIC_LIGHT,
        "Fire Hydrant" to ObstacleType.PILLAR,
        "Stop Sign" to ObstacleType.TRAFFIC_SIGN,
        "Parking Meter" to ObstacleType.PILLAR,
        
        // 街道设施
        "Bench" to ObstacleType.BENCH,
        
        // 动物类（可能出现在道路上）
        "Bird" to ObstacleType.OBSTACLE,
        "Cat" to ObstacleType.OBSTACLE,
        "Dog" to ObstacleType.OBSTACLE,
        "Horse" to ObstacleType.OBSTACLE,
        "Sheep" to ObstacleType.OBSTACLE,
        "Cow" to ObstacleType.OBSTACLE,
        "Elephant" to ObstacleType.OBSTACLE,
        "Bear" to ObstacleType.OBSTACLE,
        "Zebra" to ObstacleType.OBSTACLE,
        "Giraffe" to ObstacleType.OBSTACLE,
        
        // 个人物品
        "Backpack" to ObstacleType.BACKPACK,
        "Umbrella" to ObstacleType.UMBRELLA,
        "Handbag" to ObstacleType.HANDBAG,
        "Tie" to ObstacleType.OBSTACLE,
        "Suitcase" to ObstacleType.SUITCASE,
        
        // 运动器材
        "Frisbee" to ObstacleType.OBSTACLE,
        "Skis" to ObstacleType.OBSTACLE,
        "Snowboard" to ObstacleType.OBSTACLE,
        "Sports Ball" to ObstacleType.OBSTACLE,
        "Kite" to ObstacleType.OBSTACLE,
        "Baseball Bat" to ObstacleType.OBSTACLE,
        "Baseball Glove" to ObstacleType.OBSTACLE,
        "Skateboard" to ObstacleType.OBSTACLE,
        "Surfboard" to ObstacleType.OBSTACLE,
        "Tennis Racket" to ObstacleType.OBSTACLE,
        
        // 餐具和容器
        "Bottle" to ObstacleType.BOTTLE,
        "Wine Glass" to ObstacleType.BOTTLE,
        "Cup" to ObstacleType.BOTTLE,
        "Fork" to ObstacleType.OBSTACLE,
        "Knife" to ObstacleType.OBSTACLE,
        "Spoon" to ObstacleType.OBSTACLE,
        "Bowl" to ObstacleType.OBSTACLE,
        
        // 食物
        "Banana" to ObstacleType.OBSTACLE,
        "Apple" to ObstacleType.OBSTACLE,
        "Sandwich" to ObstacleType.OBSTACLE,
        "Orange" to ObstacleType.OBSTACLE,
        "Broccoli" to ObstacleType.OBSTACLE,
        "Carrot" to ObstacleType.OBSTACLE,
        "Hot Dog" to ObstacleType.OBSTACLE,
        "Pizza" to ObstacleType.OBSTACLE,
        "Donut" to ObstacleType.OBSTACLE,
        "Cake" to ObstacleType.OBSTACLE,
        
        // 家具类
        "Chair" to ObstacleType.CHAIR,
        "Couch" to ObstacleType.SOFA,
        "Potted Plant" to ObstacleType.POTTTED_PLANT,
        "Bed" to ObstacleType.BED,
        "Dining Table" to ObstacleType.TABLE,
        "Toilet" to ObstacleType.OBSTACLE,
        
        // 电子设备
        "TV" to ObstacleType.OBSTACLE,
        "Laptop" to ObstacleType.LAPTOP,
        "Mouse" to ObstacleType.OBSTACLE,
        "Remote" to ObstacleType.OBSTACLE,
        "Keyboard" to ObstacleType.OBSTACLE,
        "Cell Phone" to ObstacleType.PHONE,
        "Microwave" to ObstacleType.OBSTACLE,
        "Oven" to ObstacleType.OBSTACLE,
        "Toaster" to ObstacleType.OBSTACLE,
        "Sink" to ObstacleType.OBSTACLE,
        "Refrigerator" to ObstacleType.OBSTACLE,
        
        // 其他物品
        "Book" to ObstacleType.OBSTACLE,
        "Clock" to ObstacleType.OBSTACLE,
        "Vase" to ObstacleType.POTTTED_PLANT,
        "Scissors" to ObstacleType.OBSTACLE,
        "Teddy Bear" to ObstacleType.OBSTACLE,
        "Hair Drier" to ObstacleType.OBSTACLE,
        "Toothbrush" to ObstacleType.OBSTACLE
    )

    private fun mlKitLabelToObstacle(label: String): ObstacleType? = mlKitLabelMap[label]

    /**
     * 检查模型是否已加载
     */
    fun isModelLoaded(): Boolean = isLoaded
}
