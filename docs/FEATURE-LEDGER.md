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
>
> **2026-10-08 变更（hotfix，紧接上一批：release 一装环境就 SIGABRT）**：
> 上一批刚落地，用户就报「还是不行，要安装运行环境」——现场查实**环境一次都没装成**：
> 每次点「安装运行环境」都走 `TerminalActivity` 兜底 shell → `promptInstallOnce()` 认出本地包 →
> `startInstallFromFile` 解压途中进程 SIGABRT 死回主页（00:21:58 / 00:22:12 / 00:22:29 用户三次
> + 复现一次，logcat 四条一模一样的 abort）。根因是 R8：`app/proguard-rules.pro` 只 keep 了
> `native <methods>`，而被 Rust 侧**按名字**调用的 `CoreNative.onProgress(String, String)`
> （`CoreNative.kt:49-52`，`@JvmStatic`）没有 JNI 调用点可被 R8 看见 ⇒ 被改名成 `a`
> （dexdump 对照：旧 `classes.dex` 的 `CoreNative` 方法表里没有 `onProgress`），于是
> `jni_bridge.rs` 的 `call_static_method(..., "onProgress", ...)` 抛 `NoSuchMethodError`，
> 异常又没清（pending exception）⇒ 后续 JNI 调用踩 ART `AssertNoPendingException` → abort。
> **修两处**：① keep 规则补 `public static void onProgress(java.lang.String, java.lang.String)`；
> ② `rust/core/src/jni_bridge.rs` 的 `report_progress()` 加 `PROGRESS_DISABLED` 熔断 +
> `exception_clear()`，旁路进度回调失败**绝不允许升级成进程 abort**（重编 `.so` 覆盖
> `app/src/main/jniLibs/arm64-v8a/libzhengdao_core.so`：807,712 B → strip 后 808,704 B，
> 四个 LOAD 段 `p_align` 仍 `0x4000` = 16KB 对齐没丢）。**实测**（设备 `AD3J023824001723`，release）：
> 装机后点「安装运行环境」→ `I/CoreNative: 解压进度: …` 一路打点（正是被改名的那个回调）、
> `I/RootfsInstaller: Rust 解压完成: 20041 条目 973MB sha=2f1406af1939`、`RootFS 安装完成（Rust 路径）`，
> 进程不重启，主页转「环境就绪 / Debian 13.7 已安装」，终端 `root@localhost:~#`，**全程零下载**
> （直接用 `Download/证道/rootfs/` 的缓存包）。详见 `docs/ERRATA.md` E-022。另记一条工具事实：
> **androidTest 只能对 debug 变体跑**——release APK 里 R8 把 `androidx.tracing.Trace` 当无用类删了，
> `AndroidJUnitRunner` 一起手就 `NoClassDefFoundError: androidx/tracing/Trace`（用 `adb shell am instrument`
> 对着 release 跑必崩，别浪费时间）。

