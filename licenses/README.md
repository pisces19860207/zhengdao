# 许可证正文（licenses/）

> **这个目录是什么**：本仓库**随分发物提供**的第三方许可证正文。凡是「聚合分发」进本项目的组件，
> 其许可证要求的**文本副本**放在这里，逐份可核（Apache-2.0 第 4(a) 条、GPL/LGPL 第 3 条、
> BSD-3-Clause 的「复现声明」要求）。
> **谁需要哪份正文**：见仓库根 [`THIRD-PARTY-LICENSES.md`](../THIRD-PARTY-LICENSES.md) 文首的对照表。
> **原始登记**（来源、版本、逐文件修改、红线）见仓库根 [`PROVENANCE.md`](../PROVENANCE.md)。

## 一、正文清单（2026-10-11 取回并固化）

| 文件 | 许可证 | 覆盖的组件 | 来源 | 取回日期 | 字节数 | 行数 | SHA256（小写） |
|---|---|---|---|---|---|---|---|
| [`Apache-2.0.txt`](Apache-2.0.txt) | Apache License 2.0 | Termux `terminal-emulator` / `terminal-view` / `libtermux`（以及随 APK 分发的 Apache-2.0 Maven 依赖） | <https://www.apache.org/licenses/LICENSE-2.0.txt> | 2026-10-11 | 11,358 | 202 | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |
| [`GPL-2.0.txt`](GPL-2.0.txt) | GNU GPL v2.0 | proot（Termux fork 发行版，`app/src/main/assets/proot/`） | <https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt> | 2026-10-11 | 17,984 | 338 | `edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6` |
| [`LGPL-3.0.txt`](LGPL-3.0.txt) | GNU LGPL v3.0 | libtalloc（在 proot 进程内动态加载） | <https://www.gnu.org/licenses/lgpl-3.0.txt> | 2026-10-11 | 7,652 | 165 | `e3a994d82e644b03a792a930f574002658412f62407f5fee083f2555c5f23118` |
| [`BSD-3-Clause.txt`](BSD-3-Clause.txt) | BSD 3-Clause | libandroid-shmem（在 proot 进程内动态加载） | <https://github.com/termux/libandroid-shmem>（`LICENSE` 原样照录） | 2026-10-11 | 1,529 | 28 | `0dd43abe17196b0224cb0b159819af798cd508a82a21e5870126fff4e899a7eb` |

### 不放在本目录、但同样是「随附正文」的两份

| 正文 | 位置 | 覆盖 | 字节数 | 行数 | SHA256（小写） |
|---|---|---|---|---|---|
| GNU GPL v3.0 | 仓库根 [`LICENSE`](../LICENSE) | **本项目自身**的许可证；同时满足 LGPL-3.0 第 2 条「并入 GPL-3.0」的要求 | 35,823 | 674 | `230184f60bae2feaf244f10a8bac053c8ff33a183bcc365b4d8b876d2b7f4809` |
| SIL OFL 1.1 | `app/src/main/assets/fonts/OFL.txt` | JetBrains Maple Mono（终端默认字体） | 4,666 | 95 | `9e34ed245e14e5986fb693e043c9ab36493a9b3b1e624e9b3073e89a512570a7` |

> 字体那份**故意留在字体同目录**：OFL 要求「随字体一起分发」的惯例是文本与字体同处一地，
> 而它在本项目里不是「第三方组件目录」而是 assets 资源。

### 不翻印的

Maven 依赖（OkHttp / Compose / AndroidX / zstd-jni 等）的许可证文本**随各自构件分发**
（AAR/JAR 内的 `META-INF/LICENSE*`），本项目不另存副本——聚合的是构件本身，正文就在里面。

## 二、校验（一条命令）

```bash
cd <仓库根>
sha256sum licenses/*.txt                       # 与上表逐一比对
sha256sum LICENSE app/src/main/assets/fonts/OFL.txt
```

Windows PowerShell 等价：

```powershell
Get-FileHash licenses\*.txt, LICENSE, app\src\main\assets\fonts\OFL.txt -Algorithm SHA256
```

## 三、维护规则（改动需经项目所有者确认）

1. **新增随包分发的第三方组件** ⇒ 先确认它要求哪几份正文，正文放进本目录，**同步更新上表**
   （来源 URL、取回日期、字节数、行数、SHA256），并回到 `THIRD-PARTY-LICENSES.md` 的对照表加一行。
2. **正文一律取官方原文**，不自行排版、不改换行、不加 BOM（`Apache-2.0.txt` 首行是空行，
   即官方原文形态，保持原样）。
3. **升级组件版本** ⇒ 复核其许可证是否变更；换正文则同步更新 SHA256。
4. 本目录只放**许可证正文**，不放第三方源码，也不放声明性文档（那些在根目录两份文档里）。
