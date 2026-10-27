// 第二百四十批（终端解耦第一刀）：`zh :core` —— 证道与（将来的）终端 APP 共用的一层。
//
// 本模块**只装"两边都要用、且与终端无关"的东西**：
//   · EnvironmentScope   存储授权判定（原先借 ProotLauncher.storageGranted）
//   · ZhengdaoDirs       目录真源（原 terminal/Store ＋ terminal/Workspace 的路径解析 ＋ 资料库目录名）
//   · RunLog             运行日志（原 rootfs/RunLog，断环后零依赖）
//   · TimeFormats        时间戳格式化（原 util/TimeFormats，纯工具）
//   · kb/KnowledgeBase   资料库机械层 ＋ kb/KnowledgeBaseSummarizer（纯字符串/JSON）
//   · doc/DocxTextExtractor  .docx 抽正文（纯 JDK）
//   · notify/AppNotifications 通知渠道注册表（原 terminal/NotificationChannels，渠道 id 一字不改）
//
// 纪律（门禁 `CoreBoundaryGateTest` 钉住）：
//   :core **不许** import `com.termux.**`、`com.example.zhengdao.terminal.**`、`com.example.zhengdao.baopu.**`
//   —— 依赖方向只能是 :app → :core，反过来就说明"还没解耦完，只是换了个目录"。
plugins {
    alias(libs.plugins.android.library)
    // 第二百四十八批：发布到 mavenLocal（publishing 的 Kotlin DSL 访问器要求插件在 plugins{} 里 apply）
    `maven-publish`
}

android {
    namespace = "com.example.zhengdao.core"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 与 :app 同一条线（第㉕刀起 minSdk 36 / targetSdk 36，依据 docs/ERRATA.md E-134）。
        // 库模块的 minSdk 只是「消费方不得低于」的下限，不改变 :app 的任何构建产物口径。
        minSdk = 36
    }

    testOptions {
        // 与 :app 同口径：单测里触达 android.util.Log / org.json 的调用返回默认值。
        unitTests.isReturnDefaultValues = true
    }

    // RunLog 的 logcat 镜像按 BuildConfig.DEBUG 分流（N-4 A 案），库模块必须开 BuildConfig。
    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // AppPrefs 的 `edit { }`、EnvironmentScope 的 ContextCompat.checkSelfPermission
    implementation(libs.androidx.core.ktx)

    // IssueCenter 的状态是 Compose State（第二百四十一批随终端搬来：终端内核与抱朴都要报问题，
    // 而它的 `State` 面是给 UI 观察的——只引 runtime，不引 UI）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.runtime)

    testImplementation(libs.junit)
    // 单测里的 org.json 真实实现（KnowledgeBaseSummarizerTest 直接构造 JSONObject）
    testImplementation(libs.json)
}


// ── 第二百四十八批（终端解耦第四刀）：把 :core 发布成 Maven 产物 ──────────────────────
// 为什么：终端搬去 zhengdao-term 之后，新仓**只引产物、不复制源码**（设计稿 §1 选型①）。
// 版本号与主 APP 的 versionName 同步（2.2.1）——改 :core 就要发一次版本，靠版本号防漂移。
//
// 为什么不用 `from(components["release"])`：AGP 9 里没有生成名为 release 的软件组件
// （singleVariant 也不生），配置期直接报 "SoftwareComponent with name 'release' not found"。
// 所以直接挂 AAR 任务 + 手写 POM —— 产物一样是 .aar + .pom，坐标完全可控。
publishing {
    publications {
        create<MavenPublication>("release") {
            groupId = "com.example.zhengdao"
            artifactId = "zhengdao-core"
            version = "2.2.1"
            afterEvaluate {
                artifact(tasks.named("bundleReleaseAar"))
            }
            pom {
                packaging = "aar"
                name.set("zhengdao-core")
                description.set("证道 / 证道终端共用的一层：目录真源、存储授权判定、RunLog、KB、通知、PathGuard")
            }
        }
    }
}
