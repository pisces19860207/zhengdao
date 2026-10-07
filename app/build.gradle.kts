import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 版本号单点定义：defaultConfig 与输出文件名共用（GitHub Actions 产物带版本，用户可辨新旧）
// v1.3.0（2026-10-07）：**可靠性清债 + CI 止血**——无新增功能面。
//   启动链路三修（~/.local/bin 进 PATH / 启动幂等 / 已装误判成未装）；opencode 启动事故
//   （截断文件冒充已安装）+「进终端唯一入口静默失败」；终端页唯一化；E2 storageGranted
//   单一判定源；B2 移除终端 npm 版 opencode；CI 补 assembleRelease + 统一签名密钥。
// v1.2.0（2026-10-07）：**终端环境优化**——不修 UI、不加功能，只动终端里的环境本身。
//   卸载终端 npm 版 opencode；修复太极配置路径（v1.1.1 曾误指终端 taiji 的 guest 死路径）；
//   网络四项与 IP 钉住的尝试及撤除；工作区 .ignore；资源监控与体检三态。
// 版本序列：v1.0.0(12) → v1.1 → v1.1.1(13) → v1.2.0(14) → v1.3.0(15)
// ⚠️ 14 的归属曾有过争议：v1.1.1 裁决④一度把 14/1.2.0 划给 ChatGPT 线，
//    该线已归档，14 归还本计划（2026-10-07 用户拍板）。
// ⚠️ v1.3.0 是**可靠性清债版**：启动链路三修 + OpenCode 启动事故 + 终端页唯一化 +
//    E2 单一判定源 + B2 移除终端 npm 版 opencode + CI 止血（assembleRelease / 签名统一）。
//    没有新增功能面，故 versionName 只进 patch 级的语义在这里体现为 1.2.0 → 1.3.0
//    （用户 2026-10-07 拍板用 1.3.0 而非 1.2.1）。
val appVersionName = "1.3.0"

