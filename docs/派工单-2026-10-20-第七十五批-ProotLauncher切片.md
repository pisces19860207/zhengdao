# 第七十五批 · 结构拆分第四阶段：ProotLauncher 启动计划纯逻辑抽取（§B 80 切片）

- 派单：2026-10-20 蓝总管（用户：「再给workbuddy派个大活，我今天要把他们都榨干」）
- 基线：开工时最新 `main`（出单时 `ac30e31c`）。先 `git fetch origin`，确认 `git rev-list --left-right --count origin/main...HEAD` **左侧为 0**。
- 承接：workbuddy
- 排期：**64（状态机）→ 72（Controller）→ 74（TerminalActivity）→ 75**；本单只在前三单收口后做。
- 改单：2026-10-20 出单后原地更正一处硬伤——原红线写的 `rootfs/ProotLauncher.kt` **路径有误**（`rootfs/` 包是 65 在飞的 `RootfsInstaller`/`RootfsDownloader` 所在地），实际文件是 `terminal/ProotLauncher.kt`；同时按最新代码补了行号锚点与候选片段口径。其余不变。
- 红线：只动 `terminal/ProotLauncher.kt` ＋ 新建的纯逻辑 helper/test 文件；**不碰** `RootfsInstaller` / `RootfsDownloader`（65 在飞）、`TerminalActivity`（74）、`RunEngine`（64）、`BaopuChatController`（72）、`ProotBinaries` / `PinnedAssets` / `Workspace` / `Store` / `EnvSelfHeal`（只读协作类，调用原样保留）、Rust、`AndroidManifest.xml`、`build.gradle.kts`、`libs.versions.toml`、版本号；**启动行为/参数/环境变量零变化**；不合并、不推送；不跑真机。

## 0. 为什么是它

`buildLaunchPlanLocked()`（`terminal/ProotLauncher.kt:112`–`:686`，实测 575 行，§B 80 账上 574 行；文件共 843 行）是启动链路的拼装心脏：env 变量表、proot 参数串、存储 bind 判定、tmux/裸 bash 收尾命令全在这一个函数里，中间还夹着一路 rootfs 预置写盘（profile.d / 横幅 / 软链）。它碰的是「启动参数」而非 rootfs 内容，与 65 的安装/解压是两条线；把纯拼装逻辑抽出来，才能给启动链路写 JVM 级对账用例。**写盘、`RunLog` 日志点、`buildLaunchPlan` 对 `RootfsInstaller.withPrepareLock` 的持锁调用全部原位保留**（锁口径见 `:101`–`:111` KDoc）——本单只搬「值进值出」的拼装与判定。

## 1. 先出方法清单，再动手

- 统计 `ProotLauncher.kt` 里 >80 行的方法/局部块，列「方法/块 | 行数 | 依赖（File/Env/Session/Android/纯） | 可抽性」；
- 只挑 **3–5 个纯逻辑片段**（输入普通值，输出普通值/数据类，不碰 File 读写、不碰 Session 写、不碰 Android Context）：
  - env 变量表拼装（`:452`–`:499`，含 TZ 回落选择 `:451`）——输入路径/开关值，输出 `List<String>`，不写进程；
  - proot 参数串拼装（`:501`–`:628`：固定段 `:502`–`:512`、共享存储 bind `:515`–`:577`、内存上限前缀 `:586`–`:588`、收尾命令三分支 `:593`–`:628`）——输入路径/布尔，输出 `List<String>`；
  - 存储/权限路径的判定与选择（`storageGranted` `:56` 与 `wsShared` 的组合判定，当前 `:515`/`:573` 各判一次 ⇒ 合成一处纯判定，不把真实文件系统查询搬进 helper）；
  - 回退计划的构造（`fallbackPlan` `:831`–`:843`：输入两个目录路径，输出 `LaunchPlan`，含 env 五条拼装）；
  - 启动就绪/回落判定口径（`rootfsReady` `:219`、回落早返 `:149`/`:234` ⇒ 合成纯布尔判定）。
- 每个片段抽到新文件（如 `terminal/ProotLaunchRules.kt`），`ProotLauncher` 只留调用；**不改参数顺序/内容、不改启动结果**。
- `isLegalSessionName`（`:97`）、`tmuxConfAppend`（`:742`）、`sharedCacheBindings`（`:794`）**已是纯函数**：只登记为 helper 文风样板，不重搬、不改写。
- 不拆整个 `ProotLauncher`、不新建第二套启动配置、不把 File/Context 搬进 helper。

## 2. 测试

- 每个抽出的纯函数：JVM 单测覆盖正常/边界/空值/特殊路径（空格/中文/软链，至少 3 例）；
- 源码门禁：`ProotLauncher` 对应逻辑只剩调用，不得留两份实现；抽出的参数串与改前逐字节对账（典型用例给「改前/改后」**完整 `args`/`env` 数组**，不只给片段）；
- 负例：把 helper 参数顺序改一处 ⇒ 对账用例红；把调用删掉 ⇒ 编译/用例红；
- 全量单测 0 失败/0 错误，跳过数不恶化；lint 预算不升；diff 只应出现「等值搬移 + 调用替换」。

## 3. 交付物

- 分支 `refactor/proot-launch-pure`，从开工时最新 `main` 拉；建议「清单 / 抽取 / 测试」三笔提交。
- 报告 `证道设计报告/结构切片-ProotLauncher纯逻辑-交付-2026-10-20.md`：方法清单、抽了哪 3–5 个、参数串改前/改后对账、用例与负例、全量读数、未动项。
- 不合并、不推送、不升版本。

## 4. 打回

- 改了启动参数/顺序/环境变量；拆整个 launcher；把 File/Context 搬进 helper；动了 64/65/72/74 在飞文件或只读协作类；没有对账用例/负例；合并/推送。

## 5. 交接

先读 `AGENTS.md` 与 `docs/协作规约.md`，开工前跑 `python tools\zd.py preflight`。干完把分支、提交、报告交给用户；验收/合并由用户安排（蓝总管只做账与派工单）。
