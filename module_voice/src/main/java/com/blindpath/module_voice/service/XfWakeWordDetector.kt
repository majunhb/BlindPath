package com.blindpath.module_voice.service

import android.content.Context
import com.blindpath.module_voice.domain.WakeWordDetector
import com.blindpath.module_voice.domain.model.WakeWordConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ArrayBlockingQueue

/**
 * 科大讯飞 AIKit 语音唤醒检测器（反射实现）
 *
 * 基于讯飞 AIKit SDK (新版) 实现。
 * 能力ID: e867a88f2 (语音唤醒)
 *
 * ★★★ v2.0 修复（2026-06-18）：
 * 1. 授权流程修复：不再在构造时立即调 startListening()，而是等 AuthListener 回调成功后再启动
 * 2. 资源路径修复：资源复制到 workDir 根目录（非 workDir/ivw/）
 * 3. 非 self-managed：由 WakeWordServiceEnhanced 统一采集音频并喂入
 * 4. 音频写入线程在 SDK 会话启动后才启动
 *
 * 核心流程:
 * 1. init() → 初始化 SDK（启动异步授权）
 * 2. AuthListener.onAuthStateChange → 授权成功 → 自动调 startListening()
 * 3. startListening() → engineInit → loadWakeUpWords → 创建会话 → 启动写入线程
 * 4. feedAudioData() → 接收外部音频帧
 * 5. writeThread → 持续送入音频帧到 SDK
 * 6. onResult → 收到唤醒回调
 */
