package com.blindpath.audio

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import timber.log.Timber
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 音频采集器
 * 
 * 功能：持续采集麦克风音频，转换为 PCM 数据流
 * 特点：
 * - 16kHz 采样率（Porcupine 要求）
 * - 16bit PCM 格式
 * - 单声道
 * - 环形缓冲区设计，避免内存抖动
 * 
 * @param context 应用上下文
 * @param bufferSizeMs 缓冲区大小（毫秒），默认 100ms
 */
class AudioRecorder(
    private val context: Context,
    private val bufferSizeMs: Int = 100
) {
    companion object {
        const val SAMPLE_RATE = 16000        // 采样率：16kHz（Porcupine 要求）
        const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO  // 单声道
        const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT // 16bit PCM
        const val FRAME_SIZE = 512           // 每帧样本数（32ms @ 16kHz）
    }

    private var audioRecord: AudioRecord? = null
    private var isRecording = false
    
    // 协程作用域
    private val recorderScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var recordingJob: Job? = null
    
    // PCM 数据流（ShortArray 格式，Porcupine 直接可用）
    private val _pcmFlow = MutableSharedFlow<ShortArray>(extraBufferCapacity = 10)
    val pcmFlow: SharedFlow<ShortArray> = _pcmFlow.asSharedFlow()
    
    // 原始字节流（用于调试）
    private val _byteFlow = MutableSharedFlow<ByteArray>(extraBufferCapacity = 10)
    val byteFlow: SharedFlow<ByteArray> = _byteFlow.asSharedFlow()

    /**
     * 检查录音权限
     * 
     * @return true 有权限，false 无权限
     */
    fun hasPermission(): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 开始录音
     * 
     * @return true 启动成功，false 启动失败
     */
    fun start(): Boolean {
        if (isRecording) {
            Timber.w("AudioRecorder: 已经在录音中")
            return true
        }

        if (!hasPermission()) {
            Timber.e("AudioRecorder: 没有录音权限")
            return false
        }

        // 计算缓冲区大小
        val minBufferSize = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            CHANNEL_CONFIG,
            AUDIO_FORMAT
        )
        
        // 使用更大的缓冲区，避免溢出
        val bufferSize = maxOf(minBufferSize, SAMPLE_RATE * 2 * bufferSizeMs / 1000)

        return try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Timber.e("AudioRecorder: AudioRecord 初始化失败")
                return false
            }

            audioRecord?.startRecording()
            isRecording = true
            
            // 启动采集协程
            recordingJob = recorderScope.launch {
                recordLoop()
            }
            
            Timber.i("AudioRecorder: 开始录音，采样率=$SAMPLE_RATE, 缓冲区=$bufferSize bytes")
            true
        } catch (e: SecurityException) {
            Timber.e(e, "AudioRecorder: 权限异常")
            false
        } catch (e: IllegalArgumentException) {
            Timber.e(e, "AudioRecorder: 参数异常")
            false
        }
    }

    /**
     * 停止录音
     */
    fun stop() {
        if (!isRecording) {
            return
        }

        isRecording = false
        recordingJob?.cancel()
        
        try {
            audioRecord?.stop()
            audioRecord?.release()
            Timber.i("AudioRecorder: 已停止")
        } catch (e: Exception) {
            Timber.e(e, "AudioRecorder: 停止异常")
        }
        
        audioRecord = null
    }

    /**
     * 释放资源
     */
    fun release() {
        stop()
        recorderScope.cancel()
        Timber.i("AudioRecorder: 已释放")
    }

    /**
     * 录音循环
     * 持续读取音频数据，转换为 ShortArray 并发送
     */
    private suspend fun recordLoop() {
        // 使用 512 样本的缓冲区（Porcupine 要求的帧长）
        val buffer = ShortArray(FRAME_SIZE)
        
        while (isRecording && isActive) {
            try {
                // 读取音频数据
                val readSize = audioRecord?.read(buffer, 0, FRAME_SIZE) ?: 0
                
                if (readSize > 0) {
                    // 复制数据（避免缓冲区被覆盖）
                    val pcmData = buffer.copyOf(readSize)
                    
                    // 发送到流
                    _pcmFlow.tryEmit(pcmData)
                    
                    // 同时发送字节格式（用于调试或其他用途）
                    val byteData = shortsToBytes(pcmData)
                    _byteFlow.tryEmit(byteData)
                } else if (readSize < 0) {
                    Timber.e("AudioRecorder: 读取错误，code=$readSize")
                    break
                }
            } catch (e: Exception) {
                Timber.e(e, "AudioRecorder: 读取异常")
                break
            }
        }
    }

    /**
     * ShortArray 转 ByteArray（小端序）
     */
    private fun shortsToBytes(shorts: ShortArray): ByteArray {
        val bytes = ByteArray(shorts.size * 2)
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        shorts.forEach { buffer.putShort(it) }
        return bytes
    }

    /**
     * 获取录音状态
     */
    fun isRecording(): Boolean = isRecording
}

/**
 * 音频采集配置
 */
data class AudioConfig(
    val sampleRate: Int = AudioRecorder.SAMPLE_RATE,
    val frameSize: Int = AudioRecorder.FRAME_SIZE,
    val bufferSizeMs: Int = 100
)
