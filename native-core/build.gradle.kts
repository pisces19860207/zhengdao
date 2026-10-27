// 第二百四十一批修正（2026-10-27 蓝总管验收后补的）：`:native-core` —— 证道与终端**共用**的 Rust 核
//
// 为什么单独成模块（而不是留在 :terminal-core）：`libzhengdao_core.so` 干的活里有一半跟终端无关——
//   · `CoreNative.verifyEd25519`    Agent 清单**签名校验**（`ui/AgentManifest` 用；安全功能）
//   · `CoreNative.dirSizeBytes`     目录体积（`ui/SystemInfoProvider` 用；存储占用读数）
//   · `CoreNative.isRustAvailable`  能力探测（`ui/EnvHealth` 用）
//   · 而 rootfs 侧的 sha256 / 解压 / 验签也在用它
// ⇒ 若它留在 `:terminal-core`，主 APP 就会"为了校验签名而依赖终端模块"，第四刀拆终端时必断。
//
// 依赖方向（硬门禁）：:app → :native-core ← :terminal-core；本模块**不依赖**任何别的业务模块。
// 本模块只有 `jniLibs/`（人工构建入库的 .so）与 `CoreNative.kt`（JNI 声明），**不编 C/C++**
// （16KB 对齐与导出符号由 `tools/check-native-so.py` 门禁，路径已同步改到本模块）。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.zhengdao.nativecore"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 与 :app / :core / :terminal-core 同一条线（minSdk 36）
        minSdk = 36
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // RunLog（降级/回退要写一行账）；方向 :native-core → :core，合规
    implementation(project(":core"))

    // 只有 JNI 声明，没有别的依赖；单测断言导出符号与降级行为时才用 junit
    testImplementation(libs.junit)
}
