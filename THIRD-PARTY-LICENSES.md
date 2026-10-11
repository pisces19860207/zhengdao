# 第三方组件声明（THIRD-PARTY NOTICES）

本应用按「聚合分发（mere aggregation）」方式携带以下独立的第三方二进制组件。
它们与本项目第一方代码**不链接、不混合**：App 运行时将其释放为独立可执行文件并
以独立进程方式调用。本项目的 Kotlin/C 源码中**不含任何来自下列项目的代码**。

> ### 许可证正文随仓库与 APK 分发（2026-10-11 补齐）
>
> 各组件需要哪几份正文、正文在哪：
>
> | 组件 | 许可证 | 随附正文 |
> |---|---|---|
> | Termux `terminal-emulator` / `terminal-view`（本文末节） | Apache-2.0 | [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)（第 4(a) 条「给接收者一份副本」） |
> | proot（§1） | GPL-2.0-or-later | [`licenses/GPL-2.0.txt`](licenses/GPL-2.0.txt) |
> | libtalloc（§2） | LGPL-3.0-or-later | [`licenses/LGPL-3.0.txt`](licenses/LGPL-3.0.txt) ＋ 仓库根 [`LICENSE`](LICENSE)（GPL-3.0 全文；LGPL-3.0 第 2 条并入 GPL-3.0） |
> | libandroid-shmem（§3） | BSD-3-Clause | [`licenses/BSD-3-Clause.txt`](licenses/BSD-3-Clause.txt) |
> | JetBrains Maple Mono（§6） | SIL OFL 1.1 | `app/src/main/assets/fonts/OFL.txt`（随字体同目录，上游原文件） |
>
> - 每份正文的**来源 URL、取回日期、字节数与 SHA256** 见 [`licenses/README.md`](licenses/README.md)。
> - 这些正文**随分发物提供**：构建期（`app/build.gradle.kts` 的 `stageLicenseAssets`）把它们拷贝进
>   APK 的 `assets/licenses/`，应用内入口 = 设置页「关于」→「**开源许可**」（离线可读全文，2026-10-11 晚起）。
> - Maven 依赖（OkHttp / Compose / AndroidX / zstd-jni 等）的许可证文本**随各自构件分发**
>   （AAR/JAR 内的 `META-INF/LICENSE*`），本项目不另行翻印。
> - 本文件与 `PROVENANCE.md`、`docs/闭源切换清单-2026-10-11.md` 三份文件如有冲突，
>   **以本文件与 `PROVENANCE.md` 为准**（它们是取证现场）。

## 1. proot（Termux fork 发行版）

- 用途：免 root 用户空间环境（guest Linux 的加载器）
- 版本：5.1.107.96（Termux 官方仓库 aarch64 构建产物）
- 许可证：GPL-2.0-or-later（含其内嵌 loader 组件）
- 来源：<https://github.com/termux/proot>（源码即该仓库）
- **随附正文**：`licenses/GPL-2.0.txt`
- **官方产物摘要（可复现锚点，2026-10-11 取自 Termux 官方 apt 索引
  <https://packages.termux.dev/apt/termux-main/dists/stable/main/binary-aarch64/Packages>，
  索引快照 1,863,706 B）**：`proot_5.1.107.96_aarch64.deb`
  SHA256 = `8199dca06dccb693ec09fb1759e3e1ad08b4863f0c11c612f89c20bd9ecdc1a0`
- 源码可得性：本仓库分发其二进制的同时，以指向上述官方源码仓库的方式满足
  GPL 第 3 条的源码提供要求；官方 `.deb` 摘要见上（可唯一确定取的是哪一份官方产物）；
  若对二进制有任何修改，将一并公开对应源码。
- ⚠️ **构建 commit 仍缺（已知开放项）**：Termux 官方 apt 索引**不发布构建 commit**
  （官方产物由 `termux/termux-packages` 的 CI 产出），按 `packages/proot/build.sh` 版本号
  回查历史尚未做。当前可复现锚点 = **版本号 + 官方 `.deb` SHA256 + 索引快照日期
  （2026-10-11）**；见 `docs/闭源切换清单-2026-10-11.md` 第四节。
- 本项目对它的使用方式：仅作为独立可执行文件调用（execve），未链接、未修改。

## 2. libtalloc（Termux 发行版）

- 用途：上述 proot 的运行时依赖
- 版本：2.5.0（Termux 官方仓库 aarch64 构建产物）
- 许可证：**LGPL-3.0-or-later**（talloc 上游）
- 来源：上游 <https://www.samba.org/ftp/talloc/>（本项目取的是 Termux 官方构建产物，
  包元数据主页 <https://talloc.samba.org/talloc/doc/html/index.html>）
- **随附正文**：`licenses/LGPL-3.0.txt` ＋ 仓库根 `LICENSE`（GPL-3.0 全文）
- **官方产物摘要**：`libtalloc_2.5.0_aarch64.deb`
  SHA256 = `556591f43bb773ad8777e1a29522640866a55f95dab71914418b94a8c58ad5a7`
