# 功能台账（FEATURE-LEDGER）

> 取证基准：`origin/main`，截至 `682dceb`（2026-10-07 23:0x），共约 210 条提交（首提交 `34892b5` 2026-10-03）。
> ✅ 2026-10-07 23:1x 更新：`origin/main` 已推进到 `682dceb`（新增 `682dceb` 一颗「三道闸 + 删 ApiKeyStore」）。
> ✅ 2026-10-07 23:5x 更新（集成完成）：原先标着「尚未合入 main」的那批改动**已经合入** ——
> `fix/terminal-single-session` 的 `da3d8eb` / `0d0f492` / `aa1907e` 与 `v1.3` 的
> `c64185e` / `47d56f9` / `dc4369c` 已通过两个 `--no-ff` 合并进 `main`，本节表格无需再对号分支。
> 表中每个 hash 都来自 `git log origin/main` 实测，不是推测。已移除的功能集中在 §3，§2 只列在用/半残。

## 0. 开工前必读

1. 先 `git fetch`，再用 `git rev-list --left-right --count origin/main...HEAD` 确认自己落后多少；落后就先 `rebase`，**不要在旧基准上动手**。
2. 动手前先在 §2 查这个功能在不在，再用 `git log --all -S<关键词>`（内容级）和 `git log --all --diff-filter=D -- <路径>`（文件级）各搜一遍。
3. 一个 worktree = 一个分支 = 一个 agent；`main` 那个工作树只做集成，绝不允许直接在上面改代码。

## 1. 工作树地图

**当前（2026-10-08 归置后）：只有 1 个工作树。**

| 目录 | 分支 | 用途 | 负责人 |
| --- | --- | --- | --- |
| `C:\Users\guoli\AndroidStudioProjects\zhengdao` | `main`（集成时）/ 自己的功能分支 | 唯一的仓库目录；**只在集成时用 `main`**，写代码时切到自己的工作树或分支 | 共用（只做集成，勿在此改码） |

已消失的工作树（2026-10-08 归置，分支都已并入 `main`，删了不丢内容）：

| 曾经的目录 | 分支 | 现状 |
| --- | --- | --- |
| `zhengdao-wt-v13` | `v1.3`（`dc4369c`） | 目录已删；分支已删（内容在 `main` 的 `741eb5d`） |
| `zhengdao-wt-session` | `fix/terminal-single-session`（`aa1907e`） | 目录已删；分支已删（内容在 `main` 的 `741eb5d`） |
| `zhengdao-taiji-ui` | `feat/taiji-compose-ui`（`406b885`） | 目录已删；分支已删（内容在 `main`） |
| `zhengdao-v100-build` | — | **本来就不是 git 仓库**（一堆 lint 缓存残渣），已删 |

其余本地分支：`feat/v2.0-r1-rust-core-16kb`（`8484d46`）**未并入 main 但内容已冗余**
（`af37010` / `fc74cd5` 以另一种方式收了同样的东西），远端也有同名分支 ⇒ 留着不动，别在里面继续干活。
tag `wip-snapshot-2026-10-07-2258` 仍保留（它指向的 `dd70bf3` 是 WorkBuddy 终端单会话的 WIP 快照，
分支 `wip/workbuddy-terminal-2026-10-07-2258` 已删，内容由 `da3d8eb` 取代）。

