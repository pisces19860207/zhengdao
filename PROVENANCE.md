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
| OkHttp / Compose 等 | Apache-2.0 | Maven 依赖，按官方文档使用 |

> ⚠️ **许可证勘误（2026-10-05）**：`terminal-emulator` / `terminal-view` 曾被误标为
> GPL-3.0。上游 `termux-app/LICENSE.md` 明确：仓库整体为 GPLv3，**但这两个库属于
> 例外，为 Apache-2.0**（源自 jackpal/Android-Terminal-Emulator）。
> 误标会导致闭源决策误判——GPL 会要求开源第一方代码，Apache-2.0 不会。
> 相关注释已在 `TerminalActivity.kt`、`SessionManager.kt`、`CMakeLists.txt` 中同步更正。

> 🛑 **v3.9 红线（同 proot，一视同仁）**：`libtermux.so` 是**真正的 JNI 动态库**，
> 可 `System.loadLibrary("termux")`；而 `libproot.so` 只是借用 .so 命名打包的
> **独立可执行文件**，只能 `exec`，**绝不能 `dlopen`/`loadLibrary`**——
> 后者会被认定为衍生作品，导致整个 App 被 GPL 传染。

### 版本固定（可复现构建基线，2026-10-04 固化）

来源：Termux 官方仓库 stable/main（packages.termux.dev），aarch64 官方构建产物，
proot 版本 **5.1.107.96**、libtalloc **2.5.0**、libandroid-shmem **0.7**。
四个文件随 APK assets（app/src/main/assets/runtime/）内置，SHA256 如下
（App 启动时按此校验，不匹配即重释放；清单同时硬编码于 ProotLauncher.kt）：

| asset 文件 | 释放为 | SHA256 |
|---|---|---|
| tproot | files/termux-proot/proot | `1545b85b312505db6eb6908ff8b2ded0a77a3bd689c50ae85aa7c1d8445dd717` |
| tloader | files/termux-proot/loader | `cbdef0e652c2b78af25d867e1719fdebbb0915e25aae2dd35b3b5c1835f6b551` |
| libtalloc.so | files/termux-proot/libtalloc.so.2 | `742b438c4d09e276985a61d44164c9de207b6d8cc268f3018cb3001961bcb309` |
| libandroid-shmem.so | files/termux-proot/libandroid-shmem.so | `84475798e07c8174dbbfaec70a827fdb02f19ffa69a589380c13e7507fd0e731` |

升级流程：从 Termux 仓库取新版本 → 计算新 SHA256 → 更新 assets 与上表 →
真机回归（bash 存活 + 四项验收）→ 提交。

## 代码文件头

本项目所有第一方代码文件头部须带声明：

```kotlin
// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
```
