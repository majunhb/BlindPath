# BlindPath Proguard 配置
# 
# 混淆规则说明：
# 1. 保留所有公共 API
# 2. 保留序列化相关类
# 3. 保留 Compose 相关
# 4. 保留反射使用的类
# 5. 保留 Native 方法

#############################################
# 基本配置
#############################################

# 优化级别
-optimizationpasses 5
-dontusemixedcaseclassnames
-dontskipnonpubliclibraryclasses
-verbose

# 优化选项
-optimizations !code/simplification/arithmetic,!field/*,!class/merging/*,!code/allocation/variable

#############################################
# 保留规则
#############################################

# 保留应用主类
-keep public class com.blindpath.app.** { *; }

# 保留所有公共 API
-keep public class com.blindpath.base.** { 
    public *; 
    protected *;
}

# 保留序列化类
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

# 保留 Parcelable 实现
-keep class * implements android.os.Parcelable {
    public static final android.os.Parcelable$Creator *;
}

# 保留 Serializable 实现
-keepnames class * implements java.io.Serializable
-keepclassmembers class * implements java.io.Serializable {
    static final long serialVersionUID;
    private static final java.io.ObjectStreamField[] serialPersistentFields;
    !static !transient <fields>;
    private void writeObject(java.io.ObjectOutputStream);
    private void readObject(java.io.ObjectInputStream);
    java.lang.Object writeReplace();
    java.lang.Object readResolve();
}

#############################################
# Kotlin 规则
#############################################

# Kotlin 反射
-keep class kotlin.** { *; }
-keep class kotlin.Metadata { *; }
-dontwarn kotlin.**
-keepclassmembers class **$WhenMappings {
    <fields>;
}
-keepclassmembers class kotlin.Metadata {
    public <methods>;
}

# Kotlin 协程
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}
-keepclassmembernames class kotlinx.** {
    volatile <fields>;
}

# Kotlin 序列化
-keepattributes RuntimeVisibleAnnotations,AnnotationDefault
-keepclassmembers class kotlinx.serialization.json.** {
    *** Companion;
}
-keepclasseswithmembers class kotlinx.serialization.json.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,includedescriptorclasses class com.blindpath.**$$serializer { *; }
-keepclassmembers class com.blindpath.** {
    *** Companion;
}
-keepclasseswithmembers class com.blindpath.** {
    kotlinx.serialization.KSerializer serializer(...);
}

#############################################
# Jetpack Compose 规则
#############################################

# Compose 编译器生成的类
-keep class * extends androidx.compose.runtime.Composer {
    *;
}
-keep class androidx.compose.** { *; }
-dontwarn androidx.compose.**

# Compose UI 组件
-keepclassmembers class androidx.compose.ui.** {
    *;
}

#############################################
# Room 数据库规则
#############################################

# Room 生成的类
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

#############################################
# Hilt 依赖注入规则
#############################################

# Hilt 生成的类
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keep,allowobfuscation,allowshrinking class com.blindpath.app.Hilt_** { *; }
-keep,allowobfuscation,allowshrinking class com.blindpath.base.Hilt_** { *; }

#############################################
# Retrofit / OkHttp 规则
#############################################

# Retrofit
-keepattributes Signature
-keepattributes *Annotation*
-keep class retrofit2.** { *; }
-keepclasseswithmembers class * {
    @retrofit2.http.* <methods>;
}

# OkHttp
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

#############################################
# TensorFlow Lite 规则
#############################################

# TFLite
-keep class org.tensorflow.lite.** { *; }
-keep class org.tensorflow.lite.support.** { *; }
-dontwarn org.tensorflow.lite.**

#############################################
# SQLCipher 规则
#############################################

# SQLCipher
-keep class net.sqlcipher.** { *; }
-dontwarn net.sqlcipher.**

#############################################
# 高德地图规则
#############################################

# 高德地图 SDK
-keep class com.amap.api.** { *; }
-keep class com.autonavi.** { *; }
-keep class com.loc.** { *; }
-dontwarn com.amap.api.**
-dontwarn com.autonavi.**
-dontwarn com.loc.**

#############################################
# 移除日志
#############################################

# 生产环境移除日志
-assumenosideeffects class android.util.Log {
    public static *** d(...);
    public static *** v(...);
    public static *** i(...);
}

# 保留 BlindPathLog 用于错误上报
-keep class com.blindpath.base.common.BlindPathLog { *; }

#############################################
# 科大讯飞 AIKit SDK 反射保护规则
#############################################

# AIKit 核心类（反射调用，必须保留）
-keep class com.iflytek.aikit.core.** { *; }

# 唤醒词检测器（反射 + Proxy 监听器，禁止混淆）
-keep class com.blindpath.module_voice.service.XfWakeWordDetector { *; }
-keep class com.blindpath.module_voice.service.XfWakeWordDetector$Companion { *; }

# 禁止混淆 Proxy 回调方法名（onAuthStateChange, onResult, onEvent, onError）
-keepclassmembers class * {
    void onAuthStateChange(...);
    void onResult(...);
    void onEvent(...);
    void onError(...);
}

#############################################
# 异常处理
#############################################

# 保留源文件名和行号（用于崩溃日志）
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# 保留注解
-keepattributes *Annotation*
-keepattributes EnclosingMethod

# 保留泛型签名
-keepattributes Signature

# 保留异常
-keepattributes Exceptions

#############################################
# 警告抑制
###############################################

-dontwarn org.conscrypt.**
-dontwarn org.bouncycastle.**
-dontwarn org.openjsse.**
-dontwarn javax.annotation.**
-dontwarn edu.umd.cs.findbugs.annotations.**
-dontwarn com.google.errorprone.annotations.**
# ============================================
# v3.0 新增：讯飞 AIKit SDK ProGuard 规则
# 将以下内容追加到 app/proguard-rules.pro 末尾
# ============================================

#############################################
# 讯飞 AIKit SDK 规则（反射调用需要保留）
#############################################

# 保留讯飞 AIKit 所有类及其成员（XfWakeWordDetector 通过反射访问）
-keep class com.iflytek.aikit.** { *; }
-dontwarn com.iflytek.aikit.**

# 保留 AiAudio$Holder 等内部类（反射 getMethod 需要精确类名匹配）
-keep class com.iflytek.aikit.core.AiAudio$Holder { *; }
-keep class com.iflytek.aikit.core.AiRequest$Builder { *; }
-keep class com.iflytek.aikit.core.BaseLibrary$Params { *; }
-keep class com.iflytek.aikit.core.BaseLibrary$Params$Builder { *; }

# 保留 JNI 相关（SDK 底层依赖 native 方法）
-keepclasseswithmembernames class com.iflytek.aikit.** {
    native <methods>;
}
