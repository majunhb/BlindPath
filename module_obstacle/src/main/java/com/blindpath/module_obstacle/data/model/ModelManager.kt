package com.blindpath.module_obstacle.data.model

import android.content.Context
import com.blindpath.base.reliability.ReliabilityLogger
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 模型 OTA 管理器
 * 
 * 功能:
 * 1. 从 GitHub Releases 下载最新模型
 * 2. SHA256 签名校验确保完整性
 * 3. 版本管理 - 本地版本 vs 远程版本
 * 4. 下载失败回退到已缓存的模型
 */
@Singleton
class ModelManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    companion object {
        private const val TAG = "ModelManager"
        private const val MODEL_DIR = "models"
        private const val MODEL_FILENAME = "yolov8n.tflite"
        private const val VERSION_FILENAME = "model_version.txt"
        private const val DEFAULT_MODEL_URL = "https://github.com/majunhb/BlindPath/releases/download/models-v1/yolov8n.tflite"
        
        // 已知版本的 SHA256（发布时更新）
        val KNOWN_CHECKSUMS = mapOf(
            "v1" to "",  // 首次安装，不校验
        )
    }

    private val modelDir = File(context.filesDir, MODEL_DIR).also { it.mkdirs() }

    /**
     * 获取模型文件路径
     * 优先级: OTA下载版本 > 本地assets版本
     */
    fun getModelPath(): String {
        val otaModel = File(modelDir, MODEL_FILENAME)
        return if (otaModel.exists()) {
            Timber.i("$TAG: Using OTA model: ${otaModel.absolutePath}")
            otaModel.absolutePath
        } else {
            Timber.i("$TAG: Using assets model")
            ""  // 空字符串表示使用assets中的模型
        }
    }

    /**
     * 获取当前本地模型版本
     */
    fun getLocalVersion(): String {
        val versionFile = File(modelDir, VERSION_FILENAME)
        return if (versionFile.exists()) {
            versionFile.readText().trim()
        } else {
            "assets"
        }
    }

    /**
     * 检查是否有新版本可用
     */
    suspend fun checkForUpdate(): ModelUpdateInfo? = withContext(Dispatchers.IO) {
        try {
            Timber.i("$TAG: Checking for model updates...")
            
            // 从 GitHub API 获取最新 release 信息
            val releaseInfo = fetchLatestReleaseInfo()
            
            if (releaseInfo != null && releaseInfo.version != getLocalVersion()) {
                Timber.i("$TAG: New version available: ${releaseInfo.version} (local: ${getLocalVersion()})")
                return@withContext releaseInfo
            }
            
            Timber.i("$TAG: Model is up to date")
            null
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to check for updates")
            ReliabilityLogger.logFallback("model_update_check", e.message)
            null
        }
    }

    /**
     * 下载并安装模型更新
     */
    suspend fun downloadAndInstall(url: String, version: String, expectedChecksum: String = ""): ModelDownloadResult {
        return withContext(Dispatchers.IO) {
            try {
                Timber.i("$TAG: Downloading model $version from $url")
                ReliabilityLogger.logMetric("model_download_start", mapOf("version" to version))

                val tempFile = File(modelDir, "${MODEL_FILENAME}.download")
                
                // 下载文件
                downloadFile(url, tempFile)
                
                if (!tempFile.exists() || tempFile.length() == 0L) {
                    Timber.e("$TAG: Download produced empty file")
                    ReliabilityLogger.logFallback("model_download", "empty_file")
                    return@withContext ModelDownloadResult.Failed("Download produced empty file")
                }

                // SHA256 校验
                if (expectedChecksum.isNotEmpty()) {
                    val actualChecksum = calculateSHA256(tempFile)
                    if (actualChecksum != expectedChecksum) {
                        Timber.e("$TAG: Checksum mismatch! expected=$expectedChecksum, actual=$actualChecksum")
                        ReliabilityLogger.logFallback("model_checksum", "mismatch")
                        tempFile.delete()
                        return@withContext ModelDownloadResult.Failed("Checksum verification failed")
                    }
                    Timber.i("$TAG: Checksum verified for version $version")
                }

                // 原子替换: 先写临时文件，校验通过后 rename
                val targetFile = File(modelDir, MODEL_FILENAME)
                val backupFile = File(modelDir, "${MODEL_FILENAME}.backup")
                
                // 备份旧文件
                if (targetFile.exists()) {
                    targetFile.renameTo(backupFile)
                }
                
                try {
                    tempFile.renameTo(targetFile)
                    
                    // 写入版本号
                    File(modelDir, VERSION_FILENAME).writeText(version)
                    
                    // 清理备份
                    backupFile.delete()
                    
                    Timber.i("$TAG: Model $version installed successfully (${targetFile.length()} bytes)")
                    ReliabilityLogger.logMetric("model_download_success", mapOf(
                        "version" to version,
                        "size_bytes" to targetFile.length()
                    ))
                    
                    ModelDownloadResult.Success(targetFile.absolutePath, version)
                } catch (e: Exception) {
                    // 恢复备份
                    if (backupFile.exists()) {
                        backupFile.renameTo(targetFile)
                    }
                    throw e
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Model download failed")
                ReliabilityLogger.logFallback("model_download", e.message)
                ModelDownloadResult.Failed(e.message ?: "Unknown error")
            }
        }
    }

    /**
     * 回退到 assets 中的模型
     */
    fun rollbackToAssets() {
        val targetFile = File(modelDir, MODEL_FILENAME)
        if (targetFile.exists()) {
            targetFile.delete()
        }
        val versionFile = File(modelDir, VERSION_FILENAME)
        if (versionFile.exists()) {
            versionFile.delete()
        }
        Timber.i("$TAG: Rolled back to assets model")
        ReliabilityLogger.logMetric("model_rollback", mapOf("target" to "assets"))
    }

    /**
     * 获取模型信息
     */
    fun getModelInfo(): ModelInfo {
        val targetFile = File(modelDir, MODEL_FILENAME)
        return ModelInfo(
            version = getLocalVersion(),
            path = getModelPath(),
            sizeBytes = if (targetFile.exists()) targetFile.length() else -1,
            source = if (targetFile.exists()) "ota" else "assets"
        )
    }

    // ========== 私有方法 ==========

    /**
     * 获取最新 release 信息
     */
    private suspend fun fetchLatestReleaseInfo(): ModelUpdateInfo? {
        return withContext(Dispatchers.IO) {
            try {
                val url = java.net.URL("https://api.github.com/repos/majunhb/BlindPath/releases/tags/models-v1")
                val connection = url.openConnection() as java.net.HttpURLConnection
                connection.connectTimeout = 10000
                connection.readTimeout = 10000
                connection.setRequestProperty("Accept", "application/vnd.github+json")
                
                val response = connection.inputStream.bufferedReader().readText()
                connection.disconnect()

                // 简单 JSON 解析（避免引入 Gson/Moshi 依赖）
                val tagName = extractJsonValue(response, "tag_name")
                if (tagName == null) return@withContext null

                // 查找 tflite 文件的下载 URL
                val assetsUrl = extractJsonValue(response, "assets_url")
                if (assetsUrl == null) return@withContext null

                val downloadUrl = extractFirstAssetUrl(assetsUrl)
                if (downloadUrl == null) return@withContext null

                ModelUpdateInfo(
                    version = tagName,
                    downloadUrl = downloadUrl,
                    sizeBytes = 0L  // 从 GitHub API 无法简单获取大小
                )
            } catch (e: Exception) {
                Timber.w(e, "$TAG: Failed to fetch release info")
                null
            }
        }
    }

    /**
     * 下载文件
     */
    private fun downloadFile(url: String, destFile: File) {
        val connection = java.net.URL(url).openConnection() as java.net.HttpURLConnection
        connection.connectTimeout = 30000
        connection.readTimeout = 120000
        connection.setRequestProperty("Accept", "application/octet-stream")
        
        try {
            connection.inputStream.use { input ->
                destFile.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var bytesRead: Int
                    val totalSize = connection.contentLengthLong
                    var downloaded = 0L
                    
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        downloaded += bytesRead
                        if (totalSize > 0 && downloaded % (totalSize / 10) < 8192) {
                            Timber.d("$TAG: Download progress: ${downloaded * 100 / totalSize}%")
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * 计算文件 SHA256
     */
    private fun calculateSHA256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(8192)
            var bytesRead: Int
            while (input.read(buffer).also { bytesRead = it } != -1) {
                digest.update(buffer, 0, bytesRead)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    /**
     * 简单的 JSON 值提取（避免引入第三方库）
     */
    private fun extractJsonValue(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }

    /**
     * 从 assets API 获取第一个文件下载 URL
     */
    private fun extractFirstAssetUrl(assetsUrl: String): String? {
        try {
            val url = java.net.URL(assetsUrl)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 10000
            connection.readTimeout = 10000
            connection.setRequestProperty("Accept", "application/vnd.github+json")
            
            val response = connection.inputStream.bufferedReader().readText()
            connection.disconnect()

            val downloadUrl = extractJsonValueFromArray(response, "browser_download_url")
            return downloadUrl
        } catch (e: Exception) {
            Timber.w(e, "$TAG: Failed to extract asset URL")
            return null
        }
    }

    /**
     * 从 JSON 数组中提取第一个匹配的值
     */
    private fun extractJsonValueFromArray(json: String, key: String): String? {
        val regex = """"$key"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        return regex.find(json)?.groupValues?.get(1)
    }
}

/**
 * 模型更新信息
 */
data class ModelUpdateInfo(
    val version: String,
    val downloadUrl: String,
    val sizeBytes: Long
)

/**
 * 模型下载结果
 */
sealed class ModelDownloadResult {
    data class Success(val path: String, val version: String) : ModelDownloadResult()
    data class Failed(val reason: String) : ModelDownloadResult()
}

/**
 * 模型信息
 */
data class ModelInfo(
    val version: String,
    val path: String,
    val sizeBytes: Long,
    val source: String  // "assets" or "ota"
)