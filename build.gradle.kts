// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    // Macrobenchmark 模块的插件（仅在该子工程里 apply，根工程只声明版本）。
    // 注意：不要再配 kotlin-android —— AGP 9 的 com.android.test 自带 kotlin 扩展。
    alias(libs.plugins.android.test) apply false
}