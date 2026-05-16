package com.blindpath.module_obstacle.data.detection

import android.content.Context
import android.graphics.Bitmap
import com.blindpath.module_obstacle.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import timber.log.Timber
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.MappedByteBuffer
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max
import kotlin.math.min

/**
 * 基于TensorFlow Lite的AI目标检测器
 * 使用YOLOv8模型进行端侧推理
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

    // 类别映射（COCO 80类，我们映射到视障相关类别）
    private val categories = mapOf(
        0 to ObstacleType.PERSON,        // 人
        1 to ObstacleType.OBSTACLE,      // 自行车
        2 to ObstacleType.VEHICLE,       // 汽车
        3 to ObstacleType.VEHICLE,       // 摩托车
        5 to ObstacleType.VEHICLE,       // 巴士
        7 to ObstacleType.TRUCK,         // 卡车
        11 to ObstacleType.PILLAR,       // 交通灯
        13 to ObstacleType.PILLAR,       // 火车
        14 to ObstacleType.VEHICLE,      // 船
        15 to ObstacleType.PERSON,       // 猫
        16 to ObstacleType.PERSON,       // 狗
        // ... 其他类别映射
        56 to ObstacleType.PERSON,       // 椅子
        57 to ObstacleType.PERSON,       // 沙发
        59 to ObstacleType.PILLAR,       // 盆栽
        60 to ObstacleType.STEP_UP,      // 床
        62 to ObstacleType.OBSTACLE       // 餐桌
    )

    // 检测阈值
    private val confidenceThreshold = 0.5f
    private val iouThreshold = 0.45f

    /**
     * 加载模型
     */
    suspend fun loadModel(): Boolean {
        return try {
            val options = Interpreter.Options().apply {
                numThreads = numThreads
            }

            // 尝试从assets加载
            val modelBuffer = try {
                FileUtil.loadMappedFile(context, modelPath)
            } catch (e: Exception) {
                Timber.e("Failed to load model from assets: ${e.message}")
                // 如果assets中没有模型，使用默认空模型（演示用）
                createDummyModel()
            }

            interpreter = Interpreter(modelBuffer!!.asReadOnlyBuffer(), options)
            isLoaded = true
            Timber.d("YOLOv8 model loaded successfully")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to load AI model")
            isLoaded = false
            false
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
     * 检测障碍物
     */
    suspend fun detect(bitmap: Bitmap): List<DetectedObstacle> {
        if (!isLoaded || interpreter == null) {
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
                    maxClass = j - 4 // 类别索引
                }
            }

            // 检查置信度
            if (maxScore < confidenceThreshold) continue

            // 获取类别映射
            val obstacleType = categories[maxClass] ?: ObstacleType.UNKNOWN

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
     * 这是一个简化的估算，实际需要根据摄像头参数进行更精确的校准
     */
    private fun estimateDistance(type: ObstacleType, pixelHeight: Float, imageHeight: Float): Float {
        // 假设已知的实际高度（米）
        val knownHeight = when (type) {
            ObstacleType.PERSON -> 1.7f
            ObstacleType.VEHICLE -> 1.5f
            ObstacleType.TRUCK -> 3.5f
            ObstacleType.STEP_UP, ObstacleType.STEP_DOWN -> 0.2f
            ObstacleType.CURB -> 0.15f
            ObstacleType.PILLAR -> 0.3f
            ObstacleType.ELECTRIC_POLE -> 0.2f
            ObstacleType.PIT -> 0.0f
            else -> 1.0f
        }

        // 焦距（需要根据实际摄像头参数校准，这里使用典型值）
        val focalLength = 800f

        // 估算距离 = 实际高度 * 焦距 / 像素高度
        val distance = if (pixelHeight > 0) {
            knownHeight * focalLength / pixelHeight
        } else {
            10f // 无法估算
        }

        // 限制范围 0.1m - 10m
        return distance.coerceIn(0.1f, 10f)
    }

    /**
     * 计算障碍物方向
     */
    private fun calculateDirection(centerX: Float, imageWidth: Float): Direction {
        val ratio = centerX / imageWidth
        return when {
            ratio < 0.25f -> Direction.LEFT
            ratio < 0.4f -> Direction.LEFT_FRONT
            ratio < 0.5f -> Direction.FRONT_LEFT
            ratio < 0.6f -> Direction.CENTER
            ratio < 0.75f -> Direction.FRONT_RIGHT
            ratio < 0.85f -> Direction.RIGHT_FRONT
            else -> Direction.RIGHT
        }
    }

    /**
     * 非极大值抑制
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
                calculateIoU(current.boundingBox, box.boundingBox) > iouThreshold
            }
        }

        return keep
    }

    /**
     * 计算IoU
     */
    private fun calculateIoU(a: BoundingBox, b: BoundingBox): Float {
        val interLeft = max(a.left, b.left)
        val interTop = max(a.top, b.top)
        val interRight = kotlin.math.min(a.right, b.right)
        val interBottom = kotlin.math.min(a.bottom, b.bottom)

        val interArea = kotlin.math.max(0f, interRight - interLeft) * kotlin.math.max(0f, interBottom - interTop)
        val aArea = (a.right - a.left) * (a.bottom - a.top)
        val bArea = (b.right - b.left) * (b.bottom - b.top)

        return interArea / (aArea + bArea - interArea)
    }

    /**
     * 创建占位模型（用于演示）
     */
    private fun createDummyModel(): MappedByteBuffer? {
        // 在实际项目中，这里应该加载真实的YOLOv8模型
        // 模型文件需要放置在 app/src/main/assets/yolov8n.tflite
        Timber.w("Using placeholder model - replace with real YOLOv8 model for production")
        return null
    }
}
