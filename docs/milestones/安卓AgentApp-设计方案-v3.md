# 证道（Zhengdao）——安卓轻量级 AI Agent App 设计方案 v3

> v3 = v2 + 合并 Zcode 审计 20 条（P0×5 / P1×9 / P2×6，2026-10-03 逐条复核采纳）
>
> 🧩 **收尾补齐（2026-10-03，ZCode 接手 WorkBuddy 未完成部分）**：§1 竞品定位对照；§4 16KB 对齐覆盖 proot 可执行文件本体；§5 配对码+端口双字段、Phantom 命令大版本复查；§6 CI 版本断言、manifest 签名下发方式、Python 3.14 与 Hermes 表述统一；§7 快捷键条补 SHIFT（粘滞）/PGUP/PGDN；§8 tar.zst 残留统一；§9 Checklist 增补 8 项；新增 §11 里程碑分期（旗舰 Agent 装跑通 = M1 门禁）。
>
> 🔄 **v3.1（2026-10-03 外部审计合并，逐条核验后采纳）**：targetSdk 28 失效时间表改为季度跟踪并预置备选方案（架构前提）；**上游 proot 在 Android seccomp 下 signal 31 退出已证实**，proot 必须带 Android 适配补丁 + M1.0 安卓 15 真机门禁（§4/§11）；IME 验收细化 + 输入回归页（§3）；配对向导完成率 30% 指标 + Shizuku 捷径辨析 + 跳过文案（§5）；签名私钥管理（§6）；日志留存期限（§9）；会话切换器、API Key 一键清除（§7）；`tested_on` 字段 + 复用约定 CI 化（§6/附录 A）。审计中"bunproot"项目与"Hermes 仓库 IME 修复提交"两项引用经查证不实/未能证实，未采纳。
>
> 🔁 **v3.2（2026-10-03 二轮复审修正：本轮我方 v3.1 两处误判被推翻）**：① **bunproot 确实存在**——GitHub 站内检索证实 `jjtseng93/bunproot`（"A port of PRoot to Bun"，用户态 chroot / mount --bind / binfmt_misc for Android，JavaScript、2 star、持续活跃）；v3.1 的"经查不存在"系通用搜索引擎未收录小仓库所致，已在 §4 改判为"备选技术侦察对象，不作为依赖项"。② **Hermes IME 修复提交确实存在**——本地完整历史可查：`834a9fc89d fix(tui): preserve IME text before return submit`、`8e629b9f38 fix(desktop): flush committed IME text on compositionend`；v3.1 的"未能证实"系检索方法缺陷（`-i` 模糊匹配淹没命中 + head 截断），IME 风险定级已在 §3 上调。③ Issue #106487：官方镜像（cnb.cool，6 小时同步）上该编号页面返回"Private issue"、本地 git 历史无此编号——**无法离线证实或证伪，标注待核实**（编号量级与该仓库 6 位 PR 编号体系吻合）。**方法论教训入库：仓库存在性断言必须用 GitHub 站内检索，git 历史检索必须精确匹配且不得截断结果。**
>
> 📉 **v3.3（2026-10-03 架构收窄，用户定案）**：① **单一发行版 Debian 13.7（trixie）**——彻底移除 Ubuntu 26.04 与全部"切换发行版"的逻辑、UI 与构建管线（§3/§5/§6/§7/§8/§9/§11/附录 A 同步清扫）；② **依赖版本锁定**：glibc 用 Debian 自带 2.41 不手动升级；Node.js 26 经 NodeSource 官方源安装（不用 Debian 源旧版），锁定 major=26；Python 3.13 用系统自带，uv 退化为纯 pip 安装器，manifest 移除 `python_version` 字段；③ manifest 收敛为单 rootfs 对象（schema version 5）；④ 项目中文名定为**「证道」**。
>
> 🚨 **v3.4（2026-10-03 三轮外部审计合并，逐条核验后采纳）**：① **侧载身份验证政策落地（重大外部变化，已核实）**——Google 2025-08-25 宣布、2026-09-30 起在巴西/印尼/新加坡/泰国首批执法、2027 起全球推行："GMS 认证设备上安装的 APK（含侧载）必须来自已验证身份的开发者"。影响评估：国行无 GMS 设备（本项目主要人群）不在覆盖范围；带 GMS 认证设备受影响；缓解 = 开发者身份注册（免费一次性）+ 政策自带"高级流程"豁免。风险登记升级为**每月跟踪**（架构前提）。② seccomp 机制澄清：`PROOT_NO_SECCOMP` 只能解"proot 自装过滤器"类故障，对"内核策略拦截"类无效——两类故障分开诊断（§4）。③ 私钥管理细化为**离线签发**流程（§6）。④ IME 外部佐证补录、向导增设「终端手动执行」入口（§3/§5）。⑤ 审计引用修正："sereus"实为无关的 React Native 聊天应用、"cjkime 库"二次查无此物——未采纳；"zed-android-port"确认存在（佐证 targetSdk 28 策略为社区通行做法）。
>
> 🛡️ **v3.5（2026-10-04 内存治理与构建优化合并）**：① **R8 字节码优化必开**（release 构建 `isMinifyEnabled + isShrinkResources`，debug 不开），显著减小 APK 体积与内存占用；② **`onTrimMemory` 回调**：切后台主动释放图片缓存/空闲 DB 连接等可重建资源，但**绝不触碰 tmux/proot/agent 会话**（生命周期铁律）；③ **防杀矩阵新增内存治理层**：R8 + onTrimMemory + guest RSS 软监控（M2 §6），降低被系统"清道夫"盯上的概率；④ **电池白名单引导升级为两步走**：系统「不优化」弹窗 + 国产 ROM 自启动/后台锁图文引导（M2 §7）；⑤ 评估并**否决 `ulimit -v` 3GB 硬限**：限制的是虚拟地址空间而非物理内存，Node/V8 默认保留数 GB 虚拟空间，硬限会误杀 Agent——改为软监控 + 主动清理（详见 M2 §6 设计说明）。
>
> 📱 **v3.6（2026-10-04 会话模型收窄，用户定案）**：**多会话模型暂不实现，采用单会话模型**。① 移除「会话切换器」（v3.1 曾采纳的外部审计建议）——手机屏幕不需要在多个 Agent 会话间切换，桌面/平板场景以后再说；② 自由终端与会话模型统一为**单会话**：自由终端固定 `free` 会话，Agent 安装/启动复用同一会话（不再「每 Agent 一个会话」）；③ 简化 `SessionManager`——`ensureSession` 无 name 参数，固定唯一会话；`SessionRecovery` 只恢复这一个会话；④ M2/M3 骨架同步收窄。多会话能力由 tmux 分屏（window/pane）满足，不占额外界面。相关章节：§7、M2 §2/§8、M3 §3。
>
> 🌐 **v3.7（2026-10-04 网络就绪与软件源策略，用户关切）**：① **App 网络栈保持系统默认路由**——OkHttp/下载器不得设 `Proxy.NO_PROXY` 或自定义 `ProxySelector` 强制直连，否则流量绕过用户代理 App 的 VpnService，海外源全部连不上（"证道不走科学上网"的唯一可能来源，必须杜绝）；② **软件源策略**：rootfs 用 Debian 官方源、npm 官方 registry、pip 官方 PyPI，**一律不预置国内镜像源**（镜像滞后破坏可复现性、开代理访问国内源反而绕路）；③ 更新检查/manifest 拉取/组件更新失败时**同样接入连接失败智能引导**，不允许静默失败。相关章节：§6 网络代理段。
>
> 🧪 **v3.8（2026-10-05 竞品参考与运行时版本钉死）**：① **运行时钉死 Termux `v0.119.0-beta.3` 的 proot 体验**——用户长期实测该版本跑 Hermes 稳定，proot 运行时以复现其稳定体验为目标（§4 同款 fork + 参数面）；**Hermes Agent 保持官方安装方式**（`curl | bash`，不注入版本环境变量）；② **开源同类 App 纳入长期参考**（里子 Debian 13.7 不变）：Termux、proot-distro、UserLAnd、Andronix、太墟、Wolfi Terminal、Operit 的 UI/交互/文档可作为设计参考，**只学思路不抄代码**；③ **Alpine 评估后不采用**：`musl` 与主流 AI Agent 的预编译二进制（glibc）不兼容，Python 生态 wheels 多缺 musl，违背"减少踩坑"初心——Debian 13.7 仍是唯一发行版。相关章节：§1、§4、§6、§7、附录 A。
>
> 🖥️ **v3.9（2026-10-05 终端渲染层切换，用户定案）**：终端渲染层从 **xterm.js/WebView 切换为 Termux `TerminalView`**（`termux/termux-app` 的 `terminal-view` 模块，**Apache-2.0**，无 GPL 传染）——动因：WebView + xterm.js 渲染大输出卡顿，是终端卡顿的根源。**只换渲染层**：自研 `Pty.kt`（JNI PTY 桥）保留，仅把输出从"推给 WebView"改为"喂给 `TerminalEmulator`"。连锁同步：① 组件热更新**取消 `xterm-bundle`**（TerminalView 是 APK 内原生代码，渲染修复/新特性只能随 APK 发版，不再热更新）；② §7 输入方案改为 TerminalView 原生 EditText 的 `inputType` 设置；③ R8 注意事项、§9 Checklist、§10 v2 路线、M1 里程碑表述同步更新。相关章节：§3、§6、§7、§9、§10、§11、M1.1、M5。

