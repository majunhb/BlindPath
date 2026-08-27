package com.blindpath.module_voice.service

import android.content.Context
import com.blindpath.module_voice.domain.WakeWordDetector
import com.blindpath.module_voice.domain.model.WakeWordConfig
import org.json.JSONObject
import timber.log.Timber
import java.io.File
import java.lang.reflect.Proxy
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * 科大讯飞 AIKit 语音唤醒检测器（反射实现）
 *
 * 基于讯飞 AIKit SDK 官方文档实现。能力ID: e867a88f2 (语音唤醒)
 *
 * ★★★ v4.0 唤醒可靠性专项优化（2026-08-25）：
 * D2  新增 EngineState 状态机，startListening 前强制 end 旧会话，避免 18310 会话重复开启
 * D3  放宽授权成功判定：除 AUTH+code=0 外，AUTH_TIPS/SUCCESS 等非 FAIL 状态也视为授权完成
 *       并通过 authLatch 让调用方可阻塞等待授权结果
 * D4  使用 ReentrantLock 串行化 startListening，启动前先 interrupt 旧 writeThread
 *       防止"多线程抢写同一 handle"
 * D9  音频写入线程启动后再置 isListening=true，feedAudioData 按 isListening 正确丢弃早期帧
 * A+  新增诊断统计：累计送入帧数、写入错误、会话启动失败次数，供诊断接口读取
 * A+  授权失败时通过 authFailedReason / lastAuthCode 保留失败原因，不再只回调 onError
 */
