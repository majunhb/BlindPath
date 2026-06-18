package com.blindpath.module_voice.service

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 讯飞 AIKit 离线语音识别引擎 (ASR)
 *
 * 能力ID: ee62fa27c (中英听写, 离线)
 *
 * 核心优势（相比百度ASR）：
 * - 完全离线，不依赖网络 —— 盲人户外弱网/无网场景可靠
 * - VAD自动端点检测 —— 自动检测说话开始和结束，无需手动stop
 * - 与唤醒共用同一SDK生态 —— 减少依赖冲突
 *
 * 核心流程：
 * 1. initialize() → 初始化SDK（启动异步授权）
 * 2. AuthListener回调成功 → 引擎就绪
 * 3. startListening() → engineInit → start → 启动AudioRecord → 持续feed音频
 * 4. AiListener.onResult → 解析"plain"结果JSON → 提取识别文本
 * 5. AiListener.onEvent(event=2, END) → VAD检测到说话结束 → 自动停止
 * 6. stopListening() → 停止AudioRecord → end会话
 */
class XfAsrEngine(
    private val context: Context,
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String
) {
    companion object {
        private const val TAG = "XfAsrEngine"
        private const val ASR_ID = "ee62fa27c"
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
        private const val FRAME_SIZE = 1280

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
    private var coreListener: Any? = null
    private var asrListener: Any? = null

    private var audioRecord: AudioRecord? = null
    private var recordThread: Thread? = null
    private val isRecording = AtomicBoolean(false)
    private val isListening = AtomicBoolean(false)

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
            Timber.i("$TAG: AIKit SDK initialized for ASR, workDir=\${workDir.absolutePath}")
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
                copyAssetRecursive("aikit_resources/\$fileName", File(workDir, fileName), assetManager)
            }
            Timber.i("$TAG: Copied \${aikitAssets.size} resource items to \${workDir.absolutePath}")
        } catch (e: Exception) {
            Timber.w("$TAG: Error copying ASR resources: \${e.message}")
        }
    }

    private fun copyAssetRecursive(assetPath: String, targetFile: File, assetManager: android.content.res.AssetManager) {
        try {
            val children = try { assetManager.list(assetPath) } catch (e: Exception) { null }
            if (children != null && children.isNotEmpty()) {
                targetFile.mkdirs()
                children.forEach { child ->
                    copyAssetRecursive("\$assetPath/\$child", File(targetFile, child), assetManager)
                }
            } else {
                targetFile.parentFile?.mkdirs()
                targetFile.outputStream().use { out ->
                    assetManager.open(assetPath).use { inp -> inp.copyTo(out) }
                }
            }
        } catch (e: Exception) {
            Timber.w("$TAG: Failed to copy asset \$assetPath: \${e.message}")
        }
    }

    private fun initSdkByReflection(workDirPath: String): Boolean {
        return try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            aiHelper = aiHelperClass.getMethod("getInst").invoke(null)

            val paramsClass = Class.forName("\${CLS_AI_HELPER}\$Params")
            val builderClass = Class.forName("\${CLS_AI_HELPER}\$Params\$Builder")
            val builder = builderClass.getConstructor().newInstance()

            builderClass.getMethod("appId", String::class.java).invoke(builder, appId)
            builderClass.getMethod("apiKey", String::class.java).invoke(builder, apiKey)
            builderClass.getMethod("apiSecret", String::class.java).invoke(builder, apiSecret)
            builderClass.getMethod("workDir", String::class.java).invoke(builder, workDirPath)
            builderClass.getMethod("ability", String::class.java).invoke(builder, ASR_ID)
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

            Timber.i("$TAG: SDK init called, waiting for auth...")
            true
        } catch (e: Throwable) {
            Timber.e(e, "$TAG: Failed to init SDK")
            false
        }
    }

    private fun registerAuthListener() {
        try {
            val authListenerClass = Class.forName(CLS_AUTH_LISTENER)
            val errTypeClass = Class.forName(CLS_ERR_TYPE)

            coreListener = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(authListenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onAuthStateChange" -> {
                        val type = args?.getOrNull(0)
                        val code = args?.getOrNull(1) as? Int ?: -1

                        Timber.i("$TAG: Auth state change - code=\$code")

                        if (code == 0) {
                            isAuthComplete = true
                            isAuthFailed = false
                            Timber.i("$TAG: Auth SUCCESS! ASR ready.")
                            onReady?.invoke()
                        } else {
                            isAuthFailed = true
                            Timber.e("$TAG: Auth FAILED - code=\$code")
                            onError?.invoke(ERROR_SDK, "讯飞ASR授权失败(code=\$code)")
                        }
                    }
                }
                null
            }

            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            aiHelperClass.getMethod("registerListener", authListenerClass)
                .invoke(aiHelper, coreListener)
        } catch (e: Throwable) {
            Timber.e(e, "$TAG: Failed to register auth listener")
        }
    }

    private fun registerAsrListener() {
        try {
            val aiListenerClass = Class.forName(CLS_AI_LISTENER)
            val aiResponseClass = Class.forName(CLS_AI_RESPONSE)

            asrListener = Proxy.newProxyInstance(
                context.classLoader,
                arrayOf(aiListenerClass)
            ) { _, method, args ->
                when (method.name) {
                    "onResult" -> {
                        val outputData = args?.getOrNull(1) as? List<*>
                        if (outputData != null && outputData.isNotEmpty()) {
                            for (item in outputData) {
                                try {
                                    val key = aiResponseClass.getMethod("getKey").invoke(item) as? String
                                    val value = aiResponseClass.getMethod("getValue").invoke(item) as? ByteArray
                                    if (key == "plain" && value != null) {
                                        val json = String(value, Charsets.UTF_8)
                                        val text = parsePlainResult(json)
                                        if (text.isNotBlank()) {
                                            Timber.i("$TAG: ASR Result: \"\$text\"")
                                            onResult?.invoke(text, true)
                                        }
                                    } else if (key == "pgs" && value != null) {
                                        val json = String(value, Charsets.UTF_8)
                                        val text = parsePlainResult(json)
                                        if (text.isNotBlank()) {
                                            onResult?.invoke(text, false)
                                        }
                                    }
                                } catch (e: Exception) {
                                    Timber.w("$TAG: Error parsing result: \${e.message}")
                                }
                            }
                        }
                    }
                    "onEvent" -> {
                        val event = args?.getOrNull(1) as? Int ?: 0
                        when (event) {
                            1 -> { Timber.i("$TAG: VAD speech BEGIN"); onBegin?.invoke() }
                            2 -> { Timber.i("$TAG: VAD speech END"); onEnd?.invoke(); isListening.set(false) }
                            3 -> { Timber.w("$TAG: Timeout"); isListening.set(false) }
                            else -> {}
                        }
                    }
                    "onError" -> {
                        val err = args?.getOrNull(1) as? Int ?: -1
                        val msg = args?.getOrNull(2) as? String ?: "Unknown"
                        Timber.e("$TAG: ASR Error - err=\$err msg=\$msg")
                        isListening.set(false)
                        onError?.invoke(ERROR_SDK, "ASR错误(\$err): \$msg")
                    }
                }
                null
            }

            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            aiHelperClass.getMethod("registerListener", String::class.java, aiListenerClass)
                .invoke(aiHelper, ASR_ID, asrListener)
        } catch (e: Throwable) {
            Timber.e(e, "$TAG: Failed to register ASR listener")
        }
    }

    private fun parsePlainResult(json: String): String {
        return try {
            val root = JSONObject(json)
            val wsArray = root.optJSONArray("ws")
            if (wsArray != null && wsArray.length() > 0) {
                val sb = StringBuilder()
                for (i in 0 until wsArray.length()) {
                    sb.append(wsArray.getJSONObject(i).optString("w", ""))
                }
                sb.toString()
            } else ""
        } catch (e: Exception) {
            Timber.w("$TAG: Parse error: \${e.message}")
            ""
        }
    }

    fun startListening() {
        if (!isInitialized || aiHelper == null) {
            if (!initialize()) {
                onError?.invoke(ERROR_NOT_READY, "讯飞ASR引擎初始化失败")
                return
            }
        }
        if (!isAuthComplete) {
            if (isAuthFailed) {
                onError?.invoke(ERROR_NOT_READY, "讯飞ASR授权失败")
                return
            }
            Thread.sleep(1000)
            if (!isAuthComplete) {
                onError?.invoke(ERROR_NOT_READY, "讯飞ASR授权中，请稍后重试")
                return
            }
        }
        if (isListening.get()) stopListening()

        try {
            Timber.i("$TAG: Starting ASR session...")
            val aiHelperClass = Class.forName(CLS_AI_HELPER)

            val engineInitRet = aiHelperClass.getMethod("engineInit", String::class.java)
                .invoke(aiHelper, ASR_ID) as? Int ?: -1
            if (engineInitRet != 0 && engineInitRet != 18205) {
                Timber.e("$TAG: Engine init failed: \$engineInitRet")
                onError?.invoke(ERROR_SDK, "ASR引擎初始化失败(\$engineInitRet)")
                return
            }

            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("\${CLS_AI_REQUEST}\$Builder")
            val builder = builderClass.getMethod("builder").invoke(null)

            try {
                builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "vadLoad", "1")
                builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "vadOn", "1")
                builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "vadResponsetime", "300")
                builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "vadSpeechEnd", "80")
                builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "lmLoad", "1")
                builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "lmOn", "1")
                builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "puncLoad", "1")
                builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "numLoad", "1")
                builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "postprocOn", "1")
                builderClass.getMethod("param", String::class.java, Any::class.java).invoke(builder, "dialectType", "0")
            } catch (e: Exception) {
                Timber.w("$TAG: Failed to set ASR params: \${e.message}")
            }

            val request = builderClass.getMethod("build").invoke(builder)
            aiHandle = aiHelperClass.getMethod("start", String::class.java, aiRequestClass, Any::class.java)
                .invoke(aiHelper, ASR_ID, request, null)

            val isSuccess = aiHandle?.javaClass?.getMethod("isSuccess")?.invoke(aiHandle) as? Boolean ?: false
            if (!isSuccess) {
                val code = aiHandle?.javaClass?.getMethod("getCode")?.invoke(aiHandle) as? Int ?: -1
                Timber.e("$TAG: Start failed: code=\$code")
                onError?.invoke(ERROR_SDK, "ASR会话启动失败(\$code)")
                return
            }

            isListening.set(true)
            Timber.i("$TAG: ASR session STARTED!")
            startAudioRecord()
            onReady?.invoke()
        } catch (e: Throwable) {
            Timber.e(e, "$TAG: Failed to start ASR")
            isListening.set(false)
            onError?.invoke(ERROR_SDK, "ASR启动失败: \${e.message}")
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
            onError?.invoke(ERROR_AUDIO, "录音启动失败: \${e.message}")
        }
    }

    private fun feedAudioToSdk(audioData: ByteArray, frameIndex: Int) {
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            val aiAudioClass = Class.forName(CLS_AI_AUDIO)
            val aiStatusClass = Class.forName(CLS_AI_STATUS)
            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("\${CLS_AI_REQUEST}\$Builder")

            val dataBuilder = builderClass.getMethod("builder").invoke(null)
            val holder = aiAudioClass.getMethod("get", String::class.java).invoke(null, "wav")
            val encodingConst = aiAudioClass.getDeclaredField("ENCODING_DEFAULT").let { it.isAccessible = true; it.get(null) }
            aiAudioClass.getMethod("encoding", Any::class.java).invoke(holder, encodingConst)
            aiAudioClass.getMethod("data", ByteArray::class.java).invoke(holder, audioData)
            val status = if (frameIndex == 0) aiStatusClass.getField("BEGIN").get(null) else aiStatusClass.getField("CONTINUE").get(null)
            aiAudioClass.getMethod("status", Any::class.java).invoke(holder, status)
            val validPayload = aiAudioClass.getMethod("valid").invoke(holder)
            aiRequestClass.getMethod("payload", Any::class.java).invoke(dataBuilder, validPayload)
            val writeRequest = builderClass.getMethod("build").invoke(dataBuilder)
            aiHelperClass.getMethod("write", aiRequestClass, Any::class.java).invoke(aiHelper, writeRequest, aiHandle)
        } catch (e: Exception) {
            if (frameIndex % 100 == 0) Timber.w("$TAG: Feed error at frame \$frameIndex: \${e.message}")
        }
    }

    private fun sendEndFrame() {
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            val aiAudioClass = Class.forName(CLS_AI_AUDIO)
            val aiStatusClass = Class.forName(CLS_AI_STATUS)
            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("\${CLS_AI_REQUEST}\$Builder")
            val dataBuilder = builderClass.getMethod("builder").invoke(null)
            val holder = aiAudioClass.getMethod("get", String::class.java).invoke(null, "wav")
            val encodingConst = aiAudioClass.getDeclaredField("ENCODING_DEFAULT").let { it.isAccessible = true; it.get(null) }
            aiAudioClass.getMethod("encoding", Any::class.java).invoke(holder, encodingConst)
            aiAudioClass.getMethod("data", ByteArray::class.java).invoke(holder, ByteArray(0))
            aiAudioClass.getMethod("status", Any::class.java).invoke(holder, aiStatusClass.getField("END").get(null))
            val validPayload = aiAudioClass.getMethod("valid").invoke(holder)
            aiRequestClass.getMethod("payload", Any::class.java).invoke(dataBuilder, validPayload)
            val writeRequest = builderClass.getMethod("build").invoke(dataBuilder)
            aiHelperClass.getMethod("write", aiRequestClass, Any::class.java).invoke(aiHelper, writeRequest, aiHandle)
        } catch (e: Exception) {
            Timber.w("$TAG: Error sending END frame: \${e.message}")
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
                if (ret != 0 && ret != 18307) Timber.w("$TAG: End returned \$ret")
            }
        } catch (e: Exception) {
            Timber.w("$TAG: Error ending session: \${e.message}")
        }
        aiHandle = null
        try { audioRecord?.release() } catch (e: Exception) { }
        audioRecord = null
        Timber.i("$TAG: ASR stopped")
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
            aiHelperClass.getMethod("unInit").invoke(aiHelper)
        } catch (e: Exception) { }
        isInitialized = false
        isAuthComplete = false
    }

    fun isListening(): Boolean = isListening.get()
    fun isAuthComplete(): Boolean = isAuthComplete
    fun isInitialized(): Boolean = isInitialized
}
