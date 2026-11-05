# 第三方组件声明（THIRD-PARTY NOTICES）

本应用按「聚合分发（mere aggregation）」方式携带以下独立的第三方二进制组件。
它们与本项目第一方代码**不链接、不混合**：App 运行时将其释放为独立可执行文件并
以独立进程方式调用。本项目的 Kotlin/C 源码中**不含任何来自下列项目的代码**。

> ⚠️ **2026-10-28 现状注（256 批对账；历史取证保留不改写）**
> 终端已于 2026-10-27 整仓拆出到 [zhengdao-term](https://github.com/pisces19860207/zhengdao-term)。
> 本文 §1（proot）、§2（libtalloc）、§3（libandroid-shmem）、§4（xterm.js）、§6（终端字体）、
> §7（Debian rootfs）与末节「Termux terminal-emulator / terminal-view」描述的是 **v2.2.1 及更早已发布
> 安装包**的随包组件（这些版本的 APK 确实携带它们，正文与义务履行记录保留在此以保追溯）；
> **当前主仓源码已不再含这些组件**（`app/src/main/assets/proot/`、终端字体 TTF、`rootfs/` 等路径均已不存在，
> 组件随终端迁往 zhengdao-term）。下一个发版起，随包清单以届时发版为准——新包要不要保留/改写本文
> 各节，待项目所有者定（256 批报告 Q 清单）。引用的 `docs/ERRATA.md`、`docs/闭源切换清单-2026-10-11.md`
> 已移至私有侧 `..\_zd-private\docs\`。

> ### 许可证正文随仓库与 APK 分发（2026-10-11 补齐；2026-11-04 第四百二十一批按实况重对）
>
> 各组件需要哪几份正文、正文在哪。**每行开头必须写「随包」或「留档不随包」** ——
> `tools/check-licenses.py` 拿这一列去和 `app/build.gradle.kts` 的
> `StageLicenseAssetsTask.PACKAGED / ARCHIVED` 逐条比（说「随包」却不在 PACKAGED 里、
> 或说「留档」却不在 ARCHIVED 里，门禁直接红）。这就是第四百二十一批补的判据：
> 此前这张表把四份**早已归档不随包**的正文写成「随附正文」，与构建期硬断言互相打脸（420 的 K2）。
>
> | 组件 | 许可证 | 正文（随包 / 留档不随包，逐行标注） |
> |---|---|---|
> | Maven 运行时依赖（§8；**2026-11-03 第四百二十二批 jxl→POI：−1 jxl ＋7（poi 与 6 个传递依赖）＝ +6 个模块**） | 绝大多数 Apache-2.0（**POI 与它的 6 个传递依赖全是 Apache-2.0**）；zstd 本体＝**BSD 3-Clause**（静态编进 Rust 核） | 随包：[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)（覆盖全部 Apache-2.0 模块）（zstd-jni 的 BSD-2 正文已随其退役移入第二节留档，不随包）、附带 [`licenses/NOTICES.txt`](licenses/NOTICES.txt)（Apache-2.0 §4(d) 的归属声明：Apache Commons Compress ＋ OkHttp 内置 Public Suffix List 数据） |
> | proot（§1） | GPL-2.0-or-later | 留档不随包：[`licenses/GPL-2.0.txt`](licenses/GPL-2.0.txt) —— proot 已迁出，当前包里**没有任何 GPL 组件**；旧版本（≤ v2.2.1）的包里带着它，正文在仓库留档、义务不撤（那一版的包自己已经带过正文） |
> | libtalloc（§2） | LGPL-3.0-or-later | 留档不随包：[`licenses/LGPL-3.0.txt`](licenses/LGPL-3.0.txt) ＋ [`licenses/GPL-3.0.txt`](licenses/GPL-3.0.txt)（GPL-3.0 全文；LGPL-3.0 第 2 条并入 GPL-3.0。2026-10-11 前该正文放在仓库根 `LICENSE`）—— 同上：随 proot 迁出，不再进包 |
> | libandroid-shmem（§3） | BSD-3-Clause | 留档不随包：[`licenses/BSD-3-Clause.txt`](licenses/BSD-3-Clause.txt) —— 已随 proot 迁出。⚠️ 这份是 **libandroid-shmem 上游 `LICENSE` 的原文照录**（第三条里带它自己没填的 `{organization}` 占位符），**不是通用 BSD-3 模板**，别拿它去顶替 Rust 那两个 `zstd-safe` / `zstd-sys`（版权人不同 ⇒ 那是下面 §9 的 `BSD-3-Clause-generic.txt`） |
> | JetBrains Maple Mono（§6） | SIL OFL 1.1 | 留档不随包：**字体已不在包内**，且**本仓没有 OFL 正文文件** —— `assets/fonts/OFL.txt` 与字体本体已于 2026-10-28 随终端搬去 `zhengdao-term`（本仓 `app/src/main/assets/` 现在只有 `baopu/`、`persona/`、`policy/`，实测无 `.ttf`）。历史版（≤ v2.2.1）随包时已经带过正文 ⇒ 义务由那一版的包履行。<br>📝 **TODO（待项目所有者定）**：若要给旧版接收者补一份可自查的 OFL 正文，需从上游取回入 `licenses/` 并登记进第二节留档表；本批**未擅自补文件** |
> | ~~zstd-jni（Java 包装）~~（§8，2026-10-27 已删） | BSD-2-Clause | 留档不随包：[`licenses/BSD-2-Clause.txt`](licenses/BSD-2-Clause.txt) —— 依赖已删，当前包内没有它 |
> | ~~**jxl（JExcelApi 2.6.12，§8）**~~ **（2026-11-03 第四百二十二批已换成 Apache POI）** | ~~LGPL-2.1~~ | 留档不随包：正文 [`licenses/LGPL-2.1.txt`](licenses/LGPL-2.1.txt) **转为历史留档**（≤ v2.2.2 的包里有 jxl，那些版本的义务不撤；见本目录 `README.md` 第二节） |
> | Maven 运行时依赖（§8；**2026-11-03 第四百二十二批 jxl→POI：−1 jxl ＋7（poi 与 6 个传递依赖）＝ +6 个模块**） | 绝大多数 Apache-2.0（**POI 与它的 6 个传递依赖全是 Apache-2.0**）；zstd 本体＝**BSD 3-Clause**（静态编进 Rust 核） | 随包：[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)（覆盖全部 Apache-2.0 模块）（zstd-jni 的 BSD-2 正文已随其退役移入第二节留档，不随包）、附带 [`licenses/NOTICES.txt`](licenses/NOTICES.txt)（Apache-2.0 §4(d) 的归属声明：Apache Commons Compress ＋ OkHttp 内置 Public Suffix List 数据） |
> | **Rust 核 = 随包 `libzhengdao_core.so` 静态链接的 33 个 crate（§9，2026-11-04 新增）** | 31 条含 MIT 分支 ＋ 2 条 BSD-3-Clause（`zstd-safe` / `zstd-sys`）；zstd **C 本体**见 §5 | 随包：[`licenses/MIT.txt`](licenses/MIT.txt) ＋ [`licenses/BSD-3-Clause-generic.txt`](licenses/BSD-3-Clause-generic.txt) ＋ [`licenses/BSD-3-Clause-zstd.txt`](licenses/BSD-3-Clause-zstd.txt) ＋ [`licenses/NOTICES.txt`](licenses/NOTICES.txt) 第 2 段（33 条的**版权行**逐条复现——两份通用正文里的 `<year> <owner>` 是占位符，署名靠这里） |
>
> | Maven 运行时依赖（§8；**2026-11-03 第四百二十二批 jxl→POI：−1 jxl ＋7（poi 与 6 个传递依赖）＝ +6 个模块**） | 绝大多数 Apache-2.0（**POI 与它的 6 个传递依赖全是 Apache-2.0**）；zstd 本体＝**BSD 3-Clause**（静态编进 Rust 核） | 随包：[`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)（覆盖全部 Apache-2.0 模块）（zstd-jni 的 BSD-2 正文已随其退役移入第二节留档，不随包）、附带 [`licenses/NOTICES.txt`](licenses/NOTICES.txt)（Apache-2.0 §4(d) 的归属声明：Apache Commons Compress ＋ OkHttp 内置 Public Suffix List 数据） |
> 「136 个已解析模块 / 47 个 group」—— 同一事实两个数，属文内自相矛盾（420 的 K3）。第四百二十一批
> **没跑 Gradle**（本单禁改构建、且构建锁被别的批次占着），无法裁定哪个对 ⇒ 这里不再写数，
> 以 §8 那张逐组表为准；两者的真实差要等一次 `:app:dependencies --configuration releaseRuntimeClasspath`
> 实测（420 未验项 U1）。
>
> - 每份正文的**来源 URL、取回日期、字节数与 SHA256** 见 [`licenses/README.md`](licenses/README.md)。
> - 标了「**随包**」的那几份**随分发物提供**：构建期（`app/build.gradle.kts` 的 `stageLicenseAssets`）
>   按 `StageLicenseAssetsTask.PACKAGED` 白名单把它们拷贝进 APK 的 `assets/licenses/`（**第一方代码声明
>   `LICENSE` 也在内**，包内名为 `LICENSE-zhengdao.txt`），应用内入口 = 设置页「关于」→「**开源许可**」
>   （离线可读全文，2026-10-11 晚起）。标了「留档不随包」的**只在仓库里**，构建期装配末尾有断言
>   「包里多一份都红」（`tools/check-licenses.py` 从产物侧再复核一遍）。
>   ⚠️ 已知缺口（第四百二十一批**按边界未动代码**，交下次接线）：应用内那一页的条目写在
>   `app/src/main/java/com/example/zhengdao/ui/OpenSourceLicensesActivity.kt` 的 `ENTRIES` 里，
>   是**硬编码清单**，现在还只有四份正文的入口 ⇒ 新加的 `MIT.txt` / `BSD-3-Clause-generic.txt`
>   **文件确实在包里、页面上没有入口**（接收者解包能拿到，点进去看不到）。补它要在 `ENTRIES` 加两条。
> - Maven 依赖（OkHttp / Compose / AndroidX / zstd-jni 等）的许可证文本**不能指望「随构件分发」**：
>   Android 打包**默认剥掉**构件顶层的 `META-INF/LICENSE*` / `NOTICE*`（2026-10-11 实测：
>   `commons-compress` 构件里有的 `META-INF/LICENSE.txt` 与 `META-INF/NOTICE.txt`，APK 里一个都没有）。
>   所以本项目**自己随包给足**：逐组对照表见本文 **§8**，教训见 `docs/ERRATA.md` **E-105**。
> - 本文件与 `PROVENANCE.md`、`docs/闭源切换清单-2026-10-11.md` 三份文件如有冲突，
>   **以本文件与 `PROVENANCE.md` 为准**（它们是取证现场）。


