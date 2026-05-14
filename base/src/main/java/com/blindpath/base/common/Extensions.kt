package com.blindpath.base.common

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.text.SimpleDateFormat
import java.util.*

// ============ 权限相关 ============

fun Context.hasPermission(permission: String): Boolean {
    return ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
}

fun Context.hasPermissions(permissions: Array<String>): Boolean {
    return permissions.all { hasPermission(it) }
}

// ============ 震动反馈 ============

fun Context.vibrate(pattern: LongArray = longArrayOf(0, 200)) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(pattern, -1)
    }
}

fun Context.vibrateOnce(durationMs: Long = 200) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val manager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        manager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(durationMs, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(durationMs)
    }
}

// ============ 协程扩展 ============

suspend inline fun <T> withMainContext(crossinline block: suspend () -> T): T {
    return withContext(Dispatchers.Main) { block() }
}

suspend inline fun <T> withIOContext(crossinline block: suspend () -> T): T {
    return withContext(Dispatchers.IO) { block() }
}

suspend inline fun retry(
    times: Int = 3,
    delayMs: Long = 1000,
    crossinline block: suspend () -> Boolean
): Boolean {
    repeat(times) { index ->
        if (block()) return true
        if (index < times - 1) {
            Timber.w("Retry attempt ${index + 2} after ${delayMs}ms")
            delay(delayMs)
        }
    }
    return false
}

// ============ 格式化工具 ============

fun Float.formatDistance(): String {
    return when {
        this < 1f -> "${(this * 100).toInt()}厘米"
        this < 10f -> String.format("%.1f米", this)
        else -> "${this.toInt()}米"
    }
}

fun Int.formatTime(): String {
    return when {
        this < 60 -> "${this}秒"
        this < 3600 -> "${this / 60}分${this % 60}秒"
        else -> "${this / 3600}小时${(this % 3600) / 60}分"
    }
}

fun Long.formatTimestamp(pattern: String = "yyyy-MM-dd HH:mm:ss"): String {
    return SimpleDateFormat(pattern, Locale.getDefault()).format(Date(this))
}

// ============ 数学工具 ============

fun Float.clamp(min: Float, max: Float): Float {
    return if (this < min) min else if (this > max) max else this
}

fun Int.clamp(min: Int, max: Int): Int {
    return if (this < min) min else if (this > max) max else this
}
