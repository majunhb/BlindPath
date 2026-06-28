package com.blindpath.module_voice.service

import android.content.Context
import com.blindpath.module_voice.domain.WakeWordDetector
import com.blindpath.module_voice.domain.model.WakeWordConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.lang.reflect.Proxy
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.ArrayBlockingQueue

/**
 * 科大讯飞 AIKit 语音唤醒检测器（反射实现）
 *
 * 基于讯飞 AIKit SDK (新版) 实现。
 * 能力ID: e867a88f2 (语音唤醒)
 *
 * ★★★ v3.0 修复（2026-06-28）—— 基于真机 bugreport + SDK 字节码反编译的根因分析：
 *  1. [致命] Params 类名错误：AiHelper$Params 不存在，实际为 BaseLibrary$Params
 *     （Bugreport 第 13854 行 ClassNotFoundException 的直接原因）
 *  2. [致命] builder() 静态方法调用对象错误：AiRequest$Builder 没有静态 builder()，
 *     该方法属于 AiRequest。影响 startListening/loadWakeUpWords/startAudioWriteThread
 *  3. [致命] param() 参数类型错误：SDK 无 param(String, Object)，
 *     只有 param(String,String)/(String,int)/(String,boolean)/(String,double)
 *  4. [致命] payload() 参数类型错误：SDK 无 payload(Object)，只有 payload(AiData)
 *  5. [致命] write() 第二参数类型错误：SDK 无 write(AiRequest, Object)，
 *     只有 write(AiRequest, AiHandle)
 *  6. [致命] end() 参数类型错误：SDK 无 end(Object)，只有 end(AiHandle)
 *  7. [致命] AiAudio$Holder 方法签名全部错误：
 *     encoding(Any)→encoding(String)、data(ByteArray)→data(ByteBuffer)、
 *     status(Any)→status(AiStatus)；且 valid() 返回值无法传入 payload(AiData)
 *     → 改用 AiRequest$Builder.audio(String, byte[]) 直接构建音频载荷
 *  8. [重要] sdkAvailable 检查不完整：只验证了 AiHelper，未验证 BaseLibrary$Params
 *     → 导致 SDK 不完整时虚假通过，延迟崩溃到 initSdkByReflection
 *  9. [重要] 缺少 ProGuard keep 规则：反射访问的 SDK 类在 release 构建中可能被 R8 剥离
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
        // ★ v3.0 新增：Params 类实际定义在 BaseLibrary 中，而非 AiHelper
        private const val CLS_PARAMS = "com.iflytek.aikit.core.BaseLibrary\$Params"
        private const val CLS_PARAMS_BUILDER = "com.iflytek.aikit.core.BaseLibrary\$Params\$Builder"
        // ★ v3.0 新增：AiData 类，payload() 方法所需类型
        private const val CLS_AI_DATA = "com.iflytek.aikit.core.AiData"

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

        // ★ v3.0 修复(Bug #8)：sdkAvailable 检查需要同时验证 AiHelper 和 BaseLibrary$Params
        // 避免 SDK 不完整时虚假通过，延迟崩溃到 initSdkByReflection
        if (sdkAvailable == null) {
            sdkAvailable = try {
                Class.forName(CLS_AI_HELPER)
                Class.forName(CLS_PARAMS) // ★ 新增：验证 Params 类存在
                Class.forName(CLS_PARAMS_BUILDER) // ★ 新增：验证 Params$Builder 类存在
                true
            } catch (e: ClassNotFoundException) {
                Timber.w(e, "$TAG: AIKit SDK not found (missing class: ${e.message})")
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

            // ★ v3.0 修复(Bug #1)：Params 和 Params$Builder 实际定义在 BaseLibrary 中
            // 原代码错误使用 CLS_AI_HELPER$Params（即 AiHelper$Params），该类不存在
            // 导致 ClassNotFoundException，是语音助手无法工作的直接原因
            val paramsClass = Class.forName(CLS_PARAMS)
            val builderClass = Class.forName(CLS_PARAMS_BUILDER)
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
            // init(Context, BaseLibrary$Params) — 参数类型匹配，无需修改
            aiHelperClass.getMethod("init", Context::class.java, paramsClass)
                .invoke(aiHelper, context, params)

            Timber.i("$TAG: SDK init complete, registering auth listener...")

            // ★ 注册授权监听器
            val authListenerClass = Class.forName(CLS_AUTH_LISTENER)
            val authListener = Proxy.newProxyInstance(
                authListenerClass.classLoader,
                arrayOf(authListenerClass)
            ) { _, method, args ->
                Timber.d("$TAG: AuthListener.${method.name} called, args=${args?.toList()}")
                when (method.name) {
                    "onAuthStateChange" -> {
                        val state = args?.get(0)?.toString() ?: "unknown"
                        Timber.i("$TAG: ★ Auth state changed: $state")
                        // SDK 返回的授权状态字符串，"2" 或 "authed" 表示成功
                        if (state == "2" || state == "authed" || state.contains("success", ignoreCase = true)) {
                            isAuthComplete = true
                            Timber.i("$TAG: ★★★ Auth SUCCESS! Starting listening...")
                            onAuthSuccess?.invoke()
                            onAuthSuccessCb?.invoke()
                            startListening()
                        } else if (state == "3" || state == "failed" || state.contains("fail", ignoreCase = true)) {
                            isAuthFailed = true
                            Timber.e("$TAG: ★ Auth FAILED")
                            onAuthFailed?.invoke()
                            onAuthFailedCb?.invoke()
                        }
                        null
                    }
                    "onError" -> {
                        val err = args?.get(0)
                        Timber.e("$TAG: Auth error: $err")
                        isAuthFailed = true
                        onAuthFailed?.invoke()
                        onAuthFailedCb?.invoke()
                        null
                    }
                    else -> null
                }
            }
            aiHelperClass.getMethod("registerListener", authListenerClass)
                .invoke(aiHelper, authListener)

            Timber.i("$TAG: ★ Auth listener registered, waiting for auth callback...")

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
            val aiHandleClass = Class.forName(CLS_AI_HANDLE) // ★ v3.0 修复：预加载 AiHandle 类

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
            // ★ v3.0 修复(Bug #4)：builder() 是 AiRequest 的静态方法，不是 AiRequest$Builder 的
            val builder = aiRequestClass.getMethod("builder").invoke(null)

            // 设置持续唤醒 + 门限值
            // ★ v3.0 修复(Bug #3)：SDK 无 param(String, Object) 方法
            // 实际签名：param(String,String) / param(String,int) / param(String,boolean) / param(String,double)
            try {
                builderClass.getMethod("param", String::class.java, String::class.java)
                    .invoke(builder, "KEEP_ALIVE", "1")
                builderClass.getMethod("param", String::class.java, String::class.java)
                    .invoke(builder, "wdec_param_nCmThreshold", "0 0:$threshold")
            } catch (e: Exception) {
                Timber.w("$TAG: Failed to set params: ${e.message}")
            }

            val request = builderClass.getMethod("build").invoke(builder)

            // start(String, AiRequest, Object) → AiHandle — 第三个参数 Object 传 null，类型匹配正确
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
     *
     * ★ v3.0 重构(Bug #7)：原实现通过 AiAudio.get() + 逐个反射调用 encoding/data/status/valid
     *   构建载荷，但 AiAudio$Holder 上的方法签名与代码中的调用全部不匹配
     *   （encoding(String) 误用 Any、data(ByteBuffer) 误用 byte[]、status(AiStatus) 误用 Any、
     *    valid() 返回 Object 无法传入 payload(AiData)）。
     *   改用 AiRequest$Builder.audio(String, byte[]) 直接构建音频载荷，
     *   这是 SDK 官方提供的等价捷径，避免手动构造 AiAudio$Holder。
     */
    private fun startAudioWriteThread() {
        writeThread = Thread({
            Timber.i("$TAG: ★ Audio write thread started")
            try {
                val aiHelperClass = Class.forName(CLS_AI_HELPER)
                val aiRequestClass = Class.forName(CLS_AI_REQUEST)
                val builderClass = Class.forName("$CLS_AI_REQUEST\$Builder")
                val aiStatusClass = Class.forName(CLS_AI_STATUS)

                // ★ v3.0 修复(Bug #4)：builder() 是 AiRequest 的静态方法
                // 预获取 builder 工厂方法和 audio/status/build 方法，避免在循环内重复反射查找
                val builderFactoryMethod = aiRequestClass.getMethod("builder")
                val audioMethod = builderClass.getMethod("audio", String::class.java, ByteArray::class.java)
                val statusMethod = builderClass.getMethod("status", aiStatusClass)
                val buildMethod = builderClass.getMethod("build")
                val writeMethod = aiHelperClass.getMethod("write", aiRequestClass, Class.forName(CLS_AI_HANDLE))

                // 预获取 AiStatus 枚举常量，避免在循环内重复反射
                val statusBegin = aiStatusClass.getField("BEGIN").get(null)
                val statusContinue = aiStatusClass.getField("CONTINUE").get(null)

                var frameIndex = 0
                while (isListening.get()) {
                    // ★ v3.2 修复：使用阻塞队列，避免帧丢失
                    val audioData = audioDataQueue.poll() // 非阻塞取出，无数据返回null
                    if (audioData != null && aiHandle != null) {
                        try {
                            // ★ v3.0 重构：使用 builder.audio(format, bytes) 直接构建音频载荷
                            // 等价于原实现中 AiAudio.get("wav") → encoding → data → valid → payload 的完整链路
                            val dataBuilder = builderFactoryMethod.invoke(null)

                            // audio(String encoding, byte[] data) — 直接设置音频格式和数据
                            audioMethod.invoke(dataBuilder, "wav", audioData)

                            // status(AiStatus) — 设置帧状态（首帧 BEGIN，后续 CONTINUE）
                            val status = if (frameIndex == 0) statusBegin else statusContinue
                            statusMethod.invoke(dataBuilder, status)

                            val writeRequest = buildMethod.invoke(dataBuilder)

                            // ★ v3.0 修复(Bug #6)：write(AiRequest, AiHandle)
                            // 原代码使用 Any::class.java 匹配第二参数，找不到方法
                            val ret = writeMethod
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
            // ★ v3.0 修复(Bug #4)：builder() 是 AiRequest 的静态方法
            val builder = aiRequestClass.getMethod("builder").invoke(null)

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
                // ★ v3.0 修复(Bug #7)：end(AiHandle) — 原代码使用 Any::class.java 找不到方法
                val aiHandleClass = Class.forName(CLS_AI_HANDLE)
                val ret = aiHelperClass.getMethod("end", aiHandleClass)
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
