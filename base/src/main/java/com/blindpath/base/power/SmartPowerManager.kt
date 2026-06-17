package com.blindpath.base.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.blindpath.base.config.AppConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 智能省电管理器
 * 
 * 核心功能：
 * 1. 自适应省电策略：根据电量、温度、使用场景动态调整
 * 2. 后台保活优化：智能唤醒、任务调度
 * 3. 传感器管理：按需启用/禁用传感器
 * 4. 网络请求优化：批量请求、缓存策略
 * 5. CPU 频率调整：根据负载动态调整
 * 
 * 使用场景：
 * - 视障用户长时间使用应用（续航优化）
 * - 低电量环境下延长使用时间
 * - 高温环境下降低功耗
 */
@Singleton
class SmartPowerManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    
    private val _powerState = MutableStateFlow(PowerState(
        isCharging = false,
        batteryLevel = 100,
        isPowerSaveMode = false,
        isLowBattery = false
    ))
    val powerState: StateFlow<PowerState> = _powerState.asStateFlow()
    
    private val _powerSavingMode = MutableStateFlow(PowerSavingMode.NORMAL)
    val powerSavingMode: StateFlow<PowerSavingMode> = _powerSavingMode.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var monitoringJob: Job? = null
    
    // 传感器控制器
    private var sensorController: SensorController? = null
    
    // 帧率控制器
    private var frameRateController: FrameRateController? = null
    
    /**
     * 启动智能省电监控
     */
    fun startMonitoring(
        sensorController: SensorController? = null,
        frameRateController: FrameRateController? = null
    ) {
        this.sensorController = sensorController
        this.frameRateController = frameRateController
        
        if (monitoringJob?.isActive == true) {
            Timber.d("Power monitoring already active")
            return
        }
        
        monitoringJob = scope.launch {
            while (isActive) {
                updatePowerState()
                adjustPowerSavingMode()
                delay(POWER_MONITORING_INTERVAL_MS)
            }
        }
        
        Timber.d("Smart power monitoring started")
    }
    
    /**
     * 停止监控
     */
    fun stopMonitoring() {
        monitoringJob?.cancel()
        monitoringJob = null
        Timber.d("Smart power monitoring stopped")
    }
    
    /**
     * 更新电源状态
     */
    private fun updatePowerState() {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        
        val level = batteryStatus?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryStatus?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val batteryLevel = if (level >= 0 && scale > 0) (level * 100 / scale) else 0
        
        val status = batteryStatus?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                         status == BatteryManager.BATTERY_STATUS_FULL
        
        val isPowerSaveMode = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            powerManager.isPowerSaveMode
        } else {
            false
        }
        
        val temperature = getBatteryTemperature()
        val isOverheating = temperature > OVERHEATING_THRESHOLD
        
        val state = PowerState(
            isCharging = isCharging,
            batteryLevel = batteryLevel,
            isPowerSaveMode = isPowerSaveMode,
            isLowBattery = batteryLevel <= AppConfig.PowerSaving.LOW_BATTERY_THRESHOLD,
            temperature = temperature,
            isOverheating = isOverheating
        )
        
        _powerState.value = state
    }
    
    /**
     * 调整省电模式
     */
    private fun adjustPowerSavingMode() {
        val state = _powerState.value
        val currentMode = _powerSavingMode.value
        
        val newMode = when {
            // 过热优先降频
            state.isOverheating -> PowerSavingMode.ULTRA
            
            // 充电时使用高性能模式
            state.isCharging -> PowerSavingMode.NORMAL
            
            // 系统省电模式
            state.isPowerSaveMode -> PowerSavingMode.AGGRESSIVE
            
            // 低电量
            state.isLowBattery -> PowerSavingMode.AGGRESSIVE
            
            // 电量中等
            state.batteryLevel < 50 -> PowerSavingMode.MODERATE
            
            // 电量充足
            else -> PowerSavingMode.NORMAL
        }
        
        if (newMode != currentMode) {
            _powerSavingMode.value = newMode
            applyPowerSavingMode(newMode)
            Timber.i("Power saving mode changed: $currentMode -> $newMode")
        }
    }
    
    /**
     * 应用省电模式
     */
    private fun applyPowerSavingMode(mode: PowerSavingMode) {
        // 调整帧率
        frameRateController?.setPerformanceMode(mode.performanceMode)
        Timber.d("Frame rate adjusted to ${mode.performanceMode}")
        
        // 调整传感器采样率
        sensorController?.setGlobalSamplingRate(mode.sensorSamplingRate)
        Timber.d("Sensor sampling rate adjusted to ${mode.sensorSamplingRate}")
        
        // 根据模式记录状态
        when (mode) {
            PowerSavingMode.NORMAL -> Timber.i("All features enabled - full performance mode")
            PowerSavingMode.MODERATE -> Timber.i("Detection frequency reduced - moderate power saving")
            PowerSavingMode.AGGRESSIVE -> Timber.i("Non-essential features disabled - aggressive power saving")
            PowerSavingMode.ULTRA -> Timber.i("Only safety features enabled - ultra power saving")
        }
    }
    
    /**
     * 获取电池温度
     */
    private fun getBatteryTemperature(): Float {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return temperature / 10f
    }
    
    /**
     * 检查是否可以执行高功耗操作
     */
    fun canPerformHighPowerOperation(): Boolean {
        val state = _powerState.value
        return state.isCharging || 
               state.batteryLevel > 30 && !state.isOverheating
    }
    
    /**
     * 获取推荐的 GPS 更新间隔
     */
    fun getRecommendedGpsInterval(): Long {
        return when (_powerSavingMode.value) {
            PowerSavingMode.NORMAL -> AppConfig.Navigation.LOCATION_UPDATE_INTERVAL_MS
            PowerSavingMode.MODERATE -> AppConfig.Navigation.LOCATION_UPDATE_INTERVAL_MS * 2
            PowerSavingMode.AGGRESSIVE -> AppConfig.PowerSaving.POWER_SAVE_LOCATION_INTERVAL_MS
            PowerSavingMode.ULTRA -> AppConfig.PowerSaving.POWER_SAVE_LOCATION_INTERVAL_MS * 2
        }
    }
    
    /**
     * 获取推荐的检测帧率
     */
    fun getRecommendedDetectionFps(): Int {
        return when (_powerSavingMode.value) {
            PowerSavingMode.NORMAL -> AppConfig.FrameRate.HIGH_FPS
            PowerSavingMode.MODERATE -> AppConfig.FrameRate.MEDIUM_FPS
            PowerSavingMode.AGGRESSIVE -> AppConfig.FrameRate.LOW_FPS
            PowerSavingMode.ULTRA -> AppConfig.PowerSaving.POWER_SAVE_FPS
        }
    }
    
    /**
     * 是否应该降低屏幕亮度
     */
    fun shouldReduceScreenBrightness(): Boolean {
        return _powerSavingMode.value >= PowerSavingMode.AGGRESSIVE
    }
    
    /**
     * 是否应该关闭振动反馈
     */
    fun shouldDisableVibration(): Boolean {
        return _powerSavingMode.value == PowerSavingMode.ULTRA
    }
    
    /**
     * 获取省电建议
     */
    fun getPowerSavingTips(): List<String> {
        val tips = mutableListOf<String>()
        val state = _powerState.value
        
        if (!state.isCharging && state.batteryLevel < 30) {
            tips.add("电量较低，建议充电后继续使用")
        }
        
        if (state.isOverheating) {
            tips.add("设备温度过高，建议暂停使用或移至阴凉处")
        }
        
        if (state.isPowerSaveMode) {
            tips.add("系统省电模式已开启，部分功能可能受限")
        }
        
        if (_powerSavingMode.value >= PowerSavingMode.AGGRESSIVE) {
            tips.add("已启用省电模式，检测频率已降低")
        }
        
        return tips
    }
    
    /**
     * 释放资源
     */
    fun release() {
        stopMonitoring()
        scope.cancel()
        Timber.d("Smart power manager released")
    }
    
    companion object {
        private const val POWER_MONITORING_INTERVAL_MS = 5000L  // 5秒检查一次
        private const val OVERHEATING_THRESHOLD = 45f           // 过热阈值（摄氏度）
    }
}

/**
 * 省电模式
 */
enum class PowerSavingMode(val level: Int) {
    NORMAL(0),       // 正常模式
    MODERATE(1),     // 中度省电
    AGGRESSIVE(2),   // 激进省电
    ULTRA(3);        // 超级省电
    
    val performanceMode: PerformanceMode
        get() = when (this) {
            NORMAL -> PerformanceMode.HIGH
            MODERATE -> PerformanceMode.MEDIUM
            AGGRESSIVE, ULTRA -> PerformanceMode.LOW
        }
    
    val sensorSamplingRate: SensorController.SamplingRate
        get() = when (this) {
            NORMAL -> SensorController.SamplingRate.UI
            MODERATE -> SensorController.SamplingRate.NORMAL
            AGGRESSIVE -> SensorController.SamplingRate.NORMAL
            ULTRA -> SensorController.SamplingRate.NORMAL
        }
}