> 分发渠道：GitHub 直发 APK · 目标用户：非技术普通用户 · 核心原则：即开即用、按需下载、全程零命令

---

> ⚠️ **架构前提（生死线，2026-10 经 Termux/太墟事实核验后写入）**：Android 10 起 SELinux 禁止 targetSdk ≥ 29 的应用从**可写数据目录**执行文件（W^X），而本方案的整个 rootfs（/usr/bin 下成千上万个二进制）都在数据目录里。因此：
> - **targetSdkVersion 钉死 28**（Termux GitHub/F-Droid 版、太墟均为此策略）——这是本架构成立的前提，不是可选项；
> - **只走 GitHub 直发**，永不上架 Google Play（Play 强制 targetSdk 34+，上架即死；Termux 2024-06 的"回归 Play"是原作者非官方分支，靠 `system_linker_exec` 灰区方案，官方自述违反 Play 政策可能下架，不走这条路）；
> - 连锁影响：targetSdk 28 下无需声明 FGS 类型（类型机制 API 29 才引入），也天然豁免 Android 15 的 dataSync 前台服务 6 小时限制（仅约束 targetSdk 35+）；targetSdk 34+ 的"动态加载文件须只读"限制同样不适用；
> - 风险登记（**每月跟踪**，v3.4 升级）：① 最低安装门槛是否上调（Android 14+ 仅禁止安装 targetSdk < 23 的应用，28 有安全边际）；② Play 侧 2026-08-31 起新应用/更新强制 target API 36（Android 16）——只约束 Play 上架，与 GitHub 直发无关，但为门槛上抬信号；③ 无线调试 / Phantom 相关系统开关是否有变；④ AOSP 对 untrusted_app 域的策略动向。预置两级备选：`system_linker_exec` 灰区方案（只做技术储备评估，不实现）与云机瘦客户端（§10 v2 路线）。（太墟 Android 10+ arm64-only 同策略，作为持续参照物）
> - 🗣️ **大白话版（2026-10-05 补，给非程序员看 / 对外答疑用）**：Android 10 以后，系统立了条新规矩——**不许 App 从"自己的可写文件夹"里运行程序**（防止恶意 App 偷偷下载并执行恶意代码）。但证道恰恰要把一整套 Linux 系统（里面成千上万个程序文件）放进这个文件夹里跑。**规矩一禁，一个程序都跑不起来。** 所以证道必须向系统声明"我是老版本 App，请按旧规矩对我"——这个声明就是 **targetSdk 锁定 28**。
>   - **这不是我们偷懒不更新**：只要一改成新版 SDK，整个 Linux 立刻变成一堆打不开的死文件。Termux（GitHub/F-Droid 版）和太墟**全都是这么做的**，这是唯一能跑的路，不是可选项。
>   - **所以也只能 GitHub 直发**：Google Play 强制要求 App 用最新 SDK，一上架就等于自杀。这是"只走 GitHub 直发"的根本原因，两者是同一件事的两面。
>   - **对用户有没有坏处？** 没有。targetSdk 只影响"App 向系统申请哪些新规矩"，不影响功能、不影响性能、不降低安全性（证道不申请敏感权限，也不联网上传任何数据）。你在 Play 商店之外下载的绝大多数工具类 App 都是这个策略。
> - 🚨 **侧载身份验证政策（v3.4 新增，重大外部变化）**：Google 于 2025-08-25 宣布"GMS 认证设备上安装的 APK（含侧载）必须来自已验证身份的开发者"，**2026-09-30 起在巴西/印尼/新加坡/泰国首批执法，2027 起全球推行**。影响评估：① 本项目主要人群为国内无 GMS 认证设备——**不在该政策覆盖范围内**；② 带 GMS 认证设备（海外版/刷入 GMS）受影响；③ 缓解路径：在 Android Developer Console 完成开发者身份验证（免费、一次性）、政策自带"高级流程"豁免、未认证设备完全不受限；④ 处置：发布前完成开发者身份注册，并每月核对该政策的地区推进与豁免细则。

## 1. 产品原则（面向普通用户）

**定位一句话：不做大而全，精准好用。** 本项目的发起源于真实踩坑——在现有终端类 App 里手动装 Agent，反复遭遇缺依赖、硬链接失败、环境损坏只能推倒重来。因此每个功能决策只问一个问题：**"这能不能让用户少踩一个坑？"** 不能的一律不做。

**踩坑清单 → 设计对策（可追溯）**：

| 用户真实踩坑 | 内置对策 | 章节 |
|---|---|---|
| 缺 git/python/node/ripgrep/ffmpeg/libatomic 等依赖，一个个手动装 | RootFS 预装全套，一次到位 | §6 |
| 硬链接失败（npm/uv 装到一半报错） | proot `--link2symlink` + `UV_LINK_MODE=copy` 默认内置，用户无感 | §4 |
| 环境折腾坏了只能卸载重装 | 「修复环境」快照重置 30 秒，且不丢登录态 | §8 |
| 想按官方教程装但手机打不出 ESC/CTRL | 快捷键条 + 一键粘贴官方命令 | §7 |
| 后台被杀、进度全丢 | 防杀四件套 + tmux 会话恢复 | §5 |
| 三环境实测的 11 条底层坑（Wolfi / Operit / 太墟 × Hermes） | 逐条内置对策，分构建期 / helper 期 / App 期解决 | 附录 A |

1. **首次启动的核心动作只有两个**：点"开始"、等进度条（中间夹一步**可跳过**的 API Key 配置——先给用户一个即时完成的小目标，大下载放后面）。其他全部自动化。
2. **所有技术概念对用户不可见**：不出现 proot、RootFS、ADB、依赖等词汇。UI 文案用"运行环境""防杀修复""修复工具"这类说法。
3. **任何失败都给一个按钮**：下载失败→"重试"；环境损坏→"修复"（一键重置）；Agent 起不来→"重新安装"。永远不让用户看到命令行报错后不知所措。
4. **按需下载**：APK 里只有界面和引擎，运行环境、Agent 本体全部首次使用时才下载，下完以后即开即用、离线可用。
5. **双通道并存**：普通用户走"一键安装"卡片；愿意自己动手的人走"自由终端"手动装官方版本。两条路装出来的 Agent 在主界面一视同仁，都能一键启动。

**明确不做（范围红线）**：
- **不提供本地模型推理**（llama.cpp、MLX 等）——定位是"云端 Agent 的运行环境"，不是推理引擎。理由：proot 环境无法访问手机 GPU/NPU，纯 CPU 推理体验差；模型文件动辄数 GB 下载与存储；推理时发热耗电触发系统杀进程，与保活目标直接冲突；且会引入模型管理、量化格式选择等一整块新复杂度。用户在自由终端自行编译安装不受限制（那是他们的自由），但官方 manifest 不提供、不支持、不答疑。

**定位对照（2026-10 竞品语境）**：Meta Muse（2026-09 发布，云端 VM 常驻个人 Agent，能力集封闭，仅美加上线，12 天下载量超 ChatGPT）与 Grok Bot（X 平台内开箱即用智能体）验证了"人人一个常驻 Agent"的需求为真，但它们吃的是**所有人都有的头部需求**（订餐、邮件、提醒），能力边界由厂商圈定。本项目站在光谱另一端——**开放能力 + 本地自持**：模型自由、数据不出设备、无订阅无区域门槛，服务"只有你自己有的长尾需求"（自有工作流自动化、小众工具、跑官方 CLI Agent）。判据一句话：**全人类都要的，Muse 们做；只有你要的，本项目做。**

## 2. 首次启动引导（Onboarding）

共 5 步，第 4 步仅安卓 12+ 需要：

| 步骤 | 用户看到的 | App 实际做的 |
|---|---|---|
| ① 欢迎页 | "欢迎使用，点击开始"（一个大按钮） | **提前创建通知渠道以触发系统权限弹窗**（targetSdk 28 在安卓 13+ 无法主动请求 POST_NOTIFICATIONS——请求会被忽略，弹窗由系统在建渠道时自动触发，必须靠渠道时机控制）；检测安卓版本与 ABI |
| ② API Key 配置（可选，可跳过） | 按服务商填写 Key → 存入 Android Keystore 加密存储，会话启动时注入环境变量；不填不影响后续（Agent 官方登录流程同样可用）；重置环境/换机后密钥仍在 |
| ③ 下载运行环境 | 进度条 + "正在准备运行环境，约 X MB"（移动网络下先弹"建议在 WiFi 下下载"确认） | 双通道自动测速选源，分块断点续传，SHA256 校验，静默解压到私有目录；**下载体积与落盘体积分开算**：进度条按下载量（~150MB）显示，存储预检按落盘量（解压 + 工具链装齐后约 1.5–2GB）执行，不足时明确提示还差多少 |
| ④ 后台防杀修复 | **v1.0 不实现，可跳过**；设置页保留 ROM 保活图文指南 | —（详见 §5；完整方案见 M4 留档，v1.0 不做） |
| ⑤ 选择 Agent | 大卡片列表："Claude Code / Hermes / …"，点"安装" | 拉取服务器 manifest，执行对应安装命令，进度条展示 |

