package com.blindpath.base.reliability

import android.app.job.JobParameters
import android.app.job.JobService
import android.content.Intent
import timber.log.Timber

/**
 * JobScheduler回调服务，负责在被系统杀死后重启ObstacleService
 */
class ServiceRestartJobService : JobService() {

    override fun onStartJob(params: JobParameters): Boolean {
        val shouldRestart = params.extras?.getBoolean("restart_detection", false) ?: false
        if (shouldRestart) {
            Timber.w("ServiceRestartJob: Attempting to restart ObstacleService")
            try {
                val intent = Intent().apply {
                    setClassName("com.blindpath.app", "com.blindpath.module_obstacle.service.ObstacleService")
                    action = "com.blindpath.action.START_OBSTACLE"
                }
                startForegroundService(intent)
                Timber.i("ServiceRestartJob: ObstacleService restart requested")
            } catch (e: Exception) {
                Timber.e(e, "ServiceRestartJob: Failed to restart ObstacleService")
            }
        }
        jobFinished(params, false)
        return false
    }

    override fun onStopJob(params: JobParameters): Boolean {
        // 系统取消时不需要重新调度
        return false
    }
}
