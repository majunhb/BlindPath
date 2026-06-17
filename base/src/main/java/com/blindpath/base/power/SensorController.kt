package com.blindpath.base.power

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import timber.log.Timber

/**
 * 传感器管理器
 * 统一管理所有传感器的启用/禁用，优化电量消耗
 */
class SensorController(
    private val context: Context
) : SensorEventListener {
    
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    
    // 传感器状态
    private val activeSensors = mutableSetOf<Int>()
    private val sensorListeners = mutableMapOf<Int, MutableList<(SensorEvent) -> Unit>>()
    
    // 传感器类型
    object Sensors {
        const val ACCELEROMETER = Sensor.TYPE_ACCELEROMETER
        const val MAGNETIC_FIELD = Sensor.TYPE_MAGNETIC_FIELD
        const val GYROSCOPE = Sensor.TYPE_GYROSCOPE
        const val LIGHT = Sensor.TYPE_LIGHT
        const val PROXIMITY = Sensor.TYPE_PROXIMITY
        const val STEP_COUNTER = Sensor.TYPE_STEP_COUNTER
        const val STEP_DETECTOR = Sensor.TYPE_STEP_DETECTOR
    }
    
    /**
     * 传感器采样率配置
     */
    enum class SamplingRate(val sensorDelay: Int) {
        FASTEST(SensorManager.SENSOR_DELAY_FASTEST),      // 最快，最耗电
        GAME(SensorManager.SENSOR_DELAY_GAME),            // 游戏级
        UI(SensorManager.SENSOR_DELAY_UI),                // UI级
        NORMAL(SensorManager.SENSOR_DELAY_NORMAL)         // 正常，最省电
    }
    
    private var currentSamplingRate = SamplingRate.NORMAL
    
    /**
     * 注册传感器监听
     * @param sensorType 传感器类型
     * @param listener 数据回调
     * @param samplingRate 采样率
     */
    fun registerSensor(
        sensorType: Int,
        listener: (SensorEvent) -> Unit,
        samplingRate: SamplingRate = SamplingRate.NORMAL
    ): Boolean {
        val sensor = sensorManager.getDefaultSensor(sensorType)
        if (sensor == null) {
            Timber.w("Sensor not available: $sensorType")
            return false
        }
        
        // 添加监听器
        sensorListeners.getOrPut(sensorType) { mutableListOf() }.add(listener)
        
        // 如果传感器未激活，注册到系统
        if (sensorType !in activeSensors) {
            val success = sensorManager.registerListener(
                this,
                sensor,
                samplingRate.sensorDelay
            )
            
            if (success) {
                activeSensors.add(sensorType)
                Timber.d("Sensor registered: $sensorType with rate $samplingRate")
            } else {
                Timber.e("Failed to register sensor: $sensorType")
                return false
            }
        }
        
        return true
    }
    
    /**
     * 注销传感器监听
     * @param sensorType 传感器类型
     * @param listener 要移除的监听器，如果为null则移除所有
     */
    fun unregisterSensor(sensorType: Int, listener: ((SensorEvent) -> Unit)? = null) {
        val listeners = sensorListeners[sensorType] ?: return
        
        if (listener != null) {
            listeners.remove(listener)
        } else {
            listeners.clear()
        }
        
        // 如果没有监听器了，注销传感器
        if (listeners.isEmpty()) {
            val sensor = sensorManager.getDefaultSensor(sensorType)
            if (sensor != null) {
                sensorManager.unregisterListener(this, sensor)
            }
            activeSensors.remove(sensorType)
            sensorListeners.remove(sensorType)
            Timber.d("Sensor unregistered: $sensorType")
        }
    }
    
    /**
     * 注销所有传感器
     */
    fun unregisterAllSensors() {
        sensorManager.unregisterListener(this)
        activeSensors.clear()
        sensorListeners.clear()
        Timber.d("All sensors unregistered")
    }
    
    /**
     * 设置全局采样率
     */
    fun setGlobalSamplingRate(rate: SamplingRate) {
        if (currentSamplingRate == rate) return
        
        currentSamplingRate = rate
        
        // 重新注册所有活动传感器
        val activeList = activeSensors.toList()
        activeList.forEach { sensorType ->
            val sensor = sensorManager.getDefaultSensor(sensorType)
            if (sensor != null) {
                sensorManager.unregisterListener(this, sensor)
                sensorManager.registerListener(this, sensor, rate.sensorDelay)
            }
        }
        
        Timber.d("Global sampling rate set to: $rate")
    }
    
    /**
     * 获取当前活动传感器数量
     */
    fun getActiveSensorCount(): Int = activeSensors.size
    
    /**
     * 检查传感器是否可用
     */
    fun isSensorAvailable(sensorType: Int): Boolean {
        return sensorManager.getDefaultSensor(sensorType) != null
    }
    
    /**
     * 进入省电模式
     */
    fun enterPowerSaveMode() {
        setGlobalSamplingRate(SamplingRate.NORMAL)
        Timber.d("Entered power save mode for sensors")
    }
    
    /**
     * 退出省电模式
     */
    fun exitPowerSaveMode() {
        setGlobalSamplingRate(SamplingRate.UI)
        Timber.d("Exited power save mode for sensors")
    }
    
    // SensorEventListener 实现
    
    override fun onSensorChanged(event: SensorEvent) {
        val listeners = sensorListeners[event.sensor.type] ?: return
        listeners.forEach { listener ->
            try {
                listener(event)
            } catch (e: Exception) {
                Timber.e(e, "Error in sensor listener")
            }
        }
    }
    
    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        Timber.d("Sensor accuracy changed: ${sensor?.name} -> $accuracy")
    }
}

