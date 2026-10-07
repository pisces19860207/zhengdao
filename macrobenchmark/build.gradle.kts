// Macrobenchmark 模块（com.android.test）
//
// 为什么必须是一个**独立模块**而不能塞进 :app 的 androidTest：
//   Macrobenchmark 要反复「杀掉被测 App 进程 → 重新启动 → 计时」。若测试代码跑在被测
//   App 自己的进程里（普通 androidTest 就是如此），杀进程等于自杀，测量根本无从进行。
//   com.android.test + self-instrumenting 让测试代码跑在**独立进程**里，才有资格杀被测方。
//
// 本模块产出的是测试 APK，不进任何分发产物（release APK 体积与结构零影响）。
// ⚠️ 不要再 apply org.jetbrains.kotlin.android —— AGP 9 的 com.android.test 插件
//    自己就注册了 kotlin 扩展，重复 apply 会直接报
//    "Cannot add extension with name 'kotlin', as there is an extension already registered"。
plugins {
    alias(libs.plugins.android.test)
}

android {
    namespace = "com.example.zhengdao.macrobenchmark"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 与被测方 :app 对齐（minSdk 36）。测试模块的 minSdk 不能低于被测方，否则装不上。
        minSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // 被测工程：构建 / 安装 :app 的 benchmark 变体并驱动它
    targetProjectPath = ":app"

    // 自插桩：测试 APK 作为独立 APK 安装、测试代码跑在自己的进程里。
    // 关掉这项 → 测试代码会被注入 :app 进程，Macrobenchmark 的 killProcess 会把自己杀掉。
    experimentalProperties["android.experimental.self-instrumenting"] = true

    buildTypes {
        // 名字必须与 :app 的 benchmark 变体同名，Gradle 才能把「测试 APK」和「被测 APK」配对。
        // 非 debuggable：Macrobenchmark 拒绝测量 debuggable 构建（其字节码未做 release 优化，
        // 且 debuggable 会关掉大量运行时优化，测出来的数不具代表性）。
        //
        // ⚠️ 必须显式给签名：isDebuggable=false 之后 AGP **不会**自动补 debug 签名，
        //    产出的 APK 无证书，adb install 直接失败
        //    （INSTALL_PARSE_FAILED_NO_CERTIFICATES / Attempt to get length of null array）。
        create("benchmark") {
            isDebuggable = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation(libs.androidx.benchmark.macro.junit4)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.junit)
}