class XfWakeWordDetector(
    private val context: Context,
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String,
    private val threshold: Int = WakeWordConfig.XF_WAKE_THRESHOLD,
    private val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD,
    private val onWakeWordDetected: (String) -> Unit,
    /** 授权成功回调（别名，桥接到 onAuthSuccess） */
    private val onAuthSuccessCb: (() -> Unit)? = null,
    /** 授权失败回调（别名，桥接到 onAuthFailed） */
    private val onAuthFailedCb: (() -> Unit)? = null
) : WakeWordDetector {

    companion object {
        private const val TAG = "XfWakeWordDetector"
        private const val IVW_ID = "e867a88f2"
        private const val AUTH_INTERVAL = 333
        private const val AUTH_WAIT_SECONDS = 45L // 允许 authLatch 最长等待

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

        private const val MAX_QUEUE_SIZE = 10

        @Volatile
        private var sdkAvailable: Boolean? = null

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

    // ★ 引擎状态机（v4.0 新增，防会话重复/泄漏）
    enum class EngineState {
        IDLE,           // 已初始化 SDK，授权未完成，无会话
        AUTH_PENDING,   // 授权进行中
        AUTH_DONE,      // 授权完成（可以启动会话）
        SESSION_INIT,   // engineInit 成功
        SESSION_LOADED, // loadData / specifyDataSet 成功
        LISTENING,      // start 成功 + 写线程运行
        AUTH_FAILED,    // 授权已失败
        RELEASED        // 已释放
    }

    // ★ 串行化所有会修改会话的操作（D2/D4 修复）
    private val sessionLock = ReentrantLock()

    @Volatile private var isInitialized = false
    @Volatile private var engineState = EngineState.IDLE
    private val isListeningFlag = AtomicBoolean(false)
    private var aiHelper: Any? = null
    private var aiHandle: Any? = null
    private var callback: WakeWordDetector.Callback? = null

    // v3.2: ConcurrentLinkedQueue 替代 AtomicReference，避免丢帧
    private val audioQueue = ConcurrentLinkedQueue<ByteArray>()
    @Volatile private var writeThread: Thread? = null

    // ★ v4.0: 授权锁（D3 修复，调用方也能感知）
    private val authLatch = CountDownLatch(1)
    @Volatile private var isAuthComplete = false
    @Volatile private var isAuthFailed = false
    @Volatile private var lastAuthCode = -1
    @Volatile private var authFailedReason: String? = null

    var onAuthSuccess: (() -> Unit)? = null
    var onAuthFailed: (() -> Unit)? = null

    private var workDirPath: String = ""

    // ★ v4.0 诊断统计
    @Volatile var statsFeedFrames: Long = 0L; private set
    @Volatile var statsWriteErrors: Long = 0L; private set
    @Volatile var statsStartFailures: Long = 0L; private set
    @Volatile var statsWakeCount: Long = 0L; private set
    @Volatile var statsLastWakeMs: Long = 0L; private set

    init {
        try {
            initialize()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to initialize")
        }
    }

    // ──────────────────────────────────────────────
    // 初始化 + SDK 反射
    // ──────────────────────────────────────────────

    private fun initialize() {
        if (appId.isBlank() || apiKey.isBlank() || apiSecret.isBlank()) {
            throw IllegalArgumentException("iFlytek credentials cannot be empty")
        }

        if (sdkAvailable == null) {
            sdkAvailable = try {
                Class.forName(CLS_AI_HELPER)
                Class.forName("com.iflytek.aikit.core.BaseLibrary\$Params")
                Class.forName("com.iflytek.aikit.core.BaseLibrary\$Params\$Builder")
                true
            } catch (e: ClassNotFoundException) {
                Timber.w("$TAG: AIKit SDK not found (missing: ${e.message})")
                false
            }
        }

        if (sdkAvailable != true) {
            engineState = EngineState.RELEASED
            throw IllegalStateException("iFlytek AIKit SDK not available")
        }

        val workDir = File(context.getExternalFilesDir(null), "iflytek/aikit")
        if (!workDir.exists()) {
            workDir.mkdirs()
        }
        workDirPath = workDir.absolutePath

        copyResourceFiles(workDir)
        initSdkByReflection(workDirPath)
        isInitialized = true
        engineState = EngineState.AUTH_PENDING
        Timber.i("$TAG: AIKit SDK initialized, workDir=$workDirPath, waiting for auth (latch)...")
    }

    private fun copyResourceFiles(workDir: File) {
        try {
            val assetManager = context.assets
            val aikitAssets = assetManager.list("aikit_resources")
            if (!aikitAssets.isNullOrEmpty()) {
                aikitAssets.forEach { fileName ->
                    copyAssetRecursive("aikit_resources/$fileName", File(workDir, fileName), assetManager)
                }
                Timber.i("$TAG: Copied ${aikitAssets.size} items from aikit_resources/ to ${workDir.absolutePath}")
                return
            }
            val ivwAssets = assetManager.list("iflytek/ivw")
            if (!ivwAssets.isNullOrEmpty()) {
                ivwAssets.forEach { fileName ->
                    copyAssetRecursive("iflytek/ivw/$fileName", File(workDir, fileName), assetManager)
                }
                Timber.i("$TAG: Copied ${ivwAssets.size} items from iflytek/ivw/ to ${workDir.absolutePath}")
                return
            }
            Timber.w("$TAG: No resource files found in assets/ (this may cause 18100 loadData failures)")
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

    /**
     * v4.0 优化：
     * - D3 修复：authStateChange 的成功判定放宽到包含 AUTH_TIPS/SUCCESS/SMS_SUCCESS/KXV_SUCCESS
     *   等非 FAIL 状态（这些状态讯飞也视为授权 OK）；失败时记录 authFailedReason
     * - 任何 code != 0 都打印完整错误映射，便于诊断
     */
    private fun initSdkByReflection(workDirPath: String) {
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            aiHelper = aiHelperClass.getMethod("getInst").invoke(null)

            val paramsClass = Class.forName("com.iflytek.aikit.core.BaseLibrary\$Params")
            val builderClass = Class.forName("com.iflytek.aikit.core.BaseLibrary\$Params\$Builder")
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
            val debugLevel = logLvlClass.getField("DEBUG").get(null)
            aiHelperClass.getMethod("setLogLevel", logLvlClass).invoke(aiHelper, debugLevel)

            // ── AuthListener ──────────────────────────
            val authListenerClass = Class.forName(CLS_AUTH_LISTENER)
            val authListener = Proxy.newProxyInstance(
                authListenerClass.classLoader,
                arrayOf(authListenerClass)
            ) { _, method, args ->
                if (method.name == "onAuthStateChange") {
                    val type = args?.get(0)
                    val code = args?.get(1) as? Int ?: -1
                    lastAuthCode = code
                    val typeName = type?.toString() ?: ""
                    Timber.i("$TAG: Auth state change: type=$typeName, code=$code (${getErrorMessage(code)})")

                    // D3 修复：放宽授权成功判定
                    // 讯飞授权回调常见 type: AUTH / AUTH_TIPS / AUTH_SUCCESS / KXV_SUCCESS / SMS_SUCCESS
                    // FAIL / AUTH_FAIL 才是真失败
                    val typeUpper = typeName.uppercase()
                    val isFail = (typeUpper.contains("FAIL")) || (code != 0 && code in listOf(18000, 18001, 18002, 18003, 18004, 18005, 18700, 18714))
                    val isSuccess = code == 0 || (!isFail && (
                            typeUpper.contains("AUTH") ||
                            typeUpper.contains("SUCCESS") ||
                            typeUpper.contains("TIPS")   // AUTH_TIPS 也是授权 OK 的提示
                    ))

                    when {
                        isSuccess && !isAuthComplete -> {
                            Timber.i("$TAG: ★★★ SDK authorized (type=$typeName, code=$code). Starting engine...")
                            isAuthComplete = true
                            isAuthFailed = false
                            authFailedReason = null
                            engineState = EngineState.AUTH_DONE
                            authLatch.countDown()
                            onAuthSuccess?.invoke()
                            onAuthSuccessCb?.invoke()
                            // v4.0: 用异步线程 startListening，且由 sessionLock 保护（D2/D4）
                            Thread({
                                try {
                                    Thread.sleep(150)
                                    startListening()
                                } catch (e: Exception) {
                                    Timber.e(e, "$TAG: Auto-start after auth failed")
                                }
                            }, "$TAG-auth-autoStart").apply { isDaemon = true }.start()
                        }
                        isFail && !isAuthFailed -> {
                            val errMsg = getErrorMessage(code)
                            authFailedReason = errMsg
                            Timber.e("$TAG: ✗ SDK auth FAILED: type=$typeName code=$code msg=$errMsg")
                            isAuthFailed = true
                            isAuthComplete = false
                            engineState = EngineState.AUTH_FAILED
                            authLatch.countDown()
                            callback?.onError(WakeWordDetector.ERROR_UNKNOWN, "认证失败: $errMsg (code=$code)")
                            onAuthFailed?.invoke()
                            onAuthFailedCb?.invoke()
                        }
                        else -> {
                            Timber.d("$TAG: Auth intermediate event ignored: type=$typeName code=$code")
                        }
                    }
                }
                null
            }
            aiHelperClass.getMethod("registerListener", authListenerClass)
                .invoke(aiHelper, authListener)

            // ── AiListener ──────────────────────────
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
                        statsStartFailures++
                        callback?.onError(WakeWordDetector.ERROR_UNKNOWN, "$errMsg (code=$err)")
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
                val bTime = result.optInt("beginTime", 0)
                val eTime = result.optInt("endTime", 0)
                statsWakeCount++
                statsLastWakeMs = System.currentTimeMillis()
                Timber.i("$TAG: ★★★ Wake word: '$keyword' score=$score time=[$bTime,$eTime] (total=$statsWakeCount)")
                onWakeWordDetected.invoke(wakeWord)
                callback?.onWakeWordDetected(wakeWord, 0.9f)
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to parse wake result, triggering anyway")
            statsWakeCount++
            onWakeWordDetected.invoke(wakeWord)
            callback?.onWakeWordDetected(wakeWord, 0.9f)
        }
    }

    // ──────────────────────────────────────────────
    // 会话管理：start → write → end
    // ──────────────────────────────────────────────

    override fun start(): Boolean {
        startListening()
        return true
    }

    /**
     * ★★★ v4.0: sessionLock 串行化，防会话重复 + 线程泄漏
     * 调用链：end旧会话 → engineInit → loadData → specifyDataSet → start → 写线程
     */
    fun startListening() {
        sessionLock.withLock {
            if (engineState == EngineState.RELEASED) {
                Timber.w("$TAG: startListening: already released")
                return
            }
            if (engineState == EngineState.AUTH_FAILED) {
                Timber.e("$TAG: startListening: auth already failed (reason=$authFailedReason)")
                return
            }
            if (!isAuthComplete) {
                if (engineState == EngineState.AUTH_PENDING) {
                    Timber.d("$TAG: startListening: auth still pending, waiting on latch...")
                    val got = try {
                        authLatch.await(AUTH_WAIT_SECONDS, TimeUnit.SECONDS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        false
                    }
                    if (!got || !isAuthComplete) {
                        Timber.w("$TAG: startListening: auth wait timed out, aborting start")
                        return
                    }
                } else {
                    Timber.w("$TAG: startListening: auth not complete yet (state=$engineState)")
                    return
                }
            }

            // D2 修复：先 end 旧会话，保证没有残留
            if (engineState >= EngineState.SESSION_INIT && engineState <= EngineState.LISTENING) {
                Timber.i("$TAG: startListening: closing previous session first (state=$engineState)")
                endSessionInternal("startListening-force-close")
            }

            if (isListeningFlag.get()) {
                Timber.d("$TAG: Already listening, short-circuit")
                return
            }

            try {
                Timber.i("$TAG: ★ Starting wake-up engine...")
                val aiHelperClass = Class.forName(CLS_AI_HELPER)

                // 1. engineInit
                val engineInitRet = aiHelperClass.getMethod("engineInit", String::class.java)
                    .invoke(aiHelper, IVW_ID) as? Int ?: -1
                if (engineInitRet != 0) {
                    statsStartFailures++
                    val msg = getErrorMessage(engineInitRet)
                    Timber.e("$TAG: Engine init failed: $msg (code=$engineInitRet)")
                    callback?.onError(WakeWordDetector.ERROR_MODEL_LOAD, "$msg (code=$engineInitRet)")
                    return
                }
                engineState = EngineState.SESSION_INIT
                Timber.i("$TAG: ★ Engine initialized (SESSION_INIT)")

                // 2. loadData + preProcess
                loadWakeUpWords()
                engineState = EngineState.SESSION_LOADED

                // 3. specifyDataSet
                val specifyRet = aiHelperClass.getMethod(
                    "specifyDataSet", String::class.java, String::class.java, IntArray::class.java
                ).invoke(aiHelper, IVW_ID, "key_word", intArrayOf(0)) as? Int ?: -1
                if (specifyRet != 0) {
                    Timber.w("$TAG: specifyDataSet failed: ${getErrorMessage(specifyRet)} (code=$specifyRet) — continuing")
                }

                // 4. start
                val aiRequestClass = Class.forName(CLS_AI_REQUEST)
                val builderClass = Class.forName("$CLS_AI_REQUEST\$Builder")
                val builder = aiRequestClass.getMethod("builder").invoke(null)
                try {
                    builderClass.getMethod("param", String::class.java, String::class.java)
                        .invoke(builder, "KEEP_ALIVE", "1")
                    builderClass.getMethod("param", String::class.java, String::class.java)
                        .invoke(builder, "wdec_param_nCmThreshold", "0 0:$threshold")
                } catch (e: Exception) {
                    Timber.w("$TAG: Failed to set KEEP_ALIVE params: ${e.message}")
                }
                val request = builderClass.getMethod("build").invoke(builder)

                aiHandle = aiHelperClass.getMethod("start", String::class.java, aiRequestClass, Any::class.java)
                    .invoke(aiHelper, IVW_ID, request, null)

                val isSuccess = aiHandle?.javaClass?.getMethod("isSuccess")?.invoke(aiHandle) as? Boolean ?: false
                if (!isSuccess) {
                    statsStartFailures++
                    val code = aiHandle?.javaClass?.getMethod("getCode")?.invoke(aiHandle) as? Int ?: -1
                    Timber.e("$TAG: Start session failed: ${getErrorMessage(code)} (code=$code)")
                    callback?.onError(WakeWordDetector.ERROR_UNKNOWN, getErrorMessage(code))
                    aiHandle = null
                    return
                }

                // D9 修复：先启动写线程，再置 isListening=true
                // （写线程启动前的 feedAudioData 会被丢弃，避免在 handle 就绪前丢帧还不报错）
                startAudioWriteThread()
                // 写线程启动之后再接受音频数据
                isListeningFlag.set(true)
                engineState = EngineState.LISTENING
                Timber.i("$TAG: ★★★ Wake-up session STARTED! '$wakeWord' (threshold=$threshold)")

            } catch (e: Exception) {
                statsStartFailures++
                Timber.e(e, "$TAG: Failed to start listening")
                isListeningFlag.set(false)
                endSessionInternal("startListening-catch")
            }
        }
    }

    private fun loadWakeUpWords() {
        try {
            val keywordFile = File(workDirPath, "keyword.txt")
            if (!keywordFile.exists()) {
                keywordFile.parentFile?.mkdirs()
                val subCmThreshold = (threshold * 0.6).toInt()
                keywordFile.writeText("$wakeWord;nCM:$threshold;nSubCM:$subCmThreshold;\n")
                Timber.i("$TAG: Created keyword file: $wakeWord nCM=$threshold nSubCM=$subCmThreshold")
            }

            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            val aiRequestClass = Class.forName(CLS_AI_REQUEST)
            val builderClass = Class.forName("$CLS_AI_REQUEST\$Builder")
            val builder = aiRequestClass.getMethod("builder").invoke(null)
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

    private fun startAudioWriteThread() {
        // D4 修复：先中断旧线程，确保只有一个写线程
        writeThread?.let { old ->
            if (old.isAlive) {
                Timber.w("$TAG: startAudioWriteThread: interrupting stale writer thread")
                old.interrupt()
            }
        }
        writeThread = null

        val t = Thread({
            Timber.i("$TAG: ★ Audio write thread started")
            try {
                val aiHelperClass = Class.forName(CLS_AI_HELPER)
                val aiRequestClass = Class.forName(CLS_AI_REQUEST)
                val builderClass = Class.forName("$CLS_AI_REQUEST\$Builder")
                val aiStatusClass = Class.forName(CLS_AI_STATUS)
                val aiHandleClass = Class.forName(CLS_AI_HANDLE)

                val builderFactoryMethod = aiRequestClass.getMethod("builder")
                val audioMethod = builderClass.getMethod("audio", String::class.java, ByteArray::class.java)
                val statusMethod = builderClass.getMethod("status", aiStatusClass)
                val buildMethod = builderClass.getMethod("build")
                val writeMethod = aiHelperClass.getMethod("write", aiRequestClass, aiHandleClass)

                val statusBegin = aiStatusClass.getField("BEGIN").get(null)
                val statusContinue = aiStatusClass.getField("CONTINUE").get(null)

                var frameIndex = 0
                while (isListeningFlag.get() && !Thread.currentThread().isInterrupted) {
                    val audioData = audioQueue.poll()
                    if (audioData != null && aiHandle != null) {
                        try {
                            val dataBuilder = builderFactoryMethod.invoke(null)
                            audioMethod.invoke(dataBuilder, "wav", audioData)
                            val status = if (frameIndex == 0) statusBegin else statusContinue
                            statusMethod.invoke(dataBuilder, status)
                            val writeRequest = buildMethod.invoke(dataBuilder)
                            val ret = writeMethod.invoke(aiHelper, writeRequest, aiHandle) as? Int ?: -1
                            if (ret != 0) {
                                statsWriteErrors++
                                if (frameIndex % 100 == 0) {
                                    Timber.w("$TAG: Write returned ${getErrorMessage(ret)} at frame $frameIndex (totalErr=$statsWriteErrors)")
                                }
                            }
                            frameIndex++
                        } catch (e: Exception) {
                            statsWriteErrors++
                            if (frameIndex % 50 == 0) {
                                Timber.e(e, "$TAG: Error writing audio frame $frameIndex")
                            }
                        }
                    } else {
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
        t.start()
        writeThread = t
    }

    /**
     * 供外部调用：送入音频帧数据
     * v4.0: 严格按 isListeningFlag + LISTENING 状态丢弃，写入前不累计音频（D9 修复）
     */
    fun feedAudioData(data: ByteArray) {
        if (!isListeningFlag.get() || engineState != EngineState.LISTENING) return
        statsFeedFrames++
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
        sessionLock.withLock {
            if (!isListeningFlag.getAndSet(false) && engineState != EngineState.LISTENING) return
            Timber.i("$TAG: stopListening (state=$engineState)")

            writeThread?.interrupt()
            writeThread = null
            audioQueue.clear()

            endSessionInternal("stopListening")

            engineState = EngineState.AUTH_DONE
            Timber.i("$TAG: Stopped listening")
        }
    }

    /** 内部结束会话；调用方必须持有 sessionLock */
    private fun endSessionInternal(reason: String) {
        try {
            val aiHelperClass = Class.forName(CLS_AI_HELPER)
            if (aiHandle != null) {
                val aiHandleClass = Class.forName(CLS_AI_HANDLE)
                val ret = aiHelperClass.getMethod("end", aiHandleClass)
                    .invoke(aiHelper, aiHandle) as? Int ?: -1
                if (ret != 0) {
                    Timber.w("$TAG: End session ($reason): ${getErrorMessage(ret)}")
                } else {
                    Timber.i("$TAG: Session ended ok (reason=$reason)")
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error ending session (reason=$reason)")
        }
        aiHandle = null
    }

    override fun release() {
        sessionLock.withLock {
            stopListening()
            try {
                val aiHelperClass = Class.forName(CLS_AI_HELPER)
                aiHelperClass.getMethod("unInit").invoke(aiHelper)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Error during release")
            }
            isInitialized = false
            isAuthComplete = false
            engineState = EngineState.RELEASED
            Timber.i("$TAG: Released (final feed=$statsFeedFrames, writeErr=$statsWriteErrors, wake=$statsWakeCount, startFail=$statsStartFailures)")
        }
    }

    // ──────────────────────────────────────────────
    // 公共属性 + 诊断接口 (v4.0)
    // ──────────────────────────────────────────────

    fun getFrameLength(): Int = 320
    fun getSampleRate(): Int = 16000
    fun isListening(): Boolean = isListeningFlag.get() && engineState == EngineState.LISTENING
    fun isAuthComplete(): Boolean = isAuthComplete
    fun isAuthFailed(): Boolean = isAuthFailed
    fun getAuthStateCode(): Int = lastAuthCode
    fun getAuthFailedReason(): String? = authFailedReason
    fun getEngineState(): EngineState = engineState
    fun getQueueSize(): Int = audioQueue.size
    fun getWorkDirPath(): String = workDirPath

    /** 阻塞等待授权完成，最多 AUTH_WAIT_SECONDS */
    fun waitForAuth(timeoutSec: Long = AUTH_WAIT_SECONDS): Boolean {
        if (isAuthComplete) return true
        if (isAuthFailed) return false
        return try {
            authLatch.await(timeoutSec, TimeUnit.SECONDS) && isAuthComplete
        } catch (_: InterruptedException) {
            false
        }
    }

    override fun setCallback(callback: WakeWordDetector.Callback) {
        this.callback = callback
    }

    override fun setSensitivity(sens: Float) {
        Timber.d("$TAG: Set sensitivity: $sens")
    }
}
