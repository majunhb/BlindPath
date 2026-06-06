package com.yourpackage.visionengine.utils

import androidx.camera.core.CameraInfo
import kotlin.math.roundToInt

object DistanceEstimator {
    private val obstacleRealHeights = mapOf(
        "石墩" to 0.6f, "电线杆" to 1.5f, "非机动车" to 1.2f, 
        "行人" to 1.7f, "坑洼" to 0.3f
    )

    fun estimateDistance(label: String, bboxHeightPx: Int, imageHeightPx: Int): Float? {
        val realHeight = obstacleRealHeights[label] ?: return null
        val verticalFovDegrees = 65.0f
        val bboxRatio = bboxHeightPx.toFloat() / imageHeightPx
        
        if (bboxRatio <= 0f) return null
        
        val fovRadians = Math.toRadians(verticalFovDegrees.toDouble() / 2.0)
        val distance = (realHeight / (2.0 * Math.tan(fovRadians) * bboxRatio)).toFloat()
        
        return if (distance <= 5.0f && distance > 0f) distance.roundToDecimals(1) else null
    }

    private fun Float.roundToDecimals(decimals: Int): Float {
        var multiplier = 1.0
        repeat(decimals) { multiplier *= 10 }
        return ((this * multiplier).roundToInt() / multiplier).toFloat()
    }
}