完成后进入主界面 = Agent 列表，每个卡片两个状态：**「安装」→「启动」**。点启动直接进终端界面。

**本地归档自动安装（v0.6.0 已实现，2026-10-04）**：启动时若环境未安装，**先查 `Download/证道/` 下的本地安装包**（`debian-13.7-base-arm64.tar.zst`，需「所有文件访问」授权，App 内一键跳转授权页）——找到即自动安装（拷入 → SHA256 边车校验 → 解压 → 切 bash），**零交互零下载**；找不到才弹下载对话框（对话框含「授权存储」引导按钮）。该目录为共享存储，**卸载重装 App 也不会丢失**，一次下载终身使用。

## 3. 工程模块划分（Kotlin + Jetpack Compose）

```
app/
├── core/
│   ├── terminal/        # 终端渲染：Termux TerminalView（原生终端，Apache-2.0）+ 自研 JNI PTY 桥
│   ├── runtime/         # 运行引擎抽象：proot 后端（默认）+ chroot 后端（root 设备可选）
│   ├── download/        # OkHttp 分块下载：Range 断点续传、自动切源、SHA256 校验
│   ├── rootfs/          # RootFS 解压、快照、一键重置（重解压 pristine base）
│   └── manifest/        # 服务器 Agent 清单 JSON 拉取与解析（新增 Agent 免发版）
├── feature/
│   ├── onboarding/      # 首次引导 4 步
│   │   └── adbrepair/   # 无线调试配对向导（内嵌 ADB 客户端）
│   ├── agents/          # Agent 卡片列表、一键安装/启动、重新安装
│   ├── terminal/        # 自由终端：proot bash 会话 + 手机专用快捷键条 + 自定义 Agent 登记
│   ├── session/         # tmux 会话管理：启动、重连 attach、被杀恢复
│   └── settings/        # 设置：存储占用、修复环境、检查更新、关于
├── service/
│   └── SessionService   # 前台服务：常驻通知（会话状态）+ PARTIAL_WAKE_LOCK（仅会话活跃时持有）
└── update/
    └── AppUpdater       # GitHub Releases 自更新（检查→下载→引导安装）
```

技术选型：Kotlin + Compose（Material 3）、OkHttp、DataStore、Hilt。

**R8 字节码优化（必开，谷歌官方最推荐）**：`release` 构建默认开启 `isMinifyEnabled = true` + `isShrinkResources = true`，ProGuard 规则随依赖走（Compose/OkHttp 官方规则）。收益：显著减少 APK 体积与运行时内存占用（配合下方 `onTrimMemory`，两者叠加降低被系统回收的概率）。注意事项：
- `debug` 构建不开 R8，保证 zcode/ADB 调试栈与日志可读；
- R8 开启后**首次真机回归必须跑一遍完整验收清单**——混淆可能触发 TerminalView/JNI/反射等边界问题；
- 若某些反射路径（如 JNI 桥）被误删，用 `-keep` 规则显式保留并加注释，禁止全局 `-dontobfuscate`（会放弃体积收益）。

**`onTrimMemory` 回调（App 层主动降耗）**：`Application` 实现 `ComponentCallbacks2.onTrimMemory`，按等级释放非必要资源：
- `TRIM_MEMORY_RUNNING_MODERATE / LOW`：清图片缓存（Coil 等）、缩小 LRU；
- `TRIM_MEMORY_RUNNING_CRITICAL`：关闭空闲数据库连接、清空非前台页面持有的 Bitmap/Compose 状态；
- `TRIM_MEMORY_UI_HIDDEN`：App 切后台时释放可见性无关资源。
> 注意：**只释放可重建资源，绝不触碰 tmux/proot/agent 会话**——会话归属 SessionService，UI 资源释放与后台会话存活互不影响（见 §5 生命周期铁律）。

**📜 许可证与来源登记（v3.9 落地）**：本文件的「聚合分发登记 PROVENANCE」义务由项目根目录的 **`PROVENANCE.md`** 承载（2026-10-05 建立），其中列明：第三方组件清单与许可证、GPL proot 的合规声明与源码要约、**两条许可证红线**（① `libproot.so` 只能 exec 不能 `System.loadLibrary`/dlopen，否则整 App 被 GPL 传染；② `Pty.kt` 必须独立于 `termux-app` 的 GPL JNI 源码实现），以及**第一方可闭源范围**。实现与发版前必须同步维护该文件。

**终端渲染方案（v3.9 定案：原生终端，不用 WebView）**：采用 **Termux `TerminalView`（Termux 的终端渲染视图，Apache-2.0 协议）+ 自研 JNI PTY 桥**。Termux 的终端视图层（`termux/termux-app` 的 `terminal-view` 模块）是 **Apache-2.0 许可**，可安全裁剪复用，不引入 GPL-3.0 传染（真正 GPL 的是 Termux 的 proot 部分，本项目 proot 用 Termux proot fork，已在 §4/PROVENANCE 登记）。渲染层与 `termux-app` 应用本体无关，仅用其终端渲染组件。**PTY 桥保留（Pty.kt），只把输出从"推给 WebView"改为"喂给 TerminalEmulator"**——这是为消除 WebView 卡顿根源（xterm.js 在 WebView 里渲染大输出时的性能瓶颈）而做的定向替换。RootFS 用 Debian 13.7 官方 cloud image（单一发行版，见 §6），proot 用 Termux proot fork（§4）。

已知坑登记：Termux `TerminalView` 的**中文 IME 组合输入**同样需要正确处理 composition 事件——TerminalView 基于原生 `EditText`/`TerminalEmulator`，IME 处理比 WebView 稳定，但仍需真机验收。验收标准细化为可执行场景：**① 输入"你好"，候选窗位置正确；② 删除词中字符后重新输入，不丢首字符、不重复；③ 快速连续输入多个词**。另在 APK 内置"**输入回归页**"（进入即测 IME/渲染/快捷键条，每次热更新后 30 秒可回归）；CJK 输入按"持续维护的已知坑"对待。渲染 spec：**CJK/ASCII 混排等宽对齐**——字体栈必须保证中文严格两字宽（Noto Sans Mono CJK 兜底），错位会毁掉 ink 系 TUI（Claude Code）的排版，列真机验收。**IME 佐证与定级（v3.2）**：Hermes 桌面端自身携带 IME 专项修复提交（`834a9fc89d fix(tui): preserve IME text before return submit`、`8e629b9f38 fix(desktop): flush committed IME text on compositionend`），且公开渠道报告过桌面端 IME 在 xterm.js 终端不可用的未决 issue（#106487，待核实）——IME 风险为**高风险持续投入项**；设计预案：若输入回归页不达标，在输入层与 TerminalView 之间增加**输入法兼容性适配层**（拦截 composition 事件自行合成上屏）。**外部佐证（v3.4）**：社区与官方渠道持续报告 compositionend 阶段文字重复插入、候选窗错位等场景——**真机回归页即为此设**：回归不过，适配层即从预案转为正式交付项。**targetSdk 28 兼容性（v3.9 新增，定级：中风险·需验证，非生死线）**：Termux 官方 GitHub/F-Droid 版自身 targetSdk 即为 28（与本项目同因 W^X），故 `terminal-view` 模块本就是在 targetSdk 28 下开发验证的；审计所担心的 `OnBackInvokedCallback` 等仅在 targetSdk 33+ 强制、低 targetSdk 不触发，`WindowInsets` 类 API 亦多为可选兼容路径。因此风险低于"生死线"，但**必须验证**：锁定 `terminal-view` 版本号，在 targetSdk 28 下真机过一遍渲染 / IME / 快捷键条。**回退方向（若确不兼容）**：Canvas/Compose 原生渲染（可参考 ConnectBot 的终端实现思路），**严禁回退 WebView**——WebView 正是本次要消除的卡顿根源，回退等于回到坑里。

**生命周期铁律（整个保活架构的锚点）**：SessionService（前台服务）拥有 tmux server 与 proot 常驻实例，终端 UI 只是 attach/detach 的视图——**UI 死 ≠ 会话死**。任何模块设计不得违反此归属关系。

## 4. proot 编译参数清单

