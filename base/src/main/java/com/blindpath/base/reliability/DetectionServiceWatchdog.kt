package com.blindpath.base.reliability

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 检测服务看门狗
 * 每30秒检查一次ObstacleService是否存活，不存活则通过JobScheduler重启
 */
@Singleton
class DetectionServiceWatchdog @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val WATCHDOG_INTERVAL_MS = 30_000L
        private const val PREF_NAME = "blindpath_watchdog"
        private const val KEY_LAST_HEARTBEAT = "last_heartbeat"
        private const val HEARTBEAT_TIMEOUT_MS = 45_000L

        @Volatile
        private var instance: DetectionServiceWatchdog? = null

        fun getInstance(context: Context): DetectionServiceWatchdog? = instance

        fun checkFromAlarm(context: Context) {
            instance?.checkAndRestart()
        }
    }

    private val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    private var isRunning = false

    init {
        instance = this
    }

    /**
     * 记录心跳（由ObstacleService定期调用）
     */
    fun recordHeartbeat() {
        prefs.edit().putLong(KEY_LAST_HEARTBEAT, SystemClock.elapsedRealtime()).apply()
    }

    /**
     * 检查服务是否存活
     */
    fun isServiceAlive(): Boolean {
        val lastHeartbeat = prefs.getLong(KEY_LAST_HEARTBEAT, 0)
        val elapsed = SystemClock.elapsedRealtime() - lastHeartbeat
        return elapsed < HEARTBEAT_TIMEOUT_MS
    }

    /**
     * 启动看门狗
     */
    fun start() {
        if (isRunning) return
        isRunning = true
        Timber.i("DetectionServiceWatchdog started")
        recordHeartbeat()
        scheduleNextCheck()
    }

    /**
     * 停止看门狗
     */
    fun stop() {
        isRunning = false
        Timber.i("DetectionServiceWatchdog stopped")
    }

    private fun scheduleNextCheck() {
        if (!isRunning) return
        // 使用AlarmManager精确调度
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, WatchdogReceiver::class.java).apply {
            action = "com.blindpath.action.WATCHDOG_CHECK"
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, 0, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.set(
            AlarmManager.ELAPSED_REALTIME_WAKEUP,
            SystemClock.elapsedRealtime() + WATCHDOG_INTERVAL_MS,
            pendingIntent
        )
    }

    /**
     * 由WatchdogReceiver调用的检查入口
     */
    fun checkAndRestart() {
        if (!isRunning) return
        if (!isServiceAlive()) {
            Timber.w("Watchdog: Service heartbeat lost, requesting restart")
            ServiceRestartHelper.requestRestart(context)
        }
        scheduleNextCheck()
    }
}
