package com.blindpath.module_voice.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.blindpath.module_voice.domain.model.WakeWordConfig
import com.blindpath.module_voice.service.WakeWordService
import dagger.hilt.android.AndroidEntryPoint
import timber.log.Timber
import javax.inject.Inject

/**
 * 唤醒词检测接收器
 *
 * 接收 WakeWordService 发送的唤醒词检测广播，
 * 触发 VoiceCommandRepository 进入指令识别模式
 */
@AndroidEntryPoint
class WakeWordReceiver : BroadcastReceiver() {

    @Inject
    lateinit var voiceCommandRepository: com.blindpath.module_voice.domain.VoiceCommandRepository

    companion object {
        private const val TAG = "WakeWordReceiver"
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            WakeWordService.ACTION_WAKE_WORD_DETECTED -> {
                val wakeWord = intent.getStringExtra(WakeWordService.EXTRA_WAKE_WORD) ?: WakeWordConfig.DEFAULT_WAKE_WORD
                Timber.i("$TAG: Wake word detected - $wakeWord")

                // 触发语音指令识别
                try {
                    voiceCommandRepository.triggerWakeWordDetected(wakeWord)
                    Timber.i("$TAG: Triggered command recognition for wake word: $wakeWord")
                } catch (e: Exception) {
                    Timber.e(e, "$TAG: Failed to trigger command recognition")
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