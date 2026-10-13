# 独立开发声明（PROVENANCE）

本项目（zhengdao / 安卓轻量级 AI Agent App）为**从零独立开发**。
本文件是整个仓库的原创性规则，所有协作者（包括 AI）必须遵守。

## 第一方代码的版权与许可（2026-10-11）

- **版权归属**：本仓库**第一方代码**（`app/` 中本项目编写的部分、`tools/`、`rootfs/` 下的本项目
  脚本、`.github/`、`docs/` 及仓库自身配置与文档）的**版权归项目所有者所有**。
- **许可**：自 **2026-10-11** 起为 **「保留所有权利」**（正文见仓库根 `LICENSE`）——
  允许浏览、克隆与本地评估（含为评估自行构建运行）；**再分发，以及任何商业使用**
  （收费或免费提供、SaaS / 托管、捆绑 / OEM / 贴牌、企业内部规模化部署），
  **须事先取得项目所有者的书面许可**（联系：putaopanpan@gmail.com）。
- **历史版本不受影响**：**≤ v2.0.15** 的已发布版本仍按各自发布时的 **GPL-3.0** 分发 ——
  GPL 授权一经发布不可撤回。那份 GPL-3.0 正文自 2026-10-11 起存放在
  `licenses/GPL-3.0.txt`（由仓库根 `LICENSE` 移入，**逐字节未改**，SHA256 见 `licenses/README.md`）。
- **换证的依据**：仓库历史（710 个提交）中**不存在外部人类贡献者**，第一方版权完整 ——
  取证见 `docs/闭源切换清单-2026-10-11.md` 第七节。此后任何外部贡献须**先签** `CLA.md`
  （授予版权与再许可权），流程见 `CONTRIBUTING.md`。
- **第三方义务不因换证改变**：下表各第三方组件的许可证与随附义务维持原样，尤其两点红线 ——
  ① `com/termux/**` 的 Apache-2.0 衍生文件继续按 Apache-2.0 分发并保留修改通知；
  ② proot 只能以**独立进程 `exec`** 调用（聚合分发，不传染），绝不可 `dlopen` / `loadLibrary`。

## 允许参考（官方一手资料）

- Android 官方开发者文档（developer.android.com / developer.android.google.cn）
- proot 上游官方仓库与官方文档（proot-me/proot）及其官方运行参数
- Termux `terminal-emulator` / `terminal-view` 库的官方文档与源码（**Apache-2.0**，
  见上游 termux-app 仓库 LICENSE.md 的 Exceptions 一节；v3.9 起聚合为本项目的
  终端渲染层，**允许按 Apache-2.0 条款使用**，须保留其许可证与版权声明；**2026-10-11 起许可证正文
  随仓库分发**（`licenses/Apache-2.0.txt`，履行第 4(a) 条；上游没有 NOTICE 文件，故第 4(d) 条不适用，
  逐条对照见 `THIRD-PARTY-LICENSES.md` 末节）
- 标准库官方文档：OkHttp（Apache-2.0）、Apache Commons Compress、zstd / zstd-jni
- POSIX 标准 API（forkpty 等）与 ADB 公开协议文档

## 同类开源应用的参考政策（2026-10-05 项目所有者更新）

> 所有者原话：「开源的和证道差不多的 APP 挺多的，也可以参考，只是里子用 Debian 13.7
> 和它们有区别而已。」据此把原来「一律禁读」放宽为**分层参考**：

- ✅ **允许**：阅读同类开源应用（Termux 及其分支、太墟等）的**架构与机制**——
  Android 适配思路、保活对抗、终端性能手法、发行版选型等判据层经验。
- 🛑 **仍然禁止**：把它们的代码复制进本仓库。GPL 传染会推翻本项目的独立开发
  声明；学到的只能是"怎么解决问题"，不是"它们的代码"。
