plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.blindpath.module_voice"
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

    packagingOptions {
        doNotStrip("**/libvad.dnn.so")
        doNotStrip("**/libbd_easr_s1_merge_normal_20151216.dat.so")
    }
}

dependencies {
    implementation(project(":base"))

    // Hilt - 使用 version catalog
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Timber
    implementation(libs.timber)

    // Core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.viewmodel)

    // Coroutines - 使用 version catalog bundle
    implementation(libs.kotlinx.coroutines.android)

    // Porcupine Wake Word Detection
    implementation("ai.picovoice:porcupine-android:4.0.0")

    // Baidu Speech Wake Word Detection (offline)
    implementation(files("libs/bdasr_aipd_V3_20250717_1e379e2.aar"))

    // Test dependencies
    testImplementation(libs.bundles.testing)
}
