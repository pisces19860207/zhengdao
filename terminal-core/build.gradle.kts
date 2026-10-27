// 第二百四十一批（终端解耦第二刀）：`:terminal-core` —— 终端内核。
//
// 本模块装的是**终端自己的东西**（与证道/抱朴无关）：
//   · com.termux.**            PTY（terminal-emulator）＋ View ＋ textselection
//   · rootfs/**                环境包下载/校验/解压/安装（RootfsInstaller 一族）
//   · terminal/**              proot 启动（ProotLauncher）、ProotBinaries、包管理/缓存清理、
//                              会话（SessionManager/SessionService）、体检依赖类
//                              （CacheCleaner / EnvSelfHeal / HermesEnv / ResMonitor / StorageAudit）
//   · rust/CoreNative          JNI 桥（sha256 / 解压 / 验签），rootfs 与抱朴两条链共用
//   · terminal/ime/ImeProbe    输入法探针的纯逻辑（页面仍在 :app，第三刀随 :app-term）
//
// 依赖方向（硬门禁）：:app → :terminal-core → :core；本模块**不许** import
// `com.example.zhengdao.baopu.**` / `com.example.zhengdao.ui.**` / `com.example.zhengdao.R`
// / `com.example.zhengdao.MainActivity`（门禁 `:terminal-core:testDebugUnitTest` 的
// `TerminalCoreBoundaryGateTest`）。
//
// 资源口径：体检/终端用到的字符串、图标、raw 脚本**随代码搬到这里**（唯一真源）。
// 搬家时 :app 侧留过同值副本，lint 把它们判成 UnusedResources（:app 已无引用）⇒ 副本已删。
// 体检页 UI 本身仍在 :app（派工单 §2 本刀不动 UI），它读的是 `EnvHealth` 的 Check 文案，
// 不直接引这些资源 id，所以删副本不影响它。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.zhengdao.termcore"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 与 :app 同一条线（minSdk 36 / targetSdk 36）。库模块的 minSdk 只是「消费方不得低于」。
        minSdk = 36
    }

    testOptions {
        // 与 :app 同口径：单测里触达 android.util.Log / org.json 的调用返回默认值。
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    // 锁定 NDK 版本（随 cpp/ 一起从 :app 搬来，保证本机与 CI 构建可复现）
    ndkVersion = "27.2.12479018"

    // 自研/上游伪终端 JNI（Termux terminal-emulator 的 native 半边，见 src/main/cpp/）
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
}

dependencies {
    // 单向：:terminal-core → :core（RunLog / ZhengdaoDirs / AppPrefs / TimeFormats / kb / notify）
    implementation(project(":core"))
    // Rust 核（rootfs 的 sha256/解压/验签）由 :native-core 提供，与 :app 共用同一份 .so
    implementation(project(":native-core"))

    // EnvironmentScope 之外的工具（ContextCompat 等）
    implementation(libs.androidx.core.ktx)

    // RootfsInstaller 的进度是 Compose State（UI 在 :app，状态在这里）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)

    // RootFS 下载与解压：OkHttp 断点续传 + commons-compress 解 tar + zstd-jni 解 zstd
    implementation(libs.okhttp)
    implementation(libs.commons.compress)
    implementation(libs.commons.lang3)
    implementation(libs.zstd.jni)

    testImplementation(libs.junit)
    // 门禁测试要读源码树，不需要 org.json 等运行时件
}