> **2026-10-08 变更（用户四条投诉的第一批：终端右滑 / 太极发送 / 设置页 OpenCode 入口）**：
> 用户原话：「首页也没有定下来，唉，还有就是在终端页屏幕右滑不能返回是为什么啊？太极对话框的发送按钮
> 点了也没用啊,设置页的opencode bionic版检查更新就不要了，主打的不是opencode。」处置：
> ① **终端左边缘右滑返回**（`app/src/main/java/com/example/zhengdao/TerminalActivity.kt`）：旧实现两处
> 静默失效 —— 排除区设在 `view.post` 里、那时 `view.height` 常为 0（`dumpsys window` 实测
> `mSystemGestureExclusion` 是残缺碎块而非整矩形），识别带 32dp 又比真人拇指落点窄 ⇒ 留下
> 140–190px「两边都不管」的死区，加上阈值 56dp 且只在 MOVE 判定（快甩无兜底）。重写为
> **Activity 层 `dispatchTouchEvent` 旁观手势**（不消费任何事件，终端点击/长按选词/纵向滚动照旧）+
> 排除区挂 `window.decorView`、布局完成后按真实高度设置；识别带 32dp→**56dp**、阈值 56dp→**40dp**、
> 纵向容差 40dp 且要求横向占优、MOVE 与 **UP 双判定**。详见 `docs/ERRATA.md` E-023。
> ② **太极发送按钮**：真机实测**发送是通的**（注入点击后 `uiautomator dump` 里出现用户气泡 `hello`、
> 思考过程、助手回复正文，会话标题由「新会话」变 `hello`）——用户"看着没反应"是因为对话区**被他自己
> 那个画中画短剧小窗盖住**，且第一次点击偏了 36px；仍补上失败可见性：`ui/taiji/TaijiScreen.kt` 的
> `onSend` 失败弹 Toast（原先失败只写 App 私有 `cacheDir/runlog/zhengdao-log.txt`，界面与 logcat 都看不见）。
> 详见 `docs/ERRATA.md` E-024。
> ③ **设置页撤掉 OpenCode 更新入口**（`app/src/main/java/com/example/zhengdao/ui/SettingsScreen.kt`）：
> OpenCode 不是主打，删掉版本行与「检查 OpenCode 更新／安装 OpenCode」按钮，入口只保留太极抽屉底部那份
> `OcVersionFooter()`（顺带补上「未安装 → 安装 OpenCode」分支：原实现把"查不到"和"已是最新"都说成最新）。
> ④ **首页「没定下来」**：用户含义待澄清，本批**未动**首页代码。

> **2026-10-08 变更（用户第二条反馈：终端页面不像手机）**：
> 用户原话（`m04459`）：「是不是终端的界面没有适配手机啊？」，随后（`m04476`）说明他要的是
> "右滑返回失灵"的**成因**是否与界面没适配有关，并选定处置=「去掉桌面窗口外观：终端铺满全屏」。
> 结论：右滑两处缺陷**与外观无关**（详见 E-023：排除区设置时机 + 识别带/阈值按鼠标式滑动定，未按真人
> 拇指落点回归），但外观确实有一层没必要的桌面隐喻。处置：
> `app/src/main/res/layout/activity_main.xml` 撤掉 2026-10-05 定下的 macOS/Safari 窗口铬 —— 根布局
> `android:background="@android:color/white"` + `android:padding="6dp"`（四周一圈白边）改为黑底、无内边距；
> `@id/window_card` 的 `@drawable/window_bg`（圆角悬浮卡片）改为纯黑 ⇒ 黑底终端铺满整屏，顶部只留一条
> 扁平工具栏（红/黄/绿三圆点 = 关页 / 收分屏 / 分屏，是功能键不是装饰，保留；`window_bg.xml` 由此变为
> 无引用资源）。真机实测（`AD3J023824001723`，release 4,179,946 B）：tmux 状态行由半幅变整幅、同屏多出
> 约 12dp 的可用宽度；左边缘右滑的排除区同时受益（`terminal_root` 不再有 6dp 内边距，x=0 起即终端）。
> **同日再校一次**（用户 `m04534` 原话「还是有个白色边框吧，不然四等边的手机看着好难受」）：纯黑铺到屏幕
> 边缘后与四等边机身黑成一片，反倒没有"这是一块屏"的边界感 ⇒ `app/src/main/res/layout/activity_main.xml`
> 根布局改回 `android:background="@android:color/white"` + `android:padding="4dp"`（白边 6dp → **4dp**，
> 圆角仍然不要）。真机 release 4,179,962 B 实测：左右可见细白线、快捷键条正常。

