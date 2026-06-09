package com.blindpath.module_obstacle.data.detection

import android.content.Context
import android.graphics.Bitmap
import com.blindpath.base.config.AppConfig
import com.blindpath.module_obstacle.domain.model.BoundingBox
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.ObstacleType
import com.blindpath.module_obstacle.domain.model.PerceptionMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.locks.ReentrantReadWriteLock
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.read
import kotlin.concurrent.write
import kotlin.math.max
import kotlin.math.min

/**
 * 多模型AI目标检测器（Facade） - 支持室内/导航/场景三模式动态切换
 *
 * 架构设计（v5.0 拆分重构）：
 * - AIDetector: Facade，协调子组件完成检测流水线
 * - ObstacleClassifier: COCO分类、距离估算、方向判定
 * - AssistedDetector: 辅助检测（运动/边缘/ML Kit）
 * - ModelManager: TFLite模型生命周期管理
 * - 不同时加载多个模型（内存限制）
 * - 切换模式时释放旧模型、加载新模型
 * - 使用 ReentrantReadWriteLock 保证线程安全
 */
@Singleton
class AIDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ModelManager,
    private val assistedDetector: AssistedDetector,
    private val obstacleClassifier: ObstacleClassifier
) {

    /** 当前感知模式 */
    private var currentMode: PerceptionMode = PerceptionMode.NAVIGATION

    /** 当前模式的白名单过滤器 */
    private var currentWhitelist: Set<ObstacleType>? = null

    // 线程安全锁
    private val lock = ReentrantReadWriteLock()

    // 复用缓冲区
    private var inputBuffer: ByteBuffer? = null
    private var scaledBitmap: Bitmap? = null

    // 模型配置
    private val inputSize = AppConfig.AIDetection.INPUT_SIZE

    companion object {
        /** NMS IoU 阈值 */
        const val DETECTION_IOU_THRESHOLD = 0.45f

        /** 每类最大检测数量（同一类型保留的最多检测框数） */
        const val MAX_DETECTIONS_PER_CLASS = 5

        /** 最大检测距离（米），超过此距离的障碍物忽略 */
        const val MAX_DETECTION_DISTANCE = 15f
    }

    // ==================== 公开 Facade API ====================

    /**
     * 获取当前感知模式
     */
    fun getCurrentMode(): PerceptionMode = lock.read { currentMode }

    /**
     * 切换感知模式 - 自动卸载旧模型并加载新模型
     */
    suspend fun switchMode(mode: PerceptionMode): Boolean {
        lock.write {
            if (currentMode == mode && modelManager.isModelLoaded()) {
                Timber.d("Already in mode: $mode")
                return@write true
            }

            // 1. 卸载旧模型
            modelManager.unloadModel()

            // 2. 更新模式和白名单
            currentMode = mode
            currentWhitelist = when (mode) {
                PerceptionMode.INDOOR -> ObstacleClassifier.INDOOR_WHITELIST
                PerceptionMode.NAVIGATION -> ObstacleClassifier.NAVIGATION_WHITELIST
                PerceptionMode.SCENE -> ObstacleClassifier.SCENE_WHITELIST
                PerceptionMode.AUTO -> null
            }

            // 3. 重置加载尝试标志
            modelManager.resetLoadAttempt()

            Timber.d("Switched to mode: $mode, model: ${mode.modelFileName}")
        }

        // 4. 加载新模型（在锁外执行，避免阻塞）
        return modelManager.loadModel(currentMode.modelFileName)
    }

    /**
     * 加载模型（代理到 ModelManager）
     * 保持与旧 API 的兼容性，供 ObstacleRepositoryImpl 调用
     */
    suspend fun loadModel(): Boolean {
        return modelManager.loadModel(lock.read { currentMode.modelFileName })
    }

    /**
     * 卸载模型（代理到 ModelManager）
     */
    fun unloadModel() {
        modelManager.unloadModel()
        assistedDetector.releaseLastFrame()
    }

    /**
     * 模型是否已加载（代理到 ModelManager）
     */
    fun isModelLoaded(): Boolean = modelManager.isModelLoaded()

    /**
     * 是否启用了辅助检测（代理到 ModelManager）
     */
    fun isAssistedDetectionEnabled(): Boolean = modelManager.isAssistedDetectionEnabled()

    /**
     * 设置标定焦距（代理到 ObstacleClassifier）
     */
    fun setCalibratedFocalLength(focalLength: Float) {
        obstacleClassifier.calibratedFocalLength = focalLength
    }

    /**
     * 重置加载尝试标志（代理到 ModelManager）
     */
    fun resetLoadAttempt() {
        modelManager.resetLoadAttempt()
    }

    // ==================== 核心检测流水线 ====================

    /**
     * 检测障碍物 - 核心推理方法
     *
     * 优先级：
     * 1. 如果 TFLite 模型已加载，使用模型推理
     * 2. 否则，如果辅助检测启用，使用辅助检测
     * 3. 否则返回空列表
     *
     * @param bitmap 输入图像帧
     * @return 检测到的障碍物列表
     */
    suspend fun detect(bitmap: Bitmap): List<DetectedObstacle> {
        val loaded = modelManager.isModelLoaded()
        if (!loaded) {
            return if (modelManager.isAssistedDetectionEnabled()) {
                assistedDetector.assistedDetect(bitmap)
            } else {
                emptyList()
            }
        }

        return try {
            val inputBuffer = preprocessImage(bitmap)
            val outputBuffer = Array(1) {
                Array(ModelManager.NUM_OUTPUTS) {
                    FloatArray(ModelManager.NUM_ANCHORS)
                }
            }

            modelManager.getInterpreter()?.runForMultipleInputsOutputs(
                arrayOf<Any>(inputBuffer),
                mapOf(0 to outputBuffer)
            )

            postProcess(outputBuffer[0], bitmap.width, bitmap.height)
        } catch (e: Exception) {
            Timber.e(e, "Detection failed")
            emptyList()
        }
    }

    // ==================== 图像预处理 ====================

    /**
     * 图像预处理：缩放 + 归一化到 ByteBuffer
     *
     * 将任意尺寸的 Bitmap 缩放至 inputSize x inputSize，
     * 并转换为 Float32 归一化的 ByteBuffer（范围 0.0 ~ 1.0）
     */
    private fun preprocessImage(bitmap: Bitmap): ByteBuffer {
        val scaledBitmap = Bitmap.createScaledBitmap(bitmap, inputSize, inputSize, true)
        val byteBuffer = ByteBuffer.allocateDirect(
            1 * inputSize * inputSize * 3 * ModelManager.NUM_BYTES_PER_CHANNEL
        )
        byteBuffer.order(ByteOrder.nativeOrder())

        val pixels = IntArray(inputSize * inputSize)
        scaledBitmap.getPixels(pixels, 0, inputSize, 0, 0, inputSize, inputSize)

        for (pixel in pixels) {
            byteBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f) // R
            byteBuffer.putFloat(((pixel shr 8) and 0xFF) / 255.0f)  // G
            byteBuffer.putFloat((pixel and 0xFF) / 255.0f)           // B
        }
        byteBuffer.rewind()
        return byteBuffer
    }

    // ==================== 后处理 ====================

    /**
     * 后处理：解析模型输出 -> 障碍物列表
     *
     * 步骤：
     * 1. 遍历所有锚点，找最高分类别
     * 2. 置信度过滤（模式阈值 + 距离分段阈值）
     * 3. 白名单过滤
     * 4. 坐标映射与距离估算
     * 5. NMS 去重
     */
    private fun postProcess(
        output: Array<FloatArray>,
        imageWidth: Int,
        imageHeight: Int
    ): List<DetectedObstacle> {
        val results = mutableListOf<DetectedObstacle>()
        val whitelist = lock.read { currentWhitelist }
        val mode = lock.read { currentMode }

        for (i in 0 until ModelManager.NUM_ANCHORS) {
            // 找最高分类别
            var maxScore = 0f
            var maxClass = -1
            for (j in 4 until ModelManager.NUM_OUTPUTS) {
                val score = output[j][i]
                if (score > maxScore) {
                    maxScore = score
                    maxClass = j - 4 // COCO label id
                }
            }

            // 模式置信度阈值过滤
            if (maxScore < mode.confidenceThreshold) continue

            // COCO 分类
            val obstacleType = obstacleClassifier.classifyByCocoId(maxClass) ?: continue

            // 白名单过滤
            if (whitelist != null && obstacleType !in whitelist) continue

            // 坐标映射：归一化 -> 原图像素坐标
            val cx = output[0][i] / inputSize * imageWidth
            val cy = output[1][i] / inputSize * imageHeight
            val w = output[2][i] / inputSize * imageWidth
            val h = output[3][i] / inputSize * imageHeight

            val left = (cx - w / 2).coerceIn(0f, imageWidth.toFloat())
            val top = (cy - h / 2).coerceIn(0f, imageHeight.toFloat())
            val right = (cx + w / 2).coerceIn(0f, imageWidth.toFloat())
            val bottom = (cy + h / 2).coerceIn(0f, imageHeight.toFloat())

            // 距离估算
            val distance = obstacleClassifier.estimateDistance(
                obstacleType, h, imageHeight.toFloat()
            )

            // 距离分段置信度过滤
            val confThreshold = when {
                distance < ObstacleClassifier.DANGER_DISTANCE -> ObstacleClassifier.CONF_DANGER
                distance < ObstacleClassifier.WARNING_DISTANCE -> ObstacleClassifier.CONF_WARNING
                else -> ObstacleClassifier.CONF_IGNORE
            }
            if (maxScore < confThreshold) continue

            // 最大检测距离过滤
            if (distance > MAX_DETECTION_DISTANCE) continue

            results.add(DetectedObstacle(
                type = obstacleType,
                confidence = maxScore,
                distance = distance,
                direction = obstacleClassifier.calculateDirection(cx, imageWidth.toFloat()),
                boundingBox = BoundingBox(
                    left / imageWidth, top / imageHeight,
                    right / imageWidth, bottom / imageHeight
                )
            ))
        }

        return nonMaxSuppression(results, mode.nmsThreshold)
    }

    // ==================== NMS（非极大值抑制）====================

    /**
     * 非极大值抑制 - 去除同一物体的重复检测框
     *
     * @param boxes 候选检测结果
     * @param iouThreshold IoU 阈值，超过此值的框视为重复
     * @return 去重后的检测结果
     */
    private fun nonMaxSuppression(
        boxes: List<DetectedObstacle>,
        iouThreshold: Float
    ): List<DetectedObstacle> {
        if (boxes.isEmpty()) return emptyList()
        val sorted = boxes.sortedByDescending { it.confidence }.toMutableList()
        val keep = mutableListOf<DetectedObstacle>()
        while (sorted.isNotEmpty()) {
            val current = sorted.removeAt(0)
            keep.add(current)
            sorted.removeAll {
                calculateIoU(current.boundingBox, it.boundingBox) > iouThreshold
                        && it.type == current.type
            }
        }
        return keep
    }

    /**
     * 计算两个边界框的 IoU（交并比）
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
}