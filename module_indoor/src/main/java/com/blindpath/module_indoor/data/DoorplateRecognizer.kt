package com.blindpath.module_indoor.data

import android.graphics.Bitmap
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 门牌/标识文字 OCR 识别器
 *
 * 专注于门牌号、路标、指示牌等结构化文字识别：
 * - 画面中央区域 ROI 提取（减少 OCR 算力消耗）
 * - 结构化信息提取（门牌号、房间号、楼层号）
 * - 结果过滤（忽略背景广告文字）
 * - 语音播报优化
 *
 * 依赖：ML Kit Text Recognition（已存在于 TextRecognizer）
 */
@Singleton
class DoorplateRecognizer @Inject constructor(
    private val textRecognizer: TextRecognizer
) {
    // OCR 间隔（毫秒）
    private val ocrInterval = 2000L
    private var lastOcrTime = 0L

    // 门牌号正则
    private val roomNumberPattern = Regex("""\d{3,4}[室号]?|[\dA-Z]+[号室房]""")
    private val floorPattern = Regex("""[B\d]+[F楼層层]""")
    private val streetPattern = Regex("""[\u4e00-\u9fff]+[路街巷大道]""")

    /**
     * 识别门牌/标识文字
     *
     * @param bitmap 相机帧
     * @return DoorplateResult 识别结果
     */
    suspend fun recognize(bitmap: Bitmap): DoorplateResult? {
        // 控制 OCR 频率
        val now = System.currentTimeMillis()
        if (now - lastOcrTime < ocrInterval) {
            return null
        }
        lastOcrTime = now

        return withContext(Dispatchers.IO) {
            try {
                // 提取画面中央 40% 区域（门牌/标识最可能出现在中央）
                val roiWidth = (bitmap.width * 0.4f).toInt()
                val roiHeight = (bitmap.height * 0.4f).toInt()
                val roiLeft = (bitmap.width - roiWidth) / 2
                val roiTop = (bitmap.height - roiHeight) / 2

                val roiBitmap = Bitmap.createBitmap(bitmap, roiLeft, roiTop, roiWidth, roiHeight)

                val ocrResult = textRecognizer.recognize(roiBitmap)
                roiBitmap.recycle()

                val textBlocks = ocrResult.blocks
                if (textBlocks.isEmpty()) return@withContext null

                // 提取所有文字
                val allText = textBlocks.joinToString(" ") { it.text }

                // 确定文字类型
                val type = classifyDoorplateType(allText)

                // 过滤无意义文字
                if (allText.length < 2 || allText.length > 50) {
                    return@withContext null
                }

                Timber.d("DoorplateRecognizer: type=$type, text=$allText")

                DoorplateResult(
                    text = allText,
                    type = type,
                    confidence = ocrResult.confidence
                )
            } catch (e: Exception) {
                Timber.e(e, "DoorplateRecognizer: recognition failed")
                null
            }
        }
    }

    /**
     * 分类门牌文字类型
     */
    private fun classifyDoorplateType(text: String): DoorplateType {
        return when {
            roomNumberPattern.containsMatchIn(text) -> DoorplateType.ROOM_NUMBER
            floorPattern.containsMatchIn(text) -> DoorplateType.FLOOR
            streetPattern.containsMatchIn(text) -> DoorplateType.STREET_SIGN
            text.contains("出口") || text.contains("入口") || text.contains("EXIT") -> DoorplateType.EXIT_SIGN
            text.contains("WC") || text.contains("厕所") || text.contains("卫生间") -> DoorplateType.RESTROOM
            text.contains("电梯") || text.contains("ELEVATOR") -> DoorplateType.ELEVATOR
            else -> DoorplateType.UNKNOWN
        }
    }

    /**
     * 生成语音播报文本
     */
    fun getVoiceAnnouncement(result: DoorplateResult): String {
        return when (result.type) {
            DoorplateType.ROOM_NUMBER -> "门牌号：${result.text}"
            DoorplateType.FLOOR -> "${result.text}"
            DoorplateType.STREET_SIGN -> "${result.text}"
            DoorplateType.EXIT_SIGN -> "前方${result.text}"
            DoorplateType.RESTROOM -> "前方有${result.text}"
            DoorplateType.ELEVATOR -> "前方有${result.text}"
            DoorplateType.UNKNOWN -> "识别到文字：${result.text}"
        }
    }
}

/**
 * 门牌识别结果
 */
data class DoorplateResult(
    val text: String,
    val type: DoorplateType,
    val confidence: Float
)

/**
 * 门牌类型
 */
enum class DoorplateType(val chineseName: String) {
    ROOM_NUMBER("门牌号"),
    FLOOR("楼层"),
    STREET_SIGN("路牌"),
    EXIT_SIGN("出口标识"),
    RESTROOM("卫生间"),
    ELEVATOR("电梯"),
    UNKNOWN("未知")
}