| 项 | 要求 |
|---|---|
| NDK | **r28+**（16KB 对齐场景建议；r27 也能加 linker flag，但 r28 起官方对 16KB 页支持才完善。编译 `libtermux.so` 等终端侧原生库时按 r28+ 走） |
| ABI | **用户只发 arm64 单包 APK**（直装场景 .aab 不可用；Gradle `splits.abi` 产出的是 APK 而非 .aab，或直接单 ABI 构建）；x86_64 仅 CI 模拟器自用不对外分发；砍掉 armeabi-v7a——32 位存量可忽略，新版 Debian 的 armhf 支持都在萎缩 |
| 链接 | 静态链接，`-O2`，编译后 `strip` |
| 16KB 对齐 | 链接参数加 `-Wl,-z,max-page-size=16384`（Android 15 设备必需；所有自编译 ELF 统一加，**含 proot 可执行文件本体**，不止 .so）。**第三方原生库同样受此约束（v3.9 补）**：终端渲染层引入的 **`libtermux.so`** 若取自 JitPack 等预编译版本，**必须先验证其是否已 16KB 对齐**（`readelf -l libtermux.so \| grep -A1 LOAD` 看 p_align ≥ 16384，或过 `check_elf_alignment.sh`），未对齐在 16KB 页设备上加载直接失败；自行编译则按 NDK r28+ 加上述参数。**别只对齐 proot 而漏掉 `libtermux.so`** |
| seccomp | 保留官方 seccomp 加速模式（大幅降低 ptrace 开销）；初始化失败自动降级 `PROOT_NO_SECCOMP=1`。**已知风险模式（v3.1 证实）**：上游 proot-me 在 Android 的 seccomp 过滤下，guest 进程以 signal 31（SYS_SECCOMP）退出（proot-me issue #327）；社区另有"Android 15 更严 seccomp 影响 proot"的个案报告（dev.to/XDA 排障实录，最终绕开 proot 改用 patchelf + LD_PRELOAD）。对策：**proot 构建必须包含 Android syscall 适配补丁**（Termux 自维护的 proot fork 已验证此路线——学其补丁思路，不抄其代码），并在 M1.0 于安卓 15 真机裁决（§11）。注：**bunproot（v3.2 改判）**：项目真实存在——GitHub `jjtseng93/bunproot`，"A port of PRoot to Bun"（用户态 chroot / mount --bind / binfmt_misc for Android，JavaScript 实现、2 star、2026-09 仍活跃）；**列为备选技术侦察对象，不作为依赖项**（个人项目成熟度远低于 Termux proot fork，主路线不变）。v3.1 曾误判"经查不存在"，原因与教训见文首 v3.2 变更记录。**机制澄清（v3.4）**：`PROOT_NO_SECCOMP=1` 关闭的是 **proot 自己安装的 seccomp 过滤器**——guest 收到 SIGSYS/"Bad system call" 这类"过滤类"故障由它解决；若根因是**设备内核策略直接拦截 guest 系统调用**（社区所称 Android 15 收紧的另一种形态），该开关无效，唯一出路是 M1.0 真机裁决，必要时转 patchelf + LD_PRELOAD 的 Plan B（§11）。两类故障必须分开诊断。**🚨 `PROOT_NO_SECCOMP=1` 禁令（本机实测，与社区常识相反）**：**本机实测该变量致命——设了反而跑不起来**。特别注意：网上绝大多数 proot 教程/项目遇到 seccomp 报错时的**默认解法恰恰就是设这个变量**。**本项目禁止设置**，Zcode 若查到社区建议请**以本条为准**，**严禁参考其他项目把它加回来**（这是本机 App 域 ptrace 限制下的特例，不是通用结论）。 |
| 运行时参数 | 参数清单（proot-distro 已用海量设备验证这组参数面——**读源码学机制、抄参数、不抄代码**，无授权问题）：`--link2symlink`（硬链接真凶是 SELinux 非 root 拒绝 + 跨设备，F2FS 本身支持）、`--kill-on-exit`、`PROOT_TMP_DIR` 指向私有目录（部分机型 /tmp 不可写）、默认 bind `/dev` `/proc` `/sys`、**启动前落 `/etc/resolv.conf`（公共 DNS）与 `/etc/hosts`**——proot 里没有 systemd-resolved 进程，不写则进环境后解析不了任何域名（新环境第一大故障，审计 P0） |
| 可执行文件 | targetSdk 28 下无 DCL 只读要求，正常解压执行即可（"下载文件须只读"是 targetSdk 34+ 的限制，本架构不涉及） |
| **分发方式**（v3.9 补齐，**以代码实现为准**） | **现行方案（已实现）**：proot 用 **Termux 官方发行二进制**（Termux proot 5.1.107.96 aarch64，含 `proot` / `loader` / `libtalloc.so` / `libandroid-shmem.so`），以 **assets 内置**（`app/src/main/assets/proot/`），首启时**释放到 `files/termux-proot/`** 并补执行位，随后 **execve 执行**；每个文件带 **SHA256 固定清单**校验（缺失/损坏自动重释放，修环境时跳过已就位项）。**聚合分发、未修改源码，PROVENANCE.md 已登记。** 为什么可行：targetSdk 28 下 App 的 `files/` 目录**允许 exec**（这正是 targetSdk 28 的意义所在）。<br>**历史方案（已废弃留档）**：早期曾把自编译 proot 改名为 `libproot.so` 放 `jniLibs/arm64-v8a/` 从 `nativeLibraryDir` 调用，现代码已不用（`ProotLauncher.kt` 标注"留档"）。原理备注（防误判）：`nativeLibraryDir` 可执行**不是因为 targetSdk 28**——它是系统为原生库准备的可执行目录，与 W^X 针对的"App 自写数据目录"是两码事。<br>**🚨 无论哪种方案，都只能 exec，禁止 `System.loadLibrary`/dlopen**（见 §3 PROVENANCE 说明） |

**Root 增强模式（可选后端：chroot）——设计保留，实现推迟到 v1.x**（审计 P1：第二后端 = 第二套 SELinux/ROM 测试面，v1 的刀砍在核心闭环上；§5 的 Root 一键防杀修复属 App 层轻量功能，不受此限，仍在 v1）
- 运行时引擎做成抽象层：**proot 为默认后端**（免 root）；检测到设备已 root（Magisk/KernelSU，`su` 可用）时，设置页出现"Root 增强"开关，用户可切换到 **chroot 后端**；
- chroot 收益：无 ptrace 系统调用拦截开销，`npm install` 这类海量小文件 IO 明显提速；硬链接、文件锁、特殊设备节点等 proot 的兼容性边角问题全部消失；
- 实现要点：同一 rootfs tarball 双后端通用（chroot 要求更宽松）；启用时 `mount --bind` 挂 `/dev` `/proc` `/sys` 后进 chroot，退出时按序 umount；通过 Magisk/KernelSU 的 su 域执行，SELinux 上下文默认可用；
- 约束：默认体验完全不变，无 root 用户看不到任何相关入口；RootFS 构建与快照重置流程与后端无关，不增加维护面。

## 5. 后台防杀：普通用户可完成的三层方案

**防杀矩阵（设计哲学：防杀提升存活概率，tmux 恢复提供确定性兜底，两道都不可少）**

| 系统的杀法 | 防御手段 | 结果 |
|---|---|---|
| 普通后台回收（LMK） | 前台服务 + 常驻通知 | 防得住 |
| Doze 休眠 / 应用待机限制 | 忽略电池优化白名单 | 防得住 |
| CPU 休眠导致任务挂起 | PARTIAL_WAKE_LOCK | 防得住 |
| 用户一键清理后台 | 多任务界面加锁（多数 ROM 尊重） | 大部分防住 |
| Phantom 子进程超限（安卓 12+） | 无线 ADB / Root 一键修复 | 修复后防住 |
| 内存枯竭强杀 / 用户强行停止 / 厂商深度清理 | 无解，任何 App 都防不住 | **tmux 兜底恢复现场** |
| 关机 / 重启 | 无解，tmux 同样丢失 | 预期管理：长任务在 UI 明示"重启会终止会话" |

**内存治理层（主动降低被"清道夫"盯上的概率，App 层 + 构建期）**：`onTrimMemory` 切后台释放图片/数据库等可重建资源（§3）+ R8 减少体积与内存占用 + guest RSS 软监控（M2 §6，超阈值通知警告并提供"释放内存"，**不做 `ulimit -v` 硬限**——虚拟地址空间限制会误杀 Node/V8 类 Agent，详见 M2 §6 设计说明）。

注意：常驻通知本身是双刃剑——用户嫌烦关掉通知权限会连带杀死前台服务，所以通知必须"有用"（显示会话状态、运行时长、一键回到终端），降低被关概率。另注意：**通知权限被拒后 FGS 照常运行但常驻通知不可见**，防杀体验打折，引导里需强调允许通知。WakeLock 策略：仅在会话活跃时持有，通知栏提供一键"暂停保持运行"开关释放锁；息屏长任务的耗电/发热代价在 UI 明示（建议插电）。

**第 1 层（自动，无需用户操作）**：前台服务 + 常驻通知 + `PARTIAL_WAKE_LOCK` + tmux 兜底（进程被杀后重进 App 自动 attach 恢复现场）。targetSdk 28 下 FGS 无需声明类型，且天然豁免 Android 15 针对 targetSdk 35+ 的 dataSync 型前台服务"24 小时累计 6 小时"强停限制。

