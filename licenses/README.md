# 许可证正文（licenses/）

> **这个目录是什么**：本仓库**随分发物提供**的第三方许可证正文。凡是「聚合分发」进本项目的组件，
> 其许可证要求的**文本副本**放在这里，逐份可核（Apache-2.0 第 4(a) 条、GPL/LGPL 第 3 条、
> BSD 系（2/3-Clause）的「复现声明」要求）。Apache-2.0 第 4(d) 条要求的 **NOTICE 归属声明**
> 也在这里（`NOTICES.txt`，照录上游 NOTICE 原文）。
> **谁需要哪份正文**：见仓库根 [`THIRD-PARTY-LICENSES.md`](../THIRD-PARTY-LICENSES.md) 文首的对照表。
> **原始登记**（来源、版本、逐文件修改、红线）见仓库根 [`PROVENANCE.md`](../PROVENANCE.md)。

## 一、当前随包的正文（2026-10-28 重定：只留「包里真有这个组件」的）

> **判据只有一条**：当前 APK 里有这个组件吗？有 ⇒ 必须随包；没有 ⇒ 不随包、但正文留档（见第二节）。
> 2026-10-28 第二百六十六批按这条把随包清单从 8 份收到 **4 份**（proot / libtalloc / libandroid-shmem /
> zstd-jni 的正文全部退出随包）。依据：终端已整体搬去 `zhengdao-term`，本仓 `assets/` 只剩 `baopu/`。

| 文件 | 许可证 | 覆盖的组件 | 来源 | 取回日期 | 字节数 | 行数 | SHA256（小写） |
|---|---|---|---|---|---|---|---|
| [`Apache-2.0.txt`](Apache-2.0.txt) | Apache License 2.0 | 随包的 Apache-2.0 Maven 依赖（Compose / AndroidX / OkHttp / kotlinx / mikepenz / jspecify 等；逐组对照见仓库根 [`THIRD-PARTY-LICENSES.md`](../THIRD-PARTY-LICENSES.md) §8）。Termux 那三个组件（`terminal-emulator` / `terminal-view` / `libtermux`）已随终端搬去 `zhengdao-term`，本仓不再聚合 | <https://www.apache.org/licenses/LICENSE-2.0.txt> | 2026-10-11 | 11,358 | 202 | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |
| [`BSD-3-Clause-zstd.txt`](BSD-3-Clause-zstd.txt) | BSD 3-Clause | **zstd 本体**（`facebook/zstd` v1.5.6）—— 由 Rust 核**静态编进**随包 native 库 `lib/arm64-v8a/libzhengdao_core.so`（2026-10-28 实测 .so 内含 `zstd-safe-7.3.0/src/lib.rs` 路径串与 `ZSTD_trace_decompress_*` 符号 ⇒ 不是推断） | <https://github.com/facebook/zstd>（v1.5.6 的 `LICENSE` 原样照录） | 2026-10-11 | 1,549 | 30 | `7055266497633c9025b777c78eb7235af13922117480ed5c674677adc381c9d8` |
| [`LGPL-2.1.txt`](LGPL-2.1.txt) | GNU LGPL v2.1 | jxl（JExcelApi `net.sourceforge.jexcelapi:jxl:2.6.12`，第二百五十二批：旧版 .xls 只读转换；上游 POM 只写「GNU Lesser General Public License」，版本按 Wikipedia/libraries.io 的 LGPL-2.1+ 定） | <https://www.gnu.org/licenses/old-licenses/lgpl-2.1.txt> | 2026-10-28 | 26,419 | 501 | `20e50fe7aae3e56378ebf0417d9de904f55a0e61e4df315333e632a4d3555d95` |
| [`NOTICES.txt`](NOTICES.txt) | 第三方 NOTICE 汇总（**不是**许可证全文） | OkHttp 内置 Public Suffix List 数据的 NOTICE（该数据为 **MPL-2.0**，APK 内 `okhttp3/internal/publicsuffix/publicsuffixes.gz`）。2026-10-28 起不再含 Apache Commons Compress 那段（依赖已删，包内没有该组件） | 照录构件内 `okhttp3/internal/publicsuffix/NOTICE` 原文 | 2026-10-11 | 2,114 | 29 | `8ddbf3e9ca39fb40cb269b2109d59ac76c6a5b0490af244be17804161074cc45` |

## 二、仅历史留档（**不再随包**；≤ v2.2.1 的包里有）

