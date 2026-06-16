package com.blindpath.module_obstacle.data.detection

import android.graphics.Bitmap
import android.graphics.Color
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 盲道检测器
 *
 * 使用传统 CV 算法识别黄色凸起导向盲道：
 * 1. HSV 色彩空间提取黄色区域
 * 2. 纹理分析识别规律性凸起条纹
 * 3. PCA 主成分分析确定盲道走向
 * 4. 偏离计算：用户相对盲道中心线的偏移量
 *
 * 盲道特征：
 * - 黄色（R: 180-255, G: 150-255, B: 0-100）
 * - 规则凸起纹理（条纹间距约 25-30mm）
 * - 连续条状区域
 * - 位于画面底部 30% 区域（地面）
 *
 * 方案说明：Phase 2 先用 CV 方案快速上线，后续可替换为专用 YOLO 分割模型
 */
@Singleton
class TactilePavingDetector @Inject constructor() {

    // 黄色检测阈值（HSV 空间）
    private val hueMin = 20f   // 黄色相起始
    private val hueMax = 40f   // 黄色相结束
    private val satMin = 0.3f  // 最低饱和度
    private val valMin = 0.4f  // 最低明度

    // 纹理检测参数
    private val textureStripeMinWidth = 3   // 条纹最小宽度（像素）
    private val textureStripeMaxWidth = 15  // 条纹最大宽度（像素）
    private val textureMinStripes = 3       // 最少条纹数

    // 连续帧验证
    private var consecutiveDetections = 0
    private val minConsecutiveFrames = 2
    private var lastDirection = 0f
    private var lastOffset = 0f

    /**
     * 检测盲道
     *
     * @param bitmap 相机帧（ARGB_8888）
     * @return TactilePavingResult 检测结果，null 表示未检测到
     */
    fun detect(bitmap: Bitmap): TactilePavingResult? {
        try {
            val width = bitmap.width
            val height = bitmap.height

            // ============================================================
            // 第一步：HSV 颜色分割，提取黄色区域
            // ============================================================
            val yellowMask = Array(height) { BooleanArray(width) }
            var yellowPixelCount = 0

            // 只扫描画面底部 40% 区域（地面）
            val scanStartY = (height * 0.6f).toInt()
            val step = 3 // 采样步长，提升性能

            for (y in scanStartY until height step step) {
                for (x in 0 until width step step) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)

                    val hsv = rgbToHsv(r, g, b)
                    val h = hsv[0]
                    val s = hsv[1]
                    val v = hsv[2]

                    // 黄色判断：色调在黄色范围 + 足够饱和度 + 足够亮度
                    if (h in hueMin..hueMax && s >= satMin && v >= valMin) {
                        yellowMask[y][x] = true
                        yellowPixelCount++
                    }
                }
            }

            val totalScannedPixels = ((height - scanStartY) / step) * (width / step)
            val yellowRatio = yellowPixelCount.toFloat() / totalScannedPixels

            // 黄色区域占比太低，不可能是盲道
            if (yellowRatio < 0.02f) {
                consecutiveDetections = 0
                return null
            }

            // ============================================================
            // 第二步：纹理分析 - 检测规律性凸起条纹
            // ============================================================
            val stripeCount = countStripePatterns(yellowMask, width, height, scanStartY, step)

            if (stripeCount < textureMinStripes) {
                consecutiveDetections = maxOf(0, consecutiveDetections - 1)
                return null
            }

            // ============================================================
            // 第三步：PCA 主成分分析 - 确定盲道走向
            // ============================================================
            val yellowPoints = mutableListOf<Pair<Int, Int>>()
            for (y in scanStartY until height step step) {
                for (x in 0 until width step step) {
                    if (yellowMask[y][x]) {
                        yellowPoints.add(Pair(x, y))
                    }
                }
            }

            if (yellowPoints.size < 20) {
                consecutiveDetections = 0
                return null
            }

            val direction = computePCA(yellowPoints, width, height)

            // ============================================================
            // 第四步：偏离计算
            // ============================================================
            val offsetFromCenter = computeOffsetFromCenter(yellowPoints, width)

            // ============================================================
            // 第五步：置信度计算
            // ============================================================
            val confidence = computeConfidence(yellowRatio, stripeCount, yellowPoints.size)

            // 连续帧验证
            if (confidence >= 0.55f) {
                consecutiveDetections++
                lastDirection = direction
                lastOffset = offsetFromCenter
            } else {
                consecutiveDetections = maxOf(0, consecutiveDetections - 1)
            }

            if (consecutiveDetections < minConsecutiveFrames) {
                return null
            }

            Timber.d(
                "TactilePaving: detected, confidence=%.2f, direction=%.1f°, offset=%.2f, stripes=$stripeCount",
                confidence, Math.toDegrees(direction.toDouble()), offsetFromCenter, stripeCount
            )

