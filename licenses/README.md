# 许可证正文（licenses/）

> **这个目录是什么**：本仓库**随分发物提供**的第三方许可证正文。凡是「聚合分发」进本项目的组件，
> 其许可证要求的**文本副本**放在这里，逐份可核（Apache-2.0 第 4(a) 条、GPL/LGPL 第 3 条、
> BSD 系（2/3-Clause）的「复现声明」要求）。Apache-2.0 第 4(d) 条要求的 **NOTICE 归属声明**
> 也在这里（`NOTICES.txt`，照录上游 NOTICE 原文）。
> **谁需要哪份正文**：见仓库根 [`THIRD-PARTY-LICENSES.md`](../THIRD-PARTY-LICENSES.md) 文首的对照表。
> **原始登记**（来源、版本、逐文件修改、红线）见仓库根 [`PROVENANCE.md`](../PROVENANCE.md)。

## 一、正文清单（2026-10-11 取回并固化）

| 文件 | 许可证 | 覆盖的组件 | 来源 | 取回日期 | 字节数 | 行数 | SHA256（小写） |
|---|---|---|---|---|---|---|---|
| [`Apache-2.0.txt`](Apache-2.0.txt) | Apache License 2.0 | Termux `terminal-emulator` / `terminal-view` / `libtermux`（以及随 APK 分发的 Apache-2.0 Maven 依赖） | <https://www.apache.org/licenses/LICENSE-2.0.txt> | 2026-10-11 | 11,358 | 202 | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |
| [`GPL-2.0.txt`](GPL-2.0.txt) | GNU GPL v2.0 | proot（Termux fork 发行版，`app/src/main/assets/proot/`） | <https://www.gnu.org/licenses/old-licenses/gpl-2.0.txt> | 2026-10-11 | 17,984 | 338 | `edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6` |
| [`LGPL-3.0.txt`](LGPL-3.0.txt) | GNU LGPL v3.0 | libtalloc（在 proot 进程内动态加载） | <https://www.gnu.org/licenses/lgpl-3.0.txt> | 2026-10-11 | 7,652 | 165 | `e3a994d82e644b03a792a930f574002658412f62407f5fee083f2555c5f23118` |
| [`BSD-2-Clause.txt`](BSD-2-Clause.txt) | BSD 2-Clause | zstd-jni（`com.github.luben:zstd-jni:1.5.6-4`，随包 native 库 `lib/arm64-v8a/libzstd-jni-1.5.6-4.so` 600,064 B） | <https://github.com/luben/zstd-jni>（v1.5.6-4 的 `LICENSE` 原样照录） | 2026-10-11 | 1,360 | 26 | `c6a6a8926f2f1732603e4b75779a5f6ce79238eb79bc36dcc5b7feb509bc6cd9` |
| [`BSD-3-Clause.txt`](BSD-3-Clause.txt) | BSD 3-Clause | libandroid-shmem（在 proot 进程内动态加载） | <https://github.com/termux/libandroid-shmem>（`LICENSE` 原样照录） | 2026-10-11 | 1,529 | 28 | `0dd43abe17196b0224cb0b159819af798cd508a82a21e5870126fff4e899a7eb` |
| [`BSD-3-Clause-zstd.txt`](BSD-3-Clause-zstd.txt) | BSD 3-Clause | **zstd 本体**（`facebook/zstd` v1.5.6，编译进随包 native 库 `libzstd-jni-1.5.6-4.so`） | <https://github.com/facebook/zstd>（v1.5.6 的 `LICENSE` 原样照录） | 2026-10-11 | 1,549 | 30 | `7055266497633c9025b777c78eb7235af13922117480ed5c674677adc381c9d8` |
| [`NOTICES.txt`](NOTICES.txt) | 第三方 NOTICE 汇总（**不是**许可证全文） | Apache Commons Compress 的 `META-INF/NOTICE.txt`（178 B）＋ OkHttp 内置 Public Suffix List 数据的 `publicsuffix/NOTICE`（218 B） | 两份均照录各自构件内的 NOTICE 原文（路径与原文见文件内） | 2026-10-11 | 2,198 | 36 | `3ef191d960df2fd2f38120d9fb2da062491348a6fc6cb175d2eb6d9d8eea1ba4` |

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
> 现已为这些文本固定 `eol=lf`（`licenses/*.txt` 整个目录、根 `LICENSE`、`app/src/main/assets/fonts/OFL.txt`），
> 任何平台检出后直接校验都能对上；对不上就先跑 `git ls-files --eol` 看属性是否生效，
> **不要改上表的数字去迁就工作区**。二进制资产（字体 ttf）不受换行影响。
> （2026-10-11 更正：此前一度把 `OFL.txt` 写成 4,666 B / `9e34ed24…`，那是 CRLF 工作区形态，见 `docs/ERRATA.md` E-104 补记。）