> **2026-10-08 变更（hermes 依赖环境修复：搬家包恢复后必崩）**：
> 用户原话（`m04568`）：「我安装好hermes agent后，让hermes把手机里的搬家文件里的东西搬过来，然后就出问题额了，
> 再重新打开APP用命令进入hermes后就出现这个难题了。」——终端里 `hermes` 只吐三行错
> （`source-update completion failed: … 'uv.lock'` / `recorded dependency lock is missing; refusing to drop
> plugins` / `dependency environment is missing or outside this install: …/environments/3c17878dccdc…/venv`）。
> 根因两条：① 搬家包**有意不带** `installs/*/environments`（345M）却带了旧机的
> `installs/8a4017c4cabfe15f/facts.json`（记录指向旧机的依赖代）⇒ `pm/environments.py:297-318` `_recorded_venv()`
> 判定"环境不在本次安装内"；② 04:31–04:40 那次被中断的自我更新删了受 git 管理的 `hermes-agent/uv.lock`
> （+`flake.lock`）却没重建 ⇒ `prepare_launch()` 每次启动先失败，而**非 `pm` 子命令在 bootstrap 阶段就退出**，
> `hermes update` 救不了。处置：`git checkout -- uv.lock flake.lock` → 清失效记录与陈旧锁 →
> `hermes pm repair` 重建并登记新代 `34aa9d1ad79c46e19c8222a032404c59`（359M）⇒ `hermes --version` /
> `--help` / `doctor` 全部恢复；**身份数据与 `打包基线.txt` 逐项吻合**（sessions 65 / messages 7057 /
> `MEMORY.md` 4482 B / `USER.md` 3180 B），顺手删孤儿旧代腾出 353M。同时把 `/sdcard/Download/Hermes搬家-20261005-113244/`
> 里的 `一键恢复.sh` 补上「依赖环境自愈」段（原文件备份 `.orig-20261005`）并新增 `修复依赖环境.sh` ——
> 否则**每跑一次恢复都会把坏记录写回去**。另把 config.yaml 的 `custom_providers` 两条（`哈基米` 286 模型 /
> `量界智算` 86 模型）用官方 `config_migrations._migrate_to_12` 同一段代码迁进 `providers:`（迁移前后
> `get_compatible_custom_providers` 输出完全一致，`hermes doctor` 待办 3 → 1 条），皮肤设为 `moss`。
> 详见 `docs/ERRATA.md` E-025。

> **2026-10-08 续（App 侧补上"依赖环境记录"这层自愈 —— 用户选了「现在就加」）**：
> 动机：上面那次是**手工**修好的；搬家包/更新中断的成因不解决，下次恢复还会崩，且用户只会看到
> "hermes 敲不动"。落地：新增 `app/src/main/java/com/example/zhengdao/terminal/HermesEnv.kt`
> （宿主侧**只读**判定 `installs/*/facts.json`：记录里的 `environment` 目录要在且含 `pyvenv.cfg`，
> 记了 `resolved_lock` 则锁也得在 —— 与 `pm/environments.py:297-318` 同条件；宿主 `filesDir/home`
> 与 guest `/root` 同 bind，所以改 `facts.json` 等于在 guest 里改）+ `app/src/main/res/raw/hermes_env_repair.sh`
> （guest 侧五步：`git checkout -- uv.lock flake.lock` → 清失效记录 → 删陈旧标记 → `hermes pm repair`
> → `hermes --version` 复验）。**修得上就当场修，修不上绝不装作能修**：盘上有完整代（`venv/pyvenv.cfg`
> + `workspace/uv.lock`）时宿主侧把指针改到最新那代（先备份 `facts.json.bak-证道<时间戳>`），一代都没有
> 则**删掉失效记录**（留着会让 `pm repair` 以 "recorded dependency lock is missing" 拒绝重建）；
> 要重建 Python 环境 / 要 `git checkout` 时，脚本落 `Workspace.hostDir/.zhengdao/scripts/` 并在终端跑，
> 过程用户可见。入口：`EnvHealth.inspect` 新增 `hermes-deps` 项（`Check` 加 `terminalCmd` 字段表达
> "需要进 guest 的修复"）+ 首页状态卡「修复」按钮；设置页新增 `SectionCard("Hermes 依赖环境")`
> （「一键修正记录」/「在终端里修复」）。没装 Hermes 直接跳过、不报警。单测
> `app/src/test/java/com/example/zhengdao/terminal/HermesEnvTest.kt` 6 例锁住上述不变量。

