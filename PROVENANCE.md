# 独立开发声明（PROVENANCE）

本项目（zhengdao / 安卓轻量级 AI Agent App）为**从零独立开发**。
本文件是整个仓库的原创性规则，所有协作者（包括 AI）必须遵守。

## 允许参考（官方一手资料）

- Android 官方开发者文档（developer.android.com / developer.android.google.cn）
- proot 上游官方仓库与官方文档（proot-me/proot）及其官方运行参数
- xterm.js 官方文档与官方发行包（MIT 许可证）
- 标准库官方文档：OkHttp（Apache-2.0）、Apache Commons Compress、zstd / zstd-jni
- POSIX 标准 API（forkpty 等）与 ADB 公开协议文档

## 严格禁止

- 阅读、引用、反编译任何同类第三方应用的代码或安装包
  （包括但不限于太墟 / TaiXu、Termux 及其分支）
- 复刻任何同类应用的 UI 交互细节
- 把 AI 生成的"疑似来自某项目"的代码片段直接入库——发现即删除重写

## 第三方组件使用方式

> **合规声明（2026-10-04 项目所有者确认）**：本项目使用 Termux 社区维护的 proot
> 二进制（GPL-2.0+），作为独立可执行文件聚合分发，未修改其源码。
> 本项目的 Kotlin/C 源码为独立开发，不受 GPL 传染；仅承担随附来源与源码指引的
> 分发义务（见 THIRD-PARTY-LICENSES.md）。
> 允许：使用其编译产物、注明"参考了 Termux 的 Android 适配思路"。
> 绝对禁止：把 Termux（或任何 GPL 项目）的 C/C++/汇编代码复制进本项目源码。

| 组件 | 许可证 | 使用方式 |
|---|---|---|
| xterm.js | MIT | 官方发行包，按官方文档接入 |
| proot（**Termux fork 发行版**，2026-10-04 基线切换） | GPL-2.0-or-later | 官方发行二进制，assets 内置、运行时释放为**独立可执行文件**调用（聚合分发，不链接不混源） |
| libtalloc / libandroid-shmem | LGPL / 宽松许可 | 同上，作为 proot 的运行时依赖随包分发 |
| OkHttp / Compose 等 | Apache-2.0 | Maven 依赖，按官方文档使用 |

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
