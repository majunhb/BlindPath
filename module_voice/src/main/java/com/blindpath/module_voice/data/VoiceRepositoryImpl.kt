package com.blindpath.module_voice.data

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.VoiceState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceRepository {

    private val _state = MutableStateFlow(VoiceState())
    override val voiceState: StateFlow<VoiceState> = _state.asStateFlow()

    private var tts: TextToSpeech? = null
    private var isInitialized = false

    override suspend fun initialize(): Result<Boolean> {
        return try {
            Timber.d("Initializing Android TTS")

            tts = TextToSpeech(context) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    val result = tts?.setLanguage(Locale.CHINESE)
                    if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                        Timber.e("Chinese language not supported")
                        _state.update { it.copy(lastError = "不支持中文语音") }
                    } else {
                        isInitialized = true
                        _state.update { it.copy(isAvailable = true) }
                        Timber.d("Android TTS initialized successfully")
                    }
                } else {
                    Timber.e("TTS initialization failed with status: $status")
                    _state.update { it.copy(lastError = "语音初始化失败") }
                }
            }

            // 设置监听器
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {
                    _state.update { it.copy(isSpeaking = true) }
                }

                override fun onDone(utteranceId: String?) {
                    _state.update { it.copy(isSpeaking = false) }
                    Timber.d("TTS playback finished")
                }

                @Deprecated("Deprecated in API")
                override fun onError(utteranceId: String?) {
                    Timber.e("TTS error: $utteranceId")
                    _state.update {
                        it.copy(
                            isSpeaking = false,
                            lastError = "语音播放错误"
                        )
                    }
                }

                override fun onError(utteranceId: String?, errorCode: Int) {
                    Timber.e("TTS error: $utteranceId, code: $errorCode")
                    _state.update {
                        it.copy(
                            isSpeaking = false,
                            lastError = "语音播放错误: $errorCode"
                        )
                    }
                }
            })

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

            if (tts == null) {
                return Result.Error(message = "TTS 未初始化")
            }

            val utteranceId = UUID.randomUUID().toString()

            if (queueMode) {
                // 队列模式
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId)
            } else {
                // 立即播放
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
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
            tts?.stop()
            _state.update { it.copy(isSpeaking = false) }
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(message = e.message ?: "停止语音失败")
        }
    }

    override suspend fun pause(): Result<Boolean> {
        // Android TTS 不支持暂停，使用 stop 代替
        return stop()
    }

    override suspend fun resume(): Result<Boolean> {
        // Android TTS 不支持恢复，重新播放
        return Result.Error(message = "TTS 不支持恢复功能")
    }

    override fun release() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        isInitialized = false
        _state.update { VoiceState() }
        Timber.d("TTS released")
    }

    /**
     * 播报避障预警（高优先级）
     */
    suspend fun speakObstacleAlert(text: String) {
        // 停止当前播报，立即播报预警
        tts?.stop()
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
