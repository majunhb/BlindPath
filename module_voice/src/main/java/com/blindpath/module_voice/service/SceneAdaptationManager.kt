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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 多场景适配策略管理器
 * 
 * 负责根据当前环境自动切换场景配置，包括：
 * - 环境噪音检测
 * - 电量状态监控
 * - 蓝牙设备状态
 * - 自动场景切换
 * - 动态参数调整
 */
@Singleton
class SceneAdaptationManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    
    private val scope = CoroutineScope(Dispatchers.Default)
    private var monitoringJob: Job? = null
    
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
    
    // 音频录制器（用于噪音检测）
    private var noiseDetector: MediaRecorder? = null
    
    /**
     * 启动环境噪音检测
     */
    private fun startNoiseDetection() {
        if (noiseDetector != null) {
            Timber.w("Noise detection already running")
            return
        }
        
        try {
            // 检查录音权限
            if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
                Timber.w("RECORD_AUDIO permission not granted, skip noise detection")
                return
            }
            
            noiseDetector = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP)
                setAudioEncoder(MediaRecorder.AudioEncoder.AMR_NB)
                setOutputFile("${context.cacheDir.absolutePath}/noise_detect.tmp")
                
                prepare()
                start()
            }
            
            // 启动噪音检测协程
            scope.launch {
                while (isActive && noiseDetector != null) {
                    val amplitude = noiseDetector?.maxAmplitude ?: 0
                    val normalizedNoise = normalizeNoiseLevel(amplitude)
                    noiseLevel.set(normalizedNoise)
                    
                    delay(NOISE_DETECTION_INTERVAL_MS)
                }
            }
            
            Timber.d("Noise detection started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start noise detection, disable noise-based scene adaptation")
            noiseDetector = null
        }
    }
    
    /**
     * 停止场景监控
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        
        stopNoiseDetection()
        unregisterBluetoothReceiver()
        
        Timber.d("Scene monitoring stopped")
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
        
        Timber.i("Scene switched: $oldScene -> $sceneType")
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
        private const val NOISE_DETECTION_INTERVAL_MS = 1_000L
        
        private const val LOW_BATTERY_THRESHOLD = 20  // 20%
        private const val NOISE_LEVEL_LOW = 20
        private const val NOISE_LEVEL_HIGH = 60
    }
}
