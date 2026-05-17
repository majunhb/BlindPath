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
    }
}

rootProject.name = "BlindPath"
include(":app")
include(":base")
include(":module_obstacle")
include(":module_navigation")
include(":module_voice")
include(":module_community")
include(":module_settings")
