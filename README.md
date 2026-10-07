# 证道（Zhengdao）

安卓上的轻量级 AI Agent 运行环境——免 root、零命令、一键安装你自己的 Agent。

让普通用户在 Android 手机上拥有一套真实的 Linux 环境（Debian 13.7），并在其中一键运行 Claude Code、Hermes Agent、OpenCode 等官方 CLI Agent。

> **Agent 支持状态（2026-10-06，荣耀 Magic 5 Pro 真机验收）**：**OpenCode 内置**（太极 Tab 直达对话界面，bionic 宿主版零 PRoot 开销，按需下载约 65MB）；**Hermes Agent 完整支持**（一键安装 → 启动 → TUI，`hermes update` 可用）；Claude Code 与 AGY CLI（Antigravity）在终端自由安装（走各自官方脚本）。

> **界面结构**：底部三 Tab——**太极**（OpenCode 对话）/ **洞天**（终端）/ **丹房**（Agent 管理）；设置从右上角齿轮进入。
> **文件边界**：Agent 产出统一放工作区（默认手机 `Download/证道`，可在设置页更换），手机文件管理器直接可见、卸载证道后仍保留。

## 系统要求

- **Android 16（API 36）或更高版本**
- **arm64-v8a** 架构设备（绝大多数现代安卓手机和平板）
- 已在 **荣耀 Magic 5 Pro（MagicOS 11 / Android 16）** 上完成实测

> **⚠️ 关于其他 Android 版本**
> 本项目目前**仅在 Android 16 上完成真机实测**，其他版本（Android 15 及以下）**未做适配和验证**，可能存在兼容性问题。
> 如果你在非 Android 16 设备上尝试安装，请自行评估风险并测试，**不保证能正常运行**。
> 由于是个人业余项目，精力实在有限，无法对所有 Android 版本提供适配支持，**请多多包涵**。

> **🔒 关于 targetSdk 28（长期说明）**
> 证道**刻意钉死 targetSdk 28**，这是架构级约束，不是版本落后：
> - **为什么**：Android 10 起系统禁止 targetSdk ≥ 29 的应用从「自己的可写数据目录」执行文件（W^X）。证道的整套 Linux 环境（rootfs 里成千上万个程序）恰恰放在这个目录里，只有 targetSdk 28 能豁免，proot 才能正常运行。**抬高 targetSdk = 整个 Linux 环境变成无法执行的死文件。** Termux（GitHub/F-Droid 版）、太墟均采用同一策略。
> - **代价**：Android 16 上，targetSdk 28 应用读写 `/sdcard` 共享存储**需要 `READ`/`WRITE` 运行时授权**（且 Android 13+ 须摘掉 manifest 的 `READ` 帽子才能持有 `READ`——详见 `d414dca`）。经 App 真身实测，`/sdcard` 直连**读写均可用**（文件管理器可见）；**SAF 镜像同步仅作个别 ROM 的备用兜底**，非主路径。你若选了手机文件夹，证道把它镜像到 Linux 环境内的 `~/mnt/phone/` 作为兜底。
> - **长期风险**：若 Android 未来上调最低安装 targetSdk 门槛，或收紧 untrusted_app 域策略，证道需要迁移到 `system_linker_exec` 灰区或云机瘦客户端形态。该风险每季度在真机核对一次，详见 [`docs/milestones/已知限制.md`](docs/milestones/已知限制.md)。

## 网络要求

证道采用「本地环境 + 云端 Agent」的分层设计，网络需求分三个阶段：

| 阶段 | 是否需要网络 | 说明 |
|---|---|---|
| **首次安装环境** | **必需** | 下载约 300MB 的 Debian RootFS 压缩包 |
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

## 当前状态

**v1.1.1**（2026-10-07；v1.1 太极 UI 完整化 + 洞天体验打磨与清债，`versionCode 13`）：

- **太极 Tab**：OpenCode 原生 Compose 客户端——Markdown 渲染、思考折叠、工具卡、
  会话历史/新建/恢复、模型池选择器（免费模型标注、会话级切换、重启保持）
- **洞天（终端）**：Debian 13.7 真实环境（PRoot），tmux 会话保持；分屏/关屏一键化，
  首次进入有三点功能说明，「更多」菜单含清屏 / 重载字号
- **丹房**：Agent 一键安装/启动/卸载（二次确认、真实 du 尺寸、用户数据保留）、环境体检自愈
- **插件管理**（设置 → 插件）：管理**太极**内置 OpenCode 的插件（启停 / 缓存清理）
- **OpenCode 与 Hermes Agent 完整支持**（真机全链路验收）；Claude Code / AGY CLI 走各自官方脚本
- 存储策略定稿：MANAGE 主路径 + /sdcard 直连；通知 4 渠道；缓存清理白名单

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
| **③ 合规底线** | [`PROVENANCE.md`](PROVENANCE.md) | 第三方组件的来源、许可证与不可踩的红线（例如 proot 只能 exec、绝不能 `loadLibrary`）。 |

> 一句话记忆：**总设计定方向，分期规范定标准，PROVENANCE 定底线。**

## 独立开发声明

本项目全部第一方代码为从零独立编写，未参考任何第三方同类应用的代码。
允许参考的官方资料清单与禁止事项见 [PROVENANCE.md](PROVENANCE.md)。

## 致谢

这个项目是我和三个 AI 一起做出来的，各司其职：

**DeepSeek**（对话最多的伙伴）——日常讨论、方案碰撞、排障思路都先和它聊。
很多关键判断是和它一来一回磨出来的，再转给执行者落地。

**WorkBuddy**（计划制定者）——M1 到 M5 的全部里程碑规划与骨架文档出自它手：
每一期做什么、验收标准是什么，边界划得清清楚楚，执行才不会跑偏。

**Zcode**（智谱 AI 的编程助手，GLM-5.3-Flash 驱动）——全程的开发主力。
从 PRoot 在 Android 上的加载难题、SELinux 拦截、255 错误，到 Termux fork 基线的切换、
RootFS 构建管线、UI 落地，再到困了项目好几天的 Hermes uv 硬链接死局（最终靠包装
hermes 自带的 pinned uv 二进制破局）——整个"证道"从一行命令到一个完整 App，
是它一行行写出来的。

我负责提需求、踩坑，和在他们仨之间传话。

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

本项目第一方代码以 **GPL-3.0** 发布（作者选择的许可证）。集成的 Termux
terminal-emulator / terminal-view（v0.119.0-beta.3）为 **Apache-2.0**（此前误标为 GPL，
勘误详见 PROVENANCE.md）；内置 proot 组件基于上游 GPL 项目（proot-me/proot）编译，
作为独立可执行文件聚合分发，来源与许可义务见 [THIRD-PARTY-LICENSES.md](THIRD-PARTY-LICENSES.md)。