/**
 * 设备方向计算器
 * 使用加速度计和磁力计计算设备朝向
 */
class DeviceOrientationCalculator(
    context: Context,
    private val onOrientationChanged: (azimuth: Float, pitch: Float, roll: Float) -> Unit
) {
    private val sensorController = SensorController(context)
    
    private val accelerometerData = FloatArray(3)
    private val magneticFieldData = FloatArray(3)
    private val rotationMatrix = FloatArray(9)
    private val orientationValues = FloatArray(3)
    
    private var hasAccelerometerData = false
    private var hasMagneticFieldData = false
    
    /**
     * 开始监听方向变化
     */
    fun start() {
        sensorController.registerSensor(
            SensorController.Sensors.ACCELEROMETER,
            listener = { event ->
                System.arraycopy(event.values, 0, accelerometerData, 0, 3)
                hasAccelerometerData = true
                updateOrientation()
            }
        )
        
        sensorController.registerSensor(
            SensorController.Sensors.MAGNETIC_FIELD,
            listener = { event ->
                System.arraycopy(event.values, 0, magneticFieldData, 0, 3)
                hasMagneticFieldData = true
                updateOrientation()
            }
        )
    }
    
    /**
     * 停止监听
     */
    fun stop() {
        sensorController.unregisterAllSensors()
    }
    
    private fun updateOrientation() {
        if (!hasAccelerometerData || !hasMagneticFieldData) return
        
        val success = SensorManager.getRotationMatrix(
            rotationMatrix,
            null,
            accelerometerData,
            magneticFieldData
        )
        
        if (success) {
            SensorManager.getOrientation(rotationMatrix, orientationValues)
            
            // azimuth: 方位角（绕Z轴旋转），pitch: 俯仰角，roll: 翻滚角
            onOrientationChanged(
                Math.toDegrees(orientationValues[0].toDouble()).toFloat(),
                Math.toDegrees(orientationValues[1].toDouble()).toFloat(),
                Math.toDegrees(orientationValues[2].toDouble()).toFloat()
            )
        }
    }
}
