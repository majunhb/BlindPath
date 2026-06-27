package com.blindpath.module_voice.wakeup

import android.content.Context
import com.blindpath.module_voice.domain.model.WakeWordConfig
import com.blindpath.module_voice.service.XfWakeWordDetector
import timber.log.Timber

/**
 * 唤醒引擎工厂
 * 
 * 统一管理多种唤醒引擎：
 * - 科大讯飞 AIKit（默认，国内用户）
 * - 能量检测（兜底方案）
 * 
 * 使用策略：
 * 1. 优先使用科大讯飞 AIKit（中文支持好）
 * 2. AIKit 不可用时，使用能量检测
 */
object WakeEngineFactory {
    
    enum class EngineType {
        IFLYTEK,    // 科大讯飞 AIKit（默认）
        ENERGY      // 能量检测（兜底）
    }

    /**
     * 创建唤醒引擎
     * 
     * @param context 应用上下文
     * @param type 引擎类型（默认科大讯飞）
     * @return 唤醒引擎实例
     */
    fun createEngine(
        context: Context,
        type: EngineType = EngineType.IFLYTEK
    ): WakeEngine {
        return when (type) {
            EngineType.IFLYTEK -> createIflytekEngine(context)
            EngineType.ENERGY -> createEnergyEngine(context)
        }
    }

    /**
     * 自动选择最佳引擎
     * 
     * 优先级：
     * 1. 科大讯飞 AIKit（国内用户，中文支持）
     * 2. 能量检测（兜底方案）
     */
    fun createBestEngine(context: Context): WakeEngine {
        // 1. 尝试科大讯飞 AIKit
        val iflytekEngine = createIflytekEngine(context)
        if (iflytekEngine.isInitialized()) {
            Timber.i("WakeEngineFactory: 使用科大讯飞 AIKit 唤醒引擎")
            return iflytekEngine
        }

        // 2. 使用能量检测兜底
        Timber.w("WakeEngineFactory: AIKit 初始化失败，使用能量检测")
        return createEnergyEngine(context)
    }

    /**
     * 创建科大讯飞 AIKit 引擎
     */
    private fun createIflytekEngine(context: Context): WakeEngine {
        val config = loadIflytekConfig(context)
        
        return if (config.isValid()) {
            try {
                XfWakeEngineAdapter(
                    context = context,
                    appId = config.appId,
                    apiKey = config.apiKey,
                    apiSecret = config.apiSecret
                ).apply {
                    initialize()
                }
            } catch (e: Exception) {
                Timber.e(e, "WakeEngineFactory: AIKit 引擎创建失败")
                createEnergyEngine(context)
            }
        } else {
            Timber.w("WakeEngineFactory: 科大讯飞凭证未配置")
            createEnergyEngine(context)
        }
    }

    /**
     * 创建能量检测引擎（兜底）
     */
    private fun createEnergyEngine(context: Context): WakeEngine {
        return EnergyWakeEngine(context).apply {
            initialize()
        }
    }

    /**
     * 加载科大讯飞配置
     */
    private fun loadIflytekConfig(context: Context): IflytekWakeConfig {
        return try {
            val props = java.util.Properties()
            context.assets.open("credentials.properties").use { 
                props.load(it) 
            }
            
            IflytekWakeConfig(
                appId = props.getProperty("IFLYTEK_APP_ID", ""),
                apiKey = props.getProperty("IFLYTEK_API_KEY", ""),
                apiSecret = props.getProperty("IFLYTEK_API_SECRET", "")
            )
        } catch (e: Exception) {
            Timber.w("WakeEngineFactory: 无法加载科大讯飞配置")
            IflytekWakeConfig("", "", "")
        }
    }
}

/**
 * 唤醒引擎接口
 * 
 * 统一接口，支持多种实现
 */
interface WakeEngine {
    /**
     * 初始化引擎
     */
    fun initialize(): Boolean
    
    /**
     * 开始监听唤醒词
     */
    fun startListening(): Boolean
    
