package com.blindpath.module_voice.data

import android.content.Context
import com.baidu.tts.TtsMode
import com.baidu.tts.chainofresponsibility.logger.LoggerProxy
import com.baidu.tts.client.SpeechError
import com.baidu.tts.client.SpeechSynthesizer
import com.baidu.tts.client.SpeechSynthesizerListener
import com.baidu.tts.initialization.InitConfig
import com.baidu.tts.initialization.SynthesizerTool
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.VoiceState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceRepository {

    private val _state = MutableStateFlow(VoiceState())
    override val voiceState: StateFlow<VoiceState> = _state.asStateFlow()

    private var speechSynthesizer: SpeechSynthesizer? = null
    private var isInitialized = false

    private val threadPool = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, LinkedBlockingQueue())

    override suspend fun initialize(): Result<Boolean> {
        return try {
            Timber.d("Initializing Baidu TTS")

            // 设置日志
            LoggerProxy.printable(true)

            // 初始化TTS
            val initConfig = InitConfig(
                TtsMode.ONLINE,
                context,
                { status ->
                    Timber.d("TTS init status: $status")
                    if (status == SynthesizerTool.TTS_INIT_OK) {
                        isInitialized = true
                        _state.update { it.copy(isAvailable = true) }
                    }
                },
                // AppId, AppKey, SecretKey 需要替换为实际的百度语音开放平台的密钥
                appId = "YOUR_APP_ID",
                appKey = "YOUR_APP_KEY",
                secretKey = "YOUR_SECRET_KEY"
            )

            speechSynthesizer = SpeechSynthesizer.getInstance()
            speechSynthesizer?.setListener(object : SpeechSynthesizerListener {
                override fun onSynthesizeStart(p0: String?) {
                    _state.update { it.copy(isSpeaking = true) }
                }

                override fun onSynthesizeDataArrived(p0: String?, p1: ByteArray?, p2: Int, p3: Int) {}

                override fun onSynthesizeFinish(p0: String?) {
                    _state.update { it.copy(isSpeaking = false) }
                    Timber.d("TTS synthesize finished")
                }

                override fun onSpeechStart(p0: String?) {
                    _state.update { it.copy(isSpeaking = true) }
                }

                override fun onSpeechProgressMissed(p0: String?, p1: Int, p2: Int) {}

                override fun onSpeechFinish(p0: String?) {
                    _state.update { it.copy(isSpeaking = false) }
                }

                override fun onError(p0: String?, p1: SpeechError?) {
                    Timber.e("TTS error: ${p1?.description}")
                    _state.update {
                        it.copy(
                            isSpeaking = false,
                            lastError = p1?.description
                        )
                    }
                }

                override fun onSpeechStartWith utteranceId: String?) {
                    Timber.d("Speech started: $utteranceId")
                }

                override fun onSpeechFinishWith utteranceId: String?) {
                    Timber.d("Speech finished: $utteranceId")
                }

                override fun onSynthesizeFinishWith utteranceId: String?, p1: Int) {
                    Timber.d("Synthesize finished: $utteranceId")
                }

                override fun onSpeechProgressModify(p0: String?, p1: Int, p2: Int) {}
            })

            speechSynthesizer?.init(initConfig)

            // 设置参数
            speechSynthesizer?.apply {
                setParam(SpeechSynthesizer.PARAM_SPEAKER, "0")  // 0:女声, 1:男声
                setParam(SpeechSynthesizer.PARAM_VOLUME, "15")  // 音量0-15
                setParam(SpeechSynthesizer.PARAM_SPEED, "5")    // 语速0-15
                setParam(SpeechSynthesizer.PARAM_PITCH, "5")   // 音调0-15
                setParam(SpeechSynthesizer.PARAM_MIX_MODE, "1") // 启用离线
            }

            _state.update { it.copy(isAvailable = true) }
            Timber.d("Baidu TTS initialized successfully")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize TTS")
            _state.update { it.copy(lastError = e.message) }
            Result.Error(message = e.message ?: "语音初始化失败")
        }
    }

    override suspend fun speak(text: String, queueMode: Boolean): Result<Boolean> {
        return try {
            if (!isInitialized) {
                initialize()
            }

            if (queueMode) {
                speechSynthesizer?.speak(text)
            } else {
                speechSynthesizer?.stop()
                speechSynthesizer?.speak(text)
            }

            Timber.d("Speaking: $text")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to speak")
            Result.Error(message = e.message ?: "语音播报失败")
        }
    }

    override suspend fun stop(): Result<Boolean> {
        return try {
            speechSynthesizer?.stop()
            _state.update { it.copy(isSpeaking = false) }
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(message = e.message ?: "停止语音失败")
        }
    }

    override suspend fun pause(): Result<Boolean> {
        return try {
            speechSynthesizer?.pause()
            _state.update { it.copy(isSpeaking = false) }
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(message = e.message ?: "暂停语音失败")
        }
    }

    override suspend fun resume(): Result<Boolean> {
        return try {
            speechSynthesizer?.resume()
            _state.update { it.copy(isSpeaking = true) }
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(message = e.message ?: "恢复语音失败")
        }
    }

    override fun release() {
        speechSynthesizer?.stop()
        speechSynthesizer?.release()
        speechSynthesizer = null
        isInitialized = false
        _state.update { VoiceState() }
        threadPool.shutdown()
        Timber.d("TTS released")
    }

    /**
     * 播报避障预警（高优先级）
     */
    suspend fun speakObstacleAlert(text: String) {
        // 停止当前播报，立即播报预警
        speechSynthesizer?.stop()
        speak(text, queueMode = false)
    }

    /**
     * 播报导航指令（低优先级）
     */
    suspend fun speakNavigation(text: String) {
        // 排队播报，不打断避障
        if (_state.value.isSpeaking) {
            return // 正在播报避障，不播导航
        }
        speak(text, queueMode = true)
    }
}