- ⚠️ **使用形态（2026-10-11 核实，此前只写了「动态库方式独立分发」）**：
  `app/src/main/assets/proot/` 里它是**独立的 ELF 动态库**（`libtalloc.so` 33,592 B，
  运行时释放为 `files/termux-proot/libtalloc.so.2`），**由 proot 进程在进程内动态加载**
  ⇒ 符合 LGPL「以共享库方式链接、接收者可自行替换该 `.so`」的形态，但它确实运行在
  proot 进程之内，**不受「聚合不传染」那条判据保护**，LGPL 的随附义务必须履行。
- **源码可得性**：① 上游 samba.org 公开发布 talloc 源码；② Termux 官方源提供对应源码包
  （`apt-get source libtalloc`，或 packages.termux.dev 的 `pool/main/libt/libtalloc/`）。
  若需本项目随分发物直接提供源码副本，依 GPL/LGPL 第 3 条向仓库维护者索取。

## 3. libandroid-shmem（Termux 发行版）

- 用途：上述 proot 的运行时依赖（System V 共享内存的 Android 适配层）
- 版本：0.7（Termux 官方仓库 aarch64 构建产物）
- 许可证：**BSD-3-Clause**（2026-10-11 起钉死；此前写作「见 Termux 仓库对应包元数据
  （Apache-2.0 / BSD 类宽松许可）」——不精确）。上游
  <https://github.com/termux/libandroid-shmem> 的 `LICENSE`：Copyright (c) 2013,
  Sergii Pylypenko；Copyright (c) 2017, Fredrik Fornwall；第三条为
  「Neither the name of the {organization} nor the names of its contributors…」
  （`{organization}` 占位符为上游原文照录）。
- 来源：<https://github.com/termux/libandroid-shmem>（本项目取的是 Termux 官方构建产物）
- **随附正文**：`licenses/BSD-3-Clause.txt`
- **官方产物摘要**：`libandroid-shmem_0.7_aarch64.deb`
  SHA256 = `0da3a24d558b93c92bcf8d611e0826a99ff96e396b148e6cdf33b47c47c57ff6`
- 使用形态：同样是**独立的 ELF 动态库**（`libandroid-shmem.so` 14,432 B → 运行时释放为
  `files/termux-proot/libandroid-shmem.so`），在 proot 进程内加载。
- 义务：BSD-3-Clause 只要求在二进制分发物中**复现版权声明、条件列表与免责声明**，
  **没有源码要约义务**（已由 `licenses/BSD-3-Clause.txt` 履行）。

## 4. xterm.js / @xterm/addon-fit

- 用途：内嵌终端渲染（assets/terminal/ 下为其官方发行文件）
- 许可证：MIT
- 来源：<https://github.com/xtermjs/xterm.js>

## 5. zstd（zstd-jni 附带的原生库）

- 用途：RootFS 压缩包解压
- 许可证：BSD-3-Clause（双许可中的 BSD 分支）
- 来源：<https://github.com/facebook/zstd>（经 zstd-jni 官方 AAR 分发）
- 随附正文：随 AAR 构件内的 `META-INF` 分发，本项目不另行翻印（见文首说明）。

## 6. JetBrains Maple Mono（终端默认字体）

- 用途：终端默认正文字体（`app/src/main/assets/fonts/JetBrainsMapleMono-NF-Regular.ttf`，
  运行时由 `TerminalPrefs.typeface()` 经 `Typeface.createFromAsset` 载入）
- 许可证：**SIL Open Font License 1.1**；OFL 全文随字体同目录分发
  （`app/src/main/assets/fonts/OFL.txt`，即上游 zip 内的 `LICENSE.txt`）
- 来源：<https://github.com/SpaceTimee/Fusion-JetBrainsMapleMono> release `1.2304.79`
  的 `JetBrainsMapleMono-NF-XX-XX-XX.zip`（NF = Nerd Font 图标、XX = 不窄保 2:1、
  保留连字、未 hint）
- 改动情况（**2026-10-11 更正**）：**已由本项目子集化**（工具 `tools/subset-terminal-font.py`，
  范围 = GB2312 常用汉字 6,763 字 ＋ 终端必备区 ＋ Nerd Font 图标区），随包字体因此**不是**上游整包
  （现 6,773,564 B）。**OFL 1.1 允许修改与再分发，子集化产物仍受 OFL 1.1 约束**：本项目保留
  `OFL.txt` 全文与版权行、未改为暗示上游背书的字体名。入库时文件名也改为带 `NF` 的形式
  （上游 zip 内平铺文件名为 `JetBrainsMapleMono-Regular.ttf`，NF 是整包变体）
- 版权（`OFL.txt` 首三行）：JetBrains Mono Project Authors（2020）、Maple Mono Project
  Authors（2022）、Space Time（2025）