> **2026-10-08 续（单会话模型的陈旧退出回调 + Rust 单测终于有了自动化入口）**：
> ① `app/src/main/java/com/example/zhengdao/terminal/SessionManager.kt:192-222` 的退出清理原来只看
> 「当前会话跑没跑」（`session?.isRunning == false`），不看「结束的是不是当前这条」；换 Agent 走的是
> "先 finish 旧会话、立刻装新会话"，而新会话**首次 `updateSize`（spawn）之前 `isRunning` 也是 false**
> （本文件 `:112-119` 自己写明的宽判）⇒ 迟到的旧回调会把刚建好的新会话误清（抹 Agent 记录 + 停前台
> 服务 + `onSessionDied` 关页）。修法：`onSessionFinished` 传 `finishedSession` 进来，
> `onFinished` 里加 `if (session !== finished) return@post`。触发靠时序，是**竞态地雷**不是必现，
> 本次是读代码发现、真机只验"没改出新问题"。详见 `docs/ERRATA.md` E-026。
> ② `rust/README.md:70-75` 记的"本机没有 host C 编译器"其实是 **PATH 问题**：w64devkit
> （`C:\Users\guoli\w64devkit\w64devkit\bin\gcc.exe`，GCC 15.2.0）一直装着、只是没进 PATH；
> 顺带发现 rustup 自带那个 `x86_64-w64-mingw32-gcc.exe` 只是链接驱动（没有 `cc1`，不能当 `CC`）。
> 挂上 PATH 后 `cargo test -p zhengdao_core --release` = **8 passed / 0 failed**（sha256 4 + extract 4）。
> 更关键的是：这 8 例此前**本机靠手动、CI 里完全没有**（`ci.yml` 只有 `:app:testDebugUnitTest`），
> 已给 `ci.yml` 加「Rust 逻辑层单测」+ cargo 缓存两步，跑在 ubuntu runner（自带 gcc）。详见 E-027。

> **2026-10-08 续（Rust 单测进 CI 的第一个产出：红的是测试自己 + CI 失败从此可见）**：
> ① 上一批给 CI 加的「Rust 逻辑层单测」第一次真跑（`37719284097 ci @20b0082`）就红了 ——
> 红的是**测试夹具**：`rust/core/src/tests.rs` 的 `make_archive()` 把 symlink 的 linkname 写成根相对的
> `data/hello.txt`，而 tar 规范里它是**相对链接所在目录**；`rust/core/src/extract.rs:149-166` 原样落盘
> （与 Kotlin 版对拍一致 = 正确）⇒ unix 那条"读穿内容"的断言读不到文件而 panic。这条断言在 Windows 上
> 被 `#[cfg(unix)]` 整块 cfg 掉，**从来没在开发机上跑过**。夹具改 `set_link_name("hello.txt")`，
> unix 断言加强为 `read_link == "hello.txt"` + 内容一致，Windows 侧补"占位文件必须 0 字节"断言；
> 顺带清掉 6 条 warning，并把 `tests.rs` 里 7 个字面 NUL 字节改成 `\0` 转义（该文件此前在 git 眼里是
> 二进制，diff 只有 `Bin 8054 -> 8967 bytes`）。本机 `cargo test -p zhengdao_core --release`
> = **8 passed / 0 failed、0 warning**。详见 `docs/ERRATA.md` E-028。
> ② 同轮把 cargo 步骤改成**失败时抛 annotation**（`set +e` + `tee` + `PIPESTATUS[0]` + `grep` 要点
> 逐行 `::error title=cargo test 失败::…` + 退出码）：本仓库 Actions 日志未登录读不到
> （`GET /actions/jobs/{id}/logs` = 403），而 `GET /repos/{owner}/{repo}/check-runs/{id}/annotations`
> 匿名可读 —— 这次就是靠它看到 `exit code 101` 的原文的。

