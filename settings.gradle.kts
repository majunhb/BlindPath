pluginManagement {
    repositories {
        mavenLocal()
        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }
        maven { url = uri("https://dl.google.com/dl/android/maven2/") }
        google()
        mavenCentral()
        gradlePluginPortal()
        maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        // 本地AAR仓库
        flatDir {
            dirs("app/libs", "module_voice/libs")
        }
        mavenLocal()
        maven { url = uri("https://mirrors.huaweicloud.com/repository/maven/") }
        google()
        mavenCentral()
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://jitpack.io") }
        maven { url = uri("https://maven.baidu.com/content/repositories/public/") }
        // 高德官方Maven仓库（必须）
        maven { url = uri("https://aamap.artifactory.alipay.com/android") }
    }
}

rootProject.name = "BlindPath"
include(":app")
include(":base")
include(":module_obstacle")
include(":module_indoor")
include(":module_navigation")
include(":module_voice")
include(":module_community")
include(":module_settings")
include(":module_trip_assist")
include(":porcupine-wakeup")