> **2026-10-07 23:1x 变更**：WorkBuddy 的在途改动原先直接躺在主工作树的 `main` 上
> （旧台账记为「落后 9 个提交，有在途改动」），违反铁律 #3，已按 `AGENTS.md` 迁出：
> 主工作树 `git merge --ff-only origin/main` 到 `682dceb` 并清干净，在途改动挪到
> 新建的 `zhengdao-wt-session` 工作树 / `fix/terminal-single-session` 分支，
> 并 rebase 到最新基准后再提交（`da3d8eb`）。
> 同一批改动另有一个 WIP 快照，由 Zcode 在迁移前落盘保护，见
> `wip/workbuddy-terminal-2026-10-07-2258`（=`dd70bf3`，基于旧基准 `3788eac`，
> 内容为 `da3d8eb` 的子集，**已可弃用**）。
>
> **2026-10-07 23:5x 变更（集成）**：`v1.3`（`c64185e` 让号 E-020 / `47d56f9` 钩子放行合并提交 /
> `dc4369c` CI 签名修复）与 `fix/terminal-single-session`（`da3d8eb` / `0d0f492` / `aa1907e`）
> **都已并入 `main`**，冲突只有两处：`docs/ERRATA.md`（编号让号 → E-020 排在 E-018/E-019 之后）
> 与这份台账本身。同一批还改了 `tools/hooks/pre-commit`：**main 上的合并提交放行**
> （集成本来就该在 main 上做，要拦的是「直接在 main 上改代码再 commit」）。
>
> **2026-10-08 00:xx 变更（归置 + 签名修复，也是本台账工具段被改写的一次）**：
> 用户拍板「把 C 盘上三个 agent 的证道项目归置归置，定好规矩、用法和边界」之后做了三件事 ——
> ① **删**：`zhengdao-v100-build`（非 git 仓库的空壳）、`zhengdao-share\`（sha256 对不上的残件 zst）、
> `C:\Users\guoli\zhengdao-backup\rootfs-backup.tar`（1.17 GB）、`.zhengdao-backup\` 里的 WIP patch、
> Temp 下我方取证残留约 84 MB；三个已并入 main 的工作树与分支一并清掉，只剩 `zhengdao` 一个工作树。
> ② **定规矩**：新增 `docs/协作规约.md`（三主体身份 / 五条铁律 / 工具用法 / 边界 / 冲突高发文件 / 事故对照表），
> 根目录 `AGENTS.md` 改写成指向它的短入口。③ **统一工具**：`tools/zd.py`（纯标准库，三 agent 通用）
> 成为唯一入口，`agent-preflight.ps1` / `install-hooks.ps1` 降级成转发到它的 Windows 薄壳。
> 同批还修了 CI 签名：见 `docs/ERRATA.md` E-014 §8（钥匙改成显式输入 `ZHENGDAO_KEYSTORE_FILE`）。
>
> **2026-10-08 01:xx 变更（rootfs 安装包缓存与 opencode 同处 + 「检测到就自动安装」修好）**：
> 用户反馈「环境的包应该和 opencode bionic 放在一起、检测到就自动安装、没有才下载」——
> 查实 App 自己下载的包落 `Download/zhengdao/cache`（拉丁名，`51cd425` 引入），
> 而自动安装只查 `Download/证道/debian-13.7-base-arm64.tar.zst`（`6e16f16`/v0.6.0 引入），
> **两个文件夹从引入起就不一致 ⇒ 自己下过的包自己认不出**。修法（`docs/ERRATA.md` E-021）：
> `RootfsCache` 公共缓存搬到 `Download/证道/rootfs/`（与 `opencode/` 并列）、`migrateLegacy()` 自动搬旧包
> （含 `.part` 续传残片）、清理判据收紧成「只认 `debian-` 前缀」、新增
> `RootfsCache.findLocalArchive()` 作为**唯一的"本地有没有包"入口**（`TerminalActivity` 委托它）；
> 顺带把 `OcManager.checkUpdate()` 拆出 `checkUpdateDetailed()`，不再把「查不到」说成「已是最新」；
> 太极抽屉底部新增 OpenCode 版本 + 更新入口（`OcVersionFooter`）。
> CI 那边也已闭环：run `37647338521` 18 步全绿，`latest` 的 APK 实测签名 `44E2FE86…A3BE18` = 存量 key。

共同 `.git`：`C:\Users\guoli\AndroidStudioProjects\zhengdao\.git`（所有 worktree 共用；hook 装一次全局生效）。

## 2. 功能台账

| 功能 | 现状 | 定盘提交 | 位置 |
| --- | --- | --- | --- |
| 终端引擎（Termux terminal-view 原生接入） | ✅ 在用 | `5a0fd2e` | `app/src/main/java/com/termux/view/TerminalView.java`、`app/src/main/java/com/example/zhengdao/TerminalActivity.kt` |
| 终端交互增强（复制/粘贴/快捷键条/配色/滚轮） | ✅ 在用 | `c456437` | `app/src/main/java/com/termux/view/TerminalView.java` |
| tmux 会话保持 | ✅ 在用 | `51cd425` | `terminal/SessionManager.kt` |
| tmux 上下分屏（绿点标识） | ✅ 在用 | `7bb2889` | `terminal/SessionManager.kt` |
| 保活层（前台服务 + 会话所有权 + WakeLock） | ✅ 在用 | `68be789` | `terminal/SessionService.kt`、`terminal/SessionManager.kt` |
| 终端页唯一化（单会话模型，用户拍板） | ✅ 在用 | `b221603` | `app/src/main/AndroidManifest.xml`、`MainActivity.kt` |
| 一键安装 Agent（状态机 + 重试） | ✅ 在用 | `ca8825c` | `ui/AgentInstaller.kt`、`ui/AgentRepository.kt` |
| Agent 清单 ed25519 验签 | ✅ 在用 | `5d8e8e2` | `ui/AgentManifest.kt`、`rootfs/agents.json`（+`.sig`） |
| Agent 卡片版本显示 / 更新标记 | ✅ 在用 | `14b3d5e` | `ui/AgentRepository.kt`、`ui/HomeScreen.kt` |
| opencode 更新检查 | ✅ 在用 | `c07eec3` | `ui/SettingsScreen.kt`、`oc/OcManager.kt` |
| 环境体检三态 + 自愈 | ✅ 在用 | `df2b3eb` | `ui/EnvHealth.kt`、`terminal/EnvSelfHeal.kt` |
| 缓存清理（两档） | ✅ 在用 | `36a927d` | `terminal/CacheCleaner.kt`、`ui/SettingsScreen.kt` |
| 通知 4 渠道 | ✅ 在用 | `8127a49` | `terminal/NotificationChannels.kt` |
| 资源监控 | ✅ 在用 | `7d0b08c` | `terminal/ResMonitor.kt` |
| 工作区边界（内置文件夹浏览器） | ✅ 在用 | `2b60a44` | `terminal/Workspace.kt` |
| 共享存储授权（MANAGE 主路径 + 单一判定） | ✅ 在用 | `cac93b2` | `app/src/main/AndroidManifest.xml`、`terminal/ProotLauncher.kt`（判定已由 `7070261` 收敛到 `ProotLauncher.storageGranted`） |
| RootFS 下载 / 解压 / 校验（含镜像兜底） | ✅ 在用 | `5cf218e` | `rootfs/RootfsDownloader.kt`、`rootfs/RootfsInstaller.kt`、`rootfs/RootfsCache.kt` |
| RunLog 运行日志（迁 `cache/runlog`） | ✅ 在用 | `14b3d5e` | `rootfs/RunLog.kt` |
| 太极 Tab（Compose 直连 opencode serve） | ✅ 在用 | `bdada72` | `ui/taiji/TaijiScreen.kt`、`oc/TaijiState.kt` |
| 太极渲染 + 模型池选择器 | ✅ 在用 | `b95f82a` | `ui/taiji/TaijiComponents.kt`、`ui/taiji/ModelSheet.kt` |
| 太极会话完整化（新建 / 历史 / 恢复 / 自动标题） | ✅ 在用 | `f61dd51` | `ui/taiji/TaijiScreen.kt`、`oc/TaijiState.kt` |
| SSE → REST 轮询（SSE 不稳，改轮询） | ✅ 在用 | `348a88f` | `oc/SseClient.kt`、`oc/OcClient.kt` |
| 权限确认（V2 shell / edit 强制 ask） | ✅ 在用 | `74f4879` | `oc/OcClient.kt` |
| 插件系统（已收窄到太极） | ✅ 在用 | `e9997ec` | `ui/PluginManager.kt`、`ui/PluginsScreen.kt` |
| opencode 安装 / 启动（bionic 宿主版） | ✅ 在用 | `3f27844` | `oc/OcManager.kt` |
| Rust Core（`libzhengdao_core.so`） | ✅ 在用 | `af37010` | `rust/CoreNative.kt`、`app/src/main/jniLibs/arm64-v8a/libzhengdao_core.so` |
| 应用自更新 | ✅ 在用 | `5b6e453` | `ui/SettingsScreen.kt` |
| 欢迎页 + 冷启动记忆上次页面 | ✅ 在用 | `6a4ee6c` | `ui/WelcomeScreen.kt`、`ui/AppState.kt` |
| 丹房卸载功能（Agent 卸载） | ✅ 在用 | `3aed4f2` | `ui/HomeScreen.kt`、`ui/AgentManifest.kt` |
| 启动幂等（同名进程计数，防重复拉起） | ⛔ **已被取代** | `5feea73` | 原在 `TerminalActivity.kt`（该笔只动了 `TerminalActivity.kt` + `ui/AppState.kt`，**旧台账此栏写 `terminal/ProotLauncher.kt` 是错的**）。`countProcesses()` 扫 `/proc` 数同名进程 + 20 秒窗口守卫，已由 `da3d8eb` 换掉 ⇒ 见下一行 |
| 终端会话路由（**全局单会话**：同 Agent attach / 换 Agent kill） | ✅ 在用（本分支，未合 main） | `da3d8eb` | `terminal/SessionRouter.kt`（纯函数决策表 + 11 条单测）、`TerminalActivity.kt`、`terminal/SessionManager.kt` |
已移除的功能见 §3。

## 3. 已被拍板删除的功能（谁要加回来必须先问用户）

| 功能 | 删除决定 | 删除提交 | 现状 | 复活证据 |
| --- | --- | --- | --- | --- |
| API Key 管理 | 用户拍板「凭据类信息不落 App」；2026-10-07 已再次确认**按原决定删掉**并同日执行完毕（ERRATA E-017） | `e882050`（删）→ `fba9185`（误复活）→ 见 E-017 那笔（再删） | ❌ 已移除 | **有**：`fba9185`「补回 merge 漏带的 settings/ApiKeyStore.kt 及其 import」把 `settings/ApiKeyStore.kt`(93 行) 和 `OcManager.kt` 的 import 加了回来（`e882050` 原为 6 文件 +10/-185，含 `SettingsScreen.kt` -72、`ProotLauncher.kt` -15）；UI 未恢复 ⇒ 曾长期停在「`OcManager.kt` 仍读 ApiKeyStore、但设置页没有入口」的半残态。2026-10-07 已连同 `settings/` 包整份删除 |
| 旧 WebView + LocalProxy 回退路径 | v1.1.1 阶段 3「去回退」 | `3205d11` | ❌ 已移除 | 无。注意 `3205d11` 只改了调用方（`MainActivity.kt`/`OcClient.kt`/`OcManager.kt`/`CacheCleaner.kt`，+52/-40），文件本体 `oc/LocalProxy.kt`、`oc/TaijiPrefs.kt`、`ui/TaijiScreen.kt` 是 `e9997ec` 才物理删除 |
| SAF 镜像同步（`mirror/PhoneMirror.kt`） | P1.5 存储策略定稿：MANAGE_EXTERNAL_STORAGE 升主路径 | `4bbfd21` | ❌ 已移除 | 无（`PhoneMirror.kt` 由 `873add0` 以 Plan B 形态引入，再被 `4bbfd21` 删除，-282 行） |
| 旧 WebView 终端（xterm.js + Pty） | 终端原生化 | `5a0fd2e` | ❌ 已移除 | 无（删 `assets/terminal/{index.html,xterm.min.js,xterm.min.css,addon-*.min.js}`、`cpp/pty.c`、`terminal/{Pty,TerminalBridge,TerminalSession}.kt`） |
| opencode-mem 记忆插件 | 收窄插件机制 | `d5fc33f` | ❌ 已移除 | 无（只留 `terminal/LegacyMemPlugin.kt` 做幂等清理；插件本体由 `7cc1f59` 加进 `ProotLauncher.kt`） |
| 自编译 proot | App 从未使用 | `7b0550b` | ❌ 已移除 | 无（删 `rootfs/build-proot.sh` -140 行；ERRATA E-016） |
| `debug-proot.sh` | 调试残留 | `5f14fc4` | ❌ 已移除 | 无 |
| `oc-config.ts` / `oc-snap.ts` | 调试残留 | `8be27cf` | ❌ 已移除 | 无 |
| 终端 npm 版 opencode 条目 | v1.3 B2 改走宿主版 | `9b70e1b` | ❌ 已移除 | 无（清单重签 + 出厂卡片删除） |
| Zcode / Gemini CLI 清单条目 | 清单调整，改收 AGY CLI | `0b67e54` | ❌ 已移除 | 无 |
| 废弃 `.so`（`libproot.so` 186KB、x86_64 `libzstd-jni`） | 形似 so 易被误 `loadLibrary` / 无 x86 需求 | `c456437`、`8127a49` | ❌ 已移除 | 无 |
| 终端「多窗口并存 + 扫进程判重」（`C-b c` 开新窗口 / `countProcesses()` 数同名进程 / 20 秒启动窗口守卫） | 用户 2026-10-07 拍板**方向反了**：要的是「全局单会话 + 换 Agent 直接 kill」，不是「多会话并存 + 靠判重去重」 | `da3d8eb`（删；`5feea73` 是它最后的长相） | ❌ 已移除 | 无。⚠️ **最容易被误复活的一条**：它长得像"启动幂等的修复"，而单会话模型里幂等是靠 `attach` 天然达成的。谁要再加回 `C-b c` 新窗口或 `countProcesses()`，就是复活被否掉的路线 —— 先问用户 |

> 第 1 条的后续（2026-10-07 23:1x）：删除**已执行并已提交**——`settings/ApiKeyStore.kt` 及整个 `settings/` 包移除、
> `oc/OcManager.kt` 的 import 与注入调用一并删掉（`git grep -i "ApiKeyStore\|apikey" -- app/src` 零命中），
> 单测 + `assembleDebug` 通过。三件套（preflight / hook / 本台账）也随同一笔提交入库。

`e68d58c`（安装引导调用）与 `3aed4f2`（丹房卸载）的「恢复」属**回归丢失**，不是删除决定 ⇒ 见 §4。

## 4. 已知的反复/回归事件（实锤）

| 事件 | 提交 | 什么被弄丢了、被谁加回来 |
| --- | --- | --- |
| merge 会掉文件 | `fba9185` | `12bc499` 合并 `feat/taiji-compose-ui` 时漏带 `settings/ApiKeyStore.kt`（CI 红叉根因）；`fba9185` 补回文件 + `OcManager.kt` 的 import。注意该文件此前已被 `e882050` 按用户决定删除 ⇒ 这是「**删了又被 merge 带回来**」，是半残状态的来源 |
| fix(v1.2 回归)——启动 Agent 的最后一公里 | `46038f3` | 让 `~/.local/bin` 进 PATH 的逻辑 Debian 写在 `/etc/skel/.profile`，但本项目把 `files/home` 直接 bind 挂到 guest `/root`，root 的 home 从不是 `useradd` 建的 ⇒ 该逻辑**从未执行**，装好的 agy/claude 一律 `command not found`（现象：「点启动，Agent 起不来」）；另修僵尸「安装中」死按钮。改 `terminal/ProotLauncher.kt` +38、`ui/HomeScreen.kt` +9 |
| fix(v1.2 回归排查·Bug 2)——进终端唯一入口静默失败 | `020e024` | 全 App 唯一进终端的 lambda（`MainActivity.kt`）静默失败表现为「点了没反应」；按「失败必须可见 + 可诊断」加 `Log.d("OpenTerminal", …)`（只记长度不记全文）、`startActivity` 包 try/catch、异常 Toast + 落 RunLog。+27/-5 |
| 恢复过程中发现的两个回归 | `e68d58c` | ① 安装引导调用丢失：v1.1 重写 `TerminalActivity` 时 `fallbackActive` 分支的 `promptInstallOnce()` 调用没了，新用户装完 App 卡在 fallback shell 且**没有任何安装入口**（`TerminalActivity.kt` +4 补回）。② OpenCode 死循环入口：丹房已过滤 OpenCode，太极页文案「请在『丹房』下载安装后回到本页」永远走不通，改为就地 `NotInstalledPane(onExit,onInstall)` + 「下载并安装」按钮调 `OcManager.downloadAndInstall`（`ui/taiji/TaijiScreen.kt` +19/-5） |
| 一键安装/启动命令不再丢失 | `932d521` | 会话存活（attach 路径）时从主页投递的 autocmd **静默丢失**；加 `pendingAutocmd`，attach 与 fresh 两条路径都注入，回退 shell 不注入（否则打进系统 sh），等 400ms 让 tmux 重绘落定。`TerminalActivity.kt` +52、`terminal/SessionManager.kt` +7 |
| .gitignore 被覆盖（产物误入仓库） | `235f5c1` | `.gitignore` 被覆盖导致构建产物进了版本库；恢复 `.gitignore` +12 行，并移除已跟踪的 `.gradle/`、`.idea/`、`build/`、`app/.cxx/`、`h1/h2/n4–n8.png`、`u.xml`、`local.properties`、`rootfs/out/proot-arm64` |
| 丹房恢复卸载功能 | `3aed4f2` | 卸载入口重新落地：更多菜单 ⋮ + du 真实尺寸 + 三选项确认弹窗 + 注入竞态修复（`ui/HomeScreen.kt` +110、`ui/AgentManifest.kt`）。旁证：`298eceb` 曾首次落地三选项弹窗，`8127a49` 落地 manifest v5 `uninstall` 字段时注明「UI 部分待续」。**pickaxe 找不到删除提交** ⇒ 内容级回归（疑似 merge 中丢失），不能归因到某一笔删除 |
| 启动横幅回归 | `cb222d7` | 横幅改为「文件投递 + profile.d 在 pane 内消费」，删掉 `LaunchPlan.banner` 字段 |
| hermes 敲不动 | `6d53373` | 旧窗口敲 `hermes` 无反应；`/usr/local/bin` 软链 + `profile.d` PATH 兜底。与 `46038f3` 同源，当时只软链绕过 |
| release 启动即崩 | `e1269f0` | 移除 R8 `packageScope` 重命名，修 `IllegalAccessError` |
| 终端渲染 5 项回归 | `c456437` | Termux `TerminalView` 切换后的回归收尾：`attachSession` 空指针、白屏、字号 px/dp、背景色缺失、复制空实现 |
| 存储判定三份实现互相打架 | `7070261` | 三处 READ/WRITE 判定不一致，部分授权态下结论不同 ⇒ 统一委托 `ProotLauncher.storageGranted(ctx)` |
| 主页环境状态不刷新 | `ed28d93` | 装完环境回主页仍显示「未安装」；改 `ui/RootfsState` 可观察状态 + ON_RESUME 重读 |
| opencode 启动失败（截断文件冒充已安装） | `725ebef` | 新增缓存安装真机 instrumented 用例，复现并验收该事故 |
| Hermes 安装卡死在克隆 | `06572dd` | git 克隆改走国内加速镜像 `gh-proxy.com` |
| 调试截图误提交 | `c9e0d6f` | 移除误提交的调试截图 |

## 5. 怎么用（给 agent 的操作步骤）

1. 开工前跑：`python tools\zd.py preflight -k <功能关键词> -p <你要新建的文件>`；退出码 0 才准开工，1 表示有阻塞（落后主线 / 关键词命中 / 复活检测命中），2 表示不在 git 仓库。环境自检用 `python tools\zd.py doctor`。
2. 工具已统一到 **`tools/zd.py`**（一个纯标准库的 Python 入口：`preflight` / `doctor` / `install-hooks` / `hooks-status`）。`tools/agent-preflight.ps1` 与 `tools/install-hooks.ps1` 仍在，但**只是转发到它的 Windows 薄壳**（本机 ExecutionPolicy = Restricted，要 `powershell -ExecutionPolicy Bypass -File …`）；`tools/hooks/pre-commit` 是 POSIX sh，别改成 ps1。新克隆上钩子**需要手动安装一次**：`python tools\zd.py install-hooks`。
3. 装完钩子可以自证：`python tools\zd.py hooks-status`（库里 vs 已装逐字节比对，输出 ✓ 才算好）。
4. 被 `pre-commit` 拦下：闸门有三道（main 上直接提交 / 陈旧基准 / 复活检测），**改动不会丢、仍在工作区**；按提示 `git fetch origin && git rebase origin/main`，或 `git switch -c feat/<功能名>`。
5. 确实要复活 §3 的功能：**必须先问用户**；得到同意后用 `ZHENGDAO_HOOK_BYPASS=1 git commit ...` 绕闸，并在提交信息里写明「用户何时同意复活」。
6. 查不到就换维度再搜：`git log --all -S<关键词>`（内容级）、`git log --all --diff-filter=D -- <路径>`（文件级）、`git log --all --grep=<关键词>`（标题级）。