**第 2 层（引导一次）**：电池优化白名单。直发 APK 不受 Play 政策限制，可以直接弹 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`；再附一页国产 ROM 图文引导（小米/澎湃、华为、OPPO、vivo 的自启动与后台锁设置）。引导分两步走（M2 §7）：
1. **系统白名单**：一键弹窗把证道设为「不优化」（`ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`）；
2. **国产 ROM 自启动/后台锁**：图文引导进入各 ROM 的「自启动 / 省电策略 / 后台高耗电」设置，能跳转的尽量一键跳转（小米「省电策略→无限制」、华为「手动管理」全开、OPPO「允许完全后台行为」、vivo「允许后台高耗电」）。

**第 3 层（v1.0 不实现）**：**v1.0 不实现无线 ADB 配对向导**，只保留以下两项：

1. **ROM 保活图文指南**（设置页常驻，**见 M2 §7**）——即上面第 2 层的国产 ROM 自启动 / 省电策略 / 后台锁图文引导；
2. **「终端手动执行」命令复制入口**——一键复制 `settings put global settings_enable_monitor_phantom_procs false`，供愿意自己动手的用户粘贴到终端执行。

完整方案（Phantom 修复向导、Root 一键捷径、Shizuku 捷径、无线 ADB 配对与 mDNS 发现）**详见 M4 留档**，v1.0 不实现。

> **为什么不实现**：自用场景不需要；无线 ADB 配对涉及多机型入口分叉、配对码/端口双字段、mDNS 发现失败兜底等大量适配工作，成本远超收益。安卓 11 及以下用户本来就完全看不到这一层。
> 该开关（`settings_enable_monitor_phantom_procs`）属社区实证而非官方公开承诺，若将来重启该功能，每个安卓大版本发布后仍需真机复查一次有效性。

**终端里的 adb（高级用户自助）**：rootfs 不预装 android-tools，但 Debian 13 官方源有 `adb` 包（`apt install adb`），想在终端里调试其他设备的用户一条命令自给自足，官方不提供支持。命令抽屉可放一条"安装 adb 工具"快捷项指向该命令。

## 6. 分发与更新（GitHub 直发）

**RootFS / 资源分发**

发行版选型（2026-10-03 用户定案，架构收窄）：**单一发行版 Debian 13.7（trixie）**。彻底移除多发行版支持——不做"切换发行版"的逻辑、UI 与构建管线，全项目只有一个环境。
- Debian 13.7：glibc 2.41 / Python 3.13 / OpenSSL 3.5 / 原生 GNU coreutils，无 snap 冗余，支持到 2030 年；更精简，作为唯一发行版没有备选负担；
- **依赖版本锁定（三条，均为定案）**：
  - **glibc：直接使用 Debian 13.7 自带的 2.41，不手动升级**。理由：预编译的 Agent 二进制以发行版 glibc 为基准，自带的才是被全仓库测试过的那个版本，手动升级反而制造不兼容；
  - **Node.js：不用 Debian 源里的旧版本**，RootFS 构建脚本必须走 **NodeSource 官方源安装 Node.js 26**（构建脚本内执行 `curl -fsSL https://deb.nodesource.com/setup_26.x | bash -` 后 `apt-get install -y nodejs`），满足 Hermes Agent 最新要求。锁定 major = 26；将来升级 = 改脚本版本号 + CI 断言 + 真机回归，三步走；
  - **Python：直接使用 Debian 13.7 自带的 3.13**，恰好落在 Hermes 要求的 3.11–3.13 区间，**无需任何额外安装或版本管理**。uv 保留但退化为纯 pip 安装器（`uv pip install --system`，见附录 A #6 #7），不再承担 Python 版本管理职责；
- 构建顺序关键点（RootFS 构建脚本，CI 内执行）：`apt-get update` → 基础包与依赖 → NodeSource 源脚本 → `apt-get install -y nodejs` → 清理 apt 缓存 → 裁掉 `/usr/share/doc` 与多余 locale → tar.zst 打包（zstd：解压快数倍、发热更小，App 端用 zstd-jni / commons-compress）；
- **CI 版本断言（构建即验收）**：构建脚本断言并记录 `ldd --version`（glibc 2.41.x，只读不自升）、`python3 --version`（3.13.x，系统自带）、`node --version`（v26.x，NodeSource）——任一漂移**构建失败**，把"核实"固化成每次构建的机械检查；
- **明确不追"最新"滚动发行版**（Arch / Fedora 最新版）：AI Agent 需要的是"足够新 + 足够稳"，不是最新。滚动版厂商 CI 不测试、库版本跳变（如 OpenSSL 大版本）会打断预编译二进制、环境随更新频繁变化导致"昨天好用今天坏了"，且支持周期短。甜点区 = 新一代 stable，本项目的答案就是 Debian 13.7；
- **Alpine 评估后不采用（v3.8 定案）**：Alpine 虽极轻量（~5MB），但用 `musl libc` 而非 glibc，与主流 AI Agent（Hermes/Claude Code 等）的预编译二进制不兼容，Python 生态大量 wheels 缺 musl 构建，会重现"缺依赖/装不上"的坑——与本项目"减少踩坑"初心相悖。**Debian 13.7 仍是唯一发行版。**
- **运行时参考：钉死 Termux `v0.119.0-beta.3` 的 proot 体验（v3.8 定案）**：用户长期实测 Termux `v0.119.0-beta.3` 中运行 Hermes Agent 稳定、问题少，因此**本项目的 proot 运行时以复现该版本的稳定体验为目标**——proot 采用与 Termux 0.119.0-beta.3 相同的 fork 与参数面（§4），保证运行 Agent 的行为与用户在 Termux 中一致。**Hermes Agent 本身保持官方安装方式**（install/upgrade 用官方 `curl | bash`，不注入版本环境变量），因为 Hermes 官方安装脚本自带版本管理，无需也不应干预。将来如需调整运行时 = 评估新版 Termux 的 proot 改进 + 真机回归，三步走。
- 每个 Release 挂两类资产：`debian-13.7-base-arm64.tar.zst`（下载约 100–150MB，解压 + 工具链装齐后落盘约 1.5–2GB）+ `manifest.json`（含 SHA256、大小、双通道 URL、**ed25519 签名**，见"安全闸"）。x86_64 变体仅 CI 模拟器自用，由同一脚本参数产出，不对外分发；
- **解压原子性**：解到 tmp 目录 → 写完成标记 → 原子 rename；启动时检测上次残局自动清理（解压 1.5–2GB 是分钟级操作，中途被杀会留几百 MB 半成品）；进度 UI 单设"解压中"阶段，避免用户以为卡死；
- RootFS 预装清单（构建期一次到位，源自三环境实测避坑清单，附录 A）：git、curl、ca-certificates、gnupg、**python3（Debian 自带 3.13，直接使用）**、**nodejs（NodeSource 官方源，Node.js 26）**、ripgrep、**libatomic1**、ffmpeg、**tmux**（§5 兜底机制的地基，漏装 = 保活归零，审计 P0）、**procps**（ps/top 排障）、**busybox**、**sqlite3**（命令行 + Python 内置 sqlite3 模块双端可用）、**uv（最新版，仅作 pip 安装器）**、确认 `C.UTF-8` locale 开箱可用（Debian 自带）；构建脚本同时修复容器通病：`mkdir -p /var/log/apt /var/log/dpkg`；`UV_LINK_MODE=copy` 写入 `/etc/profile.d/` 与 `/etc/environment` **全局生效**（解决"子进程丢环境变量"的实测坑，而非让用户自己 export）；
- **双通道按文件大小分道（审计 P0 修正）**：大文件（rootfs，>50MB）走 GitHub Releases 直链 + **Cloudflare R2/自定义域名**回源——jsDelivr 的 `/gh/` 通道不代理 Releases 资产且单文件 50MB 上限、GitHub git 单文件 100MB 硬限，此路对 rootfs 三重堵死；小文件（manifest.json、agent-helper，均远小于 50MB）可复用 jsDelivr。**manifest 本体同样双通道托管**。启动时各发一个 Range 探测请求，谁快用谁；下载中失败自动切换；
- 环境更新：v1 直接全量重下（150MB 成本可接受，增量补丁在手机端应用还吃内存）；bsdiff 增量包推迟到 v1.x。

**网络代理（极简策略：依赖系统 VPN，手动配置仅作兜底）**
原理：用户在 Google Play 下载的代理 App（Clash、v2rayNG 等）绝大多数工作在 **VpnService 模式**——建立系统级 VPN 隧道后，**全设备所有 App 的流量都走代理**，包括本 App 及其 rootfs 里的全部进程（proot 子进程与 App 同 UID、同路由表，天然被 VPN 覆盖）。刷 X、Gemini、ChatGPT 能通，Agent 就能通，**App 内无需任何内置代理功能**。
- 默认不开发代理配置界面，不内置任何代理服务；
- **App 网络栈保持默认系统路由（重要，提醒实现方）**：OkHttp / 下载器**不要设置 `Proxy.NO_PROXY`、不要自定义 `ProxySelector` 强制直连**——保持默认路由，流量才会进入用户代理 App 的 VpnService TUN；一旦设了 NO_PROXY，rootfs 与 App 的网络请求全部绕开代理，海外源（GitHub / claude.ai / npm）全部连不上，这是"证道不走科学上网"的唯一可能来源，必须杜绝。
- **软件源策略（构建期定案，提醒实现方）**：rootfs 的 `sources.list` 用 **Debian 官方源**，npm 用官方 registry（npmjs.org），pip 用官方 PyPI，**一律不预置国内镜像源**。理由：① 镜像源同步滞后，装的包版本可能与 CI 测试不一致，破坏可复现性（构建即验收）；② 用户开着代理时访问国内镜像反而绕路变慢；③ 保持官方源与 CI 构建完全一致，故障面最小。**不要在 rootfs 构建脚本里擅自替换为国内镜像**。
- 仅保留两个低成本兜底：
  1. **连接失败智能引导**：一键安装 / Agent 启动前检测 API 连通性，失败时弹出图文引导——"①确认代理 App 已开启且为 VPN 模式；②若代理 App 开了'分应用代理'，请把本 App 勾选进去（这是最常见的失败原因）；③仍不行，点这里直达手动代理设置"；**onboarding ② 下载失败页同样放置该直达入口**（国内用户首启时往往还没配好代理，是首启死锁的第一来源）；**更新检查 / manifest 拉取 / 组件更新失败时同样接入该引导**，不允许静默失败；
  2. **手动代理（折叠在设置→高级）**：host:port + 可选认证，填写后注入所有会话的 `http_proxy` / `https_proxy` / `all_proxy` 环境变量——只服务于"代理 App 仅监听本地端口、不开 VPN"的少数场景。
