package com.blindpath.module_voice.service

import android.content.Context
import android.os.Bundle
import com.blindpath.module_voice.domain.model.WakeWordConfig
import com.iflytek.aikit.core.*
import org.json.JSONObject
import timber.log.Timber
import java.io.File

/**
 * 科大讯飞 AIKit 语音唤醒检测器
 *
 * 基于讯飞 AIKit SDK (新版) 实现。
 * 能力ID: e867a88f2 (语音唤醒)
 *
 * 集成要求：
 * 1. 将 AIKit.aar 和 SparkChain.aar 放入 module_voice/libs/
 * 2. 在讯飞开放平台创建应用，获取 appId/apiKey/apiSecret
 * 3. 在 local.properties 配置 IFLYTEK_APP_ID / IFLYTEK_API_KEY / IFLYTEK_API_SECRET
 * 4. 可选：自定义唤醒词文件放入外部存储 /Android/data/.../files/iflytek/keyword.txt
 *
 * 文档：https://www.xfyun.cn/doc/asr/AIkit_awaken/Android-SDK.html
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
        private const val IVW_ID = "e867a88f2"  // 语音唤醒能力ID（固定值）
        private const val AUTH_INTERVAL = 333   // 授权校验间隔（秒）
    }

    private var isInitialized = false
    private var isListening = false

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

        // 确保工作目录存在
        val workDir = File(context.getExternalFilesDir(null), "iflytek/aikit")
        if (!workDir.exists()) {
            workDir.mkdirs()
        }

        // 构建初始化参数
        val params = AiHelper.Params.builder()
            .appId(appId)
            .apiKey(apiKey)
            .apiSecret(apiSecret)
            .workDir(workDir.absolutePath)
            .ability(IVW_ID)
            .authInterval(AUTH_INTERVAL)
            .iLogMaxCount(1)
            .build()

        // 初始化 SDK
        AiHelper.getInst().init(context, params)

        // 设置日志级别
        AiHelper.getInst().setLogLevel(LogLvl.ERROR)

        // 注册授权监听
        AiHelper.getInst().registerListener(authListener)

        // 注册唤醒能力监听
        AiHelper.getInst().registerListener(IVW_ID, aiListener)

        isInitialized = true
        Timber.i("$TAG: AIKit SDK initialized successfully")
    }

    /**
     * 授权状态监听
     */
    private val authListener = AuthListener { type, code ->
        when (type) {
            ErrType.AUTH -> {
                if (code == 0) {
                    Timber.i("$TAG: SDK authorized successfully")
                } else {
                    Timber.e("$TAG: SDK authorization failed, code: $code")
                }
            }
            ErrType.HTTP -> {
                Timber.d("$TAG: HTTP auth result: $code")
            }
            else -> {
                Timber.w("$TAG: SDK error, type: $type, code: $code")
            }
        }
    }

    /**
     * 唤醒能力结果监听
     */
    private val aiListener = object : AiListener {
        override fun onResult(handleID: Int, outputData: List<AiResponse>?, usrContext: Any?) {
            outputData?.forEach { response ->
                val key = response.key
                val value = String(response.value ?: byteArrayOf(), Charsets.UTF_8)
                Timber.d("$TAG: onResult key=$key, value=$value")

                // 解析唤醒结果
                if (key.contains("rlt") || key.contains("func_wake_up")) {
                    parseWakeUpResult(value)
                }
            }
        }

        override fun onEvent(handleID: Int, event: Int, eventData: List<AiResponse>?, usrContext: Any?) {
            when (event) {
                AiEvent.EVENT_START.value -> Timber.d("$TAG: Event START")
                AiEvent.EVENT_END.value -> Timber.d("$TAG: Event END")
                AiEvent.EVENT_TIMEOUT.value -> Timber.d("$TAG: Event TIMEOUT")
                AiEvent.EVENT_PROGRESS.value -> Timber.d("$TAG: Event PROGRESS")
                else -> Timber.d("$TAG: Event $event")
            }
        }

        override fun onError(handleID: Int, err: Int, msg: String?, usrContext: Any?) {
            Timber.e("$TAG: Error $err: $msg")
        }
    }

    /**
     * 解析唤醒结果 JSON
     */
    private fun parseWakeUpResult(json: String) {
        try {
            // 示例: {"rlt":[{"sid":"undefine","istart":58,"iresid":0,"keyword":"ni3 hao3 xiao3 zhi4","score":1763}]}
            val root = JSONObject(json)
            val rltArray = root.optJSONArray("rlt")
            if (rltArray != null && rltArray.length() > 0) {
                val result = rltArray.getJSONObject(0)
                val keyword = result.optString("keyword", "")
                val score = result.optInt("score", 0)
                Timber.i("$TAG: Wake word detected! keyword=$keyword, score=$score")
                onWakeWordDetected.invoke(wakeWord)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse wake result, triggering callback anyway")
            onWakeWordDetected.invoke(wakeWord)
        }
    }

    /**
     * 开始监听唤醒词
     */
    fun startListening() {
        if (!isInitialized) {
            Timber.w("$TAG: Not initialized, cannot start listening")
            return
        }

        if (isListening) {
            Timber.d("$TAG: Already listening")
            return
        }

        try {
            // 1. 初始化唤醒引擎
            val ret = AiHelper.getInst().engineInit(IVW_ID)
            if (ret != 0) {
                Timber.e("$TAG: Engine init failed: $ret")
                return
            }

            // 2. 加载唤醒词（可选）
            loadWakeUpWords()

            // 3. 指定数据集
            val indexs = intArrayOf(0)
            val specifyRet = AiHelper.getInst().specifyDataSet(IVW_ID, "key_word", indexs)
            if (specifyRet != 0) {
                Timber.w("$TAG: Specify dataset failed: $specifyRet")
            }

            // 4. 构建启动参数
            val paramBuilder = AiRequest.builder()
            // 可选：设置门限值
            // paramBuilder.param("wdec_param_nCmThreshold", "0 0:$threshold")

            // 5. 启动会话
            val handle = AiHelper.getInst().start(IVW_ID, paramBuilder.build(), null)
            if (handle?.isSuccess != true) {
                Timber.e("$TAG: Start session failed: ${handle?.code}")
                return
            }

            isListening = true
            Timber.i("$TAG: Wake-up started, listening for \u0027$wakeWord\u0027")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start listening")
            isListening = false
        }
    }

    /**
     * 加载唤醒词配置文件
     */
    private fun loadWakeUpWords() {
        try {
            val keywordFile = File(context.getExternalFilesDir(null), "iflytek/keyword.txt")
            if (!keywordFile.exists()) {
                // 创建默认唤醒词文件
                keywordFile.parentFile?.mkdirs()
                keywordFile.writeText("$wakeWord;nCM:$threshold;\n")
            }

            val customBuilder = AiRequest.builder()
            customBuilder.customText("key_word", keywordFile.absolutePath, 0)
            val ret = AiHelper.getInst().loadData(IVW_ID, customBuilder.build())
            if (ret != 0) {
                Timber.w("$TAG: Load wake words failed: $ret")
            } else {
                Timber.d("$TAG: Wake words loaded from ${keywordFile.absolutePath}")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error loading wake words")
        }
    }

    /**
     * 停止监听
     */
    fun stopListening() {
        if (!isListening) return

        try {
            // AIKit 没有直接停止的方法，通过结束会话来停止
            isListening = false
            Timber.i("$TAG: Stopped listening")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to stop listening")
        }
    }

    /**
     * AIKit 自动管理音频采集，此方法不使用
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
            AiHelper.getInst().destroy()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error during release")
        }
        isInitialized = false
        Timber.i("$TAG: Released")
    }
}