> **2026-11-04 第四百二十四批 · 声明自动生成**
> `THIRD-PARTY-LICENSES.md` 的**依赖各节**改由 `tools/gen-notices.py` 从依赖真源生成
> （`gradle/libs.versions.toml` ＋ 各模块 `build.gradle.kts` ＋ `rust/Cargo.lock`），
> CI 跑 `gen-notices.py --check`：清单与依赖不一致就红。生成器**只替换两对 HTML 注释标记之间**的内容；
> 资产类、说明文字、以及 420 指出的 7 节（标 `[已迁出：zhengdao-term]` 归档到文末）
> 全部人工维护、生成器不许碰。编号沿用历史（§1–§9），文末历史节保留原文以保追溯。

---

## 5. zstd（C 本体，静态编进 Rust 核 `libzhengdao_core.so`）

- 用途：归档解压（`.tar.zst`）——Rust 核 `rust/core/src/extract.rs` 按魔数识别 zstd 帧解压，
  由 `app/src/main/java/com/example/zhengdao/baopu/archive/NativeExtractor.kt` 调用。
  ⚠️ 2026-11-04（第四百二十一批）改写过：这一行原来写「RootFS 压缩包解压」，那是 zstd-jni 时代的用途，
  rootfs 与 zstd-jni 依赖已于 2026-10-27/28 随终端搬去 `zhengdao-term`。
- 许可证：BSD-3-Clause（双许可中的 BSD 分支——上游 `zstd/COPYING` 并列写着 GPL-2.0，本项目取 BSD 分支；
  这条取舍与「为什么没触发停手条件」记在下面 §9.3）
- 来源：<https://github.com/facebook/zstd>，版本由 `rust/core/Cargo.lock` 锁死在
  **`zstd-sys v2.1.0+zstd.1.5.7` 捆绑的 C 本体**（经 zstd-safe → zstd → zstd-sys 三层进 `.so`）。
- 随附正文：[`licenses/BSD-3-Clause-zstd.txt`](licenses/BSD-3-Clause-zstd.txt)（zstd 本体的 BSD 3-Clause，
  `facebook/zstd` v1.5.6 的 `LICENSE` 原文，1,549 B / 30 行 / `70552664…`）。
  ✅ 第四百二十一批核实：正文当年按 v1.5.6 取，锁文件现在指到 **v1.5.7**——取 `zstd-sys-2.1.0+zstd.1.5.7`
  的 `.crate`（SHA256 与 `Cargo.lock` 的 `checksum` 一致）解出其中捆绑的 `zstd/LICENSE`，实测
  **1,549 B、SHA256 `7055266497633c9025b777c78eb7235af13922117480ed5c674677adc381c9d8`，与这份随包正文逐字节相同**
  ⇒ 版本号只是正文的取证锚点，1.5.6→1.5.7 之间 zstd 的 BSD-3 文本没漂移，**随包正文不用换**。
  ⚠️ 2026-10-11 更正：此前写的是「随 AAR 构件内的 `META-INF` 分发，本项目不另行翻印」——
  **构件里根本没有这份正文**（实测见 `docs/ERRATA.md` E-105）；且它与 zstd-jni 包装代码的
  BSD 2-Clause（[`licenses/BSD-2-Clause.txt`](licenses/BSD-2-Clause.txt)）**版权人不同、必须分两份**
  （那份现在只留档，见文首表）。

---

## 红线声明（对协作者与 AI 的约束）

1. 严禁将上述任何项目的源码（C/C++/汇编）复制进本项目第一方源码——GPL 传染
   将迫使整个应用开源。允许的只有：**调用其独立二进制**、**阅读其公开资料学习
   机制与参数**、**在文档中注明思路参考**。
2. 若未来需要修改上述 GPL 组件的行为，必须 fork 其源码单独修改并公开，
   保持其独立分发，而不是把补丁合入本项目源码。
3. 本文件与 PROVENANCE.md 共同构成项目的原创性合规基线，改动需经项目所有者确认。

## 8. Maven 运行时依赖（136 个已解析模块 / 47 个 group）→ 声明许可证 → 覆盖方式（2026-10-11 补）

> **2026-11-03 第四百二十二批**：`jxl`（LGPL-2.1）换成 `org.apache.poi:poi`（Apache-2.0，**只 HSSF**）。
> 本节的模块数因此 **−1（jxl）＋7（poi 与 6 个传递依赖）＝ +6**；`LGPL-2.1` 这条许可证从随包清单里消失，
> 当前 APK 内**已无任何 GPL/LGPL 组件**。换库的功能判据（同一夹具逐格一致）见交付报告 §2。

这一节回答「随包的第三方 Maven 代码按什么许可证来的、正文在哪」。**只看运行时依赖**
（`releaseRuntimeClasspath`）：`testImplementation` / `androidTestImplementation` 的构件不进安装包，
不随分发物提供，因此没有 4(a)/4(d) 义务。

**怎么重新得到这张表**（离线即可，读本地 Gradle 缓存）：

```powershell
.\gradlew.bat --offline :app:dependencies --configuration releaseRuntimeClasspath > dep.txt
# ① 取「已解析版本」：`+--- a:b:1.4.2 -> 1.5.0` 里最终生效的是箭头后面的 1.5.0；
#    只按行首 `+---` 抓会漏掉 `|    +---` 这种嵌套行（我们踩过：235 条 vs 实际的 136 条）。
# ② 每个模块的声明许可证 = 它自己 POM 里的 <licenses>；POM 在缓存里的位置是
#    ~/.gradle/caches/modules-2/files-2.1/<组>/<名>/<版本>/<sha1>/<名>-<版本>.pom
#    —— 注意中间那个 <sha1> 子目录，Get-ChildItem 必须带 -Recurse（否则整片报「POM 缺失」）。
```

<!-- gen-notices:begin:maven -->
> **本节由 `tools/gen-notices.py` 生成**（标记 `gen-notices:maven` 之间），
> 人工请勿改这一段；依赖变了跑一次生成，或让 CI 的 `--check` 把它判红。
> 许可优先从**本地 Gradle 缓存 POM** 取（含 `<parent>` 上溯）；冷缓存时由
> `tools/notices-evidence.json` 的离线登记表兜底（带 POM 原文与 sha256）。全程不联网；
> 两处都没有证据的坐标 ⇒ 写「未取证」，不按 group 猜。

**表 8-A · 直接声明的 Maven 依赖**（28 条；随包 16 / 不随包 12 / 许可未取证 0）

