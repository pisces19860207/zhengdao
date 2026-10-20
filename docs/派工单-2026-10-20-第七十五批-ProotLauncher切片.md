# 第七十五批 · 结构拆分第四阶段：ProotLauncher 启动计划纯逻辑抽取（§B 80 切片）

- 派单：2026-10-20 蓝总管（用户：「再给workbuddy派个大活，我今天要把他们都榨干」）
- 基线：开工时最新 `main`（出单时 `ac30e31c`）。先 `git fetch origin`，确认 `git rev-list --left-right --count origin/main...HEAD` **左侧为 0**。
- 承接：workbuddy
- 排期：**64（状态机）→ 72（Controller）→ 74（TerminalActivity）→ 75**；本单只在前三单收口后做。
- 红线：只动 `rootfs/ProotLauncher.kt` ＋ 新建的纯逻辑 helper/test 文件；**不碰** `RootfsInstaller` / `RootfsDownloader`（65 在飞）、`TerminalActivity`（74）、`RunEngine`（64）、`BaopuChatController`（72）、Rust、`AndroidManifest.xml`、版本号；**启动行为/参数/环境变量零变化**；不合并、不推送；不跑真机。

## 0. 为什么是它

`ProotLauncher.buildLaunchPlanLocked()` 是 §B 80 点名的 574 行单函数，负责 proot 启动计划的 env/mount/命令拼装。它碰的是「启动参数」而非 rootfs 内容，和 65 的安装/解压是两条线；把纯拼装逻辑抽出来，才能给启动链路写 JVM 级对账用例。

## 1. 先出方法清单，再动手

- 统计 `ProotLauncher.kt` 里 >80 行的方法/局部块，列「方法/块 | 行数 | 依赖（File/Env/Session/Android/纯） | 可抽性」；
- 只挑 **3–5 个纯逻辑片段**（输入普通值，输出普通值/数据类，不碰 File 读写、不碰 Session 写、不碰 Android Context）：
  - env 变量表拼装（只拼 map/list，不写进程）；
  - proot 参数串拼装（`--bind` / `--link2symlink` / 工作目录，输入路径值）；
  - 存储/权限路径的判定与选择（输入布尔/路径，不查真实文件系统）；
  - 终端启动命令/autocmd 的参数构造（只构造，不执行）；
  - 标题/模式（fallback）判定。
- 每个片段抽到新文件（如 `rootfs/ProotLaunchRules.kt`），`ProotLauncher` 只留调用；**不改参数顺序/内容、不改启动结果**。
- 不拆整个 `ProotLauncher`、不新建第二套启动配置、不把 File/Context 搬进 helper。

## 2. 测试

- 每个抽出的纯函数：JVM 单测覆盖正常/边界/空值/特殊路径（空格/中文/软链，至少 3 例）；
- 源码门禁：`ProotLauncher` 对应逻辑只剩调用，不得留两份实现；抽出的参数串与改前逐字节对账（典型用例给「改前/改后」字符串）；
- 负例：把 helper 参数顺序改一处 ⇒ 对账用例红；
- 全量单测 0 失败/0 错误，跳过数不恶化；lint 预算不升；diff 只应出现「等值搬移 + 调用替换」。

## 3. 交付物

- 分支 `refactor/proot-launch-pure`，从开工时最新 `main` 拉；建议「清单 / 抽取 / 测试」三笔提交。
- 报告 `证道设计报告/结构切片-ProotLauncher纯逻辑-交付-2026-10-20.md`：方法清单、抽了哪 3–5 个、参数串改前/改后对账、用例与负例、全量读数、未动项。
- 不合并、不推送、不升版本。

## 4. 打回

- 改了启动参数/顺序/环境变量；拆整个 launcher；把 File/Context 搬进 helper；动了 64/65/72/74 在飞文件；没有对账用例/负例；合并/推送。

## 5. 交接

先读 `AGENTS.md` 与 `docs/协作规约.md`，开工前跑 `python tools\zd.py preflight`。干完把分支、提交、报告交给用户；验收/合并由用户安排（蓝总管只做账与派工单）。
