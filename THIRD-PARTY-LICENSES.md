# 第三方组件声明（THIRD-PARTY NOTICES）

本应用按「聚合分发（mere aggregation）」方式携带以下独立的第三方二进制组件。
它们与本项目第一方代码**不链接、不混合**：App 运行时将其释放为独立可执行文件并
以独立进程方式调用。本项目的 Kotlin/C 源码中**不含任何来自下列项目的代码**。

## 1. proot（Termux fork 发行版）

- 用途：免 root 用户空间环境（guest Linux 的加载器）
- 版本：5.1.107.96（Termux 官方仓库 aarch64 构建产物）
- 许可证：GPL-2.0-or-later（含其内嵌 loader 组件）
- 来源：<https://github.com/termux/proot>（源码即该仓库）
- 源码可得性：本仓库分发其二进制的同时，以指向上述官方源码仓库的方式满足
  GPL 第 3 条的源码提供要求；若对二进制有任何修改，将一并公开对应源码。
- 本项目对它的使用方式：仅作为独立可执行文件调用（execve），未链接、未修改。

## 2. libtalloc（Termux 发行版）

- 用途：上述 proot 的运行时依赖
- 版本：2.5.0（Termux 官方仓库 aarch64 构建产物）
- 许可证：LGPL-3.0-or-later（talloc 上游；以动态库方式独立分发，符合 LGPL）
- 来源：<https://github.com/termux/proot> 所在仓库组的对应包 / 上游
  <https://www.samba.org/ftp/talloc/>

## 3. libandroid-shmem（Termux 发行版）

- 用途：上述 proot 的运行时依赖（System V 共享内存的 Android 适配层）
- 许可证：见 Termux 仓库对应包元数据（Apache-2.0 / BSD 类宽松许可）
- 来源：<https://github.com/termux/proot> 所在仓库组的对应包

## 4. xterm.js / @xterm/addon-fit

- 用途：内嵌终端渲染（assets/terminal/ 下为其官方发行文件）
- 许可证：MIT
- 来源：<https://github.com/xtermjs/xterm.js>

## 5. zstd（zstd-jni 附带的原生库）

- 用途：RootFS 压缩包解压
- 许可证：BSD-3-Clause（双许可中的 BSD 分支）
- 来源：<https://github.com/facebook/zstd>（经 zstd-jni 官方 AAR 分发）

## 6. JetBrains Maple Mono（终端默认字体）

- 用途：终端默认正文字体（`app/src/main/assets/fonts/JetBrainsMapleMono-NF-Regular.ttf`，
  运行时由 `TerminalPrefs.typeface()` 经 `Typeface.createFromAsset` 载入）
- 许可证：**SIL Open Font License 1.1**；OFL 全文随字体同目录分发
  （`app/src/main/assets/fonts/OFL.txt`，即上游 zip 内的 `LICENSE.txt`）
- 来源：<https://github.com/SpaceTimee/Fusion-JetBrainsMapleMono> release `1.2304.79`
  的 `JetBrainsMapleMono-NF-XX-XX-XX.zip`（NF = Nerd Font 图标、XX = 不窄保 2:1、
  保留连字、未 hint）
- 改动情况：**未修改、未子集化**，字节与上游一致；入库时仅改名为带 `NF` 的名字
  （上游 zip 内平铺文件名为 `JetBrainsMapleMono-Regular.ttf`，NF 是整包变体）
- 版权（`OFL.txt` 首三行）：JetBrains Mono Project Authors（2020）、Maple Mono Project
  Authors（2022）、Space Time（2025）
- SHA256：字体 `a4fc642d821671b1a2937b9a52d398b96cf0b1e1da758846ee1ff38a297b22a5`、
  OFL 文本 `6728aae70e0be6316b28681c5a806827b4d7daafe45fb767b932c790216c2533`
  （来源包与完整清单见 PROVENANCE.md「字体」小节）

---

## 红线声明（对协作者与 AI 的约束）

1. 严禁将上述任何项目的源码（C/C++/汇编）复制进本项目第一方源码——GPL 传染
   将迫使整个应用开源。允许的只有：**调用其独立二进制**、**阅读其公开资料学习
   机制与参数**、**在文档中注明思路参考**。
2. 若未来需要修改上述 GPL 组件的行为，必须 fork 其源码单独修改并公开，
   保持其独立分发，而不是把补丁合入本项目源码。
3. 本文件与 PROVENANCE.md 共同构成项目的原创性合规基线，改动需经项目所有者确认。

## Termux terminal-emulator / terminal-view / libtermux JNI

- 来源：https://github.com/termux/termux-app （v0.119.0-beta.3，模块 terminal-emulator 与 terminal-view）
- 许可证：**Apache-2.0**（源码级聚合，随本仓库分发，版权头保留）
  > ⚠️ **2026-10-05 勘误**：此前此处误标为 GPL-3.0。上游 `termux/termux-app` 仓库**整体**是
  > GPLv3-only，但其 `LICENSE.md` 明确列出例外：**`terminal-view` 与 `terminal-emulator` 两个库
  > 为 Apache-2.0**（源自 jackpal/Android-Terminal-Emulator）。本项目聚合的正是这两个库，
  > **并未聚合 GPL 的主应用本体**。GPL 会传染、Apache-2.0 不会——这条直接决定第一方代码能否闭源，
  > 属不可出错项。完整依据见 `docs/ERRATA.md` **E-001** 与 `PROVENANCE.md`。
- 用途：终端模拟引擎（VT-100/xterm 状态机）与原生终端视图（替代 WebView/xterm.js 架构）
- 修改：包内 R 引用改为宿主应用 R；textselection 把手资源并入宿主 res；其余未改动

