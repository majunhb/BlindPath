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
        // 高德地图 SDK Maven 仓库（阿里云镜像）
        maven { url = uri("https://maven.aliyun.com/repository/public") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.aliyun.com/repository/central") }
        maven { url = uri("https://jitpack.io") }
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