- 已知边界写进 FAQ：分应用代理未勾选本 App = 连接失败的第一大原因。

**App 自更新（直发渠道的最大红利）**
- 启动时调 `GET /repos/<org>/<repo>/releases/latest`，比对 versionCode；
- 有新版本 → 后台下载 APK → 弹"发现新版本，点我更新"→ 调起系统安装器；
- 需声明 `REQUEST_INSTALL_PACKAGES` 权限（直发渠道无政策障碍）。

**组件热更新（内嵌终端等组件免发版升级）**
APK 本体更新太慢，以下组件全部做成**版本化热更新组件**，走 manifest 的 `components` 字段下发：
- ~~`xterm-bundle`~~（v3.9 已移除）：原 xterm.js 渲染资源包随终端渲染层切换为 Termux TerminalView（原生代码）而**取消**——TerminalView 是 APK 内原生模块，渲染修复/新特性只能随 APK 发版，不再走热更新；
- `agent-helper`：RootFS 内置的 `agent` 辅助命令脚本——新增 Agent、官方安装方式变更即时生效；
- 规则：APK 内置一份"出厂版本"兜底（首次离线可用）；启动时比对版本号，有新即后台静默下载替换；下载失败或校验不过自动回退出厂版本，绝不让终端打不开；
- **安全闸（审计 P0：防供应链 RCE）**：manifest 里的 `install`/`upgrade` 是任意 shell 命令，manifest 被篡改 = 在用户环境内执行任意代码（还能摸到 home 里 Agent 的登录态），多镜像多通道恰恰放大了劫持面。对策：**manifest 与所有热更新组件必须 ed25519 签名，公钥固化进 APK**；验签不过 → 拒绝应用更新、回退出厂版本（复用现有回退机制，增量成本低）。第二道闸（可选）：安装命令仅允许指向域名白名单（claude.ai、官方 GitHub org 等）。**私钥管理（v3.4 细化：离线签发）**：ed25519 签名私钥在**完全离线的可信机器上生成**，以 0600 权限本地保存，**仅用于手动签署发布 manifest**——不入代码仓库、不进 APK、不进 CI（CI 只做构建与校验，不做签名）；APK 源码固化公钥并加构建期断言（确保公钥非占位符）；制定**密钥轮换预案**：新公钥随新版本 APK 下发、旧私钥作废，用户升级后完成信任迁移。签名工具链从简（自研 ed25519 或 cosign 均可，不强制）。
- 涉及 Kotlin/Java 原生逻辑的改动才需要 APK 发版（走上面的自更新），渲染层和脚本层永远热更新。
- 术语（便于对外沟通与检索资料）：这套机制业界称 **"OTA 热更新 + 自动回滚（OTA update with auto-rollback）"**——热更新部分参照 CodePush / Expo EAS Update 模式；出厂版兜底即 **Golden Image** 思想；§8 的 RootFS 快照重置属 **快照回滚（Snapshot Rollback）**，与 Android A/B 无缝更新、嵌入式 Linux 的 Mender / RAUC 同源。
- **版本护栏**：每个组件必须声明 `min_app_version`（支持该组件的最低 APK versionCode），App 比对后不兼容则跳过该组件更新、继续用当前版本——防止"新组件下发到老 APK 上当场崩溃"这一热更新体系最常见事故。
- 维护预期：APK 低频发版（约每年 2–4 次，跟随安卓大版本适配与原生功能）；manifest / 组件 / RootFS 随时热更新，无需发版。

**manifest.json 示例（服务器端，新增 Agent 免发版；整份 manifest 由 ed25519 私钥签名——签名以旁挂 `manifest.json.sig` 下发或写入 `signature` 字段，APK 内置公钥验签，不过即整份拒绝应用）**

```json
{
  "version": 5,
  "rootfs": {
    "distro": "debian-13.7",
    "abi": "arm64",
    "url_github": "https://github.com/<org>/<repo>/releases/download/env-v5/debian-13.7-base-arm64.tar.zst",
    "url_cdn": "https://dl.<your-domain>/env-v5/debian-13.7-base-arm64.tar.zst",
    "sha256": "<CI 构建时写入的 64 位十六进制 SHA256>",
    "size": 121634816
  },
  "components": {
    "agent-helper": { "version": "8", "min_app_version": 1, "url_github": "https://github.com/<org>/<repo>/releases/download/comp-v5/agent-helper-8.zip", "url_cdn": "https://dl.<your-domain>/comp-v5/agent-helper-8.zip", "sha256": "<CI 构建时写入>" }
  },
  "agents": [
    {
      "id": "claude-code",
      "name": "Claude Code",
      "desc": "Anthropic 官方 AI 编程助手",
      "install": "curl -fsSL https://claude.ai/install.sh | bash",
      "upgrade": "claude update || curl -fsSL https://claude.ai/install.sh | bash",
      "uninstall": "rm -f ~/.local/bin/claude && rm -rf ~/.local/share/claude",
      "launch": "claude",
      "check": "command -v claude",
      "size_hint_mb": 150,
      "tested_on": ["debian-13.7"]
    },
    {
      "id": "hermes",
      "name": "Hermes Agent",
      "desc": "多技能 AI 助手",
      "install": "curl -fsSL https://<hermes 官方安装地址>/install.sh | bash",
      "upgrade": "curl -fsSL https://<hermes 官方安装地址>/install.sh | bash",
      "uninstall": "rm -f ~/.local/bin/hermes",
      "launch": "hermes",
      "launch_fallback": "python3 -m hermes_cli.main --tui",
      "check": "command -v hermes",
      "size_hint_mb": 300,
      "tested_on": ["debian-13.7"]
    }
  ]
}
```

安装命令统一在 tmux 会话里执行，输出实时渲染到终端界面（用户看到的就是进度条 + 滚动日志，失败时弹"重试"按钮，日志可一键复制用于反馈）。

## 7. 自由终端（手动模式）

**定位**：给"想按官方教程自己装"的用户一个完整 Linux 终端。架构上是零成本的副产品——proot 环境里起一个 bash 会话即可，复用同一套终端渲染、tmux 兜底和前台保活。

**入口**：主界面底部第二个标签「终端」（与「Agent」并列），文案只写"终端"，不吓退普通用户；首次进入显示一行提示："这里是 Linux 命令行，适合按照官方教程手动安装。不确定怎么用？回到 Agent 页用一键安装。"

**单会话制（v3.6 定案：不多开、不切换）**：自由终端全局**只有一个会话**（tmux 会话 `free`），无论从哪个入口进入都是 attach 到这同一个会话——**不做多标签页、不做多终端并行、不做会话切换器**。理由：每个终端会话都是一棵独立的 proot 进程树 + 一份 tmux 开销，手机上挂两个以上纯属浪费内存、徒增卡顿与被杀概率；手机屏幕也没有在多个 Agent 会话间切换的真实需求（桌面/平板场景以后再说）。Agent 安装与启动复用这同一个会话：点"启动"某个 Agent = 终端界面 attach 到 `free` 会话并运行该 Agent，界面始终只有一个。真正需要并行的场景由 tmux 自带的 window/pane 分屏满足（高级用户自己在会话里 `Ctrl+B` 分屏即可，不占用额外界面）。

**为手机和非程序员做的六件事**：
1. **快捷键条**：软键盘上方常驻一行——`ESC` `CTRL` `SHIFT` `TAB` `↑` `↓` `PGUP` `PGDN` `/` `-` `|`（手机输入法打不出这些键，没有这条终端等于不可用）。`CTRL`/`SHIFT` 为**粘滞键**：先点修饰键（高亮）再点字母/Tab，再点一次取消——`SHIFT+TAB` 是 Claude Code 模式切换的高频操作、`PGUP/PGDN` 服务长输出翻屏，缺了体验残废；粘滞交互是真机重点调校项（修饰键实现最容易做错）。
2. **绕开国产 ROM 的"安全键盘"**（小米/华为/OPPO/vivo 会对密码类输入框强制弹出安全键盘：无 ESC/CTRL、布局错乱、部分禁止粘贴，终端直接不可用）：
   - 终端输入与全 App 所有输入框**一律不用密码类型**（不设 `TYPE_TEXT_VARIATION_PASSWORD`），从源头杜绝安全键盘被唤起；
   - 终端输入用"无联想普通文本"（`TYPE_TEXT_FLAG_NO_SUGGESTIONS` + 关闭自动大写/自动纠错）；TerminalView 基于原生 EditText，同样设置 `inputType` 为普通文本、关闭自动建议/自动大写/自动纠错；
   - 未来如需输入 Token/密钥，用普通文本框 + App 内自绘遮蔽（圆点遮罩），也不要用系统密码框。
