package com.blindpath.module_voice.service

import android.content.Context
import android.os.Bundle
import com.blindpath.module_voice.domain.WakeWordDetector
import com.blindpath.module_voice.domain.model.WakeWordConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.lang.reflect.Proxy

/**
 * 科大讯飞 AIKit 语音唤醒检测器（反射实现）
 *
 * 基于讯飞 AIKit SDK (新版) 实现。
 * 能力ID: e867a88f2 (语音唤醒)
 *
 * 使用反射调用 AIKit API，避免编译时依赖问题。
 */
class XfWakeWordDetector(
    private val context: Context,
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String,
    private val threshold: Int = WakeWordConfig.XF_WAKE_THRESHOLD,
    private val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD,
    private val onWakeWordDetected: (String) -> Unit
) : WakeWordDetector {

    companion object {
        private const val TAG = "XfWakeWordDetector"
        private const val IVW_ID = "e867a88f2"
        private const val AUTH_INTERVAL = 333

        private const val CLS_AI_HELPER = "com.iflytek.aikit.core.AiHelper"
        private const val CLS_AI_REQUEST = "com.iflytek.aikit.core.AiRequest"
        private const val CLS_AI_RESPONSE = "com.iflytek.aikit.core.AiResponse"
        private const val CLS_AI_HANDLE = "com.iflytek.aikit.core.AiHandle"
        private const val CLS_AI_LISTENER = "com.iflytek.aikit.core.AiListener"
        private const val CLS_AUTH_LISTENER = "com.iflytek.aikit.core.AuthListener"
        private const val CLS_ERR_TYPE = "com.iflytek.aikit.core.ErrType"
        private const val CLS_LOG_LVL = "com.iflytek.aikit.core.LogLvl"

        private var sdkAvailable: Boolean? = null
    }

    private var isInitialized = false
    private var isListening = false
    private var aiHelper: Any? = null
    private var callback: WakeWordDetector.Callback? = null

    init {
        try {
            initialize()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize")
        }
    }

    private fun initialize() {
        if (appId.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            throw IllegalArgumentException("iFlytek credentials cannot be empty")
        }

        if (sdkAvailable == null) {
            sdkAvailable = try {
                Class.forName(CLS_AI_HELPER)
                true
            } catch (e: ClassNotFoundException) {
                Timber.w("$TAG: AIKit SDK not found")
                false
            }
        }

        if (sdkAvailable != true) {
            throw IllegalStateException("iFlytek AIKit SDK not available")
        }

        val workDir = File(context.getExternalFilesDir(null), "iflytek/aikit")
        if (!workDir.exists()) {
            workDir.mkdirs()
        }

        initSdkByReflection(workDir.absolutePath)
        isInitialized = true
        Timber.i("$TAG: AIKit SDK initialized successfully")
    }

    private fun initSdkByReflection(workDirPath: String) {
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            val getInstMethod = aiHelperClass.getMethod("getInst")
            aiHelper = getInstMethod.invoke(null)

            val paramsClass = Class.forName("$CLS_AI_HELPER\$Params")
            val builderClass = Class.forName("$CLS_AI_HELPER\$Params\$Builder")
            val builder = builderClass.getConstructor().newInstance()

            builderClass.getMethod("appId", String::class.java).invoke(builder, appId)
            builderClass.getMethod("apiKey", String::class.java).invoke(builder, apiKey)
            builderClass.getMethod("apiSecret", String::class.java).invoke(builder, apiSecret)
            builderClass.getMethod("workDir", String::class.java).invoke(builder, workDirPath)
            builderClass.getMethod("ability", String::class.java).invoke(builder, IVW_ID)
            builderClass.getMethod("authInterval", Int::class.javaPrimitiveType).invoke(builder, AUTH_INTERVAL)
            builderClass.getMethod("iLogMaxCount", Int::class.javaPrimitiveType).invoke(builder, 1)

            val params = builderClass.getMethod("build").invoke(builder)
            aiHelperClass.getMethod("init", Context::class.java, paramsClass)
                .invoke(aiHelper, context, params)

            val logLvlClass = Class.forName(CLS_LOG_LVL)
            val errorLevel = logLvlClass.getField("ERROR").get(null)
            aiHelperClass.getMethod("setLogLevel", logLvlClass).invoke(aiHelper, errorLevel)

            val authListenerClass = Class.forName(CLS_AUTH_LISTENER)
            val authListener = Proxy.newProxyInstance(
                authListenerClass.classLoader,
                arrayOf(authListenerClass)
            ) { _, method, args ->
                if (method.name == "onAuthStateChange") {
                    val type = args?.get(0)
                    val code = args?.get(1) as? Int ?: -1
                    val typeName = type?.toString() ?: ""
                    if (typeName.contains("AUTH") && code == 0) {
                        Timber.i("$TAG: SDK authorized")
                    } else {
                        Timber.e("$TAG: SDK auth failed, type=$typeName, code=$code")
                    }
                }
                null
            }
            aiHelperClass.getMethod("registerListener", authListenerClass)
                .invoke(aiHelper, authListener)

            val aiListenerClass = Class.forName(CLS_AI_LISTENER)
            val aiListener = Proxy.newProxyInstance(
                aiListenerClass.classLoader,
                arrayOf(aiListenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onResult" -> {
                        val outputData = args?.get(1) as? List<*>
                        outputData?.forEach { response ->
                            try {
                                val key = response?.javaClass?.getMethod("getKey")?.invoke(response) as? String
                                val value = response?.javaClass?.getMethod("getValue")?.invoke(response) as? ByteArray
                                val valueStr = value?.let { String(it, Charsets.UTF_8) } ?: ""
                                Timber.d("$TAG: onResult key=$key")
                                if (key?.contains("rlt") == true || key?.contains("func_wake_up") == true) {
                                    parseWakeUpResult(valueStr)
                                }
                            } catch (e: Exception) {
                                Timber.e(e, "$TAG: Error parsing response")
                            }
                        }
                        null
                    }
                    "onEvent" -> {
                        val event = args?.get(1) as? Int ?: -1
                        Timber.d("$TAG: onEvent $event")
                        null
                    }
                    "onError" -> {
                        val err = args?.get(1) as? Int ?: -1
                        val msg = args?.get(2) as? String
                        Timber.e("$TAG: onError $err: $msg")
                        null
                    }
                    else -> null
                }
            }
            aiHelperClass.getMethod("registerListener", String::class.java, aiListenerClass)
                .invoke(aiHelper, IVW_ID, aiListener)

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Reflection init failed")
            throw IllegalStateException("Failed to initialize AIKit SDK: ${e.message}")
        }
    }

    private fun parseWakeUpResult(json: String) {
        try {
            val root = JSONObject(json)
            val rltArray = root.optJSONArray("rlt")
            if (rltArray != null && rltArray.length() > 0) {
                val result = rltArray.getJSONObject(0)
                val keyword = result.optString("keyword", "")
                val score = result.optInt("score", 0)
                Timber.i("$TAG: Wake word detected! keyword=$keyword, score=$score")
                onWakeWordDetected.invoke(wakeWord)
                callback?.onWakeWordDetected(wakeWord, 0.9f)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse wake result")
            onWakeWordDetected.invoke(wakeWord)
                callback?.onWakeWordDetected(wakeWord, 0.9f)
        }
    }

    override fun start(): Boolean {
        startListening()
        return true
    }

    fun startListening() {
        if (!isInitialized || aiHelper == null) {
            Timber.w("$TAG: Not initialized")
            return
        }
        if (isListening) {
            Timber.d("$TAG: Already listening")
            return
        }

        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)

            val engineInitRet = aiHelperClass.getMethod("engineInit", String::class.java)
                .invoke(aiHelper, IVW_ID) as? Int ?: -1
            if (engineInitRet != 0) {
                Timber.e("$TAG: Engine init failed: $engineInitRet")
                return
            }

            loadWakeUpWords()

            val indexs = intArrayOf(0)
            val specifyRet = aiHelperClass.getMethod("specifyDataSet", String::class.java, String::class.java, IntArray::class.java)
                .invoke(aiHelper, IVW_ID, "key_word", indexs) as? Int ?: -1
            if (specifyRet != 0) {
                Timber.w("$TAG: Specify dataset failed: $specifyRet")
            }

            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("$CLS_AI_REQUEST\$Builder")
            val builder = builderClass.getMethod("builder").invoke(null)
            val request = builderClass.getMethod("build").invoke(builder)

            val handle = aiHelperClass.getMethod("start", String::class.java, aiRequestClass, Any::class.java)
                .invoke(aiHelper, IVW_ID, request, null)

            val isSuccess = handle?.javaClass?.getMethod("isSuccess")?.invoke(handle) as? Boolean ?: false
            if (!isSuccess) {
                val code = handle?.javaClass?.getMethod("getCode")?.invoke(handle) as? Int ?: -1
                Timber.e("$TAG: Start session failed: $code")
                return
            }

            isListening = true
            Timber.i("$TAG: Wake-up started, listening for \u0027$wakeWord\u0027")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start listening")
            isListening = false
        }
    }

    private fun loadWakeUpWords() {
        try {
            val keywordFile = File(context.getExternalFilesDir(null), "iflytek/keyword.txt")
            if (!keywordFile.exists()) {
                keywordFile.parentFile?.mkdirs()
                keywordFile.writeText("$wakeWord;nCM:$threshold;\n")
            }

            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("$CLS_AI_REQUEST\$Builder")
            val builder = builderClass.getMethod("builder").invoke(null)

            builderClass.getMethod("customText", String::class.java, String::class.java, Int::class.javaPrimitiveType)
                .invoke(builder, "key_word", keywordFile.absolutePath, 0)

            val request = builderClass.getMethod("build").invoke(builder)
            val ret = aiHelperClass.getMethod("loadData", String::class.java, aiRequestClass)
                .invoke(aiHelper, IVW_ID, request) as? Int ?: -1

            if (ret != 0) {
                Timber.w("$TAG: Load wake words failed: $ret")
            } else {
                Timber.d("$TAG: Wake words loaded")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error loading wake words")
        }
    }

    fun stop() {
        stopListening()
    }

    fun stopListening() {
        if (!isListening) return
        isListening = false
        Timber.i("$TAG: Stopped listening")
    }

    override fun process(audioData: ShortArray): Boolean {
        return false
    }

    fun getFrameLength(): Int = 512
    fun getSampleRate(): Int = 16000
    fun isListening(): Boolean = isListening

    override fun setCallback(callback: WakeWordDetector.Callback) {
        this.callback = callback
    }

    override fun setSensitivity(sens: Float) {
        Timber.d("$TAG: Set sensitivity: $sens")
    }

    override fun release() {
        stopListening()
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            aiHelperClass.getMethod("unInit").invoke(aiHelper)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error during release")
        }
        isInitialized = false
        Timber.i("$TAG: Released")
    }
}
