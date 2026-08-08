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
        // JitPack for MPAndroidChart or others
        maven { url = uri("https://jitpack.io") }
    }
}

rootProject.name = "master2"
include(":app")
