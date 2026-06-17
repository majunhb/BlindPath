package com.blindpath.base.work

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * 后台任务调度器
 * 统一管理所有周期性后台任务
 */
object WorkScheduler {
    
    /**
     * 初始化所有后台任务
     */
    fun initialize(context: Context) {
        // 调度数据清理任务
        scheduleDataCleanup(context)
        
        // 调度使用统计上报任务
        scheduleUsageStatsUpload(context)
    }
    
    /**
     * 调度数据清理任务
     */
    private fun scheduleDataCleanup(context: Context) {
        val request = PeriodicWorkRequestBuilder<DataCleanupWorker>(
            1, TimeUnit.DAYS
        ).build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            DataCleanupWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
    
    /**
     * 调度使用统计上报任务
     */
    private fun scheduleUsageStatsUpload(context: Context) {
        val request = PeriodicWorkRequestBuilder<UsageStatsWorker>(
            6, TimeUnit.HOURS
        ).build()
        
        WorkManager.getInstance(context).enqueueUniquePeriodicWork(
            UsageStatsWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }
    
    /**
     * 取消所有后台任务
     */
    fun cancelAll(context: Context) {
        WorkManager.getInstance(context).cancelAllWork()
    }
}
