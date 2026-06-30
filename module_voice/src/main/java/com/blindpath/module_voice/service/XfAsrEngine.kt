package com.blindpath.module_voice.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import timber.log.Timber
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 讯飞 AIKit 离线命令词识别引擎 (ESR)
 *
 * 能力ID: e75f07b62 (离线中英命令词识别, ESR)
 *
 * 相比听写(IAT/ee62fa27c)，ESR更适合本应用：
 * - 命令词识别更快更准，专为指令场景优化
 * - 完全离线，盲人户外弱网/无网可靠
 * - VAD自动端点检测，自动检测说话开始/结束
 * - 与唤醒共用AIKit SDK，统一生态
 *
 * 核心流程（基于官方ESR Demo）：
 * 1. initialize() → 初始化SDK（启动异步授权）
 * 2. AuthListener回调成功 → 引擎就绪
 * 3. startListening() → engineInit("fsa") → start(ESR参数) → AudioRecord采集 → feed音频
 * 4. write() + read() → 持续送入音频并读取结果
 * 5. onResult(key="plain") → 解析识别文本 → 回调上层
 * 6. onResult(status=2) → VAD检测说话结束 → 自动结束
 * 7. stopListening() → end会话
 */
class XfAsrEngine(
    private val context: Context,
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String
) {
    companion object {
        private const val TAG = "XfAsrEngine"
        private const val ESR_ID = "e75f07b62"
        private const val AUTH_INTERVAL = 333

        private const val CLS_AI_HELPER = "com.iflytek.aikit.core.AiHelper"
        private const val CLS_AI_REQUEST = "com.iflytek.aikit.core.AiRequest"
        private const val CLS_AI_RESPONSE = "com.iflytek.aikit.core.AiResponse"
        private const val CLS_AI_LISTENER = "com.iflytek.aikit.core.AiListener"
        private const val CLS_AUTH_LISTENER = "com.iflytek.aikit.core.AuthListener"
        private const val CLS_ERR_TYPE = "com.iflytek.aikit.core.ErrType"
        private const val CLS_LOG_LVL = "com.iflytek.aikit.core.LogLvl"
        private const val CLS_AI_AUDIO = "com.iflytek.aikit.core.AiAudio"
        private const val CLS_AI_STATUS = "com.iflytek.aikit.core.AiStatus"

        private const val SAMPLE_RATE = 16000
        private const val FRAME_SIZE = 1280  // 40ms @ 16K 16bit mono

        const val ERROR_NOT_READY = 1
        const val ERROR_AUDIO = 2
        const val ERROR_EMPTY_RESULT = 3
        const val ERROR_TIMEOUT = 4
        const val ERROR_SDK = 5
    }

    private var isInitialized = false
    @Volatile private var isAuthComplete = false
    @Volatile private var isAuthFailed = false
    private var sdkAvailable = false
    private var aiHelper: Any? = null
    private var aiHandle: Any? = null
    private var asrListener: Any? = null

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val isListening = AtomicBoolean(false)
    private var engineInited = false

    var onResult: ((text: String, isFinal: Boolean) -> Unit)? = null
    var onError: ((errorCode: Int, errorMsg: String) -> Unit)? = null
    var onReady: (() -> Unit)? = null
    var onBegin: (() -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    var onVolume: ((Float) -> Unit)? = null

    fun initialize(): Boolean {
        if (appId.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            Timber.e("$TAG: iFlytek credentials cannot be empty")
            return false
        }
        try {
            Class.forName(CLS_AI_HELPER)
            sdkAvailable = true
        } catch (e: ClassNotFoundException) {
            Timber.e("$TAG: AIKit SDK not found in classpath")
            return false
        }
        if (!sdkAvailable) return false

        val workDir = File(context.getExternalFilesDir(null), "iflytek/aikit")
        if (!workDir.exists()) workDir.mkdirs()

        copyAsrResources(workDir)
        val success = initSdkByReflection(workDir.absolutePath)
        if (success) {
            isInitialized = true
            Timber.i("$TAG: AIKit SDK initialized for ESR, workDir=${workDir.absolutePath}")
        }
        return success
    }

    private fun copyAsrResources(workDir: File) {
        try {
            val assetManager = context.assets
            val aikitAssets = assetManager.list("aikit_resources")
            if (aikitAssets.isNullOrEmpty()) {
                Timber.w("$TAG: No aikit_resources found in assets/")
                return
            }
            aikitAssets.forEach { fileName ->
                copyAssetRecursive("aikit_resources/$fileName", File(workDir, fileName), assetManager)
            }
            Timber.i("$TAG: Copied ${aikitAssets.size} resource items to ${workDir.absolutePath}")
        } catch (e: Exception) {
            Timber.w("$TAG: Error copying ASR resources: ${e.message}")
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
                targetFile.parentFile?.mkdirs()
                targetFile.outputStream().use { out ->
                    assetManager.open(assetPath).use { inp -> inp.copyTo(out) }
                }
            }
        } catch (e: Exception) {
            Timber.w("$TAG: Failed to copy asset $assetPath: ${e.message}")
        }
    }

    private fun initSdkByReflection(workDirPath: String): Boolean {
        return try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            aiHelper = aiHelperClass.getMethod("getInst").invoke(null)

            // ★ v3.3 修复：Params 是 BaseLibrary 的内部类，不是 AiHelper 的
            val paramsClass = Class.forName("com.iflytek.aikit.core.BaseLibrary\$Params")
            val builderClass = Class.forName("com.iflytek.aikit.core.BaseLibrary\$Params\$Builder")
            val builder = builderClass.getConstructor().newInstance()

            builderClass.getMethod("appId", String::class.java).invoke(builder, appId)
            builderClass.getMethod("apiKey", String::class.java).invoke(builder, apiKey)
            builderClass.getMethod("apiSecret", String::class.java).invoke(builder, apiSecret)
            builderClass.getMethod("workDir", String::class.java).invoke(builder, workDirPath)
            // ★ 注册唤醒+ESR双能力（主进程可能两者都用）
            // ★★★ v3.1 修复：主进程只注册ESR识别能力，不注册IVW
            // 双进程各自注册对方能力会导致AiHelper单例冲突
            builderClass.getMethod("ability", String::class.java).invoke(builder, ESR_ID)
            builderClass.getMethod("authInterval", Int::class.javaPrimitiveType).invoke(builder, AUTH_INTERVAL)
            builderClass.getMethod("iLogMaxCount", Int::class.javaPrimitiveType).invoke(builder, 1)

            val params = builderClass.getMethod("build").invoke(builder)
            aiHelperClass.getMethod("init", Context::class.java, paramsClass)
                .invoke(aiHelper, context, params)

            try {
                val logLvlClass = Class.forName(CLS_LOG_LVL)
                val errorLevel = logLvlClass.getField("ERROR").get(null)
                aiHelperClass.getMethod("setLogLevel", logLvlClass).invoke(aiHelper, errorLevel)
            } catch (e: Exception) { }

            registerAuthListener()
            registerAsrListener()

            Timber.i("$TAG: SDK init called (ability=$ESR_ID only), waiting for auth...")
            true
        } catch (e: Throwable) {
            Timber.e(e, "$TAG: Failed to init SDK")
            false
        }
    }

    private fun registerAuthListener() {
        try {
            val authListenerClass = Class.forName(CLS_AUTH_LISTENER)

            val listener = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(authListenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onAuthStateChange" -> {
                        val code = args?.getOrNull(1) as? Int ?: -1
                        Timber.i("$TAG: Auth state change - code=$code")
                        if (code == 0) {
                            isAuthComplete = true
                            isAuthFailed = false
                            Timber.i("$TAG: ★ Auth SUCCESS! ESR engine ready.")
                        } else {
                            isAuthFailed = true
                            isAuthComplete = false
                            Timber.e("$TAG: Auth FAILED with code=$code")
                        }
                    }
                }
                null
            }

            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            aiHelperClass.getMethod("registerListener", authListenerClass)
                .invoke(aiHelper, listener)
        } catch (e: Throwable) {
            Timber.e(e, "$TAG: Failed to register auth listener")
        }
    }

    private fun registerAsrListener() {
        try {
            val aiListenerClass = Class.forName(CLS_AI_LISTENER)

            asrListener = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(aiListenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onResult" -> {
                        // onResult(int handleID, List<AiResponse> outputData, Object usrContext)
                        val outputData = args?.getOrNull(1)
                        if (outputData != null) {
                            handleEsrResult(outputData)
                        }
                    }
                    "onEvent" -> {
                        val event = args?.getOrNull(1) as? Int ?: 0
                        Timber.d("$TAG: onEvent event=$event")
                    }
                    "onError" -> {
                        val err = args?.getOrNull(1) as? Int ?: -1
                        val msg = args?.getOrNull(2) as? String ?: "Unknown"
                        Timber.e("$TAG: ESR Error - err=$err msg=$msg")
                        isListening.set(false)
                        onError?.invoke(ERROR_SDK, "识别错误($err): $msg")
                    }
                }
                null
            }

            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            aiHelperClass.getMethod("registerListener", String::class.java, aiListenerClass)
                .invoke(aiHelper, ESR_ID, asrListener)
        } catch (e: Throwable) {
            Timber.e(e, "$TAG: Failed to register ESR listener")
        }
    }

    /**
     * 处理ESR识别结果
     * ESR返回 List<AiResponse>，每个AiResponse有:
     * - getKey(): "plain"(最终文本), "pgs"(中间结果), "vad"(端点检测), "htk"(分词), "readable"(JSON)
     * - getValue(): byte[] 结果数据 (GBK编码)
     * - getStatus(): 2=说话结束
     */
    private fun handleEsrResult(outputData: Any) {
        try {
            // outputData 可能是 List<AiResponse>
            val size = outputData.javaClass.getMethod("size").invoke(outputData) as? Int ?: 0
            var isEndOfSpeech = false

            for (i in 0 until size) {
                val response = outputData.javaClass.getMethod("get", Int::class.javaPrimitiveType)
                    .invoke(outputData, i) ?: continue

                val key = response.javaClass.getMethod("getKey").invoke(response) as? String ?: ""
                val valueBytes = response.javaClass.getMethod("getValue").invoke(response) as? ByteArray
                val status = response.javaClass.getMethod("getStatus").invoke(response) as? Int ?: 0

                if (valueBytes == null || valueBytes.isEmpty()) continue

                // ★ ESR结果使用GBK编码（官方Demo确认）
                val text = try {
                    String(valueBytes, charset("GBK"))
                } catch (e: Exception) {
                    String(valueBytes, Charsets.UTF_8)
                }

                Timber.d("$TAG: ESR result key=$key status=$status text=$text")

                when {
                    key.contains("plain") -> {
                        // ★ plain是每段话的最终识别结果
                        val isFinal = status == 2
                        if (text.isNotBlank()) {
                            Timber.i("$TAG: ★ ESR plain result: \"$text\" (isFinal=$isFinal)")
                            onResult?.invoke(text.trim(), isFinal)
                        }
                    }
                    key.contains("pgs") -> {
                        // 中间结果，可用于实时显示
                        if (text.isNotBlank()) {
                            Timber.d("$TAG: ESR pgs: \"$text\"")
                            onResult?.invoke(text.trim(), false)
                        }
                    }
                    key.contains("vad") -> {
                        // VAD端点检测结果
                        Timber.d("$TAG: ESR vad: $text")
                        if (text.contains("ed")) {
                            // 后端点 - 用户说完了
                            onBegin?.let { Timber.d("$TAG: VAD: speech begin detected") }
                        }
                    }
                }

                // ★ status=2 表示这一轮识别结束（VAD检测到后端点）
                if (status == 2) {
                    isEndOfSpeech = true
                }
            }

            if (isEndOfSpeech) {
                Timber.i("$TAG: ★ ESR speech END detected (status=2)")
                onEnd?.invoke()
                isListening.set(false)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error handling ESR result")
        }
    }

    fun startListening() {
        if (!isInitialized || aiHelper == null) {
            if (!initialize()) {
                onError?.invoke(ERROR_NOT_READY, "讯飞ESR引擎初始化失败")
                return
            }
        }
        if (!isAuthComplete) {
            if (isAuthFailed) {
                onError?.invoke(ERROR_NOT_READY, "讯飞ESR授权失败，请检查能力(e75f07b62)是否已开通")
                return
            }
            Thread.sleep(1000)
            if (!isAuthComplete) {
                onError?.invoke(ERROR_NOT_READY, "讯飞ESR授权中，请稍后重试")
                return
            }
        }
        if (isListening.get()) stopListening()

        try {
            Timber.i("$TAG: Starting ESR session...")
            val aiHelperClass = Class.forName(CLS_AI_HELPER)

            // ★ Step 1: engineInit - ESR专用参数
            if (!engineInited) {
                val engineBuilderClass = Class.forName("${CLS_AI_REQUEST}\$Builder")
                val engineBuilder = engineBuilderClass.getMethod("builder").invoke(null)
                engineBuilderClass.getMethod("param", String::class.java, Any::class.java)
                    .invoke(engineBuilder, "decNetType", "fsa")
                engineBuilderClass.getMethod("param", String::class.java, Any::class.java)
                    .invoke(engineBuilder, "punishCoefficient", 0.0)
                engineBuilderClass.getMethod("param", String::class.java, Any::class.java)
                    .invoke(engineBuilder, "wfst_addType", 0)  // 0=中文

                val engineRequest = engineBuilderClass.getMethod("build").invoke(engineBuilder)
                val engineInitRet = aiHelperClass.getMethod("engineInit", String::class.java, Class.forName(CLS_AI_REQUEST))
                    .invoke(aiHelper, ESR_ID, engineRequest) as? Int ?: -1
                if (engineInitRet != 0 && engineInitRet != 18205) {
                    Timber.e("$TAG: ESR engineInit failed: $engineInitRet")
                    onError?.invoke(ERROR_SDK, "ESR引擎初始化失败($engineInitRet)")
                    return
                }
                engineInited = true
                Timber.i("$TAG: ★ ESR engineInit success (decNetType=fsa, language=CN)")
            }

            // ★ Step 2: start - ESR识别参数（基于官方Demo推荐值）
            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("${CLS_AI_REQUEST}\$Builder")
            val builder = builderClass.getMethod("builder").invoke(null)

            // ESR识别参数（官方Demo推荐值）
            builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "languageType", 0)    // 0=中文
            builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "vadEndGap", 60)       // 子句分割间隔(ms), 中文建议60
            builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "vadOn", true)         // VAD开关
            builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "beamThreshold", 20)   // 解码beam阈值, 中文建议20
            builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "hisGramThreshold", 3000) // 解码Gram阈值
            builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "vadLinkOn", false)    // VAD子句连接
            builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "vadSpeechEnd", 80)    // VAD后端点
            builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "vadResponsetime", 1000) // VAD前端点
            builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "postprocOn", false)   // 后处理开关
            builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "vadEnergyThreshold", 9) // VAD前端点阈值
            builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "vadThreshold", 0.1332) // VAD阈值

            val request = builderClass.getMethod("build").invoke(builder)
            aiHandle = aiHelperClass.getMethod("start", String::class.java, aiRequestClass, Any::class.java)
                .invoke(aiHelper, ESR_ID, request, null)

            val isSuccess = aiHandle?.javaClass?.getMethod("isSuccess")?.invoke(aiHandle) as? Boolean ?: false
            if (!isSuccess) {
                val code = aiHandle?.javaClass?.getMethod("getCode")?.invoke(aiHandle) as? Int ?: -1
                Timber.e("$TAG: ESR start failed: code=$code")
                onError?.invoke(ERROR_SDK, "ESR会话启动失败($code)")
                return
            }

            isListening.set(true)
            Timber.i("$TAG: ★ ESR session STARTED!")
            startAudioRecord()
            onReady?.invoke()
        } catch (e: Throwable) {
            Timber.e(e, "$TAG: Failed to start ESR")
            isListening.set(false)
            onError?.invoke(ERROR_SDK, "ESR启动失败: ${e.message}")
        }
    }

    private fun startAudioRecord() {
        try {
            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = maxOf(minBufferSize, FRAME_SIZE * 4)

            @Suppress("MissingPermission")
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufferSize
            )
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                onError?.invoke(ERROR_AUDIO, "录音初始化失败")
                return
            }
            audioRecord?.startRecording()
            isRecording.set(true)

            recordThread = Thread({
                val buffer = ByteArray(FRAME_SIZE)
                var frameIndex = 0
                while (isRecording.get() && isListening.get()) {
                    val read = audioRecord?.read(buffer, 0, FRAME_SIZE) ?: -1
                    if (read > 0) {
                        feedAudioToSdk(buffer, frameIndex)
                        frameIndex++
                    } else if (read < 0) {
                        Thread.sleep(10)
                    }
                }
            }, "$TAG-recorder").apply { isDaemon = true; priority = Thread.MAX_PRIORITY; start() }
        } catch (e: Throwable) {
            Timber.e(e, "$TAG: Failed to start audio recording")
            onError?.invoke(ERROR_AUDIO, "录音启动失败: ${e.message}")
        }
    }

    /**
     * ★ ESR音频写入：使用"audio"作为key（不是"wav"），每帧写完后调read()
     */
    private fun feedAudioToSdk(audioData: ByteArray, frameIndex: Int) {
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            val aiAudioClass = Class.forName(CLS_AI_AUDIO)
            val aiStatusClass = Class.forName(CLS_AI_STATUS)
            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("${CLS_AI_REQUEST}\$Builder")

            val dataBuilder = builderClass.getMethod("builder").invoke(null)

            // ★ ESR使用"audio"作为音频key（官方Demo确认）
            val holder = aiAudioClass.getMethod("get", String::class.java).invoke(null, "audio")
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

            // ★ write后必须调read()（ESR API要求）
            val writeRet = aiHelperClass.getMethod("write", aiRequestClass, Any::class.java)
                .invoke(aiHelper, writeRequest, aiHandle) as? Int ?: -1

            if (writeRet == 0) {
                val readRet = aiHelperClass.getMethod("read", String::class.java, Any::class.java)
                    .invoke(aiHelper, ESR_ID, aiHandle) as? Int ?: -1
                if (readRet != 0 && frameIndex % 100 == 0) {
                    Timber.w("$TAG: read returned $readRet at frame $frameIndex")
                }
            } else if (frameIndex % 100 == 0) {
                Timber.w("$TAG: write returned $writeRet at frame $frameIndex")
            }
        } catch (e: Exception) {
            if (frameIndex % 100 == 0) Timber.w("$TAG: Feed error at frame $frameIndex: ${e.message}")
        }
    }

    private fun sendEndFrame() {
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            val aiAudioClass = Class.forName(CLS_AI_AUDIO)
            val aiStatusClass = Class.forName(CLS_AI_STATUS)
            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("${CLS_AI_REQUEST}\$Builder")

            val dataBuilder = builderClass.getMethod("builder").invoke(null)
            val holder = aiAudioClass.getMethod("get", String::class.java).invoke(null, "audio")
            aiAudioClass.getMethod("data", ByteArray::class.java).invoke(holder, ByteArray(0))
            aiAudioClass.getMethod("status", Any::class.java).invoke(holder, aiStatusClass.getField("END").get(null))
            val validPayload = aiAudioClass.getMethod("valid").invoke(holder)
            aiRequestClass.getMethod("payload", Any::class.java).invoke(dataBuilder, validPayload)
            val writeRequest = builderClass.getMethod("build").invoke(dataBuilder)

            aiHelperClass.getMethod("write", aiRequestClass, Any::class.java).invoke(aiHelper, writeRequest, aiHandle)
            aiHelperClass.getMethod("read", String::class.java, Any::class.java).invoke(aiHelper, ESR_ID, aiHandle)
        } catch (e: Exception) {
            Timber.w("$TAG: Error sending END frame: ${e.message}")
        }
    }

    fun stopListening() {
        isListening.set(false)
        isRecording.set(false)
        try { audioRecord?.stop() } catch (e: Exception) { }
        recordThread?.interrupt()
        recordThread = null
        try {
            sendEndFrame()
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            if (aiHandle != null) {
                val ret = aiHelperClass.getMethod("end", Any::class.java).invoke(aiHelper, aiHandle) as? Int ?: -1
                if (ret != 0 && ret != 18307) Timber.w("$TAG: End returned $ret")
            }
        } catch (e: Exception) {
            Timber.w("$TAG: Error ending session: ${e.message}")
        }
        aiHandle = null
        try { audioRecord?.release() } catch (e: Exception) { }
        audioRecord = null
        Timber.i("$TAG: ESR stopped")
    }

    fun cancel() {
        isListening.set(false)
        isRecording.set(false)
        try { audioRecord?.stop(); audioRecord?.release() } catch (e: Exception) { }
        audioRecord = null
        recordThread?.interrupt()
        recordThread = null
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            if (aiHandle != null) aiHelperClass.getMethod("end", Any::class.java).invoke(aiHelper, aiHandle)
        } catch (e: Exception) { }
        aiHandle = null
    }

    fun release() {
        cancel()
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            if (engineInited) {
                aiHelperClass.getMethod("engineUnInit", String::class.java).invoke(aiHelper, ESR_ID)
                engineInited = false
            }
            aiHelperClass.getMethod("unInit").invoke(aiHelper)
        } catch (e: Exception) { }
        isInitialized = false
        isAuthComplete = false
    }

    fun isListening(): Boolean = isListening.get()
    fun isAuthComplete(): Boolean = isAuthComplete
    fun isInitialized(): Boolean = isInitialized
}
