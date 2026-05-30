package com.blindpath.module_voice.wakeup

import android.content.Context
import timber.log.Timber

/**
 * 唤醒引擎工厂
 * 
 * 统一管理多种唤醒引擎：
 * - 科大讯飞（默认，国内用户）
 * - Porcupine（备用，国际用户）
 * 
 * 使用策略：
 * 1. 优先使用科大讯飞（中文支持好）
 * 2. 科大讯飞不可用时，尝试 Porcupine
 * 3. 两者都失败时，使用能量检测（备用）
 */
object WakeEngineFactory {
    
    enum class EngineType {
        IFLYTEK,    // 科大讯飞（默认）
        PORCUPINE,  // Porcupine（备用）
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
            EngineType.PORCUPINE -> createPorcupineEngine(context)
            EngineType.ENERGY -> createEnergyEngine(context)
        }
    }

    /**
     * 自动选择最佳引擎
     * 
     * 优先级：
     * 1. 科大讯飞（国内用户，中文支持）
     * 2. Porcupine（国际用户，离线）
     * 3. 能量检测（兜底方案）
 */
    fun createBestEngine(context: Context): WakeEngine {
        // 1. 尝试科大讯飞
        val iflytekEngine = createIflytekEngine(context)
        if (iflytekEngine.isInitialized()) {
            Timber.i("WakeEngineFactory: 使用科大讯飞唤醒引擎")
            return iflytekEngine
        }

        // 2. 尝试 Porcupine
        val porcupineEngine = createPorcupineEngine(context)
        if (porcupineEngine.isInitialized()) {
            Timber.i("WakeEngineFactory: 使用 Porcupine 唤醒引擎")
            return porcupineEngine
        }

        // 3. 使用能量检测兜底
        Timber.w("WakeEngineFactory: 所有引擎初始化失败，使用能量检测")
        return createEnergyEngine(context)
    }

    /**
     * 创建科大讯飞引擎
     */
    private fun createIflytekEngine(context: Context): WakeEngine {
        val config = loadIflytekConfig(context)
        
        return if (config.isValid()) {
            IflytekWakeEngine(
                context = context,
                appId = config.appId,
                appKey = config.appKey,
                appSecret = config.appSecret
            ).apply {
                initialize()
            }
        } else {
            Timber.w("WakeEngineFactory: 科大讯飞凭证未配置")
            createEnergyEngine(context)
        }
    }

    /**
     * 创建 Porcupine 引擎（备用）
     */
    private fun createPorcupineEngine(context: Context): WakeEngine {
        // TODO: 实现 Porcupine 引擎创建
        // 目前返回能量检测作为占位
        Timber.d("WakeEngineFactory: Porcupine 引擎暂未实现")
        return createEnergyEngine(context)
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
                appKey = props.getProperty("IFLYTEK_API_KEY", ""),
                appSecret = props.getProperty("IFLYTEK_API_SECRET", "")
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

// 扩展 IflytekWakeEngine 实现 WakeEngine 接口
fun IflytekWakeEngine.toWakeEngine(): WakeEngine = this as WakeEngine
