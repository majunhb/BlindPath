package com.blindpath.base.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.blindpath.base.R
import com.blindpath.base.common.BlindPathLog

/**
 * BlindPath 主屏幕小组件
 * 
 * 提供快速操作入口：
 * - 一键开始障碍物检测
 * - SOS 紧急求助
 * - 快速导航
 */
class BlindPathWidget : AppWidgetProvider() {
    
    companion object {
        const val ACTION_START_DETECTION = "com.blindpath.action.START_DETECTION"
        const val ACTION_SOS = "com.blindpath.action.SOS"
        const val ACTION_START_NAVIGATION = "com.blindpath.action.START_NAVIGATION"
        
        fun updateWidget(context: Context, appWidgetId: Int) {
            val views = RemoteViews(context.packageName, R.layout.widget_blindpath)
            
            // 设置检测按钮点击
            val detectionIntent = Intent(context, BlindPathWidget::class.java).apply {
                action = ACTION_START_DETECTION
            }
            val detectionPendingIntent = PendingIntent.getBroadcast(
                context, 0, detectionIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_detection, detectionPendingIntent)
            
            // 设置 SOS 按钮点击
            val sosIntent = Intent(context, BlindPathWidget::class.java).apply {
                action = ACTION_SOS
            }
            val sosPendingIntent = PendingIntent.getBroadcast(
                context, 1, sosIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_sos, sosPendingIntent)
            
            // 设置导航按钮点击
            val navIntent = Intent(context, BlindPathWidget::class.java).apply {
                action = ACTION_START_NAVIGATION
            }
            val navPendingIntent = PendingIntent.getBroadcast(
                context, 2, navIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.btn_navigation, navPendingIntent)
            
            // 更新小组件
            val appWidgetManager = AppWidgetManager.getInstance(context)
            appWidgetManager.updateAppWidget(appWidgetId, views)
        }
    }
    
    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        BlindPathLog.i("Widget", mapOf(
            "event" to "widget_update",
            "widget_count" to appWidgetIds.size
        ))
        
        appWidgetIds.forEach { widgetId ->
            updateWidget(context, widgetId)
        }
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        
        when (intent.action) {
            ACTION_START_DETECTION -> {
                BlindPathLog.i("Widget", mapOf("event" to "widget_detection_click"))
                // 启动障碍物检测
                startDetectionActivity(context)
            }
            ACTION_SOS -> {
                BlindPathLog.i("Widget", mapOf("event" to "widget_sos_click"))
                // 触发 SOS
                triggerSos(context)
            }
            ACTION_START_NAVIGATION -> {
                BlindPathLog.i("Widget", mapOf("event" to "widget_navigation_click"))
                // 启动导航
                startNavigationActivity(context)
            }
        }
    }
    
    private fun startDetectionActivity(context: Context) {
        val intent = Intent(context, Class.forName("com.blindpath.app.MainActivity")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("shortcut_action", "obstacle_detection")
        }
        context.startActivity(intent)
    }
    
    private fun triggerSos(context: Context) {
        // 发送 SOS 广播，要求接收者拥有 TRIGGER_SOS 权限
        val sosIntent = Intent("com.blindpath.action.TRIGGER_SOS")
        context.sendBroadcast(sosIntent, "com.blindpath.permission.TRIGGER_SOS")
    }
    
    private fun startNavigationActivity(context: Context) {
        val intent = Intent(context, Class.forName("com.blindpath.app.MainActivity")).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("shortcut_action", "navigation")
        }
        context.startActivity(intent)
    }
}

/**
 * 小组件配置助手
 */
object WidgetConfigHelper {
    
    /**
     * 更新所有小组件
     */
    fun updateAllWidgets(context: Context) {
        val appWidgetManager = AppWidgetManager.getInstance(context)
        val widgetIds = appWidgetManager.getAppWidgetIds(
            android.content.ComponentName(context, BlindPathWidget::class.java)
        )
        
        widgetIds.forEach { widgetId ->
            BlindPathWidget.updateWidget(context, widgetId)
        }
    }
}
