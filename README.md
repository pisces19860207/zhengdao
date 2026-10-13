# 证道（Zhengdao）

**简体中文** ｜ [English](README.en.md)

安卓上的轻量级 AI Agent 运行环境——免 root、零命令、一键安装你自己的 Agent。

让普通用户在 Android 手机上拥有一套真实的 Linux 环境（Debian 13.7），并在其中一键运行 Claude Code、Hermes Agent、OpenCode 等官方 CLI Agent。

> ⚠️ **门槛（先看这个）：需要 Android 16（API 36）或更高 ＋ arm64 手机 —— 不符合就别下了，装了也跑不起来。**（详见下方「系统要求」）

> **Agent 支持状态（2026-10-11，荣耀 Magic 5 Pro 真机验收）**：**OpenCode 内置**（太极 Tab 直达对话界面，bionic 宿主版零 PRoot 开销，按需下载约 65MB）；**Hermes Agent 支持**（一键安装 → 启动 → TUI，`hermes update` 可用；它自带一套 Python 工具链，安装阶段需要联网，依赖环境损坏可在丹房一键修复）；Claude Code 与 AGY CLI（Antigravity）在终端自由安装（走各自官方脚本）。

> **界面结构**：底部三 Tab——**太极**（OpenCode 对话）/ **终端** / **丹房**（Agent 管理）；设置从右上角齿轮进入。
> **文件边界**：Agent 产出统一放工作区（默认手机 `Download/证道`，可在设置页更换），手机文件管理器直接可见、卸载证道后仍保留。

## 系统要求

- **Android 16（API 36）或更高版本**
- **arm64-v8a** 架构设备（绝大多数现代安卓手机和平板）
- 已在 **荣耀 Magic 5 Pro（MagicOS 10.0.0.175 / Android 16，机型代号 `PGT-AN10`）** 上完成实测

> **⚠️ 关于其他 Android 版本**
> 本项目目前**仅在 Android 16 上完成真机实测**，其他版本（Android 15 及以下）**未做适配和验证**，可能存在兼容性问题。
> 如果你在非 Android 16 设备上尝试安装，请自行评估风险并测试，**不保证能正常运行**。
> 由于是个人业余项目，精力实在有限，无法对所有 Android 版本提供适配支持，**请多多包涵**。

> **🔒 关于 targetSdk 28（长期说明）**
> 证道**刻意钉死 targetSdk 28**，这是架构级约束，不是版本落后：
> - **为什么**：Android 10 起系统禁止 targetSdk ≥ 29 的应用从「自己的可写数据目录」执行文件（W^X）。证道的整套 Linux 环境（rootfs 里成千上万个程序）恰恰放在这个目录里，只有 targetSdk 28 能豁免，proot 才能正常运行。**抬高 targetSdk = 整个 Linux 环境变成无法执行的死文件。** Termux（GitHub/F-Droid 版）、太墟均采用同一策略。
> - **代价**：Android 16 上，targetSdk 28 应用读写 `/sdcard` 共享存储**需要 `READ`/`WRITE` 运行时授权**（且 Android 13+ 须摘掉 manifest 的 `READ` 帽子才能持有 `READ`——详见 `d414dca`）。经 App 真身实测，`/sdcard` 直连**读写均可用**（文件管理器可见），这是**唯一主路径**。~~早期设计的「SAF 镜像同步到 Linux 环境内 `~/mnt/phone/`」兜底已整体删除~~（`4bbfd21`，连同 `mirror/PhoneMirror.kt` 与 FD 代理一并移除）；如今仅剩的 SAF 用途是**用户主动挑 rootfs 安装包**（`ACTION_OPEN_DOCUMENT`），与目录同步无关。
> - **长期风险**：若 Android 未来上调最低安装 targetSdk 门槛，或收紧 untrusted_app 域策略，证道需要迁移到 `system_linker_exec` 灰区或云机瘦客户端形态。该风险每季度在真机核对一次，详见 [`docs/milestones/已知限制.md`](docs/milestones/已知限制.md)。

## 网络要求

证道采用「本地环境 + 云端 Agent」的分层设计，网络需求分三个阶段：

