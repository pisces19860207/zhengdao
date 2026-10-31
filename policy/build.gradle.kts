// 第二百九十三批（宰相抽模块）：`:policy` —— 宰相的**白名单匣**。
//
// 匣子里只装「判」，不装「做」：
//   · HarmonyMediator   238 的档位机（零 IO／零时钟／零 Android）
//   · MediatorJudge     258 的判官（任务态边界＋三条禁令词表）
//   · ReframeFilter     238 的语气转译（爱称白名单／攻击词表）
//   · PatienceProfile   245 的耐心画像（阈值与衰减）
//   · MediatorWiring    258 的接线层（换档＋落账＋回灌那一句；仍零 IO）
//   · LedgerPort ＋ 208 的行词汇（DecisionKind/Group/Audit/Actor/Draft）——
//     账本接口化一刀（本批只切这一刀，其余账本另立单，不许铺开）
//
// 边界（由 `PolicyBoundaryGateTest` 钉住，红得起）：
//   ① 依赖白名单：**只许** `project(":core")` 与 Kotlin stdlib。禁 okhttp／openai／
//      android.*／androidx.*／java.net／`baopu.kb.*`／`baopu.inspect.*`／`baopu.agent.*`，
//      也禁任何**具体存储实现**（`DecisionLedger` 那一类 `object`）——`:policy` 只认 [LedgerPort]。
//   ② 依赖方向单向：`:app → :policy`。本模块不许 `project(":app")`。
//   ③ 为什么形制是 `com.android.library` 而不是纯 JVM 模块：本仓 Gradle 一律离线
//      （CONTRIBUTING.md），`org.jetbrains.kotlin.jvm` 的插件标记不在本机依赖缓存里，
//      选纯 JVM 就得联网预热依赖——那是条「今天能红、换机器就红」的路。
//      所以 android.* 的禁绝走**源码级**（与 `:core` 的 `CoreBoundaryGateTest` 同一口径：
//      那个模块同样是 com.android.library，照样把依赖方向钉死了），
//      依赖白名单另扫构建脚本：**多写一行 implementation 就红**，比编译期更严。
//   ④ 包名不动（仍是 `com.example.zhengdao.baopu`）：搬模块改的是**编译单元**，不是 import 面；
//      包名一改，全仓引用都要跟着动，那就不是「搬」了。代价是同包跨两个编译单元 ⇒
//      宰相的 `internal` 必须放宽成公开（Kotlin 的 internal 以编译单元为界），
//      放宽清单与理由见交付报告。
plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.example.zhengdao.policy"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        // 与 :core／:app 同一条线（minSdk 36，依据 docs/ERRATA.md E-134）；库模块的 minSdk
        // 只是消费方下限，不改 :app 的任何产物口径。
        minSdk = 36
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

dependencies {
    // 白名单只有这一行：:core 的 RunLog（调解器判出违规要落一行日志，292 的白名单早已认它）。
    // 想加第二行之前先看 `PolicyBoundaryGateTest`：构建脚本扫描会把多出来的依赖判红，
    // 而「让它变绿」的正确做法是把需求挪回 :app，不是往白名单里塞名字。
    implementation(project(":core"))
}
