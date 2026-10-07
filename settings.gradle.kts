pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "zhengdao"
include(":app")
// Macrobenchmark：独立的 com.android.test 模块（Compose 性能测试的标准载体）。
// 它不进 APK —— 只产出测试 APK，通过 targetProjectPath 指向 :app 并驱动真机跑基准。
include(":macrobenchmark")
