package com.blindpath.module_voice.service

import android.content.Context
import com.blindpath.module_voice.domain.WakeWordDetector
import com.blindpath.module_voice.domain.model.WakeWordConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 科大讯飞 AIKit 语音唤醒检测器（反射实现）
 *
 * 基于讯飞 AIKit SDK 官方文档实现。
 * 能力ID: e867a88f2 (语音唤醒)
 *
 * 官方调用链路:
 *   initEntry → registerListener → loadData → preProcess → specifyDataSet
 *   → start → write(持续送音频) → 回调唤醒结果 → end → unInit
 *
 * ★★★ v3.2 修复（2026-06-29）：
 * 1. ConcurrentLinkedQueue 替代 AtomicReference，避免音频帧丢失
 * 2. 唤醒词文件路径统一为 workDir 下
 * 3. 添加 preProcess 预处理调用
 * 4. 线程中断正确处理 InterruptedException
 * 5. 唤醒词文件添加 nSubCM 字门限，降低相似唤醒词串扰
 * 6. SDK 错误码映射为可读信息
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
        private const val CLS_AI_AUDIO = "com.iflytek.aikit.core.AiAudio"
        private const val CLS_AI_STATUS = "com.iflytek.aikit.core.AiStatus"

        // 队列最大容量，防止 OOM
        private const val MAX_QUEUE_SIZE = 10

        private var sdkAvailable: Boolean? = null

        /** SDK 错误码 → 可读描述 */
        private val errorCodeMap: Map<Int, String> = mapOf(
            18000 to "授权失败(通用)",
            18001 to "appId错误",
            18002 to "apiKey错误",
            18003 to "授权过期",
            18004 to "设备指纹不匹配",
            18005 to "授权量耗尽",
            18100 to "资源缺失",
            18101 to "资源路径无读写权限",
            18102 to "资源损坏",
            18200 to "引擎未初始化",
            18300 to "会话参数非法",
            18310 to "会话重复开启(未end就start)",
            18400 to "工作目录无读写权限",
            18700 to "能力未授权(需在控制台开通)",
            18714 to "密钥错误(apiKey/apiSecret)",
            18800 to "未知错误",
        )

        fun getErrorMessage(code: Int): String =
            errorCodeMap[code] ?: "未知错误($code)"
    }

    private var isInitialized = false
    private val isListening = AtomicBoolean(false)
    private var aiHelper: Any? = null
    private var aiHandle: Any? = null
    private var callback: WakeWordDetector.Callback? = null

    // ★★★ v3.2: ConcurrentLinkedQueue 替代 AtomicReference，避免丢帧
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    private var writeThread: Thread? = null

    @Volatile private var isAuthComplete = false
    @Volatile private var isAuthFailed = false

    var onAuthSuccess: (() -> Unit)? = null
    var onAuthFailed: (() -> Unit)? = null

    /** 工作目录路径，供外部查询 */
    private var workDirPath: String = ""

    init {
        try {
            initialize()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize")
        }
    }

    // ──────────────────────────────────────────────
    // 初始化
    // ──────────────────────────────────────────────

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

        // ★ 官方文档 6.3 节: workDir 用于存放资源文件和 license 缓存
        val workDir = File(context.getExternalFilesDir(null), "iflytek/aikit")
        if (!workDir.exists()) {
            workDir.mkdirs()
        }
        workDirPath = workDir.absolutePath

        // 从 assets 复制唤醒资源文件到 workDir
        copyResourceFiles(workDir)

        // SDK 全局初始化（仅一次）
        initSdkByReflection(workDirPath)
        isInitialized = true
        Timber.i("$TAG: AIKit SDK initialized, workDir=$workDirPath, waiting for auth...")
    }

    /**
     * 官方文档 6.3 节：复制 resource 资源到 SDK 工作目录
     */
    private fun copyResourceFiles(workDir: File) {
        try {
            val assetManager = context.assets

            // 优先尝试官方推荐路径: assets/aikit_resources/
            val aikitAssets = assetManager.list("aikit_resources")
            if (!aikitAssets.isNullOrEmpty()) {
                aikitAssets.forEach { fileName ->
                    copyAssetRecursive("aikit_resources/$fileName", File(workDir, fileName), assetManager)
                }
                Timber.i("$TAG: Copied ${aikitAssets.size} items from aikit_resources/ to ${workDir.absolutePath}")
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

    // ──────────────────────────────────────────────
    // SDK 初始化 (反射)
    // ──────────────────────────────────────────────

    private fun initSdkByReflection(workDirPath: String) {
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            val getInstMethod = aiHelperClass.getMethod("getInst")
            aiHelper = getInstMethod.invoke(null)

            // 构建 Params
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

            // 官方文档: initEntry (这里反射调用 init)
            aiHelperClass.getMethod("init", Context::class.java, paramsClass)
                .invoke(aiHelper, context, params)

            // 日志级别: DEBUG (方便调试，发布可改为 ERROR)
            val logLvlClass = Class.forName(CLS_LOG_LVL)
            val debugLevel = logLvlClass.getField("DEBUG").get(null)
            aiHelperClass.getMethod("setLogLevel", logLvlClass).invoke(aiHelper, debugLevel)

            // ★★★ 官方文档: 先注册监听器，再 init 会触发授权流程
            // AuthListener 注册
            val authListenerClass = Class.forName(CLS_AUTH_LISTENER)
            val authListener = Proxy.newProxyInstance(
                authListenerClass.classLoader,
                arrayOf(authListenerClass)
            ) { _, method, args ->
                if (method.name == "onAuthStateChange") {
                    val type = args?.get(0)
                    val code = args?.get(1) as? Int ?: -1
                    val typeName = type?.toString() ?: ""
                    Timber.i("$TAG: Auth state change: type=$typeName, code=$code")
                    if (typeName.contains("AUTH") && code == 0) {
                        Timber.i("$TAG: ★★★ SDK authorized! Starting engine...")
                        isAuthComplete = true
                        onAuthSuccess?.invoke()
                        // 异步启动引擎（不阻塞回调线程）
                        Thread {
                            try {
                                Thread.sleep(100)
                                startListening()
                            } catch (e: Exception) {
                                Timber.e(e, "$TAG: Auto-start after auth failed")
                            }
                        }.start()
                    } else if (typeName.contains("FAIL") || code != 0) {
                        val errMsg = getErrorMessage(code)
                        Timber.e("$TAG: ✗ SDK auth FAILED: $errMsg (code=$code)")
                        isAuthFailed = true
                        callback?.onError(WakeWordDetector.ERROR_UNKNOWN, "认证失败: $errMsg")
                        onAuthFailed?.invoke()
                    }
                }
                null
            }
            aiHelperClass.getMethod("registerListener", authListenerClass)
                .invoke(aiHelper, authListener)

            // AiListener 注册
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
                        val errMsg = getErrorMessage(err)
                        Timber.e("$TAG: onError $errMsg (code=$err) msg=$msg")
                        callback?.onError(WakeWordDetector.ERROR_UNKNOWN, errMsg)
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

    // ──────────────────────────────────────────────
    // 唤醒结果解析
    // ──────────────────────────────────────────────

    private fun parseWakeUpResult(json: String) {
        try {
            val root = JSONObject(json)
            val rltArray = root.optJSONArray("rlt")
            if (rltArray != null && rltArray.length() > 0) {
                val result = rltArray.getJSONObject(0)
                val keyword = result.optString("keyword", "")
                val score = result.optInt("score", 0)
                val bTime = result.optInt("beginTime", 0)
                val eTime = result.optInt("endTime", 0)
                Timber.i("$TAG: ★★★ Wake word: '$keyword' score=$score time=[$bTime,$eTime]")
                onWakeWordDetected.invoke(wakeWord)
                callback?.onWakeWordDetected(wakeWord, 0.9f)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse wake result, triggering anyway")
            onWakeWordDetected.invoke(wakeWord)
            callback?.onWakeWordDetected(wakeWord, 0.9f)
        }
    }

    // ──────────────────────────────────────────────
    // 唤醒词加载 (loadData → preProcess → specifyDataSet)
    // ──────────────────────────────────────────────

    /**
     * ★★★ v3.2: 唤醒词文件写入 workDir 下（与资源文件同目录）
     * 格式: 唤醒词;nCM:词门限;nSubCM:字门限
     */
    private fun loadWakeUpWords() {
        try {
            // ★ 修复: 写入 workDir 下，而非独立的 iflytek/ 目录
            val keywordFile = File(workDirPath, "keyword.txt")
            if (!keywordFile.exists()) {
                keywordFile.parentFile?.mkdirs()
                // 官方文档: 唤醒词;nCM:词门限;nSubCM:字门限
                // nSubCM 字门限可大幅降低相似唤醒词串扰
                val subCmThreshold = (threshold * 0.6).toInt()
                keywordFile.writeText("$wakeWord;nCM:$threshold;nSubCM:$subCmThreshold;\n")
                Timber.i("$TAG: Created keyword file: $wakeWord nCM=$threshold nSubCM=$subCmThreshold")
            } else {
                Timber.d("$TAG: Keyword file already exists at ${keywordFile.absolutePath}")
            }

            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("$CLS_AI_REQUEST\$Builder")
            val builder = builderClass.getMethod("builder").invoke(null)

            // 官方文档: loadData 加载唤醒词 txt 配置文件
            builderClass.getMethod("customText", String::class.java, String::class.java, Int::class.javaPrimitiveType)
                .invoke(builder, "key_word", keywordFile.absolutePath, 0)

            val request = builderClass.getMethod("build").invoke(builder)
            val ret = aiHelperClass.getMethod("loadData", String::class.java, aiRequestClass)
                .invoke(aiHelper, IVW_ID, request) as? Int ?: -1

            if (ret != 0) {
                Timber.e("$TAG: Load wake words failed: ${getErrorMessage(ret)}")
                return
            }
            Timber.i("$TAG: ★ Wake words loaded: $wakeWord")

            // ★★★ 官方文档: preProcess 预处理资源，优化加载速度
            val preRet = aiHelperClass.getMethod("preProcess", String::class.java, aiRequestClass)
                .invoke(aiHelper, IVW_ID, request) as? Int ?: -1
            if (preRet != 0) {
                Timber.w("$TAG: preProcess failed: ${getErrorMessage(preRet)} (non-fatal)")
            } else {
                Timber.i("$TAG: ★ preProcess completed")
            }

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error loading wake words")
        }
    }

    // ──────────────────────────────────────────────
    // 会话管理 (start → write → end)
    // ──────────────────────────────────────────────

    override fun start(): Boolean {
        startListening()
        return true
    }

    /**
     * 启动唤醒引擎
     * 官方文档调用链: engineInit → loadData → preProcess → specifyDataSet → start
     */
    fun startListening() {
        if (!isInitialized || aiHelper == null) {
            Timber.w("$TAG: Not initialized")
            return
        }
        if (isListening.get()) {
            Timber.d("$TAG: Already listening")
            return
        }
        if (!isAuthComplete) {
            if (isAuthFailed) {
                Timber.e("$TAG: Auth already failed, cannot start")
                return
            }
            Timber.w("$TAG: Auth not complete yet, waiting...")
            return
        }

        try {
            Timber.i("$TAG: ★ Starting wake-up engine...")
            val aiHelperClass = Class.forName(CLS_AI_HELPER)

            // 1. engineInit
            val engineInitRet = aiHelperClass.getMethod("engineInit", String::class.java)
                .invoke(aiHelper, IVW_ID) as? Int ?: -1
            if (engineInitRet != 0) {
                Timber.e("$TAG: Engine init failed: ${getErrorMessage(engineInitRet)}")
                callback?.onError(WakeWordDetector.ERROR_MODEL_LOAD, getErrorMessage(engineInitRet))
                return
            }
            Timber.i("$TAG: ★ Engine initialized")

            // 2. loadData → preProcess → specifyDataSet
            loadWakeUpWords()

            // 3. specifyDataSet: 指定生效的唤醒词索引
            val indexs = intArrayOf(0)
            val specifyRet = aiHelperClass.getMethod(
                "specifyDataSet", String::class.java, String::class.java, IntArray::class.java
            ).invoke(aiHelper, IVW_ID, "key_word", indexs) as? Int ?: -1
            if (specifyRet != 0) {
                Timber.w("$TAG: specifyDataSet failed: ${getErrorMessage(specifyRet)} (non-fatal)")
            }

            // 4. start: 创建会话
            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("$CLS_AI_REQUEST\$Builder")
            val builder = builderClass.getMethod("builder").invoke(null)

            // KEEP_ALIVE=1 持续唤醒 + 门限参数
            builderClass.getMethod("param", String::class.java, Any::class.java)
                .invoke(builder, "KEEP_ALIVE", "1")
            builderClass.getMethod("param", String::class.java, Any::class.java)
                .invoke(builder, "wdec_param_nCmThreshold", "0 0:$threshold")

            val request = builderClass.getMethod("build").invoke(builder)

            aiHandle = aiHelperClass.getMethod("start", String::class.java, aiRequestClass, Any::class.java)
                .invoke(aiHelper, IVW_ID, request, null)

            val isSuccess = aiHandle?.javaClass?.getMethod("isSuccess")?.invoke(aiHandle) as? Boolean ?: false
            if (!isSuccess) {
                val code = aiHandle?.javaClass?.getMethod("getCode")?.invoke(aiHandle) as? Int ?: -1
                Timber.e("$TAG: Start session failed: ${getErrorMessage(code)}")
                callback?.onError(WakeWordDetector.ERROR_UNKNOWN, getErrorMessage(code))
                aiHandle = null
                return
            }

            isListening.set(true)
            Timber.i("$TAG: ★★★ Wake-up session STARTED! '$wakeWord' (threshold=$threshold)")

            // 5. 启动音频写入线程
            startAudioWriteThread()

        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start listening")
            isListening.set(false)
        }
    }

    /**
     * 持续向 SDK 写入音频帧 (官方文档: write 接口)
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

                val encodingConst = aiAudioClass.getDeclaredField("ENCODING_DEFAULT").let {
                    it.isAccessible = true
                    it.get(null)
                }
                val beginStatus = aiStatusClass.getField("BEGIN").get(null)
                val continueStatus = aiStatusClass.getField("CONTINUE").get(null)

                var frameIndex = 0
                while (isListening.get() && !Thread.currentThread().isInterrupted) {
                    // ★★★ v3.2: 从队列 poll，非阻塞
                    val audioData = audioQueue.poll()
                    if (audioData != null && aiHandle != null) {
                        try {
                            val dataBuilder = builderClass.getMethod("builder").invoke(null)

                            val holder = aiAudioClass.getMethod("get", String::class.java)
                                .invoke(null, "wav")
                            aiAudioClass.getMethod("encoding", Any::class.java).invoke(holder, encodingConst)
                            aiAudioClass.getMethod("data", ByteArray::class.java).invoke(holder, audioData)

                            val status = if (frameIndex == 0) beginStatus else continueStatus
                            aiAudioClass.getMethod("status", Any::class.java).invoke(holder, status)

                            val validPayload = aiAudioClass.getMethod("valid").invoke(holder)
                            aiRequestClass.getMethod("payload", Any::class.java).invoke(dataBuilder, validPayload)

                            val writeRequest = builderClass.getMethod("build").invoke(dataBuilder)
                            val ret = aiHelperClass.getMethod("write", aiRequestClass, Any::class.java)
                                .invoke(aiHelper, writeRequest, aiHandle) as? Int ?: -1

                            if (ret != 0 && frameIndex % 100 == 0) {
                                Timber.w("$TAG: Write returned ${getErrorMessage(ret)} at frame $frameIndex")
                            }
                            frameIndex++
                        } catch (e: Exception) {
                            if (isListening.get() && frameIndex % 50 == 0) {
                                Timber.e(e, "$TAG: Error writing audio frame $frameIndex")
                            }
                        }
                    } else {
                        // ★ 正确处理中断
                        try {
                            Thread.sleep(20)
                        } catch (e: InterruptedException) {
                            Thread.currentThread().interrupt()
                            break
                        }
                    }
                }
            } catch (e: InterruptedException) {
                Timber.i("$TAG: Audio write thread interrupted")
                Thread.currentThread().interrupt()
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
     * ★★★ v3.2: 使用 ConcurrentLinkedQueue，offer 入队，丢弃旧帧防 OOM
     */
    fun feedAudioData(data: ByteArray) {
        if (!isListening.get()) return
        // 队列满时丢弃最旧帧，防止内存堆积
        while (audioQueue.size >= MAX_QUEUE_SIZE) {
            audioQueue.poll()
        }
        audioQueue.offer(data)
    }

    // ──────────────────────────────────────────────
    // 停止与释放
    // ──────────────────────────────────────────────

    override fun stop() {
        stopListening()
    }

    fun stopListening() {
        if (!isListening.getAndSet(false)) return

        // 停止写入线程
        writeThread?.interrupt()
        writeThread = null

        // 清空队列
        audioQueue.clear()

        // 官方文档: end 结束当前会话
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            if (aiHandle != null) {
                val ret = aiHelperClass.getMethod("end", Any::class.java)
                    .invoke(aiHelper, aiHandle) as? Int ?: -1
                if (ret != 0) {
                    Timber.w("$TAG: End session: ${getErrorMessage(ret)}")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error ending session")
        }

        aiHandle = null
        Timber.i("$TAG: Stopped listening")
    }

    override fun release() {
        stopListening()
        try {
            // 官方文档: unInit 释放引擎资源
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            aiHelperClass.getMethod("unInit").invoke(aiHelper)
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error during release")
        }
        isInitialized = false
        isAuthComplete = false
        Timber.i("$TAG: Released")
    }

    // ──────────────────────────────────────────────
    // 公共属性
    // ──────────────────────────────────────────────

    fun getFrameLength(): Int = 320  // 官方文档: 16k采样率下 frame_size 固定 320
    fun getSampleRate(): Int = 16000
    fun isListening(): Boolean = isListening.get()
    fun isAuthComplete(): Boolean = isAuthComplete

    override fun setCallback(callback: WakeWordDetector.Callback) {
        this.callback = callback
    }

    override fun setSensitivity(sens: Float) {
        Timber.d("$TAG: Set sensitivity: $sens")
    }
}