> 字体那份**故意留在字体同目录**：OFL 要求「随字体一起分发」的惯例是文本与字体同处一地，
> 而它在本项目里不是「第三方组件目录」而是 assets 资源。

### 依赖正文怎么覆盖（2026-10-11 晚更正，原「不翻印的」一句已被实测推翻）

原先这里写的是「Maven 依赖（OkHttp / Compose / AndroidX / zstd-jni 等）的许可证文本**随各自构件分发**
（AAR/JAR 内的 `META-INF/LICENSE*`），本项目不另存副本」。**实测这句话不成立**：

- **Android 打包默认剥掉构件顶层的 `META-INF/LICENSE*` / `NOTICE*`**：`commons-compress` 构件里明明有
  `META-INF/LICENSE.txt`（11,357 B，Apache-2.0 全文）与 `META-INF/NOTICE.txt`（178 B），**APK 里一个都没有**；
  留下来的只有**带子目录的**（AGP 合并出的 `META-INF/androidx/<组>/<构件>/LICENSE.txt` × 5）与 okhttp 的
  `okhttp3/internal/publicsuffix/NOTICE` —— 留不留是打包细节，不是承诺。
- 因此本项目**自己随包给足**：运行时依赖（136 个已解析模块）声明的许可证里，Apache-2.0 与 Public Domain
  由 [`Apache-2.0.txt`](Apache-2.0.txt) 与登记覆盖；**zstd-jni 的 BSD 2-Clause** 见
  [`BSD-2-Clause.txt`](BSD-2-Clause.txt)、**它编译进去的 zstd 本体的 BSD 3-Clause** 见
  [`BSD-3-Clause-zstd.txt`](BSD-3-Clause-zstd.txt)（这两份都是**本次补的**：同一个 `.so` 里
  有两份版权人不同的 BSD 文本，谁也不能替谁履行）；需要在分发物里复现的归属声明
  （Apache-2.0 第 4(d) 条）见 [`NOTICES.txt`](NOTICES.txt)。
- 逐组「依赖 → 声明许可证 → 覆盖方式」对照表在仓库根
  [`THIRD-PARTY-LICENSES.md`](../THIRD-PARTY-LICENSES.md) **§8**（含重新生成它的命令与两个坑）。
- 完整取证与教训：`docs/ERRATA.md` **E-105**。

### 这几份正文也随 APK 分发（2026-10-11 起）

上表里 `licenses/` 的每一份（六份许可证正文 ＋ NOTICE 汇总 ＋ 本 README）＋ 仓库根 `LICENSE` ＋ 字体 `OFL.txt`，构建期会被原样复制进 APK 的
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

期望看到 `assets/licenses/` 下**八份 `.txt`**（Apache-2.0 / BSD-2-Clause / BSD-3-Clause /
BSD-3-Clause-zstd / GPL-2.0 / GPL-3.0 / LGPL-3.0 / NOTICES）与本 README，字节数与上表一致；
`assets/fonts/OFL.txt` 也在包里
（字体那份按 OFL 惯例与字体同目录）。
**少一份就是构建期装配出了问题**：`stageLicenseAssets` 末尾有 `check` 断言，构建会直接失败。

## 三、维护规则（改动需经项目所有者确认）

1. **新增随包分发的第三方组件** ⇒ 先确认它要求哪几份正文，正文放进本目录，**同步更新上表**
   （来源 URL、取回日期、字节数、行数、SHA256），并回到 `THIRD-PARTY-LICENSES.md` 的对照表加一行、
   **§8 的依赖表加/改组**；同时把文件名补进 `app/build.gradle.kts` 的 `stageLicenseAssets` 断言。
   > 加表行后**必须同步改 `app/src/test/java/com/example/zhengdao/LicenseTextsTest.kt` 里的记录数**
   > （它断言本文件两张表解析出的正文份数恰好等于实际份数，加一份就要 +1）。
2. **正文一律取官方原文**，不自行排版、不改换行、不加 BOM（`Apache-2.0.txt` 首行是空行，
   即官方原文形态，保持原样）。
3. **升级组件版本** ⇒ 复核其许可证是否变更；换正文则同步更新 SHA256。
4. 本目录只放**随分发物提供的正文**（许可证全文、以及照录的上游 NOTICE 归属声明），
   不放第三方源码；解释性文档在根目录两份文档里。`NOTICES.txt` 属于前者：
   它装的是**上游 NOTICE 的原文照录**，不是我们自己写的说明（说明写在文件开头的注释里，且不参与 SHA256 之外的解释）。
