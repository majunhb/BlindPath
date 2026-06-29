package com.blindpath.base.reliability

import android.app.ActivityManager
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import timber.log.Timber

/**
 * ★ 唤醒词服务看门狗
 *
 * 解决问题：华为/荣耀设备的 iAwareF (SystemManager) 会强杀 :wakeword 进程，
 * START_STICKY 在这些设备上不可靠。
 *
 * 方案：主进程通过 AlarmManager 每 60 秒检查一次 wakeword 进程是否存活，
 * 若不存活则通过 startForegroundService 重启。
 *
 * 为什么用 AlarmManager 而不是 WorkManager：
 * - WorkManager 最小间隔 15 分钟，太慢
 * - AlarmManager.setExactAndAllowWhileIdle 可以在 Doze 模式下唤醒
 * - 60 秒间隔对电池影响可控
 */
class WakeWordWatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CHECK_WAKEWORD) return
        
        Timber.d("WakeWordWatchdog: Checking wakeword process...")
        
        // 检查 wakeword 进程是否存活
        val isAlive = isWakeWordProcessAlive(context)
        
        if (!isAlive) {
            Timber.w("WakeWordWatchdog: ★ wakeword process DEAD, restarting...")
            restartWakeWordService(context)
        } else {
            Timber.d("WakeWordWatchdog: wakeword process alive")
        }
        
        // 调度下一次检查
        scheduleNextCheck(context)
    }
    
    companion object {
        private const val ACTION_CHECK_WAKEWORD = "com.blindpath.action.CHECK_WAKEWORD"
        private const val CHECK_INTERVAL_MS = 60_000L // 60秒
        private const val WAKEWORD_PROCESS_SUFFIX = ":wakeword"
        
        fun start(context: Context) {
            Timber.i("WakeWordWatchdog: Starting watchdog")
            scheduleNextCheck(context)
        }
        
        fun stop(context: Context) {
            Timber.i("WakeWordWatchdog: Stopping watchdog")
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = getPendingIntent(context)
            alarmManager.cancel(pendingIntent)
        }
        
        private fun scheduleNextCheck(context: Context) {
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pendingIntent = getPendingIntent(context)
            val triggerTime = SystemClock.elapsedRealtime() + CHECK_INTERVAL_MS
            
            when {
                // Android 12+ 需要检查是否有精确闹钟权限
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Timber.d("WakeWordWatchdog: Scheduled exact alarm (SCHEDULE_EXACT_ALARM granted)")
                    } else {
                        // [P1 修复 2026-06-29] 降级：使用非精确闹钟，避免 SecurityException 崩溃
                        // 看门狗间隔60秒，允许几秒偏差，不影响保活效果
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.ELAPSED_REALTIME_WAKEUP,
                            triggerTime,
                            pendingIntent
                        )
                        Timber.w("WakeWordWatchdog: ★ SCHEDULE_EXACT_ALARM not granted, fallback to inexact alarm")
                    }
                }
                // Android 6~11：直接使用精确闹钟（无需额外权限）
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
                // Android 5 及以下
                else -> {
                    alarmManager.setExact(
                        AlarmManager.ELAPSED_REALTIME_WAKEUP,
                        triggerTime,
                        pendingIntent
                    )
                }
            }
        }
        
        private fun getPendingIntent(context: Context): PendingIntent {
            val intent = Intent(context, WakeWordWatchdogReceiver::class.java).apply {
                action = ACTION_CHECK_WAKEWORD
            }
            return PendingIntent.getBroadcast(
                context,
                1001,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        }
        
        /**
         * 检查 wakeword 进程是否存活
         */
        private fun isWakeWordProcessAlive(context: Context): Boolean {
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val packageName = context.packageName
            val targetProcessName = "$packageName$WAKEWORD_PROCESS_SUFFIX"
            
            @Suppress("DEPRECATION")
            val processes = activityManager.runningAppProcesses ?: return false
            
            return processes.any { process ->
                process.processName == targetProcessName
            }
        }
        
        /**
         * 重启 wakeword 服务
         * base 模块不依赖 module_voice，使用反射获取 Service Class
         */
        private fun restartWakeWordService(context: Context) {
            try {
                val serviceClass = Class.forName("com.blindpath.module_voice.service.WakeWordServiceEnhanced")
                val intent = Intent(context, serviceClass).apply {
                    action = "com.blindpath.wakeword.START"
                }
                
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                
                Timber.i("WakeWordWatchdog: ★ WakeWord service restarted")
            } catch (e: Exception) {
                Timber.e(e, "WakeWordWatchdog: Failed to restart wakeword service")
            }
        }
    }
}
