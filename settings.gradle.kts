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