// ── 签名钥匙：由环境变量**显式钉死**，不再依赖 AGP 自己猜目录（docs/ERRATA.md E-014 §7）──
// 背景（2026-10-07 深夜实测）：CI 把本机那把 debug keystore 还原到 $HOME/.android/debug.keystore，
//   keytool 读出来的指纹**就是**存量用户那把 key（44e2fe86…a3be18）——可打出来的 release 包
//   却是**另一把随机 key** 签的（连着两次各不同：18e5268a… / 9409433d…）。
//   ⇒ runner 上 AGP 解析到的 store 并不是我们写进去的那一份；原因尚未定论
//     （`:app:signingReport` 已进 CI 日志常驻诊断）。
// 因此：CI 里设 ZHENGDAO_KEYSTORE_FILE=<keystore 绝对路径>，这里把 AGP 内建「debug」签名配置的
//   storeFile 直接钉到该路径。本地不设这个变量 ⇒ 行为与从前一字不差（仍用 ~/.android/debug.keystore）。
val zdKeystorePath: String? = System.getenv("ZHENGDAO_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

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
        // 最低安装门槛：安卓 16（API 36）。实测环境为荣耀 Magic 5 Pro
        // （PGT-AN10 / MagicOS 10.0.0.175 CHNC00E175R208P6 / Android 16）；
        // Android 15 及以下未适配未验证（README 有明确声明），直接拒绝安装。
        // ⚠️ 2026-10-07 更正：原注释写「MagicOS 11」，实测 build 号对不上——MagicOS 10
        //    才基于 Android 16（MagicOS 11 对应 Android 17），记录见 docs/acceptance/v1.1-2026-10-07.md。
        minSdk = 36
        versionCode = 15
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

    // 显式覆盖 AGP 内建「debug」签名配置的 storeFile（见文件顶部 zdKeystorePath 的说明）。
    // ⚠️ 只覆盖 storeFile，不新建签名配置：debug / release / benchmark 三个变体本来就都指
    //    这一份（:82、:95），覆盖一处即三处同时生效。
    if (zdKeystorePath != null) {
        signingConfigs {
            getByName("debug") {
                storeFile = file(zdKeystorePath)
                System.getenv("ZHENGDAO_KEYSTORE_PASSWORD")?.takeIf { it.isNotBlank() }?.let { storePassword = it }
                System.getenv("ZHENGDAO_KEY_ALIAS")?.takeIf { it.isNotBlank() }?.let { keyAlias = it }
                System.getenv("ZHENGDAO_KEY_PASSWORD")?.takeIf { it.isNotBlank() }?.let { keyPassword = it }
            }
        }
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
        // 供 :macrobenchmark 测量的变体：与 release 同配置（R8 + 资源收缩），只改三处 ——
        // ① 不可调试（Macrobenchmark 拒绝测 debuggable 构建）；
        // ② 同用 debug 签名：这样 install -r 能直接覆盖用户机上已有的包，**不丢 Debian 环境**；
        // ③ profileable：AGP **不会**自动注入（已核对合并清单确认），而 Macrobenchmark 的
        //    FrameTimingMetric 要靠 perfetto 采帧，release 类构建不声明它就会采不到帧数据。
        // 它不参与任何分发，只为产出"贴近 release 的可测体"。
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
            isDebuggable = false
            isProfileable = true
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
    // 多源回退逻辑测试：本地环回假服务器，真实走 OkHttp 栈（零外网依赖）
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
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

    // ⚠️ 仅 benchmark 变体（绝不进 release）：Macrobenchmark 在每轮之间要广播
    //    DROP_SHADER_CACHE 清掉 GPU shader 缓存，否则第一轮之后的启动数据会被缓存"美化"，
    //    测出来的是缓存热启动而不是冷启动。该广播的接收器来自本库，缺了它 Macrobenchmark
    //    会直接抛 IllegalStateException 拒绝开测（真机已复现）。
    //    只在 benchmark 变体引入 —— release 不背这个依赖，产物零变化。
    // 用 add("<name>") 而非 Kotlin DSL 访问器：AGP 9 不为脚本里 create() 出来的 buildType
    // 生成 benchmarkImplementation 访问器（已实测报 Unresolved reference）。
    add("benchmarkImplementation", "androidx.profileinstaller:profileinstaller:1.4.1")

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

// ─────────────────────────────────────────────────────────────────────────────
// 构建期公钥断言（M3 红线，2026-10-07 落地）
//
// `AgentManifest.PUBLIC_KEY_B64` 是 Agent 清单（manifest）Ed25519 验签的**信任根**：
// 它固化在 APK 里，验签不过 = 整份清单被拒。原先只有运行时兜底断言（长度 / 非占位符），
// 而那发生在用户设备上——改错了照样出包，装上去才炸。这里把它挪到构建期钉死。
//
// 断言四件事（任一不满足 → 构建立即失败）：
//   ① 源码里确实存在 PUBLIC_KEY_B64 赋值（防被误删 / 改名）；
//   ② 值非空、非占位符（xxx / TODO / placeholder / replace…）；
//   ③ base64 解码后恰好 32 字节（Ed25519 raw 公钥长度）；
//   ④ 与下面 expectedAgentPubKeyB64 逐字相同（防有人悄悄换钥匙）。
//
// ④ 才是核心：只校验格式的话，换一把攻击者可控的公钥照样能通过。
// 若确为有意轮换公钥：先改本文件的 expectedAgentPubKeyB64，再改 AgentManifest.kt 的值，
// 并同步 tools/sign-agents-manifest.py 配套的私钥（私钥在仓库外 ~/.zhengdao-keys/）。
// ─────────────────────────────────────────────────────────────────────────────
val expectedAgentPubKeyB64 = "LW7JtVXGiZGrFBFl8x1wlyPBtBez7tNNWzz4AhSI54Q="

val assertAgentPublicKey = tasks.register("assertAgentPublicKey") {
    description = "构建期断言 AgentManifest.PUBLIC_KEY_B64（manifest 验签信任根）"
    group = "verification"
    val manifestKt = layout.projectDirectory.file(
        "src/main/java/com/example/zhengdao/ui/AgentManifest.kt"
    )
    val expected = expectedAgentPubKeyB64
    inputs.file(manifestKt).withPropertyName("agentManifestKt")
    // 断言极廉价（读一个小文件 + 正则），不做增量跳过——免得「跳过」看起来像「通过」。
    outputs.upToDateWhen { false }
    doLast {
        val src = manifestKt.asFile
        if (!src.isFile) error("[assertAgentPublicKey] 找不到 $src —— 公钥是验签信任根，不可缺失")
        val text = src.readText(Charsets.UTF_8)
        val m = Regex("""PUBLIC_KEY_B64\s*=\s*"([^"]*)"""").find(text)
            ?: error("[assertAgentPublicKey] AgentManifest.kt 里找不到 PUBLIC_KEY_B64 赋值")
        val actual = m.groupValues[1]
        check(actual.isNotBlank()) { "[assertAgentPublicKey] PUBLIC_KEY_B64 为空" }
        check(!Regex("(?i)xxx|todo|placeholder|replace|changeme|占位").containsMatchIn(actual)) {
            "[assertAgentPublicKey] PUBLIC_KEY_B64 仍是占位符：$actual"
        }
        val raw = Base64.getDecoder().decode(actual)
        check(raw.size == 32) {
            "[assertAgentPublicKey] PUBLIC_KEY_B64 解码后应为 32 字节（Ed25519 raw 公钥），实际 ${raw.size}"
        }
        check(actual == expected) {
            "[assertAgentPublicKey] PUBLIC_KEY_B64 与构建期固化值不一致，构建中止。\n" +
                "  构建期固化: $expected\n" +
                "  源码实际值: $actual\n" +
                "  若确为有意轮换：请同步更新 app/build.gradle.kts 的 expectedAgentPubKeyB64、" +
                "AgentManifest.kt 的公钥、以及 tools/sign-agents-manifest.py 配套的私钥。"
        }
        logger.lifecycle("[assertAgentPublicKey] OK：公钥已固化（${actual.take(8)}…，32 字节）")
    }
}

// 挂到所有变体的编译前置：assembleDebug / assembleRelease / bundle* 都绕不过去。
tasks.matching { it.name.matches(Regex("pre[A-Z].*Build")) }.configureEach {
    dependsOn(assertAgentPublicKey)
}