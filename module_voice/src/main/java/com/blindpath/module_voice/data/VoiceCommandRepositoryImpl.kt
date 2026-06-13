package com.blindpath.module_voice.data

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceCommandRepository
import com.blindpath.module_voice.domain.model.VoiceCommand
import com.blindpath.module_voice.domain.model.VoiceCommandResult
import com.blindpath.module_voice.domain.model.VoiceInteractionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音指令仓库实现
 *
 * 核心职责：
 * 1. 管理 Android SpeechRecognizer 生命周期
 * 2. 将识别结果转换为 VoiceCommand
 * 3. 维护唤醒词检测状态标志
 */
@Singleton
class VoiceCommandRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceCommandRepository {

    private val _interactionState = MutableStateFlow(VoiceInteractionState())
    override val interactionState: Flow<VoiceInteractionState> = _interactionState.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null
    private var isInitialized = false
    private var wakeWordEnabled = true
    private var currentWakeWord = "小智小智"

    override suspend fun initialize(): Result<Boolean> {
        return try {
            if (SpeechRecognizer.isRecognitionAvailable(context)) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                isInitialized = true
                Timber.i("VoiceCommandRepository: SpeechRecognizer 初始化成功")
                Result.Success(true)
            } else {
                Timber.w("VoiceCommandRepository: 设备不支持语音识别")
                Result.Error(message = "设备不支持语音识别")
            }
        } catch (e: Exception) {
            Timber.e(e, "VoiceCommandRepository: SpeechRecognizer 初始化失败")
            Result.Error(message = e.message ?: "初始化失败")
        }
    }

    override suspend fun startListening(): Result<Boolean> {
        val recognizer = speechRecognizer
        if (recognizer == null) {
            Timber.w("VoiceCommandRepository: SpeechRecognizer 未初始化")
            return Result.Error(message = "SpeechRecognizer 未初始化")
        }

        return try {
            // 取消之前的识别会话
            recognizer.cancel()

            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1000L)
            }

            recognizer.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {
                    Timber.d("VoiceCommandRepository: ASR 就绪，开始监听")
                    _interactionState.update { it.copy(isListening = true, lastError = null) }
                }

                override fun onBeginningOfSpeech() {
                    Timber.d("VoiceCommandRepository: 检测到语音开始")
                }

                override fun onRmsChanged(rmsdB: Float) {
                    // 音量变化，可用于 UI 反馈
                }

                override fun onBufferReceived(buffer: ByteArray?) {}

                override fun onEndOfSpeech() {
                    Timber.d("VoiceCommandRepository: 语音结束")
                    _interactionState.update { it.copy(isListening = false) }
                }

                override fun onError(error: Int) {
                    val errorMsg = when (error) {
                        SpeechRecognizer.ERROR_AUDIO -> "音频错误"
                        SpeechRecognizer.ERROR_CLIENT -> "客户端错误"
                        SpeechRecognizer.ERROR_SERVER -> "服务端错误"
                        SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                        SpeechRecognizer.ERROR_NO_MATCH -> "未识别到语音"
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器繁忙"
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "权限不足"
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音超时"
                        else -> "未知错误: $error"
                    }
                    Timber.w("VoiceCommandRepository: ASR 错误 - $errorMsg")

                    // 未识别到语音不算真正的错误，重置状态即可
                    if (error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
                        _interactionState.update {
                            it.copy(
                                isListening = false,
                                isWakeWordDetected = false,
                                lastError = null
                            )
                        }
                    } else {
                        _interactionState.update {
                            it.copy(
                                isListening = false,
                                isWakeWordDetected = false,
                                lastError = errorMsg
                            )
                        }
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    val text = matches?.firstOrNull() ?: ""

                    Timber.i("VoiceCommandRepository: ASR 识别结果: \"$text\"")

                    val command = VoiceCommand.fromSpokenText(text)
                    val confidence = if (command != null) 0.9f else 0.3f

                    _interactionState.update {
                        it.copy(
                            isListening = false,
                            isWakeWordDetected = false,
                            lastCommand = VoiceCommandResult(
                                command = command,
                                confidence = confidence,
                                rawText = text
                            ),
                            lastError = null
                        )
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {
                    val partial = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    if (!partial.isNullOrBlank()) {
                        Timber.d("VoiceCommandRepository: 部分识别: \"$partial\"")
                    }
                }

                override fun onEvent(eventType: Int, params: Bundle?) {}
            })

            recognizer.startListening(intent)
            Timber.i("VoiceCommandRepository: 开始语音识别")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "VoiceCommandRepository: 启动识别失败")
            _interactionState.update {
                it.copy(isListening = false, isWakeWordDetected = false, lastError = e.message)
            }
            Result.Error(message = e.message ?: "启动识别失败")
        }
    }

    override suspend fun stopListening(): Result<Boolean> {
        return try {
            speechRecognizer?.stopListening()
            _interactionState.update { it.copy(isListening = false) }
            Timber.d("VoiceCommandRepository: 停止识别")
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(message = e.message ?: "停止识别失败")
        }
    }

    override suspend fun recognizeOnce(): Result<VoiceCommandResult> {
        // 复用 startListening + 等待 lastCommand 的模式
        val startResult = startListening()
        if (startResult is Result.Error) return startResult

        // 等待识别结果（最多 10 秒）
        kotlinx.coroutines.withTimeoutOrNull(10000L) {
            interactionState.first { it.lastCommand != null }
        }

        val result = _interactionState.value.lastCommand
        _interactionState.update { it.copy(lastCommand = null) }
        return Result.Success(result ?: VoiceCommandResult(null, 0f, ""))
    }

    override fun release() {
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Timber.w(e, "VoiceCommandRepository: 释放 SpeechRecognizer 异常")
        }
        speechRecognizer = null
        isInitialized = false
        Timber.i("VoiceCommandRepository: 已释放")
    }

    override fun setWakeWord(word: String) {
        currentWakeWord = word
        _interactionState.update { it.copy(wakeWord = word) }
    }

    override fun setWakeWordEnabled(enabled: Boolean) {
        wakeWordEnabled = enabled
        _interactionState.update { it.copy(isWakeWordEnabled = enabled) }
    }

    override fun triggerWakeWordDetected(wakeWord: String) {
        Timber.i("VoiceCommandRepository: ★ 唤醒词检测到: $wakeWord")
        _interactionState.update {
            it.copy(
                isWakeWordDetected = true,
                wakeWord = wakeWord,
                lastCommand = null  // 清除上一条指令
            )
        }
    }

    override fun consumeLastCommand(): VoiceCommandResult? {
        val result = _interactionState.value.lastCommand
        _interactionState.update { it.copy(lastCommand = null) }
        Timber.d("VoiceCommandRepository: consumeLastCommand: ${result?.command?.name}")
        return result
    }

    override fun notifyTtsStart() {
        // TTS 开始播报时，暂停识别以避免 TTS 被误识别
        try {
            speechRecognizer?.stopListening()
        } catch (_: Exception) {}
    }

    override fun notifyTtsStop() {
        // TTS 停止播报时，无需自动恢复（由 Pipeline 控制）
    }
}