3. **一键粘贴**：剪贴板里检测到 `curl`/`npm`/`bash` 开头的命令时，弹出"粘贴官方安装命令？"气泡，点一下自动贴入。用户从浏览器/公众号复制教程命令过来就能跑。
4. **滑动滚屏 + 双指缩放字号**，长按选中复制。
5. **搞坏了有兜底**：自由终端里把环境折腾坏了，走和一键安装完全相同的「修复环境」快照重置（§8），无额外维护成本。
6. **平板与外接键盘适配**：横屏/平板下终端全宽显示、快捷键条自动贴边；检测到外接物理键盘时隐藏快捷键条腾出屏幕，ESC/CTRL/TAB 由硬件按键直通。

**终端里的"下载/升级 Agent"双便利设计**（手动模式也不用翻教程查命令）：
- **命令抽屉（点一下执行）**：终端界面右上角一个抽屉按钮，展开后按 Agent 分组列出 manifest 里的官方命令——「安装 Claude Code」「升级 Claude Code」「卸载 Claude Code」……点哪条就把哪条注入当前终端执行，输出照常滚屏。命令与 manifest 实时同步，官方安装方式变了只需改服务器文件。
- **`agent` 辅助命令（给喜欢敲命令的人）**：RootFS 内置一个小 bash 脚本 `agent`，由 App 随 manifest 下发更新：
  - `agent list` —— 列出所有支持的 Agent 及本机安装状态
  - `agent install claude-code` / `agent upgrade claude-code` / `agent remove claude-code` —— 等价执行 manifest 里的对应命令，带断点续传和友好输出
  - 用户在终端里永远只需要记 `agent` 这一个命令。
- **`agent-helper` 内置工程规范（逐条对应实测坑，附录 A）**：
  1. **不直接 `curl | bash`**：安装脚本先 `curl --fail --retry 3 -C -` 下载到临时文件再执行——断点续传、管道断裂可见、脚本报错不失真（实测坑 #10）；
  2. **系统 Python 直接使用**：单一发行版 Debian 13.7 自带 Python 3.13，天然兼容 Hermes，不做任何版本切换与环境注入（实测坑 #5 因发行版收窄从根上消除，helper 无需版本管理逻辑）；
  3. **Python 包一律走 `uv pip install --system`**：天然无视 PEP 668 限制、不触碰 apt 系统包，同时消灭 `externally-managed-environment` 和 `Cannot uninstall RECORD file not found` 两类报错（实测坑 #6 #7）；
  4. **快捷命令兜底**：安装后执行 `check`，失败时自动按 `launch_fallback` 生成 `/usr/local/bin/<name>` 包装脚本（`exec python3 -m <模块>`），保证"装完必有命令可敲"（实测坑 #8）；
  5. **安装路径登记**：安装过程记录真实源码路径到本地状态文件，`agent list` 可见，不用全局 `find`（实测坑 #9）。

**手动安装 → 也能一键启动**：主界面提供「添加自定义 Agent」——填一个名字 + 启动命令（如 `claude`），App 自动检测该命令是否已存在（`command -v`），存在才允许保存。保存后与官方预设卡片完全同等待遇：一键启动、tmux 保活。用户从官方渠道手动装的 Agent，体验不输一键安装。

**官方脚本手动跑 vs `agent install`（两条路，殊途同归）**：
- **自由终端里手动跑官方脚本**（`curl | bash` 原汁原味）：完全支持、不加任何拦截——这正是一键粘贴和命令抽屉服务的场景。且附录 A 的底层坑（缺 busybox/libatomic1、硬链接、PEP 668 的依赖环境）已在 rootfs 构建期填平，官方脚本在本环境的成功率远高于 Wolfi/Operit/太墟；
- **`agent install xxx`（带保险版）**：同样的官方命令，但外包四层保险（下载再执行、uv 安装、命令兜底、路径登记）——适合不想操心的时刻；
- 手动跑脚本遇到疑难杂症时，随时可以改用 `agent install` 重装同一个 Agent，两者不冲突；装完都可登记为自定义卡片。

**API Key 管理（App 层托管，不落 rootfs 明文）**：设置页提供"API Key 管理"——按服务商（Anthropic / DeepSeek / OpenAI 等）填写后，密钥存入 **Android Keystore 加密**的私有存储，启动 Agent 会话时注入为对应环境变量（`ANTHROPIC_API_KEY` 等）。收益：①密钥不以明文落进 rootfs 文件；②修复环境、重装 Agent 后密钥都在（与 §8 home 分离形成双保险——Agent 自己存的登录态靠 home，App 托管的 key 靠 Keystore）；③自由终端会话同样注入，手动运行的 Agent 也能用。用户在 TUI 里自己 `export` 或走 Agent 官方登录流程不受限制。设置页提供"**清除全部密钥**"一键操作（二次确认，明示"清除后需重新填写"），覆盖换服务商/换设备场景（外部审计建议采纳）。

## 8. 冲突与重置（快照恢复）

**关键设计：home 与系统分离（重置不丢登录态）**
- rootfs 解压时把用户 home 目录（`/root`）放到 **base 之外的独立私有目录**，以 bind mount 方式挂入——系统层和"用户的配置与数据"物理隔离；
- 效果：①「修复环境」只重解压系统层，**Agent 的登录态、API Key、历史配置（~/.claude、~/.config 等）全部保留**，用户修完直接用，不用重新登录；②自由终端里误删系统文件不殃及个人数据；
- 例外处理：若用户 home 本身损坏（极少见），设置页提供二级选项"同时清空个人配置"（默认不勾，二次确认）。

- 安装 Agent 前，对 pristine base 解压目录做一次硬保留（原始 **tar.zst** 本地缓存 + 解压完成标记；与 §6 的 .tar.zst 统一，不保留第二种格式）；
- 用户点"修复环境"→ 停止会话 → 重解压 base（不动 home）→ 询问"是否顺带重装已选的 Agent"（默认是，自动按 manifest 重跑安装）；
- 全程约 30 秒 + Agent 重装时间，**不重下 RootFS、不丢登录态**。

## 9. 发布 Checklist

- [ ] 所有 .so 16KB 对齐（`check_elf_alignment.sh` 过一遍）
- [ ] 出包形态：arm64 单 APK ≤ 20MB（Gradle `splits.abi` 出 APK；**不用 .aab**——只能经 Play 分发，与直发自相矛盾；x86_64 仅 CI 模拟器自用）
- [ ] **targetSdk 28 钉死**（架构前提，见文首警告框）+ `REQUEST_INSTALL_PACKAGES` 声明；确认无 FGS 类型声明需求
- [ ] TerminalView 中文 IME 组合输入真机验证（主流中文输入法在终端内输入/删改正常）
- [ ] API Key 管理：Android Keystore 加密存储 + 会话环境变量注入验证
- [ ] Debian 13.7 单发行版 RootFS 构建管线（arm64 单变体；Node.js 26 经 NodeSource 官方源安装，不用 Debian 源旧版；预装清单见 §6：busybox、libatomic1、uv（纯 pip 安装器）、`/var/log/apt|dpkg` 目录、全局 `UV_LINK_MODE=copy`——附录 A 构建期对策）；CI 断言 glibc 2.41 / Python 3.13 / Node 26
- [ ] RootFS 内置 `agent` 辅助命令，随 manifest 版本同步下发
- [ ] TerminalView 渲染回归：APK 内置出厂版随版本号验证，异常回退机制真机验证（TerminalView 为原生代码，不设热更新通道）
- [ ] Release 资产命名规范固定（自更新与 manifest 解析依赖它）
- [ ] 全 App 输入框排查：无任何密码类型字段，小米/华为/OPPO/vivo 各一台真机验证不弹安全键盘
- [ ] Root 路径回归：Magisk / KernelSU 设备各一台，验证 chroot 后端、Root 一键防杀修复、后端切换后 Agent 数据完好
- [ ] home 分离验证：修复环境后 Agent 登录态与配置完好
- [ ] 连接失败引导验证：断网/关代理/分应用代理未勾选三种场景下，引导文案与实测一致；手动代理注入（高级设置）三路生效
- [ ] 平板横屏 + 外接键盘真机验证（快捷键条自动隐藏）
- [ ] GitHub 仓库挂极简隐私政策页（措辞用"**不上传用户数据；网络访问仅用于下载资源与检查更新**"——manifest 拉取必然留服务器访问日志，不用"不收集任何数据"的绝对化说法；日志明示**留存期限（如 7 天）与用途（仅排障，不作用户行为分析）**）+ App 内"问题反馈"入口（跳 GitHub Issues）
- [ ] manifest 验签演练：篡改 manifest 内容 / 换签名两种场景，App 必须拒绝应用并回退出厂版（安全闸）
- [ ] DNS 引导验证：首次进入终端 `curl -I` 任意域名解析正常（`/etc/resolv.conf` 落盘生效；不写则"下载得动、上不了网"）
- [ ] 解压中断恢复：解压中途杀进程 → 下次启动残局自动清理并重解压成功
- [ ] 通知权限：首启第①步创建渠道即触发系统弹窗；拒绝权限后记录 FGS 存活、常驻通知不可见的降级表现
- [ ] 无代理首启：飞行模式/无代理场景下，onboarding 下载失败页可直达手动代理设置（死锁解除）
- [ ] TerminalView 版本兼容：Termux `terminal-view` 模块版本与 targetSdk 28 兼容性验证，异常时回退备用渲染路径可用
- [ ] CJK/ASCII 混排等宽：Claude Code TUI 混排界面真机目测不错位
- [ ] CI 版本断言生效：人为制造 Node/Python 版本漂移，构建必须失败
- [ ] 真机回归：安卓 10 / 12 / 14 / 15 各一台，重点测后台 30 分钟存活、被杀后 attach 恢复、Phantom 开关在 12/14/15 上的有效性
- [ ] R8 开启后完整回归：混淆产物在真机跑通全部验收项；JNI 桥/反射路径未被误删（必要时 `-keep`）
- [ ] `onTrimMemory` 验证：切后台内存回落、FGS/会话不受影响（只释放可重建资源）
- [ ] 内存治理验证：guest RSS 超阈值有通知警告，「释放内存」后 RSS 下降且 agent 会话不中断

