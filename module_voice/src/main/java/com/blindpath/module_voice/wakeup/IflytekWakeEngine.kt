package com.blindpath.module_voice.wakeup

import android.content.Context
import android.os.Bundle
import com.iflytek.cloud.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber

/**
 * 科大讯飞语音唤醒引擎
 * 
 * 专为国内视障用户设计，特点：
 * - 中文唤醒词支持（"小智小智"）
 * - 科大讯飞 AIKit 在线唤醒
 * - 华为设备完美兼容
 * - 低延迟（100-300ms）
 * 
 * @param context 应用上下文
 * @param appId 科大讯飞 App ID
 * @param appKey 科大讯飞 API Key
 * @param appSecret 科大讯飞 API Secret
 */
class IflytekWakeEngine(
    private val context: Context,
    private val appId: String,
    private val appKey: String,
    private val appSecret: String
) {
    companion object {
        // 默认唤醒词
        const val DEFAULT_WAKE_WORD = "小智小智"
        
        // 置信度阈值（0-1000），越高越严格
        const val CONFIDENCE_THRESHOLD = 800
    }

    private var wakeUpListener: WakeUpListener? = null
    private var isInitialized = false
    private var isListening = false
    
    // 协程作用域
    private val engineScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // 事件流
    private val _wakeWordFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val wakeWordFlow: SharedFlow<String> = _wakeWordFlow.asSharedFlow()
    
    private val _errorFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val errorFlow: SharedFlow<String> = _errorFlow.asSharedFlow()
    
    // 回调（简化使用）
    var onWakeWordDetected: ((String) -> Unit)? = null
    var onError: ((String) -> Unit)? = null

    /**
     * 初始化科大讯飞唤醒引擎
     * 
     * @return 初始化是否成功
     */
    fun initialize(): Boolean {
        if (isInitialized) {
            Timber.w("IflytekWakeEngine: 已初始化")
            return true
        }

        // 验证凭证
        if (appId.isBlank() || appKey.isBlank() || appSecret.isBlank()) {
            val error = "科大讯飞凭证未配置"
            Timber.e("IflytekWakeEngine: $error")
            onError?.invoke(error)
            return false
        }

        return try {
            // 初始化科大讯飞 SDK
            SpeechUtility.createUtility(context, SpeechConstant.APPID + "=" + appId)
            
            // 创建唤醒监听器
            wakeUpListener = createWakeUpListener()
            
            isInitialized = true
            Timber.i("IflytekWakeEngine: 初始化成功，AppID=$appId")
            true
        } catch (e: Exception) {
            val error = "初始化失败: ${e.message}"
            Timber.e(e, "IflytekWakeEngine: $error")
            onError?.invoke(error)
            false
        }
    }

    /**
     * 开始监听唤醒词
     * 
     * @param wakeWord 唤醒词（默认"小智小智"）
     * @return 是否启动成功
     */
    fun startListening(wakeWord: String = DEFAULT_WAKE_WORD): Boolean {
        if (!isInitialized) {
            val error = "引擎未初始化"
            Timber.w("IflytekWakeEngine: $error")
            onError?.invoke(error)
            return false
        }

        if (isListening) {
            Timber.d("IflytekWakeEngine: 已在监听中")
            return true
        }

        return try {
            // 创建唤醒对象
            val wakeUp = WakeUp.createWakeUp(context, wakeUpListener)
            
            // 设置唤醒参数
            val params = StringBuffer()
            params.append(SpeechConstant.APPID).append("=").append(appId)
            params.append(",")
            params.append(SpeechConstant.WAKEUP_THRESHOLD).append("=").append(CONFIDENCE_THRESHOLD)
            
            // 启动唤醒
            wakeUp.startListening(params.toString())
            
            isListening = true
            Timber.i("IflytekWakeEngine: 开始监听唤醒词 '$wakeWord'")
            true
        } catch (e: Exception) {
            val error = "启动监听失败: ${e.message}"
            Timber.e(e, "IflytekWakeEngine: $error")
            onError?.invoke(error)
            false
        }
    }

    /**
     * 停止监听
     */
    fun stopListening() {
        if (!isListening) return

        try {
            WakeUp.createWakeUp(context, null).stopListening()
            isListening = false
            Timber.i("IflytekWakeEngine: 已停止监听")
        } catch (e: Exception) {
            Timber.e(e, "IflytekWakeEngine: 停止监听异常")
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        stopListening()
        engineScope.cancel()
        
        try {
            WakeUp.createWakeUp(context, null).destroy()
            Timber.i("IflytekWakeEngine: 已释放")
        } catch (e: Exception) {
            Timber.e(e, "IflytekWakeEngine: 释放异常")
        }
        
        wakeUpListener = null
        isInitialized = false
    }

    /**
     * 创建唤醒监听器
     */
    private fun createWakeUpListener(): WakeUpListener {
        return object : WakeUpListener {
            override fun onResult(results: WakeUpResult?) {
                if (results == null) return
                
                val text = results.resultString
                val confidence = results.confidence
                
                Timber.d("IflytekWakeEngine: 唤醒结果 - text=$text, confidence=$confidence")
                
                if (confidence >= CONFIDENCE_THRESHOLD) {
                    // 唤醒成功
                    val wakeWord = text ?: DEFAULT_WAKE_WORD
                    
                    engineScope.launch {
                        _wakeWordFlow.emit(wakeWord)
                    }
                    
                    onWakeWordDetected?.invoke(wakeWord)
                    
                    Timber.i("IflytekWakeEngine: 检测到唤醒词 '$wakeWord' (置信度=$confidence)")
                }
            }

            override fun onError(error: SpeechError?) {
                val errorMsg = error?.errorDescription ?: "未知错误"
                Timber.e("IflytekWakeEngine: 错误 - $errorMsg")
                
                engineScope.launch {
                    _errorFlow.emit(errorMsg)
                }
                
                onError?.invoke(errorMsg)
            }

            override fun onEvent(eventType: Int, arg1: Int, arg2: Int, obj: Bundle?) {
                // 事件回调，可根据需要处理
                Timber.d("IflytekWakeEngine: 事件 - type=$eventType")
            }
        }
    }

    /**
     * 获取监听状态
     */
    fun isListening(): Boolean = isListening

    /**
     * 获取初始化状态
     */
    fun isInitialized(): Boolean = isInitialized
}

/**
 * 科大讯飞唤醒配置
 * 
 * @param appId App ID
 * @param appKey API Key
 * @param appSecret API Secret
 * @param wakeWord 唤醒词（默认"小智小智"）
 */
data class IflytekWakeConfig(
    val appId: String,
    val appKey: String,
    val appSecret: String,
    val wakeWord: String = IflytekWakeEngine.DEFAULT_WAKE_WORD
) {
    /**
     * 验证配置是否有效
     */
    fun isValid(): Boolean {
        return appId.isNotBlank() && appKey.isNotBlank() && appSecret.isNotBlank()
    }
}
