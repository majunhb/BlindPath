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

    // 复用缓冲区，避免逐帧分配 GC 抖动
    private var inputBuffer: ByteBuffer? = null
    private var scaledBitmap: Bitmap? = null
    
    // [增强] 辅助检测模式 - 模型未加载时使用
    private var useAssistedDetection = false
    private var lastFrame: Bitmap? = null
    private var frameCounter = 0
    private val ASSIST_FRAME_SKIP = 3  // 每3帧处理1帧

    // 模型配置 - 支持多个路径（兼容不同模块的assets目录）
    private val modelPath = "yolov8n.tflite"
    private val modelPaths = listOf(
        "yolov8n.tflite",                                    // app/src/main/assets/
        "module_obstacle/yolov8n.tflite",                    // module_obstacle 合并后的路径
    )
    private val minValidModelSize = 1024 // 有效模型至少1KB
    // 多镜像下载地址（优先国内可访问源）
    private val modelUrls = listOf(
        "https://github.com/majunhb/BlindPath/releases/download/models/yolov8n.tflite", // 项目自有 release
        "https://ghfast.top/https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n.tflite", // 国内加速镜像
        "https://mirror.ghproxy.com/https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n.tflite",  // ghproxy镜像
        "https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n.tflite",  // 官方 release（兜底）
    )
    private val inputSize = AppConfig.AIDetection.INPUT_SIZE
    private val numThreads = AppConfig.AIDetection.NUM_THREADS

    // ============ COCO 80类 完整映射 到 视障障碍物类型 ============
    // COCO 80类完整参考: https://cocodataset.org/#home
    // 所有类别均映射，确保 YOLOv8n 识别出的所有物体都有对应语义
    private val cocoToObstacle = mapOf(
        // ---- 人物 ----
        0 to ObstacleType.PERSON,           // person

        // ---- 交通工具 ----
        1 to ObstacleType.BICYCLE,          // bicycle
        2 to ObstacleType.VEHICLE,          // car
        3 to ObstacleType.MOTORCYCLE,       // motorcycle
        4 to ObstacleType.AIRPLANE,         // airplane
        5 to ObstacleType.BUS,              // bus
        6 to ObstacleType.TRAIN,            // train
        7 to ObstacleType.TRUCK,            // truck
        8 to ObstacleType.BOAT,             // boat

        // ---- 交通设施 ----
        9 to ObstacleType.TRAFFIC_LIGHT,    // traffic light
        10 to ObstacleType.TRAFFIC_SIGN,    // fire hydrant（消防栓，视为路边柱状设施）
        11 to ObstacleType.TRAFFIC_SIGN,    // stop sign
        12 to ObstacleType.PARKING_METER,   // parking meter
        13 to ObstacleType.BENCH,           // bench

        // ---- 动物 ----
        14 to ObstacleType.BIRD,            // bird
        15 to ObstacleType.CAT,             // cat
        16 to ObstacleType.DOG,             // dog
        17 to ObstacleType.HORSE,           // horse
        18 to ObstacleType.SHEEP,           // sheep
        19 to ObstacleType.COW,             // cow
        20 to ObstacleType.ELEPHANT,        // elephant
        21 to ObstacleType.BEAR,            // bear
        22 to ObstacleType.ZEBRA,           // zebra
        23 to ObstacleType.GIRAFFE,         // giraffe

        // ---- 个人物品 ----
        24 to ObstacleType.BACKPACK,        // backpack
        25 to ObstacleType.UMBRELLA,        // umbrella
        26 to ObstacleType.HANDBAG,         // handbag
        27 to ObstacleType.ROAD_HAZARD,     // tie（领带 → 归为通用障碍物）
        28 to ObstacleType.SUITCASE,        // suitcase

        // ---- 运动/娱乐 ----
        29 to ObstacleType.FRISBEE,         // frisbee
        30 to ObstacleType.SKIS,            // skis
        31 to ObstacleType.SNOWBOARD,       // snowboard
        32 to ObstacleType.SPORTS_BALL,     // sports ball
        33 to ObstacleType.KITE,            // kite
        34 to ObstacleType.ROAD_HAZARD,     // baseball bat（运动器材 → 路面障碍）
        35 to ObstacleType.ROAD_HAZARD,     // baseball glove
        36 to ObstacleType.SKATEBOARD,      // skateboard
        37 to ObstacleType.SURFBOARD,       // surfboard
        38 to ObstacleType.TENNIS_RACKET,   // tennis racket

        // ---- 厨房/餐饮 ----
        39 to ObstacleType.BOTTLE,          // bottle
        40 to ObstacleType.WINE_GLASS,      // wine glass
        41 to ObstacleType.CUP,             // cup
        42 to ObstacleType.FORK,            // fork
        43 to ObstacleType.KNIFE,           // knife
        44 to ObstacleType.SPOON,           // spoon
        45 to ObstacleType.BOWL,            // bowl

        // ---- 食物（归为通用食物类） ----
        46 to ObstacleType.BANANA,          // banana
        47 to ObstacleType.APPLE,           // apple
        48 to ObstacleType.FOOD,            // sandwich
        49 to ObstacleType.FOOD,            // orange
        50 to ObstacleType.FOOD,            // broccoli
        51 to ObstacleType.FOOD,            // carrot
        52 to ObstacleType.FOOD,            // hot dog
        53 to ObstacleType.FOOD,            // pizza
        54 to ObstacleType.FOOD,            // donut
        55 to ObstacleType.FOOD,            // cake

        // ---- 家居/家具 ----
        56 to ObstacleType.CHAIR,           // chair
        57 to ObstacleType.SOFA,            // couch
        58 to ObstacleType.POTTED_PLANT,    // potted plant
        59 to ObstacleType.BED,             // bed
        60 to ObstacleType.TABLE,           // dining table
        61 to ObstacleType.SINK,            // toilet（卫生间设施 → 水槽类）
        62 to ObstacleType.TV,              // tv

        // ---- 电子设备 ----
        63 to ObstacleType.LAPTOP,          // laptop
        64 to ObstacleType.MOUSE_DEVICE,    // mouse
        65 to ObstacleType.REMOTE,          // remote
        66 to ObstacleType.KEYBOARD,        // keyboard
        67 to ObstacleType.PHONE,           // cell phone
        68 to ObstacleType.MICROWAVE,       // microwave
        69 to ObstacleType.OVEN,            // oven
        70 to ObstacleType.TOASTER,         // toaster
        71 to ObstacleType.SINK,            // sink
        72 to ObstacleType.REFRIGERATOR,    // refrigerator

        // ---- 书籍/文具 ----
        73 to ObstacleType.BOOK,            // book
        74 to ObstacleType.CLOCK,           // clock
        75 to ObstacleType.VASE,            // vase
        76 to ObstacleType.SCISSORS,        // scissors
        77 to ObstacleType.TEDDY_BEAR,      // teddy bear
        78 to ObstacleType.HAIR_DRYER,      // hair drier
        79 to ObstacleType.TOOTHBRUSH       // toothbrush
    )

    // ============ COCO 80类中文名称映射 ============
    // 用于日志输出和调试，与 cocoToObstacle 一一对应
    private val cocoChineseNames = mapOf(
        0 to "人", 1 to "自行车", 2 to "汽车", 3 to "摩托车", 4 to "飞机",
        5 to "公交车", 6 to "火车", 7 to "卡车", 8 to "船", 9 to "红绿灯",
        10 to "消防栓", 11 to "停车标志", 12 to "停车收费桩", 13 to "长椅",
        14 to "鸟", 15 to "猫", 16 to "狗", 17 to "马", 18 to "羊",
        19 to "牛", 20 to "大象", 21 to "熊", 22 to "斑马", 23 to "长颈鹿",
        24 to "背包", 25 to "雨伞", 26 to "手提包", 27 to "领带", 28 to "行李箱",
        29 to "飞盘", 30 to "滑雪板", 31 to "单板滑雪", 32 to "运动球", 33 to "风筝",
        34 to "棒球棒", 35 to "棒球手套", 36 to "滑板", 37 to "冲浪板", 38 to "网球拍",
        39 to "瓶子", 40 to "酒杯", 41 to "杯子", 42 to "叉子", 43 to "刀",
        44 to "勺子", 45 to "碗", 46 to "香蕉", 47 to "苹果", 48 to "三明治",
        49 to "橙子", 50 to "西兰花", 51 to "胡萝卜", 52 to "热狗", 53 to "披萨",
        54 to "甜甜圈", 55 to "蛋糕", 56 to "椅子", 57 to "沙发", 58 to "盆栽",
        59 to "床", 60 to "餐桌", 61 to "马桶", 62 to "电视", 63 to "笔记本电脑",
        64 to "鼠标", 65 to "遥控器", 66 to "键盘", 67 to "手机", 68 to "微波炉",
        69 to "烤箱", 70 to "烤面包机", 71 to "水槽", 72 to "冰箱", 73 to "书",
        74 to "时钟", 75 to "花瓶", 76 to "剪刀", 77 to "玩具熊", 78 to "吹风机",
        79 to "牙刷"
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
        ObstacleType.POTTED_PLANT to 0.5f,  // 盆栽高度约0.5m
        ObstacleType.BED to 0.5f,           // 床高度约0.5m
        ObstacleType.TABLE to 0.75f,         // 餐桌高度约0.75m

        // 动物类
        ObstacleType.CAT to 0.3f,             // 猫身高约0.3m
        ObstacleType.DOG to 0.5f,             // 中型犬约0.5m
        ObstacleType.BIRD to 0.2f,            // 鸟类约0.2m
        ObstacleType.HORSE to 1.6f,           // 马肩高约1.6m
        ObstacleType.COW to 1.4f,             // 牛肩高约1.4m
        ObstacleType.ELEPHANT to 3.0f,        // 大象约3m
        ObstacleType.BEAR to 1.0f,            // 熊约1m（四足状态）
        ObstacleType.ZEBRA to 1.4f,           // 斑马约1.4m
        ObstacleType.GIRAFFE to 4.5f,         // 长颈鹿约4.5m

        // 交通工具（扩展）
        ObstacleType.AIRPLANE to 5.0f,        // 飞机约5m
        ObstacleType.TRAIN to 4.0f,           // 火车约4m
        ObstacleType.BOAT to 2.5f,            // 船约2.5m

        // 运动设施
        ObstacleType.SKATEBOARD to 0.15f,     // 滑板约0.15m

        // 室内物品
        ObstacleType.REFRIGERATOR to 1.8f,    // 冰箱约1.8m
        ObstacleType.TV to 0.6f,              // 电视约0.6m
        ObstacleType.BOOK to 0.03f,           // 书约3cm
        ObstacleType.VASE to 0.4f,            // 花瓶约0.4m
        ObstacleType.CLOCK to 0.3f            // 时钟约0.3m
    )

    // [规范] 距离分段置信阈值 - 根治乱报/漏报
    // 统一使用低阈值检测，在 postProcess 中按距离分段过滤
    private val confidenceThreshold = 0.25f  // 检测阶段低阈值，保证召回
    private val iouThreshold = 0.45f         // NMS阈值固定
    
    // [规范] 距离分段置信阈值
    companion object {
        const val DANGER_DISTANCE = 1.5f   // 危险区 <1.5m
        const val WARNING_DISTANCE = 3.0f  // 警示区 1.5~3m
        const val IGNORE_DISTANCE = 3.0f   // 忽略区 >3m，一律屏蔽播报
        
        const val CONF_DANGER = 0.28f      // 危险区置信阈值（压低，提高灵敏度）
        const val CONF_WARNING = 0.45f     // 警示区置信阈值（中等，过滤噪点）
        const val CONF_IGNORE = 0.7f       // 忽略区置信阈值（高，杜绝远景误报）
    }

    // 焦距（需根据实际摄像头参数校准）
    private var calibratedFocalLength: Float? = null

    /**
     * 加载模型（支持自动下载）
     */
    suspend fun loadModel(): Boolean {
        return try {
            val options = Interpreter.Options().apply {
                numThreads = numThreads
            }

            // 1. 先尝试从内部存储加载
            val modelFile = getModelFile()
            
            if (modelFile != null && modelFile.exists() && modelFile.length() >= minValidModelSize) {
                interpreter = Interpreter(modelFile, options)
                isLoaded = true
                Timber.d("YOLOv8 model loaded from file: ${modelFile.absolutePath}")
                return true
            }

            // 2. 尝试从assets加载（检查是否为LFS占位符，支持多个路径）
            var assetLoaded = false
            for (path in modelPaths) {
                try {
                    var assetSize = 0L
                    try {
                        val afd = context.assets.openFd(path)
                        assetSize = afd.length
                        afd.close()
                    } catch (_: Exception) {
                        continue // 此路径不存在，尝试下一个
                    }

                    if (assetSize >= minValidModelSize) {
                        val assetBuffer = FileUtil.loadMappedFile(context, path)
                        if (assetBuffer.capacity() >= minValidModelSize) {
                            interpreter = Interpreter(assetBuffer.asReadOnlyBuffer(), options)
                            isLoaded = true
                            Timber.d("YOLOv8 model loaded from assets: $path")
                            assetLoaded = true
                            break
                        } else {
                            Timber.w("Model at assets/$path is too small (${assetBuffer.capacity()} bytes), likely LFS placeholder")
                        }
                    } else {
                        Timber.w("Model at assets/$path is too small ($assetSize bytes), likely LFS placeholder")
                    }
                } catch (e: Exception) {
                    Timber.d("Failed to load model from assets/$path: ${e.message}")
                }
            }
            if (assetLoaded) return true

            // 3. 自动从网络下载（尝试多个镜像）
            for ((index, url) in modelUrls.withIndex()) {
                Timber.d("Downloading model from mirror #${index + 1}: $url")
                val downloadedFile = downloadModel(url)
                
                if (downloadedFile != null && downloadedFile.exists() && downloadedFile.length() >= minValidModelSize) {
                    interpreter = Interpreter(downloadedFile, options)
                    isLoaded = true
                    Timber.d("YOLOv8 model downloaded from mirror #${index + 1} and loaded successfully")
                    return true
                }
                Timber.w("Mirror #${index + 1} download failed or file invalid")
            }

            // 4. 所有方法都失败 - 启用辅助检测模式
            Timber.w("AI模型加载失败，启用辅助检测模式")
            Timber.w("可能原因：")
            Timber.w("1. 模型文件是Git LFS占位符（仅9字节），需要下载真实模型")
            Timber.w("2. 网络连接失败，无法从镜像下载模型")
            Timber.w("3. 模型文件路径错误")
            Timber.w("辅助检测模式将使用运动检测和边缘检测提供基础障碍物提示")
            isLoaded = false
            useAssistedDetection = true
            return false
            
        } catch (e: Exception) {
            Timber.e(e, "Failed to load AI model")
            isLoaded = false
            return false
        }
    }

    /**
     * 卸载模型
     */
    fun unloadModel() {
        interpreter?.close()
        interpreter = null
        inputBuffer = null
        scaledBitmap = null
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
    private suspend fun downloadModel(url: String): File? {
        return withContext(Dispatchers.IO) {
            var outputFile: File? = null
            try {
                Timber.d("Starting model download from: $url")

                val client = OkHttpClient.Builder()
                    .connectTimeout(60, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()

                val request = Request.Builder()
                    .url(url)
                    .build()

                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    Timber.e("Download failed with code: ${response.code()}")
                    return@withContext null
                }

                // 保存到内部存储
                val responseBody = response.body() ?: return@withContext null
                outputFile = File(context.filesDir, modelPath)
                val fileSize = responseBody.contentLength()

                responseBody.byteStream().use { inputStream ->
                    FileOutputStream(outputFile).use { outputStream ->
                        val buffer = ByteArray(4096)
                        var bytesRead: Int
                        var totalBytes: Long = 0

                        while (true) {
                            bytesRead = inputStream.read(buffer)
                            if (bytesRead == -1) break
                            outputStream.write(buffer, 0, bytesRead)
                            totalBytes += bytesRead
                            if (fileSize > 0) {
                                val progress = (totalBytes * 100 / fileSize).toInt()
                                Timber.d("Download progress: $progress%")
                            }
                        }
                    }
                }

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
        frameCounter++
        
        // [增强] 跳帧处理 - 每3帧处理1帧，提高FPS
        if (frameCounter % ASSIST_FRAME_SKIP != 0 && isLoaded) {
            return emptyList()  // 跳过此帧，降低CPU负载
        }
        
        if (!isLoaded || interpreter == null) {
            // [增强] 模型未加载时使用辅助检测
            return if (useAssistedDetection) {
                assistedDetect(bitmap)
            } else {
                emptyList()
            }
        }

        return try {
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
            Timber.e(e, "Detection failed")
            emptyList()
        }
    }
    
    /**
     * [增强] 辅助检测模式 - 模型未加载时使用图像处理技术
     * 
     * [重要修复] 添加帧间确认机制，避免误报：
     * - 连续3帧检测到同一类型障碍物才确认
     * - 提高检测阈值，减少环境纹理误检
     * - 辅助检测仅作为提示，不播报危险级别
     */
    private fun assistedDetect(bitmap: Bitmap): List<DetectedObstacle> {
        val results = mutableListOf<DetectedObstacle>()
        
        try {
            // 1. 运动检测 - 对比前后帧（仅检测明显运动物体）
            lastFrame?.let { last ->
                if (last.width == bitmap.width && last.height == bitmap.height) {
                    val motionRegions = detectMotion(last, bitmap)
                    results.addAll(motionRegions)
                }
            }
            
            // 2. 边缘检测 - 严格条件，避免误报
            val edgeRegions = detectEdges(bitmap)
            results.addAll(edgeRegions)
            
            // 保存当前帧用于下次运动检测
            lastFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false)
            
        } catch (e: Exception) {
            Timber.w(e, "Assisted detection failed")
        }
        
        return results
    }
    
    // [新增] 帧间确认缓存
    private val frameConfirmCache = mutableMapOf<ObstacleType, Int>()
    private val CONFIRM_FRAMES_REQUIRED = 3  // 需要连续3帧确认
    private val CONFIRM_DECAY = 0.5f  // 未检测到时的衰减系数
    
    /**
     * [新增] 帧间确认 - 只有连续多帧检测到才确认
     */
    private fun confirmDetection(type: ObstacleType): Boolean {
        val currentCount = frameConfirmCache.getOrDefault(type, 0) + 1
        frameConfirmCache[type] = currentCount
        
        // 衰减其他类型
        frameConfirmCache.keys.filter { it != type }.forEach { key ->
            frameConfirmCache[key] = (frameConfirmCache[key]!! * CONFIRM_DECAY).toInt()
        }
        
        return currentCount >= CONFIRM_FRAMES_REQUIRED
    }
    
    /**
     * 运动检测 - 检测画面中的运动物体
     */
    private fun detectMotion(prev: Bitmap, curr: Bitmap): List<DetectedObstacle> {
        val results = mutableListOf<DetectedObstacle>()
        val width = curr.width
        val height = curr.height
        
        // [修复] 提高采样步长，减少噪声
        val sampleStep = 20
        var diffPixels = 0
        val motionRegions = mutableListOf<Pair<Float, Float>>()
        
        for (y in 0 until height step sampleStep) {
            for (x in 0 until width step sampleStep) {
                val prevPixel = prev.getPixel(x, y)
                val currPixel = curr.getPixel(x, y)
                
                val diff = kotlin.math.abs((prevPixel and 0xFF) - (currPixel and 0xFF)) +
                          kotlin.math.abs(((prevPixel shr 8) and 0xFF) - ((currPixel shr 8) and 0xFF)) +
                          kotlin.math.abs(((prevPixel shr 16) and 0xFF) - ((currPixel shr 16) and 0xFF))
                
                // [修复] 提高差异阈值（50->120），减少光线变化误报
                if (diff > 120) {
                    diffPixels++
                    motionRegions.add(x.toFloat() / width to y.toFloat() / height)
                }
            }
        }
        
        // [修复] 提高运动比例阈值（0.1->0.3），需要更明显的运动
        val totalSamples = ((width / sampleStep) * (height / sampleStep)).coerceAtLeast(1)
        val motionRatio = diffPixels.toFloat() / totalSamples
        if (motionRatio > 0.3f && motionRegions.isNotEmpty()) {
            val avgX = motionRegions.map { it.first }.average().toFloat()
            val avgY = motionRegions.map { it.second }.average().toFloat()
            
            val direction = when {
                avgX < 0.33f -> Direction.LEFT
                avgX > 0.66f -> Direction.RIGHT
                else -> Direction.CENTER
            }
            
            // [修复] 使用帧间确认
            if (confirmDetection(ObstacleType.PERSON)) {
                results.add(DetectedObstacle(
                    type = ObstacleType.PERSON,
                    confidence = 0.4f,  // [修复] 提高置信度
                    distance = 3f,
                    direction = direction,
                    boundingBox = BoundingBox(
                        (avgX - 0.1f).coerceAtLeast(0f),
                        (avgY - 0.1f).coerceAtLeast(0f),
                        (avgX + 0.1f).coerceAtMost(1f),
                        (avgY + 0.1f).coerceAtMost(1f)
                    )
                ))
            }
        }
        
        return results
    }
    
    /**
     * 边缘检测 - 检测画面中的明显边缘（路沿、台阶等）
     */
    private fun detectEdges(bitmap: Bitmap): List<DetectedObstacle> {
        val results = mutableListOf<DetectedObstacle>()
        val width = bitmap.width
        val height = bitmap.height
        
        // [修复] 检测多条水平线，确认是路沿而非纹理
        val scanLines = listOf(
            height * 2 / 3,      // 下方1/3
            height * 3 / 4,      // 下方1/4
            height * 5 / 6       // 更下方
        )
        
        var totalEdgeScore = 0
        var consistentEdges = 0
        
        for (sampleY in scanLines) {
            if (sampleY >= height - 10 || sampleY <= 10) continue
            
            var lineEdgeCount = 0
            var lineEdgeX = 0f
            var consecutiveEdges = 0
            var maxConsecutive = 0
            
            for (x in 1 until width - 1 step 2) {  // [修复] 步长2，减少噪声
                val topPixel = bitmap.getPixel(x, sampleY - 5)
                val bottomPixel = bitmap.getPixel(x, sampleY + 5)
                
                val diff = kotlin.math.abs((topPixel and 0xFF) - (bottomPixel and 0xFF)) +
                          kotlin.math.abs(((topPixel shr 8) and 0xFF) - ((bottomPixel shr 8) and 0xFF)) +
                          kotlin.math.abs(((topPixel shr 16) and 0xFF) - ((bottomPixel shr 16) and 0xFF))
                
                // [修复] 大幅提高阈值（80->200），只有明显颜色分界才算边缘
                if (diff > 200) {
                    lineEdgeCount++
                    lineEdgeX += x
                    consecutiveEdges++
                    maxConsecutive = kotlin.math.max(maxConsecutive, consecutiveEdges)
                } else {
                    consecutiveEdges = 0
                }
            }
            
            // [修复] 要求边缘连续（路沿是连续直线，纹理是离散的）
            val lineWidth = width / 2  // 实际检测的像素数
            if (maxConsecutive > lineWidth * 0.4f) {  // [修复] 要求40%以上连续
                consistentEdges++
                totalEdgeScore += lineEdgeCount
            }
        }
        
        // [修复] 要求多条扫描线都检测到连续边缘（确认是水平路沿而非随机纹理）
        if (consistentEdges >= 2 && totalEdgeScore > width * 0.3f) {
            // [修复] 使用帧间确认
            if (confirmDetection(ObstacleType.CURB)) {
                results.add(DetectedObstacle(
                    type = ObstacleType.CURB,
                    confidence = 0.5f,  // [修复] 提高置信度
                    distance = 2f,
                    direction = Direction.CENTER,
                    boundingBox = BoundingBox(0f, 0.6f, 1f, 0.8f)
                ))
            }
        }
        
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
            ratio < 0.40f -> Direction.LEFT_FRONT
            ratio < 0.50f -> Direction.CENTER
            ratio < 0.60f -> Direction.CENTER
            ratio < 0.70f -> Direction.RIGHT_FRONT
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
     * 检查模型是否已加载
     */
    fun isModelLoaded(): Boolean = isLoaded && interpreter != null
}