- SHA256：字体（子集化后）`10c8ea51ab6bb2df9a615424df1054ad6b3c08ad60a47e8bb3368039b92b5089`、
  OFL 文本 `6728aae70e0be6316b28681c5a806827b4d7daafe45fb767b932c790216c2533`（4,572 B，仓库内 LF 形态 = 上游原文件；Windows 检出为 4,666 B，见 `docs/ERRATA.md` E-104 补记）
  （来源包与完整清单见 PROVENANCE.md「字体」小节）

## 7. Debian GNU/Linux 13.7 base（guest 系统层 rootfs）

- 用途：PRoot 加载的 guest 用户空间**系统层**（终端里那个 rootfs）
- 版本：Debian GNU/Linux **13.7（trixie）**，aarch64
  （2026-10-11 真机核对：`/etc/os-release` = `PRETTY_NAME="Debian GNU/Linux 13 (trixie)"`、
  `VERSION_ID="13"`、`VERSION_CODENAME=trixie`；`/etc/debian_version` = `13.7`）
- **构建来源（2026-10-11 补记）**：本仓库 `rootfs/build-rootfs.sh`
  （`DEBIAN_RELEASE="trixie"`、`DEBIAN_VERSION="13.7"`；`debootstrap` 主动构建，
  x86_64 构建机走 `qemu-user-static` 两阶段交叉；构建期删减见该脚本 `:363`）⇒ 发布为
  Release 资产 `debian-13.7-base-arm64.tar.zst`（约 229 MB，zstd；另有增量补丁与
  Ed25519 签名索引）。镜像内包清单与逐文件摘要由 `tools/rootfs-manifest.py` 写入
  `rootfs-manifest.txt`（每行 `type` / `mode(%04o)` / `size` / `digest` / `path`）。
  ⚠️ **Release 资产本身的摘要（索引里的 sha256）尚未抄进本文件，待补。**
- 许可证：**Debian 是数百个独立软件包的聚合发行版，各包许可互不相同**
  （GPL-2.0/3.0、LGPL、BSD、MIT、Apache-2.0、Artistic 等）。镜像内实测装了 **353 个包**
  （2026-10-11 真机 `grep -c '^Package:' /var/lib/dpkg/status`）
- ⚠️ **本环境为控制体积，在构建时删除了 `/usr/share/doc`、`/usr/share/man`、`/usr/share/info`
  （`rootfs/build-rootfs.sh:363`）** ⇒ 镜像内**不含**逐包版权文本
  （真机核对：`/usr/share/doc` 只剩 4 个构建后才装的条目）。
  逐包 copyright 的替代获取途径（任一即可）：
  1. `http://deb.debian.org/debian`（镜像内 `sources.list` 即指向此处的 `trixie main`）
     取同版本包，包内自带 `usr/share/doc/<包>/copyright`；
  2. `apt-get source <包>`，读源码包里的 `debian/copyright`；
  3. 历史/已下架版本用 <https://snapshot.debian.org/>。
- **待核（诚实标注，不是缺陷）**：*Debian rootfs 所含各包的完整许可证清单及源码获取方式，
  待逐包核查后补充。* 当前口径：各包许可证以 Debian 官方仓库同版本包内的
  `debian/copyright` 为准，本项目**未逐包点数**（rootfs 为 200 MB 级、353 个包）。
- 源码要约：本仓库对**自己分发**的 GPL 组件（proot 等，见 §1）以指向官方源码仓库的方式满足
  GPL 第 3 条；系统层里各 GPL/AGPL 组件的源码，由 Debian 官方源按上一条途径提供。
- 改动情况：构建脚本对系统层只做**删减**（文档 / man / info / 多余 locale / 构建残留）与少量
  **配置文件写入**（`/etc/resolv.conf`、`/etc/hosts`、`/etc/uv/uv.toml`、
  `/etc/profile.d/zhengdao-uv.sh`、`/etc/localtime`、apt 源），**未修改任何包的二进制**；
  各包功能与上游同版本一致，卸载/升级仍走 `apt`。

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
- **Apache-2.0 义务逐条对照（2026-10-11 补）**：
  - **4(a) 给接收者一份许可证副本** ⇒ [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)。
    本次补齐前，**仓库内没有任何 Apache-2.0 正文**（唯一另一个许可证文件是字体的 OFL）。
  - **4(b) 修改须显著标注** ⇒ 12 个衍生文件逐个带文件头修改通知
    （逐文件修改点见 `PROVENANCE.md`「Apache-2.0 修改通知」一节、`docs/ERRATA.md` E-100）。
  - **4(c) 保留版权、专利、商标与归属声明** ⇒ 未移除上游文件内的任何声明
    （这 12 个上游文件原本就没有许可证头，故「保留」体现为不移除、并新增我们的修改通知）。
  - **4(d) NOTICE 文件** ⇒ **不适用**：上游 `termux-app` 的 v0.119.0-beta.3 快照里只有根
    `LICENSE.md` 与 `termux-shared/LICENSE.md`，**没有 NOTICE 文件**（2026-10-11 核对）。
  - 上游例外依据原文（`termux-app/LICENSE.md`）：「Terminal Emulator for Android code is used
    which is released under Apache 2.0 license. Check `terminal-view` and `terminal-emulator`
    libraries.」
