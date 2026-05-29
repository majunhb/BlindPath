package com.blindpath.module_voice.service

import android.content.Context
import android.os.Bundle
import com.blindpath.module_voice.domain.model.WakeWordConfig
import timber.log.Timber
import org.json.JSONObject

/**
 * 科大讯飞 MSC 语音唤醒检测器（反射调用）
 *
 * 通过反射调用讯飞 MSC SDK 的 VoiceWakeuper API，
 * 这样在没有 msc.jar 的环境下也能编译通过。
 * 运行时如果 msc.jar 不存在，初始化会优雅失败并返回 null。
 *
 * 集成要求：
 * 1. 从讯飞开放平台下载 MSC SDK
 * 2. 将 msc.jar 放入 module_voice/libs/
 * 3. 将 libmsc.so 放入 module_voice/src/main/jniLibs/{abi}/
 * 4. 唤醒词资源 {appid}.jet 放入 module_voice/src/main/assets/ivw/
 * 5. 在 local.properties 配置 IFLYTEK_APP_ID
 *
 * 文档：https://www.xfyun.cn/doc/mscapi/Android/androidwakeuper.html
 */
class XfWakeWordDetector(
    private val context: Context,
    private val appId: String,
    private val threshold: Int = WakeWordConfig.XF_WAKE_THRESHOLD,
    private val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD,
    private val onWakeWordDetected: (String) -> Unit
) : WakeWordDetector {

    companion object {
        private const val TAG = "XfWakeWordDetector"

        // 讯飾 SDK 类名
        private const val CLS_WAKEUPER = "com.iflytek.cloud.VoiceWakeuper"
        private const val CLS_WAKEUPER_RESULT = "com.iflytek.cloud.WakeuperResult"
        private const val CLS_SPEECH_ERROR = "com.iflytek.cloud.SpeechError"
        private const val CLS_SPEECH_CONSTANT = "com.iflytek.cloud.SpeechConstant"
        private const val CLS_RESOURCE_UTIL = "com.iflytek.cloud.util.ResourceUtil"

        // 反射缓存
        private var sdkAvailable: Boolean? = null
    }

    private var wakeuper: Any? = null  // VoiceWakeuper instance
    private var isListening = false
    private var isInitialized = false

    init {
        try {
            initialize()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize")
        }
    }

    private fun initialize() {
        if (appId.isBlank()) {
            throw IllegalArgumentException("iFlytek AppID cannot be empty")
        }

        // Check if SDK is available (cache the result)
        if (sdkAvailable == null) {
            sdkAvailable = try {
                Class.forName(CLS_WAKEUPER)
                true
            } catch (e: ClassNotFoundException) {
                Timber.w("$TAG: MSC SDK not found (msc.jar not in libs/)")
                false
            }
        }

        if (sdkAvailable != true) {
            throw IllegalStateException("iFlytek MSC SDK not available")
        }

        // VoiceWakeuper.createWakeuper(context, null)
        val createMethod = Class.forName(CLS_WAKEUPER)
            .getMethod("createWakeuper", Context::class.java, Any::class.java)
        wakeuper = createMethod.invoke(null, context, null)

        if (wakeuper == null) {
            throw IllegalStateException("Failed to create VoiceWakeuper")
        }

        isInitialized = true
        Timber.i("$TAG: Initialized successfully (AppID: $appId)")
    }

    /**
     * 通过反射调用 VoiceWakeuper.setParameter()
     */
    private fun setParameter(key: String, value: String?) {
        try {
            wakeuper?.javaClass?.getMethod("setParameter", String::class.java, Any::class.java)
                ?.invoke(wakeuper, key, value)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: setParameter failed: $key=$value")
        }
    }

    /**
     * 创建动态代理的 WakeuperListener
     */
    private fun createWakeuperListener(): Any {
        val listenerClass = Class.forName("com.iflytek.cloud.WakeuperListener")

        return java.lang.reflect.Proxy.newProxyInstance(
            listenerClass.classLoader,
            arrayOf(listenerClass)
        ) { _, method, args ->
            when (method.name) {
                "onResult" -> {
                    try {
                        val result = args?.get(0) ?: return@newProxyInstance null
                        val resultStr = result.javaClass.getMethod("getResultString").invoke(result) as? String
                        Timber.i("$TAG: onResult: $resultStr")
                        if (resultStr != null) {
                            val json = JSONObject(resultStr)
                            val keyword = json.optString("keyword", "")
                            val score = json.optInt("score", 0)
                            Timber.i("$TAG: Wake word detected! keyword=$keyword, score=$score")
                        }
                        onWakeWordDetected.invoke(wakeWord)
                    } catch (e: Exception) {
                        Timber.e(e, "$TAG: Failed to parse wake result, triggering callback anyway")
                        onWakeWordDetected.invoke(wakeWord)
                    }
                    null
                }
                "onError" -> {
                    val error = args?.get(0)
                    val errorCode = error?.javaClass?.getMethod("getErrorCode")?.invoke(error) as? Int ?: -1
                    val errorDesc = error?.javaClass?.getMethod("getErrorDescription")?.invoke(error) as? String ?: "unknown"
                    Timber.e("$TAG: Wake error: code=$errorCode, desc=$errorDesc")
                    null
                }
                "onBeginOfSpeech" -> {
                    Timber.d("$TAG: onBeginOfSpeech")
                    null
                }
                "onEvent" -> {
                    Timber.d("$TAG: onEvent")
                    null
                }
                "onVolumeChanged" -> {
                    null
                }
                else -> null
            }
        }
    }

    /**
     * 开始监听唤醒词
     */
    fun startListening() {
        if (!isInitialized || wakeuper == null) {
            Timber.w("$TAG: Not initialized, cannot start listening")
            return
        }

        if (isListening) {
            Timber.d("$TAG: Already listening")
            return
        }

        try {
            // 清空参数
            setParameter("params", null)

            // 唤醒门限值
            setParameter("ivw_threshold", "0:$threshold")

            // 唤醒模式：wakeup = 纯唤醒
            setParameter("ivw_sst", "wakeup")

            // 持续唤醒：1 = 循环监听
            setParameter("keep_alive", "1")

            // 闭环优化网络模式：0 = 关闭
            setParameter("ivw_net_mode", "0")

            // 设置唤醒资源路径
            val resPath = generateResourcePath()
            setParameter("ivw_res_path", resPath)
            Timber.i("$TAG: Resource path: $resPath")

            // 启动唤醒
            val listener = createWakeuperListener()
            wakeuper?.javaClass?.getMethod("startListening", Class.forName("com.iflytek.cloud.WakeuperListener"))
                ?.invoke(wakeuper, listener)
            isListening = true
            Timber.i("$TAG: Wake-up started, listening for '$wakeWord'")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start listening")
            isListening = false
        }
    }

    /**
     * 生成唤醒资源路式
     */
    private fun generateResourcePath(): String {
        return try {
            val resourceUtilClass = Class.forName(CLS_RESOURCE_UTIL)
            val generateMethod = resourceUtilClass.getMethod(
                "generateResourcePath",
                Context::class.java,
                Int::class.javaPrimitiveType,
                String::class.java
            )
            // RESOURCE_TYPE.assets = 1
            generateMethod.invoke(null, context, 1, "ivw/$appId.jet") as String
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to generate resource path")
            "assets:///ivw/$appId.jet"
        }
    }

    /**
     * 停止监听
     */
    fun stopListening() {
        if (wakeuper == null || !isListening) return

        try {
            wakeuper?.javaClass?.getMethod("stopListening")?.invoke(wakeuper)
            isListening = false
            Timber.i("$TAG: Stopped listening")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to stop listening")
        }
    }

    /**
     * 讯飞 SDK 自动管理音频采集，此方法不使用
     */
    override fun process(audioData: ShortArray): Boolean {
        return false
    }

    fun getFrameLength(): Int = 512
    fun getSampleRate(): Int = 16000
    fun isListening(): Boolean = isListening

    override fun release() {
        stopListening()
        try {
            wakeuper?.javaClass?.getMethod("destroy")?.invoke(wakeuper)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error during release")
        }
        wakeuper = null
        isInitialized = false
        Timber.i("$TAG: Released")
    }
}
