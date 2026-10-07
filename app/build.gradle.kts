plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 版本号单点定义：defaultConfig 与输出文件名共用（GitHub Actions 产物带版本，用户可辨新旧）
// v1.2.0（2026-10-07）：**终端环境优化**——不修 UI、不加功能，只动终端里的环境本身。
//   卸载终端 npm 版 opencode；修复太极配置路径（v1.1.1 曾误指终端 taiji 的 guest 死路径）；
//   网络四项与 IP 钉住的尝试及撤除；工作区 .ignore；资源监控与体检三态。
// 版本序列：v1.0.0(12) → v1.1 → v1.1.1(13) → v1.2.0(14)
// ⚠️ 14 的归属曾有过争议：v1.1.1 裁决④一度把 14/1.2.0 划给 ChatGPT 线，
//    该线已归档，14 归还本计划（2026-10-07 用户拍板）。
val appVersionName = "1.2.0"

android {
    namespace = "com.example.zhengdao"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.zhengdao"
        // 架构生死线（设计文档 v3「架构前提」）：
        // targetSdk 必须钉死 28 —— Android 10 起，targetSdk ≥ 29 的应用会被 SELinux
        // 禁止从可写数据目录执行文件（W^X 限制），而本 App 的核心能力恰恰是在数据
        // 目录里运行下载来的 Linux 环境（proot + rootfs）。targetSdk 28 是合法的
        // 旧规则选择，同类免 root Linux 环境产品均采用同一策略。
        // 🚫 未经架构前提重新评估，任何人不得上调此值。
        targetSdk = 28
        // 最低安装门槛：安卓 16（API 36）。实测环境为荣耀 Magic 5 Pro（MagicOS 11 /
        // Android 16）；Android 15 及以下未适配未验证（README 有明确声明），直接拒绝安装。
        minSdk = 36
        versionCode = 14
        versionName = appVersionName

        ndk {
            // 只编真机 arm64 单架构（2026-10-06 用户定案，见设计文档 §4）：
            // 定位是手机 / 平板，不做 x86_64 模拟器支持。
            // 收益：① APK 体积少一份 libzstd-jni（约 690KB）；② 16KB 对齐只需验一套，不用 ×2；
            //      ③ 将来若引 Rust（cargo-ndk）只需交叉编译一个目标，工作量减半。
            // 注意：删的是 APK 的 x86_64 ABI，与 CI 构建机架构（ubuntu-latest）无关——
            //      .github/workflows/build.yml 里的 x86_64 指的是 qemu 交叉编译 rootfs 的 runner。
            abiFilters += listOf("arm64-v8a")
        }

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    base {
        archivesName.set("zhengdao-$appVersionName")
    }

    buildTypes {
        release {
            optimization {
                enable = true
                // ⚠️ packageScope（跨包重命名收拢）已在 v1.0 移除：mikepenz 0.38.1（v1.1
                //    引入）的 AAR 内含自家 r8 重映射类（如 kotlin.sequences 扩展 kh1），
                //    packageScope 对 kotlin.** 的重命名会与它撞名——真机 release 启动即
                //    IllegalAccessError（debug 未混淆看不出）。保留普通 R8 优化与混淆。
            }
            // R8 字节码优化 + 资源收缩（用户第四批后追加）：显著减小体积与内存占用。
            // JNI / JS 桥的 keep 规则见 proguard-rules.pro。
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 个人分发渠道：release 也用 debug 签名，保证产物可直接安装
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // lint 检查：ExpiredTargetSdkVersion 是 Google Play 上架要求（targetSdk≥33），
    // 但本项目 targetSdk=28 是架构生死线（proot 需要从可写目录 exec），不可上调。
    // 本项目不走 Google Play，走 GitHub Releases 直发，故禁用该检查。
    testOptions {
        // EnvSelfHeal 等纯逻辑单测会触达 android.util.Log——未 mock 的调用返回默认值
        // （仅 unit test 生效；真机行为不变）
        unitTests.isReturnDefaultValues = true
    }

    lint {
        disable += "ExpiredTargetSdkVersion"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }

    // 原生库落盘为真实文件：jniLibs 里的 proot 需要被复制+chmod+execve，
    // zstd-jni 的加载兜底也需要在 nativeLibraryDir 找到 libzstd-jni-*.so
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // zstd-jni 把桌面端各平台的原生库也塞进了 classes.jar，Android 一个都用不上
            // （真机只加载 lib/arm64-v8a/libzstd-jni.so）。实测这 5 个文件占 4.58MB：
            //   win/{aarch64,amd64,x86}/libzstd-jni-1.5.6-4.dll
            //   darwin/{aarch64,x86_64}/libzstd-jni-1.5.6-4.dylib
            // freebsd/ 与 linux/* 在 Android 变体里本就没有，一并排除以防换版本后回来。
            excludes += setOf(
                "win/**",
                "darwin/**",
                "freebsd/**",
            )
        }
    }

    // 锁定 NDK 版本（与已安装版本一致，保证本机与 CI 构建可复现）
    ndkVersion = "27.2.12479018"

    // 本机 C 代码：自研伪终端 JNI（见 src/main/cpp/，依据 POSIX 标准接口）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // RootFS 下载与解压（M1.1）：OkHttp 断点续传 + commons-compress 解 tar + zstd-jni 解 zstd
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.apache.commons:commons-compress:1.26.2")
    androidTestImplementation("org.apache.commons:commons-compress:1.26.2")
    implementation("com.github.luben:zstd-jni:1.5.6-4")

    // xz 解压（太极 bionic OpenCode 的 .pkg.tar.xz 释放）：commons-compress 的 XZ 后端
    implementation("org.tukaani:xz:1.9")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Markdown 渲染（v1.1 第四阶段）：纯 Compose 实现，覆盖最终回答的 Markdown 排版 +
    // 代码块独立背景/等宽/横向滚动/复制按钮/语法高亮。锁 0.38.1（Kotlin 同线，见 libs.versions.toml）。
    // 仅用于渲染层（TaijiComponents.FinalAnswerText），不触碰会话/网络层（架构冻结红线）。
    implementation(libs.mikepenz.markdown)
    implementation(libs.mikepenz.markdown.m3)
    implementation(libs.mikepenz.markdown.code)
    testImplementation(libs.junit)
    // 单元测试里的 org.json 真实实现（PluginManager 读写 opencode.json 用的就是它）：
    // Android 的 android.jar 只是 mockable stub，方法调用一律抛 "not mocked"，
    // 只有补上真实实现，配置读写的单测才有意义。仅作用于 test classpath，不进 APK。
    testImplementation("org.json:json:20240303")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}