> **2026-10-08 续（体检第 10 项：让 native 的静默降级可见）**：
> 用户当天的原话是「sha256→Rust 这个真机验出来不是说收益不大吗？你觉得有意义就接吧」——**对，速度上确实
> 没收益**（实测与 Java 路径差距 <2%）；这一项要解决的是别的问题：`rust/CoreNative.kt:26-31` 只在类加载
> 时探一次 `System.loadLibrary("zhengdao_core")`，失败就**永久**标记，之后解压退回 `RootfsInstaller` 的
> commons-compress 纯 Java 路径、SHA256 退回 `MessageDigest`——功能照常、只是更慢，而 E-012（16KB 页对齐
> 漏配 ⇒ native 静默失效）与 E-022（R8 改掉 JNI 回调名 ⇒ release 一解压就 SIGABRT）都出在这条链路上。
> 落地在 `ui/EnvHealth.kt`：`inspect()` 里排在「存储权限」与「资源占用」之间，判定文案抽成纯函数
> `nativeDetail(available)`；**编码为 ⚠（`ok=true` + `warn=true`）而不是 ✗**——native 加载失败用户侧修不了
> （.so 打包/页对齐，只能换包），报红会破坏本文件写明的「红 = 修得了」不变量（渲染侧 `!ok -> ✗` 会盖掉 warn）。
> `app/src/test/java/com/example/zhengdao/ui/EnvHealthTest.kt` +3 例，其中「探不到时必须编成告警」那例
> 利用 JVM 里必然没有 .so 这一点验**编码**本身。真机验收：装机后展开状态卡 =「环境体检 10/10 通过」，
> 新项 `✓ native 加速层 · libzhengdao_core.so 已加载：解压与 SHA256 走 native`。
> 顺带销掉一处文档/实现漂移：`CoreNative.isRustAvailable()` 的 KDoc 一直写着「供测试与体检展示」，
> 而体检此前从没用过它。

> **2026-10-08 续（CI：发布不再排在 RootFS 后面 —— 下载页停在旧包的结构性修法）**：
> 用户当天问「那个什么 GPL 没 CI 好吧？」。**LICENSE 本身与 CI 无关**（合并只是加一个文本文件，
> PR 分支自己那一跑 `ci @427fb98` = success）；真正出问题的是**发布通道**：滚动版 `latest` 的 APK
> 一直停在 `2026-10-07T17:25:57Z`（4,179,922 B），2026-10-08 一整天的修复（E-021 缓存目录、
> E-025/E-026 依赖与会话自愈、体检第 10 项）一个都没进下载页。根因两条叠加：`build.yml` 把
> 「发布到 Releases」排在 30–90 分钟的 qemu RootFS 构建**之后**，而同一文件
> `concurrency.cancel-in-progress: true` 让任何新推送都能掐死正在跑的 run ⇒ 掐死点只要落在
> RootFS 那段时间里，发布 100% 走不到。⚠️ 这不是新坑：E-020 §3 已记过同一现象，当时的收尾是人工
> 纪律"构建跑着时别推 main"—— 第二天就被"用户自己合了个 LICENSE"打破。修法（改结构本身）：
> ① 发布 APK **提前到 RootFS 之前**（仍在 E-014 的签名硬关卡之后）；② RootFS 改为只在 `rootfs/`
> 有改动 / 手动触发 / 拿不到 `github.event.before` 时重建（checkout 改 `fetch-depth: 0` + 新增判定
> 步骤，四个 rootfs 步骤挂 `if`）；③ 发布拆成「发布 APK」与「发布 RootFS 包」两步。
> 定盘提交 `fa8d467`；全过程见 `docs/ERRATA.md` E-029。

