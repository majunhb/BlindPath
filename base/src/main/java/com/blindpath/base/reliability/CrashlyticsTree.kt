package com.blindpath.base.reliability

import android.util.Log
import timber.log.Timber

/**
 * Release 模式的 Crashlytics 日志树
 * - ERROR/FATAL 级别上报到 Crashlytics
 * - WARN 级别记录非致命异常
 * - INFO/DEBUG/VERBOSE 忽略
 *
 * 注意: Firebase Crashlytics SDK 未集成前，先用 Log 输出 + 本地文件兜底
 */
class CrashlyticsTree : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        return priority >= Log.WARN
    }

    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        when {
            priority >= Log.ERROR -> {
                // TODO: Firebase Crashlytics 集成后替换为:
                // FirebaseCrashlytics.getInstance().recordException(t ?: RuntimeException(message))
                Log.e(tag, message, t)
                // 本地兜底: 写入文件
                ReliabilityLogger.logError(tag, message, t)
            }
            priority >= Log.WARN -> {
                // TODO: Firebase Analytics 集成后替换为:
                // FirebaseAnalytics.getInstance(context).logEvent("warning", bundleOf("tag" to tag, "msg" to message))
                Log.w(tag, message, t)
                ReliabilityLogger.logWarning(tag, message)
            }
        }
    }
}