> ⚠️ **正文文件必须留在仓库**：旧版本还挂在 GitHub Releases 上可下载，那几版的分发物里确实带着
> proot / libtalloc / libandroid-shmem，合规义务不因为「新版本不再分发」而消失。
> 但它们**不许进新包**：`app/build.gradle.kts` 的 `StageLicenseAssetsTask` 按白名单装配，
> `tools/check-licenses.py` 会断言这几份**不在** APK 里（多带一份也红）。

| 文件 | 许可证 | 曾经覆盖的组件 | 为什么不再随包 | 取回日期 | 字节数 | 行数 | SHA256（小写） |
|---|---|---|---|---|---|---|---|
| [`GPL-2.0.txt`](GPL-2.0.txt) | GNU GPL v2.0 | proot（Termux fork 发行版，曾内置在 `assets/proot/`） | proot 与整个终端已搬去 `zhengdao-term`；本仓 `app/src/main/assets/` 现在只有 `baopu/`，不含任何 proot 二进制。旧版本（≤ v2.2.1）的包内仍有它，正文留档不撤 | 2026-10-11 | 17,984 | 338 | `edaef632cbb643e4e7a221717a6c441a4c1a7c918e6e4d56debc3d8739b233f6` |
| [`GPL-3.0.txt`](GPL-3.0.txt) | GNU GPL v3.0 | ① 第一方历史版本（≤ v2.0.15）自身的许可正文；② 曾满足 libtalloc 的 LGPL-3.0 第 2 条「并入 GPL-3.0」 | 当前包内**没有任何 GPL 组件**：libtalloc 随 proot 走，第一方自 2026-10-11 起改为「保留所有权利」。旧版本自己的包里已带这份正文（GPL 授权不可撤回，但不需要每个新包再带一遍） | 2026-10-11 | 35,149 | 674 | `3972dc9744f6499f0f9b2dbf76696f2ae7ad8af9b23dde66d6af86c9dfb36986` |
| [`LGPL-3.0.txt`](LGPL-3.0.txt) | GNU LGPL v3.0 | libtalloc（曾在 proot 进程内动态加载） | 同上：随 proot 一起搬去 `zhengdao-term`，当前包内无此库 | 2026-10-11 | 7,652 | 165 | `e3a994d82e644b03a792a930f574002658412f62407f5fee083f2555c5f23118` |
| [`BSD-3-Clause.txt`](BSD-3-Clause.txt) | BSD 3-Clause | libandroid-shmem（曾在 proot 进程内动态加载） | 同上 | 2026-10-11 | 1,529 | 28 | `0dd43abe17196b0224cb0b159819af798cd508a82a21e5870126fff4e899a7eb` |
| [`BSD-2-Clause.txt`](BSD-2-Clause.txt) | BSD 2-Clause | zstd-jni 的 **Java 包装代码**（`com.github.luben:zstd-jni`） | 2026-10-27 随终端搬走时该依赖已删。当前包内剩下的 zstd 是 **C 本体**（静态编进 `libzhengdao_core.so`），它走 `BSD-3-Clause-zstd` 那一份 —— 两份版权人不同，从来不能互相顶替 | 2026-10-11 | 1,360 | 26 | `c6a6a8926f2f1732603e4b75779a5f6ce79238eb79bc36dcc5b7feb509bc6cd9` |

> 📌 `BSD-3-Clause.txt` 是上游 `LICENSE` 的**原文照录**：其第三条里的 `{organization}` 是
> **上游自己没填的占位符**（见 <https://github.com/termux/libandroid-shmem> 的 `LICENSE`）。
> 本项目刻意**不改动正文**——改了就与上游原文不一致，SHA256 也不再是「逐字节可核」的那一份。
> 该条要求的是「不得用本项目（作者）的名义为衍生品背书」，本项目也确实没有这样做。

> 📌 `BSD-3-Clause.txt` 是上游 `LICENSE` 的**原文照录**：其第三条里的 `{organization}` 是
> **上游自己没填的占位符**（见 <https://github.com/termux/libandroid-shmem> 的 `LICENSE`）。
> 本项目刻意**不改动正文**——改了就与上游原文不一致，SHA256 也不再是「逐字节可核」的那一份。
> 该条要求的是「不得用本项目（作者）的名义为衍生品背书」，本项目也确实没有这样做。

### 不放在本目录、但同样是「随附正文」的一份

> **第一方代码的许可声明不是「第三方正文」，因此不进本文件的两张表** —— 它在仓库根 `LICENSE`
> （自 2026-10-11 起为「保留所有权利」），随包时更名为 `assets/licenses/LICENSE-zhengdao.txt`。
> 历史版本（≤ v2.0.15）所用的 GPL-3.0 正文已移入本目录（第二节表里的 `GPL-3.0.txt`），逐字节未改。


