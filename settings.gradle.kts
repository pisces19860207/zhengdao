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
// :core —— 证道与终端将来共用、且**不含任何终端/rootfs 代码**的一层（第二百四十批）。
// 只放「两边都要用」的东西：存储授权判定、目录真源、运行日志、时间格式、知识库、docx 解析、通知渠道。
// 依赖方向单向：:app → :core；:core 不许 import com.termux.** / com.example.zhengdao.terminal.** /
// com.example.zhengdao.baopu.**（门禁 `:core:testDebugUnitTest` 里的 CoreBoundaryGateTest）。
include(":core")
// :terminal-core —— 终端内核（第二百四十一批）。PTY/View、rootfs 安装器、proot 启动、
// 会话与体检依赖类、伪终端 JNI 与 proot 四件套 .so。
// 依赖方向单向：:app → :terminal-core → :core；:terminal-core 不许 import
// com.example.zhengdao.baopu.** / com.example.zhengdao.ui.** / :app 的 R 与 MainActivity
// （门禁 `:terminal-core:testDebugUnitTest` 里的 TerminalCoreBoundaryGateTest）。
include(":native-core")
include(":terminal-core")
// Macrobenchmark：独立的 com.android.test 模块（Compose 性能测试的标准载体）。
// 它不进 APK —— 只产出测试 APK，通过 targetProjectPath 指向 :app 并驱动真机跑基准。
include(":macrobenchmark")
