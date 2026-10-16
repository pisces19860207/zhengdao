import java.util.Base64

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// 版本号单点定义：defaultConfig 与输出文件名共用（GitHub Actions 产物带版本，用户可辨新旧）
// v2.0.0（2026-10-09）：**里子工程 + 环境信任链**——第一个真正换「引擎」的主版本。
//   Rust 核心收口（sha256 / rootfs 解压 / 增量补丁解压 / Ed25519 验签全部进单个
//   libzhengdao_core.so；native 不可用时自动回退 Java，且回退本身可观测）；
//   环境包索引 Ed25519 签名上线（先验签后解析，Rust 与平台实现对拍，不一致按拒绝处理）；
//   增量更新（差分补丁）落地；环境包瘦身（剔除 GPU 软件渲染栈，落盘 −118 MB）；
//   本地包校验值改为**以签名索引为准** + 过期 `.sha256` 边车自愈（E-053）；
//   入库 `.so` 门禁（16 KB 页对齐 + JNI 入口符号）；抱朴 Tab 可用性三修。
// v1.3.0（2026-10-07）：**可靠性清债 + CI 止血**——无新增功能面。
//   启动链路三修（~/.local/bin 进 PATH / 启动幂等 / 已装误判成未装）；opencode 启动事故
//   （截断文件冒充已安装）+「进终端唯一入口静默失败」；终端页唯一化；E2 storageGranted
//   单一判定源；B2 移除终端 npm 版 opencode；CI 补 assembleRelease + 统一签名密钥。
// v1.2.0（2026-10-07）：**终端环境优化**——不修 UI、不加功能，只动终端里的环境本身。
//   卸载终端 npm 版 opencode；修复抱朴配置路径（v1.1.1 曾误指终端 taiji 的 guest 死路径）；
//   网络四项与 IP 钉住的尝试及撤除；工作区 .ignore；资源监控与体检三态。
// 版本序列：v1.0.0(12) → v1.1 → v1.1.1(13) → v1.2.0(14) → v1.3.0(15) → v2.0.0(16)
// ⚠️ v2.0.0 是**主版本**：换的是引擎与信任根（解压/校验/验签主路径进 Rust、索引签名成为
//    唯一信任锚、本地包校验值优先级改变），不是靠新功能面撑版本号（用户 2026-10-09 拍板）。
// ⚠️ 14 的归属曾有过争议：v1.1.1 裁决④一度把 14/1.2.0 划给 ChatGPT 线，
//    该线已归档，14 归还本计划（2026-10-07 用户拍板）。
// ⚠️ v1.3.0 是**可靠性清债版**：启动链路三修 + OpenCode 启动事故 + 终端页唯一化 +
//    E2 单一判定源 + B2 移除终端 npm 版 opencode + CI 止血（assembleRelease / 签名统一）。
//    没有新增功能面，故 versionName 只进 patch 级的语义在这里体现为 1.2.0 → 1.3.0
//    （用户 2026-10-07 拍板用 1.3.0 而非 1.2.1）。
// ⚠️ 2.0.1（2026-10-09，ERRATA E-056）：修的是自家 2.0 带出的回归 —— uv 的缓存被挂到
//    共享存储（FUSE）上，FUSE 既建不了软链也拿不到 flock，于是 hermes 依赖装不上。
//    只动挂载表与一条日志文案，无新功能面，故进 patch 级。
// ⚠️ 2.0.2（2026-10-09，ERRATA E-059 / E-060）：盯着「用户不会卸载 App、只会一直点重试」
//    这一个场景 —— 缓存的安装脚本自检（HTML 错误页 / 门户页 / 半截文件一律判废重下）、
//    残锁清面（`index.lock` 之外还清 `*.lock`）、已装 Agent 的 ⋮ 里多一个「重新安装」；
//    另外「安装中」卡片提前说明可能停在 Agent 自己的配置提问上（按 ESC 跳过，别按 Ctrl+C）。
//    仍无新功能面，故继续进 patch 级。
// ⚠️ 2.0.3（2026-10-09，ERRATA E-061 / E-062 / E-063 / E-064）：第一个**带新功能面**的 2.0.x ——
//    「资料库」（知识库集成）：把资料丢进 `/sdcard/Download/证道/资料库/原始/`，App 自动生成
//    `整理/00-目录.md` 并给终端里的 AI 指路（`/root/AGENTS.md`）；P2 借抱朴的免费模型给长文档补摘要
//    （`.docx` 也能读，P3a）。只加功能、不动既有链路，故仍进 patch 级。
// ⚠️ 2.0.4（2026-10-09，ERRATA E-065）：用户问「会不会被别人拿去卖」之后的**署名与出处** ——
//    关于页写清作者（小信多多）/ 版本来源（官方发布 ✓ / ⚠️ 非官方（被重新打包过））/ 许可证 GPL-3.0 /
//    联系作者；安装包签名自检（官方证书 SHA-256 与运行时装包签名对比，**只提醒不拦功能** ——
//    GPL 明确允许别人改代码自己签名，拦了会误伤自己编译自己用的用户）；README 写明转载与
//    二次打包的态度。仍无破坏性改动，故进 patch 级。
// ⚠️ 2.0.5（2026-10-09，ERRATA E-058）：抱朴与设置页的提示条（「已复制到剪贴板」等）从**屏幕顶部**
//    搬回屏幕底部 —— 根因是宿主节点写在 `Modifier.fillMaxSize()` 里的 Box 中，fillMaxSize 会把固定
//    最小约束一起传给子节点，SnackbarHost 因此被量成整屏（实测 1312×2425），`align(BottomCenter)`
//    只管摆放、救不回被撑大的宿主，于是落到了宿主左上角；修法是给宿主加
//    `wrapContentHeight(Alignment.Bottom)`（抱朴页与设置页各一处）。另附开放 Issue #6 的存储结论
//    （hermes 是 git 安装 ⇒ `.git` 不能剥离，改做 cache/installs 清理），不改任何逻辑，故进 patch 级。
// ⚠️ 2.0.6（2026-10-09，ERRATA E-066 ~ E-068）：三件事一起进 —— ①「怡然两个红叉」的真因修复
//    （hermes 的 `facts.json` 记录的依赖环境是 **guest 视角**路径，App 却按宿主视角判读写 ⇒
//    既误报 ✗、又会把记录改写成 hermes 找不到的路径；E-066）；②缓存清理新增「清理 Agent
//    缓存包」按钮（真机一次释放 437MB，不碰 `tools`/`installs`/`uv.lock`）+ 抱朴人设 v2
//    （不再谎称自己跑在 Debian 里 —— 它的 shell 就是安卓宿主 mksh；E-067）；③Release 正文
//    机制修正（`body_path` 此前钉死 `release-notes.md`，导致 v2.0.1~v2.0.5 五份正文一模一样；
//    E-068）。仍无破坏性改动，故进 patch 级。
// ⚠️ 2.0.7（2026-10-09，ERRATA E-069）：抱朴与终端**彻底分开**（用户拍板）—— 抱朴的工作区从
//    共享的 `Download/证道` 换成 App 私有的 `files/oc/workspace`，安装包缓存从
//    `Download/证道/opencode` 搬到 `files/oc/pkg`（旧的自动搬家并清掉），抱朴的预置（人设 +
//    `opencode.json` 字段）从「终端启动」这条路径搬进抱朴自己拉 serve 之前，人设升 v3。
//    终端的共享工作区 / 资料库 / 日志一律不动。旧装机升级即自动搬家，无需用户操作。
// ⚠️ 2.0.8（2026-10-09，ERRATA E-072~E-076）：一轮「维护与优化」批次，六件事：
//    ①#1 存储读取回归——真机读写 `/sdcard` 的仪器冒烟用例 5 例（`StorageReadWriteSmokeTest`）
//      + JVM 静态防复发守卫（`StoragePermissionGuardTest`，进 CI）；②#2 输入回归页
//      （`ui/ImeRegressionActivity`：三场景脚本自检 + 页面手工走查，终端改动后 30 秒回归 IME，
//      E-074）；③#3 存储提示与自动清理——启动检测可清理 >500MB、安装进程在跑就跳过、
//      完成后静默通知；④#4 全 App 错误提示——「最近问题」清单（`core/IssueCenter.kt`），
//      30+ 处只写日志的失败变成界面上可重试/可修复/可反馈的卡片（E-070）；⑤#5 后台被杀留档
//      （`keepalive/KeepaliveArchive.kt` + `KeepaliveWatcher.kt`，被杀前后 logcat 可查，E-071）；
//      ⑥#8 存储优化——hermes 旧依赖代自动清理（保留当前代 + 最新一代）、可选工具
//      （chromium/ffmpeg/cua-driver/agent-browser）按需清理、公共区安装包只留「当前 + 上一个」、
//      体检面板「存储占用」区块 + 明细/一键清理（E-073、E-075）。
//      ⚠️ 同时修掉一个**静默了整代**的缺陷：安卓 13+ 的 `POST_NOTIFICATIONS` 既没声明也没请求
//      ⇒ 所有通知（含 #3 的静默清理提示）被系统丢掉；现已在清单声明并在冷启动按需请求（E-076）。
//      仍无破坏性改动，故进 patch 级。
// ⚠️ 2.0.9（2026-10-10，ERRATA E-081~E-085）：一天里连着五批收口，全部有真机取证：
//    ①E-081 Hermes 退出「恢复上次装过的 Agent」候选（它的官方安装器在受限网络下必然失败，
//      依赖链又重；只关恢复入口，安装卡片照旧）；②E-082 信任边界三条（P3-2 公共区安装脚本
//      改为**执行私有副本**、P3-5 解包防路径穿越、P3-6 缺校验值拒绝安装）；③E-083 rootfs
//      审计的两条真 BUG（Rust 解压不认「0 条目」、termux-proot 每次开会话重算 313,648 B
//      哈希 ⇒ 新增「戳」记忆）；④E-084 FIX-F：提问时把本机找到的「资料库线索」附在这句话
//      后面一起送进终端（App 自己不再把内容发出去）；⑤E-085 审计工作表第一批五条（软链
//      linkname 越界、resolv.conf 权限不还原、路径前缀判断、增量空间预检、zz 脚本按内容
//      比对）。仍无破坏性改动，故进 patch 级。
// ⚠️ 2.0.10（2026-10-10，终端视觉升级；三个分支一次性合并落地）：
//    ①配色——新增 Catppuccin Mocha 并设为**出厂默认**（16 个 ANSI 色一次给全，明暗不靠
//      「亮一档」区分），含选区色；设置页「配色」仍可切回经典黑白 / 琥珀 / 复古绿 / 浅色；
//    ②字体——正文换成 JetBrains Maple Mono（Nerd Font 版）并**按 GB2312 常用集子集化**
//      （6,763 汉字 + 终端必备符号 + NF 图标区，工具 `tools/subset-terminal-font.py`）：
//      字体 17.94 MB → 6.46 MB、APK 13.53 MB → 7.82 MB、首次加载 72–73 ms → 27 ms；
//      子集化后 2:1 中英对齐逐像素复核**仍是 0 px 偏差**（验收页
//      docs/acceptance/terminal-font-subset-spacing-2026-10-10.md）；
//    ③间距——新增「舒适」档：行高 1.15×、画布上下各留 4dp、功能键条 48dp；设置页
//      「终端外观 → 行距」可选舒适/紧凑（紧凑＝旧版观感，只改行盒高度、不动列宽换算）。
//      仍无破坏性改动，故进 patch 级。
// ⚠️ 2.0.11（2026-10-11，ERRATA E-089~E-090）：两条修复 + 一条文档，全部无破坏性改动：
//    ①E-090 opencode 装不上（**v2.0.9 起 100% 失败**的回归）——E-082 在 `OcManager.extract()` 里
//      把 `substringAfter("files/usr/")` 改成 `removePrefix("files/usr/")`，而归档条目名以
//      `data/data/com.termux/` 开头 ⇒ 一个字都没剥掉、整包落到 `files/oc/usr/data/…` 错位，
//      `installed()` 判否 ⇒ 报「释放后二进制缺失（包不完整？）」。现改为前缀常量
//      `PKG_ENTRY_PREFIX` + `relPathOf()`（准入判断与剥前缀共用同一前缀），并顺手清掉旧版
//      错位残留（真机回收 275 MB）、给这条失败补一行日志。真机验收：清理 275 MB 残留 →
//      `OpenCode 2.0.22 释放完成（2 个文件）` → 抱朴 ● 已连接（SSE HTTP 200）；
//      `OcExtractPathTest` 13 例全绿（全套 63 suite / 563 例 / 0 失败）。
//    ②E-089 资料库扫描不再静默截断：清单自己写一行「只列了前 2000 个」，设置页状态带
//      「（已达扫描上限，可能未列全）」；`资料库/说明.md`「各种格式都行」改准。
//      ⚠️ 这条只有代码复核 + 单测，未做真机复现（触发需要 `资料库/原始/` 下 > 2000 个文件）。
//    ③README / README.en 把系统要求（Android 16+ / arm64）提到第一屏。
//    仍无破坏性改动，故进 patch 级。
// ⚠️ 2.0.12（2026-10-11）：三批修复攒在一起发，均无破坏性改动：
//    ①抱朴连接保活（用户报「每次重进抱朴都会重新连接」）：网络实例与轮询/SSE 改挂进程级
//      `OcRuntime`（原先挂在页面上，切 Tab 即销毁），`OcRepository.open()` 按纯函数
//      `shouldReopenConnection()` 判断"连接还活着就复用"。真机验收：切到终端再回抱朴，
//      日志为 `open 复用现有连接：会话 ses_…（切 Tab / 返回不重连）`，期间 SSE 事件照收
//      （docs/acceptance/taiji-keepalive-2026-10-11.md）。附 E-093：换包后首次进抱朴可能
//      因 serve.log「listening 先、password 后」的落盘竞态拿到 null 密码 → 401，
//      已给孤儿 serve 分支补有界等待。
//    ②E-092 缓存口径：ffmpeg 是 hermes **必需**包（`pm/install.py` 的 drift 里 optional
//      才跳过），原先被「可选工具清理」当可清项删掉 ⇒ hermes 每次启动报
//      `install out of sync (ffmpeg: …)` 且不会自愈；现归入保留，并新增「hermes 自检」组
//      列出「已注册但目录不在」的包。另：终端外观卡的 字号/画布留白/配色 改为只读默认
//      （只有行距可选）、快捷键条 `⌫` 改发 Delete（CSI `3~`）、公共区安装脚本可单独清。
//    ③UI/UX 审计 10 条 P0/P1（台账 §A #21）：首页按钮折行、顶栏副行溢出、抱朴关闭/删除
//      防误触与文案、终端选词时左缘右滑不再误关、权限抽屉 remember 跨请求残留、
//      抽屉底部被键盘/手势条遮挡、存储卡「统计中…」等。
//    仍无破坏性改动，故进 patch 级。
// 2.0.13（2026-10-11）：git 提速与加固（镜像包装器 + push 直连，E-096）、仅私有模式判据修好（E-094）、
//    网络预检改横幅不再往会话里打字（E-095）、资料库 .docx 指引补可照抄命令（E-080）、
//    文档补「PRoot 不提供安全隔离」与 Debian rootfs 许可段（E-097/E-098）、
//    **正式包改用独立 release keystore**（E-099）、顶栏标题改短并加 autoSize（另一会话产出，蓝总管验收）。
//    ⚠️ 换签名钥匙 = 装这一版前必须先卸载旧版（README、CI 正文、E-099 三处都写明）。
// 2.0.14（2026-10-11）：三处用户点名的界面改动 ——
//    ①首启权限引导合并成一个**权限页**（原来是一次弹一个系统对话框，用户说「有点晃眼也不符合
//      使用习惯」）：一屏列全、逐条点授权，记「已引导过」不再反复弹；
//    ②终端长按「更多」删掉「重载字号」一项（字号已有双指缩放 + 设置页「终端外观」可看），
//      并把「搜索选中文字」从写死 bing 的 URL 改成 `ACTION_WEB_SEARCH`（交给手机自己的默认
//      搜索 / 浏览器，只有设备没有可用 App 时才回落到浏览器打开搜索 URL）；
//    ③设置页「保活记录（被杀留档）」由内联大卡改成一行入口，内容搬进独立页面。
//    仍无破坏性改动，故进 patch 级。
// 2.0.15（2026-10-11）：三处真机反馈到的卡顿 / 路径问题 ——
//    ①清理「Agent 缓存包」时界面卡住（原来在 Compose 的 onClick 里同步删目录）：改成丢到 IO 线程，
//      按钮就地显示「清理中…」，并且**同一次遍历**里把文件数 / 目录数 / 真实字节数一起数出来
//      （不再「先量后删」两趟走）；
//    ②换工作区后资料库跟着换、旧那份像丢了：换之前先问一句，可以选「复制过去并切换」或
//      「只切换，资料留在原地」；当前资料库还没挂载而别处有一份带资料的，会指出来并给一键复制；
//    ③在 App 外面往「原始」文件夹放东西、App 里没反应：回到设置页（ON_RESUME）即自动重新整理，
//      不需要先进终端。
//    另附渲染层两处（台账 §B-补-47）：选词拖动时不再跟着滚终端；「显示列 → 合法游标列」的判据
//    收敛成单一第一方实现，并修掉「行内出现零宽字符就把手柄列夹回去」的毛病（有 JVM 单测）。
//    仍无破坏性改动，故进 patch 级。
// 2.0.16（2026-10-11）：许可边界与信息架构收尾 ——
//    ①**第一方代码自本版起改为「保留所有权利」**（用户拍板；依据 = 全历史 710 提交无外部人类贡献者）：
//      根 `LICENSE` 换成第一方声明，原 GPL-3.0 全文**逐字节**移入 `licenses/GPL-3.0.txt`，
//      **≤ v2.0.15 的已发布版本仍按 GPL-3.0 分发**（GPL 不可撤回）。随包正文因此变成**九份** .txt
//      （第一方声明在包内名 `LICENSE-zhengdao.txt`）；设置页「关于 → 许可证」与「开源许可」页
//      同步改口径。新增 `CONTRIBUTING.md` / `CLA.md`（外部贡献须先签 CLA，且必须早于第一个外部 PR）。
//    ②设置页信息架构重排：入口页 + 六个二级页（另一会话产出，蓝总管验收）——终端外观 / 工作区 /
//      资料库 / 保活记录 / 运行日志 / 帮助；首页「去设置看记录」「看日志」的通知按钮**接到对应二级页**
//      （台账 §B 顺序 11 = 原 §B-补-42；此前两处都只是打开设置首页）。
//    仍无破坏性改动，故进 patch 级。
//    2.0.17（2026-10-11 深夜，用户「今天就彻底结束了」那一轮）：
//    ① 进终端不再自动重放上次 Agent 的启动命令（ERRATA E-113）；② 首页体检「Hermes 依赖环境」
//      红叉误报修掉（E-114）；③ hermes 自己装的扩展——浏览器工具 agent-browser/chromium 与桌面
//      自动化 cua-driver——一律保留、不给任何清理入口（E-115）。三条都是修正既有行为，仍 patch 级。
//    2.0.18（2026-10-13，前端线与「扫 bug / 死代码」两批合并后收口）：
//    ①界面双语化 + 深色主题（另一会话产出，蓝总管本机验收后合并）：全部用户可见文案进
//      `res/values-en/`（743 键与中文一一对应、占位符零差异）＋ `res/xml/locales_config.xml`
//      ＋ manifest `localeConfig`；设置页「外观」可切跟随系统 / 浅色 / 深色；体检改并行流式
//      上屏（TCP 探测 1.2s→0.6s）、首页 Toast→Snackbar、「上次值」缓存接住数字位点；顺带
//      修掉 3 处「按中文文案判分支」——切英文后必然失效的真 bug。终端页标题
//      「证道 - Debian 13.7」按用户点名豁免、不翻。
//    ②明文放行收窄（审计）：`usesCleartextTraffic="true"` → `network_security_config`
//      白名单，只放行回环。曾把 `deb.debian.org` 也加进去，后**撤掉**：明文策略只在平台
//      网络栈生效（AOSP NetworkSecurityPolicy），不拦 bionic 的 `connect()`，guest 里的
//      apt 是 glibc + GnuTLS 原生程序 ⇒ 与 NSC 无关（真机实测 `apt-get update` 照常）。
//    ③E-097 收口（用户拍板「随包分发」）：镜像构建时把 348 个 Debian 包的 `copyright` 收成
//      `/usr/share/zhengdao-copyright.tar.zst`（4.78 MiB → 0.49 MiB；份数 < 300 即 `exit 1`），
//      并作为独立资产随 Release 分发（v2.0.13~v2.0.17 的旧镜像已回补挂上）。
//    ④「扫 bug 与死代码」一批（审计）：安装锁罩住会话启动（`withPrepareLock`）、备份规则由
//      AGP 空模板改成逐条 exclude、serve 停服判据收窄到 `/oc/usr/bin/opencode`、
//      `BackgroundOnly` 把「别在主线程量内存」变成会抛的断言、越界判据统一到 `PathGuard`、
//      硬件退格在粘滞 Ctrl 下也镜像行缓冲（`HardwareKeyBufferGuardTest` 钉住）、删 3 个零引用类型。
//    ⑤本版新增两道「不许静默退化」的关卡：本地打 release 包若没设 `ZHENGDAO_KEYSTORE_FILE`
//      直接失败（见下方发版签名断言）；CI 增加许可证正文门禁 `tools/check-licenses.py`
//      （E-105 实测过 AGP 会剥掉构件里的 `META-INF/LICENSE*`，所以「随构件分发」不能当承诺）。
//    仍无破坏性改动，故进 patch 级。
// ⚠️ 2.1.1（2026-10-13）：patch 级 —— 修 **E-123「抱朴页切到历史会话后页面不重载」**。
//    现象（用户 2026-10-13 真机报障，逐字：「我刚刚在抱朴页切换会话的时候就有个出错，然后关闭 APP
//    重进了才没问题的。是那种切换回旧聊天会话的时候」）：切会话后页面停在空列表、看着像卡死/出错，
//    杀进程重进才恢复。根因：`OcRepository.switchSession()` 先把 `sessionId` 预置成目标 id，再去调
//    `open()`；而 `open()` 的连接复用判据（v2.0.12 / §A #22 引入）看到 `requested == current` 就
//    整段 `return`（只打「open 复用现有连接」，不 `loadAll`、不重建轮询与 SSE）⇒ 换会话等于没换。
//    修法：把「清残留状态」抽成 `BaopuState.afterSessionSwitchStarted()`，**刻意不动 sessionId**，
//    让判据照旧判「换会话」；`switchSession()` 只负责清状态 + 调 `open()`。两条 JVM 回归用例钉住
//    （`OcConnectionPolicyTest`：「预置状态绝不动 sessionId」「预置后 + 判据 ⇒ 必须重开」）。
//    另附一处顺带澄清：`startNewSession()` 走同一条路，一并修好。
//    无破坏性改动：与 v2.0.13~v2.1.0 同一把发布钥匙，**直接覆盖安装、数据全在**。
// ⚠️ 2.1.0（2026-10-13）：本版是**新功能面**，故进 minor 级（2.0.x 那几版都走 patch 级）。
//    ①Agents 页新增 **Codex CLI 卡**：npm 全局安装 `@openai/codex`，启动命令 `codex --no-daemon`
//      （`--no-daemon` 必须是**顶层**标志，写到子命令后面 Codex 会报未知参数 —— §A #69 的坑一）；
//      这张卡只做安装 / 启动 / 卸载三件事，**不预置** `~/.codex/config.toml`、**不预置**
//      `models.json`、App 内**不收**密钥（用户 2026-10-13 拍板：国外用户走官方登录；
//      想换基底——比如 DeepSeek——的人自己在 guest 里配，卸载时 `~/.codex` 保留）。
//    ②终端 **WakeLock 空闲策略**：保活锁不再跟「会话存不存在」走，改为跟「终端有没有活动」走 ——
//      每 30s 汇总本 UID 全部进程的 CPU jiffies，连续 20 轮（10 分钟）单轮 < 2 jiffy 且累计
//      < 15 jiffy 才释放；释放期间活动回来立即重取；通知文案两态（会话运行中 ⇄ 空闲休眠）。
//      判据一律偏保守：**宁可多持锁，不误判为空闲**（用户逐字）。
//    ③抱朴 **401 自治愈**（E-093 的另一半）：收到 401 就用**重新解析到**的 serve 密码重试一次
//      （OkHttp 原生 `Authenticator`，最多重试一次、SSE 不碰）；密码解析改为「收集候选口令
//      （最多 3 个、新→旧）+ `GET /api/session` 探针定案」，不再只看日志最后一行。
//    三处都无破坏性改动：与 v2.0.13~v2.0.18 同一把发布钥匙，**直接覆盖安装、数据全在**。
val appVersionName = "2.1.1"

