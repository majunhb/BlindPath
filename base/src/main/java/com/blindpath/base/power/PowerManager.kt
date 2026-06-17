package com.blindpath.base.power

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import timber.log.Timber

/**
 * 电源状态
 */
data class PowerState(
    val isCharging: Boolean,
    val batteryLevel: Int,          // 0-100
    val isPowerSaveMode: Boolean,
    val isLowBattery: Boolean,      // 低于20%
    val temperature: Float = 25f,   // 电池温度（摄氏度）
    val isOverheating: Boolean = false  // 是否过热
) {
    val batteryPercentage: Float
        get() = batteryLevel / 100f
    
    /**
     * 推荐的性能模式
     */
    val recommendedPerformanceMode: PerformanceMode
        get() {
            return when {
                isCharging -> PerformanceMode.HIGH
                isOverheating || isLowBattery || isPowerSaveMode -> PerformanceMode.LOW
                batteryLevel < 50 -> PerformanceMode.MEDIUM
                else -> PerformanceMode.HIGH
            }
        }
}

/**
 * 性能模式
 */
enum class PerformanceMode {
    LOW,       // 低性能：省电模式
    MEDIUM,    // 中等性能：平衡模式
    HIGH       // 高性能：全速模式
}

/**
 * 电量管理器
 * 监控电量状态并提供省电策略
 */
class PowerManager(
    private val context: Context
) {
    
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
    private val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
    
    /**
     * 获取当前电源状态
     */
    fun getPowerState(): PowerState {
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
        
        val isLowBattery = batteryLevel <= 20
        
        val temperature = getBatteryTemperature()
        val isOverheating = temperature > 45f
        
        return PowerState(
            isCharging = isCharging,
            batteryLevel = batteryLevel,
            isPowerSaveMode = isPowerSaveMode,
            isLowBattery = isLowBattery,
            temperature = temperature,
            isOverheating = isOverheating
        )
    }
    
    /**
     * 获取推荐的性能模式
     */
    fun getRecommendedPerformanceMode(): PerformanceMode {
        return getPowerState().recommendedPerformanceMode
    }
    
    /**
     * 检查是否应该进入省电模式
     */
    fun shouldEnterPowerSaveMode(): Boolean {
        val state = getPowerState()
        return state.isLowBattery || state.isPowerSaveMode
    }
    
    /**
     * 检查是否可以执行高功耗操作
     */
    fun canPerformHighPowerOperation(): Boolean {
        val state = getPowerState()
        return state.isCharging || state.batteryLevel > 30
    }
    
    /**
     * 获取电池温度
     */
    fun getBatteryTemperature(): Float {
        val batteryStatus = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val temperature = batteryStatus?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0
        return temperature / 10f // 转换为摄氏度
    }
    
    /**
     * 检查设备是否过热
     */
    fun isDeviceOverheating(): Boolean {
        return getBatteryTemperature() > 45f // 超过45度视为过热
    }
}