| 坐标 | 版本 | 许可 | 随包？ | 来源 | 许可取证 |
|---|---|---|---|---|---|
| `androidx.activity:activity-compose:1.13.0` | `1.13.0` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.benchmark:benchmark-macro-junit4:1.5.0` | `1.5.0` | Apache-2.0 | 否（:macrobenchmark 是独立测试模块） | macrobenchmark/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.compose.material3:material3:1.4.0` | `1.4.0` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=BOM 托管 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.compose.runtime:runtime:1.12.1` | `1.12.1` | Apache-2.0 | 是（implementation） | core/build.gradle.kts（implementation）；版本=BOM 托管 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.compose.ui:ui-graphics:1.12.1` | `1.12.1` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=BOM 托管 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.compose.ui:ui-test-junit4:1.12.1` | `1.12.1` | Apache-2.0 | 否（androidTestImplementation） | app/build.gradle.kts（androidTestImplementation）；版本=BOM 托管 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.compose.ui:ui-test-manifest:1.12.1` | `1.12.1` | Apache-2.0 | 否（debugImplementation） | app/build.gradle.kts（debugImplementation）；版本=BOM 托管 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.compose.ui:ui-tooling-preview:1.12.1` | `1.12.1` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=BOM 托管 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.compose.ui:ui-tooling:1.12.1` | `1.12.1` | Apache-2.0 | 否（debugImplementation） | app/build.gradle.kts（debugImplementation）；版本=BOM 托管 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.compose.ui:ui:1.12.1` | `1.12.1` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=BOM 托管 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.core:core-ktx:1.19.1` | `1.19.1` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；core/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.lifecycle:lifecycle-runtime-compose:2.11.0` | `2.11.0` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.lifecycle:lifecycle-runtime-ktx:2.11.0` | `2.11.0` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.navigation:navigation-compose:2.10.2` | `2.10.2` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.profileinstaller:profileinstaller:1.4.1` | `1.4.1` | Apache-2.0 | 否（benchmarkImplementation） | app/build.gradle.kts（benchmarkImplementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.test.espresso:espresso-core:3.7.0` | `3.7.0` | Apache-2.0 | 否（androidTestImplementation） | app/build.gradle.kts（androidTestImplementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.test.ext:junit:1.3.0` | `1.3.0` | Apache-2.0 | 否（:macrobenchmark 是独立测试模块/androidTestImplementation） | app/build.gradle.kts（androidTestImplementation）；macrobenchmark/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.test.uiautomator:uiautomator:2.4.0` | `2.4.0` | Apache-2.0 | 否（:macrobenchmark 是独立测试模块） | macrobenchmark/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `androidx.work:work-runtime:2.12.0` | `2.12.0` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `com.mikepenz:multiplatform-markdown-renderer-code:0.38.1` | `0.38.1` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `com.mikepenz:multiplatform-markdown-renderer-m3:0.38.1` | `0.38.1` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `com.mikepenz:multiplatform-markdown-renderer:0.38.1` | `0.38.1` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `com.squareup.okhttp3:mockwebserver3:5.5.1` | `5.5.1` | Apache-2.0 | 否（testImplementation） | app/build.gradle.kts（testImplementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `com.squareup.okhttp3:okhttp:5.5.1` | `5.5.1` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `io.gitlab.arturbosch.detekt:detekt-cli:1.23.8` | `1.23.8` | Apache-2.0 | 否（detektCli） | app/build.gradle.kts（detektCli）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `junit:junit:4.13.2` | `4.13.2` | EPL-1.0 | 否（testImplementation） | app/build.gradle.kts（testImplementation）；core/build.gradle.kts（testImplementation）；native-core/build.gradle.kts（testImplementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `org.apache.poi:poi:5.5.1` | `5.5.1` | Apache-2.0 | 是（implementation） | app/build.gradle.kts（implementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |
| `org.json:json:20240303` | `20240303` | PublicDomain | 否（testImplementation） | app/build.gradle.kts（testImplementation）；core/build.gradle.kts（testImplementation）；版本=版本目录 | 离线取证（本地 Gradle 缓存 POM 或登记表，不联网） |

**表 8-B · 版本托管（BOM，platform() 声明，本身不进分发物）**

| 坐标 | 版本 | 用途 | 来源 |
|---|---|---|---|
| `androidx.compose:compose-bom:2026.09.00` | `2026.09.00` | 只给别的坐标供版本，不产生 4(a)/4(d) 义务 | app/build.gradle.kts（platform）；core/build.gradle.kts（platform） |

> 口径说明：`test*` / `androidTest*` / `debug*` / `benchmark*` / `detektCli` 的构件，
> 以及 `:macrobenchmark`（`com.android.test` 模块，独立测试 APK）的全部依赖，
> 都不进主 app 的 release 分发物，随包？列写「否」；其余按「进分发物」保守登记。
> 传递闭包不在本节登记范围——
> 它由 Gradle 冲突解析决定，离线按 POM 递归只能得到**推定**集合（420 批标 B 级），
> 那张逐组表与正文覆盖方式见上面 §8 的人工说明；本节只登记构建脚本里**硬证明**的直接声明。
<!-- gen-notices:end:maven -->

> **下方逐组表 = 2026-10-11 的人工快照（已冻结）**：当前依赖逐条登记以生成块「表 8-A / 8-B」为准；
> 旧表保留的是「按组聚合的覆盖方式」取证与已删依赖的删除线记录，不是当前依赖清单。

| 组 | 已解析模块数 | POM 里声明的许可证 | 覆盖方式 |
|---|---|---|---|
| `androidx.*`（33 个 group） | 99 | Apache-2.0（写作 `The Apache Software License, Version 2.0`） | 随包 [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |
| `com.mikepenz:multiplatform-markdown-renderer*` | 6 | Apache-2.0 | 随包 [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |
| `org.jetbrains.kotlinx:*` | 11 | Apache-2.0 | 随包 [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |
| `org.jetbrains.kotlin:*`（4）＋ `org.jetbrains:{annotations,markdown,markdown-jvm}`（3） | 7 | Apache-2.0 | 随包 [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |
| `com.squareup.okhttp3:okhttp`（1）＋ `com.squareup.okio:{okio,okio-jvm}`（2） | 3 | Apache-2.0 | 随包 [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)；其中内置的 Public Suffix List 数据（APK 内 `okhttp3/internal/publicsuffix/publicsuffixes.gz`，41,394 B）为 **MPL-2.0** ⇒ 其 NOTICE 进随包 [`licenses/NOTICES.txt`](licenses/NOTICES.txt) |
| `dev.snipme:highlights{,-jvm}` | 2 | Apache-2.0 | 随包 [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |
| `org.jspecify:jspecify` | 1 | Apache-2.0 | 随包 [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |
| ~~`org.apache.commons:commons-compress` / `commons-lang3` ＋ `commons-codec` ＋ `commons-io`~~ **（2026-10-28 第二百六十六批：全部已删 —— 源码零引用；APK 少 2.88 MB）** | 4 | 自身 POM **没有** `<licenses>` 段（继承 `commons-parent`）；**构件内自带** `META-INF/LICENSE.txt`（11,357–11,359 B，Apache-2.0 全文）与 `META-INF/NOTICE.txt`（172–178 B） | Apache-2.0 正文由随包 [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) 覆盖；`commons-compress` 的 NOTICE **原文照录**进随包 [`licenses/NOTICES.txt`](licenses/NOTICES.txt)（§4(d)） |
| `com.google.guava:listenablefuture` | 1 | 自身 POM 没有 `<licenses>` 段；父 POM `com.google.guava:guava-parent:26.0-android` 声明 Apache-2.0 | 随包 [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt) |
| ~~**`com.github.luben:zstd-jni`**~~ **（2026-10-27 随终端搬走时已删；zstd 本体随 Rust 核静态进包，见上一行的 BSD-3-Clause-zstd）** | 1 | **BSD 2-Clause**（包装代码）＋ 它编译进去的 zstd 本体为 **BSD 3-Clause**（<https://opensource.org/licenses/BSD-2-Clause>、（<https://github.com/facebook/zstd>） | **随包 [`licenses/BSD-2-Clause.txt`](licenses/BSD-2-Clause.txt) 与 [`licenses/BSD-3-Clause-zstd.txt`](licenses/BSD-3-Clause-zstd.txt)（2026-10-11 补；此前一直缺，且两份版权人不同不能互相顶替）** —— 该构件内**不带任何许可证文本**，而 APK 里确实有它的二进制 `lib/arm64-v8a/libzstd-jni-1.5.6-4.so`（600,064 B） |
| ~~**`org.tukaani:xz`**~~ **（2026-10-28 第二百六十六批已删：commons-compress 的 XZ 后端，随它一起失去引入方）** | 1 | **Public Domain**（POM 原文 `<comments>You can do whatever you want with this package.</comments>`） | 无正文义务，仅登记 |
| **`org.apache.poi:poi`**（第四百二十二批，旧版 `.xls` 只读；**只 HSSF，不引 poi-ooxml**） | 1 | **Apache-2.0**（POM `<licenses>` 原文 `Apache License, Version 2.0`） | 随包 [`licenses/Apache-2.0.txt`](licenses/Apache-2.0.txt)。**它的 6 个传递依赖也都是 Apache-2.0**：`commons-codec`、`commons-collections4`、`commons-math3`、`commons-io`、`SparseBitSet`、`log4j-api`（POI 只声明 api，**不含 log4j-core**；构建侧仍显式 exclude core 防以后）。⚠️ 该构件**不带任何许可证正文**（APK 里 `META-INF/LICENSE*` 被 AGP 剥掉，见 E-105），所以必须自己随包 |


> **判据**（解包 APK 全量扫，一条命令；期望至少看到 `assets/licenses/` 下**四份 `.txt`**
> ＋ README，以及 `META-INF/androidx/.../LICENSE.txt` × 5 与 `okhttp3/internal/publicsuffix/NOTICE`；
> **不该**再出现 `LGPL-2.1.txt`——422 批起它只留档不随包）：
> ```powershell
> Add-Type -AssemblyName System.IO.Compression.FileSystem
> $z = [IO.Compression.ZipFile]::OpenRead((Get-ChildItem app\build\outputs\apk\debug\*.apk | Select-Object -First 1).FullName)
> $z.Entries | Where-Object FullName -match '(?i)licen|notice|copying' | ForEach-Object { "{0,8}  {1}" -f $_.Length, $_.FullName }
> $z.Dispose()
> ```
>
> **为什么必须自己随包**：Android 打包默认剥掉构件顶层的 `META-INF/LICENSE*` / `NOTICE*`；
> 活下来的只有带子目录的那些（AGP 合并的 `androidx` 5 条）与 okhttp 那个嵌套路径的。
> 这是 2026-10-11 实测结论，完整取证见 `docs/ERRATA.md` **E-105**。

## 9. Rust 核 `libzhengdao_core.so` 静态链接的 crates（33 条）→ 声明许可证 → 版权人 → 覆盖方式（2026-11-04 第四百二十一批补）

> **为什么单开这一节**：第四百二十批对账的结论是「**漏 32 条，全在 Rust 侧**」。随包的**唯一** native
> 二进制 `native-core/src/main/jniLibs/arm64-v8a/libzhengdao_core.so` 静态链接了 crates.io 的包，而本文此前只在 §5 声明了「zstd 本体（C）」一条。
> Maven 侧（§8）420 实测**无漏**，本节只补 Rust 侧。
> ⚠️ 工单写的 `rust/core/Cargo.lock` 在本仓**不存在**：workspace 的锁文件是 **`rust/Cargo.lock`**
> （`rust/Cargo.toml` 里 `members = ["core"]`，锁文件生成在 workspace 根）。本节的坐标／版本／依赖边都取自它。
>
> **这一节的三个字段各自的证据等级**：① 许可证与版权行 = **A 硬证据**（逐条下载 `.crate` 包，
> 校验「下载包 SHA256 == `Cargo.lock` 的 checksum」后才读包内 `license` 字段与 `LICENSE*` 原文，
> 33/33 全绑定；420 那版只能标 C 待查，因为本机 `~/.cargo/registry/` 的 `cache/`、`src/` 已被清理、
> 而 crates.io 的 sparse index JSON 里**没有 license 字段**——实测字段只有
> `name/vers/deps/cksum/features/yanked/pubtime`）；② 是否进 `.so` = **A**（见 9.1）；
> ③ 来源（直接／传递＋引入方）= **A**（`Cargo.lock` 的 `[[package]]` 依赖边，两遍解析补全）。

### 9.1 先定案：随包 `.so` 里**实际**链接了哪些 crate（工单判据⑤「cargo tree 或符号证据，二选一」）

选的是**编译输入清单（dep-info）＋ 逐字节复现**这一条，比符号串硬；`cargo tree` 本机跑不动（下面一并记）。

| # | 证据 | 实测读数（2026-11-04 本机复跑） |
|---|---|---|
| 1 | **随包件 = 本机构建产物，逐字节** | `native-core/src/main/jniLibs/arm64-v8a/libzhengdao_core.so` ＝ **963,128 B** ／ SHA256 `e5674dcadc98978bb5e19ae205ef7de8dcc3e22e424178a45822ceda6b6b6da9`（入库提交 `667037ef`，2026-10-28 17:16） |
| 2 | **按 `rust/README.md` 第 88–91 行的工序复现它** | `llvm-strip --strip-unneeded` 作用于该次构建的原始产物 `rust/target/aarch64-linux-android/release/deps/libzhengdao_core.so`（1,285,400 B）⇒ 得到 **963,128 B / `e5674dc…` —— 与入库件字节全等**。⇒ 「编译单元＝分发物」不是推定：这一批 `.d` 文件列出的输入，就是那个正在 APK 里的 `.so` 的输入 |
| 3 | **dep-info：谁真的参与了编译** | 扫同一目录 `deps/*.d` 里出现过的 `…\.cargo\registry\src\index.crates.io-*\<name>-<ver>\src\…` 路径 ⇒ **唯一名 33 个**（`jni-sys` 有 0.3.1／0.4.1 两个版本各算一条）。`Cargo.lock` 第三方 package 共 **63** 条 ⇒ 余下 **30** 条在编译输入里**一条都没有**（分组见 9.4） |
| 4 | **捆绑的 C／asm 目标文件** | `release/build/zstd-sys-*/out/` ＝ `libzstd.a` ＋ 36 个 `.o`（zstd C 本体的确证）；`release/build/sha2-asm-*/out/` ＝ `libsha256.a` ＋ `sha256_aarch64.o`（`sha2` 的 `asm` feature 确证） |
| 5 | **随包字节里的串（旁证，只作旁证）** | 在**已 strip 的随包件**里能直接搜到 `<name>-<version>` 串的 **8 / 33** 条：`cesu8-1.1.0`、`crc32fast-1.5.2`、`flate2-1.1.10`、`jni-0.21.1`、`miniz_oxide-0.9.1`、`num-bigint-0.4.8`、`tar-0.4.46`、`zstd-safe-7.3.0`。其余 25 条搜不到＝`[profile.release] opt-level=3 + lto=true` 再叠加 strip 把路径串内联／裁掉了 ⇒ **不能反推"没链进去"**，定案靠 2＋3。§5 与 `app/build.gradle.kts` 引用的 `ZSTD_trace_decompress_*` 符号属同一层旁证 |

- ❌ **`cargo tree` 这条路本机走不通**（写下来免得后人重踩）：`cargo tree --offline` 报
  `failed to download adler2 v2.0.1 … --offline was specified`，且 `~/.cargo/registry/cache/`、
  `~/.cargo/registry/src/` 两个目录都已不存在（只剩 sparse index）。dep-info 是**更强**的证据
  （它直接来自产出这个 `.so` 的那次编译），所以判据⑤不需要联网重跑。
- ⚠️ **随包 `.so` 里还有第三类东西，不在这 33 条里**：Rust **标准库自己**的依赖。随包字节里有
  `/rust/deps/addr2line-0.27.1/src/unit.rs`、`/rust/deps/gimli-0.34.0/src/read/line.rs`、
  `/rust/deps/rustc-demangle-0.1.28/src/lib.rs` 这三条路径串，以及
  `library/std/src/../../backtrace/src/symbolize/gimli.rs` ＋ `library/core/src/…`、`alloc/src/…`
  共 46 处 panic 位置串和 47 处 `/rustc/b940084d7eb6a299eb4bfeb8e34901bc051e7ac4/`（= 本机 rustc 1.99.0 的 commit）。
  它们**不在 `rust/Cargo.lock` 里**（由工具链自带的 `rust-deps` 提供），所以 420 与本节两张表都不覆盖；
  许可是 Rust 项目的 `MIT OR Apache-2.0`，已由随包的 `Apache-2.0.txt` 覆盖正文，**缺的是登记**。
  ⇒ 口径要项目所有者定（要不要在本表下面单列一小节「工具链自带件」）；第四百二十四批若把这张表做成
  自动生成的，就必须决定它扫的是「`Cargo.lock`」还是「`.so` 的真实链接闭包」——两者现在差这 3 条。

### 9.2 登记表（33 条，逐条：坐标｜版本｜许可｜是否进 .so｜来源｜版权人／年份｜覆盖方式）

「版权行」这一列是**上游正文里的那一行照录**（MIT 要求「上述版权声明＋许可声明须出现在所有副本中」、
BSD-3-Clause 第 1／2 条要求源码与二进制分发都复现版权声明）。随包的两份正文是**通用模板**
（第一行是 `<year> <owner>` 占位符），署名就落在 `licenses/MIT.txt` ＋ `licenses/NOTICES.txt` 第 2 段。

<!-- gen-notices:begin:rust -->
> **本节由 `tools/gen-notices.py` 生成**（标记 `gen-notices:rust` 之间）。
> 坐标 / 版本 / 来源来自 `Cargo.lock`（workspace 根；派工单写的 `rust/core/Cargo.lock` 不存在）；
> 「是否随包」取 421 批 dep-info 取证集合，许可取带 checksum 绑定的离线登记表；
> 本机 `~/.cargo/registry/{cache,src}` 已清理、sparse index JSON 无 license 字段 ⇒
> 登记表缺该包（或 checksum 与锁文件不符）一律「未取证」，不按 crate 名字猜许可。

**表 9-A · `Cargo.lock` 第三方 package**（63 条；进 .so 33 / 不进 30 / 许可未取证 30；锁文件总 package 64）

| 坐标 | 版本 | 许可 | 随包？ | 来源 | 许可取证 |
|---|---|---|---|---|---|
| `adler2@2.0.1` | `2.0.1` | 0BSD OR MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← miniz_oxide-0.9.1；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `autocfg@1.5.1` | `1.5.1` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← num-traits-0.2.19；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `block-buffer@0.10.4` | `0.10.4` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← digest-0.10.7；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `bytes@1.12.1` | `1.12.1` | MIT | 是（进 .so，dep-info） | 传递（← combine-4.6.8；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `cc@1.6.0` | `1.6.0` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← sha2-asm-0.6.4, zstd-sys-2.1.0+zstd.1.5.7；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `cesu8@1.1.0` | `1.1.0` | Apache-2.0/MIT | 是（进 .so，dep-info） | 传递（← jni-0.21.1；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `cfg-if@1.0.5` | `1.0.5` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← crc32fast-1.5.2, filetime-0.2.29, getrandom-0.4.3 ＋2；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `combine@4.6.8` | `4.6.8` | MIT | 是（进 .so，dep-info） | 传递（← jni-0.21.1；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `cpufeatures@0.2.17` | `0.2.17` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← sha2-0.10.9；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `crc32fast@1.5.2` | `1.5.2` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← flate2-1.1.10；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `crypto-common@0.1.7` | `0.1.7` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← digest-0.10.7；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `digest@0.10.7` | `0.10.7` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← sha2-0.10.9；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `filetime@0.2.29` | `0.2.29` | MIT/Apache-2.0 | 是（进 .so，dep-info） | 传递（← tar-0.4.46；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `find-msvc-tools@0.1.14` | `0.1.14` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← cc-1.6.0；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `flate2@1.1.10` | `1.1.10` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 直接（rust/core/Cargo.toml） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `generic-array@0.14.7` | `0.14.7` | MIT | 是（进 .so，dep-info） | 传递（← block-buffer-0.10.4, crypto-common-0.1.7；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `getrandom@0.4.3` | `0.4.3` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← jobserver-0.1.35；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `hex@0.4.3` | `0.4.3` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 直接（rust/core/Cargo.toml） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `jni-sys-macros@0.4.1` | `0.4.1` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← jni-sys-0.4.1；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `jni-sys@0.3.1` | `0.3.1` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← jni-0.21.1；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `jni-sys@0.4.1` | `0.4.1` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（Cargo.lock 依赖边，引入方未取证） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `jni@0.21.1` | `0.21.1` | MIT/Apache-2.0 | 是（进 .so，dep-info） | 直接（rust/core/Cargo.toml） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `jobserver@0.1.35` | `0.1.35` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← cc-1.6.0；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `libc@0.2.190` | `0.2.190` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← cc-1.6.0, cpufeatures-0.2.17, filetime-0.2.29 ＋3；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `log@0.4.34` | `0.4.34` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← jni-0.21.1；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `memchr@2.8.3` | `2.8.3` | Unlicense OR MIT | 是（进 .so，dep-info） | 传递（← combine-4.6.8；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `miniz_oxide@0.9.1` | `0.9.1` | MIT OR Zlib OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← flate2-1.1.10；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `num-bigint@0.4.8` | `0.4.8` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 直接（rust/core/Cargo.toml） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `num-integer@0.1.47` | `0.1.47` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← num-bigint-0.4.8；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `num-traits@0.2.19` | `0.2.19` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 直接（rust/core/Cargo.toml） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `pkg-config@0.3.34` | `0.3.34` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← zstd-sys-2.1.0+zstd.1.5.7；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `proc-macro2@1.0.107` | `1.0.107` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← quote-1.0.47, syn-2.0.119, thiserror-impl-1.0.69；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `quote@1.0.47` | `1.0.47` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← jni-sys-macros-0.4.1, syn-2.0.119, thiserror-impl-1.0.69；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `r-efi@6.0.0` | `6.0.0` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← getrandom-0.4.3；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `same-file@1.0.6` | `1.0.6` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← walkdir-2.5.0；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `sha2-asm@0.6.4` | `0.6.4` | MIT | 是（进 .so，dep-info） | 传递（← sha2-0.10.9；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `sha2@0.10.9` | `0.10.9` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 直接（rust/core/Cargo.toml） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `shlex@2.0.1` | `2.0.1` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← cc-1.6.0；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `simd-adler32@0.3.10` | `0.3.10` | MIT | 是（进 .so，dep-info） | 传递（← miniz_oxide-0.9.1；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `syn@2.0.119` | `2.0.119` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← jni-sys-macros-0.4.1, thiserror-impl-1.0.69；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `tar@0.4.46` | `0.4.46` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 直接（rust/core/Cargo.toml） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `thiserror-impl@1.0.69` | `1.0.69` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← thiserror-1.0.69；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `thiserror@1.0.69` | `1.0.69` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← jni-0.21.1；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `typenum@1.20.1` | `1.20.1` | MIT OR Apache-2.0 | 是（进 .so，dep-info） | 传递（← crypto-common-0.1.7, generic-array-0.14.7；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `unicode-ident@1.0.26` | `1.0.26` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← proc-macro2-1.0.107, syn-2.0.119；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `version_check@0.9.5` | `0.9.5` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← generic-array-0.14.7；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `walkdir@2.5.0` | `2.5.0` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← jni-0.21.1；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `winapi-util@0.1.11` | `0.1.11` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← same-file-1.0.6, walkdir-2.5.0；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `windows-link@0.2.1` | `0.2.1` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← windows-sys-0.61.2；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `windows-sys@0.45.0` | `0.45.0` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← jni-0.21.1；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `windows-sys@0.61.2` | `0.61.2` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← winapi-util-0.1.11；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `windows-targets@0.42.2` | `0.42.2` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← windows-sys-0.45.0；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `windows_aarch64_gnullvm@0.42.2` | `0.42.2` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← windows-targets-0.42.2；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `windows_aarch64_msvc@0.42.2` | `0.42.2` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← windows-targets-0.42.2；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `windows_i686_gnu@0.42.2` | `0.42.2` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← windows-targets-0.42.2；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `windows_i686_msvc@0.42.2` | `0.42.2` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← windows-targets-0.42.2；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `windows_x86_64_gnu@0.42.2` | `0.42.2` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← windows-targets-0.42.2；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `windows_x86_64_gnullvm@0.42.2` | `0.42.2` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← windows-targets-0.42.2；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `windows_x86_64_msvc@0.42.2` | `0.42.2` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← windows-targets-0.42.2；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `zlib-rs@0.6.8` | `0.6.8` | 未取证（该包不进 .so，421 批未对非随包件取证） | 否（dep-info 未出现） | 传递（← flate2-1.1.10；Cargo.lock 依赖边） | 无（不进分发物，无随包义务；421 批只对随包 33 条取证，本机无 .crate 元数据） |
| `zstd-safe@7.3.0` | `7.3.0` | BSD-3-Clause | 是（进 .so，dep-info） | 传递（← zstd-0.13.3；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `zstd-sys@2.1.0+zstd.1.5.7` | `2.1.0+zstd.1.5.7` | BSD-3-Clause | 是（进 .so，dep-info） | 传递（← zstd-safe-7.3.0；Cargo.lock 依赖边） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |
| `zstd@0.13.3` | `0.13.3` | MIT | 是（进 .so，dep-info） | 直接（rust/core/Cargo.toml） | 登记表：第四百二十一批 .crate SHA256 == Cargo.lock checksum 绑定后读 Cargo.toml license 字段 |

> 边界：Rust 标准库自带的 `rust-deps`（addr2line / gimli / rustc-demangle 等）
> **不在 `Cargo.lock` 里**，本表不覆盖；逐条理由见上面 §9.1 的人工取证说明。
<!-- gen-notices:end:rust -->

> **下方 9.2 表 = 第四百二十一批的 33 条人工取证快照（已冻结）**：逐条版权行与覆盖正文在此；
> 当前 `Cargo.lock` 的全量登记以生成块「表 9-A」为准（含不进 `.so` 的 30 条边界）。

| crate | 版本 | 许可证（`Cargo.toml` 的 `license` 字段原文） | 进 `.so` | 来源（谁引入） | 版权行（上游原文） | 覆盖正文 |
|---|---|---|---|---|---|---|
| `adler2` | `2.0.1` | `0BSD OR MIT OR Apache-2.0` | 是 | 传递（← miniz_oxide-0.9.1） | Copyright (C) Jonas Schievink `<jonasschievink@gmail.com>` | `MIT.txt`（或 `Apache-2.0.txt`） |
| `block-buffer` | `0.10.4` | `MIT OR Apache-2.0` | 是 | 传递（← digest-0.10.7） | Copyright (c) 2018-2019 The RustCrypto Project Developers | `MIT.txt`（或 `Apache-2.0.txt`） |
| `bytes` | `1.12.1` | `MIT` | 是 | 传递（← combine-4.6.8） | Copyright (c) 2018 Carl Lerche | `MIT.txt` |
| `cesu8` | `1.1.0` | `Apache-2.0/MIT` | 是 | 传递（← jni-0.21.1） | （上游正文里**未填**版权行 ⇒ 按 `Cargo.toml` authors 登记：Eric Kidd `<git@randomhacks.net>`） | `MIT.txt`（或 `Apache-2.0.txt`） |
| `cfg-if` | `1.0.5` | `MIT OR Apache-2.0` | 是 | 传递（← crc32fast-1.5.2, filetime-0.2.29, jni-0.21.1, sha2-0.10.9） | Copyright (c) 2014 Alex Crichton | `MIT.txt`（或 `Apache-2.0.txt`） |
| `combine` | `4.6.8` | `MIT` | 是 | 传递（← jni-0.21.1） | Copyright (c) 2015 Markus Westerlind | `MIT.txt` |
| `cpufeatures` | `0.2.17` | `MIT OR Apache-2.0` | 是 | 传递（← sha2-0.10.9） | Copyright (c) 2020-2025 The RustCrypto Project Developers | `MIT.txt`（或 `Apache-2.0.txt`） |
| `crc32fast` | `1.5.2` | `MIT OR Apache-2.0` | 是 | 传递（← flate2-1.1.10） | Copyright (c) 2018 Sam Rijs, Alex Crichton and contributors | `MIT.txt`（或 `Apache-2.0.txt`） |
| `crypto-common` | `0.1.7` | `MIT OR Apache-2.0` | 是 | 传递（← digest-0.10.7） | Copyright (c) 2021 RustCrypto Developers | `MIT.txt`（或 `Apache-2.0.txt`） |
| `digest` | `0.10.7` | `MIT OR Apache-2.0` | 是 | 传递（← sha2-0.10.9） | Copyright (c) 2017 Artyom Pavlov | `MIT.txt`（或 `Apache-2.0.txt`） |
| `filetime` | `0.2.29` | `MIT/Apache-2.0` | 是 | 传递（← tar-0.4.46） | Copyright (c) 2014 Alex Crichton | `MIT.txt`（或 `Apache-2.0.txt`） |
| `flate2` | `1.1.10` | `MIT OR Apache-2.0` | 是 | **直接**（`Cargo.toml`） | Copyright (c) 2014-2026 Alex Crichton | `MIT.txt`（或 `Apache-2.0.txt`） |
| `generic-array` | `0.14.7` | `MIT` | 是 | 传递（← block-buffer-0.10.4, crypto-common-0.1.7） | Copyright (c) 2015 Bartłomiej Kamiński | `MIT.txt` |
| `hex` | `0.4.3` | `MIT OR Apache-2.0` | 是 | **直接**（`Cargo.toml`） | Copyright (c) 2013-2014 The Rust Project Developers.／Copyright (c) 2015-2020 The rust-hex Developers | `MIT.txt`（或 `Apache-2.0.txt`） |
| `jni` | `0.21.1` | `MIT/Apache-2.0` | 是 | **直接**（`Cargo.toml`） | Copyright (c) 2016 Prevoty, Inc. and jni-rs contributors | `MIT.txt`（或 `Apache-2.0.txt`） |
| `jni-sys` | `0.3.1` | `MIT OR Apache-2.0` | 是 | 传递（← jni-0.21.1） | Copyright (c) 2015 The rust-jni-sys Developers | `MIT.txt`（或 `Apache-2.0.txt`） |
| `jni-sys` | `0.4.1` | `MIT OR Apache-2.0` | 是 | 传递（← jni-sys-0.3.1） | Copyright (c) 2015 The rust-jni-sys Developers | `MIT.txt`（或 `Apache-2.0.txt`） |
| `libc` | `0.2.190` | `MIT OR Apache-2.0` | 是 | 传递（← cpufeatures-0.2.17, filetime-0.2.29, tar-0.4.46） | Copyright (c) The Rust Project Developers | `MIT.txt`（或 `Apache-2.0.txt`） |
| `log` | `0.4.34` | `MIT OR Apache-2.0` | 是 | 传递（← jni-0.21.1） | Copyright (c) 2014 The Rust Project Developers | `MIT.txt`（或 `Apache-2.0.txt`） |
| `memchr` | `2.8.3` | `Unlicense OR MIT` | 是 | 传递（← combine-4.6.8） | Copyright (c) 2015 Andrew Gallant | `MIT.txt` |
| `miniz_oxide` | `0.9.1` | `MIT OR Zlib OR Apache-2.0` | 是 | 传递（← flate2-1.1.10） | Copyright 2013-2014 RAD Game Tools and Valve Software／Copyright 2010-2014 Rich Geldreich and Tenacious Software LLC／Copyright (c) 2017 Frommi／Copyright (c) 2017-2024 oyvindln | `MIT.txt` |
| `num-bigint` | `0.4.8` | `MIT OR Apache-2.0` | 是 | **直接**（`Cargo.toml`） | Copyright (c) 2014 The Rust Project Developers | `MIT.txt`（或 `Apache-2.0.txt`） |
| `num-integer` | `0.1.47` | `MIT OR Apache-2.0` | 是 | 传递（← num-bigint-0.4.8） | Copyright (c) 2014 The Rust Project Developers | `MIT.txt`（或 `Apache-2.0.txt`） |
| `num-traits` | `0.2.19` | `MIT OR Apache-2.0` | 是 | **直接**（`Cargo.toml`） | Copyright (c) 2014 The Rust Project Developers | `MIT.txt`（或 `Apache-2.0.txt`） |
| `sha2` | `0.10.9` | `MIT OR Apache-2.0` | 是 | **直接**（`Cargo.toml`） | Copyright (c) 2006-2009 Graydon Hoare／Copyright (c) 2009-2013 Mozilla Foundation／Copyright (c) 2016 Artyom Pavlov | `MIT.txt`（或 `Apache-2.0.txt`） |
| `sha2-asm` | `0.6.4` | `MIT` | 是 | 传递（← sha2-0.10.9） | Copyright (c) 2020 RustCrypto Developers／Copyright (c) 2017 Project Nayuki, Artyom Pavlov | `MIT.txt` |
| `simd-adler32` | `0.3.10` | `MIT` | 是 | 传递（← miniz_oxide-0.9.1） | Copyright (c) [2021] [Marvin Countryman] | `MIT.txt` |
| `tar` | `0.4.46` | `MIT OR Apache-2.0` | 是 | **直接**（`Cargo.toml`） | Copyright (c) The tar-rs Project Contributors | `MIT.txt`（或 `Apache-2.0.txt`） |
| `thiserror` | `1.0.69` | `MIT OR Apache-2.0` | 是 | 传递（← jni-0.21.1） | （上游正文里**未填**版权行 ⇒ 按 `Cargo.toml` authors 登记：David Tolnay `<dtolnay@gmail.com>`） | `MIT.txt`（或 `Apache-2.0.txt`） |
| `typenum` | `1.20.1` | `MIT OR Apache-2.0` | 是 | 传递（← crypto-common-0.1.7, generic-array-0.14.7） | Copyright (c) 2014 Paho Lurie-Gregg | `MIT.txt`（或 `Apache-2.0.txt`） |
| `zstd` | `0.13.3` | `MIT` | 是 | **直接**（`Cargo.toml`） | Copyright (c) 2016 Alexandre Bury | `MIT.txt` |
| `zstd-safe` | `7.3.0` | `BSD-3-Clause` | 是 | 传递（← zstd-0.13.3） | Copyright (c) 2026, Alexandre Bury | `BSD-3-Clause-generic.txt` |
| `zstd-sys` | `2.1.0+zstd.1.5.7` | `BSD-3-Clause` | 是 | 传递（← zstd-safe-7.3.0） | Copyright (c) 2026, Alexandre Bury／Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved. | `BSD-3-Clause-generic.txt` |

**合计 33 条 = 8 条直接声明 ＋ 25 条传递。** 直接声明的 8 条与 `rust/core/Cargo.toml` 的
`[dependencies]`（`sha2`／`hex`／`zstd`／`tar`／`flate2`／`num-bigint`／`num-traits`）
＋ `[target.'cfg(target_os = "android")'.dependencies]`（`sha2` 开 `asm`、`jni`）逐条对得上。
`zstd-sys` 的 `2.1.0+zstd.1.5.7` 里那半截是**它捆绑的 zstd C 本体版本** ⇒ 见下一条。

### 9.3 许可收敛（工单里「遇 GPL／AGPL 就停手」的条件**没有触发**）

- 33 条全部是宽松许可，按 `license` 字段**原文**分组（数字由 `crates.json` 那 33 条逐条点数得来）：
  **22 条**是 MIT／Apache 双许可的三种写法——`MIT OR Apache-2.0` 19 ＋ `MIT/Apache-2.0` 2 ＋ `Apache-2.0/MIT` 1；
  **6 条**纯 `MIT`（`bytes`／`combine`／`generic-array`／`sha2-asm`／`simd-adler32`／`zstd`）；
  **2 条**纯 `BSD-3-Clause`（`zstd-safe`／`zstd-sys`）；
  **3 条**三分支各一——`0BSD OR MIT OR Apache-2.0`（`adler2`）、`MIT OR Zlib OR Apache-2.0`（`miniz_oxide`）、
  `Unlicense OR MIT`（`memchr`）。22＋6＋2＋3 ＝ 33。
  **零 GPL、零 LGPL、零 AGPL、零 MPL**（这里的「零」指这 33 条的 `license` 字段实测，不看下面的可选分支）
  ⇒ 无传染，不需要换依赖，本批照补登记。
- **双许可怎么取**：`MIT OR Apache-2.0` 这类按「择一分支即合规」处理，本项目**取 MIT 分支**
  （随包 `MIT.txt`）；`Apache-2.0.txt` 同时在包里，取 Apache 分支也成立。取 MIT 就必须给版权行
  ⇒ 那 33 行的署名逐条进了 `licenses/NOTICES.txt` 第 2 段。
- ⚠️ **`zstd-sys` 里捆绑的 zstd C 本体**（v1.5.7，见 9.1 证据 4）：上游给了两个许可分支——
  `zstd/LICENSE` = **BSD-3-Clause**（版权人 Meta Platforms）、`zstd/COPYING` = **GPL-2.0** 的另一分支。
  本项目**取 BSD-3 分支**，随包正文是 §5 那份 `BSD-3-Clause-zstd.txt`；✅ 第四百二十一批顺手核实：
  该包内 `zstd/LICENSE` 与随包那份正文 **SHA256 相同**（`7055266497633c90…`），即 420 的 K1／U4
  「随包正文写 v1.5.6、链进的是 v1.5.7」**在文本层面没有漂移**，只是版本号锚点要对齐（本文 §5 已注）。
- ⚠️ **两处"上游正文里没有版权行"**：`cesu8` 只给了 Rust 项目的模板 `COPYRIGHT-RUST.txt`
  （里面是 Valgrind／LLVM／jemalloc／Hoedown 等历史罗列，**不含本 crate 署名**）；`thiserror` 的
  `LICENSE-APACHE`／`LICENSE-MIT` 是空白模板。两条都改按各自 `Cargo.toml` 的 `authors` 登记
  （`Eric Kidd`／`David Tolnay`）并在表里标明是这么来的——**不冒充成照录**。
- 📌 **`r-efi` v6.0.0 的 `license` 字段是 `MIT OR Apache-2.0 OR LGPL-2.1-or-later`**（含 LGPL 分支）。
  它**没编进 `.so`**（引入链 `getrandom`→`jobserver`→`cc` 全是构建期），所以现在不构成随包义务；
  但第四百二十三批那道「政策门禁」要把这种**许可字段里含强传染分支**的包单列成一个未知区，
  别让它哪天被 feature 一开就悄悄进包。

### 9.4 `Cargo.lock` 里**没**编进 `.so` 的 30 条（边界；登记它们是为了"为什么不登记"也可核）

依据 ＝ 9.1 证据 3：这 30 条在**产出随包 `.so` 的那次编译**的 dep-info 里一条都没出现。

- **「声明形态是构建期／proc-macro／dev-dependencies」**（不参与最终链接，9 条）：`autocfg-1.5.1`、`cc-1.6.0`、`jni-sys-macros-0.4.1`、`pkg-config-0.3.34`、`proc-macro2-1.0.107`、`thiserror-impl-1.0.69`、`version_check-0.9.5`、`walkdir-2.5.0`、`zlib-rs-0.6.8`
- **「引入方自己也没编进」**（构建期链条上的下游，跟着一起出局，8 条）：`find-msvc-tools-0.1.14`、`getrandom-0.4.3`、`jobserver-0.1.35`、`quote-1.0.47`、`same-file-1.0.6`、`shlex-2.0.1`、`syn-2.0.119`、`unicode-ident-1.0.26`
- **「非 Android 目标」**（Windows／UEFI 专用，交叉编译时根本不选中，13 条）：`r-efi-6.0.0`、`winapi-util-0.1.11`、`windows-link-0.2.1`、`windows-sys-0.45.0`、`windows-sys-0.61.2`、`windows-targets-0.42.2`、`windows_aarch64_gnullvm-0.42.2`、`windows_aarch64_msvc-0.42.2`、`windows_i686_gnu-0.42.2`、`windows_i686_msvc-0.42.2`、`windows_x86_64_gnu-0.42.2`、`windows_x86_64_gnullvm-0.42.2`、`windows_x86_64_msvc-0.42.2`

两条要点名的具体理由：`zlib-rs` 是 `flate2` 的**可选后端**，我们走默认的 `rust_backend`
（＝ `miniz_oxide`，它自己在上面那 33 条里）；`windows-sys` v0.45.0 的引入方 `jni` **在包内**，
它没编进去只是因为 `cfg(windows)` 门控 —— `Cargo.lock` 把两个平台的依赖都记着，
dep-info 只记**这次编译真正吃进去的**，这正是本节拿它当判据的原因。

⇒ 这 30 条**不产生随包义务**（不在分发物里），所以不进 9.2、也不进 `NOTICES.txt`。
谁哪天把它们编进去了（换 feature／换后端／加 `rand` 一类依赖），9.5 的命令会当场把集合改变，
这就是把"不进包"的一侧也写下来的原因。

### 9.5 怎么复算这张表（离线；工单判据②要求的「待查项的查法」）

```powershell
# ① 进 .so 的集合（dep-info）——不需要联网，也不需要跑 Gradle
python - <<'PY'
import os, re
D = r"rust/target/aarch64-linux-android/release/deps"
P = re.compile(r"([A-Za-z0-9_+.\-]+-[0-9][A-Za-z0-9_.+\-]*)[/\\]src[/\\]")
s = set()
for f in os.listdir(D):
    if f.endswith(".d"):
        s |= set(P.findall(open(os.path.join(D, f), encoding="utf-8", errors="replace").read()))
