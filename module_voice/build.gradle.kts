plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.blindpath.module_voice"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")

        // 语音 SDK 凭证 (CI 使用空值，真实环境通过 local.properties 覆盖)
        buildConfigField("String", "BAIDU_APP_ID", "\"${project.findProperty("BAIDU_APP_ID") ?: ""}\"")
        buildConfigField("String", "BAIDU_API_KEY", "\"${project.findProperty("BAIDU_API_KEY") ?: ""}\"")
        buildConfigField("String", "BAIDU_SECRET_KEY", "\"${project.findProperty("BAIDU_SECRET_KEY") ?: ""}\"")
        buildConfigField("String", "IFLYTEK_APP_ID", "\"${project.findProperty("IFLYTEK_APP_ID") ?: ""}\"")
        buildConfigField("String", "IFLYTEK_API_KEY", "\"${project.findProperty("IFLYTEK_API_KEY") ?: ""}\"")
        buildConfigField("String", "IFLYTEK_API_SECRET", "\"${project.findProperty("IFLYTEK_API_SECRET") ?: ""}\"")
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
    buildFeatures {
        buildConfig = true
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
    
    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)
    
    // Timber
    implementation(libs.timber)

    // Core
    implementation(libs.core.ktx)
    implementation(libs.lifecycle.runtime.ktx)
    implementation(libs.lifecycle.viewmodel.ktx)

    // Coroutines
    implementation(libs.coroutines.android)

    // 百度语音 SDK (编译时依赖，实际打包由 app 模块负责，避免 Library AAR 内嵌本地 AAR)
    compileOnly(fileTree("libs") { include("*.aar", "*.jar") })

    // 讯飞 AIKit SDK (编译时依赖，实际打包由 app 模块负责)
    compileOnly(files("$rootDir/app/libs/AIKit.aar"))
    
    // Test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.arch.core.testing)
}