---

## 10. 附：跨平台边界说明（回应"跨平台"诉求的明确定义）

"跨平台"在本项目中的准确含义，分两层钉死：

- **iOS 本地执行 = 不可能**。iOS 禁止执行下载的二进制、无前台服务机制、不给 ptrace，本方案的整个技术栈（proot/chroot + rootfs）在 iOS 上无一可用。任何声称"iOS 版同功能"的承诺都是误导。
- **真正的跨平台路线（v2 候选）**：复用本方案的终端渲染层，做 **SSH/Mosh 瘦客户端连云机**——云厂商开一台 Debian/Ubuntu 小机，iOS/安卓/桌面浏览器共用同一套终端界面连上去。v1（安卓本地执行）的终端组件选型（Termux TerminalView 为原生终端，界面层同样可抽离）为这条路预留了 UI 复用性——不过 TerminalView 是安卓原生视图，跨平台复用时需要按平台重写终端 UI 层，此红利弱于原 WebView 路线。
- 分期结论：**v1 只做安卓本地执行**（本文档全部内容）；v2 是否做云机瘦客户端，视 v1 用户反馈再议，现在不投入任何开发量。

---

## 11. 里程碑分期（范围收敛的落点）

| 里程碑 | 内容 | 门禁/备注 |
|---|---|---|
| **M1 最小闭环（✅ 2026-10-04 真机验收通过）** | **M1.0 安卓 15 真机 proot 可用性验证**——**进展 2026-10-03：技术路线已由用户在安卓 16 真机长期实证（同架构免 root proot 路线日常运行 Hermes Agent）** → **M1.1** rootfs 下载/解压（含原子性）+ 终端桥 + 单会话 bash——**进展 2026-10-03：自研 JNI 伪终端（Pty.kt，切片 2）已跑通系统 shell 并本地编译通过**；终端渲染层于 v3.9 由 xterm.js/WebView 切换为 Termux TerminalView，M1.1 后续调试以此为准 → **M1.2** 旗舰 Agent 装跑通 | **验收记录（2026-10-04，Honor 安卓 16 真机）**：Debian 13.7 bash 交互式存活 ✓、`uname -a`=aarch64 GNU/Linux ✓、Python 3.13.5 ✓、Node v26.10.0 ✓、中文输入法无安全键盘 ✓。**关键实测结论**：自编译上游 proot 在 App 域内加载 guest 静默退出 255（run-as 域正常）——App 域与 shell 域的 ptrace 子进程内存写入限制差异所致，切换为 Termux proot fork（GPL，聚合分发，PROVENANCE 登记）后完全正常；PROOT_NO_SECCOMP=1 在本机反而致命，禁止设置 |
| **M2 保活** | FGS + 常驻通知 + WakeLock 策略 + tmux 兜底 + 防杀三层 | 安卓 10/12/14/15 后台存活实测 |
| **M3 一键安装** | manifest（含 ed25519 验签）+ Agent 卡片 + 快照重置 | 验签演练过 Checklist |
| ~~**M4 防杀向导**~~ **⛔ 已砍掉（2026-10-05 用户拍板，v1.0 不实现）** | ~~无线 ADB 配对向导（工作量最大的一块）+ Root 一键修复~~ | **不开发**。替代 = 设置页 ROM 保活图文指南（电池白名单两步引导 + 自启动/后台锁路径图文，见 M2 §7）+ 终端手动执行命令复制入口。原因：自用场景不需要，无线 ADB 配对的多机型适配成本远超收益。骨架文档留档，头部已加醒目状态标注 |
| **M5 更新体系** | App 自更新 + 组件热更新 + Debian 13.7 RootFS 构建管线 | 回退机制真机验证 |
| **v1.x 明确推迟** | chroot 后端、bsdiff 增量、x86_64 分发、云机瘦客户端（§10） | v1 的刀砍在核心闭环上 |

*当前进度：**M1 最小闭环已完成并通过真机验收（2026-10-04）**。用户已进入后续里程碑开发，使用 zcode（外部 AI 编码助手）通过 ADB 连接安卓真机进行迭代调试。下一步焦点：M2 保活（FGS + 常驻通知 + WakeLock + tmux 兜底 + 防杀三层）→ M3 一键安装（manifest ed25519 验签 + Agent 卡片 + 快照重置）。*

> 📁 **验收记录位置（v3.9 补）**：M1 的验收记录**内联在上表 M1 行的「门禁/备注」列**（含 2026-10-04 Honor 安卓 16 真机的实测结论：bash 交互存活 / `uname -a` / Python 3.13.5 / Node v26.10.0 / 中文输入法无安全键盘，以及 proot 255 退出与 `PROOT_NO_SECCOMP=1` 致命这两条关键实测结论）。项目当前**尚未建立 `docs/acceptance/` 独立目录**；后续里程碑验收建议统一落到 `docs/acceptance/M<N>-<日期>.md`（真机日志 + 截图），M2 起执行。

---

## 附录 A：安卓端运行 AI Agent 避坑清单（三环境实测 × 11 条）

> 来源：Wolfi Terminal（Debian）、Operit（Ubuntu）、太墟（Ubuntu PRoot）三个环境安装 Hermes Agent 的实测踩坑。每一条都已转化为内置对策，按解决时点分三类：**构建期**（做进 rootfs）、**helper 期**（做进 agent 辅助命令）、**App 期**（做进应用层）。

| # | 坑（实测现象） | 根因 | 内置对策 | 解决时点 |
|---|---|---|---|---|
| 1 | `busybox: not found`，基础命令失效 | 容器极度精简 | 构建清单预装 busybox + coreutils（Debian 13.7 原生自带 GNU coreutils） | 构建期 |
| 2 | `Directory '/var/log/apt/' missing` | 容器缺日志目录 | 构建脚本 `mkdir -p /var/log/apt /var/log/dpkg` | 构建期 |
| 3 | `libatomic.so.1: cannot open shared object file`（装 Node 时） | 缺系统动态库 | 构建清单预装 libatomic1 | 构建期 |
| 4 | `failed to hardlink file... Operation not permitted`（uv/pip 装依赖）；且脚本子进程会丢失手动 export 的变量 | SELinux 限制非 root 硬链接 + 跨设备 | `UV_LINK_MODE=copy` 写入 `/etc/profile.d/` + `/etc/environment` 全局生效 + proot `--link2symlink`；rootfs 内置最新 uv（≥0.12.22，支持全局 config） | 构建期 |
| 5 | 官方脚本默认装 Python 3.14，但 Hermes 要求 `<3.14`；脚本不认 `--python` 参数 | 版本窗口错配 | **根因消除（v3.3 发行版收窄后）**：单一发行版 Debian 13.7 自带 Python 3.13，天然落在 Hermes 兼容区间；系统 Python 直接使用，无需 `uv python` 与 manifest 版本管理（helper 规范 #2 相应简化） | 构建期 |
| 6 | `error: externally-managed-environment`（PEP 668 禁止 pip 全局装） | Debian 新策略 | helper 一律 `uv pip install --system`，天然不受 PEP 668 约束 | helper 期 |
| 7 | `Cannot uninstall cryptography... RECORD file not found` | pip 试图升级 apt 系统包 | 同上，`uv pip install --system` 不触碰 apt 包体系 | helper 期 |
| 8 | `pip install -e .` 成功但 `hermes: command not found` | PRoot 下 pip 脚本生成机制受限 | 安装后 `check` 失败 → 自动按 `launch_fallback` 生成 `/usr/local/bin/` 包装脚本（`exec python3 -m <模块> "$@"`） | helper 期 |
| 9 | 源码被克隆到 `/root/.hermes/hermes-agent` 等隐藏目录，找不到 | 官方脚本路径不规范 | helper 登记真实安装路径到本地状态文件，`agent list` 可见 | helper 期 |
| 10 | `curl: (23) Failure writing output to destination` | 脚本中途报错导致管道断裂 | 不直接 `curl \| bash`：先 `--fail --retry 3 -C -` 下载到临时文件再执行 | helper 期 |
| 11 | 息屏/切后台几分钟后 TUI 被杀 | OOM / Doze / Phantom | 防杀三层方案 + tmux 兜底恢复（§5 完整矩阵） | App 期 |

**复用约定**：后续每个新 Agent 上架 manifest 前，必须在唯一在维护变体 debian-13.7 上实测一遍本清单的 #4–#8 五项（纯 helper 逻辑项），通过才可发布。**该约定 CI 化（外部审计建议采纳）**：manifest 的 agent 条目已带 `tested_on` 字段记录通过实测的发行版变体，发布流水线校验 `tested_on` 覆盖 `debian-13.7`，缺项直接拒绝发布——把人工约定变成机器强制。
