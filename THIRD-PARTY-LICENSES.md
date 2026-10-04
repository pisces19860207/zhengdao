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

---

## 红线声明（对协作者与 AI 的约束）

1. 严禁将上述任何项目的源码（C/C++/汇编）复制进本项目第一方源码——GPL 传染
   将迫使整个应用开源。允许的只有：**调用其独立二进制**、**阅读其公开资料学习
   机制与参数**、**在文档中注明思路参考**。
2. 若未来需要修改上述 GPL 组件的行为，必须 fork 其源码单独修改并公开，
   保持其独立分发，而不是把补丁合入本项目源码。
3. 本文件与 PROVENANCE.md 共同构成项目的原创性合规基线，改动需经项目所有者确认。
