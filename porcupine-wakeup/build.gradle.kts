/**
 * Porcupine 唤醒引擎模块构建配置
 * 
 * 功能：提供离线语音唤醒能力
 * 依赖：Picovoice Porcupine SDK
 */

plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.blindpath.porcupine"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
        
        // Porcupine Access Key 配置
        // 从 local.properties 读取，避免硬编码
        val porcupineAccessKey = project.findProperty("PORCUPINE_ACCESS_KEY") as String? ?: ""
        buildConfigField("String", "PORCUPINE_ACCESS_KEY", "\"${porcupineAccessKey}\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    
    kotlinOptions {
        jvmTarget = "17"
    }
    
    // 启用 BuildConfig 生成
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    // Porcupine 唤醒引擎 SDK
    implementation("ai.picovoice:porcupine-android:3.0.1")
    
    // Kotlin 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // Timber 日志
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    
    // 测试
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
}