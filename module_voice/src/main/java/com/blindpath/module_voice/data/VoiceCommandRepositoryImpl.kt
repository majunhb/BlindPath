package com.blindpath.module_voice.data

import android.content.Context
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceCommandRepository
import com.blindpath.module_voice.domain.model.VoiceCommand
import com.blindpath.module_voice.domain.model.VoiceCommandResult
import com.blindpath.module_voice.domain.model.VoiceInteractionState
import com.blindpath.module_voice.service.BaiduAsrEngine
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 语音指令仓库实现 v2.0 — 百度ASR版
 *
 * 核心变更：用百度ASR SDK替换Android系统SpeechRecognizer
 * 原因：Android SpeechRecognizer需要Google Play Services，
 * 国产手机基本不支持，导致语音识别完全不可用。
 *
 * 核心职责：
 * 1. 管理百度ASR引擎生命周期
 * 2. 将识别结果通过interactionState暴露给Pipeline
 * 3. 维护唤醒词检测状态标志
 */
@Singleton
class VoiceCommandRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceCommandRepository {

    private val _interactionState = MutableStateFlow(VoiceInteractionState())
    override val interactionState: Flow<VoiceInteractionState> = _interactionState.asStateFlow()

    private var asrEngine: BaiduAsrEngine? = null
    private var isInitialized = false
    private var wakeWordEnabled = true
    private var currentWakeWord = "小智小智"

    override suspend fun initialize(): Result<Boolean> {
        return try {
            // 从BuildConfig读取百度凭证
            val appId = com.blindpath.module_voice.BuildConfig.BAIDU_APP_ID
            val apiKey = com.blindpath.module_voice.BuildConfig.BAIDU_API_KEY
            val secretKey = com.blindpath.module_voice.BuildConfig.BAIDU_SECRET_KEY

            if (appId.isBlank()) {
                Timber.e("VoiceCommandRepository: 百度凭证未配置，ASR不可用")
                return Result.Error(message = "百度语音凭证未配置")
            }

            Timber.i("VoiceCommandRepository: 初始化百度ASR (appId=${appId})")

            val engine = BaiduAsrEngine(context, appId, apiKey, secretKey)

            if (!engine.initialize()) {
                Timber.e("VoiceCommandRepository: 百度ASR引擎初始化失败")
                return Result.Error(message = "百度ASR引擎初始化失败")
            }

            // 设置ASR回调
            engine.onResult = { text, isFinal ->
                if (isFinal && text.isNotBlank()) {
                    Timber.i("VoiceCommandRepository: ★ ASR识别结果: \"${text}\"")
                    _interactionState.update {
                        it.copy(
                            isListening = false,
                            isWakeWordDetected = false,
                            lastCommand = VoiceCommandResult(
                                command = null,
                                confidence = 1.0f,
                                rawText = text
                            ),
                            lastError = null
                        )
                    }
                }
            }

            engine.onError = { code, msg ->
                Timber.w("VoiceCommandRepository: ASR错误 - code=${code}, msg=${msg}")
                val userMsg = when (code) {
                    BaiduAsrEngine.ERROR_AUDIO -> "录音错误"
                    BaiduAsrEngine.ERROR_NETWORK -> "网络错误，请检查网络连接"
                    BaiduAsrEngine.ERROR_AUDIO_TOO_LONG -> "语音过长"
                    BaiduAsrEngine.ERROR_SERVER -> "服务器错误"
                    BaiduAsrEngine.ERROR_EMPTY_RESULT, BaiduAsrEngine.ERROR_NO_MATCH -> "没有听清"
                    BaiduAsrEngine.ERROR_NOT_READY -> "识别引擎未就绪"
                    else -> "语音识别错误: ${msg}"
                }

                val isNoMatch = (code == BaiduAsrEngine.ERROR_NO_MATCH ||
                    code == BaiduAsrEngine.ERROR_EMPTY_RESULT)

                _interactionState.update {
                    it.copy(
                        isListening = false,
                        isWakeWordDetected = false,
                        lastError = if (isNoMatch) null else userMsg,
                        lastCommand = null
                    )
                }
            }

            engine.onReady = {
                Timber.d("VoiceCommandRepository: 百度ASR就绪")
                _interactionState.update {
                    it.copy(isListening = true, lastError = null)
                }
            }

            engine.onEnd = {
                Timber.d("VoiceCommandRepository: 语音结束")
                _interactionState.update { it.copy(isListening = false) }
            }

            engine.onVolume = { vol ->
                // 音量变化，可用于UI反馈
            }

            asrEngine = engine
            isInitialized = true
            Timber.i("VoiceCommandRepository: 百度ASR引擎初始化成功")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "VoiceCommandRepository: 初始化异常")
            Result.Error(message = e.message ?: "初始化失败")
        }
    }

    override suspend fun startListening(): Result<Boolean> {
        val engine = asrEngine
        if (engine == null || !isInitialized) {
            Timber.w("VoiceCommandRepository: ASR引擎未初始化")
            return Result.Error(message = "ASR引擎未初始化")
        }

        return try {
            // 清除上一次的结果
            _interactionState.update {
                it.copy(
                    isListening = false,
                    isWakeWordDetected = false,
                    lastCommand = null,
                    lastError = null
                )
            }

            engine.startListening()
            Timber.i("VoiceCommandRepository: 百度ASR开始监听")
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "VoiceCommandRepository: 启动ASR失败")
            _interactionState.update {
                it.copy(isListening = false, isWakeWordDetected = false, lastError = e.message)
            }
            Result.Error(message = e.message ?: "启动ASR失败")
        }
    }

    override suspend fun stopListening(): Result<Boolean> {
        return try {
            asrEngine?.stopListening()
            _interactionState.update { it.copy(isListening = false) }
            Timber.d("VoiceCommandRepository: 停止ASR监听")
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(message = e.message ?: "停止ASR失败")
        }
    }

    override suspend fun recognizeOnce(): Result<VoiceCommandResult> {
        val startResult = startListening()
        if (startResult is Result.Error) return startResult

        // 等待识别结果（最多10秒）
        kotlinx.coroutines.withTimeoutOrNull(10000L) {
            interactionState.first { it.lastCommand != null }
        }

        val result = _interactionState.value.lastCommand
        _interactionState.update { it.copy(lastCommand = null) }
        return Result.Success(result ?: VoiceCommandResult(null, 0f, ""))
    }

    override fun release() {
        try {
            asrEngine?.release()
        } catch (e: Exception) {
            Timber.w(e, "VoiceCommandRepository: 释放ASR引擎异常")
        }
        asrEngine = null
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
        Timber.i("VoiceCommandRepository: ★ 唤醒词检测到: ${wakeWord}")
        _interactionState.update {
            it.copy(
                isWakeWordDetected = true,
                wakeWord = wakeWord,
                lastCommand = null
            )
        }
    }

    override fun consumeLastCommand(): VoiceCommandResult? {
        val result = _interactionState.value.lastCommand
        _interactionState.update { it.copy(lastCommand = null) }
        Timber.d("VoiceCommandRepository: consumeLastCommand: ${result?.rawText}")
        return result
    }

    override fun notifyTtsStart() {
        // TTS开始播报时取消ASR，避免TTS音频被误识别
        try {
            asrEngine?.cancel()
        } catch (_: Exception) {}
    }

    override fun notifyTtsStop() {
        // TTS停止后不自动恢复（由Pipeline控制何时重新启动ASR）
    }
}
