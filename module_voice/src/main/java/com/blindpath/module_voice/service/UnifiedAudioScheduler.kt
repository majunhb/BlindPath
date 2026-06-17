package com.blindpath.module_voice.service

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityManager
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.PriorityBlockingQueue
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局音频统一调度中心
 * 
 * 核心职责：
 * 1. 统一接管所有麦克风、音频焦点资源
 * 2. 智能兼容系统读屏服务（TalkBack/旁白）
 * 3. 协调唤醒、ASR、TTS 三方模块优先级
 * 4. 动态监听蓝牙外设并自动切换音频路由
 * 5. 实现音频输出避让机制
 * 
 * 设计原则：
 * - 读屏服务优先，永不抢占
 * - 唤醒模块最高优先级，后台常驻
 * - 播报时不切断收音，仅降低输出音量
 * - 外设切换实时适配
 */
@Singleton
class UnifiedAudioScheduler @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val accessibilityManager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    
    // 音频模块优先级定义（数值越大优先级越高）
    enum class AudioModule(val priority: Int, val description: String) {
        TALKBACK(100, "系统读屏服务"),         // 最高优先级，永不抢占
        WAKE_WORD(90, "语音唤醒"),             // 次高优先级，后台常驻
        NAVIGATION_TTS(70, "导航播报"),       // 导航语音播报
        ASR(60, "语音识别"),                   // 语音识别
        SYSTEM_TTS(50, "系统播报"),           // 系统提示音
        MEDIA(30, "媒体音频")                  // 背景音乐等
    }
    
    // 音频场景定义
    enum class AudioScene {
        FOREGROUND,        // 前台运行
        BACKGROUND,        // 后台运行
        LOCK_SCREEN,       // 锁屏状态
        BLUETOOTH_ACTIVE,  // 蓝牙耳机连接
        TALKBACK_ACTIVE    // 读屏服务开启
    }
    
    // 音频资源状态
    data class AudioState(
        val currentHolder: AudioModule? = null,
        val isMicrophoneAvailable: Boolean = true,
        val isTalkBackActive: Boolean = false,
        val isBluetoothConnected: Boolean = false,
        val currentScene: AudioScene = AudioScene.FOREGROUND,
        val activeModules: Set<AudioModule> = emptySet()
    )
    
    private val _audioState = MutableStateFlow(AudioState())
    val audioState: StateFlow<AudioState> = _audioState.asStateFlow()
    
    // 模块请求队列（按优先级排序）
    private val moduleQueue = PriorityBlockingQueue<ModuleRequest>(11, compareByDescending { it.module.priority })
    
    // 当前活跃的模块持有者
    private val activeHolders = ConcurrentHashMap<AudioModule, ModuleRequest>()
    
    // 音频焦点请求
    private var currentFocusRequest: android.media.AudioFocusRequest? = null
    
    // 蓝牙设备监听器
    private var audioDeviceCallback: AudioDeviceCallback? = null
    
    // 协程作用域
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    
    // 读屏服务监听
    private var talkBackListener: AccessibilityManager.TouchExplorationStateChangeListener? = null
    
    data class ModuleRequest(
        val module: AudioModule,
        val onRequestGranted: (() -> Unit)? = null,
        val onRequestLost: (() -> Unit)? = null,
        val timestamp: Long = System.currentTimeMillis()
    )
    
    init {
        initializeScheduler()
    }
    
    private fun initializeScheduler() {
        Timber.i("UnifiedAudioScheduler: Initializing")
        
        // 检测读屏服务状态
        detectTalkBackStatus()
        
        // 监听蓝牙设备变化
        registerAudioDeviceCallback()
        
        // 监听音频焦点变化
        setupAudioFocusListener()
        
        // 启动调度协程
        startSchedulerLoop()
        
        Timber.i("UnifiedAudioScheduler: Initialized - TalkBack=${_audioState.value.isTalkBackActive}, Bluetooth=${_audioState.value.isBluetoothConnected}")
    }
    
    /**
     * 请求音频资源
     * 
     * @param module 请求的模块
     * @param onGranted 获得资源回调
     * @param onLost 失去资源回调
     * @return 是否成功获得资源
     */
    fun requestAudioResource(
        module: AudioModule,
        onGranted: (() -> Unit)? = null,
        onLost: (() -> Unit)? = null
    ): Boolean {
        Timber.d("UnifiedAudioScheduler: ${module.description} requesting audio resource")
        
        // 读屏服务永远优先
        if (_audioState.value.isTalkBackActive && module != AudioModule.TALKBACK) {
            Timber.d("UnifiedAudioScheduler: TalkBack active, granting shared access to ${module.description}")
            // 共享模式：不独占，允许同时使用
            val request = ModuleRequest(module, onGranted, onLost)
            activeHolders[module] = request
            onGranted?.invoke()
            return true
        }
        
        // 检查当前持有者优先级
        val currentHolder = _audioState.value.currentHolder
        if (currentHolder != null && currentHolder.priority > module.priority) {
            Timber.w("UnifiedAudioScheduler: ${module.description} rejected - ${currentHolder.description} has higher priority")
            return false
        }
        
        // 创建请求并加入队列
        val request = ModuleRequest(module, onGranted, onLost)
        moduleQueue.offer(request)
        
        // 触发调度
        scheduleAudioResources()
        
        return true
    }
    
    /**
     * 释放音频资源
     */
    fun releaseAudioResource(module: AudioModule) {
        Timber.d("UnifiedAudioScheduler: ${module.description} releasing audio resource")
        
        activeHolders.remove(module)
        
        // 触发重新调度
        scheduleAudioResources()
    }
    
    /**
     * 调度音频资源分配
     */
    private fun scheduleAudioResources() {
        // 清理已释放的请求
        val activeRequests = moduleQueue.filter { activeHolders.containsKey(it.module) }.toMutableList()
        
        // 找出最高优先级的请求
        val highestPriorityRequest = moduleQueue.peek() ?: return
        
        // 检查是否需要切换持有者
        val currentHolder = _audioState.value.currentHolder
        if (currentHolder != null && currentHolder != highestPriorityRequest.module && currentHolder != AudioModule.TALKBACK) {
            // 通知旧持有者失去资源
            activeHolders[currentHolder]?.onRequestLost?.invoke()
            activeHolders.remove(currentHolder)
            
            Timber.i("UnifiedAudioScheduler: Switching from ${currentHolder.description} to ${highestPriorityRequest.module.description}")
        }
        
        // 授予新持有者资源
        if (!activeHolders.containsKey(highestPriorityRequest.module)) {
            activeHolders[highestPriorityRequest.module] = highestPriorityRequest
            highestPriorityRequest.onRequestGranted?.invoke()
            
            Timber.i("UnifiedAudioScheduler: Resource granted to ${highestPriorityRequest.module.description}")
        }
        
        // 更新状态
        _audioState.update { state ->
            state.copy(
                currentHolder = highestPriorityRequest.module,
                activeModules = activeHolders.keys.toSet()
            )
        }
    }
    
    /**
     * 检测读屏服务状态
     */
    private fun detectTalkBackStatus() {
        val isTalkBackActive = accessibilityManager.isTouchExplorationEnabled
        
        _audioState.update { it.copy(isTalkBackActive = isTalkBackActive) }
        
        // 监听读屏服务状态变化
        talkBackListener = AccessibilityManager.TouchExplorationStateChangeListener { enabled ->
            val isActive = enabled
            
            _audioState.update { it.copy(isTalkBackActive = isActive) }
            
            Timber.i("UnifiedAudioScheduler: TalkBack status changed - active=$isActive")
            
            // 读屏服务状态变化时重新调度
            if (isActive) {
                // 读屏开启，切换到共享模式
                Timber.i("UnifiedAudioScheduler: Switching to shared audio mode (TalkBack)")
            } else {
                // 读屏关闭，恢复独占模式
                Timber.i("UnifiedAudioScheduler: Switching to exclusive audio mode")
            }
            
            scheduleAudioResources()
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val listener = talkBackListener
            if (listener != null) {
                accessibilityManager.addTouchExplorationStateChangeListener(listener)
            }
        }
    }
    
    /**
     * 注册蓝牙设备监听
     */
    private fun registerAudioDeviceCallback() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        
        audioDeviceCallback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                checkBluetoothDevices()
            }
            
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                checkBluetoothDevices()
            }
        }
        
        audioManager.registerAudioDeviceCallback(audioDeviceCallback, Handler(Looper.getMainLooper()))
        
        // 初始检查
        checkBluetoothDevices()
    }
    
    /**
     * 检查蓝牙设备连接状态
     */
    private fun checkBluetoothDevices() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return
        
        val devices = audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS or AudioManager.GET_DEVICES_OUTPUTS)
        val hasBluetooth = devices.any { 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO || 
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
            it.type == AudioDeviceInfo.TYPE_HEARING_AID
        }
        
        val wasConnected = _audioState.value.isBluetoothConnected
        _audioState.update { it.copy(isBluetoothConnected = hasBluetooth) }
        
        if (hasBluetooth != wasConnected) {
            Timber.i("UnifiedAudioScheduler: Bluetooth device ${if (hasBluetooth) "connected" else "disconnected"}")
            
            if (hasBluetooth) {
                // 蓝牙设备连接，切换音频路由
                switchToBluetooth()
            } else {
                // 蓝牙设备断开，恢复默认音频路由
                switchToDefault()
            }
            
            _audioState.update { 
                it.copy(currentScene = if (hasBluetooth) AudioScene.BLUETOOTH_ACTIVE else AudioScene.FOREGROUND)
            }
        }
    }
    
    /**
     * 切换到蓝牙音频
     */
    fun switchToBluetooth(): Boolean {
        return try {
            if (!audioManager.isBluetoothScoAvailableOffCall) {
                Timber.w("UnifiedAudioScheduler: Bluetooth SCO not available")
                return false
            }
            
            audioManager.startBluetoothSco()
            audioManager.isBluetoothScoOn = true
            audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
            
            Timber.i("UnifiedAudioScheduler: Switched to Bluetooth SCO audio")
            true
        } catch (e: Exception) {
            Timber.e(e, "UnifiedAudioScheduler: Failed to switch to Bluetooth")
            false
        }
    }
    
    /**
     * 切换到默认音频
     */
    fun switchToDefault() {
        try {
            audioManager.isBluetoothScoOn = false
            audioManager.stopBluetoothSco()
            audioManager.mode = AudioManager.MODE_NORMAL
            
            Timber.i("UnifiedAudioScheduler: Switched to default audio")
        } catch (e: Exception) {
            Timber.e(e, "UnifiedAudioScheduler: Failed to switch to default audio")
        }
    }
    
    /**
     * 设置音频焦点监听
     */
    private fun setupAudioFocusListener() {
        // 音频焦点变化会通过 AudioManager.OnAudioFocusChangeListener 回调
        // 这里使用共享监听器处理
    }
    
    /**
     * 启动调度循环
     */
    private fun startSchedulerLoop() {
        scope.launch {
            // 定期检查音频状态
            while (isActive) {
                delay(5000) // 每5秒检查一次
                
                // 检查读屏服务状态
                val isTalkBackActive = accessibilityManager.isTouchExplorationEnabled
                if (isTalkBackActive != _audioState.value.isTalkBackActive) {
                    _audioState.update { it.copy(isTalkBackActive = isTalkBackActive) }
                    Timber.d("UnifiedAudioScheduler: TalkBack status updated - $isTalkBackActive")
                }
                
                // 检查蓝牙设备
                checkBluetoothDevices()
            }
        }
    }
    
    /**
     * TTS 播报避让机制
     * 
     * 播报时：
     * - 不切断收音输入
     * - 仅降低输出音量（DUCK）
     * - 维持唤醒监听状态
     */
    fun enableTtsDucking() {
        Timber.d("UnifiedAudioScheduler: Enabling TTS ducking mode")
        
        // 请求音频焦点但使用 DUCK 模式
        // 这样不会打断其他音频输入
        val request = android.media.AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setOnAudioFocusChangeListener { focusChange: Int ->
                when (focusChange) {
                    AudioManager.AUDIOFOCUS_LOSS -> {
                        Timber.d("UnifiedAudioScheduler: TTS lost audio focus")
                    }
                    AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                        Timber.d("UnifiedAudioScheduler: TTS temporarily lost audio focus")
                    }
                    AudioManager.AUDIOFOCUS_GAIN -> {
                        Timber.d("UnifiedAudioScheduler: TTS regained audio focus")
                    }
                }
            }
            .build()
        
        currentFocusRequest = request
        val result = audioManager.requestAudioFocus(request)
        
        Timber.d("UnifiedAudioScheduler: TTS ducking request result: $result")
    }
    
    /**
     * 停止 TTS 播报避让
     */
    fun disableTtsDucking() {
        Timber.d("UnifiedAudioScheduler: Disabling TTS ducking mode")
        
        currentFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        currentFocusRequest = null
    }
    
    /**
     * 获取当前音频场景
     */
    fun getCurrentScene(): AudioScene {
        return _audioState.value.currentScene
    }
    
    /**
     * 检查读屏服务是否激活
     */
    fun isTalkBackActive(): Boolean {
        return _audioState.value.isTalkBackActive
    }
    
    /**
     * 检查蓝牙是否连接
     */
    fun isBluetoothActive(): Boolean {
        return _audioState.value.isBluetoothConnected
    }
    
    /**
     * 检查模块是否有音频资源
     */
    fun hasResource(module: AudioModule): Boolean {
        return activeHolders.containsKey(module)
    }
    
    /**
     * 获取所有活跃模块
     */
    fun getActiveModules(): Set<AudioModule> {
        return activeHolders.keys.toSet()
    }
    
    /**
     * 释放所有资源
     */
    fun release() {
        Timber.i("UnifiedAudioScheduler: Releasing all resources")
        
        // 注销监听器
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            audioDeviceCallback?.let {
                audioManager.unregisterAudioDeviceCallback(it)
            }
        }
        
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.KITKAT) {
            val listener = talkBackListener
            if (listener != null) {
                accessibilityManager.removeTouchExplorationStateChangeListener(listener)
            }
        }
        
        // 释放音频焦点
        currentFocusRequest?.let {
            audioManager.abandonAudioFocusRequest(it)
        }
        
        // 停止蓝牙音频
        switchToDefault()
        
        // 清空队列
        moduleQueue.clear()
        activeHolders.clear()
        
        // 取消协程
        scope.cancel()
        
        Timber.i("UnifiedAudioScheduler: Released")
    }
}
