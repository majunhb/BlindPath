package com.blindpath.module_voice.data

import android.content.Context
import com.blindpath.base.common.Result
import com.blindpath.module_voice.domain.VoiceInteractionManager
import com.blindpath.module_voice.domain.model.*
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class VoiceInteractionManagerImpl @Inject constructor(
    @ApplicationContext private val context: Context
) : VoiceInteractionManager {

    private val _interactionState = MutableStateFlow(VoiceInteractionState())
    override val interactionState: StateFlow<VoiceInteractionState> = _interactionState

    override val isInitialized: Boolean = false
    private var commandExecutor: VoiceCommandExecutor? = null

    override suspend fun initialize(): Result<Boolean> {
        return Result.Success(true)
    }

    override suspend fun speakWelcome() {}
    override suspend fun speakHelp() {}
    override suspend fun speak(text: String, type: VoiceType) {
        Timber.d("TTS: $text")
    }

    override suspend fun startListening(): Result<Boolean> = Result.Success(true)
    override suspend fun stopListening(): Result<Boolean> = Result.Success(true)

    override suspend fun handleCommand(command: VoiceCommand): Boolean {
        return commandExecutor?.executeCommand(command) ?: false
    }

    override fun setCommandExecutor(executor: VoiceCommandExecutor) {
        commandExecutor = executor
    }

    override fun release() {}
}
