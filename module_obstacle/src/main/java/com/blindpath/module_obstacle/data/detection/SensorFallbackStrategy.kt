package com.blindpath.module_obstacle.data.detection

import android.content.Context
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.blindpath.base.reliability.ReliabilityLogger
import com.blindpath.module_obstacle.domain.model.BoundingBox
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.Direction
import com.blindpath.module_obstacle.domain.model.ObstacleType
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 传感器兜底策略 - 基于设备传感器的最后手段
 *
 * 使用 proximity sensor 和 accelerometer 推断最近障碍物。
 * 永远不会抛出异常，始终返回结果。
 * 当 AI 检测和辅助检测都失败时，作为最终兜底。
 */
@Singleton
class SensorFallbackStrategy @Inject constructor(
    @ApplicationContext private val context: Context
) : DetectionStrategy, SensorEventListener {

    override val name: String = "sensor_fallback"

    override val isAvailable: Boolean = true  // 传感器始终可用

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private var lastProximityCm: Float = 100f  // 默认100cm（无障碍物）
    private var lastAcceleration: Float = 0f
    private var isRegistered = false

    // 缓存最后一次检测结果
    @Volatile
    private var lastResult: List<DetectedObstacle> = emptyList()

    /**
     * 注册传感器监听
     */
    fun registerSensors() {
        if (isRegistered) return
        val proximitySensor = sensorManager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
        if (proximitySensor != null) {
            sensorManager.registerListener(
                this, proximitySensor, SensorManager.SENSOR_DELAY_NORMAL
            )
        }
        val accelSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accelSensor != null) {
            sensorManager.registerListener(
                this, accelSensor, SensorManager.SENSOR_DELAY_UI
            )
        }
        isRegistered = true
        Timber.i("SensorFallbackStrategy: sensors registered")
    }

    /**
     * 注销传感器监听
     */
    fun unregisterSensors() {
        if (!isRegistered) return
        sensorManager.unregisterListener(this)
        isRegistered = false
        Timber.i("SensorFallbackStrategy: sensors unregistered")
    }

    override fun onSensorChanged(event: SensorEvent) {
        when (event.sensor.type) {
            Sensor.TYPE_PROXIMITY -> {
                lastProximityCm = event.values[0]
                updateFallbackResult()
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val x = event.values[0]
                val y = event.values[1]
                val z = event.values[2]
                lastAcceleration = kotlin.math.sqrt(x * x + y * y + z * z)
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    private fun updateFallbackResult() {
        if (lastProximityCm < 8f) {  // proximity sensor 大多数最大值8cm
            // 非常近的障碍物
            lastResult = listOf(DetectedObstacle(
                type = ObstacleType.UNKNOWN,
                distance = lastProximityCm,
                direction = Direction.CENTER,
                confidence = 0.5f,
                boundingBox = BoundingBox(0f, 0f, 0f, 0f),
                timestamp = System.currentTimeMillis()
            ))
        } else {
            lastResult = emptyList()
        }
    }

    override fun detect(bitmap: Bitmap): List<DetectedObstacle> {
        // 传感器检测不使用图像，返回缓存结果
        if (!isRegistered) {
            registerSensors()
        }
        return if (lastResult.isNotEmpty()) {
            ReliabilityLogger.logFallback("sensor_fallback_used", "all_detectors_failed")
            lastResult
        } else {
            // 传感器也没检测到，返回一个低置信度的安全提醒
            listOf(DetectedObstacle(
                type = ObstacleType.UNKNOWN,
                distance = 2.0f,
                direction = Direction.CENTER,
                confidence = 0.1f,
                boundingBox = BoundingBox(0f, 0f, 0f, 0f),
                timestamp = System.currentTimeMillis()
            ))
        }
    }
}
