package com.blindpath.module_indoor.data

import android.content.Context
import android.graphics.Bitmap
import com.blindpath.module_indoor.domain.model.*
import com.blindpath.module_obstacle.data.detection.AIDetector
import com.blindpath.module_obstacle.domain.model.BoundingBox
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.Direction
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 室内环境检测器
 * 结合 ML Kit Image Labeling 进行场景识别
 * 复用 AIDetector 进行障碍物检测
 */
@Singleton
class IndoorDetector @Inject constructor(
    @ApplicationContext private val context: Context,
    private val aiDetector: AIDetector
) {
    private var imageLabeler: com.google.mlkit.vision.label.ImageLabeler? = null
    private var isLoaded = false
    private var aiModelLoaded = false
    private var mlKitInitialized = false

    // ML Kit 标签到房间类型的映射
    private val labelToRoomType = mapOf(
        // 客厅相关标签
        "Living room" to RoomType.LIVING_ROOM,
        "Furniture" to RoomType.LIVING_ROOM,
        "Couch" to RoomType.LIVING_ROOM,
        "Sofa" to RoomType.LIVING_ROOM,
        "Coffee table" to RoomType.LIVING_ROOM,
        "Television" to RoomType.LIVING_ROOM,
        "TV" to RoomType.LIVING_ROOM,
        "Table" to RoomType.LIVING_ROOM,
        "Dining table" to RoomType.LIVING_ROOM,
        "Chair" to RoomType.LIVING_ROOM,
        "Lamp" to RoomType.LIVING_ROOM,
        "Floor lamp" to RoomType.LIVING_ROOM,

        // 卧室相关标签
        "Bedroom" to RoomType.BEDROOM,
        "Bed" to RoomType.BEDROOM,
        "Mattress" to RoomType.BEDROOM,
        "Nightstand" to RoomType.BEDROOM,
        "Pillow" to RoomType.BEDROOM,
        "Blanket" to RoomType.BEDROOM,
        "Wardrobe" to RoomType.BEDROOM,

        // 厨房相关标签
        "Kitchen" to RoomType.KITCHEN,
        "Refrigerator" to RoomType.KITCHEN,
        "Oven" to RoomType.KITCHEN,
        "Stove" to RoomType.KITCHEN,
        "Microwave" to RoomType.KITCHEN,
        "Sink" to RoomType.KITCHEN,
        "Kitchen appliance" to RoomType.KITCHEN,
        "Kitchen counter" to RoomType.KITCHEN,
        "Cupboard" to RoomType.KITCHEN,
        "Kitchen utensil" to RoomType.KITCHEN,
        "Countertop" to RoomType.KITCHEN,

        // 卫生间相关标签
        "Bathroom" to RoomType.BATHROOM,
        "Toilet" to RoomType.BATHROOM,
        "Sink" to RoomType.BATHROOM,
        "Shower" to RoomType.BATHROOM,
        "Bathtub" to RoomType.BATHROOM,
        "Mirror" to RoomType.BATHROOM,
        "Tap" to RoomType.BATHROOM,
        "Restroom" to RoomType.BATHROOM,
        "Washbasin" to RoomType.BATHROOM,

        // 阳台相关标签
        "Balcony" to RoomType.BALCONY,
        "Terrace" to RoomType.BALCONY,
        "Patio" to RoomType.BALCONY,
        "Railing" to RoomType.BALCONY,

        // 走廊相关标签
        "Hallway" to RoomType.HALLWAY,
        "Corridor" to RoomType.HALLWAY,
        "Hall" to RoomType.HALLWAY,
        "Door" to RoomType.HALLWAY,
        "Door handle" to RoomType.HALLWAY,

        // 楼梯间相关标签
        "Stairs" to RoomType.STAIRS,
        "Staircase" to RoomType.STAIRS,
        "Stairwell" to RoomType.STAIRS,
        "Handrail" to RoomType.STAIRS,
        "Step" to RoomType.STAIRS
    )

    // COCO 类别到室内障碍物类型的映射
    // 只映射真正与室内障碍物相关的类别，排除小型物品（鼠标、遥控器、手机等）
    private val cocoToIndoorObstacle = mapOf(
        // 家具类
        56 to IndoorObstacleType.CHAIR,        // chair
        57 to IndoorObstacleType.SOFA,         // sofa/couch
        59 to IndoorObstacleType.BED,          // bed
        60 to IndoorObstacleType.TABLE,        // dining table

        // 电器类
        62 to IndoorObstacleType.TV,           // tv
        68 to IndoorObstacleType.REFRIGERATOR, // microwave
        69 to IndoorObstacleType.REFRIGERATOR, // oven
        72 to IndoorObstacleType.REFRIGERATOR  // refrigerator
    )

    // ML Kit 标签到室内障碍物类型的映射
    private val mlKitLabelToIndoorObstacle = mapOf(
        "Chair" to IndoorObstacleType.CHAIR,
        "Couch" to IndoorObstacleType.SOFA,
        "Sofa" to IndoorObstacleType.SOFA,
        "Table" to IndoorObstacleType.TABLE,
        "Dining table" to IndoorObstacleType.TABLE,
        "Coffee table" to IndoorObstacleType.TABLE,
        "Bed" to IndoorObstacleType.BED,
        "Cabinet" to IndoorObstacleType.CABINET,
        "Cupboard" to IndoorObstacleType.CABINET,
        "Wardrobe" to IndoorObstacleType.CABINET,
        "Door" to IndoorObstacleType.DOOR,
        "Window" to IndoorObstacleType.WINDOW,
        "Stairs" to IndoorObstacleType.STAIRS,
        "Staircase" to IndoorObstacleType.STAIRS,
        "Television" to IndoorObstacleType.TV,
        "TV" to IndoorObstacleType.TV,
        "Refrigerator" to IndoorObstacleType.REFRIGERATOR,
        "Fridge" to IndoorObstacleType.REFRIGERATOR,
        "Washing machine" to IndoorObstacleType.WASHING_MACHINE,
        "Washer" to IndoorObstacleType.WASHING_MACHINE
    )

    /**
     * 加载检测模型
     * 修复：即使 AIDetector 加载失败，也继续初始化 ML Kit Image Labeler，提供降级功能
     */
    suspend fun loadModel(): Boolean {
        Timber.d("IndoorDetector.loadModel() 开始加载模型...")
        return try {
            // 1. 尝试加载 AIDetector 模型（障碍物检测）
            aiModelLoaded = try {
                Timber.d("开始加载 AIDetector 模型...")
                val loaded = aiDetector.loadModel()
                Timber.d("AIDetector 模型加载结果: $loaded")
                loaded
            } catch (e: Exception) {
                Timber.e(e, "AIDetector 模型加载失败，将使用 ML Kit 降级方案。异常类型: ${e.javaClass.simpleName}, 消息: ${e.message}")
                false
            }

            // 2. 初始化 ML Kit Image Labeler（场景识别，必须成功）
            mlKitInitialized = try {
                Timber.d("开始初始化 ML Kit Image Labeler...")
                val options = ImageLabelerOptions.Builder()
                    .setConfidenceThreshold(0.3f)  // 降低阈值提高召回率
                    .build()
                imageLabeler = ImageLabeling.getClient(options)
                Timber.d("ML Kit Image Labeler 初始化成功")
                true
            } catch (e: Exception) {
                Timber.e(e, "ML Kit Image Labeler 初始化失败。异常类型: ${e.javaClass.simpleName}, 消息: ${e.message}")
                false
            }

            // 3. 判断整体加载状态：只要 ML Kit 初始化成功，就认为模型可用（降级模式）
            isLoaded = mlKitInitialized
            
            if (isLoaded) {
                if (aiModelLoaded) {
                    Timber.i("IndoorDetector 加载完成: AI模型+ML Kit双引擎就绪")
                } else {
                    Timber.w("IndoorDetector 加载完成: AIDetector 失败，仅 ML Kit 单引擎运行（降级模式）")
                }
            } else {
                Timber.e("IndoorDetector 加载失败: ML Kit 初始化失败，无法提供任何检测能力")
            }
            
            Timber.d("IndoorDetector 最终状态: isLoaded=$isLoaded, aiModelLoaded=$aiModelLoaded, mlKitInitialized=$mlKitInitialized")
            isLoaded
        } catch (e: Exception) {
            Timber.e(e, "加载室内检测模型时发生未捕获异常。异常类型: ${e.javaClass.simpleName}, 消息: ${e.message}")
            isLoaded = false
            aiModelLoaded = false
            mlKitInitialized = false
            false
        }
    }

    /**
     * 卸载模型
     */
    fun unloadModel() {
        Timber.d("IndoorDetector 开始卸载模型...")
        try {
            aiDetector.unloadModel()
            Timber.d("AIDetector 模型已卸载")
        } catch (e: Exception) {
            Timber.e(e, "卸载 AIDetector 模型时发生异常")
        }
        try {
            imageLabeler?.close()
            Timber.d("ML Kit Image Labeler 已关闭")
        } catch (e: Exception) {
            Timber.e(e, "关闭 ML Kit Image Labeler 时发生异常")
        }
        imageLabeler = null
        isLoaded = false
        aiModelLoaded = false
        mlKitInitialized = false
        Timber.d("IndoorDetector 卸载完成")
    }

    /**
     * 检测室内场景和障碍物
     * 修复：即使 AIDetector 未加载，也继续执行 ML Kit 检测
     */
    suspend fun detect(bitmap: Bitmap): IndoorScene {
        if (!isLoaded) {
            Timber.w("IndoorDetector 未加载，返回空场景")
            return IndoorScene(
                roomType = RoomType.UNKNOWN,
                confidence = 0f,
                obstacles = emptyList()
            )
        }

        return withContext(Dispatchers.IO) {
            try {
                // 并行执行检测任务
                val roomTypeDeferred = async { detectRoomType(bitmap) }
                
                // 只有 AIDetector 加载成功时才执行障碍物检测
                val obstaclesDeferred = async {
                    if (aiModelLoaded) {
                        try {
                            aiDetector.detect(bitmap)
                        } catch (e: Exception) {
                            Timber.e(e, "AIDetector 检测失败，跳过")
                            emptyList()
                        }
                    } else {
                        Timber.d("AIDetector 未加载，跳过障碍物检测")
                        emptyList()
                    }
                }
                
                val mlKitObstaclesDeferred = async { detectWithMlKit(bitmap) }

                // 等待所有结果
                val roomTypeResult = roomTypeDeferred.await()
                val detectedObstacles = obstaclesDeferred.await()
                val mlKitObstacles = mlKitObstaclesDeferred.await()

                // 转换和合并
                val indoorObstacles = detectedObstacles.mapNotNull { convertToIndoorObstacle(it) }
                val allObstacles = mergeObstacles(indoorObstacles, mlKitObstacles)

                Timber.d("检测完成: 房间类型=${roomTypeResult.first.chineseName}, AIDetector障碍物=${indoorObstacles.size}, MLKit障碍物=${mlKitObstacles.size}, 合并后=${allObstacles.size}")

                IndoorScene(
                    roomType = roomTypeResult.first,
                    confidence = roomTypeResult.second,
                    obstacles = allObstacles
                )
            } catch (e: Exception) {
                Timber.e(e, "室内检测失败。异常类型: ${e.javaClass.simpleName}, 消息: ${e.message}")
                IndoorScene(
                    roomType = RoomType.UNKNOWN,
                    confidence = 0f,
                    obstacles = emptyList()
                )
            }
        }
    }

    /**
     * 使用 ML Kit Image Labeling 识别房间类型
     */
    private suspend fun detectRoomType(bitmap: Bitmap): Pair<RoomType, Float> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val labels = imageLabeler?.process(image)?.await()

            if (labels.isNullOrEmpty()) {
                Timber.d("ML Kit 未返回任何标签")
                return Pair(RoomType.UNKNOWN, 0f)
            }

            Timber.d("ML Kit 返回 ${labels.size} 个标签: ${labels.map { "${it.text}(${String.format("%.2f", it.confidence)})" }}")

            // 查找匹配的房间类型
            for (label in labels) {
                val roomType = labelToRoomType[label.text]
                if (roomType != null) {
                    Timber.d("检测到房间类型: ${label.text} -> ${roomType.chineseName} (置信度: ${label.confidence})")
                    return Pair(roomType, label.confidence)
                }
            }

            // 如果没有直接匹配，根据标签组合推断
            val labelTexts = labels.map { it.text.lowercase() }.toSet()
            val inferredType = inferRoomType(labelTexts)

            if (inferredType != RoomType.UNKNOWN) {
                Timber.d("通过标签组合推断房间类型: ${inferredType.chineseName}")
            }

            Pair(inferredType, labels.firstOrNull()?.confidence ?: 0.5f)
        } catch (e: Exception) {
            Timber.e(e, "房间类型识别失败。异常类型: ${e.javaClass.simpleName}, 消息: ${e.message}")
            Pair(RoomType.UNKNOWN, 0f)
        }
    }

    /**
     * 根据标签组合推断房间类型
     */
    private fun inferRoomType(labels: Set<String>): RoomType {
        val labelString = labels.joinToString(" ")

        return when {
            // 厨房特征
            labelString.contains("kitchen") ||
            labelString.contains("refrigerator") ||
            labelString.contains("oven") ||
            labelString.contains("stove") ||
            labelString.contains("microwave") -> RoomType.KITCHEN

            // 卧室特征
            labelString.contains("bed") ||
            labelString.contains("bedroom") ||
            labelString.contains("pillow") ||
            labelString.contains("blanket") -> RoomType.BEDROOM

            // 卫生间特征
            labelString.contains("bathroom") ||
            labelString.contains("toilet") ||
            labelString.contains("shower") ||
            labelString.contains("bathtub") -> RoomType.BATHROOM

            // 客厅特征
            labelString.contains("sofa") ||
            labelString.contains("couch") ||
            labelString.contains("tv") ||
            labelString.contains("television") ||
            labelString.contains("coffee table") -> RoomType.LIVING_ROOM

            // 楼梯特征
            labelString.contains("stairs") ||
            labelString.contains("staircase") ||
            labelString.contains("handrail") ||
            labelString.contains("step") -> RoomType.STAIRS

            // 走廊特征
            labelString.contains("door") ||
            labelString.contains("door handle") ||
            labelString.contains("hallway") ||
            labelString.contains("corridor") -> RoomType.HALLWAY

            // 阳台特征
            labelString.contains("railing") ||
            labelString.contains("outdoor") ||
            labelString.contains("balcony") ||
            labelString.contains("patio") -> RoomType.BALCONY

            else -> RoomType.UNKNOWN
        }
    }

    /**
     * 将 AIDetector 检测结果转换为室内障碍物
     */
    private fun convertToIndoorObstacle(detectedObstacle: DetectedObstacle): DetectedIndoorObstacle? {
        // 获取 COCO 类别索引（通过反向查找）
        val obstacleType = when (detectedObstacle.type) {
            com.blindpath.module_obstacle.domain.model.ObstacleType.CHAIR -> IndoorObstacleType.CHAIR
            com.blindpath.module_obstacle.domain.model.ObstacleType.SOFA -> IndoorObstacleType.SOFA
            com.blindpath.module_obstacle.domain.model.ObstacleType.TABLE -> IndoorObstacleType.TABLE
            com.blindpath.module_obstacle.domain.model.ObstacleType.BED -> IndoorObstacleType.BED
            com.blindpath.module_obstacle.domain.model.ObstacleType.STAIRS -> IndoorObstacleType.STAIRS
            else -> null
        }

        return obstacleType?.let {
            DetectedIndoorObstacle(
                type = it,
                confidence = detectedObstacle.confidence,
                distance = detectedObstacle.distance,
                direction = detectedObstacle.direction,
                boundingBox = detectedObstacle.boundingBox
            )
        }
    }

    /**
     * 使用 ML Kit 进行额外的室内物品检测
     */
    private suspend fun detectWithMlKit(bitmap: Bitmap): List<DetectedIndoorObstacle> {
        return try {
            val image = InputImage.fromBitmap(bitmap, 0)
            val labels = imageLabeler?.process(image)?.await()
            val results = mutableListOf<DetectedIndoorObstacle>()

            labels?.forEach { label ->
                val obstacleType = mlKitLabelToIndoorObstacle[label.text]
                if (obstacleType != null && label.confidence > 0.3f) {
                    // ML Kit Image Labeling 只返回标签和置信度，不提供位置信息。
                    // 以下位置数据为估算默认值，不应用于精确距离判断。
                    // 实际障碍物定位应依赖 AIDetector 的目标检测结果。
                    results.add(
                        DetectedIndoorObstacle(
                            type = obstacleType,
                            confidence = label.confidence,
                            distance = 2.0f, // 估算默认距离，非精确值
                            direction = Direction.CENTER,
                            boundingBox = BoundingBox(
                                left = 0.3f,
                                top = 0.3f,
                                right = 0.7f,
                                bottom = 0.7f
                            )
                        )
                    )
                }
            }

            if (results.isNotEmpty()) {
                Timber.d("ML Kit 检测到 ${results.size} 个室内物品: ${results.map { it.type.chineseName }}")
            }

            results
        } catch (e: Exception) {
            Timber.e(e, "ML Kit 室内物品检测失败。异常类型: ${e.javaClass.simpleName}, 消息: ${e.message}")
            emptyList()
        }
    }

    /**
     * 合并障碍物列表（去重）
     */
    private fun mergeObstacles(
        list1: List<DetectedIndoorObstacle>,
        list2: List<DetectedIndoorObstacle>
    ): List<DetectedIndoorObstacle> {
        val merged = mutableListOf<DetectedIndoorObstacle>()
        merged.addAll(list1)

        // 添加 list2 中不重复的项目
        list2.forEach { obstacle2 ->
            val isDuplicate = list1.any { obstacle1 ->
                obstacle1.type == obstacle2.type &&
                kotlin.math.abs(obstacle1.distance - obstacle2.distance) < 0.5f
            }
            if (!isDuplicate) {
                merged.add(obstacle2)
            }
        }

        // 按优先级和距离排序
        return merged.sortedWith(
            compareByDescending<DetectedIndoorObstacle> { it.type.priority }
                .thenBy { it.distance }
        )
    }

    /**
     * 检查模型是否已加载
     */
    fun isModelLoaded(): Boolean = isLoaded

    /**
     * 检查 AI 模型是否加载成功
     */
    fun isAiModelLoaded(): Boolean = aiModelLoaded

    /**
     * 检查 ML Kit 是否初始化成功
     */
    fun isMlKitInitialized(): Boolean = mlKitInitialized
}
