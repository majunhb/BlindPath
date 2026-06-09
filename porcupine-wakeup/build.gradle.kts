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
    implementation(libs.porcupine.android)
    
    // Kotlin 协程
    implementation(libs.coroutines.android)
    
    // Timber 日志
    implementation(libs.timber)
    
    // AndroidX Core
    implementation(libs.core.ktx)
    
    // 测试
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.ext.junit)
}