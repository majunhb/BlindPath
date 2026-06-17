package com.blindpath.module_voice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.blindpath.module_voice.service.WakeWordBridgeService
import timber.log.Timber

/**
 * 开机启动接收器
 *
 * 功能：
 * - 设备开机完成后自动启动语音唤醒服务
 * - 确保视障用户开机即可使用语音交互
 *
 * 修复：WakeWordService 已删除，改用 WakeWordBridgeService 作为唤醒词桥接服务。
 */
class BootCompletedReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            Timber.i("BootCompleted: Device booted, starting WakeWordBridgeService")

            val serviceIntent = Intent(context, WakeWordBridgeService::class.java).apply {
                action = WakeWordBridgeService.ACTION_WAKE_WORD_BRIDGE
            }

            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(serviceIntent)
                } else {
                    context.startService(serviceIntent)
                }
                Timber.i("BootCompleted: WakeWordBridgeService started successfully")
            } catch (e: Exception) {
                Timber.e(e, "BootCompleted: Failed to start WakeWordBridgeService")
            }
        }
    }
}
