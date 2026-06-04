package com.blindpath.module_voice.service

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 蓝牙外设动态监听器
 * 
 * 核心职责：
 * 1. 实时监听蓝牙耳机/骨传导耳机连接状态
 * 2. 自动切换音频路由
 * 3. 处理设备断开后的回退逻辑
 * 4. 适配不同类型蓝牙设备
 * 
 * 支持设备类型：
 * - 普通蓝牙耳机 (A2DP)
 * - 骨传导耳机
 * - 车载蓝牙
 * - 助听器
 */
@Singleton
class BluetoothDeviceMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
    private val unifiedAudioScheduler: UnifiedAudioScheduler
) {
    // 蓝牙设备状态
    data class BluetoothState(
        val isConnected: Boolean = false,
        val deviceName: String? = null,
        val deviceType: DeviceType = DeviceType.UNKNOWN,
        val supportsSco: Boolean = false,
        val batteryLevel: Int = -1
    )
    
    enum class DeviceType {
        HEADSET,           // 普通蓝牙耳机
        BONE_CONDUCTION,   // 骨传导耳机
        HEARING_AID,       // 助听器
        CAR_AUDIO,         // 车载蓝牙
        UNKNOWN            // 未知类型
    }
    
    private val _bluetoothState = MutableStateFlow(BluetoothState())
    val bluetoothState: StateFlow<BluetoothState> = _bluetoothState.asStateFlow()
    
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    
    private var audioDeviceCallback: AudioDeviceCallback? = null
    private var bluetoothReceiver: BroadcastReceiver? = null
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    @Volatile
    private var isMonitoring = false
    
    /**
     * 开始监听蓝牙设备
     */
    fun startMonitoring() {
        if (isMonitoring) return
        
        Timber.i("BluetoothDeviceMonitor: Starting monitoring")
        
        // 注册音频设备回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            registerAudioDeviceCallback()
        }
        
        // 注册蓝牙广播接收器
        registerBluetoothReceiver()
        
        // 初始检查
        checkCurrentBluetoothState()
        
        isMonitoring = true
        Timber.i("BluetoothDeviceMonitor: Monitoring started")
    }
    
    /**
     * 停止监听
     */
    fun stopMonitoring() {
        if (!isMonitoring) return
        
        Timber.i("BluetoothDeviceMonitor: Stopping monitoring")
        
        // 注销音频设备回调
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback?.let {
                audioManager.unregisterAudioDeviceCallback(it)
            }
        }
        
        // 注销蓝牙广播接收器
        try {
            bluetoothReceiver?.let {
                context.unregisterReceiver(it)
            }
        } catch (e: Exception) {
            Timber.w(e, "BluetoothDeviceMonitor: Failed to unregister receiver")
        }
        
        isMonitoring = false
        Timber.i("BluetoothDeviceMonitor: Monitoring stopped")
    }
    
    /**
     * 注册音频设备回调
     */
    private fun registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                Timber.d("BluetoothDeviceMonitor: Audio devices added - ${addedDevices.size}")
                checkBluetoothDevices(addedDevices.toList())
            }
            
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                Timber.d("BluetoothDeviceMonitor: Audio devices removed - ${removedDevices.size}")
                checkBluetoothRemoval(removedDevices.toList())
            }
        }
        
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
    }
    
    /**
     * 注册蓝牙广播接收器
     */
    private fun registerBluetoothReceiver() {
        bluetoothReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let { onBluetoothDeviceConnected(it) }
                    }
                    
                    BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                        val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.let { onBluetoothDeviceDisconnected(it) }
                    }
                    
                    BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                        val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)
                        onHeadsetConnectionStateChanged(state)
                    }
                    
                    // 电池电量变化 - 只在 Android P+ 处理
                    "android.bluetooth.device.action.BATTERY_LEVEL_CHANGED" -> {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                            } else {
                                @Suppress("DEPRECATION")
                                intent?.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                            }
                            val level = intent?.getIntExtra("android.bluetooth.device.extra.BATTERY_LEVEL", -1) ?: -1
                            device?.let { onBatteryLevelChanged(it, level) }
                        }
                    }
                }
            }
        }
        
        val filter = IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                addAction("android.bluetooth.device.action.BATTERY_LEVEL_CHANGED")
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(bluetoothReceiver, filter)
        }
    }
    
    /**
     * 检查当前蓝牙状态
     */
    private fun checkCurrentBluetoothState() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
            checkBluetoothDevices(devices.toList())
        }
        
        // 检查蓝牙适配器状态
        bluetoothAdapter?.let { adapter ->
            if (adapter.isEnabled) {
                val bondedDevices = adapter.bondedDevices
                Timber.d("BluetoothDeviceMonitor: Bonded devices - ${bondedDevices.size}")
            }
        }
    }
    
    /**
     * 检查蓝牙设备列表
     */
    private fun checkBluetoothDevices(devices: List<AudioDeviceInfo>) {
        val bluetoothDevices = devices.filter { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_HEARING_AID
        }
        
        if (bluetoothDevices.isNotEmpty()) {
            val device = bluetoothDevices.first()
            val deviceType = detectDeviceType(device)
            
            _bluetoothState.update { state ->
                state.copy(
                    isConnected = true,
                    deviceName = device.productName?.toString() ?: "未知设备",
                    deviceType = deviceType,
                    supportsSco = true
                )
            }
            
            Timber.i("BluetoothDeviceMonitor: Bluetooth device detected - ${device.productName} (type: $deviceType)")
            
            // 自动切换到蓝牙音频
            unifiedAudioScheduler.switchToBluetooth()
        }
    }
    
    /**
     * 检查蓝牙设备移除
     */
    private fun checkBluetoothRemoval(devices: List<AudioDeviceInfo>) {
        val hasBluetooth = devices.any { device ->
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
            device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            device.type == AudioDeviceInfo.TYPE_HEARING_AID
        }
        
        if (!hasBluetooth && _bluetoothState.value.isConnected) {
            _bluetoothState.update { state ->
                BluetoothState(isConnected = false)
            }
            
            Timber.i("BluetoothDeviceMonitor: Bluetooth device removed")
            
            // 切换回默认音频
            unifiedAudioScheduler.switchToDefault()
        }
    }
    
    /**
     * 检测设备类型
     */
    private fun detectDeviceType(device: AudioDeviceInfo): DeviceType {
        val productName = device.productName?.toString()?.lowercase() ?: ""
        
        return when {
            productName.contains("bone") || productName.contains("骨传导") -> {
                DeviceType.BONE_CONDUCTION
            }
            productName.contains("hearing") || productName.contains("助听") -> {
                DeviceType.HEARING_AID
            }
            productName.contains("car") || productName.contains("车载") -> {
                DeviceType.CAR_AUDIO
            }
            device.type == AudioDeviceInfo.TYPE_HEARING_AID -> {
                DeviceType.HEARING_AID
            }
            else -> {
                DeviceType.HEADSET
            }
        }
    }
    
    /**
     * 蓝牙设备连接回调
     */
    private fun onBluetoothDeviceConnected(device: BluetoothDevice) {
        Timber.i("BluetoothDeviceMonitor: Device connected - ${device.name}")
        
        val deviceType = detectDeviceTypeFromBluetoothDevice(device)
        
        _bluetoothState.update { state ->
            state.copy(
                isConnected = true,
                deviceName = device.name,
                deviceType = deviceType,
                supportsSco = true
            )
        }
        
        // 切换到蓝牙音频
        scope.launch {
            delay(500) // 等待设备稳定连接
            unifiedAudioScheduler.switchToBluetooth()
        }
    }
    
    /**
     * 蓝牙设备断开回调
     */
    private fun onBluetoothDeviceDisconnected(device: BluetoothDevice) {
        Timber.i("BluetoothDeviceMonitor: Device disconnected - ${device.name}")
        
        _bluetoothState.update { state ->
            BluetoothState(isConnected = false)
        }
        
        // 切换回默认音频
        unifiedAudioScheduler.switchToDefault()
    }
    
    /**
     * 耳机连接状态变化
     */
    private fun onHeadsetConnectionStateChanged(state: Int) {
        when (state) {
            BluetoothHeadset.STATE_CONNECTED -> {
                Timber.d("BluetoothDeviceMonitor: Headset connected")
            }
            BluetoothHeadset.STATE_DISCONNECTED -> {
                Timber.d("BluetoothDeviceMonitor: Headset disconnected")
            }
        }
    }
    
    /**
     * 电池电量变化
     */
    private fun onBatteryLevelChanged(device: BluetoothDevice, level: Int) {
        Timber.d("BluetoothDeviceMonitor: Battery level changed - ${device.name}: $level%")
        
        _bluetoothState.update { state ->
            state.copy(batteryLevel = level)
        }
        
        // 低电量警告
        if (level in 1..20) {
            Timber.w("BluetoothDeviceMonitor: Low battery warning - ${device.name}: $level%")
        }
    }
    
    /**
     * 从 BluetoothDevice 检测设备类型
     */
    private fun detectDeviceTypeFromBluetoothDevice(device: BluetoothDevice): DeviceType {
        val name = device.name?.lowercase() ?: ""
        
        return when {
            name.contains("bone") || name.contains("骨传导") -> DeviceType.BONE_CONDUCTION
            name.contains("hearing") || name.contains("助听") -> DeviceType.HEARING_AID
            name.contains("car") || name.contains("车载") -> DeviceType.CAR_AUDIO
            else -> DeviceType.HEADSET
        }
    }
    
    /**
     * 是否连接蓝牙设备
     */
    fun isBluetoothConnected(): Boolean {
        return _bluetoothState.value.isConnected
    }
    
    /**
     * 获取当前设备类型
     */
    fun getCurrentDeviceType(): DeviceType {
        return _bluetoothState.value.deviceType
    }
    
    /**
     * 获取设备名称
     */
    fun getDeviceName(): String? {
        return _bluetoothState.value.deviceName
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stopMonitoring()
        scope.cancel()
    }
}
