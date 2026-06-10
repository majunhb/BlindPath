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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 场景识别器 - 增强版（室内外全场景）
 *
 * 支持识别的场景：
 * 室外：人行道、斑马线、路口、道路标线、信号灯、道牙、井盖、积水
 * 室内：走廊、楼梯间、门口/门槛、厕所、电梯口、大厅
 * 公共场所：医院、银行、学校、商城、餐厅（基于障碍物推断）
 *
 * 检测策略：
 * 1. 传统CV算法（颜色/边缘/纹理）- 无需AI模型即可工作
 * 2. 障碍物组合推理 - 当AI模型可用时增强判断
 * 3. 亮度/色彩分布分析 - 区分室内外环境
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

        // 0. [新增] 室内外环境分类（最优先，影响后续检测策略）
        classifyIndoorOutdoor(bitmap, detectedObstacles)?.let { results.add(it) }

        // 1. 检测斑马线
        detectZebraCrossing(bitmap)?.let { results.add(it) }

        // 2. 检测楼梯/台阶
        detectStairs(bitmap, detectedObstacles)?.let { results.add(it) }

        // 3. 检测人行道
        detectSidewalk(bitmap)?.let { results.add(it) }

        // 4. [新增] 检测积水（深色反光区域）
        detectPuddle(bitmap)?.let { results.add(it) }

        // 5. [新增] 检测道牙（底部边缘突变线）
        detectCurb(bitmap)?.let { results.add(it) }

        // 6. [新增] 检测门口/门槛（室内垂直线条+亮度变化）
        detectDoorway(bitmap)?.let { results.add(it) }

        // 7. 基于检测到的障碍物推断场景（增强版）
        inferSceneFromObstacles(detectedObstacles)?.let { results.add(it) }

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
                            // 重置计数器，避免跨行累加
                            whitePixels = 0
                            blackPixels = 0
                        }
                    }
                }
                // 每行扫描结束后重置计数器，避免跨行累加
                if (!inWhiteStripe) {
                    whitePixels = 0
                    blackPixels = 0
                }
            }

            // 如果检测到多条白色条纹，可能是斑马线
            if (whiteLineCount >= 4) {
                val confidence = kotlin.math.min(0.9f, 0.5f + (whiteLineCount - 4) * 0.05f)
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
                val confidence = kotlin.math.min(0.8f, 0.5f + (horizontalEdges - 20) * 0.005f)
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
                    val confidence = kotlin.math.min(0.75f, 0.5f + (grayRatio - 0.5f) * 0.5f)
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
     * 基于检测到的障碍物推断场景（增强版）
     * 新增：医院/银行/学校/商城/餐厅等公共场所识别
     */
    private fun inferSceneFromObstacles(obstacles: List<DetectedObstacle>): Pair<SceneType, Float>? {
        // 检测到多个红绿灯 → 路口
        val trafficLights = obstacles.count { it.type == ObstacleType.TRAFFIC_LIGHT }
        if (trafficLights >= 1) {
            return Pair(SceneType.TRAFFIC_SIGNAL_AREA, 0.7f + kotlin.math.min(0.2f, trafficLights * 0.05f))
        }

        if (trafficLights >= 2) {
            return Pair(SceneType.INTERSECTION, 0.75f)
        }

        // 检测到台阶 + 门框 → 建筑物入口
        val hasStairs = obstacles.any { it.type == ObstacleType.STEP_UP || it.type == ObstacleType.STEP_DOWN }
        val hasPillar = obstacles.any { it.type == ObstacleType.PILLAR }
        if (hasStairs && hasPillar) {
            return Pair(SceneType.BUILDING_ENTRANCE, 0.65f)
        }

        // [新增] 检测到多把椅子+桌子 → 餐厅/商城休息区
        val chairs = obstacles.count { it.type == ObstacleType.CHAIR }
        val tables = obstacles.count { it.type == ObstacleType.TABLE }
        if (chairs >= 3 && tables >= 1) {
            return Pair(SceneType.RESTAURANT, 0.6f)
        }
        if (chairs >= 4 || tables >= 3) {
            return Pair(SceneType.SHOPPING_MALL, 0.55f)
        }

        // [新增] 多个人物 + 行李箱/手提包 → 商城/交通枢纽
        val people = obstacles.count { it.type == ObstacleType.PERSON }
        val luggage = obstacles.count {
            it.type == ObstacleType.SUITCASE || it.type == ObstacleType.HANDBAG || it.type == ObstacleType.BACKPACK
        }
        if (people >= 3 && luggage >= 2) {
            return Pair(SceneType.SHOPPING_MALL, 0.6f)
        }

        // [新增] 大量人物 + 交通标志 → 学校区域
        val trafficSigns = obstacles.count { it.type == ObstacleType.TRAFFIC_SIGN }
        if (people >= 5 && trafficSigns >= 1) {
            return Pair(SceneType.SCHOOL_AREA, 0.55f)
        }

        // [新增] 室内物品组合（沙发+盆栽+桌椅）→ 银行大厅
        val hasSofa = obstacles.any { it.type == ObstacleType.SOFA }
        val hasPlant = obstacles.any { it.type == ObstacleType.POTTED_PLANT }
        if ((hasSofa || hasPlant) && chairs >= 2) {
            return Pair(SceneType.BANK_AREA, 0.58f)
        }

        // [新增] 交通工具聚集 → 停车场/交通枢纽
        val vehicles = obstacles.count { it.type in listOf(
            ObstacleType.VEHICLE, ObstacleType.TRUCK, ObstacleType.BUS,
            ObstacleType.MOTORCYCLE, ObstacleType.BICYCLE
        )}
        if (vehicles >= 3) {
            return Pair(SceneType.PARKING_LOT, 0.65f)
        }
        val hasAirplane = obstacles.any { it.type == ObstacleType.AIRPLANE }
        val hasTrainOrBoat = obstacles.any {
            it.type == ObstacleType.TRAIN || it.type == ObstacleType.BOAT
        }
        if (hasAirplane || hasTrainOrBoat) {
            return Pair(SceneType.TRANSPORTATION_HUB, 0.70f)
        }

        // [新增] 动物出现 → 动物园/农场场景
        val wildAnimals = obstacles.count { it.type in listOf(
            ObstacleType.ELEPHANT, ObstacleType.BEAR, ObstacleType.ZEBRA,
            ObstacleType.GIRAFFE, ObstacleType.HORSE, ObstacleType.COW,
            ObstacleType.SHEEP
        )}
        if (wildAnimals >= 1) {
            return Pair(SceneType.ZOO_OR_FARM, 0.70f)
        }
        val pets = obstacles.count { it.type in listOf(ObstacleType.CAT, ObstacleType.DOG) }
        if (pets >= 2) {
            return Pair(SceneType.PET_AREA, 0.60f)
        }

        // [新增] 运动器材 → 运动场/健身房场景
        val sportEquip = obstacles.count { it.type in listOf(
            ObstacleType.SPORTS_BALL, ObstacleType.SKATEBOARD, ObstacleType.TENNIS_RACKET,
            ObstacleType.SURFBOARD, ObstacleType.SKIS, ObstacleType.FRISBEE
        )}
        if (sportEquip >= 2) {
            return Pair(SceneType.SPORTS_AREA, 0.65f)
        }

        // [新增] 电子产品聚集 → 电器区域
        val electronics = obstacles.count { it.type in listOf(
            ObstacleType.TV, ObstacleType.LAPTOP, ObstacleType.KEYBOARD,
            ObstacleType.MOUSE_DEVICE, ObstacleType.REMOTE, ObstacleType.MICROWAVE,
            ObstacleType.OVEN, ObstacleType.REFRIGERATOR, ObstacleType.TOASTER
        )}
        if (electronics >= 2) {
            return Pair(SceneType.APPLIANCE_AREA, 0.60f)
        }

        // [新增] 书本聚集 → 图书馆/书店
        val books = obstacles.count { it.type == ObstacleType.BOOK }
        if (books >= 3) {
            return Pair(SceneType.LIBRARY_AREA, 0.65f)
        }

        // [新增] 餐饮器具聚集 → 厨房/餐厅
        val kitchenItems = obstacles.count { it.type in listOf(
            ObstacleType.BOWL, ObstacleType.KNIFE, ObstacleType.FORK,
            ObstacleType.SPOON, ObstacleType.CUP, ObstacleType.WINE_GLASS
        )}
        if (kitchenItems >= 3) {
            return Pair(SceneType.KITCHEN_AREA, 0.65f)
        }

        // ===== 增强公共场景识别规则 =====

        // 医院场景：多长椅 + 多行人 + 交通标志
        val benches = obstacles.count { it.type == ObstacleType.BENCH }
        if (benches >= 3 && people >= 5) {
            return Pair(SceneType.HOSPITAL, 0.5f)
        }

        // 银行场景：沙发 + 盆栽 + 柱子（典型银行大厅布局）
        if (hasSofa && hasPlant && hasPillar) {
            return Pair(SceneType.BANK, 0.5f)
        }

        // 公交站台：长椅 + 交通标志 + 行人
        if (benches >= 2 && trafficSigns >= 1) {
            return Pair(SceneType.BUS_STOP, 0.5f)
        }

        // 餐厅场景：多椅子 + 多桌子 + 杯子/碗
        if (chairs >= 4 && tables >= 2) {
            return Pair(SceneType.RESTAURANT_AREA, 0.5f)
        }

        // 超市场景：多桌子(货架) + 多行人
        if (tables >= 3 && people >= 3) {
            return Pair(SceneType.SUPERMARKET, 0.5f)
        }

        // 学校场景：多行人 + 交通标志 + 长椅
        if (people >= 5 && trafficSigns >= 2 && benches >= 1) {
            return Pair(SceneType.SCHOOL, 0.5f)
        }

        // 公交车内场景：扶手 + 行人（室内环境）
        val handrails = obstacles.count { it.type == ObstacleType.HANDRAIL }
        if (handrails >= 2 && people >= 2) {
            return Pair(SceneType.BUS_INTERIOR, 0.5f)
        }

        // 电梯场景：门 + 扶手
        val doors = obstacles.count { it.type == ObstacleType.DOOR }
        if (doors >= 1 && handrails >= 1) {
            return Pair(SceneType.ELEVATOR, 0.5f)
        }

        // ATM场景：电视屏幕(ATM显示屏) + 柱子
        val tvs = obstacles.count { it.type == ObstacleType.TV }
        if (tvs >= 1 && hasPillar) {
            return Pair(SceneType.ATM, 0.5f)
        }

        // 机场场景：行李箱 + 长椅 + 行人
        if (luggage >= 3 && benches >= 2 && people >= 3) {
            return Pair(SceneType.AIRPORT, 0.5f)
        }

        // 火车站场景：行李箱 + 长椅 + 多行人
        if (luggage >= 2 && benches >= 2 && people >= 5) {
            return Pair(SceneType.TRAIN_STATION, 0.5f)
        }

        // 药店场景：桌子(柜台) + 交通标志(招牌)
        if (tables >= 1 && trafficSigns >= 2) {
            return Pair(SceneType.PHARMACY, 0.5f)
        }

        return null
    }

    /**
     * [新增] 室内外环境分类
     * 基于亮度分布、色彩饱和度、纹理复杂度判断
     */
    private fun classifyIndoorOutdoor(bitmap: Bitmap, obstacles: List<DetectedObstacle>): Pair<SceneType, Float>? {
        try {
            val width = bitmap.width
            val height = bitmap.height

            var totalBrightness = 0
            var indoorScore = 0   // 高=室内
            var outdoorScore = 0   // 高=室外
            var sampleCount = 0
            val step = kotlin.math.max(8, width / 80)

            for (y in 0 until height step step * 2) {
                for (x in 0 until width step step) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val brightness = (r + g + b) / 3
                    totalBrightness += brightness

                    // 色彩饱和度（室内通常较低，室外较高）
                    val maxC = kotlin.math.max(kotlin.math.max(r, g), b).toFloat()
                    val minC = kotlin.math.min(kotlin.math.min(r, g), b).toFloat()
                    val saturation = if (maxC > 0) (maxC - minC) / maxC else 0f

                    sampleCount++

                    // 室内特征：均匀亮度、低饱和度、暖色调
                    if (brightness in 100..200 && saturation < 0.3f && r > b) {
                        indoorScore++
                    }
                    // 室外特征：高亮度、高对比、天空蓝色或绿色植物
                    if (brightness > 180 || saturation > 0.5f || b > r + 20) {
                        outdoorScore++
                    }
                    // 天空特征（图像上方偏蓝）
                    if (y < height / 3 && b > r + 30 && b > g + 10) {
                        outdoorScore += 2
                    }
                }
            }

            if (sampleCount < 10) return null

            val avgBrightness = totalBrightness / sampleCount
            val isIndoor = indoorScore > outdoorScore * 1.3 && avgBrightness in 120..190
            val isOutdoor = outdoorScore > indoorScore * 1.3

            if (isIndoor) {
                // 进一步区分室内场景类型
                val confidence = 0.55f + kotlin.math.min(0.25f, (indoorScore - outdoorScore) / sampleCount.toFloat())
                // 有大量家具类障碍物 → 更具体室内场景
                val furnitureCount = obstacles.count {
                    it.type in listOf(ObstacleType.CHAIR, ObstacleType.TABLE,
                        ObstacleType.SOFA, ObstacleType.BED, ObstacleType.POTTED_PLANT)
                }
                if (furnitureCount >= 3) {
                    return Pair(SceneType.INDOOR_HALL, confidence)
                }
                return Pair(SceneType.INDOOR_CORRIDOR, confidence)
            } else if (isOutdoor) {
                val confidence = 0.55f + kotlin.math.min(0.25f, (outdoorScore - indoorScore) / sampleCount.toFloat())
                return Pair(SceneType.ROAD, confidence)
            }
        } catch (e: Exception) {
            Timber.e(e, "Indoor/outdoor classification failed")
        }
        return null
    }

    /**
     * [新增] 积水检测
     * 识别路面深色反光区域（积水特征：暗色+局部高亮反射）
     */
    private fun detectPuddle(bitmap: Bitmap): Pair<SceneType, Float>? {
        try {
            val width = bitmap.width
            val height = bitmap.height
            var puddlePixels = 0
            var groundPixels = 0
            val startY = (height * 0.6).toInt() // 只检查地面区域

            for (y in startY until height step 6) {
                for (x in 0 until width step 6) {
                    val pixel = bitmap.getPixel(x, y)
                    val r = Color.red(pixel)
                    val g = Color.green(pixel)
                    val b = Color.blue(pixel)
                    val brightness = (r + g + b) / 3

                    groundPixels++
                    // 积水特征：较暗但有反光（不完全黑），且颜色偏灰暗
                    val isDark = brightness in 30..120
                    val isFlatColor = abs(r - g) < 20 && abs(r - b) < 20 && abs(g - b) < 20
                    if (isDark && isFlatColor) {
                        puddlePixels++
                    }
                }
            }

            if (groundPixels > 0) {
                val puddleRatio = puddlePixels.toFloat() / groundPixels
                // 积水占比超过15%时报警
                if (puddleRatio > 0.15f) {
                    val confidence = kotlin.math.min(0.85f, 0.5f + puddleRatio * 1.5f)
                    Timber.d("Puddle detected: ratio=$puddleRatio, confidence=$confidence")
                    return Pair(SceneType.PUDDLE, confidence)
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Puddle detection failed")
        }
        return null
    }

    /**
     * [增强] 道牙/路沿检测
     * 在图像底部检测水平边缘突变线（道牙是地面到路缘的高度差）
     * 
     * 检测算法：
     * 1. 分析底部30%区域的亮度梯度
     * 2. 检测水平方向的连续边缘线
     * 3. 验证边缘的几何特征（水平性、连续性）
     */
    private fun detectCurb(bitmap: Bitmap): Pair<SceneType, Float>? {
        try {
            val width = bitmap.width
            val height = bitmap.height
            
            // ============ 1. 底部30%区域检测 ============
            val startY = (height * 0.7).toInt()
            val endY = height
            
            // 记录每行的边缘强度
            val edgeStrengths = mutableListOf<Float>()
            
            for (y in startY until endY step 3) {
                var prevBrightness = -1
                var edgeCount = 0
                var totalDiff = 0

                // 水平扫描，检测亮度突变
                for (x in 0 until width step 3) {
                    val pixel = bitmap.getPixel(x, y)
                    val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3

                    if (prevBrightness >= 0) {
                        val diff = abs(brightness - prevBrightness)
                        // 道牙产生明显的亮度跳变（阈值调整为35，降低误检）
                        if (diff > 35) {
                            edgeCount++
                            totalDiff += diff
                        }
                    }
                    prevBrightness = brightness
                }
                
                // 计算该行的边缘强度（边缘点占比）
                val edgeStrength = edgeCount.toFloat() / (width / 3)
                edgeStrengths.add(edgeStrength)
            }
            
            // ============ 2. 验证连续水平边缘 ============
            // 路沿应该在多行中表现为连续的边缘
            val strongEdges = edgeStrengths.count { it > 0.08f } // 超过8%的像素有边缘
            
            // ============ 3. 计算置信度 ============
            if (strongEdges >= 2) {
                val avgStrength = edgeStrengths.average().toFloat()
                val confidence = kotlin.math.min(0.85f, 0.55f + avgStrength * 2f)
                
                Timber.d("Curb detected: $strongEdges strong edges, avg strength=${(avgStrength * 100).toInt()}%, confidence=${(confidence * 100).toInt()}%")
                return Pair(SceneType.CURB, confidence)
            }
            
        } catch (e: Exception) {
            Timber.e(e, "Curb detection failed")
        }
        return null
    }

    /**
     * [新增] 门口/门槛检测
     * 室内环境中的垂直线条 + 亮度变化（门框特征）
     */
    private fun detectDoorway(bitmap: Bitmap): Pair<SceneType, Float>? {
        try {
            val width = bitmap.width
            val height = bitmap.height
            var verticalEdges = 0

            // 扫描图像中央区域寻找垂直边缘
            val startX = (width * 0.3).toInt()
            val endX = (width * 0.7).toInt()
            val topY = (height * 0.2).toInt()
            val bottomY = (height * 0.8).toInt()

            for (x in startX until endX step 4) {
                var prevBrightness = -1
                var consecutiveEdges = 0

                for (y in topY until bottomY step 4) {
                    val pixel = bitmap.getPixel(x, y)
                    val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3

                    if (prevBrightness >= 0 && abs(brightness - prevBrightness) > 35) {
                        consecutiveEdges++
                    }
                    prevBrightness = brightness
                }

                // 一列中有连续多个垂直边缘点 → 门框竖边
                if (consecutiveEdges >= 5) {
                    verticalEdges++
                }
            }

            if (verticalEdges >= 2) {
                val confidence = kotlin.math.min(0.78f, 0.5f + verticalEdges * 0.06f)
                Timber.d("Doorway detected: $verticalEdges vertical edges")
                return Pair(SceneType.INDOOR_DOORWAY, confidence)
            }
        } catch (e: Exception) {
            Timber.e(e, "Doorway detection failed")
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
