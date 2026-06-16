plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.blindpath.module_navigation"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
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
}

dependencies {
    implementation(project(":base"))
    implementation(project(":module_voice"))
    implementation(project(":module_obstacle"))
    
    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    
    // Location
    implementation(libs.play.services.location)
    implementation(libs.amap.location.search)
    // 高德搜索 SDK - 必须添加！包含地理编码、逆地理编码、路径规划、POI搜索等功能
    implementation(libs.amap.search)

    // Timber
    implementation(libs.timber)

    // Coroutines
    implementation(libs.coroutines.android)
    implementation(libs.coroutines.core)
    
    // Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.service)
    
    // Test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.arch.core.testing)
}