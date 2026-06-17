# Porcupine 模块 ProGuard 规则
# 保留 Porcupine SDK 相关类

-keep class ai.picovoice.porcupine.** { *; }
-keep class com.blindpath.porcupine.** { *; }
-keep class com.blindpath.audio.** { *; }
-keep class com.blindpath.voice.** { *; }

# 保留 Kotlin 协程相关
-keepclassmembers class kotlinx.coroutines.** {
    volatile <fields>;
}
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# 保留 Timber 日志
-keep class timber.log.Timber { *; }