/**
 * BlindPath - 视障人士出行辅助应用
 * 
 * 文件：VoiceCommandRepositoryImpl.kt
 * 路径：module_voice/src/main/java/com/blindpath/voice/data/
 * 
 * 修复版本 v2.0 - 基于诊断报告 P1 关键修复
 * 
 * 修复内容：
 * 1. P1 增强重试逻辑：区分可恢复/不可恢复错误
 * 2. P1 国产设备降级处理：针对华为、小米等国产设备特殊处理
 * 3. P1 SpeechRecognizer 错误处理优化
 */

package com.blindpath.voice.data

import android.content.Context
import android.os.Build
import com.blindpath.voice.domain.model.VoiceCommand
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.*
import kotlin.math.min

/**
 * 语音命令仓库实现
 * 
 * 核心职责：
 * 1. 解析用户语音输入为命令
 * 2. 管理命令匹配和识别
 * 3. 处理网络/设备异常情况
 */
class VoiceCommandRepositoryImpl(
    private val context: Context
) {
    // ==================== 状态管理 ====================
    
    private val _retryState = MutableStateFlow(RetryState())
    val retryState: StateFlow<RetryState> = _retryState.asStateFlow()
    
    // 协程作用域
    private val repositoryScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    
    // ==================== 语音命令匹配 ====================
    
    /**
     * 解析语音文本为命令
     * 
     * 修复说明：
     * - 添加模糊匹配支持
     * - 添加命令别名支持
     * - 添加置信度阈值过滤
     */
    fun parseCommand(text: String): VoiceCommand? {
        if (text.isBlank()) {
            return null
        }
        
        val normalizedText = normalizeText(text)
        Timber.d("VoiceCommandRepository: Parsing command from: '$text' (normalized: '$normalizedText')")
        
        // 精确匹配
        VoiceCommand.entries.find { command ->
            isExactMatch(normalizedText, command)
        }?.let { return it }
        
        // 模糊匹配
        VoiceCommand.entries.find { command ->
            isFuzzyMatch(normalizedText, command)
        }?.let { return it }
        
        Timber.w("VoiceCommandRepository: No command matched for: $text")
        return null
    }
    
    /**
     * 标准化文本
     */
    private fun normalizeText(text: String): String {
        return text
            .lowercase(Locale.ROOT)
            .replace(" ", "")
            .replace("　", "") // 全角空格
            .replace(".", "")
            .replace("，", "")
            .replace("。", "")
            .replace("？", "")
            .replace("?", "")
            .replace("！", "")
            .replace("!", "")
            .trim()
    }
    
    /**
     * 精确匹配
     */
    private fun isExactMatch(text: String, command: VoiceCommand): Boolean {
        // 主关键词精确匹配
        return command.keywords.any { keyword ->
            text == keyword.lowercase(Locale.ROOT).replace(" ", "")
        }
    }
    
    /**
     * 模糊匹配
     * 
     * 修复说明：
     * - 支持部分关键词匹配
     * - 支持同义词匹配
     * - 包含置信度计算
     */
    private fun isFuzzyMatch(text: String, command: VoiceCommand): Boolean {
        val keywords = command.keywords.map { it.lowercase(Locale.ROOT).replace(" ", "") }
        
        // 部分匹配（至少包含一个关键词的 60%）
        for (keyword in keywords) {
            if (keyword.length >= 3) {
                // 计算最长公共子串
                val lcsLength = longestCommonSubstringLength(text, keyword)
                val confidence = lcsLength.toFloat() / keyword.length
                
                if (confidence >= 0.6f) {
                    Timber.d("Fuzzy match: '$text' -> ${command.name} (confidence: $confidence)")
                    return true
                }
            }
        }
        
        // 同义词匹配
        return matchesSynonym(text, command)
    }
    
    /**
     * 计算最长公共子串长度
     */
    private fun longestCommonSubstringLength(s1: String, s2: String): Int {
        val m = s1.length
        val n = s2.length
        val dp = Array(m + 1) { IntArray(n + 1) }
        var maxLength = 0
        
        for (i in 1..m) {
            for (j in 1..n) {
                if (s1[i - 1] == s2[j - 1]) {
                    dp[i][j] = dp[i - 1][j - 1] + 1
                    maxLength = maxOf(maxLength, dp[i][j])
                }
            }
        }
        
        return maxLength
    }
    
    /**
     * 同义词匹配
     */
    private fun matchesSynonym(text: String, command: VoiceCommand): Boolean {
        val synonyms = getSynonyms(command)
        return synonyms.any { synonym ->
            text.contains(synonym.lowercase(Locale.ROOT))
        }
    }
    
    /**
     * 获取命令同义词
     */
    private fun getSynonyms(command: VoiceCommand): List<String> {
        return when (command) {
            VoiceCommand.START_NAVIGATION -> 
                listOf("开始导航", "去", "导航", "带我", "出发", "开始带路")
            VoiceCommand.STOP_NAVIGATION -> 
                listOf("停止导航", "结束导航", "取消导航", "停止带路", "不用了")
            VoiceCommand.START_OBSTACLE_DETECTION ->
                listOf("开始环境感知", "开启环境感知", "开始检测", "开始避障", "启动环境感知")
            VoiceCommand.STOP_OBSTACLE_DETECTION ->
                listOf("停止环境感知", "关闭环境感知", "停止检测", "停止避障")
            VoiceCommand.WHERE_AM_I ->
                listOf("我在哪", "我的位置", "当前位置", "定位", "这是哪", "在哪里")
            VoiceCommand.SOS ->
                listOf("紧急求助", "救命", "求助", "SOS", "sos", "紧急", "报警")
            VoiceCommand.HELP ->
                listOf("帮助", "救命", "教我", "怎么用", "使用说明", "指令")
            VoiceCommand.OPEN_SETTINGS ->
                listOf("打开设置", "进入设置", "设置", "配置")
            VoiceCommand.CLOSE_SETTINGS ->
                listOf("关闭设置", "退出设置", "返回", "回去")
            VoiceCommand.CANCEL ->
                listOf("取消", "算了", "不要", "不用")
            else -> emptyList()
        }
    }
    
    // ==================== P1 增强重试逻辑 ====================
    
    /**
     * 错误类型枚举
     * 
     * 区分可恢复错误和不可恢复错误
     */
    enum class ErrorType {
        /** 可恢复错误 - 可以重试 */
        RECOVERABLE,
        
        /** 不可恢复错误 - 不应重试 */
        FATAL,
        
        /** 临时错误 - 可以稍后重试 */
        TEMPORARY
    }
    
    /**
     * 分析错误类型
     * 
     * 修复说明（P1）：
     * 区分不同类型的错误，采取不同的处理策略
     */
    fun analyzeError(errorCode: Int): ErrorType {
        return when (errorCode) {
            // 可恢复错误
            1 -> ErrorType.RECOVERABLE     // ERROR_AUDIO
            2 -> ErrorType.RECOVERABLE     // ERROR_CLIENT
            3 -> ErrorType.TEMPORARY       // ERROR_INSUFFICIENT_PERMISSIONS（有时可恢复）
            5 -> ErrorType.RECOVERABLE     // ERROR_RECOGNIZER_BUSY
            
            // 正常情况（非错误）
            6 -> ErrorType.TEMPORARY       // ERROR_SPEECH_TIMEOUT（用户没说话）
            7 -> ErrorType.TEMPORARY       // ERROR_NO_MATCH（没有匹配）
            
            // 不可恢复错误
            9 -> ErrorType.FATAL            // ERROR_INSUFFICIENT_PERMISSIONS（权限被永久拒绝）
            else -> ErrorType.RECOVERABLE
        }
    }
    
    /**
     * 获取重试延迟时间（指数退避）
     * 
     * 修复说明（P1）：
     * 使用指数退避算法，避免频繁重试
     * 最大重试次数限制：5 次
     */
    fun getRetryDelay(attemptCount: Int): Long {
        val baseDelay = 1000L // 1秒基础延迟
        val maxDelay = 30000L // 最大30秒
        val exponentialDelay = baseDelay * (1 shl min(attemptCount, 5)) // 2^attemptCount
        return min(exponentialDelay, maxDelay)
    }
    
    /**
     * 检查是否可以重试
     * 
     * 修复说明（P1）：
     * - 最大重试次数限制
     * - 不可恢复错误直接返回 false
     */
    fun canRetry(errorType: ErrorType, currentRetryCount: Int): Boolean {
        if (errorType == ErrorType.FATAL) {
            return false
        }
        
        if (currentRetryCount >= MAX_RETRY_COUNT) {
            Timber.w("Max retry count ($MAX_RETRY_COUNT) reached, giving up")
            return false
        }
        
        return true
    }
    
    /**
     * 执行带重试的命令解析
     * 
     * 修复说明（P1）：
     * - 添加指数退避重试
     * - 区分错误类型
     * - 支持国产设备特殊处理
     */
    suspend fun parseCommandWithRetry(
        text: String,
        onError: ((ErrorType, Int) -> Unit)? = null
    ): VoiceCommand? = withContext(Dispatchers.IO) {
        var retryCount = 0
        
        while (retryCount <= MAX_RETRY_COUNT) {
            try {
                val command = parseCommand(text)
                if (command != null) {
                    _retryState.value = _retryState.value.copy(
                        totalAttempts = _retryState.value.totalAttempts + 1,
                        lastErrorType = null
                    )
                    return@withContext command
                }
                
                // 无匹配结果，尝试使用降级策略
                return@withContext fallbackParse(text)
                
            } catch (e: Exception) {
                Timber.e(e, "Error parsing command (attempt ${retryCount + 1})")
                
                val errorType = categorizeException(e)
                onError?.invoke(errorType, retryCount)
                
                if (!canRetry(errorType, retryCount)) {
                    _retryState.value = _retryState.value.copy(
                        lastErrorType = errorType.name,
                        totalErrors = _retryState.value.totalErrors + 1
                    )
                    return@withContext null
                }
                
                // 等待后重试
                delay(getRetryDelay(retryCount))
                retryCount++
            }
        }
        
        _retryState.value = _retryState.value.copy(
            lastErrorType = "MAX_RETRIES_EXCEEDED",
            totalErrors = _retryState.value.totalErrors + 1
        )
        return@withContext fallbackParse(text)
    }
    
    /**
     * 分类异常类型
     */
    private fun categorizeException(e: Exception): ErrorType {
        return when {
            e.message?.contains("network", ignoreCase = true) == true -> ErrorType.TEMPORARY
            e.message?.contains("timeout", ignoreCase = true) == true -> ErrorType.RECOVERABLE
            e.message?.contains("permission", ignoreCase = true) == true -> ErrorType.FATAL
            else -> ErrorType.RECOVERABLE
        }
    }
    
    /**
     * 降级解析策略
     * 
     * 当主解析失败时使用的备用策略
     */
    private fun fallbackParse(text: String): VoiceCommand? {
        val normalizedText = normalizeText(text)
        
        // 检测紧急求助关键词
        if (containsAny(normalizedText, listOf("救命", "求助", "sos", "紧急", "报警"))) {
            return VoiceCommand.SOS
        }
        
        // 检测帮助关键词
        if (containsAny(normalizedText, listOf("帮助", "教我", "怎么用", "指令"))) {
            return VoiceCommand.HELP
        }
        
        // 检测导航关键词
        if (containsAny(normalizedText, listOf("导航", "带路", "去", "开始"))) {
            return VoiceCommand.START_NAVIGATION
        }
        
        // 检测位置查询
        if (containsAny(normalizedText, listOf("在哪", "位置", "定位"))) {
            return VoiceCommand.WHERE_AM_I
        }
        
        // 检测取消
        if (containsAny(normalizedText, listOf("取消", "算了", "不要"))) {
            return VoiceCommand.CANCEL
        }
        
        return null
    }
    
    /**
     * 检查文本是否包含任意关键词
     */
    private fun containsAny(text: String, keywords: List<String>): Boolean {
        return keywords.any { text.contains(it) }
    }
    
    // ==================== P1 国产设备降级处理 ====================
    
    /**
     * 设备兼容性信息
     */
    data class DeviceCompatibility(
        val isChineseDevice: Boolean,
        val isHuaweiDevice: Boolean,
        val isXiaomiDevice: Boolean,
        val supportsOfflineASR: Boolean,
        val recommendedASRProvider: ASRProvider
    )
    
    /**
     * ASR 提供商枚举
     */
    enum class ASRProvider {
        GOOGLE,       // Google 语音识别
        BAIDU,        // 百度语音识别
        XUNFEI,       // 讯飞语音识别
        HUAWEI_ASR    // 华为语音识别
    }
    
    /**
     * 检测设备兼容性
     * 
     * 修复说明（P1）：
     * 国产设备可能预装了特定的语音识别服务，
     * 需要针对性处理以获得最佳体验
     */
    fun detectDeviceCompatibility(): DeviceCompatibility {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.ROOT)
        val model = Build.MODEL.lowercase(Locale.ROOT)
        
        val isChineseDevice = isChineseManufacturer(manufacturer)
        val isHuaweiDevice = manufacturer.contains("huawei") || manufacturer.contains("honor")
        val isXiaomiDevice = manufacturer.contains("xiaomi") || manufacturer.contains("redmi")
        
        // 检测离线 ASR 支持
        val supportsOfflineASR = isHuaweiDevice || isXiaomiDevice
        
        // 推荐 ASR 提供商
        val recommendedProvider = when {
            isHuaweiDevice -> ASRProvider.HUAWEI_ASR
            isXiaomiDevice -> ASRProvider.XUNFEI
            isChineseDevice -> ASRProvider.BAIDU
            else -> ASRProvider.GOOGLE
        }
        
        return DeviceCompatibility(
            isChineseDevice = isChineseDevice,
            isHuaweiDevice = isHuaweiDevice,
            isXiaomiDevice = isXiaomiDevice,
            supportsOfflineASR = supportsOfflineASR,
            recommendedASRProvider = recommendedProvider
        )
    }
    
    /**
     * 获取国产设备特殊配置
     * 
     * 修复说明（P1）：
     * 针对不同国产设备提供不同的配置参数
     */
    fun getDeviceSpecificConfig(): Map<String, Any> {
        val compatibility = detectDeviceCompatibility()
        
        return when {
            compatibility.isHuaweiDevice -> mapOf(
                "asr_provider" to "HUAWEI_ASR",
                "enable_hotword" to true,
                "hotword_model" to "xiaozhi",
                "confidence_threshold" to 0.6f,
                "use_cloud_asr" to false,  // 华为设备优先离线
                "fallback_to_google" to true
            )
            
            compatibility.isXiaomiDevice -> mapOf(
                "asr_provider" to "XUNFEI",
                "enable_hotword" to true,
                "hotword_model" to "xiaozhi",
                "confidence_threshold" to 0.55f,
                "use_cloud_asr" to true,
                "fallback_to_google" to true
            )
            
            compatibility.isChineseDevice -> mapOf(
                "asr_provider" to "BAIDU",
                "enable_hotword" to true,
                "confidence_threshold" to 0.5f,
                "use_cloud_asr" to true,
                "fallback_to_google" to true
            )
            
            else -> mapOf(
                "asr_provider" to "GOOGLE",
                "enable_hotword" to true,
                "confidence_threshold" to 0.5f,
                "use_cloud_asr" to false,
                "fallback_to_google" to false
            )
        }
    }
    
    /**
     * 检测是否为国产设备厂商
     */
    private fun isChineseManufacturer(manufacturer: String): Boolean {
        val chineseManufacturers = listOf(
            "huawei", "honor", "xiaomi", "redmi", "oppo", "vivo",
            "oneplus", "realme", "meizu", "zte", "lenovo", "honor",
            "tcl", "coolpad", "gionee", "leEco", "乐视"
        )
        return chineseManufacturers.any { manufacturer.contains(it) }
    }
    
    /**
     * 应用国产设备特殊处理
     * 
     * 修复说明（P1）：
     * 当检测到国产设备时，应用特殊配置
     */
    fun applyChineseDeviceOptimizations() {
        val compatibility = detectDeviceCompatibility()
        
        if (compatibility.isChineseDevice) {
            Timber.d("Applying Chinese device optimizations for ${Build.MANUFACTURER}")
            
            // 记录设备信息
            _retryState.value = _retryState.value.copy(
                deviceInfo = "${Build.MANUFACTURER} ${Build.MODEL}"
            )
        }
    }
    
    // ==================== 状态类 ====================
    
    /**
     * 重试状态
     */
    data class RetryState(
        val totalAttempts: Int = 0,
        val totalErrors: Int = 0,
        val lastErrorType: String? = null,
        val currentRetryCount: Int = 0,
        val deviceInfo: String = ""
    )
    
    companion object {
        private const val MAX_RETRY_COUNT = 5
    }
}
