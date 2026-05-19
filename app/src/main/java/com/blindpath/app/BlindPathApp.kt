package com.blindpath.app

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.blindpath.base.error.GlobalExceptionHandler
import dagger.hilt.android.HiltAndroidApp
import timber.log.Timber

@HiltAndroidApp
class BlindPathApp : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // 初始化 Timber 日志
        initTimber()
        
        // 初始化全局异常捕获器
        GlobalExceptionHandler.initialize(this)
        
        // 确保 ProcessLifecycleOwner 被初始化，供 CameraX 等组件使用
        ProcessLifecycleOwner.get()
        
        Timber.d("BlindPath Application initialized")
    }
    
    /**
     * 初始化 Timber 日志
     */
    private fun initTimber() {
        if (BuildConfig.DEBUG) {
            // Debug 模式：打印到 Logcat
            Timber.plant(Timber.DebugTree())
        } else {
            // Release 模式：可以接入 Crashlytics 等服务
            // Timber.plant(CrashlyticsTree())
        }
    }
}
