package com.blindpath.module_obstacle.data.detection

import android.content.Context
import android.graphics.Bitmap
import com.blindpath.module_obstacle.domain.model.BoundingBox
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.Direction
import com.blindpath.module_obstacle.domain.model.ObstacleType
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.objects.ObjectDetection
import com.google.mlkit.vision.objects.defaults.ObjectDetectorOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread
import kotlin.math.abs

/**
 * 辅助检测器 - 当TFLite模型不可用时提供回退检测能力
 *
 * 从 AIDetector 拆分出的单一职责组件：
 * - 运动检测（帧间差分）
 * - 边缘检测（水平边缘/台阶）
 * - ML Kit 回退检测
 * - 基于位置的简易距离估算
 *
 * 构造函数注入 ApplicationContext
 */
@Singleton
class AssistedDetector @Inject constructor(
    @ApplicationContext private val context: Context
) {

    /** 上一帧位图，用于运动检测 */
    private var lastFrame: Bitmap? = null

    /** 辅助检测帧计数器 */
    private var frameCounter = 0

    companion object {
        /** 辅助检测最大置信度 */
        const val ASSIST_CONFIDENCE = 0.5f

        /** 运动检测块大小（像素） */
        const val MOTION_BLOCK_SIZE = 40

        /** 运动检测像素变化阈值 */
        const val MOTION_CHANGE_PIXELS = 30
    }

    /**
     * 辅助检测入口 - 聚合运动检测、边缘检测和ML Kit结果
     *
     * @param bitmap 当前帧
     * @return 检测到的障碍物列表
     */
    fun assistedDetect(bitmap: Bitmap): List<DetectedObstacle> {
        frameCounter++
        val results = mutableListOf<DetectedObstacle>()
        try {
            lastFrame?.let { last ->
                if (last.width == bitmap.width && last.height == bitmap.height) {
                    results.addAll(detectMotion(last, bitmap))
                }
            }
            results.addAll(detectEdges(bitmap))
            // [修复] 墙壁检测
            results.addAll(detectWalls(bitmap))
            // ML Kit 回退检测在单独线程中运行，避免阻塞主流程
            val mlKitLatch = CountDownLatch(1)
            val mlKitResults = mutableListOf<DetectedObstacle>()
            thread {
                try { mlKitResults.addAll(detectWithMLKit(bitmap)) }
                finally { mlKitLatch.countDown() }
            }
            mlKitLatch.await(500, TimeUnit.MILLISECONDS)
            results.addAll(mlKitResults)

            // 保存当前帧
            lastFrame?.recycle()
            lastFrame = bitmap.copy(Bitmap.Config.ARGB_8888, false)
        } catch (e: Exception) {
            Timber.w(e, "Assisted detection failed")
        }
        return results
    }

    /**
     * 帧间运动检测 - 基于块匹配的像素差分
     *
     * 将图像分割为 MOTION_BLOCK_SIZE 像素的块，
     * 计算块内像素差异比例，超过阈值的视为运动区域。
     *
     * @param prev 上一帧
     * @param curr 当前帧
     * @return 运动检测结果
     */
    fun detectMotion(prev: Bitmap, curr: Bitmap): List<DetectedObstacle> {
        val results = mutableListOf<DetectedObstacle>()
        try {
            val width = minOf(prev.width, curr.width)
            val height = minOf(prev.height, curr.height)
            val blockSize = MOTION_BLOCK_SIZE
            val threshold = MOTION_CHANGE_PIXELS
            var motionBlocks = 0
            val motionRegions = mutableListOf<Pair<Int, Int>>()

            // 批量读取像素数据，避免逐像素 getPixel() 调用的开销
            val prevPixels = IntArray(width * height)
            val currPixels = IntArray(width * height)
            prev.getPixels(prevPixels, width, 0, 0, 0, width, height)
            curr.getPixels(currPixels, width, 0, 0, 0, width, height)

            for (by in 0 until height step blockSize) {
                for (bx in 0 until width step blockSize) {
                    var diff = 0
                    var count = 0
                    for (y in by until minOf(by + blockSize, height) step 4) {
                        for (x in bx until minOf(bx + blockSize, width) step 4) {
                            val idx = y * width + x
                            val p1 = prevPixels[idx]
                            val p2 = currPixels[idx]
                            val dr = abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF))
                            val dg = abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF))
                            val db = abs((p1 and 0xFF) - (p2 and 0xFF))
                            if (dr + dg + db > threshold * 2) diff++
                            count++
                        }
                    }
                    if (count > 0 && diff.toFloat() / count > 0.10f) {
                        motionBlocks++
                        motionRegions.add(Pair(bx + blockSize / 2, by + blockSize / 2))
                    }
                }
            }

            if (motionBlocks >= 2) {
                val avgX = motionRegions.map { it.first }.average().toFloat()
                val avgY = motionRegions.map { it.second }.average().toFloat()
                val distance = estimateDistanceFromPosition(avgX, avgY, width, height)

                results.add(DetectedObstacle(
                    type = ObstacleType.OBSTACLE,
                    confidence = minOf(ASSIST_CONFIDENCE, 0.3f + motionBlocks * 0.05f),
                    boundingBox = BoundingBox(0f, 0f, 0f, 0f),
                    distance = distance,
                    direction = estimateDirection(avgX, width)
                ))
            }
        } catch (e: Exception) {
            Timber.w(e, "Motion detection error")
        }
        return results
    }

    /**
     * 水平边缘检测 - 检测台阶、道牙等显著的横向边缘
     *
     * 仅扫描图像底部 1/3 区域，检测垂直方向上的像素突变，
     * 视作潜在的地面高度变化。
     *
     * @param bitmap 当前帧
     * @return 边缘检测结果
     */
    fun detectEdges(bitmap: Bitmap): List<DetectedObstacle> {
        val results = mutableListOf<DetectedObstacle>()
        try {
            val width = bitmap.width
            val height = bitmap.height
            val scanY = height * 2 / 3

            // 批量读取像素数据
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, width, 0, 0, 0, width, height)

            var edgeCount = 0
            var edgeY = 0
            for (y in scanY until height - 10 step 2) {
                for (x in 0 until width step 8) {
                    val idx1 = y * width + x
                    val idx2 = (y + 10) * width + x
                    val p1 = pixels[idx1]
                    val p2 = pixels[idx2]
                    val diff = abs(((p1 shr 16) and 0xFF) - ((p2 shr 16) and 0xFF)) +
                            abs(((p1 shr 8) and 0xFF) - ((p2 shr 8) and 0xFF)) +
                            abs((p1 and 0xFF) - (p2 and 0xFF))
                    if (diff > 80) {
                        edgeCount++
                        edgeY = y
                    }
                }
            }

            if (edgeCount > width / 8) {
                val distance = estimateDistanceFromPosition(
                    width / 2f, edgeY.toFloat(), width, height
                )
                results.add(DetectedObstacle(
                    type = ObstacleType.STEP_DOWN,
                    confidence = minOf(0.45f, 0.25f + edgeCount * 0.01f),
                    boundingBox = BoundingBox(0f, 0f, 0f, 0f),
                    distance = distance,
                    direction = Direction.CENTER
                ))
            }
        } catch (e: Exception) {
            Timber.w(e, "Edge detection error")
        }
        return results
    }

    /**
     * ML Kit Object Detection 回退检测
     *
     * 当 TFLite 模型不可用时，使用 Google ML Kit 作为补充检测手段。
     * 通过 CountDownLatch 实现同步等待（最多等待 3 秒）。
     *
     * @param bitmap 当前帧
     * @return ML Kit 检测结果
     */
    fun detectWithMLKit(bitmap: Bitmap): List<DetectedObstacle> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val options = ObjectDetectorOptions.Builder()
                .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
                .enableMultipleObjects()
                .build()

            val detector = ObjectDetection.getClient(options)
            val results = mutableListOf<DetectedObstacle>()
            val latch = CountDownLatch(1)

            detector.process(image)
                .addOnSuccessListener { detectedObjects ->
                    for (obj in detectedObjects) {
                        val labels = obj.labels
                        val category = if (labels.isNotEmpty()) labels[0].text ?: "" else ""
                        val obstacleType = when {
                            category.contains("Person", ignoreCase = true) -> ObstacleType.PERSON
                            else -> ObstacleType.OBSTACLE
                        }
                        val bounds = obj.boundingBox
                        val cx = (bounds.left + bounds.right) / 2f
                        val cy = (bounds.top + bounds.bottom) / 2f
                        val distance = estimateDistanceFromPosition(
                            cx, cy, bitmap.width, bitmap.height
                        )
                        results.add(DetectedObstacle(
                            type = obstacleType,
                            confidence = if (labels.isNotEmpty()) labels[0].confidence else 0.5f,
                            distance = distance,
                            direction = estimateDirection(cx, bitmap.width),
                            boundingBox = BoundingBox(
                                bounds.left.toFloat() / bitmap.width,
                                bounds.top.toFloat() / bitmap.height,
                                bounds.right.toFloat() / bitmap.width,
                                bounds.bottom.toFloat() / bitmap.height
                            )
                        ))
                    }
                    latch.countDown()
                }
                .addOnFailureListener { e ->
                    Timber.w(e, "ML Kit detection failed")
                    latch.countDown()
                }

            latch.await(3, TimeUnit.SECONDS)
            results
        } catch (e: Exception) {
            Timber.w(e, "ML Kit detection error")
            emptyList()
        }
    }

    /**
     * 基于物体在图像中的位置估算距离
     *
     * 假设：物体越靠近图像底部，距离越近。
     * 使用经验公式根据归一化 Y 坐标估算距离。
     *
     * @param x 物体中心 X 坐标
     * @param y 物体中心 Y 坐标
     * @param width 图像宽度
     * @param height 图像高度
     * @return 估算距离（米）
     */
    fun estimateDistanceFromPosition(x: Float, y: Float, width: Int, height: Int): Float {
        val normalizedY = y / height
        return when {
            normalizedY > 0.8f -> 0.5f + (1f - normalizedY) * 5f
            normalizedY > 0.5f -> 1.5f + (0.8f - normalizedY) * 10f
            else -> 4f + (0.5f - normalizedY) * 20f
        }
    }

    /**
     * 基于物体在图像中的水平位置估算方向
     *
     * @param x 物体中心 X 坐标
     * @param width 图像宽度
     * @return 方向判定
     */
    fun estimateDirection(x: Float, width: Int): Direction {
        val centerX = width / 2f
        return when {
            x < centerX - width * 0.2f -> Direction.LEFT
            x > centerX + width * 0.2f -> Direction.RIGHT
            else -> Direction.CENTER
        }
    }

    /**
     * [修复] 墙壁/垂直平面检测
     * 检测图像中央区域的垂直边缘密集区（墙壁特征）
     */
    fun detectWalls(bitmap: Bitmap): List<DetectedObstacle> {
        val results = mutableListOf<DetectedObstacle>()
        try {
            val width = bitmap.width
            val height = bitmap.height
            val centerX = width / 2
            val centerY = height / 2
            var verticalEdgeCount = 0

            // 批量读取像素数据
            val pixels = IntArray(width * height)
            bitmap.getPixels(pixels, width, 0, 0, 0, width, height)

            // 检测中央区域的垂直边缘密度
            for (x in (centerX - width / 4)..(centerX + width / 4) step 4) {
                var prevBrightness = -1
                var colEdges = 0
                for (y in (centerY - height / 3)..(centerY + height / 3) step 4) {
                    val idx = y.coerceIn(0, height - 1) * width + x.coerceIn(0, width - 1)
                    val pixel = pixels[idx]
                    val brightness = ((pixel shr 16) and 0xFF + (pixel shr 8) and 0xFF + pixel and 0xFF) / 3
                    if (prevBrightness >= 0 && abs(brightness - prevBrightness) > 25) {
                        colEdges++
                    }
                    prevBrightness = brightness
                }
                if (colEdges >= 3) verticalEdgeCount++
            }

            // 中央区域有大量垂直边缘 → 可能是墙壁
            if (verticalEdgeCount > width / 16) {
                val coverageRatio = verticalEdgeCount.toFloat() / (width / 2)
                val distance = when {
                    coverageRatio > 0.8f -> 0.3f
                    coverageRatio > 0.5f -> 1.0f
                    else -> 2.5f
                }

                results.add(DetectedObstacle(
                    type = ObstacleType.WALL,
                    confidence = minOf(0.6f, 0.3f + coverageRatio * 0.3f),
                    distance = distance,
                    direction = Direction.CENTER,
                    boundingBox = BoundingBox(0.2f, 0.1f, 0.8f, 0.9f)
                ))
            }
        } catch (e: Exception) {
            Timber.w(e, "Wall detection error")
        }
        return results
    }

    /**
     * 释放上一帧资源
     */
    fun releaseLastFrame() {
        lastFrame?.recycle()
        lastFrame = null
    }
}