class XfWakeWordDetector(
    private val context: Context,
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String,
    private val threshold: Int = WakeWordConfig.XF_WAKE_THRESHOLD,
    private val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD,
    private val onWakeWordDetected: (String) -> Unit,
    private val onAuthSuccessCb: (() -> Unit)? = null,
    private val onAuthFailedCb: (() -> Unit)? = null
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
    private val audioDataQueue = ArrayBlockingQueue<ByteArray>(20)
    private var workDirPath: String = ""

    // ★★★ 授权状态
    @Volatile private var isAuthComplete = false
    @Volatile private var isAuthFailed = false

    // ★ 授权回调（供 EngineManager 感知）
    var onAuthSuccess: (() -> Unit)? = null
    var onAuthFailed: (() -> Unit)? = null

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
        workDirPath = workDir.absolutePath

        // ★ 修复1：从 assets 复制唤醒资源文件到 workDir 根目录（非 workDir/ivw/）
        copyResourceFiles(workDir)

        initSdkByReflection(workDir.absolutePath)
        isInitialized = true
        Timber.i("$TAG: AIKit SDK initialized, workDir=${workDir.absolutePath}, waiting for auth...")
    }

    /**
     * ★ 修复1（v2.0）：将 assets/aikit_resources/ 下的唤醒资源复制到 workDir 根目录
     * 官方文档 6.3 节：复制 resource 文件夹中资源到 SDK 工作目录（即 workDir 根目录）
     */
    private fun copyResourceFiles(workDir: File) {
        try {
            // ★ 直接复制到 workDir 根目录
            val assetManager = context.assets

            // 优先尝试官方推荐路径: assets/aikit_resources/
            val aikitAssets = assetManager.list("aikit_resources")
            if (!aikitAssets.isNullOrEmpty()) {
                aikitAssets.forEach { fileName ->
                    copyAssetRecursive("aikit_resources/$fileName", File(workDir, fileName), assetManager)
                }
                Timber.i("$TAG: ★ Copied ${aikitAssets.size} items from aikit_resources/ to ${workDir.absolutePath}")
                return
            }

            // 兼容旧路径: assets/iflytek/ivw/
            val ivwAssets = assetManager.list("iflytek/ivw")
            if (!ivwAssets.isNullOrEmpty()) {
                ivwAssets.forEach { fileName ->
                    copyAssetRecursive("iflytek/ivw/$fileName", File(workDir, fileName), assetManager)
                }
                Timber.i("$TAG: Copied ${ivwAssets.size} items from iflytek/ivw/ to ${workDir.absolutePath}")
                return
            }

            Timber.w("$TAG: No resource files found in assets/")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error copying resource files")
        }
    }

    /**
     * 递归复制 asset 文件/目录到目标路径
     */
    private fun copyAssetRecursive(assetPath: String, targetFile: File, assetManager: android.content.res.AssetManager) {
        try {
            val children = try { assetManager.list(assetPath) } catch (e: Exception) { null }
            if (children != null && children.isNotEmpty()) {
                targetFile.mkdirs()
                children.forEach { child ->
                    copyAssetRecursive("$assetPath/$child", File(targetFile, child), assetManager)
                }
            } else {
                targetFile.outputStream().use { out ->
                    assetManager.open(assetPath).use { inp -> inp.copyTo(out) }
                }
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
            // ★★★ v3.1 修复：:wakeword进程只注册IVW唤醒能力，不注册ESR
            // 双进程各自注册对方能力会导致AiHelper单例冲突，授权失败
            builderClass.getMethod("ability", String::class.java).invoke(builder, IVW_ID)
            builderClass.getMethod("authInterval", Int::class.javaPrimitiveType).invoke(builder, AUTH_INTERVAL)
            builderClass.getMethod("iLogMaxCount", Int::class.javaPrimitiveType).invoke(builder, 1)

            val params = builderClass.getMethod("build").invoke(builder)
            aiHelperClass.getMethod("init", Context::class.java, paramsClass)
                .invoke(aiHelper, context, params)

            val logLvlClass = Class.forName(CLS_LOG_LVL)
            val errorLevel = logLvlClass.getField("ERROR").get(null)
            aiHelperClass.getMethod("setLogLevel", logLvlClass).invoke(aiHelper, errorLevel)

            // ★★★ 授权状态监听 — 授权成功后自动启动引擎
            val authListenerClass = Class.forName(CLS_AUTH_LISTENER)
            val authListener = Proxy.newProxyInstance(
                authListenerClass.classLoader,
                arrayOf(authListenerClass)
            ) { _, method, args ->
                if (method.name == "onAuthStateChange") {
                    val type = args?.get(0)
                    val code = args?.get(1) as? Int ?: -1
                    val typeName = type?.toString() ?: ""
                    Timber.i("$TAG: ★ Auth state change: type=$typeName, code=$code")
                    if (typeName.contains("AUTH") && code == 0) {
                        Timber.i("$TAG: ★★★ SDK authorized successfully! Starting engine...")
                        isAuthComplete = true
                        // ★ v3.2 修复：先通知外部（EngineManager），再启动引擎
                        onAuthSuccessCb?.invoke()
                        onAuthSuccess?.invoke()
                        // ★ 自动启动引擎（异步，不阻塞回调线程）
                        Thread {
                            try {
                                Thread.sleep(100) // 短暂等待，确保 SDK 内部状态就绪
                                startListening()
                            } catch (e: Exception) {
                                Timber.e(e, "$TAG: Auto-start after auth failed")
                            }
                        }.start()
                    } else if (typeName.contains("FAIL") || code != 0) {
                        Timber.e("$TAG: ✗ SDK auth FAILED: type=$typeName, code=$code")
                        isAuthFailed = true
                        onAuthFailedCb?.invoke()
                        onAuthFailed?.invoke()
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
                Timber.i("$TAG: ★★★ Wake word detected! keyword=$keyword, score=$score")
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

    /**
     * ★★★ v2.0 修复：授权完成后才启动引擎
     *
     * 调用时机：
     * - AuthListener 回调成功后自动调用
     * - 外部可安全重复调用（幂等）
     */
    fun startListening() {
        if (!isInitialized || aiHelper == null) {
            Timber.w("$TAG: Not initialized, cannot start listening")
            return
        }
        if (isListening.get()) {
            Timber.d("$TAG: Already listening")
            return
        }

        // ★ 检查授权状态
        if (!isAuthComplete) {
            if (isAuthFailed) {
                Timber.e("$TAG: Auth already failed, cannot start listening")
                return
            }
            Timber.w("$TAG: Auth not complete yet, waiting for AuthListener callback...")
            return
        }

        try {
            Timber.i("$TAG: ★ Starting wake-up engine (auth complete)...")

            val aiHelperClass = Class.forName(CLS_AI_HELPER)

            // 1. 初始化引擎
            val engineInitRet = aiHelperClass.getMethod("engineInit", String::class.java)
                .invoke(aiHelper, IVW_ID) as? Int ?: -1
            if (engineInitRet != 0) {
                Timber.e("$TAG: Engine init failed: $engineInitRet")
                return
            }
            Timber.i("$TAG: ★ Engine initialized successfully")

            // 2. 加载唤醒词
            loadWakeUpWords()

            // 3. 指定数据集
            val indexs = intArrayOf(0)
            val specifyRet = aiHelperClass.getMethod("specifyDataSet", String::class.java, String::class.java, IntArray::class.java)
                .invoke(aiHelper, IVW_ID, "key_word", indexs) as? Int ?: -1
            if (specifyRet != 0) {
                Timber.w("$TAG: Specify dataset failed: $specifyRet (non-fatal)")
            }

            // 4. 创建会话（设置持续唤醒）
            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("$CLS_AI_REQUEST\$Builder")
            val builder = builderClass.getMethod("builder").invoke(null)

            // 设置持续唤醒 + 门限值
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
                Timber.e("$TAG: Start session failed: code=$code")
                return
            }

            isListening.set(true)
            Timber.i("$TAG: ★★★ Wake-up session STARTED! Listening for '$wakeWord' (threshold=$threshold)")

            // ★ 启动音频写入线程
            startAudioWriteThread()

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start listening")
            isListening.set(false)
        }
    }

    /**
     * ★ 持续向 SDK 写入音频帧
     * 由 WakeWordServiceEnhanced 的 AudioRecorder 通过 feedAudioData() 喂入音频
     */
    private fun startAudioWriteThread() {
        writeThread = Thread({
            Timber.i("$TAG: ★ Audio write thread started")
            try {
                val aiHelperClass = Class.forName(CLS_AI_HELPER)
                val aiAudioClass = Class.forName(CLS_AI_AUDIO)
                val aiStatusClass = Class.forName(CLS_AI_STATUS)
                val aiRequestClass = Class.forName(CLS_AI_REQUEST)
                val builderClass = Class.forName("$CLS_AI_REQUEST\$Builder")

                var frameIndex = 0
                while (isListening.get()) {
                    // ★ v3.2 修复：使用阻塞队列，避免帧丢失
                    val audioData = audioDataQueue.poll() // 非阻塞取出，无数据返回null
                    if (audioData != null && aiHandle != null) {
                        try {
                            val dataBuilder = builderClass.getMethod("builder").invoke(null)

                            val holder = aiAudioClass.getMethod("get", String::class.java)
                                .invoke(null, "wav")

                            val encodingConst = aiAudioClass.getDeclaredField("ENCODING_DEFAULT").let {
                                it.isAccessible = true
                                it.get(null)
                            }

                            aiAudioClass.getMethod("encoding", Any::class.java).invoke(holder, encodingConst)
                            aiAudioClass.getMethod("data", ByteArray::class.java).invoke(holder, audioData)

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
                                Timber.w("$TAG: Write returned $ret at frame $frameIndex")
                            }

                            frameIndex++
                        } catch (e: Exception) {
                            if (isListening.get() && frameIndex % 50 == 0) {
                                Timber.e(e, "$TAG: Error writing audio frame $frameIndex")
                            }
                        }
                    } else {
                        // 无数据时短暂等待，避免 CPU 空转
                        Thread.sleep(5)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Audio write thread error")
            }
            Timber.i("$TAG: Audio write thread stopped (frames written: ${writeThread?.name})")
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
        // ★ v3.2 修复：使用队列缓冲，队列满时丢弃最旧帧
        if (!audioDataQueue.offer(data)) {
            audioDataQueue.poll() // 丢弃最旧帧
            audioDataQueue.offer(data)
        }
    }

    private fun loadWakeUpWords() {
        try {
            // ★ v3.2 修复：keyword.txt 必须与 SDK 资源在同一目录（workDir）
            val keywordFile = File(workDirPath, "keyword.txt")
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
                Timber.i("$TAG: ★ Wake words loaded: $wakeWord (threshold=$threshold)")
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

        writeThread?.interrupt()
        writeThread = null

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
    fun isAuthComplete(): Boolean = isAuthComplete

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
