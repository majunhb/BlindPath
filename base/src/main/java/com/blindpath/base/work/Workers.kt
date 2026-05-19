package com.blindpath.base.work

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.blindpath.base.common.BlindPathLog
import com.blindpath.base.data.local.AppUsageStatEntity
import com.blindpath.base.data.local.BlindPathDatabase
import com.blindpath.base.data.local.ObstacleDetectionEntity
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * 数据清理 Worker
 * 定期清理过期的历史数据
 */
@HiltWorker
class DataCleanupWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val database: BlindPathDatabase
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        const val WORK_NAME = "data_cleanup_work"
        
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<DataCleanupWorker>(
                1, TimeUnit.DAYS
            ).build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
    
    override suspend fun doWork(): Result {
        val tag = "DataCleanupWorker"
        val cutoffTime = System.currentTimeMillis() - (30 * 24 * 60 * 60 * 1000L) // 30天前
        
        return try {
            // 清理过期的导航历史
            val deletedNavHistory = database.navigationHistoryDao()
                .deleteOldHistory(cutoffTime)
            
            // 清理过期的障碍物检测历史
            val deletedDetections = database.obstacleDetectionDao()
                .deleteOldDetections(cutoffTime)
            
            // 清理过期的使用统计（保留90天）
            val cutoffDate = getDateString(System.currentTimeMillis() - (90 * 24 * 60 * 60 * 1000L))
            val deletedStats = database.appUsageStatsDao()
                .deleteOldStats(cutoffDate)
            
            BlindPathLog.i(tag, mapOf(
                "event" to "cleanup_completed",
                "deleted_nav_history" to deletedNavHistory,
                "deleted_detections" to deletedDetections,
                "deleted_stats" to deletedStats
            ))
            
            Result.success()
        } catch (e: Exception) {
            BlindPathLog.e(tag, mapOf(
                "event" to "cleanup_failed",
                "error" to (e.message ?: "unknown")
            ), e)
            Result.retry()
        }
    }
    
    private fun getDateString(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
}

/**
 * 使用统计上报 Worker
 * 定期汇总使用数据
 */
@HiltWorker
class UsageStatsWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val database: BlindPathDatabase
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        const val WORK_NAME = "usage_stats_work"
        
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<UsageStatsWorker>(
                6, TimeUnit.HOURS
            ).build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
    
    override suspend fun doWork(): Result {
        val tag = "UsageStatsWorker"
        
        return try {
            val today = getDateString(System.currentTimeMillis())
            val stats = database.appUsageStatsDao().getStatsForDate(today)
            
            BlindPathLog.i(tag, mapOf(
                "event" to "stats_collected",
                "date" to today,
                "features_count" to stats.size
            ))
            
            // 这里可以上报到服务器
            // analyticsReporter.report(stats)
            
            Result.success()
        } catch (e: Exception) {
            BlindPathLog.e(tag, mapOf(
                "event" to "stats_collection_failed",
                "error" to (e.message ?: "unknown")
            ), e)
            Result.retry()
        }
    }
    
    private fun getDateString(timestamp: Long): String {
        val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(timestamp))
    }
}

/**
 * 模型更新检查 Worker
 * 检查是否有新的 AI 模型版本
 */
@HiltWorker
class ModelUpdateWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters
) : CoroutineWorker(context, workerParams) {
    
    companion object {
        const val WORK_NAME = "model_update_work"
        
        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<ModelUpdateWorker>(
                7, TimeUnit.DAYS
            ).build()
            
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
        
        fun checkNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<ModelUpdateWorker>().build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "${WORK_NAME}_once",
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }
    
    override suspend fun doWork(): Result {
        val tag = "ModelUpdateWorker"
        
        return try {
            // 检查模型版本
            // 这里可以检查服务器是否有新版本模型
            BlindPathLog.i(tag, mapOf("event" to "model_check_completed"))
            Result.success()
        } catch (e: Exception) {
            BlindPathLog.e(tag, mapOf(
                "event" to "model_check_failed",
                "error" to (e.message ?: "unknown")
            ), e)
            Result.retry()
        }
    }
}

/**
 * 后台任务调度器
 * 统一管理所有 WorkManager 任务
 */
object WorkScheduler {
    
    private const val TAG = "WorkScheduler"
    
    /**
     * 初始化所有定期任务
     */
    fun initialize(context: Context) {
        BlindPathLog.i(TAG, mapOf("event" to "initializing_workers"))
        
        // 数据清理 - 每天执行
        DataCleanupWorker.schedule(context)
        
        // 使用统计 - 每6小时
        UsageStatsWorker.schedule(context)
        
        // 模型更新检查 - 每周
        ModelUpdateWorker.schedule(context)
        
        BlindPathLog.i(TAG, mapOf("event" to "workers_initialized"))
    }
    
    /**
     * 取消所有任务
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWork()
        BlindPathLog.i(TAG, mapOf("event" to "all_workers_cancelled"))
    }
}
