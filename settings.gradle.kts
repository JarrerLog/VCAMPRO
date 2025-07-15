pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
        maven("https://jitpack.io")                        // 🟢 Thêm jitpack để dùng lib từ GitHub
        maven("https://api.xposed.info/")                   // Xposed
        mavem("https://artifactory.appodeal.com/appodeal-public/") // Repo private cần token (cẩn thận 401)
    }
}

rootProject.name = "VCAMSX"

include(":app")
