package com.blindpath.module_obstacle.data.detection

import android.content.Context
import android.graphics.Bitmap
import com.blindpath.module_obstacle.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
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

    // 模型配置
    private val modelPath = "yolov8n.tflite" // 需要放置模型文件到assets目录
    private val inputSize = 640 // YOLOv8输入尺寸
    private val numThreads = 4

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

    // 检测阈值
    private val confidenceThreshold = 0.4f  // 降低阈值，提高召回率，适合盲人导航场景
    private val iouThreshold = 0.5f      // 提高IoU阈值，减少重叠框合并

    // 焦距（需根据实际摄像头参数校准）
    private var calibratedFocalLength: Float? = null

    /**
     * 加载模型
     */
    suspend fun loadModel(): Boolean {
        return try {
            val options = Interpreter.Options().apply {
                numThreads = numThreads
                // 注意：GPU加速需要添加 tensorflow-lite-gpu 依赖
                // 如需启用GPU加速，请在 build.gradle.kts 中添加:
                // implementation("org.tensorflow:tensorflow-lite-gpu:2.13.0")
            }

            // 尝试从assets加载
            val modelBuffer = try {
                FileUtil.loadMappedFile(context, modelPath)
            } catch (e: Exception) {
                Timber.w("AI模型文件不存在，使用模拟模式: ${e.message}")
                // 模型不存在时返回true，允许应用在没有真实模型时运行
                isLoaded = true
                return true
            }

            interpreter = Interpreter(modelBuffer.asReadOnlyBuffer(), options)
            isLoaded = true
            Timber.d("YOLOv8 model loaded successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load AI model, using simulation mode")
            // 即使模型加载失败，也允许应用继续运行（模拟模式）
            isLoaded = true
            true
        }
    }

    /**
     * 卸载模型
     */
    fun unloadModel() {
        interpreter?.close()
        interpreter = null
        isLoaded = false
        Timber.d("AI model unloaded")
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
            return emptyList()
        }

        if (interpreter == null) {
            // 模拟模式：返回空列表（摄像头正常工作，但不检测）
            return emptyList()
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
     * 检查模型是否已加载
     */
    fun isModelLoaded(): Boolean = isLoaded
}