> **2026-10-08 续（CI 优化：让"发布"前面只剩必须步骤 —— E-030）**：
> 用户 m05687 问「CI还要不要优化一下呢？」。先把账算清：本仓库是 public（`private=false`），
> Actions 分钟数免费不限量 ⇒ **省机器时间本身没有意义**，有意义的只有"推送 → 用户能下载"的时延
> 与"红叉是否可信"。E-029 上线后第一次跑 `build #147 @7d4953a` = success：run 起 `03:30:15Z`、
> 发布完成 `03:37:50Z`（**7 分 35 秒**），`latest` 的 `zhengdao-1.3.0-release.apk` =
> **4,190,625 B、`updated_at = 2026-10-08T03:37:50Z`** —— 当天的包终于进了下载页
> （`debian-13.7-base-arm64.tar.zst` 仍是 10-07 那份，按设计跳过）。但发布的**前面**还站着三个
> 只服务开发者/排查的步骤：keystore 诊断 **154s**（E-014 的"常驻诊断"）、Debug APK 编译 **54s**、
> 为 RootFS 腾盘的「释放磁盘空间」 **38s**（合计 ≈ 4.1 分钟，比要发布的那个包自己的编译
> 119s 还贵）。改动四条：①诊断 → `if: failure() || github.event_name == 'workflow_dispatch'`
> 且后移到发布之后（真正的把关本就是「校验 release 包的签名」那步对着**产物**验）；
> ②Debug APK 编译后移到发布之后 + 新增「收集 Debug APK 产物」补进 artifact；③释放磁盘 →
> `if: steps.rootfs_needed.outputs.rootfs == 'true'`；④`ci.yml` 的 R8 冒烟（120s，含 artifact
> 上传）→ 只在 `pull_request` / `workflow_dispatch` 跑（main 上 `build.yml` 每次都编 release
> 且多一道验签）。**刻意不做**：合并 ci/build 去重（会把"快反馈"与"发布"两条职责搅在一起）、
> 给 RootFS 做缓存复用（qemu 交叉构建的半成品缓存有正确性风险，而 rootfs 极少变）。
> 预期"推送 → 能下载"从 7 分 35 秒压到 **约 3.5 分钟**；全过程与实测数字见
> `docs/ERRATA.md` E-030。

> **2026-10-08 续（v1.3.0 正式发布）**：用户 m06007 要求「把 1.3 版弄到 github，现在还是 1.2 那个」。
> `.github/release-notes.md` 重写成 v1.3.0 版（四节：安装与环境 / 体检与可见性 / 打包与发布 /
> 可靠性清债；Hermes 从"受限支持（uv 硬链接问题）"改为"安装时需联网取它自己 pin 的 Python 工具链；
> 依赖环境损坏可在首页一键修复"）→ 提交 `27e20ab` → 合并 `25b0b0f` → 推 main → 建附注 tag
> **`v1.3.0`**（指向 `25b0b0f`）→ 推 tag。结果：`build #150 [v1.3.0]` = success，
> **GitHub Release `v1.3.0`（「证道 v1.3.0 — 正式版」）已建**，资产 =
> `zhengdao-1.3.0-release.apk` **4,190,625 B @2026-10-08T04:31:46Z**（此前最新正式版是 v1.2.0）。
> 同日 `build #149 [main]`、`ci #39 [main]` 均 success，且 `ci` 里「R8 冒烟」「上传 release APK」
> = **skipped** ⇒ E-030 的"只在 PR/dispatch 跑 R8"在 main 上如期生效。
> ⚠️ 一个遗留：历史正式版（v1.1.1 / v1.2.0 / v1.3.0）**只发 APK、没有 rootfs 包**，
> 所以"跨版本差分包"没法拿历史包实测（见 E-031 §7-3）。

