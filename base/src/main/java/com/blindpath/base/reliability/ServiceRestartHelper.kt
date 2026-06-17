package com.blindpath.base.reliability

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.PersistableBundle
import timber.log.Timber

/**
 * 使用JobScheduler确保服务被系统杀死后能重启
 */
object ServiceRestartHelper {

    private const val SERVICE_RESTART_JOB_ID = 1001

    /**
     * 请求通过JobScheduler重启检测服务
     */
    fun requestRestart(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        val componentName = ComponentName(context, ServiceRestartJobService::class.java)

        val bundle = PersistableBundle()
        bundle.putBoolean("restart_detection", true)

        val jobInfo = JobInfo.Builder(SERVICE_RESTART_JOB_ID, componentName)
            .setMinimumLatency(1000L)          // 至少延迟1秒
            .setOverrideDeadline(5000L)        // 最多5秒内执行
            .setPersisted(true)                // 设备重启后仍然有效
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY) // 不需要网络
            .setExtras(bundle)
            .build()

        val result = jobScheduler.schedule(jobInfo)
        if (result == JobScheduler.RESULT_SUCCESS) {
            Timber.i("ServiceRestartJob scheduled successfully")
        } else {
            Timber.e("Failed to schedule ServiceRestartJob")
        }
    }

    /**
     * 取消重启任务
     */
    fun cancelRestart(context: Context) {
        val jobScheduler = context.getSystemService(Context.JOB_SCHEDULER_SERVICE) as JobScheduler
        jobScheduler.cancel(SERVICE_RESTART_JOB_ID)
        Timber.d("ServiceRestartJob cancelled")
    }
}
