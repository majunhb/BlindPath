pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // 高德地图 SDK 仓库
        maven { url = uri("https://maven.amap.com/repository/public/") }
        maven { url = uri("https://maven.amap.com/repository/android/")
            // 高德本地仓库，仅包含高德 SDK
        }
        // 百度 AI 开放平台仓库 (TTS 和 OCR)
        maven { url = uri("https://ai.baidu.com/ai-doc/OCR/zk6rih1kk") }
    }
}

rootProject.name = "BlindPath"
include(":app")
include(":base")
include(":module_obstacle")
include(":module_navigation")
include(":module_voice")
include(":module_settings")
include(":module_community")
