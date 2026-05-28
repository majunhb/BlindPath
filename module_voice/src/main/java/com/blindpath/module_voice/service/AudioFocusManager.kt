package com.blindpath.module_voice.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import androidx.annotation.RequiresApi
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局音频焦点管理器
 * 
 * 功能：
 * - 管理音频焦点请求与释放
 * - 兼容 TalkBack 屏幕阅读器
 * - 处理蓝牙耳机音频路由
 * - 协调多个音频模块的优先级
 */
@Singleton
class AudioFocusManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    
    private val _audioFocusState = MutableStateFlow(AudioFocusState.LOSS)
    val audioFocusState: StateFlow<AudioFocusState> = _audioFocusState.asStateFlow()
    
    private var currentFocusRequest: AudioFocusRequest? = null
    private var focusChangeListener: AudioManager.OnAudioFocusChangeListener? = null
    
    // 音频模块优先级（数值越大优先级越高）
    private val modulePriorities = mutableMapOf<String, Int>()
    private var currentHolder: String? = null
    
    /**
     * 请求音频焦点
     * @param moduleId 模块标识（如 "tts", "asr", "wakeword"）
     * @param priority 优先级
     * @return 是否成功获取焦点
     */
    fun requestFocus(moduleId: String, priority: Int = 0): Boolean {
        Timber.d("AudioFocus: Request from $moduleId (priority: $priority)")
        
        modulePriorities[moduleId] = priority
        
        // 检查当前持有者优先级
        currentHolder?.let { holder ->
            val holderPriority = modulePriorities[holder] ?: 0
            if (holderPriority > priority && _audioFocusState.value == AudioFocusState.GAIN) {
                Timber.d("AudioFocus: $moduleId rejected, $holder has higher priority")
                return false
            }
        }
        
        val result = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            requestFocusApi26(moduleId)
        } else {
            requestFocusLegacy(moduleId)
        }
        
        if (result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            currentHolder = moduleId
            _audioFocusState.value = AudioFocusState.GAIN
            Timber.i("AudioFocus: Granted to $moduleId")
            return true
        }
        
        Timber.w("AudioFocus: Request failed for $moduleId")
        return false
    }
    
    /**
     * 释放音频焦点
     */
    fun abandonFocus(moduleId: String) {
        if (currentHolder != moduleId) {
            Timber.d("AudioFocus: $moduleId trying to abandon, but holder is $currentHolder")
            return
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            abandonFocusApi26()
        } else {
            abandonFocusLegacy()
        }
        
        currentHolder = null
        _audioFocusState.value = AudioFocusState.LOSS
        Timber.i("AudioFocus: Abandoned by $moduleId")
    }
    
    /**
     * 检查是否有音频焦点
     */
    fun hasFocus(moduleId: String): Boolean {
        return currentHolder == moduleId && _audioFocusState.value == AudioFocusState.GAIN
    }
    
    /**
     * 获取当前焦点持有者
     */
    fun getCurrentHolder(): String? = currentHolder
    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun requestFocusApi26(moduleId: String): Int {
        val listener = createFocusChangeListener(moduleId)
        focusChangeListener = listener
        
        val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener(listener)
            .build()
        
        currentFocusRequest = focusRequest
        return audioManager.requestAudioFocus(focusRequest)
    }
    
    @Suppress("DEPRECATION")
    private fun requestFocusLegacy(moduleId: String): Int {
        val listener = createFocusChangeListener(moduleId)
        focusChangeListener = listener
        
        return audioManager.requestAudioFocus(
            listener,
            AudioManager.STREAM_MUSIC,
            AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
        )
    }
    
    @RequiresApi(Build.VERSION_CODES.O)
    private fun abandonFocusApi26() {
        currentFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        currentFocusRequest = null
        focusChangeListener = null
    }
    
    @Suppress("DEPRECATION")
    private fun abandonFocusLegacy() {
        focusChangeListener?.let {
            audioManager.abandonAudioFocus(it)
        }
        focusChangeListener = null
    }
    
    private fun createFocusChangeListener(moduleId: String): AudioManager.OnAudioFocusChangeListener {
        return AudioManager.OnAudioFocusChangeListener { focusChange ->
            when (focusChange) {
                AudioManager.AUDIOFOCUS_GAIN -> {
                    Timber.d("AudioFocus: GAIN for $moduleId")
                    _audioFocusState.value = AudioFocusState.GAIN
                }
                AudioManager.AUDIOFOCUS_LOSS,
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                    Timber.d("AudioFocus: LOSS for $moduleId")
                    _audioFocusState.value = AudioFocusState.LOSS
                    currentHolder = null
                }
                AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK -> {
                    Timber.d("AudioFocus: DUCK for $moduleId")
                    _audioFocusState.value = AudioFocusState.DUCK
                }
            }
        }
    }
    
    /**
     * 切换到蓝牙耳机（骨传导耳机）
     */
    fun switchToBluetoothSco(): Boolean {
        return try {
            if (!audioManager.isBluetoothScoAvailableOffCall) {
                Timber.w("AudioFocus: Bluetooth SCO not available")
                return false
            }
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            Timber.i("AudioFocus: Switched to Bluetooth SCO")
            true
        } catch (e: Exception) {
            Timber.e(e, "AudioFocus: Failed to switch to Bluetooth SCO")
            false
        }
    }
    
    /**
     * 停止蓝牙耳机音频
     */
    fun stopBluetoothSco() {
        try {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            Timber.i("AudioFocus: Stopped Bluetooth SCO")
        } catch (e: Exception) {
            Timber.e(e, "AudioFocus: Failed to stop Bluetooth SCO")
        }
    }
    
    /**
     * 检查是否连接了蓝牙耳机
     */
    fun isBluetoothHeadsetConnected(): Boolean {
        return audioManager.isBluetoothScoAvailableOffCall
    }
}

/**
 * 音频焦点状态
 */
enum class AudioFocusState {
    GAIN,   // 获得焦点
    LOSS,   // 失去焦点
    DUCK    // 降低音量（可被抢占）
}
