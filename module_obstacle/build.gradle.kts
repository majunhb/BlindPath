plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.blindpath.module_obstacle"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
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
    
    // Hilt - 使用 version catalog
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    
    // CameraX - 使用 version catalog bundle
    implementation(libs.bundles.camerax)
    
    // ML Kit Object Detection
    implementation(libs.mlkit.objectDetection)
    
    // TensorFlow Lite - 使用 version catalog bundle
    implementation(libs.bundles.tensorflow.lite)
    
    // Timber
    implementation(libs.timber)
    
    // OkHttp
    implementation(libs.okhttp)
    
    // Coroutines - 使用 version catalog bundle
    implementation(libs.bundles.coroutines)
    implementation(libs.kotlinx.coroutines.play.services)
    
    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.process)
    
    // Test dependencies
    testImplementation(libs.bundles.testing)
}