// ── 签名钥匙：由环境变量**显式钉死**，不再依赖 AGP 自己猜目录（docs/ERRATA.md E-014 §7）──
// 背景（2026-10-07 深夜实测）：CI 把本机那把 debug keystore 还原到 $HOME/.android/debug.keystore，
//   keytool 读出来的指纹**就是**存量用户那把 key（44e2fe86…a3be18）——可打出来的 release 包
//   却是**另一把随机 key** 签的（连着两次各不同：18e5268a… / 9409433d…）。
//   ⇒ runner 上 AGP 解析到的 store 并不是我们写进去的那一份；原因尚未定论
//     （`:app:signingReport` 已进 CI 日志常驻诊断）。
// 因此：CI 里设 ZHENGDAO_KEYSTORE_FILE=<keystore 绝对路径>，这里把 AGP 内建「debug」签名配置的
//   storeFile 直接钉到该路径。本地不设这个变量 ⇒ 行为与从前一字不差（仍用 ~/.android/debug.keystore）。
val zdKeystorePath: String? = System.getenv("ZHENGDAO_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }

// ── 发版签名断言（2026-10-13）──────────────────────────────────────────────────
// 为什么需要：release 变体用的是 AGP 内建「debug」签名配置（见下面 buildTypes 里的说明），
//   本机没设 ZHENGDAO_KEYSTORE_FILE 时它会**静默回退**到 `~/.android/debug.keystore` ——
//   打出来的「正式包」与存量用户机上那把签名不一致，用户只能卸载重装（卸载会清掉 App 私有
//   目录 = 整个 Debian 环境重下，E-014 / E-099 的旧伤）。CI 侧本来就有两道关卡（build.yml
//   还原钥匙时对指纹、assembleRelease 之后 apksigner 验产物），唯独「在本机打正式包」这条路
//   没有任何提示，所以补这一道**配置期**断言：要跑 release 任务而钥匙没设 ⇒ 直接失败。
// 只对名字里带 Release 的任务生效 ⇒ debug 包、单测、Lint、IDE 同步都不受影响。
// CI 的 R8 冒烟（.github/workflows/ci.yml）不带这个变量、产物也刻意不发布，故显式传
//   -PallowDebugSignedRelease 放行（它只验「混淆与资源收缩会不会炸」）。
val releaseTaskNames = gradle.startParameter.taskNames.filter { it.contains("Release") }
if (releaseTaskNames.isNotEmpty() && zdKeystorePath == null &&
    !project.hasProperty("allowDebugSignedRelease")
) {
    throw GradleException(
        "拒绝在没有发版钥匙的情况下打正式包：本次任务 ${releaseTaskNames.joinToString(" ")}，" +
            "但环境变量 ZHENGDAO_KEYSTORE_FILE 未设置 ⇒ 产物会用 ~/.android/debug.keystore 签，" +
            "存量用户无法覆盖安装、只能卸载重装（详见 docs/ERRATA.md E-014 / E-099）。" +
            "请先设置 ZHENGDAO_KEYSTORE_FILE / ZHENGDAO_KEY_ALIAS / ZHENGDAO_KEYSTORE_PASSWORD / " +
            "ZHENGDAO_KEY_PASSWORD；确实只想验证混淆与资源收缩（产物不发布）时，加 " +
            "-PallowDebugSignedRelease 放行。"
    )
}

android {
    namespace = "com.example.zhengdao"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.zhengdao"
        // 打包口径（2026-10-15 第㉕刀，依据 docs/ERRATA.md E-134）：
        //
        // 旧口径（已废）：「targetSdk 必须钉死 28 —— Android 10 起 W^X 禁止 targetSdk≥29 的
        // 应用从可写数据目录 exec，而 rootfs 跑在数据目录里 ⇒ 不可上调的架构级禁令」。
        //
        // E-134 证伪的是「**必须 ≤28**」这条必然性：真机原型（荣耀 PGT-AN10 / Android 16 /
        // targetSdk 36）把 Termux 四件套打进 jniLibs（nativeLibraryDir 只读且可执行）＋
        // PROOT_LOADER 显式指向后四判据全过。关键机制：guest 二进制由 proot loader **mmap
        // 进内存**加载（原型 ProotRunner.kt 原话「Android 从不直接 execve rootfs 里的任何
        // 文件」）⇒ 数据目录不可 exec 的限制根本不作用到它们。第⑳刀已把四件套全量迁入
        // jniLibs，故本刀抬到 36。
        //
        // ⚠️ 28 是**旧打包方式**（assets → files/termux-proot/ 可写目录再 exec）的前提，
        // 不是架构铁律。谁把四件套改回「释放到可写目录再 exec」的老路，29+ 确实过不去，
        // 必须重新评估此值 —— 别以为抬过一次就能永远抬着。
        //
        // ⚠️⚠️ oc 直启 opencode（ProcessBuilder(filesDir/oc/usr/bin/opencode)，不经 loader）
        // 的真机实测（第㉕刀，荣耀 PGT-AN10 / Android 16）：抬 36 后**首次启动**曾
        // 「error=13, Permission denied」⇒ 抱朴 serve 起不来；但杀进程重启 / App 重启后
        // **两次复验均成功**（/proc/<pid>/exe 实证数据目录二进制在跑、SSE 正常）。
        // ⇒ 「36 必被 W^X 拦」的预告在本机**未复现**，error=13 属偶发（疑安装后首启的
        // 厂商 freezer 瞬态，与「上次退出：厂商后台清理」同源）。单设备实证，别当普适
        // 结论：换机 / 换 ROM 必须重验；若真遇必拦，修法 = 给 opencode 也做「⑳刀式」
        // jniLibs 迁移或走 proot loader 加载（属 oc 业务代码，红线不碰）。
        targetSdk = 36
        // 最低安装门槛：安卓 16（API 36）。实测环境为荣耀 Magic 5 Pro
        // （PGT-AN10 / MagicOS 10.0.0.175 CHNC00E175R208P6 / Android 16）；
        // Android 15 及以下未适配未验证（README 有明确声明），直接拒绝安装。
        // ⚠️ 2026-10-07 更正：原注释写「MagicOS 11」，实测 build 号对不上——MagicOS 10
        //    才基于 Android 16（MagicOS 11 对应 Android 17），记录见 docs/acceptance/v1.1-2026-10-07.md。
        minSdk = 36
        versionCode = 36
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

    // ── 许可证正文随 APK 分发（2026-10-11）──────────────────────────────────────────
    // 义务来源：Apache-2.0 §4(a)（要给接收者许可证副本）、GPL-3.0 §4、LGPL-3.0 §2（libtalloc 并入
    // GPL-3.0）、BSD-3-Clause（二进制分发须复现声明）。此前正文只在仓库里 ⇒ 只拿到 APK 的人取不到，
    // §4(a) 在分发物层面没落实（docs/ERRATA.md E-104）。
    // ⚠️ **git 里只存一份正文**：源 = 仓库根 `licenses/`（八份 .txt + README）与根 `LICENSE`
    //    （第一方代码声明，随包更名为 `LICENSE-zhengdao.txt`），构建期由
    //    `stageLicenseAssets` 原样复制进 assets。之所以复制而不各存一份，是为了让
    //    「licenses/README.md 记的 SHA256」永远等于「随包的那一份」——两份一旦漂移，
    //    README 的校验命令就成了一句空话。
    // 入口：设置页「关于」→「开源许可」页（OpenSourceLicensesActivity）。
    // ⚠️ 资源目录不在这里挂：AGP 9 明确不允许 sourceSets API 接收 Provider（`srcDir(Any)` 也已弃用），
    //    改用本文件末尾的 Variant API 装配（`androidComponents { onVariants { … } }`）——
    //    顺带由它带上「装配任务 → 资源合并」的任务依赖，不靠 preBuild 兜。

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
            // 2026-10-12（§B 顺序 21 ③）：`isShrinkResources` 之前**没开**，资源侧
            //   一直只有代码侧在被 R8 缩；审计点出后补上（AGP 9.4 的 BuildType 上
            //   仍是这个属性名，已核对 gradle-api-9.4.0.jar 的 DSL 接口）。
            //   只影响未引用资源（模板色、旧 drawable），不动 JNI/JS 桥的 keep。
            isShrinkResources = true
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

    // lint 检查：ExpiredTargetSdkVersion 是 Google Play 的上架要求（targetSdk≥33）。
    // 第㉕刀（2026-10-15）targetSdk 已抬到 36（依据 ERRATA E-134，见上面 targetSdk 处注释），
    // 该检查的触发条件（targetSdk<33）已不成立；且本项目走 GitHub Releases 直发、从不上 Play。
    // disable 项**保留不删**：谁把 targetSdk 调回 <33，它会立刻重新生效 —— 那正是想要的。
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

    // xz 解压（抱朴 bionic OpenCode 的 .pkg.tar.xz 释放）：commons-compress 的 XZ 后端
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
    // P4-7（issue #17）：LocalLifecycleOwner 官方迁移载体（见 libs.versions.toml 注释）
    implementation(libs.androidx.lifecycle.runtime.compose)

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
    // 仅用于渲染层（BaopuMessageList.FinalAnswerText），不触碰会话/网络层（架构冻结红线）。
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

// ─────────────────────────────────────────────────────────────────────────────
// 许可证正文装配（2026-10-11；同日随「第一方代码换许可」调整）：仓库根 licenses/*.txt + 根 LICENSE → assets/licenses/
//   · licenses/ 里每份 .txt（Apache-2.0 / BSD-2-Clause / BSD-3-Clause / BSD-3-Clause-zstd /
//     GPL-2.0 / GPL-3.0 / LGPL-3.0 / NOTICES）与 README.md 内容一字不改，换行归一成 LF
//   · 根 LICENSE 更名为 LICENSE-zhengdao.txt（**第一方代码声明**；GPL-3.0 正文自本版起在 licenses/GPL-3.0.txt）
// 末尾断言九份 .txt 必须落到实处：漏一份就等于 APK 里缺一份正文，
// 而这正是 E-104 记的「台账写了、文件里没有」的同款错误 —— 宁可构建失败。
// 校验随包结果（不必装包）：
//   .\gradlew.bat :app:assembleDebug
//   powershell -c "Add-Type -A System.IO.Compression.FileSystem; $z=[IO.Compression.ZipFile]::OpenRead('<apk>'); $z.Entries | ? FullName -like 'assets/licenses/*' | %% { \"{0}  {1}\" -f $_.Length, $_.FullName }; $z.Dispose()"
// ─────────────────────────────────────────────────────────────────────────────
/**
 * 许可证正文装配：把仓库根 `licenses/`（八份 .txt 正文 + NOTICE 汇总 + README）与根 `LICENSE`
 * （第一方代码声明，更名 LICENSE-zhengdao.txt）原样复制进一个受 AGP 管辖的「生成资源目录」，
 * 由变体资源合并随 APK 打包。
 *
 * 为什么不直接 `sourceSets.assets.srcDir(<仓库根 licenses>)`：
 *   · 根 `LICENSE` 在仓库根目录下，没法只挑一个文件加进 assets 源集（挂目录会把整个仓库根带进去）；
 *   · AGP 9 明确禁止 sourceSets API 接收 Provider，并要求改用 Variant API。
 * 复制而不是在 assets 里再存一份：见 `android {}` 里的说明（免两份漂移）。
 */
abstract class StageLicenseAssetsTask : DefaultTask() {

    @get:InputDirectory
    abstract val licensesDir: DirectoryProperty

    @get:InputFile
    abstract val rootLicense: RegularFileProperty

    /** 由 `addGeneratedSourceDirectory` 指定（build/generated/…/assets/licenses），不手工设置。 */
    @get:OutputDirectory
    abstract val outDir: DirectoryProperty

    /**
     * CRLF（以及裸 CR）→ LF。许可证正文的权威形态是 LF（`licenses/README.md` 记的字节数与 SHA256 都是它），
     * 构建期统一，免得随包正文的哈希随本机检出状态（`core.autocrlf`）漂移。
     *
     * ⚠️ 必须写成**类内**方法：脚本（`.kts`）里声明的顶层函数是**脚本类的成员**，
     * 任务类一旦调用它，Kotlin 就会把这个类编成「非静态内部类」，Gradle 建任务时直接报
     * `Class Build_gradle.StageLicenseAssetsTask is a non-static inner class.`（本次实测踩到）。
     */
    private fun normalizeToLf(bytes: ByteArray): ByteArray =
        String(bytes, Charsets.UTF_8).replace("\r\n", "\n").replace('\r', '\n').toByteArray(Charsets.UTF_8)

    @TaskAction
    fun stage() {
        val outRoot = outDir.get().asFile
        if (outRoot.exists() && !outRoot.deleteRecursively()) {
            error("[stageLicenseAssets] 清不掉旧输出目录：$outRoot")
        }
        // ⚠️ AGP 把这个目录当作 assets 的**根**：正文必须落在它以下的 licenses/ 子目录里，
        //    包内路径才会是 assets/licenses/<正文>（否则会摊平到 assets/<正文>）。
        val out = File(outRoot, "licenses")
        check(out.mkdirs() || out.isDirectory) { "[stageLicenseAssets] 建不出输出目录：$out" }

        // ⚠️ 一律归一成 LF 再落盘。仓库形态（git blob）本就是 LF，但 `core.autocrlf=true` 的**老检出**
        //    工作区里可能是 CRLF（本机主工作树就是），直接 copy 会把 CRLF 版本装进 APK ⇒
        //    随包正文的哈希随「在哪台机器上构建」变，`licenses/README.md` 记的 SHA256 就不再是
        //    「随包那一份」的摘要。归一之后：随包正文恒等于官方原文（LF）、与上表逐字节一致。
        val copies: List<Pair<File, String>> =
            licensesDir.get().asFile.listFiles().orEmpty()
                .filter { it.isFile }
                .map { it to it.name } +
                (rootLicense.get().asFile to "LICENSE-zhengdao.txt")
        copies.forEach { (src, name) ->
            val canonical = normalizeToLf(src.readBytes())
            File(out, name).writeBytes(canonical)
            if (canonical.size.toLong() != src.length()) {
                logger.lifecycle(
                    "[stageLicenseAssets] $name：CRLF → LF 归一（${src.length()} → ${canonical.size} B）",
                )
            }
        }

        // 兜底：装配结果里不许再留 CR —— 留着就说明归一没生效，随包哈希会与 README 对不上。
        val withCr = out.listFiles().orEmpty()
            .filter { it.isFile && it.readBytes().any { b -> b == 0x0D.toByte() } }
            .map { it.name }
        check(withCr.isEmpty()) { "[stageLicenseAssets] 正文里仍有 CR（未归一成 LF）：$withCr" }

        val txts = out.listFiles { f -> f.isFile && f.name.endsWith(".txt") }
            ?.map { it.name }
            ?.sorted()
            .orEmpty()
        val need = listOf(
            "Apache-2.0.txt",
            "BSD-2-Clause.txt",
            "BSD-3-Clause.txt",
            "BSD-3-Clause-zstd.txt",
            "GPL-2.0.txt",
            "GPL-3.0.txt",
            "LGPL-3.0.txt",
            "LICENSE-zhengdao.txt",
            "NOTICES.txt",
        )
        check(txts.containsAll(need)) {
            "[stageLicenseAssets] 许可证正文缺失：应有 $need，实际 $txts ——" +
                "APK 将不带正文（见 docs/ERRATA.md E-104 与 licenses/README.md）"
        }
        logger.lifecycle("[stageLicenseAssets] OK：已装配 ${txts.size} 份许可证正文 → ${out.absolutePath}")
    }
}

val stageLicenseAssets = tasks.register<StageLicenseAssetsTask>("stageLicenseAssets") {
    description = "把仓库根 licenses/ 与 LICENSE 复制进 assets/licenses/（随 APK 分发的许可证正文）"
    group = "build"
    licensesDir.set(rootProject.layout.projectDirectory.dir("licenses"))
    rootLicense.set(rootProject.layout.projectDirectory.file("LICENSE"))
    // 复制 + 列目录 + 比文件名极廉价，不做增量跳过 —— 免得「跳过」看起来像「通过」。
    outputs.upToDateWhen { false }
}

// 每个变体（debug / release / benchmark）的 assets 里都生成一份，任务依赖由 AGP 负责携带。
androidComponents {
    onVariants { variant ->
        // assets 源集在 AGP 里声明为可空 → 用安全调用。真漏挂的兜底不是这里的编译期，
        // 而是「解包 APK 逐份核 SHA256」那一步（装配目录照样生成，但不进包会被当场看见）。
        variant.sources.assets?.addGeneratedSourceDirectory(
            stageLicenseAssets,
            StageLicenseAssetsTask::outDir,
        )
    }
}