package com.blindpath.module_voice.service

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.MediaRecorder
import android.os.BatteryManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.blindpath.module_voice.config.VoiceServiceConfig
import com.blindpath.module_voice.config.VoiceServiceConfig.SceneConfig
import com.blindpath.module_voice.config.VoiceServiceConfig.SceneType
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 多场景适配策略管理器
 * 
 * 负责根据当前环境自动切换场景配置，包括：
 * - 环境噪音检测（间歇式：每30秒检测1次，每次2秒）
 * - 电量状态监控
 * - 蓝牙设备状态
 * - 自动场景切换
 * - 动态参数调整
 * 
 * 噪音检测策略：
 * - 每隔 30 秒检测一次
 * - 每次只录制 2 秒
 * - 检测前检查麦克风是否被其他组件占用
 * - 检测完成后立即释放 MediaRecorder
 * - 避免与唤醒/ASR 音频采集冲突
 */
@Singleton
class SceneAdaptationManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val unifiedAudioScheduler: UnifiedAudioScheduler
) {
    
    private val scope = CoroutineScope(Dispatchers.Default)
    private var monitoringJob: Job? = null
    private var noiseDetectionJob: Job? = null
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    
    // 当前场景类型
    private val currentScene = AtomicReference(SceneType.NORMAL)
    
    // 环境噪音级别（0-100）
    private val noiseLevel = AtomicReference(0)
    
    // 是否连接蓝牙耳机
    private val isBluetoothHeadsetConnected = AtomicReference(false)
    
    // 电量百分比
    private val batteryLevel = AtomicReference(100)
    
    // 场景切换监听器
    private var sceneChangeListener: ((SceneType, SceneConfig) -> Unit)? = null
    
    // 音频录制器（用于噪音检测，间歇式使用）
    @Volatile private var noiseDetector: MediaRecorder? = null
    private val noiseDetectorLock = Any()
    
    /**
     * 启动场景监控
     */
    fun startMonitoring() {
        if (monitoringJob?.isActive == true) {
            Timber.w("Scene monitoring already running")
            return
        }
        
        monitoringJob = scope.launch {
            Timber.d("Scene monitoring started")
            
            // 注册蓝牙状态监听
            registerBluetoothReceiver()
            
            // 启动间歇式环境噪音检测
            startIntermittentNoiseDetection()
            
            // 定期检查场景变化
            while (isActive) {
                checkAndUpdateScene()
                delay(SCENE_CHECK_INTERVAL_MS)
            }
        }
    }
    
    /**
     * 启动间歇式噪音检测
     * 
     * 策略：
     * - 每隔 30 秒检测一次
     * - 每次只录制 2 秒
     * - 检测前检查麦克风是否被其他组件占用
     * - 检测完成后立即释放 MediaRecorder
     */
    private fun startIntermittentNoiseDetection() {
        if (noiseDetectionJob?.isActive == true) {
            return
        }
        
        noiseDetectionJob = scope.launch {
            Timber.d("Intermittent noise detection started")
            
            while (isActive) {
                try {
                    // 等待 30 秒间隔
                    delay(NOISE_DETECTION_INTERVAL_MS)
                    
                    // 执行单次噪音检测
                    performSingleNoiseDetection()
                    
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Noise detection error")
                    delay(5000) // 出错后等待 5 秒再重试
                }
            }
        }
    }
    
    /**
     * 执行单次噪音检测
     * 
     * 检测流程：
     * 1. 检查麦克风是否被占用
     * 2. 创建临时 MediaRecorder
     * 3. 录制 2 秒音频
     * 4. 计算平均振幅
     * 5. 立即释放 MediaRecorder
     */
    private suspend fun performSingleNoiseDetection() {
        withContext(Dispatchers.IO) {
            // 检查麦克风是否被占用
            if (!isMicrophoneAvailable()) {
                Timber.d("SceneAdaptation: Microphone in use by another component, skipping noise detection")
                return@withContext
            }
            
            var recorder: MediaRecorder? = null
            try {
                // 创建 MediaRecorder
                recorder = MediaRecorder().apply {
                    setAudioSource(MediaRecorder.AudioSource.MIC)
                    setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                    setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                    setOutputFile("${context.cacheDir.absolutePath}/noise_detect.tmp")
                    prepare()
                    start()
                }
                
                // 录制 2 秒，采样振幅
                val samples = mutableListOf<Int>()
                val sampleCount = 20 // 2秒，每100ms采样一次
                
                repeat(sampleCount) {
                    delay(100)
                    val amplitude = try {
                        recorder.maxAmplitude
                    } catch (e: Exception) {
                        0
                    }
                    if (amplitude > 0) {
                        samples.add(amplitude)
                    }
                }
                
                // 计算平均振幅
                if (samples.isNotEmpty()) {
                    val avgAmplitude = samples.average().toInt()
                    val normalizedNoise = normalizeNoiseLevel(avgAmplitude)
                    noiseLevel.set(normalizedNoise)
                    Timber.d("SceneAdaptation: Noise level detected: $normalizedNoise (avg amplitude: $avgAmplitude)")
                } else {
                    // 如果采样失败，使用默认值
                    Timber.w("SceneAdaptation: No valid amplitude samples collected")
                }
                
            } catch (e: Exception) {
                Timber.e(e, "SceneAdaptation: Failed to perform noise detection")
            } finally {
                // 立即释放 MediaRecorder
                try {
                    recorder?.apply {
                        stop()
                        release()
                    }
                } catch (e: Exception) {
                    Timber.e(e, "SceneAdaptation: Error releasing noise detector")
                }
            }
        }
    }
    
    /**
     * 检查麦克风是否可用
     * 
     * 检查 UnifiedAudioScheduler 中的活跃模块，
     * 如果 WAKE_WORD 或 ASR 正在使用麦克风，则返回 false
     */
    private fun isMicrophoneAvailable(): Boolean {
        val activeModules = unifiedAudioScheduler.getActiveModules()
        
        // 如果唤醒或 ASR 正在使用，返回不可用
        if (activeModules.contains(UnifiedAudioScheduler.AudioModule.WAKE_WORD) ||
            activeModules.contains(UnifiedAudioScheduler.AudioModule.ASR)) {
            return false
        }
        
        return true
    }
    
    /**
     * 停止场景监控
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        
        noiseDetectionJob?.cancel()
        noiseDetectionJob = null
        
        releaseNoiseDetector()
        unregisterBluetoothReceiver()
        
        Timber.d("Scene monitoring stopped")
    }
    
    /**
     * 释放噪音检测器
     */
    private fun releaseNoiseDetector() {
        synchronized(noiseDetectorLock) {
            try {
                noiseDetector?.apply {
                    stop()
                    release()
                }
                noiseDetector = null
            } catch (e: Exception) {
                Timber.e(e, "Failed to release noise detector")
            }
        }
    }
    
    /**
     * 归一化噪音级别（0-100）
     */
    private fun normalizeNoiseLevel(amplitude: Int): Int {
        // MediaRecorder.getMaxAmplitude() 返回 0-32767
        return (amplitude * 100 / 32767).coerceIn(0, 100)
    }
    
    /**
     * 设置场景切换监听器
     */
    fun setSceneChangeListener(listener: (SceneType, SceneConfig) -> Unit) {
        sceneChangeListener = listener
    }
    
    /**
     * 获取当前场景类型
     */
    fun getCurrentScene(): SceneType = currentScene.get()
    
    /**
     * 获取当前场景配置
     */
    fun getCurrentConfig(): SceneConfig {
        return VoiceServiceConfig.getSceneConfig(currentScene.get())
    }
    
    /**
     * 手动切换场景
     */
    fun switchScene(sceneType: SceneType) {
        if (currentScene.get() == sceneType) {
            return
        }
        
        val oldScene = currentScene.get()
        currentScene.set(sceneType)
        
        val config = VoiceServiceConfig.getSceneConfig(sceneType)
        sceneChangeListener?.invoke(sceneType, config)
        
        Timber.i("Scene switched: $oldScene → $sceneType")
    }
    
    /**
     * 检查并更新场景
     */
    private suspend fun checkAndUpdateScene() {
        withContext(Dispatchers.IO) {
            // 更新电量
            updateBatteryLevel()
            
            // 确定新场景
            val newScene = determineScene()
            
            // 如果场景变化，切换场景
            if (newScene != currentScene.get()) {
                withContext(Dispatchers.Main) {
                    switchScene(newScene)
                }
            }
        }
    }
    
    /**
     * 确定当前应该使用的场景
     */
    private fun determineScene(): SceneType {
        // 1. 低电量模式优先
        if (batteryLevel.get() <= LOW_BATTERY_THRESHOLD) {
            return SceneType.LOW_BATTERY
        }
        
        // 2. 蓝牙耳机连接
        if (isBluetoothHeadsetConnected.get()) {
            return SceneType.BLUETOOTH
        }
        
        // 3. 根据环境噪音判断
        val noise = noiseLevel.get()
        return when {
            noise >= NOISE_LEVEL_HIGH -> SceneType.OUTDOOR_NOISY
            noise <= NOISE_LEVEL_LOW -> SceneType.INDOOR_QUIET
            else -> SceneType.NORMAL
        }
    }
    
    /**
     * 更新电量信息
     */
    private fun updateBatteryLevel() {
        try {
            val level = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            } else {
                val filter = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
                val batteryStatus = context.registerReceiver(null, filter)
                val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
                val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
                
                if (level >= 0 && scale > 0) {
                    (level * 100) / scale
                } else {
                    100
                }
            }
            
            batteryLevel.set(level)
        } catch (e: Exception) {
            Timber.e(e, "Failed to get battery level")
        }
    }
    
    /**
     * 注册蓝牙状态监听
     */
    private fun registerBluetoothReceiver() {
        if (bluetoothAdapter == null) {
            Timber.d("Bluetooth not supported")
            return
        }
        
        val filter = IntentFilter().apply {
            addAction(BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bluetoothReceiver, filter)
        }
        
        // 检查初始蓝牙状态
        checkBluetoothHeadsetStatus()
        
        Timber.d("Bluetooth receiver registered")
    }
    
    /**
     * 注销蓝牙状态监听
     */
    private fun unregisterBluetoothReceiver() {
        try {
            context.unregisterReceiver(bluetoothReceiver)
            Timber.d("Bluetooth receiver unregistered")
        } catch (e: Exception) {
            Timber.e(e, "Failed to unregister bluetooth receiver")
        }
    }
    
    /**
     * 蓝牙状态接收器
     */
    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothHeadset.ACTION_AUDIO_STATE_CHANGED,
                BluetoothDevice.ACTION_ACL_CONNECTED,
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    checkBluetoothHeadsetStatus()
                }
            }
        }
    }
    
    /**
     * 检查蓝牙耳机连接状态
     */
    private fun checkBluetoothHeadsetStatus() {
        if (bluetoothAdapter == null) {
            return
        }
        
        if (ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Timber.w("BLUETOOTH_CONNECT permission not granted")
            return
        }
        
        try {
            val isConnected = bluetoothAdapter.getProfileProxy(
                context,
                object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                        if (profile == BluetoothProfile.HEADSET) {
                            val headset = proxy as BluetoothHeadset
                            val connectedDevices = headset.connectedDevices
                            val hasHeadset = connectedDevices.isNotEmpty()
                            
                            isBluetoothHeadsetConnected.set(hasHeadset)
                            Timber.d("Bluetooth headset connected: $hasHeadset")
                            
                            bluetoothAdapter.closeProfileProxy(BluetoothProfile.HEADSET, proxy)
                        }
                    }
                    
                    override fun onServiceDisconnected(profile: Int) {
                        // No-op
                    }
                },
                BluetoothProfile.HEADSET
            )
            
            if (!isConnected) {
                Timber.w("Failed to get Bluetooth headset profile")
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to check bluetooth headset status")
        }
    }
    
    /**
     * 获取当前环境信息
     */
    fun getEnvironmentInfo(): EnvironmentInfo {
        return EnvironmentInfo(
            sceneType = currentScene.get(),
            noiseLevel = noiseLevel.get(),
            batteryLevel = batteryLevel.get(),
            isBluetoothConnected = isBluetoothHeadsetConnected.get()
        )
    }
    
    /**
     * 环境信息数据类
     */
    data class EnvironmentInfo(
        val sceneType: SceneType,
        val noiseLevel: Int,
        val batteryLevel: Int,
        val isBluetoothConnected: Boolean
    )
    
    companion object {
        private const val SCENE_CHECK_INTERVAL_MS = 5_000L
        // 间歇式噪音检测：每 30 秒检测一次
        private const val NOISE_DETECTION_INTERVAL_MS = 30_000L
        
        private const val LOW_BATTERY_THRESHOLD = 20  // 20%
        private const val NOISE_LEVEL_LOW = 20
        private const val NOISE_LEVEL_HIGH = 60
    }
}
