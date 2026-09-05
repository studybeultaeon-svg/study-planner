pluginManagement {
    repositories {
        google()
        gradlePluginPortal()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven("https://maven.pkg.jetbrains.space/public/p/compose/dev")
    }
}

rootProject.name = "PhoneLockDesktop"

// 82차(감사 §7 ":shared"): 안드로이드/데스크탑이 공유하는 순수 Kotlin 로직 모듈 — composite build로 연결.
includeBuild("../shared")
