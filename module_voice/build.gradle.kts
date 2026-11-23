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

    // 构建时自动生成 credentials.properties（讯飞/百度 SDK 凭证）
    // 确保本地构建和 CI 构建都能产出此文件，SDK 可从 assets 读取
    val generateCredentials by tasks.registering {
        val outFile = layout.buildDirectory.file("generated/assets/credentials/credentials.properties")
        outputs.file(outFile)
        doLast {
            val props = linkedMapOf(
                "BAIDU_APP_ID" to (project.findProperty("BAIDU_APP_ID") as String? ?: ""),
                "BAIDU_API_KEY" to (project.findProperty("BAIDU_API_KEY") as String? ?: ""),
                "BAIDU_SECRET_KEY" to (project.findProperty("BAIDU_SECRET_KEY") as String? ?: ""),
                "IFLYTEK_APP_ID" to (project.findProperty("IFLYTEK_APP_ID") as String? ?: ""),
                "IFLYTEK_API_KEY" to (project.findProperty("IFLYTEK_API_KEY") as String? ?: ""),
                "IFLYTEK_API_SECRET" to (project.findProperty("IFLYTEK_API_SECRET") as String? ?: ""),
                "AMAP_API_KEY" to (project.findProperty("AMAP_API_KEY") as String? ?: ""),
                "AMAP_WEB_KEY" to (project.findProperty("AMAP_WEB_KEY") as String? ?: ""),
                "PORCUPINE_ACCESS_KEY" to (project.findProperty("PORCUPINE_ACCESS_KEY") as String? ?: "")
            )
            val f = outFile.get().asFile
            f.parentFile.mkdirs()
            f.writeText(props.entries.joinToString("\n") { "${it.key}=${it.value}" })
        }
    }

    // 将生成的 credentials.properties 作为 assets 源目录
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/assets/credentials"))
    // 让所有 MergeAssets 任务依赖 generateCredentials
    tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }.configureEach {
        dependsOn(generateCredentials)
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
    implementation(project(":porcupine-wakeup"))
    
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
    compileOnly(mapOf("name" to "bdasr_aipd_V3_20250717_1e379e2", "ext" to "aar"))

    // 讯飞 AIKit SDK (编译时依赖，实际打包由 app 模块负责)
    compileOnly(mapOf("name" to "AIKit", "ext" to "aar"))
    
    // Test dependencies
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.arch.core.testing)
}