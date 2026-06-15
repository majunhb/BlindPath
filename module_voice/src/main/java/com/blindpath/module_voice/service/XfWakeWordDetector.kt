package com.blindpath.module_voice.service

import android.content.Context
import com.blindpath.module_voice.domain.WakeWordDetector
import com.blindpath.module_voice.domain.model.WakeWordConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.io.InputStream
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 科大讯飞 AIKit 语音唤醒检测器（反射实现）
 *
 * 基于讯飞 AIKit SDK (新版) 实现。
 * 能力ID: e867a88f2 (语音唤醒)
 *
 * 官方文档: https://www.xfyun.cn/doc/asr/AIkit_awaken/Android-SDK.html
 *
 * 核心流程:
 * 1. init() → 初始化 SDK + 加载唤醒资源
 * 2. engineInit() → 初始化唤醒引擎
 * 3. loadData() → 加载唤醒词
 * 4. start() → 创建会话
 * 5. write() → 持续送入音频帧（关键！不送音频就不会有唤醒结果）
 * 6. onResult → 收到唤醒回调
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

        // 反射类名
        private const val CLS_AI_HELPER = "com.iflytek.aikit.core.AiHelper"
        private const val CLS_AI_REQUEST = "com.iflytek.aikit.core.AiRequest"
        private const val CLS_AI_RESPONSE = "com.iflytek.aikit.core.AiResponse"
        private const val CLS_AI_HANDLE = "com.iflytek.aikit.core.AiHandle"
        private const val CLS_AI_LISTENER = "com.iflytek.aikit.core.AiListener"
        private const val CLS_AUTH_LISTENER = "com.iflytek.aikit.core.AuthListener"
        private const val CLS_ERR_TYPE = "com.iflytek.aikit.core.ErrType"
        private const val CLS_LOG_LVL = "com.iflytek.aikit.core.LogLvl"
        // 音频相关
        private const val CLS_AI_AUDIO = "com.iflytek.aikit.core.AiAudio"
        private const val CLS_AI_STATUS = "com.iflytek.aikit.core.AiStatus"

        private var sdkAvailable: Boolean? = null
    }

    private var isInitialized = false
    private val isListening = AtomicBoolean(false)
    private var aiHelper: Any? = null
    private var aiHandle: Any? = null
    private var callback: WakeWordDetector.Callback? = null
    private var writeThread: Thread? = null
    private val audioDataRef = AtomicReference<ByteArray>(null)

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

        // ★ 修复1：从 assets 复制唤醒资源文件到 workDir
        copyResourceFiles(workDir)

        initSdkByReflection(workDir.absolutePath)
        isInitialized = true
        Timber.i("$TAG: AIKit SDK initialized successfully, workDir=${workDir.absolutePath}")
    }

    /**
     * ★ 修复：将 assets/aikit_resources/ 下的唤醒资源复制到 workDir
     * 官方文档 7.4 节：可在 assets 目录下创建 aikit_resources 目录
     * 同时兼容 assets/iflytek/ivw/ 路径（旧版）
     */
    private fun copyResourceFiles(workDir: File) {
        try {
            val targetDir = File(workDir, "ivw")
            if (targetDir.exists()) {
                val children = targetDir.listFiles()
                if (children != null && children.isNotEmpty()) {
                    Timber.d("$TAG: Resource files already exist in ${targetDir.absolutePath}")
                    return
                }
            }
            targetDir.mkdirs()

            val assetManager = context.assets

            // 优先尝试官方推荐路径: assets/aikit_resources/
            val aikitAssets = assetManager.list("aikit_resources")
            if (!aikitAssets.isNullOrEmpty()) {
                aikitAssets.forEach { fileName ->
                    copyAssetToFile("aikit_resources/$fileName", File(targetDir, fileName))
                }
                Timber.i("$TAG: Copied ${aikitAssets.size} resource files from aikit_resources/ to ${targetDir.absolutePath}")
                return
            }

            // 兼容旧路径: assets/iflytek/ivw/
            val ivwAssets = assetManager.list("iflytek/ivw")
            if (!ivwAssets.isNullOrEmpty()) {
                ivwAssets.forEach { fileName ->
                    copyAssetToFile("iflytek/ivw/$fileName", File(targetDir, fileName))
                }
                Timber.i("$TAG: Copied ${ivwAssets.size} resource files from iflytek/ivw/ to ${targetDir.absolutePath}")
                return
            }

            // 最后尝试直接从 assets 根目录查找
            val rootAssets = assetManager.list("")
            val ivwFiles = rootAssets?.filter {
                it.endsWith(".jet") || it.endsWith(".bin") || it.contains("ivw") || it.contains("wake")
            }
            if (!ivwFiles.isNullOrEmpty()) {
                ivwFiles.forEach { fileName ->
                    copyAssetToFile(fileName, File(targetDir, fileName))
                }
                Timber.i("$TAG: Copied ${ivwFiles.size} resource files from assets root to ${targetDir.absolutePath}")
                return
            }

            Timber.w("$TAG: No resource files found in assets/. Resource files may be downloaded on first activation.")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error copying resource files")
        }
    }

    private fun copyAssetToFile(assetPath: String, targetFile: File) {
        try {
            val inputStream: InputStream = context.assets.open(assetPath)
            targetFile.outputStream().use { out ->
                inputStream.copyTo(out)
            }
        } catch (e: Exception) {
            Timber.w("$TAG: Failed to copy asset $assetPath: ${e.message}")
        }
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

            // 授权状态监听
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
                        Timber.i("$TAG: SDK authorized successfully")
                    } else {
                        Timber.e("$TAG: SDK auth failed, type=$typeName, code=$code")
                    }
                }
                null
            }
            aiHelperClass.getMethod("registerListener", authListenerClass)
                .invoke(aiHelper, authListener)

            // 能力结果监听
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
                                Timber.d("$TAG: onResult key=$key value=$valueStr")
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
                        val event = args?.get(2) as? Int ?: -1
                        Timber.d("$TAG: onEvent event=$event")
                        null
                    }
                    "onError" -> {
                        val err = args?.get(1) as? Int ?: -1
                        val msg = args?.get(2) as? String
                        Timber.e("$TAG: onError err=$err msg=$msg")
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
                Timber.i("$TAG: ★ Wake word detected! keyword=$keyword, score=$score")
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
        if (isListening.get()) {
            Timber.d("$TAG: Already listening")
            return
        }

        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)

            // 1. 初始化引擎
            val engineInitRet = aiHelperClass.getMethod("engineInit", String::class.java)
                .invoke(aiHelper, IVW_ID) as? Int ?: -1
            if (engineInitRet != 0) {
                Timber.e("$TAG: Engine init failed: $engineInitRet")
                return
            }
            Timber.i("$TAG: Engine initialized")

            // 2. 加载唤醒词
            loadWakeUpWords()

            // 3. 指定数据集
            val indexs = intArrayOf(0)
            val specifyRet = aiHelperClass.getMethod("specifyDataSet", String::class.java, String::class.java, IntArray::class.java)
                .invoke(aiHelper, IVW_ID, "key_word", indexs) as? Int ?: -1
            if (specifyRet != 0) {
                Timber.w("$TAG: Specify dataset failed: $specifyRet")
            }

            // 4. 创建会话（设置持续唤醒）
            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("$CLS_AI_REQUEST\$Builder")
            val builder = builderClass.getMethod("builder").invoke(null)

            // ★ 修复3：设置持续唤醒 + 门限值
            try {
                builderClass.getMethod("param", String::class.java, Any::class.java)
                    .invoke(builder, "KEEP_ALIVE", "1")
                builderClass.getMethod("param", String::class.java, Any::class.java)
                    .invoke(builder, "wdec_param_nCmThreshold", "0 0:$threshold")
            } catch (e: Exception) {
                Timber.w("$TAG: Failed to set KEEP_ALIVE: ${e.message}")
            }

            val request = builderClass.getMethod("build").invoke(builder)

            aiHandle = aiHelperClass.getMethod("start", String::class.java, aiRequestClass, Any::class.java)
                .invoke(aiHelper, IVW_ID, request, null)

            val isSuccess = aiHandle?.javaClass?.getMethod("isSuccess")?.invoke(aiHandle) as? Boolean ?: false
            if (!isSuccess) {
                val code = aiHandle?.javaClass?.getMethod("getCode")?.invoke(aiHandle) as? Int ?: -1
                Timber.e("$TAG: Start session failed: $code")
                return
            }

            isListening.set(true)
            Timber.i("$TAG: ★ Wake-up session started, listening for '$wakeWord'")

            // ★ 修复2：启动音频写入线程
            startAudioWriteThread()

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start listening")
            isListening.set(false)
        }
    }

    /**
     * ★ 修复2（核心）：持续向 SDK 写入音频帧
     * 官方文档 6.7.2 节：必须通过 write() 送入音频数据
     * 不送音频 → SDK 无法检测唤醒词 → 永远不会回调
     */
    private fun startAudioWriteThread() {
        writeThread = Thread({
            Timber.i("$TAG: Audio write thread started")
            try {
                val aiHelperClass = Class.forName(CLS_AI_HELPER)
                val aiAudioClass = Class.forName(CLS_AI_AUDIO)
                val aiStatusClass = Class.forName(CLS_AI_STATUS)
                val aiRequestClass = Class.forName(CLS_AI_REQUEST)
                val builderClass = Class.forName("$CLS_AI_REQUEST\$Builder")

                var frameIndex = 0
                while (isListening.get()) {
                    val audioData = audioDataRef.get()
                    if (audioData != null && aiHandle != null) {
                        try {
                            val dataBuilder = builderClass.getMethod("builder").invoke(null)

                            // 构建音频数据: AiAudio.get("wav").encoding(...).data(...).status(...)
                            val holder = aiAudioClass.getMethod("get", String::class.java)
                                .invoke(null, "wav")

                            val encodingConst = aiAudioClass.getDeclaredField("ENCODING_DEFAULT").let {
                                it.isAccessible = true
                                it.get(null)
                            }

                            aiAudioClass.getMethod("encoding", Any::class.java).invoke(holder, encodingConst)
                            aiAudioClass.getMethod("data", ByteArray::class.java).invoke(holder, audioData)

                            // 首帧用 BEGIN，后续用 CONTINUE
                            val status = if (frameIndex == 0) {
                                aiStatusClass.getField("BEGIN").get(null)
                            } else {
                                aiStatusClass.getField("CONTINUE").get(null)
                            }
                            aiAudioClass.getMethod("status", Any::class.java).invoke(holder, status)

                            val validPayload = aiAudioClass.getMethod("valid").invoke(holder)
                            aiRequestClass.getMethod("payload", Any::class.java).invoke(dataBuilder, validPayload)

                            val writeRequest = builderClass.getMethod("build").invoke(dataBuilder)
                            val ret = aiHelperClass.getMethod("write", aiRequestClass, Any::class.java)
                                .invoke(aiHelper, writeRequest, aiHandle) as? Int ?: -1

                            if (ret != 0 && frameIndex % 100 == 0) {
                                Timber.w("$TAG: Write failed at frame $frameIndex: $ret")
                            }

                            frameIndex++
                        } catch (e: Exception) {
                            if (isListening.get()) {
                                Timber.e(e, "$TAG: Error writing audio frame $frameIndex")
                            }
                        }
                    } else {
                        // 没有音频数据，等待
                        Thread.sleep(20)
                    }
                    Thread.sleep(10) // 控制写入频率
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Audio write thread error")
            }
            Timber.i("$TAG: Audio write thread stopped")
        }, "$TAG-audio-writer").apply {
            isDaemon = true
            priority = Thread.MAX_PRIORITY
        }
        writeThread?.start()
    }

    /**
     * 供外部调用：送入音频帧数据
     * WakeWordServiceEnhanced 的 AudioRecorder 回调会调用此方法
     */
    fun feedAudioData(data: ByteArray) {
        audioDataRef.set(data)
    }

    private fun loadWakeUpWords() {
        try {
            val keywordFile = File(context.getExternalFilesDir(null), "iflytek/keyword.txt")
            if (!keywordFile.exists()) {
                keywordFile.parentFile?.mkdirs()
                keywordFile.writeText("$wakeWord;nCM:$threshold;\n")
                Timber.i("$TAG: Created keyword file: $wakeWord;nCM:$threshold;")
            } else {
                Timber.d("$TAG: Keyword file already exists")
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
                Timber.i("$TAG: Wake words loaded: $wakeWord (threshold=$threshold)")
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error loading wake words")
        }
    }

    override fun stop() {
        stopListening()
    }

    fun stopListening() {
        if (!isListening.getAndSet(false)) return

        // 停止音频写入线程
        writeThread?.interrupt()
        writeThread = null

        // ★ 修复：官方文档 6.7.4 节 — 用 end() 结束会话
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            if (aiHandle != null) {
                val ret = aiHelperClass.getMethod("end", Any::class.java)
                    .invoke(aiHelper, aiHandle) as? Int ?: -1
                if (ret != 0) {
                    Timber.w("$TAG: End session failed: $ret")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error ending session")
        }

        aiHandle = null
        Timber.i("$TAG: Stopped listening")
    }

    fun getFrameLength(): Int = 320  // 官方文档 6.7.2 节：frame_size 固定 320
    fun getSampleRate(): Int = 16000
    fun isListening(): Boolean = isListening.get()

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
