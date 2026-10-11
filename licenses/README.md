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

> 📌 `BSD-3-Clause.txt` 是上游 `LICENSE` 的**原文照录**：其第三条里的 `{organization}` 是
> **上游自己没填的占位符**（见 <https://github.com/termux/libandroid-shmem> 的 `LICENSE`）。
> 本项目刻意**不改动正文**——改了就与上游原文不一致，SHA256 也不再是「逐字节可核」的那一份。
> 该条要求的是「不得用本项目（作者）的名义为衍生品背书」，本项目也确实没有这样做。

### 不放在本目录、但同样是「随附正文」的两份

| 正文 | 位置 | 覆盖 | 字节数 | 行数 | SHA256（小写） |
|---|---|---|---|---|---|
| GNU GPL v3.0 | 仓库根 [`LICENSE`](../LICENSE) | **本项目自身**的许可证；同时满足 LGPL-3.0 第 2 条「并入 GPL-3.0」的要求 | 35,149 | 674 | `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986` |
| SIL OFL 1.1 | `app/src/main/assets/fonts/OFL.txt` | JetBrains Maple Mono（终端默认字体） | 4,572 | 95 | `6728aae70e0be6316b28681c5a806827b4d7daafe45fb767b932c790216c2533` |

> ⚠️ **上表的字节数与 SHA256 一律以「仓库内形态」为准（即 git 索引/blob 里的 LF 换行）。**
> 本仓 `core.autocrlf=true` ＋ `.gitattributes` 的 `* text=auto` 会让 Windows 工作区检出成 CRLF
> （同一份 `OFL.txt`：仓库形态 4,572 B，Windows 工作区形态 4,666 B），**照着工作区算出来的摘要会与上表不符**。
> 现已为这六份文本固定 `eol=lf`（`licenses/*.txt`、根 `LICENSE`、`app/src/main/assets/fonts/OFL.txt`），
> 任何平台检出后直接校验都能对上；对不上就先跑 `git ls-files --eol` 看属性是否生效，
> **不要改上表的数字去迁就工作区**。二进制资产（字体 ttf）不受换行影响。
> （2026-10-11 更正：此前一度把 `OFL.txt` 写成 4,666 B / `9e34ed24…`，那是 CRLF 工作区形态，见 `docs/ERRATA.md` E-104 补记。）

> 字体那份**故意留在字体同目录**：OFL 要求「随字体一起分发」的惯例是文本与字体同处一地，
> 而它在本项目里不是「第三方组件目录」而是 assets 资源。

### 不翻印的

Maven 依赖（OkHttp / Compose / AndroidX / zstd-jni 等）的许可证文本**随各自构件分发**
（AAR/JAR 内的 `META-INF/LICENSE*`），本项目不另存副本——聚合的是构件本身，正文就在里面。

### 这几份正文也随 APK 分发（2026-10-11 起）

上表里 `licenses/` 的四份 ＋ 仓库根 `LICENSE` ＋ 字体 `OFL.txt`，构建期会被原样复制进 APK 的
`assets/licenses/`（`app/build.gradle.kts` 的 `stageLicenseAssets` 任务，由 `preDebugBuild` /
`preReleaseBuild` 拉起来）⇒ **只拿到安装包、不联网的人也能读到全部正文**。应用内入口 = 设置页
「关于」→「**开源许可**」（`app/src/main/java/com/example/zhengdao/ui/OpenSourceLicensesActivity.kt`）。

> **git 里只留这一份正文**，随包那份是构建期拷出来的 —— 免得两份副本漂移之后，
> 上表的 SHA256 与「随包正文」各说各话（这正是 E-104 的教训）。

**换行**：装配时一律**归一成 LF**（`app/build.gradle.kts` 的 `normalizeToLf`），所以随包正文与上表的
字节数 / SHA256 逐字节一致 —— 与构建机是 Windows 还是 Linux、`core.autocrlf` 怎么设都无关。
若你本机的**老检出**里这几份还是 CRLF（`.gitattributes` 的 `eol=lf` 只对新检出 / 被重写的文件生效），
`Get-FileHash` 会得到另一组值：那是本地检出陈旧，不是上表错了 —— 删掉该文件重新检出即变 LF。

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

### 不依赖工作区换行的校准命令

```bash
cd <仓库根>
git cat-file blob HEAD:licenses/Apache-2.0.txt | sha256sum     # 与上表逐一比对
```

直接 `sha256sum licenses/*.txt` 也对得上——前提是 `.gitattributes` 的 `eol=lf` 生效（`git ls-files --eol` 可证）。

### 包内正文核对（不必装包）

```powershell
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = Get-ChildItem app\build\outputs\apk\debug\*.apk | Select-Object -First 1
$z = [IO.Compression.ZipFile]::OpenRead($apk.FullName)
$z.Entries | Where-Object FullName -like 'assets/licenses/*' |
    ForEach-Object { "{0,8}  {1}" -f $_.Length, $_.FullName }
$z.Dispose()
```

期望看到 `assets/licenses/` 下五份 `.txt`（Apache-2.0 / BSD-3-Clause / GPL-2.0 / GPL-3.0 / LGPL-3.0）
与本 README，字节数与上表一致；`assets/fonts/OFL.txt` 也在包里（字体那份按 OFL 惯例与字体同目录）。
**少一份就是构建期装配出了问题**：`stageLicenseAssets` 末尾有 `check` 断言，构建会直接失败。

## 三、维护规则（改动需经项目所有者确认）

1. **新增随包分发的第三方组件** ⇒ 先确认它要求哪几份正文，正文放进本目录，**同步更新上表**
   （来源 URL、取回日期、字节数、行数、SHA256），并回到 `THIRD-PARTY-LICENSES.md` 的对照表加一行。
2. **正文一律取官方原文**，不自行排版、不改换行、不加 BOM（`Apache-2.0.txt` 首行是空行，
   即官方原文形态，保持原样）。
3. **升级组件版本** ⇒ 复核其许可证是否变更；换正文则同步更新 SHA256。
4. 本目录只放**许可证正文**，不放第三方源码，也不放声明性文档（那些在根目录两份文档里）。
