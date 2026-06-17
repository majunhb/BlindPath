package com.blindpath.base.shortcuts

import android.content.Context
import android.content.Intent
import androidx.core.content.pm.ShortcutInfoCompat
import androidx.core.content.pm.ShortcutManagerCompat
import androidx.core.graphics.drawable.IconCompat
import com.blindpath.base.R

/**
 * 动态快捷方式管理器
 * 
 * 提供主屏幕长按应用图标的快捷操作
 */
object AppShortcutsManager {
    
    const val SHORTCUT_OBSTACLE_DETECTION = "obstacle_detection"
    const val SHORTCUT_NAVIGATION = "navigation"
    const val SHORTCUT_SOS = "sos"
    const val SHORTCUT_VOICE_COMMAND = "voice_command"
    
    /**
     * 更新动态快捷方式
     */
    fun updateShortcuts(context: Context) {
        val shortcuts = listOf(
            createObstacleDetectionShortcut(context),
            createNavigationShortcut(context),
            createSosShortcut(context),
            createVoiceCommandShortcut(context)
        )
        
        ShortcutManagerCompat.setDynamicShortcuts(context, shortcuts)
    }
    
    /**
     * 障碍物检测快捷方式
     */
    private fun createObstacleDetectionShortcut(context: Context): ShortcutInfoCompat {
        return ShortcutInfoCompat.Builder(context, SHORTCUT_OBSTACLE_DETECTION)
            .setShortLabel("障碍物检测")
            .setLongLabel("开始障碍物检测")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_detection))
            .setIntent(
                Intent(Intent.ACTION_VIEW).apply {
                    setClassName(context, "com.blindpath.app.MainActivity")
                    putExtra("shortcut_action", SHORTCUT_OBSTACLE_DETECTION)
                }
            )
            .setRank(1)
            .build()
    }
    
    /**
     * 导航快捷方式
     */
    private fun createNavigationShortcut(context: Context): ShortcutInfoCompat {
        return ShortcutInfoCompat.Builder(context, SHORTCUT_NAVIGATION)
            .setShortLabel("导航")
            .setLongLabel("开始导航")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_navigation))
            .setIntent(
                Intent(Intent.ACTION_VIEW).apply {
                    setClassName(context, "com.blindpath.app.MainActivity")
                    putExtra("shortcut_action", SHORTCUT_NAVIGATION)
                }
            )
            .setRank(2)
            .build()
    }
    
    /**
     * SOS 求助快捷方式
     */
    private fun createSosShortcut(context: Context): ShortcutInfoCompat {
        return ShortcutInfoCompat.Builder(context, SHORTCUT_SOS)
            .setShortLabel("SOS 求助")
            .setLongLabel("发送紧急求助")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_sos))
            .setIntent(
                Intent(Intent.ACTION_VIEW).apply {
                    setClassName(context, "com.blindpath.app.MainActivity")
                    putExtra("shortcut_action", SHORTCUT_SOS)
                }
            )
            .setRank(0) // 最高优先级
            .build()
    }
    
    /**
     * 语音命令快捷方式
     */
    private fun createVoiceCommandShortcut(context: Context): ShortcutInfoCompat {
        return ShortcutInfoCompat.Builder(context, SHORTCUT_VOICE_COMMAND)
            .setShortLabel("语音命令")
            .setLongLabel("启动语音助手")
            .setIcon(IconCompat.createWithResource(context, R.drawable.ic_shortcut_voice))
            .setIntent(
                Intent(Intent.ACTION_VIEW).apply {
                    setClassName(context, "com.blindpath.app.MainActivity")
                    putExtra("shortcut_action", SHORTCUT_VOICE_COMMAND)
                }
            )
            .setRank(3)
            .build()
    }
    
    /**
     * 报告快捷方式使用
     */
    fun reportShortcutUsed(context: Context, shortcutId: String) {
        ShortcutManagerCompat.reportShortcutUsed(context, shortcutId)
    }
}
