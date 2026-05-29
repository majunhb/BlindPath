plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.blindpath.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.blindpath.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 8
        versionName = "3.5"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }

        // 凭证配置：从 local.properties 读取，通过 manifestPlaceholders 注入到 AndroidManifest.xml
        manifestPlaceholders += mapOf(
            "BAIDU_APP_ID" to (project.findProperty("BAIDU_APP_ID") as String? ?: ""),
            "BAIDU_API_KEY" to (project.findProperty("BAIDU_API_KEY") as String? ?: ""),
            "BAIDU_SECRET_KEY" to (project.findProperty("BAIDU_SECRET_KEY") as String? ?: "")
        )

        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    buildTypes {
        debug {
            // Debug 版本也启用基础优化
            isMinifyEnabled = false
            isShrinkResources = false
        }
        release {
            // Release 版本启用完整优化
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
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.10"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            // 排除未使用的资源
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE"
            excludes += "/META-INF/LICENSE.txt"
            excludes += "/META-INF/NOTICE"
            excludes += "/META-INF/NOTICE.txt"
        }

        jniLibs {
            // 压缩 JNI 库（减少约 30% 体积）
            useLegacyPackaging = false
        }
    }
    
    // Bundle 优化（AAB 格式）
    bundle {
        language {
            // 启用语言资源分割，用户只下载设备语言资源
            enableSplit = true
        }
        density {
            // 启用密度资源分割，用户只下载设备密度资源
            enableSplit = true
        }
        abi {
            // 启用 ABI 分割，用户只下载设备架构资源
            enableSplit = true
        }
    }
}

dependencies {
    implementation(project(":base"))
    implementation(project(":module_obstacle"))
    implementation(project(":module_indoor"))
    implementation(project(":module_navigation"))
    implementation(project(":module_voice"))
    implementation(project(":module_settings"))
    implementation(project(":module_community"))
    implementation(project(":module_trip_assist"))

    // 科大讯飞 AIKit SDK（本地AAR依赖）
    implementation(files("libs/AIKit.aar"))
    implementation(files("libs/SparkChain.aar"))

    // ============ 高德地图 SDK ============
    // 方案一：仅定位 + 搜索（减少约 20MB）
    // 如果不需要地图显示，使用此方案
    // implementation("com.amap.api:location:6.5.1")
    // implementation("com.amap.api:search:9.7.4")
    
    // 方案二：一体包（包含地图显示，体积较大）
    // 当前使用此方案，如需优化体积可切换到方案一
    implementation(libs.amap.sdk)

    // Hilt - 使用 version catalog
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.navigation.compose)

    // Core - 使用 version catalog
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.activity.compose)

    // Timber
    implementation(libs.timber)

    // CameraX - 使用 version catalog bundle
    implementation(libs.bundles.camerax)

    // Compose - 使用 version catalog
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose)
    implementation(libs.androidx.compose.material.icons)

    // Debug
    debugImplementation(libs.bundles.compose.debug)

    // Test dependencies - 使用 version catalog
    testImplementation(libs.bundles.testing)

    // Android Test
    androidTestImplementation(libs.bundles.android.testing)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}
