package com.blindpath.module_voice.data

import android.content.Context
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
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

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
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            isInitialized = true
            Result.Success(true)
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize speech recognizer")
            Result.Error(message = e.message ?: "初始化失败")
        }
    }

    override suspend fun startListening(): Result<Boolean> = Result.Success(true)
    override suspend fun stopListening(): Result<Boolean> = Result.Success(true)

    override suspend fun recognizeOnce(): Result<VoiceCommandResult> {
        return Result.Success(VoiceCommandResult(command = null, confidence = 0f, rawText = ""))
    }

    override fun release() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    override fun setWakeWord(word: String) { currentWakeWord = word }
    override fun setWakeWordEnabled(enabled: Boolean) { wakeWordEnabled = enabled }

    override fun triggerWakeWordDetected(wakeWord: String) {
        _interactionState.value = _interactionState.value.copy(
            isWakeWordDetected = true,
            wakeWord = wakeWord
        )
    }

    override fun consumeLastCommand(): VoiceCommandResult? = null
    override fun notifyTtsStart() {}
    override fun notifyTtsStop() {}
}
