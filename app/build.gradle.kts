plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.blindpath.app"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.blindpath.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 百度语音 SDK 凭证（CI 使用空值，真实环境通过 local.properties 覆盖）
        manifestPlaceholders["BAIDU_APP_ID"] = project.findProperty("BAIDU_APP_ID") as String? ?: ""
        manifestPlaceholders["BAIDU_API_KEY"] = project.findProperty("BAIDU_API_KEY") as String? ?: ""
        manifestPlaceholders["BAIDU_SECRET_KEY"] = project.findProperty("BAIDU_SECRET_KEY") as String? ?: ""

        // 高德地图 API Key（CI 使用空值，真实环境通过 local.properties 覆盖）
        manifestPlaceholders["AMAP_API_KEY"] = project.findProperty("AMAP_API_KEY") as String? ?: ""
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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
        freeCompilerArgs += listOf(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3Api"
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = libs.versions.kotlin.compiler.extension.get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(project(":base"))
    implementation(project(":module_obstacle"))
    implementation(project(":module_navigation"))
    implementation(project(":module_voice"))
    implementation(project(":module_settings"))
    implementation(project(":module_community"))
    implementation(project(":module_trip_assist"))
    implementation(project(":module_indoor"))

    // 百度语音 SDK (app 模块直接引入 AAR，Library 模块编译时用 compileOnly)
    implementation(fileTree("$rootDir/module_voice/libs") { include("*.aar") })
    
    // Timber logging
    implementation(libs.timber)

    // 高德地图 SDK（AMapLocationClient 等）
    implementation(libs.amap.location.search)

    // CameraX（ProcessCameraProvider, PreviewView, ImageAnalysis 等）
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    implementation(libs.hilt.navigation.compose)
    
    // Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.process)
    implementation(libs.activity.compose)
    
    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.compose.foundation)
    implementation(libs.compose.foundation.layout)
    implementation(libs.compose.ui.unit)
    implementation(libs.compose.ui.geometry)
    implementation(libs.compose.animation)
    implementation(libs.compose.animation.core)
    
    // Debug
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
    
    // Test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.arch.core.testing)
    testImplementation(libs.lifecycle.runtime.testing)
    
    // Android Test
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
}