            return TactilePavingResult(
                detected = true,
                confidence = confidence,
                direction = direction,
                offsetFromCenter = offsetFromCenter,
                pavingRatio = yellowRatio
            )

        } catch (e: Exception) {
            Timber.e(e, "TactilePaving: detection failed")
            return null
        }
    }

    /**
     * RGB 转 HSV
     * @return FloatArray [hue(0-360), saturation(0-1), value(0-1)]
     */
    private fun rgbToHsv(r: Int, g: Int, b: Int): FloatArray {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f

        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val delta = max - min

        // Hue
        val h = when {
            delta == 0f -> 0f
            max == rf -> 60f * (((gf - bf) / delta) % 6f)
            max == gf -> 60f * (((bf - rf) / delta) + 2f)
            else -> 60f * (((rf - gf) / delta) + 4f)
        }.let { if (it < 0) it + 360f else it }

        // Saturation
        val s = if (max == 0f) 0f else delta / max

        // Value
        val v = max

        return floatArrayOf(h, s, v)
    }

    /**
     * 统计盲道纹理条纹数量
     * 盲道条纹特征：在黄色区域内出现规律性亮暗交替
     */
    private fun countStripePatterns(
        mask: Array<BooleanArray>,
        width: Int,
        height: Int,
        scanStartY: Int,
        step: Int
    ): Int {
        var totalStripes = 0

        // 水平扫描多行，统计条纹交替次数
        for (y in scanStartY until height step step * 2) {
            var inYellow = false
            var yellowRunLength = 0
            var notYellowRunLength = 0
            var rowStripes = 0

            for (x in 0 until width step step) {
                if (mask[y][x]) {
                    yellowRunLength++
                    if (!inYellow && notYellowRunLength in textureStripeMinWidth..textureStripeMaxWidth) {
                        // 从非黄色切换到黄色，且间隔宽度合理
                        rowStripes++
                    }
                    inYellow = true
                    notYellowRunLength = 0
                } else {
                    notYellowRunLength++
                    if (inYellow && yellowRunLength in textureStripeMinWidth..textureStripeMaxWidth) {
                        // 从黄色切换到非黄色，且黄色宽度合理
                        rowStripes++
                    }
                    inYellow = false
                    yellowRunLength = 0
                }
            }

            // 该行至少有 2 对亮暗交替才算有效条纹
            if (rowStripes >= 2) {
                totalStripes += rowStripes / 2 // 每对亮暗算一条条纹
            }
        }

        return totalStripes
    }

    /**
     * PCA 主成分分析 - 计算黄色区域的主要方向
     *
     * 对黄色像素点做协方差矩阵分析，第一主成分的方向即为盲道走向
     *
     * @return 方向角度（弧度），0 表示水平向右，PI/2 表示垂直向上
     */
    private fun computePCA(points: List<Pair<Int, Int>>, width: Int, height: Int): Float {
        if (points.isEmpty()) return 0f

        val n = points.size.toFloat()

        // 计算中心点
        val cx = points.sumOf { it.first.toDouble() } / n
        val cy = points.sumOf { it.second.toDouble() } / n

        // 计算协方差矩阵
        var covXX = 0.0
        var covXY = 0.0
        var covYY = 0.0

        for ((x, y) in points) {
            val dx = x - cx
            val dy = y - cy
            covXX += dx * dx
            covXY += dx * dy
            covYY += dy * dy
        }

        covXX /= n
        covXY /= n
        covYY /= n

        // 计算特征值和特征向量
        // 对于 2x2 协方差矩阵 [[covXX, covXY], [covXY, covYY]]
        // 特征值 λ = (covXX + covYY ± sqrt((covXX-covYY)^2 + 4*covXY^2)) / 2
        val trace = covXX + covYY
        val det = covXX * covYY - covXY * covXY
        val discriminant = sqrt(trace * trace - 4 * det)

        // 最大特征值对应的特征向量
        val lambda1 = (trace + discriminant) / 2.0

        // 特征向量 (covXX - lambda1, covXY) 归一化
        val vx = covXX - lambda1
        val vy = covXY
        val norm = sqrt(vx * vx + vy * vy)

        if (norm < 1e-6) return 0f

        // 返回方向角度（弧度）
        // atan2(vy, vx) 给出主方向
        // 对于盲道，主要方向是延伸方向（像素空间 Y 轴向下）
        val angle = atan2(vy, vx).toFloat()

        // 将角度归一化到 [-PI/2, PI/2]（因为盲道方向不需要区分正反）
        var normalized = angle
        while (normalized > Math.PI.toFloat() / 2) normalized -= Math.PI.toFloat()
        while (normalized < -Math.PI.toFloat() / 2) normalized += Math.PI.toFloat()

        return normalized
    }

    /**
     * 计算用户相对盲道中心线的偏移量
     *
     * @return 归一化偏移量 [-1, 1]，0 表示在中心，负值偏左，正值偏右
     */
    private fun computeOffsetFromCenter(points: List<Pair<Int, Int>>, width: Int): Float {
        if (points.isEmpty()) return 0f

        // 计算黄色区域在画面中的水平中心
        val avgX = points.map { it.first }.average().toFloat()

        // 画面中心
        val centerX = width / 2f

        // 归一化偏移：-1（最左）到 +1（最右）
        return ((avgX - centerX) / centerX).coerceIn(-1f, 1f)
    }

    /**
     * 计算综合置信度
     */
    private fun computeConfidence(yellowRatio: Float, stripeCount: Int, pointCount: Int): Float {
        // 黄色占比得分（理想值约 5%-30%）
        val ratioScore = when {
            yellowRatio in 0.05f..0.25f -> 0.9f
            yellowRatio in 0.02f..0.05f -> 0.7f
            yellowRatio > 0.25f -> 0.6f  // 太多黄色可能是其他物体
            else -> 0.4f
        }

        // 条纹数量得分
        val stripeScore = min(1.0f, stripeCount / 10f)

        // 像素点数量得分
        val pointScore = min(1.0f, pointCount / 500f)

        // 加权综合
        return ratioScore * 0.4f + stripeScore * 0.35f + pointScore * 0.25f
    }

    /**
     * 重置检测状态
     */
    fun reset() {
        consecutiveDetections = 0
        lastDirection = 0f
        lastOffset = 0f
    }
}

/**
 * 盲道检测结果
 */
data class TactilePavingResult(
    val detected: Boolean,
    val confidence: Float,           // 置信度 0-1
    val direction: Float,            // 盲道走向角度（弧度）
    val offsetFromCenter: Float,     // 偏离中心线距离（归一化，-1 到 1）
    val pavingRatio: Float           // 黄色区域占比
)