package com.blindpath.module_voice.service

import android.content.Context
import com.blindpath.module_voice.domain.model.WakeWordConfig
import com.iflytek.cloud.SpeechConstant
import com.iflytek.cloud.SpeechError
import com.iflytek.cloud.VoiceWakeuper
import com.iflytek.cloud.WakeuperListener
import com.iflytek.cloud.WakeuperResult
import com.iflytek.cloud.util.ResourceUtil
import org.json.JSONObject
import timber.log.Timber

/**
 * 科大讯飞 MSC 语音唤醒检测器
 *
 * 基于讯飞 MSC SDK 的 VoiceWakeuper 实现。
 * SDK 自动管理音频采集（自管理模式）。
 *
 * 集成要求：
 * 1. 从讯飞开放平台下载 MSC SDK（包含 msc.jar + libmsc.so）
 * 2. 将 msc.jar 放入 module_voice/libs/
 * 3. 将 libmsc.so 放入 module_voice/src/main/jniLibs/{abi}/
 * 4. 在讯飞控制台制作唤醒词资源，得到 {appid}.jet 文件
 * 5. 将 .jet 文件放入 module_voice/src/main/assets/ivw/
 * 6. 在 local.properties 中配置 IFLYTEK_APP_ID
 *
 * 文档：https://www.xfyun.cn/doc/mscapi/Android/androidwakeuper.html
 */
class XfWakeWordDetector(
    private val context: Context,
    private val appId: String,
    private val threshold: Int = 1450,
    private val wakeWord: String = WakeWordConfig.DEFAULT_WAKE_WORD,
    private val onWakeWordDetected: (String) -> Unit
) : WakeWordDetector {

    companion object {
        private const val TAG = "XfWakeWordDetector"
    }

    private var wakeuper: VoiceWakeuper? = null
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

        // 创建唤醒对象
        wakeuper = VoiceWakeuper.createWakeuper(context, null)

        if (wakeuper == null) {
            throw IllegalStateException("Failed to create VoiceWakeuper (is MSC SDK in libs/?)")
        }

        isInitialized = true
        Timber.i("$TAG: Initialized successfully (AppID: $appId)")
    }

    /**
     * 唤醒事件监听器
     */
    private val wakeuperListener = object : WakeuperListener {
        override fun onResult(result: WakeuperResult?) {
            try {
                val text = result?.resultString ?: return
                Timber.i("$TAG: onResult: $text")

                val json = JSONObject(text)
                val keyword = json.optString("keyword", "")
                val score = json.optInt("score", 0)
                Timber.i("$TAG: Wake word detected! keyword=$keyword, score=$score")

                onWakeWordDetected.invoke(wakeWord)
            } catch (e: Exception) {
                Timber.e(e, "$TAG: Failed to parse wake result, triggering callback anyway")
                onWakeWordDetected.invoke(wakeWord)
            }
        }

        override fun onError(error: SpeechError?) {
            val errorCode = error?.errorCode ?: -1
            val errorDesc = error?.errorDescription ?: "unknown"
            Timber.e("$TAG: Wake error: code=$errorCode, desc=$errorDesc")
        }

        override fun onBeginOfSpeech() {
            Timber.d("$TAG: onBeginOfSpeech")
        }

        override fun onEvent(eventType: Int, isLast: Int, arg2: Int, obj: Bundle?) {
            Timber.d("$TAG: onEvent: type=$eventType, isLast=$isLast")
        }

        override fun onVolumeChanged(volume: Int) {
            // 音量变化，可用于调试
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
            wakeuper?.setParameter(SpeechConstant.PARAMS, null)

            // 唤醒门限值（范围 [0, 3000]，默认 1450）
            wakeuper?.setParameter(SpeechConstant.IVW_THRESHOLD, "0:$threshold")

            // 唤醒模式：wakeup = 纯唤醒
            wakeuper?.setParameter(SpeechConstant.IVW_SST, "wakeup")

            // 持续唤醒：1 = 循环监听
            wakeuper?.setParameter(SpeechConstant.KEEP_ALIVE, "1")

            // 闭环优化网络模式：0 = 关闭
            wakeuper?.setParameter(SpeechConstant.IVW_NET_MODE, "0")

            // 设置唤醒资源路径（assets/ivw/{appid}.jet）
            val resPath = ResourceUtil.generateResourcePath(
                context,
                ResourceUtil.RESOURCE_TYPE.assets,
                "ivw/$appId.jet"
            )
            wakeuper?.setParameter(SpeechConstant.IVW_RES_PATH, resPath)
            Timber.i("$TAG: Resource path: $resPath")

            // 启动唤醒
            wakeuper?.startListening(wakeuperListener)
            isListening = true
            Timber.i("$TAG: Wake-up started, listening for '$wakeWord'")
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Failed to start listening")
            isListening = false
        }
    }

    /**
     * 停止监听
     */
    fun stopListening() {
        if (wakeuper == null || !isListening) return

        try {
            wakeuper?.stopListening()
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
            wakeuper?.destroy()
        } catch (e: Exception) {
            Timber.e(e, "$TAG: Error during release")
        }
        wakeuper = null
        isInitialized = false
        Timber.i("$TAG: Released")
    }
}