| 阶段 | 是否需要网络 | 说明 |
|---|---|---|
| **首次安装环境** | **必需** | 下载约 200MB 的 Debian RootFS 压缩包（构建期已剔除 GPU 渲染栈等死重，见「当前状态」） |
| **安装 Agent** | **必需** | 从官方源下载 Claude Code / Hermes 等安装包（OpenCode 由太极 Tab 内置下载） |
| **日常使用 Agent** | **必需** | Agent 调用的是云端 API（GLM / DeepSeek / Claude 等），无网则 Agent 无法工作 |
| **纯终端使用** | **可选** | 环境安装完成后，离线也能使用 bash、git、python、npm 等本地工具 |

> 证道定位是「云端 Agent 的运行环境」，不提供本地模型推理。Agent 的智能来自云端 API，无网络时 Agent 无法工作，但本地 Linux 环境仍可正常使用。

### 网络代理提示

如果你使用代理 App（Clash、v2rayNG 等），请确保：

1. 代理 App 已开启，并工作在 **VPN 模式**（系统级代理）。
2. 若代理 App 开启了「分应用代理」，请把 **证道** 勾选进去（这是连接失败最常见的原因）。
3. 如果仍无法连接，可在设置页 →「高级」里手动填写代理地址。

## 荣耀 / MagicOS 保活指南

针对荣耀 MagicOS（含 Magic 5 Pro）实测，建议完成以下设置，避免证道在后台被系统清理：

1. **应用启动管理**
   - 设置 → 应用和服务 → 应用启动管理 → 找到「证道」
   - 关闭「自动管理」，手动开启「允许自启动」「允许关联启动」「允许后台活动」

2. **电池优化**
   - 设置 → 电池 → 更多电池设置 → 找到「证道」
   - 设为「不允许优化」

3. **多任务锁定**
   - 进入多任务界面（上滑悬停），找到「证道」卡片
   - 向下滑动出现小锁图标，点击锁定

4. **关闭子进程限制（如遇到进程意外退出）**
   - 进入开发者选项，确认「不要保留活动」没有被勾选

> 荣耀系统对前台服务较为尊重。只要证道显示了「前台服务通知」，并在多任务界面上了锁，通常可以在后台稳定存活。若仍被系统清理，证道内置的 tmux 会话恢复机制会在重新打开 App 时自动 attach 回原来的现场。

## 隐私声明

证道不上传用户数据。网络访问仅用于：

- 下载 RootFS 与 Agent 安装包
- 检查 App 与组件更新

服务器访问日志仅用于排查故障，保留 7 天，不用于用户行为分析。

⚠️ **终端不是安全沙箱**：guest（PRoot）里的进程与 App **同一个 Android UID**、同一个内核与网络栈，能读写 App 能读写的文件。**只运行你信任的命令**——App 的签名与校验链防的是"分发被篡改"，防不了"你在终端里自己执行的东西"。详见 [`docs/milestones/已知限制.md`](docs/milestones/已知限制.md) §10。

## 当前状态