> **2026-10-28**：终端字体（JetBrains Maple Mono）及其 OFL 正文已随终端搬去 `zhengdao-term`；
> 本仓不再分发该字体，故两张表都不列 OFL 行（正文在那边 `app/src/main/assets/fonts/OFL.txt`）。
> ⚠️ **两张表的字节数与 SHA256 一律以「仓库内形态」为准（即 git 索引/blob 里的 LF 换行）。**
> 本仓 `core.autocrlf=true` ＋ `.gitattributes` 的 `* text=auto` 会让 Windows 工作区检出成 CRLF
> （同一份 `OFL.txt`：仓库形态 4,572 B，Windows 工作区形态 4,666 B），**照着工作区算出来的摘要会与上表不符**。
> 现已为这些文本固定 `eol=lf`（`licenses/*.txt` 整个目录、根 `LICENSE`；字体那份 `OFL.txt` 已随终端搬走，规则也一并删了），
> 任何平台检出后直接校验都能对上；对不上就先跑 `git ls-files --eol` 看属性是否生效，
> **不要改表里的数字去迁就工作区**。二进制资产（字体 ttf）不受换行影响。
> （2026-10-11 更正：此前一度把 `OFL.txt` 写成 4,666 B / `9e34ed24…`，那是 CRLF 工作区形态，见 `docs/ERRATA.md` E-104 补记。）

> 字体那份 `OFL.txt` 已随字体一起搬到 `zhengdao-term`（OFL 要求「随字体一起分发」，文本与字体同处一地）——本仓不再保留。

### 依赖正文怎么覆盖（2026-10-11 晚更正，原「不翻印的」一句已被实测推翻）

原先这里写的是「Maven 依赖（OkHttp / Compose / AndroidX / zstd-jni 等）的许可证文本**随各自构件分发**
（AAR/JAR 内的 `META-INF/LICENSE*`），本项目不另存副本」。**实测这句话不成立**：

- **Android 打包默认剥掉构件顶层的 `META-INF/LICENSE*` / `NOTICE*`**：`commons-compress` 构件里明明有
  `META-INF/LICENSE.txt`（11,357 B，Apache-2.0 全文）与 `META-INF/NOTICE.txt`（178 B），**APK 里一个都没有**；
  留下来的只有**带子目录的**（AGP 合并出的 `META-INF/androidx/<组>/<构件>/LICENSE.txt` × 5）与 okhttp 的
  `okhttp3/internal/publicsuffix/NOTICE` —— 留不留是打包细节，不是承诺。
- 因此本项目**自己随包给足**：运行时依赖（2026-10-28 第二百六十六批删掉 commons-compress /
  commons-lang3 / tukaani:xz 之后是 **140 个已解析模块**）声明的许可证里，Apache-2.0
  由 [`Apache-2.0.txt`](Apache-2.0.txt) 覆盖；**zstd 本体的 BSD 3-Clause**（静态编进
  `libzhengdao_core.so`）见 [`BSD-3-Clause-zstd.txt`](BSD-3-Clause-zstd.txt)；zstd-jni 那份
  **BSD 2-Clause** 已随该依赖删除而转为历史留档（第二节）；需要在分发物里复现的归属声明
  （Apache-2.0 第 4(d) 条）见 [`NOTICES.txt`](NOTICES.txt)；**jxl 的 LGPL-2.1**（第二百五十二批，
  旧版 `.xls` 只读转换）见 [`LGPL-2.1.txt`](LGPL-2.1.txt)——它是**聚合分发**的 LGPL-2.1 库，
  第 3 条要求随附正文，而它自己的 jar 里一份许可证文本都没有（实测：`jxl-2.6.12.jar` 内
  只有 `META-INF/MANIFEST.MF` 与 class/资源），指望构件自带是不成立的（E-105 同款）。
- 逐组「依赖 → 声明许可证 → 覆盖方式」对照表在仓库根
  [`THIRD-PARTY-LICENSES.md`](../THIRD-PARTY-LICENSES.md) **§8**（含重新生成它的命令与两个坑）。
- 完整取证与教训：`docs/ERRATA.md` **E-105**。

### 这几份正文也随 APK 分发（2026-10-11 起）

