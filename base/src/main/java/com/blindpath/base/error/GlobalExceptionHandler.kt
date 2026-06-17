package com.blindpath.base.error

import android.content.Context
import android.os.Process
import timber.log.Timber
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 全局异常捕获器
 * 捕获应用中未处理的异常，防止崩溃
 */
class GlobalExceptionHandler(
    private val context: Context,
    private val defaultHandler: Thread.UncaughtExceptionHandler?,
    private val crashLogDir: File = File(context.cacheDir, "crash_logs")
) : Thread.UncaughtExceptionHandler {
    
    companion object {
        private const val MAX_CRASH_LOGS = 10
        
        @Volatile
        private var instance: GlobalExceptionHandler? = null
        
        /**
         * 初始化全局异常捕获器
         */
        fun initialize(context: Context) {
            if (instance != null) {
                Timber.w("GlobalExceptionHandler already initialized")
                return
            }
            
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            instance = GlobalExceptionHandler(context, defaultHandler)
            Thread.setDefaultUncaughtExceptionHandler(instance)
            
            Timber.d("GlobalExceptionHandler initialized")
        }
        
        /**
         * 获取崩溃日志列表
         */
        fun getCrashLogs(context: Context): List<File> {
            val crashDir = File(context.cacheDir, "crash_logs")
            if (!crashDir.exists()) return emptyList()
            
            return crashDir.listFiles()
                ?.filter { it.name.endsWith(".txt") }
                ?.sortedByDescending { it.lastModified() }
                ?: emptyList()
        }
        
        /**
         * 清除所有崩溃日志
         */
        fun clearCrashLogs(context: Context) {
            val crashDir = File(context.cacheDir, "crash_logs")
            crashDir.listFiles()?.forEach { it.delete() }
            Timber.d("Crash logs cleared")
        }
    }
    
    private val errorListeners = CopyOnWriteArrayList<ErrorListener>()
    
    /**
     * 添加错误监听器
     */
    fun addErrorListener(listener: ErrorListener) {
        errorListeners.add(listener)
    }
    
    /**
     * 移除错误监听器
     */
    fun removeErrorListener(listener: ErrorListener) {
        errorListeners.remove(listener)
    }
    
    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        Timber.e(throwable, "Uncaught exception in thread: ${thread.name}")
        
        // 保存崩溃日志
        saveCrashLog(thread, throwable)
        
        // 通知监听器
        errorListeners.forEach { listener ->
            try {
                listener.onError(thread, throwable)
            } catch (e: Exception) {
                Timber.e(e, "Error in error listener")
            }
        }
        
        // 调用默认处理器（如果存在）
        defaultHandler?.uncaughtException(thread, throwable)
    }
    
    private fun saveCrashLog(thread: Thread, throwable: Throwable) {
        try {
            if (!crashLogDir.exists()) {
                crashLogDir.mkdirs()
            }
            
            // 清理旧日志
            cleanupOldLogs()
            
            // 生成日志文件名
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val logFile = File(crashLogDir, "crash_$timestamp.txt")
            
            // 写入日志
            FileWriter(logFile).use { writer ->
                PrintWriter(writer).use { printWriter ->
                    printWriter.println("=== BlindPath Crash Log ===")
                    printWriter.println("Time: ${Date()}")
                    printWriter.println("Thread: ${thread.name} (${thread.id})")
                    printWriter.println("App Version: ${getAppVersion()}")
                    printWriter.println("Android: ${android.os.Build.VERSION.SDK_INT}")
                    printWriter.println("Device: ${android.os.Build.MODEL}")
                    printWriter.println()
                    printWriter.println("=== Stack Trace ===")
                    throwable.printStackTrace(printWriter)
                }
            }
            
            Timber.d("Crash log saved: ${logFile.absolutePath}")
        } catch (e: Exception) {
            Timber.e(e, "Failed to save crash log")
        }
    }
    
    private fun cleanupOldLogs() {
        val logs = crashLogDir.listFiles()
            ?.filter { it.name.startsWith("crash_") && it.name.endsWith(".txt") }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        
        // 保留最近的 MAX_CRASH_LOGS 个日志
        logs.drop(MAX_CRASH_LOGS).forEach { it.delete() }
    }
    
    private fun getAppVersion(): String {
        return try {
            val packageInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            "${packageInfo.versionName} (${packageInfo.versionCode})"
        } catch (e: Exception) {
            "Unknown"
        }
    }
    
    /**
     * 错误监听器接口
     */
    interface ErrorListener {
        fun onError(thread: Thread, throwable: Throwable)
    }
}


