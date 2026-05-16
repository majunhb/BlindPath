package com.blindpath.module_obstacle.data.detection

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import com.blindpath.module_obstacle.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.min

/**
 * 场景识别器
 * 识别用户当前所处的环境场景，帮助视障用户了解周围环境
 *
 * 支持识别的场景：
 * - 人行道
 * - 斑马线
 * - 楼梯口
 * - 路口
 * - 建筑物入口
 */
@Singleton
class SceneClassifier @Inject constructor(
    @ApplicationContext private val context: Context
) {
    // 场景识别置信度阈值
    private val sceneConfidenceThreshold = 0.6f

    // 连续帧数要求（避免抖动）
    private val frameRequirement = 3
    private var frameCounter = mutableMapOf<SceneType, Int>()

    // 上次识别的场景
    private var lastRecognizedScene: SceneType? = null
    private var lastRecognitionTime = 0L
    private val sceneCooldown = 5000L // 5秒内不重复播报相同场景

    /**
     * 识别当前场景
     */
    fun recognizeScene(bitmap: Bitmap, detectedObstacles: List<DetectedObstacle>): SceneRecognitionResult? {
        val results = mutableListOf<Pair<SceneType, Float>>()

        // 1. 检测斑马线
        detectZebraCrossing(bitmap)?.let {
            results.add(it)
        }

        // 2. 检测楼梯/台阶
        detectStairs(bitmap, detectedObstacles)?.let {
            results.add(it)
        }

        // 3. 检测人行道
        detectSidewalk(bitmap)?.let {
            results.add(it)
        }

        // 4. 基于检测到的障碍物推断场景
        inferSceneFromObstacles(detectedObstacles)?.let {
            results.add(it)
        }

        // 返回最高置信度的场景
        val bestMatch = results.maxByOrNull { it.second }
        if (bestMatch != null && bestMatch.second >= sceneConfidenceThreshold) {
            // 检查是否需要更新计数
            val currentCount = frameCounter.getOrDefault(bestMatch.first, 0) + 1
            frameCounter[bestMatch.first] = currentCount

            // 达到连续帧要求才确认场景
            if (currentCount >= frameRequirement) {
                val sceneResult = SceneRecognitionResult(
                    sceneType = bestMatch.first,
                    confidence = bestMatch.second
                )

                // 检查是否需要播报场景变化
                checkAndAnnounceSceneChange(sceneResult)

                return sceneResult
            }
        }

        // 如果没有检测到明确场景，减少计数
        frameCounter.keys.forEach {
            frameCounter[it] = (frameCounter[it] ?: 0) - 1
            if ((frameCounter[it] ?: 0) <= 0) {
                frameCounter.remove(it)
            }
        }

        return null
    }

    /**
     * 检测斑马线
     * 使用线条检测算法识别白色的平行条纹
     */
    private fun detectZebraCrossing(bitmap: Bitmap): Pair<SceneType, Float>? {
        try {
            val width = bitmap.width
            val height = bitmap.height

            // 缩小图像以提高处理速度
            val scale = 4
            val scaledWidth = width / scale
            val scaledHeight = height / scale

            // 统计白色条纹
            var whiteLineCount = 0
            val stripeWidth = 10 // 斑马线条纹宽度（像素）

            // 检查图像下半部分（更可能是地面）
            val startY = (height * 0.5).toInt()
            for (y in startY until height step scale) {
                var inWhiteStripe = false
                var whitePixels = 0
                var blackPixels = 0

                for (x in 0 until width step scale) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)

                    // 判断是否为白色或浅色
                    val isWhite = r > 200 && g > 200 && b > 200 && abs(r - g) < 30 && abs(r - b) < 30

                    if (isWhite) {
                        whitePixels++
                        if (!inWhiteStripe && whitePixels > stripeWidth) {
                            whiteLineCount++
                            inWhiteStripe = true
                        }
                    } else {
                        blackPixels++
                        if (inWhiteStripe && blackPixels > stripeWidth) {
                            inWhiteStripe = false
                            whitePixels = 0
                            blackPixels = 0
                        }
                    }
                }
            }

            // 如果检测到多条白色条纹，可能是斑马线
            if (whiteLineCount >= 4) {
                val confidence = min(0.9f, 0.5f + (whiteLineCount - 4) * 0.05f)
                Timber.d("Zebra crossing detected: $whiteLineCount stripes, confidence: $confidence")
                return Pair(SceneType.CROSSWALK, confidence)
            }
        } catch (e: Exception) {
            Timber.e(e, "Zebra crossing detection failed")
        }

        return null
    }

    /**
     * 检测楼梯/台阶区域
     * 基于水平边缘检测和规则图案
     */
    private fun detectStairs(bitmap: Bitmap, obstacles: List<DetectedObstacle>): Pair<SceneType, Float>? {
        // 如果已经检测到台阶类障碍物，更可能是楼梯口
        val hasStairs = obstacles.any {
            it.type == ObstacleType.STEP_UP ||
            it.type == ObstacleType.STEP_DOWN ||
            it.type == ObstacleType.STAIRS
        }

        if (hasStairs) {
            // 检查是否在楼梯口位置（障碍物在画面中央偏上）
            val stairsObstacles = obstacles.filter {
                it.type == ObstacleType.STEP_UP ||
                it.type == ObstacleType.STEP_DOWN ||
                it.type == ObstacleType.STAIRS
            }

            if (stairsObstacles.any { it.boundingBox.centerY < 0.4f }) {
                return Pair(SceneType.STAIR_ENTRANCE, 0.75f)
            }
        }

        // 使用边缘检测检测楼梯图案
        try {
            val width = bitmap.width
            val height = bitmap.height

            // 统计水平边缘（楼梯特征）
            var horizontalEdges = 0
            val threshold = 30

            for (y in (height * 0.3).toInt() until (height * 0.7).toInt() step 3) {
                var prevBrightness = -1
                for (x in 0 until width step 5) {
                    val pixel = bitmap.getPixel(x, y)
                    val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3

                    if (prevBrightness >= 0) {
                        val diff = abs(brightness - prevBrightness)
                        if (diff > threshold) {
                            horizontalEdges++
                        }
                    }
                    prevBrightness = brightness
                }
            }

            // 如果检测到多个水平边缘，可能是楼梯
            if (horizontalEdges > 20 && horizontalEdges < 100) {
                val confidence = min(0.8f, 0.5f + (horizontalEdges - 20) * 0.005f)
                Timber.d("Stairs pattern detected: $horizontalEdges edges, confidence: $confidence")
                return Pair(SceneType.STAIR_ENTRANCE, confidence)
            }
        } catch (e: Exception) {
            Timber.e(e, "Stairs detection failed")
        }

        return null
    }

    /**
     * 检测人行道
     * 基于颜色和纹理特征
     */
    private fun detectSidewalk(bitmap: Bitmap): Pair<SceneType, Float>? {
        try {
            val width = bitmap.width
            val height = bitmap.height

            // 人行道通常是灰色，有规则的纹理
            var grayPixelCount = 0
            var totalPixelCount = 0

            // 检查图像下半部分
            val startY = (height * 0.6).toInt()
            for (y in startY until height step 4) {
                for (x in 0 until width step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)

                    // 判断是否为灰色（人行道特征）
                    val isGray = abs(r - g) < 25 && abs(r - b) < 25 && abs(g - b) < 25
                    val brightness = (r + g + b) / 3f

                    if (isGray && brightness in 80f..180f) {
                        grayPixelCount++
                    }
                    totalPixelCount++
                }
            }

            // 如果大部分像素是灰色，可能是人行道
            if (totalPixelCount > 0) {
                val grayRatio = grayPixelCount.toFloat() / totalPixelCount
                if (grayRatio > 0.5f) {
                    val confidence = min(0.75f, 0.5f + (grayRatio - 0.5f) * 0.5f)
                    Timber.d("Sidewalk detected: gray ratio $grayRatio, confidence: $confidence")
                    return Pair(SceneType.SIDEWALK, confidence)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Sidewalk detection failed")
        }

        return null
    }

    /**
     * 基于检测到的障碍物推断场景
     */
    private fun inferSceneFromObstacles(obstacles: List<DetectedObstacle>): Pair<SceneType, Float>? {
        // 检测到多个红绿灯 → 路口
        val trafficLights = obstacles.count { it.type == ObstacleType.TRAFFIC_LIGHT }
        if (trafficLights >= 2) {
            return Pair(SceneType.INTERSECTION, 0.7f)
        }

        // 检测到台阶 + 门框 → 建筑物入口
        val hasStairs = obstacles.any { it.type == ObstacleType.STEP_UP || it.type == ObstacleType.STEP_DOWN }
        val hasPillar = obstacles.any { it.type == ObstacleType.PILLAR }
        if (hasStairs && hasPillar) {
            return Pair(SceneType.BUILDING_ENTRANCE, 0.65f)
        }

        return null
    }

    /**
     * 检查是否需要播报场景变化
     */
    private fun checkAndAnnounceSceneChange(result: SceneRecognitionResult) {
        val currentTime = System.currentTimeMillis()

        // 检查冷却期
        if (currentTime - lastRecognitionTime < sceneCooldown) {
            return
        }

        // 检查场景是否变化
        if (result.sceneType != lastRecognizedScene) {
            lastRecognizedScene = result.sceneType
            lastRecognitionTime = currentTime
            Timber.d("Scene changed to: ${result.sceneType.chineseName}")
        }
    }

    /**
     * 获取场景进入提示消息
     */
    fun getSceneAnnouncement(result: SceneRecognitionResult?): String {
        return result?.sceneType?.getEntryAnnouncement() ?: ""
    }

    /**
     * 重置场景识别状态
     */
    fun reset() {
        frameCounter.clear()
        lastRecognizedScene = null
        lastRecognitionTime = 0L
    }
}
