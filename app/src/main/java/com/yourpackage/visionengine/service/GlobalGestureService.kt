package com.yourpackage.visionengine.service

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.view.accessibility.AccessibilityEvent

class GlobalGestureService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onGesture(gestureId: Int): Boolean {
        return when (gestureId) {
            GESTURE_SWIPE_UP -> { broadcastModeChange("obstacle_mode"); true }
            GESTURE_SWIPE_DOWN -> { broadcastModeChange("navigation_mode"); true }
            else -> false
        }
    }

    private fun broadcastModeChange(mode: String) {
        val intent = Intent("com.yourpackage.ACTION_MODE_CHANGED").apply { putExtra("mode", mode) }
        sendBroadcast(intent)
    }

    companion object {
        const val GESTURE_SWIPE_UP = 10
        const val GESTURE_SWIPE_DOWN = 11
    }
}
