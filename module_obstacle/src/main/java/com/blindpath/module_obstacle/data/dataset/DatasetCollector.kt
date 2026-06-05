package com.blindpath.module_obstacle.data.dataset

import android.content.Context
import android.graphics.Bitmap
import android.os.Environment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [数据集收集] 视障场景数据自动采集服务
 * 
 * 功能：
 * 1. 自动保存摄像头帧用于后续标注
 * 2. 按场景分类存储（盲道/斑马线/障碍物等）
 * 3. 记录GPS位置和时间戳
 * 4. 支持手动标记场景类型
 * 
 * 使用方式：
 * - 开发阶段：开启自动收集，积累训练数据
 * - 测试阶段：用户可主动标记"当前是盲道"等
 */
@Singleton
class DatasetCollector @Inject constructor(
    private val context: Context
) {
    private val dateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.CHINA)
    
    // 数据集根目录
    private val datasetDir by lazy {
        File(context.getExternalFilesDir(null), "blindpath_dataset").apply { mkdirs() }
    }
    
    /**
     * 保存一帧图像到数据集
     * @param bitmap 摄像头帧
     * @param category 场景类别（盲道/斑马线/障碍物等）
     * @param autoLabeled 是否自动标注（false=用户手动确认）
     */
    suspend fun saveFrame(
        bitmap: Bitmap,
        category: String,
        autoLabeled: Boolean = false
    ): String = withContext(Dispatchers.IO) {
        val timestamp = dateFormat.format(Date())
        val labelPrefix = if (autoLabeled) "auto" else "manual"
        val filename = "${labelPrefix}_${category}_${timestamp}.jpg"
        
        val categoryDir = File(datasetDir, category).apply { mkdirs() }
        val file = File(categoryDir, filename)
        
        FileOutputStream(file).use { out ->
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
        }
        
        Timber.d("Dataset: Saved $category frame to ${file.absolutePath}")
        file.absolutePath
    }
    
    /**
     * 获取已收集的数据统计
     */
    fun getCollectionStats(): Map<String, Int> {
        val stats = mutableMapOf<String, Int>()
        datasetDir.listFiles()?.forEach { dir ->
            if (dir.isDirectory) {
                stats[dir.name] = dir.listFiles()?.size ?: 0
            }
        }
        return stats
    }
    
    /**
     * 导出数据集为YOLO格式
     */
    suspend fun exportToYoloFormat(): File = withContext(Dispatchers.IO) {
        val exportDir = File(datasetDir, "yolo_export").apply { mkdirs() }
        
        // 创建目录结构
        val imagesDir = File(exportDir, "images").apply { mkdirs() }
        val labelsDir = File(exportDir, "labels").apply { mkdirs() }
        
        // TODO: 将收集的图像和标注转换为YOLO格式
        // 需要配合标注工具（如LabelImg）完成人工标注
        
        Timber.i("Dataset: Exported to ${exportDir.absolutePath}")
        exportDir
    }
    
    companion object {
        // 预定义的场景类别
        val CATEGORIES = listOf(
            "sidewalk",      // 人行道
            "blind_path",    // 盲道
            "zebra_crossing",// 斑马线
            "traffic_light", // 红绿灯
            "obstacle",      // 障碍物
            "stairs",        // 楼梯
            "curb",          // 路沿
            "manhole",       // 井盖
            "puddle",        // 水坑
            "vehicle",       // 车辆
            "person",        // 行人
            "indoor",        // 室内
            "hospital",      // 医院
            "bank",          // 银行
            "school",        // 学校
            "station",       // 车站
            "mall",          // 商场
            "restaurant",    // 餐厅
            "unknown"        // 未知
        )
    }
}
