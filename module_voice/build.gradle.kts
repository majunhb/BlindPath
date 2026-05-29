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

        // 凭证配置：从 local.properties 读取，不硬编码到代码中
        val baiduAppId = project.findProperty("BAIDU_APP_ID") as String? ?: ""
        val baiduApiKey = project.findProperty("BAIDU_API_KEY") as String? ?: ""
        val baiduSecretKey = project.findProperty("BAIDU_SECRET_KEY") as String? ?: ""
        val xfAppId = project.findProperty("IFLYTEK_APP_ID") as String? ?: ""
        val xfApiKey = project.findProperty("IFLYTEK_API_KEY") as String? ?: ""
        val xfApiSecret = project.findProperty("IFLYTEK_API_SECRET") as String? ?: ""

        buildConfigField("String", "BAIDU_APP_ID", "\"`${baiduAppId}\"")
        buildConfigField("String", "BAIDU_API_KEY", "\"`${baiduApiKey}\"")
        buildConfigField("String", "BAIDU_SECRET_KEY", "\"`${baiduSecretKey}\"")
        buildConfigField("String", "IFLYTEK_APP_ID", "\"`${xfAppId}\"")
        buildConfigField("String", "IFLYTEK_API_KEY", "\"`${xfApiKey}\"")
        buildConfigField("String", "IFLYTEK_API_SECRET", "\"`${xfApiSecret}\"")
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
    buildFeatures {
        buildConfig = true
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

    // iFlytek AIKit SDK (用户上传的 AAR 文件)
    // AIKit 是新版讯飞 SDK，支持唤醒+识别一体化
    implementation(files("libs/AIKit.aar", "libs/SparkChain.aar"))

    // Baidu Speech Wake Word SDK (extracted from AAR - JARs + SO libs)
    implementation(files("libs/classes.jar", "libs/bdasr_V3_20250717_1e379e2.jar", "libs/auth_base_20260129.jar"))

    // Test dependencies
    testImplementation(libs.bundles.testing)
}