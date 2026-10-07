# Android 16 兼容性扫描报告

> 扫描日期：2026-10-07 · 目标：证道在 Android 16（API 36）上的行为变更逐项判定
> 基本盘：**minSdk=36**——本 App 只安装在 Android 16 设备上；真机（Honor Magic 5 Pro /
> PGT-AN10 / Android 16）日常使用即持续实证。**targetSdk=28 为 W^X 铁律不可升**
> （见 docs/milestones/已知限制.md §1-2），因此"以 targetSdk 为触发条件"的变更多数不适用。

## 逐项判定表

| # | Android 16 变更项 | 判定 | 证据 |
|---|---|---|---|
| 1 | **方向锁定失效**（≥600dp 屏上 screenOrientation 被忽略） | **N/A** | 全库 0 处 `screenOrientation`/`requestedOrientation`（manifest 与代码均无）；TerminalActivity 靠 configChanges 旋转不重建（AndroidManifest.xml:60） |
| 2 | **Android/data 直接访问被阻止** | **N/A** | 全库 0 处访问 `Android/data`；工作区走 `/sdcard/Download/证道`（Workspace.kt:30）或私有 getExternalFilesDir |
| 3 | **16KB 页大小** | **已处理** | cpp/CMakeLists.txt `-Wl,-z,max-page-size=16384` + abiFilters arm64 单架构（build.gradle.kts:39） |
| 4 | **MANAGE_EXTERNAL_STORAGE 行为** | **已实证** | 引导链完整（MainActivity.kt:118-143 两级 Intent）；真机 EnvHealth「存储权限」项日常显示已开启；实际存储判定不依赖它（走 READ+WRITE 运行时授权，ProotLauncher.kt:43 注释） |
| 5 | **本机网络访问权限**（新运行时权限） | **N/A（仅回环）** | 全库网络目标仅 127.0.0.1:14000（OcClient.kt:45，太极 REST/SSE）与公共 DNS/下载源，无局域网地址；太极连太极本机 serve 每日实证可用 |
| 6 | **预测性返回** | **不适用 + 已兜底** | targetSdk 28 不触发强制迁移；未声明 enableOnBackInvokedCallback。已知边界：material3 抽屉的 predictive back 在此配置下不生效——TaijiScreen.kt:110 已显式 BackHandler 兜底（真机复测 2026-10-07，注释在位） |
| 7 | **边到边 opt-out 下线** | **已处理** | v1.1 第三阶段（054b517）：WindowCompat.setDecorFitsSystemWindows(false) + Compose insets（statusBarsPadding/imePadding）；未用 enableEdgeToEdge、未设 statusBarColor，无下线影响面 |
| 8 | **cleartext HTTP** | **记录为待办** | manifest `usesCleartextTraffic="true"` 全局放开（targetSdk 28 默认禁，故显式开）；实际明文仅 127.0.0.1:14000。可收紧为 network_security_config 仅对 localhost 放行——**今天不做**（安全加固需真机回归，记 v1.1 待办） |
| 9 | **共享存储进一步收紧**（Android/data 之外） | **已实证** | READ 帽子摘除（d414dca）+ MANAGE 主路径；SAF 镜像已删（4bbfd21），剩余 SAF 仅 ACTION_OPEN_DOCUMENT 选 rootfs 安装包（TerminalActivity.kt:673，用户主动，不受影响） |

## 结论

**9 项变更：6 项 N/A 或已处理，2 项已实证，1 项（cleartext 收紧）记为 v1.1 可选加固待办。无阻塞性风险。**

结构性边界不变式（再次确认）：
- targetSdk 28 = proot 从数据目录 exec 的 W^X 豁免，**不可谈判**（已知限制.md §2）
- minSdk 36 = 只服务 Android 16，无需向下兼容矩阵
- 需季度盯 AOSP 对 untrusted_app exec 豁免的动向（已知限制.md §6）

## 扫描附带清理

- proguard-rules.pro：删除指向已退役 Pty 类的失效 keep 规则（随 Rust PoC commit 一并处理）
- docs/milestones/已知限制.md：修正指向已删除 mirror/PhoneMirror.kt 的过时引用（本次 commit）
