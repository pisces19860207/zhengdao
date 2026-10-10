# 独立开发声明（PROVENANCE）

本项目（zhengdao / 安卓轻量级 AI Agent App）为**从零独立开发**。
本文件是整个仓库的原创性规则，所有协作者（包括 AI）必须遵守。

## 允许参考（官方一手资料）

- Android 官方开发者文档（developer.android.com / developer.android.google.cn）
- proot 上游官方仓库与官方文档（proot-me/proot）及其官方运行参数
- Termux `terminal-emulator` / `terminal-view` 库的官方文档与源码（**Apache-2.0**，
  见上游 termux-app 仓库 LICENSE.md 的 Exceptions 一节；v3.9 起聚合为本项目的
  终端渲染层，**允许按 Apache-2.0 条款使用**，须保留其许可证与 NOTICE）
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
| **Termux `terminal-emulator`**（v3.9 新增） | **Apache-2.0** | 以源码形式集成（`app/src/main/java/com/termux/terminal/`），作为独立库聚合，未修改其许可证头 |
| **Termux `terminal-view`**（v3.9 新增） | **Apache-2.0** | 同上（`app/src/main/java/com/termux/view/`）；其 native 半边由 `app/src/main/cpp/termux/` 编译为 `libtermux.so` |
| proot（**Termux fork 发行版**，2026-10-04 基线切换） | GPL-2.0-or-later | 官方发行二进制，assets 内置、运行时释放为**独立可执行文件**调用（聚合分发，不链接不混源） |
| libtalloc / libandroid-shmem | LGPL / 宽松许可 | 同上，作为 proot 的运行时依赖随包分发 |
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

升级流程：从 Termux 仓库取新版本 → 计算新 SHA256 → 更新 assets 与上表 →
真机回归（bash 存活 + 四项验收）→ 提交。

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

本项目所有第一方代码文件头部须带声明：

```kotlin
// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
```

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