> **2026-10-08 续（环境包瘦身取证：ffmpeg 一棵树 = 半个包；压缩参数白省 24.8%；zstd 差分被实测否掉）**：
> 用户 m06007 第①问「做一个把环境包压到最小的方案」。把线上那份包下载、解开、逐项剔除、重打包实测
> （详见 `docs/milestones/证道-环境包瘦身与压缩方案-2026-10-08.md` 与 `docs/ERRATA.md` E-031）：
> 线上包 **326.6 MB**（zstd 默认级别 3）→ **只换成 zstd -19（内容一字不改）= 245.7 MB（−24.8%）**；
> `--long`/`window_log 27` 只再省 2.4% 却要端侧 128 MB 解码窗口 ⇒ 不做。
> 内容侧最大一块是 **ffmpeg 及其 201 个仅它依赖的包**（安装体积 397.8 MB、包体积 158.4 MB，
> 占包的一半）——`docs/milestones/安卓AgentApp-设计方案-v3.md:232` 与
> `docs/milestones/文档审核报告-2026-10-06.md:143` 本来就写着"保留理由待复核"。
> 全叠加（剔 ffmpeg + 裁 locale + 清构建残留）+ zstd -19 = **106.6 MB（−67.4%）**。
> **待用户拍板**：是否剔 ffmpeg（功能取舍：环境里就没有 `ffmpeg`/`ffprobe` 命令了，补救可另发 extras 包）。
> 另否掉一条捷径：**zstd `--patch-from` 差分不可用**（480 MB 基线的字典要 ≥480 MB 窗口，
> 实测内容完全一致时差分包仍等于全量）⇒ 增量下发要走**文件级**（清单 `sha256\tpath`，实测 zstd-19 后仅 0.44 MB）。

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
| 环境体检·native 加速层可见（第 10 项，⚠ 不是 ✗） | ✅ 在用 | `d002e3a` | `ui/EnvHealth.kt`、`app/src/test/java/com/example/zhengdao/ui/EnvHealthTest.kt` |
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
| 终端会话路由（**全局单会话**：同 Agent attach / 换 Agent kill） | ✅ 在用（**已在 main**：`da3d8eb` 是 main 的祖先；2026-10-08 用户真机复验换 Agent 通过） | `da3d8eb` | `terminal/SessionRouter.kt`（纯函数决策表 + 11 条单测）、`TerminalActivity.kt`、`terminal/SessionManager.kt` |
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
| CI 发布通道被架在长步骤后面（下载页停在旧包） | `fa8d467` | 「发布到 Releases」排在 30–90 分钟的 qemu RootFS 之后，而 `concurrency.cancel-in-progress: true` 让任何新推送都能掐死正在跑的 run ⇒ 掐死点落在 RootFS 期间时发布永远走不到；2026-10-08 一整天修复都没进滚动版 `latest`（资产停在 10-07T17:25:57Z）。修法：发布 APK 提到 RootFS 之前 + RootFS 只在 `rootfs/` 变化时重建（ERRATA E-029）。⚠️ E-020 §3 已记过同一现象，当时只加了人工纪律，这次才改结构 |

## 5. 怎么用（给 agent 的操作步骤）

1. 开工前跑：`python tools\zd.py preflight -k <功能关键词> -p <你要新建的文件>`；退出码 0 才准开工，1 表示有阻塞（落后主线 / 关键词命中 / 复活检测命中），2 表示不在 git 仓库。环境自检用 `python tools\zd.py doctor`。
2. 工具已统一到 **`tools/zd.py`**（一个纯标准库的 Python 入口：`preflight` / `doctor` / `install-hooks` / `hooks-status`）。`tools/agent-preflight.ps1` 与 `tools/install-hooks.ps1` 仍在，但**只是转发到它的 Windows 薄壳**（本机 ExecutionPolicy = Restricted，要 `powershell -ExecutionPolicy Bypass -File …`）；`tools/hooks/pre-commit` 是 POSIX sh，别改成 ps1。新克隆上钩子**需要手动安装一次**：`python tools\zd.py install-hooks`。
3. 装完钩子可以自证：`python tools\zd.py hooks-status`（库里 vs 已装逐字节比对，输出 ✓ 才算好）。
4. 被 `pre-commit` 拦下：闸门有三道（main 上直接提交 / 陈旧基准 / 复活检测），**改动不会丢、仍在工作区**；按提示 `git fetch origin && git rebase origin/main`，或 `git switch -c feat/<功能名>`。
5. 确实要复活 §3 的功能：**必须先问用户**；得到同意后用 `ZHENGDAO_HOOK_BYPASS=1 git commit ...` 绕闸，并在提交信息里写明「用户何时同意复活」。
6. 查不到就换维度再搜：`git log --all -S<关键词>`（内容级）、`git log --all --diff-filter=D -- <路径>`（文件级）、`git log --all --grep=<关键词>`（标题级）。