print(len(s), sorted(s))
PY
# ② 许可证与版权行：逐条下载 .crate ⇒ 校 SHA256 == Cargo.lock 的 checksum ⇒ 读包内 license 字段与 LICENSE 原文
#    https://static.crates.io/crates/<name>/<name>-<version>.crate
#    ⚠️ 必须先过校验再取正文：校验相等才说明"你读的那份就是锁文件钉住的那份"（33/33 全过）。
#    crates.io 的 sparse index（~/.cargo/registry/index/）JSON 里**没有 license 字段**，指望它是取不到的。
# ③ 随包件与本机构建同源（tools/ 里已有门禁，跑现成的）：
python tools/check-so-provenance.py          # .so ↔ rust/core/src ↔ Cargo.lock ↔ 工具链指纹
python tools/check-licenses.py               # 产物侧：assets/licenses/ ↔ PACKAGED 双向，外加文首表 ↔ PACKAGED
```

**本机取证脚本**（一次性，不进仓）：421 批的抓取／比对脚本在
`%TEMP%\zd-421\fetch_licenses.py`（下载＋checksum 绑定＋抽正文）、`gen421.py`（生成 9.2 表行与
NOTICES 段）、`recheck_so.py`（复算进 .so 集合）；中间产物 `crates.json`（33 条，含每条的
`bound_to_lock=True`、`license_field`、`license_files[].sha256/text`）与 `sec9-rows.txt`。

---

## 历史节（420 指出的陈旧 7 节：[已迁出：zhengdao-term]）

> 第四百二十批 §2.3 判定：这 7 节**不是写错了**，是「描述的对象已不在当前分发物里」——
> 终端已于 2026-10-27 整仓拆出到 `zhengdao-term`，`assets/proot/`、终端字体 TTF、`rootfs/` 等路径均不存在。
> 第四百二十四批按派工单把它们标 `[已迁出：zhengdao-term]` 归档到文末，**不直接删**
> （旧版 ≤ v2.2.1 的 APK 确实带过这些组件，正文与义务履行记录保留以保追溯）。
> 每节原文逐字未改；当前分发物的依赖请看 §8 生成块「表 8-A」与 §9 生成块「表 9-A」。

### [已迁出：zhengdao-term] 1. proot（Termux fork 发行版）

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

### [已迁出：zhengdao-term] 2. libtalloc（Termux 发行版）

- 用途：上述 proot 的运行时依赖
- 版本：2.5.0（Termux 官方仓库 aarch64 构建产物）
- 许可证：**LGPL-3.0-or-later**（talloc 上游）
- 来源：上游 <https://www.samba.org/ftp/talloc/>（本项目取的是 Termux 官方构建产物，
  包元数据主页 <https://talloc.samba.org/talloc/doc/html/index.html>）
- **随附正文**：`licenses/LGPL-3.0.txt` ＋ `licenses/GPL-3.0.txt`（GPL-3.0 全文；2026-10-11 前该正文放在仓库根 `LICENSE`）
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

### [已迁出：zhengdao-term] 3. libandroid-shmem（Termux 发行版）

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

### [已迁出：zhengdao-term] 4. xterm.js / @xterm/addon-fit

- 用途：内嵌终端渲染（assets/terminal/ 下为其官方发行文件）
- 许可证：MIT
- 来源：<https://github.com/xtermjs/xterm.js>


### [已迁出：zhengdao-term] 6. JetBrains Maple Mono（终端默认字体）

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

### [已迁出：zhengdao-term] 7. Debian GNU/Linux 13.7 base（guest 系统层 rootfs）

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
  **Release 资产摘要（2026-10-11 入册）**：`debian-13.7-base-arm64.tar.zst` = 201,632,517 B /
  `d80639e7dc5c055fb731e5af62b6789d6ac4d00d07dafe9717f6aed726174f02`，
  `rootfs-manifest.txt` = 2,117,653 B /
  `82fa3f4e84a7963d399cf5f63a3bb8a41d5af183ee7f5812a6a8e84eeecdf16c`，
  `rootfs-index.json` = 469 B /
  `50f10326d9e68a4c1fdd03154e317c9d6068a0d6fa2c71a4c5e73f3b073b78ff`
  （含两份 `.sha256` 共五项；**v2.0.14 ~ v2.0.17 四版逐字节相同**）。
  判据 = 从公开地址回读 GitHub REST 的 `assets[].digest`，完整表与真机交叉核对见
  根 `PROVENANCE.md`「官方发布资产摘要」一节。
- 许可证：**Debian 是数百个独立软件包的聚合发行版，各包许可互不相同**
  （GPL-2.0/3.0、LGPL、BSD、MIT、Apache-2.0、Artistic 等）。镜像内实测装了 **353 个包**
  （2026-10-11 真机 `grep -c '^Package:' /var/lib/dpkg/status`）。
  2026-10-12 细化：**镜像内**（`var/lib/dpkg/status`，即真正的分发物）是 **348** 个
  （313 `arm64` + 35 `all`）；真机那 353 个里多出的 `libgpm2`/`vim`/`vim-common`/`vim-runtime`
  等是构建之后在客服端装的，不属于分发物 —— 见 `docs/ERRATA.md` E-097 补记
- ⚠️ **本环境为控制体积，在构建时删除了 `/usr/share/doc`、`/usr/share/man`、`/usr/share/info`
  （`rootfs/build-rootfs.sh` 的 §2.10 清理段）** ⇒ 镜像里没有逐包版权**目录树**。
  **但版权文本本身随包分发**（2026-10-12 结 ERRATA E-097，用户拍板「随包分发」）：
  构建脚本在删除之前，把每包的 `copyright` 收成一个归档留在镜像里 ——
  `/usr/share/zhengdao-copyright.tar.zst`（路径刻意放在 `/usr/share` 顶层，避开被删的
  `/usr/share/doc/*`；归档内另附 `README.txt` 说明来源与查法）。镜像内展开即可查证：

  ```bash
  tar --zstd -xf /usr/share/zhengdao-copyright.tar.zst -C /tmp/zd-copyright
  dpkg -S /usr/bin/node          # 查某文件属于哪个包
  dpkg-query -W -f='${Package} ${Version}\n'   # 已装包与版本
  ```

  ⚠️ **版本边界（诚实标注）**：镜像**内**那份归档是「本次改动之后重建的镜像」才自带。
  v2.0.14 ~ v2.0.17 用的那份镜像（`debian-13.7-base-arm64.tar.zst`，摘要见上）构建于改动之前，
  镜像里**不含**它。✅ **已用独立资产补上（2026-10-13）**：同一份归档另发为独立的 Release 资产，
  只有 0.49 MiB —— 不必为了查版权再下 200 MB 整包：

  - 滚动版：<https://github.com/pisces19860207/zhengdao/releases/download/latest/debian-13.7-copyright.tar.zst>
    （同名 `.sha256` = `512515f34c562c4f12f7d858266e1ad4fefb6141a4896204d7456fc3a053e816`，517,471 B）
  - 也已补挂到 **v2.0.13 ~ v2.0.17 五个正式 Release**：这五版与滚动版用的都是**同一份镜像**
    （`d80639e7…74f02`，判据 = 从 GitHub REST 回读 `assets[].digest`）⇒ 版权文本逐字相同，
    同一份归档对它们都成立。
  - 构建侧从此自动带上：`rootfs/build-rootfs.sh` 在打包镜像之后把镜像内那份**再导出**到输出目录
    （`debian-13.7-copyright.tar.zst` + 裸摘要 `.sha256`），CI 的
    `gh release upload latest rootfs-files/*` 与 tag job 搬运 rootfs 资产时的 `debian-*.tar.zst` /
    `debian-*.tar.zst.sha256` glob 都会一并带上 ⇒ **不需要改 `.github/workflows/build.yml`**。
  - 归档内容 = 镜像内那 348 个包的 `copyright` 全文（未压缩 4.78 MiB → zstd-19 后 0.49 MiB），
    逐份 SHA-256 与来源 URL 见 `docs/ERRATA.md` E-097 补记。

  对**更早**的版本（v2.0.12 及以前，Release 里没有镜像副本；它们的镜像指纹未经核对），
  替代获取途径（任一即可）：
  1. `http://deb.debian.org/debian`（镜像内 `sources.list` 即指向此处的 `trixie main`）
     取同版本包，包内自带 `usr/share/doc/<包>/copyright`；
  2. `apt-get source <包>`，读源码包里的 `debian/copyright`；
  3. 历史/已下架版本用 <https://snapshot.debian.org/>。
- **待核（诚实标注，不是缺陷）**：*Debian rootfs 所含各包的完整许可证清单及源码获取方式，
  待逐包核查后补充。* 2026-10-12 起**文本层面已随包分发**（上一条），仍是欠账的是
  **逐包许可证标识的汇总表**（哪个包 = 哪个 SPDX 标识）——那需要解析每份 `copyright` 的
  `License:` 字段（多数包是 DEP-5 机读格式，但有相当一部分是自由文本，只能半自动 + 人工核）。
  **原文已经没有障碍**：2026-10-12 已把镜像内 348 个包的 `copyright` 逐份取齐（含 SHA-256，
  见 `docs/ERRATA.md` E-097 补记），缺的只是这份汇总表本身 —— 本项目**未逐包点数**。
  当前口径：各包许可证以随包归档 / Debian 官方仓库同版本包内的 `debian/copyright` 为准。
- 源码要约：本仓库对**自己分发**的 GPL 组件（proot 等，见 §1）以指向官方源码仓库的方式满足
  GPL 第 3 条；系统层里各 GPL/AGPL 组件的源码，由 Debian 官方源按上一条途径提供。
- 改动情况：构建脚本对系统层只做**删减**（文档 / man / info / 多余 locale / 构建残留）与少量
  **配置文件写入**（`/etc/resolv.conf`、`/etc/hosts`、`/etc/uv/uv.toml`、
  `/etc/profile.d/zhengdao-uv.sh`、`/etc/localtime`、apt 源），**未修改任何包的二进制**；
  各包功能与上游同版本一致，卸载/升级仍走 `apt`。

### [已迁出：zhengdao-term] Termux terminal-emulator / terminal-view / libtermux JNI

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

