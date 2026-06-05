package com.blindpath.module_voice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blindpath.module_voice.domain.model.WakeWordConfig
import com.blindpath.module_voice.service.WakeWordBridgeService
import com.blindpath.module_voice.service.WakeWordService
import timber.log.Timber

/**
 * 唤醒词检测接收器
 *
 * ★★★ 修复 v2-2：移除 Hilt 注入（静态注册 BR 不支持），改用 BridgeService 桥接
 *
 * 原问题：
 * - @AndroidEntryPoint + @Inject 在 AndroidManifest 静态注册的 BroadcastReceiver 上不生效
 * - voiceCommandRepository 永远为 null → 唤醒信号丢失
 *
 * 修复方案：
 * - 移除 @AndroidEntryPoint 和 @Inject
 * - 收到唤醒词广播后，通过显式 Intent 转发给 WakeWordBridgeService
 * - BridgeService 运行在主进程，可正常使用 Hilt 注入获取 VoiceCommandRepository
 */
class WakeWordReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "WakeWordReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WakeWordService.ACTION_WAKE_WORD_DETECTED -> {
                val wakeWord = intent.getStringExtra(WakeWordService.EXTRA_WAKE_WORD)
                    ?: WakeWordConfig.DEFAULT_WAKE_WORD
                Timber.i("$TAG: Wake word detected (from external engine) - $wakeWord")

                // ★★★ 修复：不再直接调用 voiceCommandRepository（它永远是 null）
                // 改为转发给 WakeWordBridgeService，由桥接服务通过 Hilt 注入调用
                try {
                    val bridgeIntent = Intent(context, WakeWordBridgeService::class.java).apply {
                        action = WakeWordBridgeService.ACTION_WAKE_WORD_BRIDGE
                        putExtra(WakeWordBridgeService.EXTRA_WAKE_WORD, wakeWord)
                        setPackage(context.packageName)
                    }

                    // BridgeService 是非前台服务，用 startService 即可
                    // 如果服务未运行会自动创建，处理完后会自动销毁（START_NOT_STICKY）
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

            WakeWordService.ACTION_START -> {
                Timber.d("$TAG: Wake word service started")
            }

            WakeWordService.ACTION_STOP -> {
                Timber.d("$TAG: Wake word service stopped")
            }

            else -> {
                Timber.d("$TAG: Unknown action: ${intent.action}")
            }
        }
    }
}
