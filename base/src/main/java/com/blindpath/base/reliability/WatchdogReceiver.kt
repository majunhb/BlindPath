package com.blindpath.base.reliability

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import timber.log.Timber

class WatchdogReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == "com.blindpath.action.WATCHDOG_CHECK") {
            Timber.d("Watchdog alarm triggered")
            // 通过静态方法触发检查
            DetectionServiceWatchdog.checkFromAlarm(context)
        }
    }
}
