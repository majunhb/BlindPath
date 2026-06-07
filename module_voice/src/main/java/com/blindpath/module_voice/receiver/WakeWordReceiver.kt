package com.blindpath.module_voice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blindpath.module_voice.domain.model.WakeWordConfig
import com.blindpath.module_voice.service.WakeWordBridgeService
import timber.log.Timber

/**
 * 唤醒词检测接收器
 *
 * 修复 v2-2：移除 Hilt 注入（静态注册 BR 不支持），改用 BridgeService 桥接
 *
 * 修复 v2-3：WakeWordService 已删除，所有 action 常量迁移到 WakeWordBridgeService。
 */
class WakeWordReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WakeWordReceiver"

        // 迁移自已删除的 WakeWordService
        const val ACTION_WAKE_WORD_DETECTED = "com.blindpath.wakeword.DETECTED"
        const val ACTION_START = "com.blindpath.wakeword.START"
        const val ACTION_STOP = "com.blindpath.wakeword.STOP"
        const val EXTRA_WAKE_WORD = "wake_word"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_WAKE_WORD_DETECTED -> {
                val wakeWord = intent.getStringExtra(EXTRA_WAKE_WORD)
                    ?: WakeWordConfig.DEFAULT_WAKE_WORD
                Timber.i("$TAG: Wake word detected (from external engine) - $wakeWord")

                try {
                    val bridgeIntent = Intent(context, WakeWordBridgeService::class.java).apply {
                        action = WakeWordBridgeService.ACTION_WAKE_WORD_BRIDGE
                        putExtra(WakeWordBridgeService.EXTRA_WAKE_WORD, wakeWord)
                        setPackage(context.packageName)
                    }

                    if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                        context.startForegroundService(bridgeIntent)
                    } else {
                        context.startService(bridgeIntent)
                    }

                    Timber.i("$TAG: Bridged wake word to WakeWordBridgeService - $wakeWord")
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to bridge wake word to service")
                }
            }

            ACTION_START -> {
                Timber.d("$TAG: Wake word service started")
            }

            ACTION_STOP -> {
                Timber.d("$TAG: Wake word service stopped")
            }

            else -> {
                Timber.d("$TAG: Unknown action: ${intent.action}")
            }
        }
    }
}