- **Termux App 参考版本钉死 `v0.119.0-beta.3`**（所有者日常使用的版本，实测无
  问题；比 F-Droid 稳定版新、坑更少。tag 已核验存在，commit `e634d8f9`）。
  本地参考快照放仓库外：`C:\Users\guoli\AndroidStudioProjects\termux-app-ref\`。
- **rootfs 发行版选型钉死 Debian 13.7 (trixie)**：Alpine/musl 与官方 glibc 预编译
  生态（Node.js / astral uv 等）不兼容，避坑；Ubuntu 同为 glibc 但 rootfs 体积与
  常驻内存明显更大（所有者判断：臃肿）。Debian 13.7 是兼容性与轻量的平衡点。

## 严格禁止

- 把任何同类第三方应用的代码、安装包内的资源**复制进本项目**（包括但不限于
  太墟 / TaiXu、Termux 及其分支）；发现即删除重写


## 第三方组件使用方式

> **合规声明（2026-10-04 项目所有者确认）**：本项目使用 Termux 社区维护的 proot
> 二进制（GPL-2.0+），作为独立可执行文件聚合分发，未修改其源码。
> 本项目的 Kotlin/C 源码为独立开发，不受 GPL 传染；仅承担随附来源与源码指引的
> 分发义务（见 THIRD-PARTY-LICENSES.md）。
> 允许：使用其编译产物、注明"参考了 Termux 的 Android 适配思路"。
> 绝对禁止：把 Termux（或任何 GPL 项目）的 C/C++/汇编代码复制进本项目源码。

| 组件 | 许可证 | 使用方式 |
|---|---|---|
| ~~xterm.js~~ （**v3.9 已移除**） | MIT | 曾作为 WebView 终端渲染层；2026-10-05 随终端渲染层切换为原生 TerminalView 而删除，历史实现保留在 git 中 |
| **Termux `terminal-emulator`**（v3.9 新增） | **Apache-2.0** | 以源码形式集成（`app/src/main/java/com/termux/terminal/`），作为独立库聚合，未修改其许可证头（上游这些文件原本就没有许可证头）；本项目**在其基础上做过修改** ⇒ 2026-10-11 起在文件头加 Apache-2.0 第 4(b) 条要求的修改通知，逐文件见下节「Apache-2.0 修改通知」 |
| **Termux `terminal-view`**（v3.9 新增） | **Apache-2.0** | 同上（`app/src/main/java/com/termux/view/`）；其 native 半边由 `app/src/main/cpp/termux/` 编译为 `libtermux.so`。本项目在其上**改过 2 个文件、另新写 6 个第一方类**（沿用 `com.termux.view` 包名），见下节「Apache-2.0 修改通知」 |
| proot（**Termux fork 发行版**，2026-10-04 基线切换） | **GPL-2.0-or-later** | 官方发行二进制，assets 内置、运行时释放为**独立可执行文件**调用（聚合分发，不链接不混源）；**正文随仓库分发** = `licenses/GPL-2.0.txt`，官方 `.deb` 摘要见「版本固定」节 |
| libtalloc / libandroid-shmem | **LGPL-3.0-or-later** / **BSD-3-Clause**（2026-10-11 钉死，此前只写「LGPL / 宽松许可」） | 同上，作为 proot 的运行时依赖随包分发；**两者都是被 proot 进程在进程内动态加载的 ELF 动态库** ⇒ 不受「聚合不传染」判据保护，随附义务必须履行：正文 = `licenses/LGPL-3.0.txt` ＋ `licenses/GPL-3.0.txt`（GPL-3.0 全文，LGPL-3.0 第 2 条并入；2026-10-11 前它放在仓库根 `LICENSE`）、`licenses/BSD-3-Clause.txt`；源码可得性与使用形态见 `THIRD-PARTY-LICENSES.md` §2 / §3 |
| **hermes pinned uv 0.12.3**（astral-sh/uv，运行时下载） | MIT OR Apache-2.0 | **不随包分发**。hermes 只认它自装的 pinned uv（剥离一切环境变量与配置），证道的包装器（`ProotLauncher.HERMES_UV_WRAPPER_B64`，源文件 `build/hermes-uv-wrapper.sh`）按 hermes install.sh 同源 URL 下载该工件并做 SHA256 校验（`bb66cb52…68dcca2`）后 exec；包装器同时强制 `UV_LINK_MODE=copy`（Android/proot 无可用硬链接） |
| OkHttp / Compose 等 | Apache-2.0 | Maven 依赖，按官方文档使用 |
| **JetBrains Maple Mono**（终端默认字体，2026-10-10 引入；2026-10-10 追加子集化） | **SIL OFL 1.1** | 随包分发的字体文件（`app/src/main/assets/fonts/JetBrainsMapleMono-NF-Regular.ttf`），取自 SpaceTimee/Fusion-JetBrainsMapleMono 的 `NF-XX-XX-XX` 变体包；**已由本项目子集化**（GB2312 常用汉字 6,763 字 + 终端必备区 + Nerd Font 图标区，见下节「字体」；工具与参数随仓库 `tools/subset-terminal-font.py`，OFL 1.1 允许修改与再分发，子集化产物仍受 OFL 1.1 约束），OFL 全文随字体同目录分发（`app/src/main/assets/fonts/OFL.txt`） |

> ⚠️ **许可证勘误（2026-10-05）**：`terminal-emulator` / `terminal-view` 曾被误标为
> GPL-3.0。上游 `termux-app/LICENSE.md` 明确：仓库整体为 GPLv3，**但这两个库属于
> 例外，为 Apache-2.0**（源自 jackpal/Android-Terminal-Emulator）。
> 误标会导致闭源决策误判——GPL 会要求开源第一方代码，Apache-2.0 不会。
> 相关注释已在 `TerminalActivity.kt`、`SessionManager.kt`、`CMakeLists.txt` 中同步更正。

> 🛑 **v3.9 红线**：`libtermux.so` 是**真正的 JNI 动态库**，用 `System.loadLibrary("termux")`；
> 而 proot 是**独立可执行文件**，**只能 `exec`，绝不能 `dlopen` / `loadLibrary`**——
> 后者会被认定为衍生作品，导致整个 App 被 GPL 传染。
> 注：本条红线**与文件名、存放目录无关**。历史方案曾把 proot 改名为 `libproot.so`
> 打进 jniLibs（只为借用原生库打包机制），该方案已废弃；但即便沿用那个名字，
> proot 依然只能 exec，不因叫 `.so` 就能当库加载。

### Apache-2.0 修改通知（2026-10-11 补做）

`app/src/main/java/com/termux/` 与 `app/src/main/cpp/termux/` 里的文件**不是上游副本**：它们源自
Termux `terminal-emulator` / `terminal-view`（**Apache-2.0**，上游基线 **`v0.119.0-beta.3`**，
tarball SHA256 `484A114A19187084156AF0F9A00EF7386112E4C5E9F62BF9D20C13A91C2C20D6`，四来源下载逐字节一致），
**已由本项目修改**。按 Apache-2.0 第 4(b) 条，下表 12 个文件在文件头带统一修改通知。

| 文件（相对 `app/src/main/`） | 修改方向 |
|---|---|
| `java/com/termux/terminal/TerminalEmulator.java` | 移植上游 `d8d6b02`（OSC52 100 KB 上限）+ 自研 🟡-7 OSC 载荷超限丢弃（`MAX_OSC_DISCARD_LENGTH`）+ 日志与 `Base64.DEFAULT`；**2026-10-12 追加**：DECSLRM 之后退格会把光标列写成 −1（随后 ICH 撞 `TerminalBuffer.blockCopy` 的断言、从 pty 读线程冒顶崩进程）⇒ BS 判据改 `mCursorCol <= mLeftMargin`，`setCursorCol()` 钳到缓冲区边界；**2026-10-12 再追加**：IL（`ESC [ N L`）分支补 `mAboutToAutoWrap = false`（兄弟分支 EL/ICH/DL/DCH 都清，独它漏 ⇒ 打满一行后插行再敲字符会凭空跳行，并给该行盖上假的换行标志） |
| `java/com/termux/terminal/TerminalColorScheme.java` | `NUM_INDEXED_COLORS` 259 → 260、新增 `COLOR_INDEX_SELECTION`、`reset()` private → public |
| `java/com/termux/terminal/TextStyle.java` | 选区底色（`COLOR_INDEX_SELECTION`） |
| `java/com/termux/terminal/TerminalBuffer.java` | 行尾清空时补 `clearLineWrap` |
| `java/com/termux/terminal/TerminalRow.java` | `mSpaceUsed` `short` → `int`；**2026-10-12 追加**：`clear()` 补 `mLineWrap = false`（行复用路径——滚动 / IL / DL 都走 `clear()`——必须把换行标志一起复位，否则 `getSelectedText()` 把两行粘成一行 / 凭空多一行空格） |
| `java/com/termux/terminal/TerminalSession.java` | 进程 / 终端 IO 队列 4 KiB → 64 KiB；**2026-10-12 追加**：`isRunning()` 判据 `mShellPid != -1` → `mShellPid > 0`（字段语义是 0 = 未启动、−1 = 已结束；旧判据让 `finishIfRunning()` 对未 spawn 的会话发 `Os.kill(0, SIGKILL)` ＝ 自杀） |
| `java/com/termux/terminal/KeyHandler.java` | `kN` / `kP` 上下翻页对调修正 |
| `java/com/termux/view/TerminalView.java` | 拆分：1,589 → 758 行（职责搬进 6 个第一方类）+ 输入 / 选区定制 |
| `java/com/termux/view/TerminalRenderer.java` | 行高倍率构造器、选区底色、`drawTextRun` 的 API 版本闸 |
| `java/com/termux/textselection/TextSelectionCursorController.java` | `R` 类导入改为 `com.example.zhengdao.R`；**2026-10-11 追加**：`getValidCurX()` 里「列 → 合法游标列」的判据抽到第一方类 `com.termux.view.textselection.DisplayColumnIndex`（同时移出已无用的 `com.termux.terminal.WcWidth` 导入），此处行为改为调用该判据 |
| `java/com/termux/textselection/TextSelectionHandleView.java` | 同上 |
| `cpp/termux/termux.c` | JNI `ReleaseStringUTFChars(env, cmd, cmd_cwd)` → `(env, cwd, cmd_cwd)`（**上游 beta.3 尚未修的内存 bug**） |

另有 **6 个纯第一方**文件沿用 `com.termux.view` 包名（合计 1,501 行，非上游作品；
上游 `terminal-view` 里**不存在同名文件**，2026-10-11 逐文件核对）——
`TerminalImeBridge` 442、`TerminalGestureController` 308、`TerminalCursorBlinker` 250、
`TerminalA11yDelegate` 206、`TerminalSelectionController` 194、`TerminalSizeResolver` 101 ——
行数为**含声明头的现状行数**（各含本文件「代码文件头」一节规定的**独立开发声明** 4 行）。

**2026-10-11 追加的第一方类**：`app/src/main/java/com/termux/view/textselection/DisplayColumnIndex.java`
（`package com.termux.view.textselection`，目录与包名一致；带同一套独立开发声明头）。用途：把
「显示列 → 合法游标列」的判据收敛成单一实现（终端按列排版，宽字符占两列、组合字符占零列，
选词手柄按列给坐标），供上表的 `TextSelectionCursorController` 调用；纯计算、无 Android 依赖，
JVM 单测在 `app/src/test/java/com/example/zhengdao/terminal/DisplayColumnIndexTest.kt`。
它不在上述 6 个之列（那 6 个在 `com/termux/view/` 目录下）。

其余 **11 个上游文件逐字节未改**（terminal 7：`ByteQueue` / `JNI` / `Logger` / `TerminalColors` /
`TerminalOutput` / `TerminalSessionClient` / `WcWidth`；view 3：`GestureAndScaleRecognizer` /
`TerminalViewClient` / `support/PopupWindowCompatGingerbread`；textselection 1：`CursorController`），
保持原样、不加任何标记。**更正（2026-10-11）**：早前此处写「上游仓库级 `LICENSE.md` 与
`THIRD-PARTY-LICENSES.md` 照常随仓库分发」**不准确** —— 上游这两个文件在**仓库根/termux-shared 下，
不在被聚合的 `terminal-emulator` / `terminal-view` 模块目录内**，本项目因此**没有**它们，这是聚合范围问题，
**不构成"移除"**；本项目随自己仓库分发的声明在根 `THIRD-PARTY-LICENSES.md`、正文在 `licenses/`；
**没有任何第三方版权声明、LICENSE 或 NOTICE 被移除**。

复现比对：`git diff --no-index --numstat <上游 tarball 解包目录> app/src/main/java/com/termux`。
（注意 `textselection` 存在包名与目录不符的历史遗留，比对时按路径而非包名对应，见 `docs/ERRATA.md` E-101。）

**2026-10-11 复核结果**（按上述方法，对 `v0.119.0-beta.3` tarball 快照逐文件核）：terminal 14/**14**、
view 上游 8 / 本项目 11（**6 个第一方类上游无同名文件**）、textselection 3/**3**；逐字节相同的
**11 个文件**为 `ByteQueue` / `JNI` / `Logger` / `TerminalColors` / `TerminalOutput` /
`TerminalSessionClient` / `WcWidth` / `GestureAndScaleRecognizer` / `TerminalViewClient` /
`support/PopupWindowCompatGingerbread` / `CursorController`；12 个衍生文件**逐个实测带文件头修改通知**；
`cpp/termux/termux.c` 的差异为 7 增 1 删（通知头 6 行 + `ReleaseStringUTFChars(env, cmd, cmd_cwd)`
→ `(env, cwd, cmd_cwd)` 的内存 bug 修复）。**没有任何"上游有、本项目缺"的文件**（除按设计未收的
上游其它模块）。取证与台账见 `docs/FEATURE-LEDGER.md` §A #36。
### 版本固定（可复现构建基线，2026-10-04 固化）

来源：Termux 官方仓库 stable/main（packages.termux.dev），aarch64 官方构建产物，
proot 版本 **5.1.107.96**、libtalloc **2.5.0**、libandroid-shmem **0.7**。
四个文件随 APK assets（`app/src/main/assets/proot/`）内置，SHA256 如下
（App 启动时按此校验，不匹配即重释放；清单同时硬编码于 ProotLauncher.kt）：

| asset 文件 | 释放为 | SHA256 |
|---|---|---|
| tproot | files/termux-proot/proot | `1545b85b312505db6eb6908ff8b2ded0a77a3bd689c50ae85aa7c1d8445dd717` |
| tloader | files/termux-proot/loader | `cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551` |
| libtalloc.so | files/termux-proot/libtalloc.so.2 | `742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309` |
| libandroid-shmem.so | files/termux-proot/libandroid-shmem.so | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` |

**官方产物摘要（2026-10-11 补，取自 Termux 官方 apt 索引
<https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages>，
索引快照 1,863,706 B）**：`proot_5.1.107.96_aarch64.deb` =
`8199dca06dccb693ec09fb1759e3e1ad08b4863f0c11c612f89c20bd9ecdc1a0`、
`libtalloc_2.5.0_aarch64.deb` = `556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7`、
`libandroid-shmem_0.7_aarch64.deb` = `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6`
**构建侧回查（2026-10-11 补做，替代"构建 commit"）**：Termux 官方索引**不发布构建 commit**
（`.deb` 由 `termux/termux-packages` 的 CI 产出，没有对应的源码提交号），所以锚点落在**三样可核验的东西**上：

| 组件 | 构建配方（`termux/termux-packages`） | 版本 bump 提交 | 上游源码包 SHA256 |
|---|---|---|---|
| proot 5.1.107.96 | `packages/proot/build.sh`（blob `440897beb2717fc334edd5ad2ab42bd63b504910`） | `de39661946f7e8175b5dd0755121fa28cb0aebd1`（2026-10-01T18:39:38Z，「bump(main/proot): 5.1.107.96」） | `https://github.com/termux/proot/archive/v5.1.107.96.zip` = `75f654fe60dea92dabff2bf083ae8bfe4f91baa6a1a374786a6bf391015eebaa`（上游 tag `v5.1.107.96` → commit `a179d3e8a4e045aaa1fb8cc3284f23509d96d353`） |
| libtalloc 2.5.0 | `packages/libtalloc/build.sh`（blob `814cb06bc6a5052b84012ce2bb99e8c9cec84ee8`） | — | `https://www.samba.org/ftp/talloc/talloc-2.5.0.tar.gz` = `912afa237510ae542a7733998eb18a12bcda35ab6729c8e2ddb43e8d0ebab007` |
| libandroid-shmem 0.7 | `packages/libandroid-shmem/build.sh`（blob `7e013d0a658622a3a1797f868d4028cfd58f42a7`） | — | `https://github.com/termux/libandroid-shmem/archive/refs/tags/v0.7.tar.gz` = `1e5ff8459bc0a8c229dd8a94b27d119987e09ef3414331c2b5ebfff20b98e867` |

配方里 `export PROOT_UNBUNDLE_LOADER=$TERMUX_PREFIX/libexec/proot` 正是 assets 里 **`tproot` ＋ `tloader`**
两个文件的来源（loader 不内嵌，装到 `libexec`，随包释放成 `files/termux-proot/loader`）；
`TERMUX_PKG_DEPENDS="libandroid-shmem, libtalloc"` 与上表两个 `.so` 对应。
取数时间 2026-10-11，来源 GitHub REST（`/commits?path=packages/proot/build.sh`、`/contents/...`、
`/repos/termux/proot/git/ref/tags/v5.1.107.96`）。
许可证正文随仓库分发：`licenses/GPL-2.0.txt`（proot）、`licenses/LGPL-3.0.txt` ＋ `licenses/GPL-3.0.txt`
（libtalloc；后者 2026-10-11 前放在仓库根 `LICENSE`）、`licenses/BSD-3-Clause.txt`（libandroid-shmem）。

升级流程：从 Termux 仓库取新版本 → 计算新 SHA256 → 更新 assets 与上表 →
真机回归（bash 存活 + 四项验收）→ 提交。

### 官方发布资产摘要（Release assets，2026-10-11 入册）

**判据不是本项目自记**，而是从公开地址回读 GitHub REST
`/repos/pisces19860207/zhengdao/releases/tags/<tag>` 返回的 `assets[].digest`（GitHub 自己算的
`sha256:` 值）⇒ 任何人下载后都能独立复算。取数时间：v2.0.14 / v2.0.15 段为 2026-10-11T13:1xZ，
**v2.0.16 段为 2026-10-11T14:27Z**（Release 建立于 `2026-10-11T14:26:37Z`，tag 指向 `6ed8de4`）；
**v2.0.17 段为 2026-10-11T15:43Z**（Release 建立于 `2026-10-11T15:43:04Z`，tag 指向 `6aa1517`）。

随版本变化的产物：

| 版本 | 资产 | 字节 | SHA256 |
|---|---|---|---|
| v2.0.14 | `zhengdao-2.0.14-release.apk` | 8,226,607 | `3ea4b2f1d2dd2620a178ade52062474f470273419aeb4edf718ad3995adca0a8` |
| v2.0.15 | `zhengdao-2.0.15-release.apk` | 8,293,206 | `35e2feac79e110a4b4a246a3c41e411fd1c6d245a786f967ddf0aaf8b6ea9210` |
| v2.0.16 | `zhengdao-2.0.16-release.apk` | 8,317,372 | `b9702f32f44f532315100c2bbef003d5db9ddc99bdb1301a63f216b7bc5156ee` |
| v2.0.17 | `zhengdao-2.0.17-release.apk` | 8,318,188 | `ee269c37e2b88483cec834fe356db6f598c9a44bb6574f5924a2b14268679c9d` |

rootfs 五项（**v2.0.14 ~ v2.0.17 四版逐字节相同、digest 一致** —— 这四版都没有改动 rootfs；
末两项是随资产分发的校验文件）：

| 资产 | 字节 | SHA256 |
|---|---|---|
| `debian-13.7-base-arm64.tar.zst` | 201,632,517 | `d80639e7dc5c055fb731e5af62b6789d6ac4d00d07dafe9717f6aed726174f02` |
| `debian-13.7-base-arm64.tar.zst.sha256` | 65 | `2b7b292e5d8641fb3eaf0014adede54beee627606993a652380bbcd1038717b5` |
| `rootfs-index.json` | 469 | `50f10326d9e68a4c1fdd03154e317c9d6068a0d6fa2c71a4c5e73f3b073b78ff` |
| `rootfs-manifest.txt` | 2,117,653 | `82fa3f4e84a7963d399cf5f63a3bb8a41d5af183ee7f5812a6a8e84eeecdf16c` |
| `rootfs-manifest.txt.sha256` | 65 | `5fc8007e93a30609f3e5e9ba3d9f57f6e51321fc16dcad340b75dbaa8f4cd66f` |

**真机交叉核对（2026-10-11 实测）**：手机公共区缓存
`/sdcard/Download/证道/rootfs/debian-13.7-base-arm64.tar.zst`（201,632,517 B）端侧 `sha256sum`
= `d80639e7dc5c055fb731e5af62b6789d6ac4d00d07dafe9717f6aed726174f02` = 上表 digest
⇒ **App 实际下载消费的离线包与线上发布资产逐字节一致**（不是"名字一样"，是同一串字节）。

**滚动版 `latest` 不入册**：它每次构建都刷新（`zhengdao-2.0.15-release.apk` 的 `updated_at`
= 2026-10-11T13:09:45Z；`zhengdao-2.0.16-release.apk` 在 `2026-10-11T14:07:53Z` 那次为
**8,317,176 B / `987bce291cac776664dc0291a2c53041336efe2c9fd59ddf4df5af98e30082d3`**；
`zhengdao-2.0.17-release.apk` 在 `2026-10-11T15:41:40Z` 那次为 **8,318,188 B /
`23df2359e31625aad5b42dced58cb56c43dea01678f3e051bb8e0638fe1e9b1a`** —— 与 tag 正式包
**字节数相同、sha256 不同**，两次是各自独立的构建），
同一版本号的两次构建 sha256 并不相同（v2.0.15 本地构建 8,293,218 B vs CI 构建 8,293,206 B；v2.0.16
本地构建 8,317,384 B vs tag 正式包 8,317,372 B；v2.0.17 本地构建 8,318,196 B vs tag 正式包 8,318,188 B
—— **差值 12 B / 12 B / 8 B**，见 `docs/ERRATA.md` E-081：
滚动构建不可复现：同密钥、同 versionCode ⇒ 覆盖安装仍兼容）。**只有打了 tag 的正式版入册。**

`THIRD-PARTY-LICENSES.md` §7「Debian rootfs」一节据此加了指向本节的一行。

### 字体（终端默认正文，2026-10-10 固化）

来源：`SpaceTimee/Fusion-JetBrainsMapleMono` release **1.2304.79**（2025-12-05）的
`JetBrainsMapleMono-NF-XX-XX-XX.zip`（152.3 MB，zip SHA256
`3a7ed5e50f6831dc1414a4ad96b1e03c13cbc67ca32bc1bb7e9d90c56b903358`）。
变体选 `NF-XX-XX-XX`：`NF` 带 Nerd Font 图标字形；**必须 `XX` 而非 `NR`**——上游 README
写明 `NR`（CN Narrow）「会导致中英文/日英文不再 2:1 宽完美对齐」；保留连字、未 hint。

| asset 文件 | 上游文件名 | SHA256 |
|---|---|---|
| `app/src/main/assets/fonts/JetBrainsMapleMono-NF-Regular.ttf` | （**已子集化**，非上游原字节；上游原件为 `JetBrainsMapleMono-Regular.ttf`，见下「子集化」） | `10c8ea51ab6bb2df9a615424df1054ad6b3c08ad60a47e8bb3368039b92b5089`（6,773,564 B） |
| `app/src/main/assets/fonts/OFL.txt` | `LICENSE.txt`（zip 内，4572 B，SIL OFL 1.1 全文 + 三行版权） | `6728aae70e0be6316b28681c5a806827b4d7daafe45fb767b932c790216c2533` |

### 字体子集化（2026-10-10，`tools/subset-terminal-font.py`）

上游全量字体 18,815,220 B 里 **glyf 表占 96.4%**（18,136,017 B），按 Unicode 范围裁到 GBK
全量也只省 3%（18.19 MB / 97%）——按「保 GBK 全量」做，APK 会停在 13 MB 以上。因此改为
**GB2312 常用集**：

| 项 | 值 |
|---|---|
| 上游全量源字体 | 18,815,220 B，SHA256 `a4fc642d821671b1a2937b9a52d398b96cf0b1e1da758846ee1ff38a297b22a5`（**不随包分发**，重新子集化时用 `--source` 指向它） |
| 产物（入库资产） | 6,773,564 B = 源字体 36.0%，SHA256 `10c8ea51ab6bb2df9a615424df1054ad6b3c08ad60a47e8bb3368039b92b5089` |
| 工具 | `tools/subset-terminal-font.py`（fontTools **4.66.1** + brotli，`python -m fontTools.subset`） |
| 参数 | `--unicodes=U+0000-00FF,U+0100-024F,U+2000-206F,U+2190-21FF,U+2300-23FF,U+2500-259F,U+25A0-27BF,U+2B00-2BFF,U+3000-303F,U+3400-4DBF,U+F900-FAFF,U+FE30-FE4F,U+FF00-FFEF,U+E000-F8FF,U+F0000-FFFFD` + `--text-file=<GB2312 解码出的 6,763 汉字>` + `--layout-features=* --notdef-glyph --name-IDs=* --no-recalc-timestamp` |
| 可复现性 | `--no-recalc-timestamp` 关掉时间戳重算，同输入两次运行产物 SHA256 相同（已验证）；**故意不写 `U+4E00-9FFF`**——写范围会把上游 20,975 个汉字全留下（回到 17.4 MB），汉字只从 GB2312 的 6,763 字取 |
| 字形变化 | 34,127 → 18,342 字形，cmap 33,355 → 17,994 码位（保留 GDEF/GPOS/GSUB/gasp，连字与 OpenType 特性未动） |
| 度量不变 | unitsPerEm 1000；ASCII 步进 600/1000 em、CJK（含 U+3000/U+FF0C）1,200/1000 em ⇒ **仍严格 2:1**（真机列宽 25.0/50.0 px） |
| 覆盖代价 | GB2312 之外的**约 1.4 万汉字回退系统字体**（列宽不再保证 2:1）；上游字体本身**不含** CJK 扩展 A/B/C/D/E/F/G 任何字形（U+3400 㐀、U+3401 㐁、U+9F98、U+9FEF 均缺失），生僻字必然回退，与本项目子集化无关 |

重新子集化：`python tools/subset-terminal-font.py --source <上游全量 ttf>`（默认写回 asset
路径）；换 release tag 时的流程 = 取同名变体包的全量 ttf → 跑该脚本 → 更新上表两行
SHA256 → 更新 THIRD-PARTY-LICENSES.md § 6（如有需要）→ 真机验 2:1。

字体版权（`OFL.txt` 首三行）：The JetBrains Mono Project Authors（2020）、The Maple Mono
Project Authors（2022）、Space Time（2025）。

加载方式：`TerminalPrefs.typeface(ctx)`（`app/src/main/java/com/example/zhengdao/terminal/
TerminalPrefs.kt`）在 `applyTo()` 里于 `setTextSize()` **之后**调用
`termView.setTypeface(...)`——顺序不能反：`TerminalSizeResolver.setTypeface()` 会读
`mRenderer.mTextSize`，而渲染器是 `setTextSize()` 里懒创建的，先设字体直接 NPE。
载入失败（asset 缺失等）回退 `Typeface.MONOSPACE`。

升级流程：换 release tag → 取同名变体包的全量 `JetBrainsMapleMono-Regular.ttf` →
跑 `python tools/subset-terminal-font.py --source <全量 ttf>` → 更新 assets、上表两行 SHA256
与 THIRD-PARTY-LICENSES.md § 6 → 真机验 2:1（`echo "中文中文 abcd"` 与 `echo "中a文b中c文d"`）
与字体加载耗时 → 提交。

## 代码文件头

本项目**所有第一方代码文件**（`*.kt` / `*.rs`，含 `app/src/main`、单元测试 `app/src/test`、
仪器测试 `app/src/androidTest`、`macrobenchmark` 与 `rust/core/src`）头部须带声明：

```kotlin
// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
```

两类例外：① `app/src/main/java/com/termux/**` 下**源自上游的衍生文件**用上节规定的 Apache-2.0 §4(b)
修改通知（那里只用通知，不再叠加本节声明）；② 同目录下 6 个纯第一方类（`TerminalImeBridge` /
`TerminalGestureController` / `TerminalCursorBlinker` / `TerminalA11yDelegate` /
`TerminalSelectionController` / `TerminalSizeResolver`）仍用本节声明，并额外补一行说明它们沿用
`com.termux.view` 包名、非上游文件。

自查（逐文件取前 10 行判关键词，**不要手工维护清单**）：

```bash
git ls-files '*.kt' '*.rs' | grep -v '/com/termux/' |
  while read f; do head -10 "$f" | grep -q '独立开发声明' || echo "缺声明：$f"; done
```

2026-10-11 实测：第一方 `*.kt` / `*.rs` 共 **175** 个，声明齐全（本轮补做 **34** 个 —— 早前只扫了
`app/src/main` + `rust`，漏掉单测 / androidTest / macrobenchmark；口径更正见 `docs/ERRATA.md` E-100
补记与 `docs/FEATURE-LEDGER.md` §A #36）。

## 义务核验流程（2026-10-11 新增，模板）

任何「某项许可证义务已履行」的话，**在写进台账 / README / 提交信息之前**，必须走完四步：

1. **核验** —— 逐文件查义务是否真落在**文件本身**，而不是台账里的自我报告。
   两个现成判据：本节上面的文件头自查命令；`git diff --no-index --numstat <上游 tarball 解包目录> <聚合目录>`。
2. **补齐** —— 缺的当场补，不写「待办」。
3. **复验** —— 同一条命令再跑一遍，必须**零命中**；同时**重数一遍数量**。
   教训（2026-10-11 一晚踩了两次）：先说「上游 12 个有差异文件里 7 个没标注」（对），
   紧接着说「第一方缺声明 11 个」——**实际 34 个**，因为那次只扫了 `app/src/main` + `rust`。
   **「有没有」和「有几个」都必须用同一条命令数一遍再写**，别用手工清单。
4. **落账** —— 核验命令、命中数量、复验结果，一起写进台账的「证据 / 验证方式」列。

应用示例（本轮全量补齐 **34** 个第一方文件，逐个名单见 `docs/ERRATA.md` E-100 补记与
`docs/FEATURE-LEDGER.md` §A #36；下表是本仓 `app/src/main` + `rust/core/src` 的那 11 个）：

| 位置 | 文件 |
|---|---|
| `app/src/main/java/com/example/zhengdao/` | `ZhengdaoApp.kt`、`oc/OcConnectionPolicy.kt`、`oc/OcRuntime.kt`、`terminal/StorageAudit.kt`、`ui/theme/Type.kt`、`ui/HermesInstallScript.kt`、`ui/ImeProbe.kt`、`ui/ImeRegressionActivity.kt`、`ui/NetSelfCheck.kt` |
| `rust/core/src/` | `jni_bridge.rs`、`tests.rs` |

其中 `OcRuntime.kt` / `StorageAudit.kt` / `NetSelfCheck.kt` / `jni_bridge.rs` 四个（即下方
「实现方红线清单」第 2 条最可能被质疑之处）**额外一行**写明「只按 POSIX 标准接口 / Android
NDK 官方文档实现，未参考任何第三方同类应用（含 termux-app）的源码」。

来历：`docs/ERRATA.md` E-100（措辞里那句「所有修改文件已按第 4(b) 条标注修改通知」当时为假，
文件先补、话后说）与 `docs/FEATURE-LEDGER.md` §A #36（34 个第一方文件头补齐）。
**文件先改，话后说；数字先数，再写。**

## 实现方红线清单（发给 Zcode / 任何实现方之前，请先读这一节）

1. **proot 只用 exec 启动**（与文件名、存放目录无关，见上方 v3.9 红线），代码里**不得出现**
   `System.loadLibrary("proot")` 或任何形式的 dlopen。
2. 自研的 JNI / 终端桥接代码**不得参考或复制 `termux-app` 的 GPL 源码**——只能依据
   POSIX 标准接口独立实现。
3. **不得移除**任何第三方组件中的版权声明、LICENSE 或 NOTICE 文件。
4. Apache-2.0 组件需在分发物中**保留其 LICENSE 文本**（或其链接）与 NOTICE 内容；
   若做了修改须明确标注。
5. **R8 混淆不影响上述义务**——混淆的是第一方代码，第三方许可证声明必须照常保留；
   `-keep` 规则不得被误用于"清理"这些资源。
6. proot 的**构建 commit 必须记录**（用于履行源码要约的可复现性）。
7. 新增任何第三方依赖时，**同步更新本文件**。

---

## 免责说明

本文件基于项目当前技术选型编写，用于工程合规自证，**不构成法律意见**。
若未来涉及商业化分发、境外分发或引入新的 copyleft 组件，建议由专业法务复核。
