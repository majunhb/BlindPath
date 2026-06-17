package com.blindpath.base.accessibility

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import timber.log.Timber
import kotlin.math.abs

/**
 * 手势处理器
 * 为视障用户提供简单直观的手势操作
 */
class GestureHandler(
    context: Context,
    private val onGesture: (GestureType) -> Unit
) {
    
    /**
     * 手势类型
     */
    enum class GestureType {
        // 单击
        SINGLE_TAP,
        
        // 双击
        DOUBLE_TAP,
        
        // 长按
        LONG_PRESS,
        
        // 上滑
        SWIPE_UP,
        
        // 下滑
        SWIPE_DOWN,
        
        // 左滑
        SWIPE_LEFT,
        
        // 右滑
        SWIPE_RIGHT,
        
        // 双指上滑
        TWO_FINGER_SWIPE_UP,
        
        // 双指下滑
        TWO_FINGER_SWIPE_DOWN,
        
        // 双指双击
        TWO_FINGER_DOUBLE_TAP
    }
    
    private val gestureDetector = GestureDetector(context, GestureListener())
    
    // 用于检测滑动
    private var startX = 0f
    private var startY = 0f
    private var fingerCount = 0
    
    // 滑动阈值
    private val swipeThreshold = 100f
    
    /**
     * 处理触摸事件
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        fingerCount = event.pointerCount
        
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val deltaX = event.x - startX
                val deltaY = event.y - startY
                
                // 判断是否为滑动
                if (abs(deltaX) > swipeThreshold || abs(deltaY) > swipeThreshold) {
                    handleSwipe(deltaX, deltaY, fingerCount)
                }
            }
        }
        
        return gestureDetector.onTouchEvent(event)
    }
    
    private fun handleSwipe(deltaX: Float, deltaY: Float, fingerCount: Int) {
        val isHorizontalSwipe = abs(deltaX) > abs(deltaY)
        
        val gesture = if (fingerCount >= 2) {
            // 双指手势
            if (isHorizontalSwipe) {
                null // 双指水平滑动暂不使用
            } else {
                if (deltaY < 0) GestureType.TWO_FINGER_SWIPE_UP
                else GestureType.TWO_FINGER_SWIPE_DOWN
            }
        } else {
            // 单指手势
            if (isHorizontalSwipe) {
                if (deltaX > 0) GestureType.SWIPE_RIGHT
                else GestureType.SWIPE_LEFT
            } else {
                if (deltaY < 0) GestureType.SWIPE_UP
                else GestureType.SWIPE_DOWN
            }
        }
        
        gesture?.let {
            Timber.d("Gesture detected: $it")
            onGesture(it)
        }
    }
    
    private inner class GestureListener : GestureDetector.SimpleOnGestureListener() {
        
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            Timber.d("Single tap")
            onGesture(GestureType.SINGLE_TAP)
            return true
        }
        
        override fun onDoubleTap(e: MotionEvent): Boolean {
            Timber.d("Double tap")
            onGesture(GestureType.DOUBLE_TAP)
            return true
        }
        
        override fun onLongPress(e: MotionEvent) {
            Timber.d("Long press")
            onGesture(GestureType.LONG_PRESS)
        }
    }
}

/**
 * 手势动作映射
 * 将手势映射到具体功能
 */
class GestureActionMapper(
    private val onAction: (GestureAction) -> Unit
) {
    
    /**
     * 手势动作
     */
    enum class GestureAction {
        // 播放/暂停
        PLAY_PAUSE,
        
        // 开始检测
        START_DETECTION,
        
        // 停止检测
        STOP_DETECTION,
        
        // 重复播报
        REPEAT_ANNOUNCEMENT,
        
        // 切换模式
        TOGGLE_MODE,
        
        // 音量增加
        VOLUME_UP,
        
        // 音量减少
        VOLUME_DOWN,
        
        // 返回
        GO_BACK,
        
        // 打开设置
        OPEN_SETTINGS,
        
        // SOS求救
        SOS_EMERGENCY,
        
        // 切换语音
        TOGGLE_VOICE,
        
        // 取消当前操作
        CANCEL
    }
    
    // 当前模式
    private var currentMode: GestureMode = GestureMode.NORMAL
    
    /**
     * 手势模式
     */
    enum class GestureMode {
        NORMAL,      // 正常模式
        NAVIGATION,  // 导航模式
        DETECTION    // 检测模式
    }
    
    /**
     * 设置当前模式
     */
    fun setMode(mode: GestureMode) {
        currentMode = mode
    }
    
    /**
     * 处理手势
     */
    fun handleGesture(gesture: GestureHandler.GestureType) {
        val action = mapGestureToAction(gesture)
        if (action != null) {
            Timber.d("Gesture $gesture mapped to action $action in mode $currentMode")
            onAction(action)
        }
    }
    
    private fun mapGestureToAction(gesture: GestureHandler.GestureType): GestureAction? {
        return when (currentMode) {
            GestureMode.NORMAL -> {
                when (gesture) {
                    GestureHandler.GestureType.SINGLE_TAP -> GestureAction.REPEAT_ANNOUNCEMENT
                    GestureHandler.GestureType.DOUBLE_TAP -> GestureAction.TOGGLE_MODE
                    GestureHandler.GestureType.LONG_PRESS -> GestureAction.OPEN_SETTINGS
                    GestureHandler.GestureType.SWIPE_UP -> GestureAction.START_DETECTION
                    GestureHandler.GestureType.SWIPE_DOWN -> GestureAction.STOP_DETECTION
                    GestureHandler.GestureType.SWIPE_LEFT -> GestureAction.GO_BACK
                    GestureHandler.GestureType.SWIPE_RIGHT -> GestureAction.TOGGLE_VOICE
                    GestureHandler.GestureType.TWO_FINGER_SWIPE_UP -> GestureAction.VOLUME_UP
                    GestureHandler.GestureType.TWO_FINGER_SWIPE_DOWN -> GestureAction.VOLUME_DOWN
                    GestureHandler.GestureType.TWO_FINGER_DOUBLE_TAP -> GestureAction.SOS_EMERGENCY
                }
            }
            GestureMode.NAVIGATION -> {
                when (gesture) {
                    GestureHandler.GestureType.SINGLE_TAP -> GestureAction.REPEAT_ANNOUNCEMENT
                    GestureHandler.GestureType.DOUBLE_TAP -> GestureAction.TOGGLE_MODE
                    GestureHandler.GestureType.LONG_PRESS -> GestureAction.CANCEL
                    GestureHandler.GestureType.SWIPE_UP -> GestureAction.VOLUME_UP
                    GestureHandler.GestureType.SWIPE_DOWN -> GestureAction.VOLUME_DOWN
                    GestureHandler.GestureType.SWIPE_LEFT -> null
                    GestureHandler.GestureType.SWIPE_RIGHT -> null
                    GestureHandler.GestureType.TWO_FINGER_SWIPE_UP -> null
                    GestureHandler.GestureType.TWO_FINGER_SWIPE_DOWN -> null
                    GestureHandler.GestureType.TWO_FINGER_DOUBLE_TAP -> GestureAction.SOS_EMERGENCY
                }
            }
            GestureMode.DETECTION -> {
                when (gesture) {
                    GestureHandler.GestureType.SINGLE_TAP -> GestureAction.REPEAT_ANNOUNCEMENT
                    GestureHandler.GestureType.DOUBLE_TAP -> GestureAction.STOP_DETECTION
                    GestureHandler.GestureType.LONG_PRESS -> GestureAction.TOGGLE_MODE
                    GestureHandler.GestureType.SWIPE_UP -> null
                    GestureHandler.GestureType.SWIPE_DOWN -> GestureAction.STOP_DETECTION
                    GestureHandler.GestureType.SWIPE_LEFT -> GestureAction.GO_BACK
                    GestureHandler.GestureType.SWIPE_RIGHT -> GestureAction.TOGGLE_VOICE
                    GestureHandler.GestureType.TWO_FINGER_SWIPE_UP -> null
                    GestureHandler.GestureType.TWO_FINGER_SWIPE_DOWN -> null
                    GestureHandler.GestureType.TWO_FINGER_DOUBLE_TAP -> GestureAction.SOS_EMERGENCY
                }
            }
        }
    }
}
