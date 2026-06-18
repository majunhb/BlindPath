package com.blindpath.module_voice.data

import android.content.Context
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceCommandRepository
import com.blindpath.module_voice.domain.model.VoiceCommandResult
import com.blindpath.module_voice.domain.model.VoiceInteractionState
import com.blindpath.module_voice.service.XfAsrEngine
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
 * 语音指令仓库实现 v3.0 — 讯飞AIKit离线ASR版
 *
 * 核心变更：用讯飞AIKit离线ASR替换百度云端ASR
 * 原因：
 * 1. 百度ASR依赖网络，盲人户外弱网/无网时完全失效 → 安全风险
 * 2. 百度ASR需要asr.ready事件，时序协调复杂 → 用户"没回应"
 * 3. 讯飞AIKit支持离线识别+VAD自动端点检测，更可靠更简单
 *
 * 核心职责：
 * 1. 管理讯飞ASR引擎生命周期
 * 2. 将识别结果通过interactionState暴露给Pipeline
 * 3. 维护唤醒词检测状态标志
 */
@Singleton
class VoiceCommandRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceCommandRepository {

    private val _interactionState = MutableStateFlow(VoiceInteractionState())
    override val interactionState: Flow<VoiceInteractionState> = _interactionState.asStateFlow()

    private var asrEngine: XfAsrEngine? = null
    private var isInitialized = false
    private var wakeWordEnabled = true
    private var currentWakeWord = "小智小智"

    override suspend fun initialize(): Result<Boolean> {
        return try {
            val appId = com.blindpath.module_voice.BuildConfig.IFLYTEK_APP_ID
            val apiKey = com.blindpath.module_voice.BuildConfig.IFLYTEK_API_KEY
            val apiSecret = com.blindpath.module_voice.BuildConfig.IFLYTEK_API_SECRET

            if (appId.isBlank()) {
                Timber.e("VoiceCommandRepository: 讯飞凭证未配置，ASR不可用")
                return Result.Error(message = "讯飞语音凭证未配置")
            }

            Timber.i("VoiceCommandRepository: 初始化讯飞AIKit离线ASR (appId=\${appId})")

            val engine = XfAsrEngine(context, appId, apiKey, apiSecret)

            if (!engine.initialize()) {
                Timber.e("VoiceCommandRepository: 讯飞ASR引擎初始化失败")
                return Result.Error(message = "讯飞ASR引擎初始化失败")
            }

            // 设置ASR回调
            engine.onResult = { text, isFinal ->
                if (isFinal && text.isNotBlank()) {
                    Timber.i("VoiceCommandRepository: ★ ASR识别结果: \"\${text}\"")
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
                Timber.w("VoiceCommandRepository: ASR错误 - code=\${code}, msg=\${msg}")
                val userMsg = when (code) {
                    XfAsrEngine.ERROR_AUDIO -> "录音异常，请重试"
                    XfAsrEngine.ERROR_NOT_READY -> "识别引擎未就绪，请重试"
                    XfAsrEngine.ERROR_TIMEOUT -> "识别超时，请再说一遍"
                    XfAsrEngine.ERROR_SDK -> msg // SDK错误直接传递（含授权信息）
                    else -> "语音识别异常: \${msg}"
                }
                _interactionState.update {
                    it.copy(
                        isListening = false,
                        isWakeWordDetected = false,
                        lastError = userMsg,
                        lastCommand = null
                    )
                }
            }

            engine.onReady = {
                Timber.d("VoiceCommandRepository: 讯飞ASR就绪")
                _interactionState.update {
                    it.copy(isListening = true, lastError = null)
                }
            }

            engine.onBegin = {
                Timber.d("VoiceCommandRepository: VAD检测到说话开始")
            }

            engine.onEnd = {
                Timber.d("VoiceCommandRepository: VAD检测到说话结束")
                _interactionState.update { it.copy(isListening = false) }
            }

            engine.onVolume = { vol ->
                // 音量变化，可用于UI反馈
            }

            asrEngine = engine
            isInitialized = true
            Timber.i("VoiceCommandRepository: 讯飞ASR引擎初始化成功（离线模式）")
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
            _interactionState.update {
                it.copy(
                    isListening = false,
                    isWakeWordDetected = false,
                    lastCommand = null,
                    lastError = null
                )
            }

            engine.startListening()
            Timber.i("VoiceCommandRepository: 讯飞ASR开始监听 (VAD自动端点检测)")
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
            _interactionState.update {
                it.copy(
                    isListening = false,
                    lastCommand = null,
                    lastError = null
                )
            }
            Timber.d("VoiceCommandRepository: 停止ASR监听 (state fully cleared)")
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(message = e.message ?: "停止ASR失败")
        }
    }

    override suspend fun recognizeOnce(): Result<VoiceCommandResult> {
        val startResult = startListening()
        if (startResult is Result.Error) return startResult

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
        Timber.i("VoiceCommandRepository: ★ 唤醒词检测到: \${wakeWord}")
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
        Timber.d("VoiceCommandRepository: consumeLastCommand: \${result?.rawText}")
        return result
    }

    override fun notifyTtsStart() {
        try {
            asrEngine?.cancel()
        } catch (e: Exception) { }
    }

    override fun notifyTtsStop() {
        // TTS停止后不自动恢复（由Pipeline控制何时重新启动ASR）
    }
}
