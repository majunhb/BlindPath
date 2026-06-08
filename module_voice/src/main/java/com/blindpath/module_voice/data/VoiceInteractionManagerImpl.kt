package com.blindpath.module_voice.data

import android.content.Context
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceCommandExecutor
import com.blindpath.module_voice.domain.VoiceInteractionManager
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceInteractionManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val voiceRepository: VoiceRepository
) : VoiceInteractionManager {

    private val _interactionState = MutableStateFlow(VoiceInteractionState())
    override val interactionState: StateFlow<VoiceInteractionState> = _interactionState

    private var _isInitialized = false
    override val isInitialized: Boolean get() = _isInitialized

    private var commandExecutor: VoiceCommandExecutor? = null

    override suspend fun initialize(): Result<Boolean> {
        return try {
            val ttsResult = voiceRepository.initialize()
            if (ttsResult is Result.Error) return ttsResult
            _isInitialized = true
            _interactionState.value = _interactionState.value.copy(isInitialized = true, status = VoiceInteractionStatus.READY)
            Result.Success(true)
        } catch (e: Exception) {
            Result.Error(message = e.message ?: "初始化失败")
        }
    }

    override suspend fun speakWelcome() {
        if (!_isInitialized) return
        voiceRepository.announce("欢迎使用智行助盲，我是您的语音助手小智。", VoiceType.SYSTEM_STATUS)
    }

    override suspend fun speakHelp() {
        if (!_isInitialized) return
        voiceRepository.announce("可用指令：开始导航、环境感知、我要回家、附近设施、帮助。", VoiceType.SYSTEM_STATUS)
    }

    override suspend fun speak(text: String, type: VoiceType) {
        if (!_isInitialized) { Timber.w("未初始化，跳过: $text"); return }
        voiceRepository.announce(text, type)
    }

    override suspend fun startListening(): Result<Boolean> {
        _interactionState.value = _interactionState.value.copy(status = VoiceInteractionStatus.LISTENING)
        return Result.Success(true)
    }

    override suspend fun stopListening(): Result<Boolean> {
        _interactionState.value = _interactionState.value.copy(status = VoiceInteractionStatus.READY)
        return Result.Success(true)
    }

    override suspend fun handleCommand(command: VoiceCommand): Boolean {
        return commandExecutor?.executeCommand(command) ?: false
    }

    override fun setCommandExecutor(executor: VoiceCommandExecutor) {
        commandExecutor = executor
    }

    override fun release() {
        _isInitialized = false
        voiceRepository.release()
        _interactionState.value = VoiceInteractionState()
    }
}