**当前版本：v2.1.0**（`versionCode 35`，2026-10-13）。功能面在 v1.1 定型、v2.0.12 起收口——**之后以修 BUG、跟上游变化为主，不再按批次改结构**。仍未关的账（含已知的性能与体验条目）见 [Issues](https://github.com/pisces19860207/zhengdao/issues)。

单测 **737 例 / 91 个 suite 全绿**（`app\build\test-results\testDebugUnitTest`，2026-10-13 实测 0 失败 0 错误、2 例按环境条件跳过；v2.1.0 批次分支 `fix/batch-v2.1.0` 的合并提交见 `docs/FEATURE-LEDGER.md` §A）。

### 近期版本

| 版本 | 日期 | 一句话 |
|---|---|---|
| **v2.1.0**（当前） | 2026-10-13 | Agents 页新增 **Codex CLI 卡**（npm 全局安装、启动 `codex --no-daemon`、卸载保留 `~/.codex`；只做装/起/卸，不预置配置、App 内不收密钥）；保活锁改为**跟终端活动走**（30s 一轮汇总 CPU jiffies，连续 10 分钟无增长才释放，通知两态「会话运行中 ⇄ 空闲休眠」，判据偏保守——宁多持锁不误判空闲）；太极 **401 自治愈**（E-093 的另一半：收到 401 用重新解析到的密码重试一次，SSE 不碰）+ serve 密码解析改为「候选口令（最多 3 个）+ `GET /api/session` 探针定案」。 |
| v2.0.18 | 2026-10-13 | 英文界面 + 深色主题（743 条字符串全站双语、设置页「外观」可切跟随系统／浅色／深色、体检并行流式上屏）；明文放行由全局开关收窄成只放行回环的 `network_security_config`；E-097 收口——348 个 Debian 包的版权正文随镜像分发（镜像内 `zstd` 归档，并作为独立资产挂到 Release）；「扫 bug / 死代码」一批修复（安装锁罩住会话启动、备份规则逐条排除、serve 停服判据收窄、主线程断言、越界判据统一、粘滞 Ctrl 下退格也镜像行缓冲）；新增两道门禁：本机打正式包缺发版钥匙直接失败、CI 核许可证正文与仓库逐字节一致。 |
| v2.0.17 | 2026-10-11 | 缓存清理口径收口（E-115）：hermes 自带的浏览器工具与 `cua-driver` 划入「点名保留」，工具组**可清项清零**、清理入口不再渲染——沿用 v2.0.12 定下的原则（不把 Agent 必需/依赖包当可清项，当时修的是 `ffmpeg`），从此「可清」列表为空。 |
| v2.0.16 | 2026-10-11 | 许可边界收口：全套第三方许可证正文随包进 `assets/licenses/`，App 内「设置 → 关于 → 开源许可」可离线阅读全文；「保活记录 / 运行日志」两处「看日志」按钮落到正确出口（原来一律兜底跳设置页）；修复设置页行标签被长数值挤成竖排（E-111，本次发版的触发项）。 |
| v2.0.15 | 2026-10-11 | 三处真机反馈的修复：清理「Agent 缓存包」改到后台线程 + **边删边数**（以前在主线程同步删，缓存一大会卡）；换工作区**先问一句**（复制过去并切换 / 只切换），当前资料库未挂载而别处有资料时给**一键复制**；回到设置页即**自动重新整理资料库**（不必先进终端）；另修终端选词时拖动会跟着滚、并把「列 → 合法游标列」判据收敛成单一实现（修掉零宽字符把手柄列夹回去的毛病，8 条单测钉住） |
| v2.0.14 | 2026-10-11 | 三处点名界面改动：首启权限由「一个个系统弹窗」合并成一页**权限页**（一屏列全、逐项授权、记已引导）；终端长按「更多」去掉「重载字号」、选中文字搜索改走**系统默认搜索**（不再写死 bing）；设置页「保活记录（被杀留档）」由整屏大卡改成**一行入口 + 独立页**（内容一字未改） |
| v2.0.13 | 2026-10-11 | 正式包改用**独立发布签名钥匙**（E-099；旧包须卸载重装一次）；顶栏标题收短 + 自适应字号（不再被省略号截断）；v2.0.12 正文补「先卸载」警告、线上 19 个旧正式版清理 |
| v2.0.12 | 2026-10-11 | 太极页连接保活（切 Tab / 返回不再重连，顺带修「换包后首次进页偶发 401」＝E-093）；界面统一成 **iOS 化皮肤**（三 Tab 去胶囊、终端深色 chrome、键条深色化——**17 键 id 与顺序零改动**）；缓存口径修正（不再把 hermes 必需包 `ffmpeg` 当可清项＝E-092）；终端外观卡只留行距可选、键条 `⌫` 改 **DEL**；UI/UX 审计 10 条 |
| v2.0.11 | 2026-10-11 | 内置 opencode（bionic 宿主版）装不上修复（E-090）；资料库扫描上限不再静默截断（E-089）；系统要求提到第一屏 |
| v2.0.10 | 2026-10-10 | 终端视觉升级：**Catppuccin Mocha** 出厂配色、字体按 GB2312 子集化（APK 13.53 → **7.82 MB**）、行距「舒适」档（默认） |
| v2.0.9 | 2026-10-10 | 安装链路加固：公共区安装脚本**先核对指纹再执行**；rootfs 两条真 BUG + 审计工作表第一批五条；资料库摘要改本地线索 |
| v2.0.0 | 2026-10-09 | 里子工程 + **环境信任链**（见下节） |

每版正文见 [Releases 页面](https://github.com/pisces19860207/zhengdao/releases)；更早的历史变更记录见 [`release-notes.md`](release-notes.md)。

### 环境信任链（v2.0.0 建立，此后未改）

- **Rust 核心收口（`zhengdao_core`）**：sha256 校验、rootfs 解压（zstd + tar）、安装完整性摘要全部收敛进**单个** `libzhengdao_core.so`——JNI 边界只跨一次、`.so` 只加载一次；native 不可用时**自动回退**纯 Java 路径（sha256 → `MessageDigest`，解压 → commons-compress），可用性可观测
- **安装完整性 / 环境指纹**（E-044/E-049）：「修复环境」「回退重装」**不再抹掉**环境指纹；同版本整包已在本地时**直接用本地包补指纹**，不再为一个标记重下 192 MB
- **环境包瘦身**：构建残留清理 + locale 裁剪 + **剔除 GPU 软件渲染栈**（mesa + LLVM 152 MB，实际只切 `libllvm19` 落盘 −118 MB；mesa 本体保留——硬删会被 apt 依赖链连坐带走 ffmpeg）——终端场景**零功能损失**
- **环境包索引签名**：Ed25519，App 侧**先验签后解析**（签名由 Rust 核心验，与平台实现**对拍**，不一致按拒绝处理）；CI 端「没有密钥就不许发布」的硬关卡**已启用**——secret 已配置、线上 `.sig` 可回读验签（2026-10-08，见 `docs/ERRATA.md` E-052）
- **本地包校验值以索引为准 + 边车自愈**（E-053）：终端页「安装失败：SHA256 校验失败」的根因是本地那个没人维护的 `.sha256` 边车文件过期；现在按**签名索引**校验，过期边车在校验通过后**自愈重写**
- **增量更新（差分补丁）落地**（E-050）：环境更新只下差分补丁，补丁解压同样走 Rust 核心（可跳过指定成员）；补丁 sha 不匹配时硬失败，原环境一动不动
- **入库 `.so` 门禁**（E-048）：CI 自动解析 ELF，校验 16KB 页对齐与 JNI 入口符号（期望值现读 `CoreNative.kt` 源码）——「Rust 核心有没有真的在跑」从此由门禁说了算，不再可能静默回退没人知道
- **终端与太极可靠性一批**：会话 wakelock 续期（修掉「跑满 6 小时静默失保」）、终端画布留白（边到边不再吃行列数）、模型池刷新失败不再静默 + 失败文案走**人话口径**、`zzclean` 缓存自清理命令落盘（`--status` 只看不删）、真机走查三项（白屏防御 / 安装状态行收尾 / 同版本不再谎报「发现新版本」）
- **太极 Tab 可用性三修**（2026-10-09）：工具状态胶囊徽标（`●/✓/✗` 此前只声明、从未渲染）、会话历史行**常驻删除入口**、刷新会话失败不再静默也不再清空列表
- **测试（v2.0.0 当时）**：单测 **296 例 / 35 个 suite 全绿**（`app\build\test-results\testDebugUnitTest`，2026-10-09 09:24 实测 0 失败 0 错误 0 跳过）；Macrobenchmark + Compose 性能基线持续在用

功能面（v1.1 起定型；v2.0.12 换了一套 **iOS 化界面皮肤**，功能未增减）：

- **太极 Tab**：OpenCode 原生 Compose 客户端——Markdown 渲染、思考折叠、工具卡、
  会话历史/新建/恢复、模型池选择器（免费模型标注、会话级切换、重启保持）
- **终端**：Debian 13.7 真实环境（PRoot），tmux 会话保持；分屏/关屏一键化，
  首次进入有三点功能说明，「更多」菜单含清屏 / 重置终端、选中文字可直接交给手机的默认搜索或浏览器（v2.0.14 起去掉「重载字号」）
- **丹房**：Agent 一键安装/启动/卸载（二次确认、真实 du 尺寸、用户数据保留）、环境体检自愈
- **插件管理**（设置 → 插件）：管理**太极**内置 OpenCode 的插件（启停 / 缓存清理）
- **资料库**（设置 → 资料库）：把手机上的资料挂进终端 Agent 的视野；「提问时自动附上资料库线索」**默认关闭**，开启后由 App 在**本机**按子串匹配，只把「文件名＋行号」压成一行附在你这句话后面送出——内容不出本机（E-080 / E-084）
- **OpenCode 内置支持**、**Hermes Agent 支持**（真机全链路验收）；Claude Code / AGY CLI 走各自官方脚本
- 存储策略定稿：MANAGE 主路径 + /sdcard 直连；通知 4 渠道；缓存清理**分档显示**——hermes **必需包**（如 `ffmpeg`）与自己装的**扩展**（浏览器 `chromium` / `agent-browser`、桌面自动化 `cua-driver`，用户点名保留）一律只报不可清（v2.0.17 起工具组已无可清项）、公共区「安装脚本」可清而「工作区」是你的数据不清

> **⚠️ 关于 OpenCode 跑在哪（v1.2 起）**：「太极」Tab 用的是 App **内置**的宿主版
> （开箱即用，跑在**宿主 bionic**、不经 PRoot），配置文件在
> `files/oc/xdg/config/opencode/`；插件管理页只作用于这一份。
> **v1.2 起 App 不再在终端里预装/保留另一份 OpenCode**（终端若要自建 CLI 由用户自己在终端里
> `npm install -g` 决定，App 不为它管配置与插件）。
> ⚠️ 因为两者不在同一个网络栈，**改 rootfs 里的 `/etc/hosts` / `/etc/resolv.conf`
> 不会影响太极**，反之亦然。详见《故障排查手册》**坑 #0（置顶）**。

CI 产物（APK / RootFS / proot）见 [Actions 页面](https://github.com/pisces19860207/zhengdao/actions)，
正式版见 [Releases 页面](https://github.com/pisces19860207/zhengdao/releases)。

## 文档结构说明

本项目的文档分三层，各司其职。**动手改代码前先看对应层级**，避免照着过时的描述做：

| 层级 | 位置 | 作用 |
|---|---|---|
| **① 总设计** | [`docs/milestones/安卓AgentApp-设计方案-v3.md`](docs/milestones/安卓AgentApp-设计方案-v3.md) | 整体架构、技术选型与架构级红线（如 targetSdk 28 钉死、单会话模型、proot 分发方式）。所有分期规范的依据。 |
| **② 分期规范** | [`docs/milestones/`](docs/milestones/)（M1.1 ~ M5） | 每个里程碑的**工程约束与验收标准**。原则：**实现以代码为准，约束与红线以本文档为准**。 |
| **③ 合规底线** | [`PROVENANCE.md`](PROVENANCE.md) | 第三方组件的来源、许可证与不可踩的红线（例如 proot 只能 exec、绝不能 `loadLibrary`）。逐组件的许可义务与许可证正文见 [`THIRD-PARTY-LICENSES.md`](THIRD-PARTY-LICENSES.md) 与 [`licenses/`](licenses/)。 |

> 一句话记忆：**总设计定方向，分期规范定标准，PROVENANCE 定底线。**

## 独立开发声明

本项目全部第一方代码为从零独立编写，未参考任何第三方同类应用的代码。
允许参考的官方资料清单与禁止事项见 [PROVENANCE.md](PROVENANCE.md)。

## 致谢

这个项目是我和几个 AI 伙伴一起做出来的，各司其职：

**DeepSeek**（对话最多的伙伴）——日常讨论、方案碰撞、排障思路都先和它聊。
很多关键判断是和它一来一回磨出来的，再转给执行者落地。

**WorkBuddy**（计划制定者）——M1 到 M5 的全部里程碑规划与骨架文档出自它手：
每一期做什么、验收标准是什么，边界划得清清楚楚，执行才不会跑偏。如今它的几个
带人设的分身经由 MCP 各管一条支线，并行出活。

**Zcode**（智谱 AI 的编程助手，GLM-5.3-Flash 驱动）——全程的开发主力。
从 PRoot 在 Android 上的加载难题、SELinux 拦截、255 错误，到 Termux fork 基线的切换、
RootFS 构建管线、UI 落地，再到困了项目好几天的 Hermes uv 硬链接死局（最终靠包装
hermes 自带的 pinned uv 二进制破局）——整个"证道"从一行命令到一个完整 App，
是它一行行写出来的。

**DSH**（收尾总工）——多分支并行开发的汇总者：合分支、修冲突、过编译、
立 CI 门禁、写勘误台账、清工作树，最后交付真机验收。v2.0 R1 的里子工程
（Rust 核心收口、环境包瘦身、索引签名、`.so` 门禁）出自它手。

我负责提需求、拍板、踩坑，和在他们之间传话。

特别感谢 GLM-5.3-Flash 提供的免费额度，让这个项目能在 5 亿 token 的对话里，
从一个想法变成 GitHub 上一个真实可跑的仓库。

同样感谢这些站在肩膀上的开源项目：

- [Termux](https://github.com/termux/termux-packages) —— proot 的 Android 适配发行版，以及 terminal-emulator / terminal-view 终端引擎
- [proot-me/proot](https://github.com/proot-me/proot) —— 免 root 跑起整套 Linux 环境的基石
- [astral-sh/uv](https://github.com/astral-sh/uv) —— 快得飞起的 Python 包管理器
- [Nous Research](https://github.com/NousResearch) —— Hermes Agent 及其开放的官方安装脚本
- [OpenCode](https://github.com/sst/opencode) —— 开源编程 Agent
- [Debian](https://www.debian.org/) —— 13.7 作为 guest 环境的底座

## 许可

本项目**第一方代码**自 **2026-10-11** 起采用 **「保留所有权利」**（正文见仓库根 [`LICENSE`](LICENSE)）：
允许浏览、克隆与本地评估（含为评估而自行构建运行），**再分发与任何商业使用 —— 收费或免费提供、
作为服务对外提供（SaaS / 托管）、捆绑 / OEM / 贴牌、企业内部规模化部署 —— 需事先取得项目所有者的
书面许可**（联系：putaopanpan@gmail.com）。

**在此之前发布的版本（≤ v2.0.15）不受影响**，仍按各自发布时的 **GPL-3.0** 分发：GPL 授权一经发布
不可撤回，已经拿到那些副本的人，其 GPL 权利继续有效。那份 GPL-3.0 正文现在保存在
[`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt)（从仓库根 `LICENSE` 移入，**逐字节未改**）。

> **为什么可以换**：仓库历史上**没有外部人类贡献者**，第一方代码版权全在项目所有者手里 ——
> 取证（710 个提交的作者统计）见
> [`docs/闭源切换清单-2026-10-11.md`](docs/闭源切换清单-2026-10-11.md) 第七节。
> 外部贡献者此后若要合入代码，须先签 [`CLA.md`](CLA.md)（授予再许可权），见 [`CONTRIBUTING.md`](CONTRIBUTING.md)。

集成的 Termux terminal-emulator / terminal-view（v0.119.0-beta.3）为 **Apache-2.0**（此前误标为 GPL，
勘误详见 PROVENANCE.md）；内置 proot 组件基于上游 GPL 项目（proot-me/proot）编译，
作为独立可执行文件聚合分发，来源与许可义务见 [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md)。

随包携带的第三方组件、各自需要哪份许可证正文、正文在仓库里的位置：

| 组件 | 许可证 | 正文 |
|---|---|---|
| 本项目第一方代码 | **保留所有权利**（≤ v2.0.15 的版本为 GPL-3.0） | 仓库根 [`LICENSE`](LICENSE)；历史 GPL-3.0 正文见 [`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt) |
| Termux `terminal-emulator` / `terminal-view` | Apache-2.0 | [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |
| proot（Termux fork 发行版，独立进程调用） | GPL-2.0-or-later | [`licenses/GPL-2.0.txt`](licenses/GPL-2.0.txt) |
| libtalloc（proot 进程内动态库） | LGPL-3.0-or-later | [`licenses/LGPL-3.0.txt`](licenses/LGPL-3.0.txt) ＋ [`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt)（LGPL-3.0 第 2 条并入 GPL-3.0） |
| libandroid-shmem（proot 进程内动态库） | BSD-3-Clause | [`licenses/BSD-3-Clause.txt`](licenses/BSD-3-Clause.txt) |
| JetBrains Maple Mono（终端默认字体） | SIL OFL 1.1 | [`app/src/main/assets/fonts/OFL.txt`](app/src/main/assets/fonts/OFL.txt) |
| Maven 运行时依赖（Compose / AndroidX / OkHttp / kotlinx / commons-* 等，共 136 个已解析模块） | Apache-2.0 | [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |
| zstd-jni 包装代码 ＋ 其内的 zstd 本体（随包 native 库 `libzstd-jni-1.5.6-4.so`） | BSD 2-Clause（zstd-jni）／ BSD 3-Clause（facebook/zstd） | [`licenses/BSD-2-Clause.txt`](licenses/BSD-2-Clause.txt) ＋ [`licenses/BSD-3-Clause-zstd.txt`](licenses/BSD-3-Clause-zstd.txt) |
| 归属声明（Apache Commons Compress、Public Suffix List 数据） | NOTICE（Apache-2.0 §4(d) / MPL-2.0 数据） | [`licenses/NOTICES.txt`](licenses/NOTICES.txt) |
| xz（`org.tukaani:xz`） | Public Domain | 无正文义务（仅登记） |

**上表每一份正文都随安装包分发**：构建期装进 APK 的 `assets/licenses/`（第一方声明也在内，包内名为
`LICENSE-zhengdao.txt`），App 内 **设置 → 关于 →「开源许可」** 可离线阅读全文；来源 URL、取回日期、
字节数与 SHA256 见 [`licenses/README.md`](licenses/README.md)。
Maven 运行时依赖逐组对照表（声明许可证 + 覆盖方式 + 重新生成的方法）见
[THIRD-PARTY-LICENSES.md §8](THIRD-PARTY-LICENSES.md)：其中 **Apache-2.0 / Public Domain 由 `licenses/Apache-2.0.txt`
与登记覆盖，zstd-jni 的 BSD 2-Clause 见 `licenses/BSD-2-Clause.txt`，需要复现的归属声明见 `licenses/NOTICES.txt`**。
（2026-10-11 更正：此前这里写「依赖正文随各自构件分发」，实测不成立——Android 打包默认剥掉构件顶层的
`META-INF/LICENSE*` / `NOTICE*`，见 `docs/ERRATA.md` E-105。）

## 转载、二次打包与「证道」这个名字

第一方代码自 **2026-10-11** 起是 **「保留所有权利」**（≤ v2.0.15 的旧版本仍是 GPL-3.0）。作者的态度
写在这里，省得来回问：

- **欢迎**：写教程、录视频、代配环境/答疑收费、读源码学习、为评估在自己机器上构建运行、给上游提 PR 或 Issue。
- **需要先问（书面许可）**：再分发 —— 包括换个名字发布你自己的版本、任何商业使用、把本项目或其衍生品
  作为服务对外提供、捆绑进硬件或其它产品。商业授权与合作：putaopanpan@gmail.com。
- **请不要**：把安装包换个皮就说是你做的；删掉「关于」页里的作者、许可证与源码地址；
  用「证道」这个名字（或作者署名「小信多多」）发布你的重打包版本 —— 代码许可本来也只授权**代码**，
  **不含名称/商标授权**，冒用名字与署名是另一码事。
- **App 会说实话**：每个安装包都带签名，证道的「关于」页会显示「官方发布 ✓ / ⚠️ 非官方（被重新打包过）」，
  非官方包启动时再提示一次（ERRATA E-065）。这不是防谁，只是让用户知道手里这个包是谁签的。
- **签名密钥不在仓库里**（正式包一把独立的 release keystore、开发包一把 debug keystore，都在本机 ＋ CI Secret）。
  想自己发布就用自己的密钥，别指望能覆盖安装官方包。
  ⚠️ 2026-10-11 起正式包换了独立钥匙：**在那之前装过旧包的用户，必须卸载重装一次**（共享存储里的工作区不受影响，App 私有目录里的 Debian 环境要重下）。见 ERRATA E-099。

联系作者：putaopanpan@gmail.com（或直接开 [Issue](https://github.com/pisces19860207/zhengdao/issues)）。
