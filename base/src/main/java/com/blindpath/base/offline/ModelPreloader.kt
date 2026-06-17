package com.blindpath.base.offline

import android.content.Context
import android.content.SharedPreferences
import com.blindpath.base.config.AppConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 模型预加载管理器
 * 负责AI模型的下载、校验和预加载
 */
class ModelPreloader private constructor(
    private val context: Context
) {
    
    companion object {
        private const val PREFS_NAME = "model_prefs"
        private const val KEY_MODEL_VERSION = "model_version"
        private const val KEY_MODEL_CHECKSUM = "model_checksum"
        private const val KEY_LAST_CHECK_TIME = "last_check_time"
        
        // 模型下载地址（可配置）
        private const val MODEL_DOWNLOAD_URL = "https://github.com/ultralytics/assets/releases/download/v0.0.0/yolov8n.tflite"
        
        @Volatile
        private var instance: ModelPreloader? = null
        
        fun getInstance(context: Context): ModelPreloader {
            return instance ?: synchronized(this) {
                instance ?: ModelPreloader(context.applicationContext).also { instance = it }
            }
        }
    }
    
    private val prefs: SharedPreferences by lazy {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    private val isDownloading = AtomicBoolean(false)
    
    /**
     * 模型状态
     */
    sealed class ModelState {
        object NotDownloaded : ModelState()
        object Downloading : ModelState()
        data class Downloaded(val version: String, val size: Long) : ModelState()
        data class Error(val message: String) : ModelState()
    }
    
    /**
     * 检查模型是否已下载
     */
    fun isModelDownloaded(): Boolean {
        return getModelFile().exists()
    }
    
    /**
     * 获取模型文件
     */
    fun getModelFile(): File {
        return File(context.filesDir, AppConfig.AIDetection.MODEL_NAME)
    }
    
    /**
     * 获取模型状态
     */
    fun getModelState(): ModelState {
        val modelFile = getModelFile()
        
        if (isDownloading.get()) {
            return ModelState.Downloading
        }
        
        if (!modelFile.exists()) {
            return ModelState.NotDownloaded
        }
        
        return ModelState.Downloaded(
            version = prefs.getString(KEY_MODEL_VERSION, "unknown") ?: "unknown",
            size = modelFile.length()
        )
    }
    
    /**
     * 从assets复制模型到内部存储
     */
    suspend fun copyModelFromAssets(): Result<File> = withContext(Dispatchers.IO) {
        try {
            val modelFile = getModelFile()
            
            // 如果已存在且校验通过，直接返回
            if (modelFile.exists() && verifyModelChecksum(modelFile)) {
                Timber.d("Model already exists and verified")
                return@withContext Result.success(modelFile)
            }
            
            // 从assets复制
            context.assets.open(AppConfig.AIDetection.MODEL_NAME).use { input ->
                FileOutputStream(modelFile).use { output ->
                    input.copyTo(output)
                }
            }
            
            // 保存校验和
            val checksum = calculateChecksum(modelFile)
            prefs.edit()
                .putString(KEY_MODEL_CHECKSUM, checksum)
                .putString(KEY_MODEL_VERSION, "assets")
                .apply()
            
            Timber.d("Model copied from assets, size: ${modelFile.length()}")
            Result.success(modelFile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to copy model from assets")
            Result.failure(e)
        }
    }
    
    /**
     * 从网络下载模型
     */
    suspend fun downloadModel(
        progressCallback: ((progress: Int) -> Unit)? = null
    ): Result<File> = withContext(Dispatchers.IO) {
        if (isDownloading.get()) {
            return@withContext Result.failure(IllegalStateException("Download already in progress"))
        }
        
        isDownloading.set(true)
        
        try {
            val modelFile = getModelFile()
            val tempFile = File(context.cacheDir, "${AppConfig.AIDetection.MODEL_NAME}.tmp")
            
            Timber.d("Starting model download from: $MODEL_DOWNLOAD_URL")
            
            // 下载到临时文件
            URL(MODEL_DOWNLOAD_URL).openStream().use { input ->
                val totalSize = input.available()
                var downloaded = 0L
                val buffer = ByteArray(8192)
                
                FileOutputStream(tempFile).use { output ->
                    var bytesRead: Int
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        
                        // 报告进度
                        if (totalSize > 0) {
                            val progress = (downloaded * 100 / totalSize).toInt()
                            progressCallback?.invoke(progress)
                        }
                    }
                }
            }
            
            // 校验下载的文件
            if (tempFile.length() < 1000) { // 最小有效模型大小
                tempFile.delete()
                return@withContext Result.failure(IllegalStateException("Downloaded file is too small"))
            }
            
            // 移动到最终位置
            if (modelFile.exists()) {
                modelFile.delete()
            }
            tempFile.renameTo(modelFile)
            
            // 保存校验和
            val checksum = calculateChecksum(modelFile)
            prefs.edit()
                .putString(KEY_MODEL_CHECKSUM, checksum)
                .putString(KEY_MODEL_VERSION, "downloaded")
                .putLong(KEY_LAST_CHECK_TIME, System.currentTimeMillis())
                .apply()
            
            Timber.d("Model downloaded successfully, size: ${modelFile.length()}")
            Result.success(modelFile)
        } catch (e: Exception) {
            Timber.e(e, "Failed to download model")
            Result.failure(e)
        } finally {
            isDownloading.set(false)
        }
    }
    
    /**
     * 校验模型文件完整性
     */
    fun verifyModelChecksum(file: File): Boolean {
        if (!file.exists()) return false
        
        val savedChecksum = prefs.getString(KEY_MODEL_CHECKSUM, null) ?: return true // 没有保存的校验和，跳过校验
        val currentChecksum = calculateChecksum(file)
        
        return savedChecksum == currentChecksum
    }
    
    /**
     * 计算文件校验和
     */
    private fun calculateChecksum(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { fis ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (fis.read(buffer).also { bytesRead = it } != -1) {
                md.update(buffer, 0, bytesRead)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
    
    /**
     * 检查模型更新（检查远程版本）
     */
    suspend fun checkForUpdate(): Boolean = withContext(Dispatchers.IO) {
        // 简单实现：检查下载地址是否可访问
        // 实际实现应该检查版本号
        try {
            val lastCheck = prefs.getLong(KEY_LAST_CHECK_TIME, 0)
            val now = System.currentTimeMillis()
            
            // 24小时检查一次
            if (now - lastCheck < 24 * 60 * 60 * 1000) {
                return@withContext false
            }
            
            // TODO: 实际检查远程版本
            prefs.edit().putLong(KEY_LAST_CHECK_TIME, now).apply()
            
            false
        } catch (e: Exception) {
            Timber.e(e, "Failed to check for update")
            false
        }
    }
    
    /**
     * 删除模型文件
     */
    fun deleteModel() {
        val modelFile = getModelFile()
        if (modelFile.exists()) {
            modelFile.delete()
            prefs.edit()
                .remove(KEY_MODEL_VERSION)
                .remove(KEY_MODEL_CHECKSUM)
                .apply()
            Timber.d("Model deleted")
        }
    }
    
    /**
     * 预加载模型（在后台下载）
     */
    fun preloadInBackground(onComplete: (Result<File>) -> Unit) {
        if (isModelDownloaded()) {
            onComplete(Result.success(getModelFile()))
            return
        }

        // 使用协程替代 Thread + runBlocking 反模式
        CoroutineScope(Dispatchers.IO).launch {
            val result = runCatching {
                copyModelFromAssets().getOrThrow()
            }.recoverCatching {
                downloadModel().getOrThrow()
            }

            withContext(Dispatchers.Main) {
                onComplete(result)
            }
        }
    }
}