    /**
     * 停止监听
     */
    fun stopListening()
    
    /**
     * 释放资源
     */
    fun release()
    
    /**
     * 是否已初始化
     */
    fun isInitialized(): Boolean
    
    /**
     * 是否正在监听
     */
    fun isListening(): Boolean
    
    /**
     * 设置唤醒回调
     */
    var onWakeWordDetected: ((String) -> Unit)?
    
    /**
     * 设置错误回调
     */
    var onError: ((String) -> Unit)?
}

/**
 * 科大讯飞 AIKit 唤醒配置
 * 
 * @param appId App ID
 * @param apiKey API Key
 * @param apiSecret API Secret
 */
data class IflytekWakeConfig(
    val appId: String,
    val apiKey: String,
    val apiSecret: String
) {
    /**
     * 验证配置是否有效
     */
    fun isValid(): Boolean {
        return appId.isNotBlank() && apiKey.isNotBlank() && apiSecret.isNotBlank()
    }
}

/**
 * 能量检测唤醒引擎（兜底方案）
 * 
 * 当其他引擎都不可用时使用
 * 特点：
 * - 完全离线
 * - 任何声音都触发（敏感）
 * - 仅作为备用
 */
class EnergyWakeEngine(private val context: Context) : WakeEngine {
    
    private var isInitialized = false
    private var isListening = false
    
    override var onWakeWordDetected: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    override fun initialize(): Boolean {
        isInitialized = true
        Timber.i("EnergyWakeEngine: 初始化成功（能量检测模式）")
        return true
    }

    override fun startListening(): Boolean {
        if (!isInitialized) return false
        isListening = true
        Timber.w("EnergyWakeEngine: 开始监听（任何声音都会触发）")
        return true
    }

    override fun stopListening() {
        isListening = false
        Timber.i("EnergyWakeEngine: 已停止")
    }

    override fun release() {
        stopListening()
        isInitialized = false
    }

    override fun isInitialized(): Boolean = isInitialized
    override fun isListening(): Boolean = isListening
}

/**
 * XfWakeWordDetector 适配器
 * 
 * 将 XfWakeWordDetector 适配为 WakeEngine 接口
 */
class XfWakeEngineAdapter(
    private val context: Context,
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String
) : WakeEngine {
    
    private var detector: XfWakeWordDetector? = null
    private var isInitialized = false
    
    override var onWakeWordDetected: ((String) -> Unit)? = null
    override var onError: ((String) -> Unit)? = null

    override fun initialize(): Boolean {
        if (isInitialized) return true
        
        return try {
            detector = XfWakeWordDetector(
                context = context,
                appId = appId,
                apiKey = apiKey,
                apiSecret = apiSecret,
                threshold = WakeWordConfig.XF_WAKE_THRESHOLD,
                wakeWord = WakeWordConfig.DEFAULT_WAKE_WORD,
                onWakeWordDetected = { wakeWord ->
                    onWakeWordDetected?.invoke(wakeWord)
                },
                onAuthSuccessCb = {
                    Timber.i("XfWakeEngineAdapter: Auth success, engine starting...")
                },
                onAuthFailedCb = {
                    Timber.e("XfWakeEngineAdapter: Auth failed")
                    onError?.invoke("讯飞授权失败")
                }
            )
            isInitialized = true
            Timber.i("XfWakeEngineAdapter: AIKit 引擎初始化成功")
            true
        } catch (e: Exception) {
            Timber.e(e, "XfWakeEngineAdapter: 初始化失败")
            onError?.invoke("初始化失败: ${e.message}")
            false
        }
    }

    override fun startListening(): Boolean {
        if (!isInitialized) return false
        detector?.startListening()
        return true
    }

    override fun stopListening() {
        detector?.stopListening()
    }

    override fun release() {
        detector?.release()
        detector = null
        isInitialized = false
    }

    override fun isInitialized(): Boolean = isInitialized
    
    override fun isListening(): Boolean = detector?.isListening() ?: false
}
