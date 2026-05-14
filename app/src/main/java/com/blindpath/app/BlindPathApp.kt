package com.blindpath.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class BlindPathApp : Application() {

    companion object {
        const val CHANNEL_OBSTACLE = "channel_obstacle"      // 避障预警
        const val CHANNEL_NAVIGATION = "channel_navigation" // 导航播报
        const val CHANNEL_SERVICE = "channel_service"        // 服务通知
    }

    override fun onCreate() {
        super.onCreate()

        // 初始化日志
        if (BuildConfig.DEBUG) {
            Timber.plant(Timber.DebugTree())
        }

        // 创建通知渠道
        createNotificationChannels()

        Timber.d("BlindPathApp initialized")
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // 避障预警通知（高优先级）
            val obstacleChannel = NotificationChannel(
                CHANNEL_OBSTACLE,
                "避障预警",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "视障人士避障预警信息"
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
            }

            // 导航播报通知
            val navChannel = NotificationChannel(
                CHANNEL_NAVIGATION,
                "导航指引",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "步行导航指引信息"
                setSound(null, null)
            }

            // 服务通知
            val serviceChannel = NotificationChannel(
                CHANNEL_SERVICE,
                "后台服务",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "应用后台运行时通知"
            }

            notificationManager.createNotificationChannels(
                listOf(obstacleChannel, navChannel, serviceChannel)
            )
        }
    }
}