第一节表里 `licenses/` 的那几份（**四份**：Apache-2.0 / BSD-3-Clause-zstd / LGPL-2.1 / NOTICES ＋ 本 README），
再加上第一方声明 `LICENSE`（随包更名为 `LICENSE-zhengdao.txt`），构建期会被原样复制进 APK 的
`assets/licenses/`（`app/build.gradle.kts` 的 `stageLicenseAssets` 任务，由 `preDebugBuild` /
`preReleaseBuild` 拉起来；2026-10-28 起按 `StageLicenseAssetsTask.PACKAGED` 白名单装配，第二节那几份不进包）
「关于」→「**开源许可**」（`app/src/main/java/com/example/zhengdao/ui/OpenSourceLicensesActivity.kt`）。

> **git 里只留这一份正文**，随包那份是构建期拷出来的 —— 免得两份副本漂移之后，
> 表里的 SHA256 与「随包正文」各说各话（这正是 E-104 的教训）。

**换行**：装配时一律**归一成 LF**（`app/build.gradle.kts` 的 `normalizeToLf`），所以随包正文与上表的
字节数 / SHA256 逐字节一致 —— 与构建机是 Windows 还是 Linux、`core.autocrlf` 怎么设都无关。
若你本机的**老检出**里这几份还是 CRLF（`.gitattributes` 的 `eol=lf` 只对新检出 / 被重写的文件生效），
`Get-FileHash` 会得到另一组值：那是本地检出陈旧，不是表里写错了 —— 删掉该文件重新检出即变 LF。

## 三、校验（一条命令）

```bash
cd <仓库根>
sha256sum licenses/*.txt                       # 与两张表逐一比对（随包 4 份 + 留档 5 份 = 9 份都在仓库里）
```

Windows PowerShell 等价：

```powershell
Get-FileHash licenses\*.txt -Algorithm SHA256
```

### 不依赖工作区换行的校准命令

```bash
cd <仓库根>
git cat-file blob HEAD:licenses/Apache-2.0.txt | sha256sum     # 与表里的记录比对
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

期望看到 `assets/licenses/` 下**五份 `.txt`**（Apache-2.0 / BSD-3-Clause-zstd / LGPL-2.1 / NOTICES /
LICENSE-zhengdao.txt）与本 README，**且不该有别的**（GPL-2.0 / GPL-3.0 / LGPL-3.0 / BSD-2-Clause / BSD-3-Clause 一份都不许出现），
其中第三方正文的字节数与上表一致；
本仓 APK **不再含** `assets/fonts/OFL.txt`（字体与其 OFL 正文都已搬到 `zhengdao-term`）。
**少一份、多一份都是构建期装配出了问题**：`stageLicenseAssets` 末尾有 `check` 断言（缺也红、多也红），
`tools/check-licenses.py` 再从产物侧复核一遍。

## 四、维护规则（改动需经项目所有者确认）

1. **新增随包分发的第三方组件** ⇒ 先确认它要求哪几份正文，正文放进本目录，**登记进 `StageLicenseAssetsTask.PACKAGED`**，
   **同步更新第一节表**（来源 URL、取回日期、字节数、行数、SHA256），并回到 `THIRD-PARTY-LICENSES.md` 的对照表加一行、
   **§8 的依赖表加/改组**；同时把文件名补进 `app/build.gradle.kts` 的 `stageLicenseAssets` 断言。
   > 加表行后**必须同步改 `app/src/test/java/com/example/zhengdao/LicenseTextsTest.kt` 里的记录数**
   > （它断言本文件两张表解析出的正文份数恰好等于实际份数，加一份就要 +1；当前 = **9** = 随包 4 + 留档 5：
   > `licenses/` 九条（八份许可证正文 ＋ `NOTICES.txt` 汇总）。字体那份 `OFL.txt` 已于 2026-10-28
   > 随终端搬去 `zhengdao-term`，本仓不再分发该字体，也不在这两张表里 —— 那一行是随字体一起走的）。
2. **正文一律取官方原文**，不自行排版、不改换行、不加 BOM（`Apache-2.0.txt` 首行是空行，
   即官方原文形态，保持原样）。
3. **升级组件版本** ⇒ 复核其许可证是否变更；换正文则同步更新 SHA256。
4. **删依赖就要回头动清单**：组件离包（例如终端搬走）⇒ 它那份正文从 `PACKAGED` 移到 `ARCHIVED`，
   第一节表那一行搬到第二节并写清「为什么不再随包」；**正文本身不删**（旧版本还在发）。
5. 本目录只放**随分发物提供的正文**（许可证全文、以及照录的上游 NOTICE 归属声明），
   不放第三方源码；解释性文档在根目录两份文档里。`NOTICES.txt` 属于前者：
   它装的是**上游 NOTICE 的原文照录**，不是我们自己写的说明（说明写在文件开头的注释里，且不参与 SHA256 之外的解释）。
