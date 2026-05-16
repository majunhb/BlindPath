package com.blindpath.base.tts

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.blindpath.base.common.AlertLevel

/**
 * 振动反馈辅助类
 */
object VibrationHelper {

    private const val TAG = "VibrationHelper"

    // 振动模式（毫秒）：停止、运行、停止、运行...
    private val DANGER_PATTERN = longArrayOf(0, 500, 200, 500, 200, 500)     // 危险：三次急促
    private val WARNING_PATTERN = longArrayOf(0, 300, 200, 300)               // 警告：两次中等
    private val SAFE_PATTERN = longArrayOf(0, 150)                             // 安全：一次轻微

    /**
     * 根据预警级别触发对应振动
     */
    fun vibrate(context: Context, level: AlertLevel) {
        val pattern = when (level) {
            AlertLevel.DANGER -> DANGER_PATTERN
            AlertLevel.WARNING -> WARNING_PATTERN
            AlertLevel.SAFE -> SAFE_PATTERN
        }

        vibrate(context, pattern, level != AlertLevel.SAFE)
    }

    /**
     * 自定义振动模式
     */
    fun vibrate(context: Context, pattern: LongArray, repeat: Boolean = false) {
        val vibrator = getVibrator(context) ?: return

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val effect = VibrationEffect.createWaveform(pattern, if (repeat) 1 else -1)
                vibrator.vibrate(effect)
            } else {
                @Suppress("DEPRECATION")
                vibrator.vibrate(pattern, if (repeat) 1 else -1)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Vibration failed: ${e.message}")
        }
    }

    /**
     * 取消振动
     */
    fun cancel(context: Context) {
        try {
            getVibrator(context)?.cancel()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun getVibrator(context: Context): Vibrator? {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
            vibratorManager.defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }
    }
}
