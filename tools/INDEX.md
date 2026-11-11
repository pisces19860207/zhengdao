# tools/ 脚本索引（480 单 · 2026-11-05）

> **用法（三行）**
> 1. **先查此表** —— 动手前先找有没有现成脚本已经干这件事。
> 2. **有就直接用路径调用** —— 「名」列就是可直接复制的 `python3 tools/xxx.py ...` 路径，别另写一份等价实现。
> 3. **没有才新造，造完回来登记** —— 新脚本落 `tools/` 后，回来在本表补一行（六列齐全、来源可核）。

## 口径（不猜、不编）

- **收录范围**：`tools/*.py`（仅顶层，子目录无 `.py`），共 **44** 个。基线 `fac90bc35`（origin/main，2026-11-05），统计时刻 2026-11-05。`tools/` 下的 `.ps1` / `.json` / `.txt` / `.kts` / `hooks/` / `osv/` 不是 `.py`，不在本表。
- **一行用途** ＝ 脚本**模块 docstring 首句原文摘录**（未改写）；无模块 docstring 时取文件头注释；两者都取不到才写「未自述，需看源码」。
- **主要入参/子命令** ＝ 脚本 `argparse` 定义 ∪ docstring「用法」段 ∪ `ci.yml` 里实际带的参数，三者取并集；不推断未出现的参数。
- **是否 CI 里跑** ＝ `.github/workflows/ci.yml` 中 `python3 tools/xxx.py` 所在 **step 名**（原样抄录），`[verify]` / `[supply-chain]` 为所属 job；写 `—` 表示 ci.yml 未引用。`.github/workflows/build.yml` 的调用不计入此列，另见备注。
- **最后改动** ＝ `git log -1 --abbrev=8 --format='%ad %h' --date=short -- <文件>`，基于 `fac90bc35`。
- **CI 常驻标记**：备注列 `【CI 常驻】` ＝ 该脚本已由 ci.yml 的 step 固定调用，本地**不要再写第二份等价实现**；要复跑就直接用表里路径 + 入参跑同一条命令。这是**登记口径**（依据是 ci.yml 引用，可复核），不是「本地禁止执行」：部分脚本自述明确写「CI 与本地都能跑」，归此类只因 ci.yml 有引用。
- **未自述**：按派工单指定来源（模块 docstring / `--help`）取不到用途的，共 **1** 条，见文末「未自述条目」。
- **行数核对**：只数下面「脚本表」那一节的表格行（以 ``| `tools/`` 开头，共 44 行，等于 `ls tools/*.py | wc -l`）。文首「CI 环境依赖」子集表与文末「未自述条目」表是辅助表，**不计入行数**；直接对全文件 `grep` 会数出 51 行，别用来判行数。

## 只在 CI 跑、本地不跑（30 个 · 别在本地重复实现）

**这一类是：CI 引用列写了 step 名的全部 30 个脚本。** 它们的执行已落在 ci.yml 里 —— 本地要做同一件事，直接复制路径调用，**不要重写一份检查/报告/生成逻辑**（重写即产生第二套判据，必然漂移）。CI 引用列为 `—` 的另外 14 个是本地/人工工具。

其中**本地通常没有输入、别硬跑**的「CI 环境依赖」子集（依据脚本 docstring / ci.yml 注释原文，非推断）：

| 脚本 | 依据原文（摘） |
| --- | --- |
| `tools/ci-test-annotations.py` | docstring 用法：「CI 里紧跟在测试步之后，`if: always()`」；读 Gradle 写的 JUnit XML，输出 `::error::` 注解 |
| `tools/check-r8-mapping.py` | ci.yml 注释：「必须紧跟 assembleRelease（上面那步），不能挂到只跑单测的 job 上——否则找不到产物」；脚本才另备 `--allow-missing` 供「本机还没构建 release 时自测用」 |
| `tools/dependency-report.py` | ci.yml 先 `> build/deps/deps.txt` 再 `--deps build/deps/deps.txt`；输入是 gradlew 依赖树重定向 |
| `tools/check-licenses.py` | ci.yml 排在 `assembleDebug` 之后；docstring：「不给路径就取 `app/build/outputs/apk/` 下最新的包」，本地无包时用 `--docs-only` |
| `tools/check-detekt-budget.py`、`tools/check-detekt-modules-observe.py` | ci.yml 先跑 `detektReport` / `detektModulesReport` 生成 XML，再由脚本消费 |
| `tools/sbom.py` | ci.yml 传 `--commit "${GITHUB_SHA::7}"`（GitHub runner 环境变量） |

## 脚本表（44 行）

| 名 | 一行用途 | 主要入参/子命令 | 是否 CI 里跑（写 step 名） | 最后改动(日期/短号) | 备注 |
| --- | --- | --- | --- | --- | --- |
| `tools/check-activity-registration.py` | Activity 注册防回归门禁（435，2026-11-04）：**代码里显式拉起的组件，必须在清单里注册。** | `--repo` `--manifest` `--src` `--selftest` | [verify] Activity 注册防回归门禁（435 · 显式启动目标必须在 manifest） | 2026-11-04 / f27463d3 | 【CI 常驻】step 内先 `--selftest` 再空参跑一次 |
| `tools/check-baseline-refresh.py` | check-baseline-refresh.py —— 「刷基线」收窄门禁（第㉗②-A，§B 57 加分项）。 | `--base <git rev>` `[仓库根]` | [verify] 刷基线收窄门禁（改基线必须连带改 .so 或 rust/core/src） | 2026-10-27 / 361fd310 | 【CI 常驻】无 argparse，参数由 `sys.argv` 解析；CI 传 `--base "$BASE"` |
| `tools/check-criteria-gates.py` | 第三百一十批 · 判据进化门禁（牙齿层）。 | `--selftest`（空参＝主检查） | [verify] 判据进化门禁（310 批：不可进化清单／锚定只增／纪元账三态／禁标量分） | 2026-11-02 / 781f9a74 | 【CI 常驻】自述 `--selftest` 用内存夹具断言负例会红；ci.yml 未带该参数 |
| `tools/check-detekt-budget.py` | check-detekt-budget.py —— detekt 的**预算闸**：存量不动，多了就红。 | `--xml` `--budget` `--write` `--from-ci` `--allow-raise` `--single-ledger` | [verify] detekt 预算闸（与硬墙互锁：两处必须相等；存量不动，新增即红） | 2026-10-31 / fe44c3a1 | 【CI 常驻】CI 环境依赖：消费 `detektReport` 的 XML |
| `tools/check-detekt-modules-observe.py` | check-detekt-modules-observe.py —— detekt 观察档闸（352，2026-11-01）。 | `--xml` `--selftest` | [verify] detekt 观察档（:policy/:core，report-only ＋ 只增即红） | 2026-11-02 / ffb3766f | 【CI 常驻】CI 环境依赖：消费 `detektModulesReport` 的 XML；存量只报、只增即红 |
| `tools/check-detekt-no-baseline.py` | check-detekt-no-baseline.py —— 324 门禁：detekt 的 baseline 冻结机制**永久退役**。 | 无参数（默认调用） | [verify] detekt 门禁：build 脚本与 config 不许再现 baseline 参数（324） | 2026-10-31 / 9552f972 | 【CI 常驻】docstring 明写「用法（CI 与本地同一条命令）」 |
| `tools/check-doc-ledger.py` | check-doc-ledger.py —— 文档账本一致性门禁（第三十三批）。 | `[仓库根]` `--strict` `--allow-shallow` | [verify] 账本一致性门禁（第三十三批） | 2026-10-20 / 8492c3ec | 【CI 常驻】CI 传 `--strict`；默认模式只报告、退出码恒 0；脚本无 `--fix` |
| `tools/check-entry-coverage.py` | check-entry-coverage.py —— 入口覆盖清点（373，2026-11-02）。 | `[root]` `--strict` `--check` `--selftest` | [verify] 入口覆盖清点（373 · 只报不拦） | 2026-11-05 / a1ee075a | 【CI 常驻】step 内先空参再 `--selftest`；默认模式永远退出 0，`--strict` 才拦 |
| `tools/check-evo-anchor-wiring.py` | check-evo-anchor-wiring.py —— 样本锚与接线登记闸（第五百九十一批，2026-11-11）。 | `--report` `--generate` `--dry-run` `--selftest` | — | 2026-11-11 / 未提交 | 本地/人工工具；白名单 `tools/evo-anchor-wiring.json` **只减不增**；域清单从 `Canary.kt`／`EvoSample.kt` 真源解析。⚠ 本行的加入**未重算**文首「44 个／44 行」——那是 2026-11-05 快照，本次只补行；重基线归文档批（现 `ls tools/*.py` 已是 48） |
| `tools/check-eval-baseline.py` | check-eval-baseline.py —— 评测基线不许放松（第二十三批 B 段 · 派工单 §2.2；判据 E2/E3/E4/E6/E7）。 | `--base <rev>` `[repo]` | [verify] 评测基线不许放松门禁（cases 不许删、baseline 不许松） | 2026-10-18 / 405e785b | 【CI 常驻】CI 传 `--base "$BASE"` |
| `tools/check-inflight.py` | check-inflight.py —— 读 docs/在飞.md，做跨会话占用（文件租约）相交机检。 | `--paths a,b` `--table` `--selftest` | — | 2026-11-03 / f5d54441 | 本地/人工工具；docstring 自述已挂进 `zd.py preflight` 第 5 段 |
| `tools/check-law-gates.py` | check-law-gates.py —— 法典**门禁与规范**的形状机检（第二百七十一批；271-补 分两层）。 | `--dir` `--constitution-dir` `--spec-dir` `--md` `--md-strict` `--json` `--selftest` | [verify] 法典门禁自查（271 批：门禁 15／规范 7，含"门禁必须碰生产"机检） | 2026-11-03 / 2c237e33 | 【CI 常驻】主工作树另有并发未提交改动，本表按 main 版本登记 |
| `tools/check-ledger-dupe.py` | 账本重复检测（防"合并时并列保留双方"）。 | `[path]`（默认 `docs/FEATURE-LEDGER.md`） | [verify] 账本重复检查（同段重复行、重复章节） | 2026-10-26 / 09d44752 | 【CI 常驻】无 argparse，位置参数取 `sys.argv[1]` |
| `tools/check-license-policy.py` | check-license-policy.py —— 许可政策门禁（第四百二十三批）。 | `--verbose` `--all-scopes` `--assets` `--selftest` `--gradle-home` | [supply-chain] 许可政策门禁（423：禁止/未登记/未取证 ⇒ 红）；[supply-chain] 资产许可门禁（440：第一方名册＋第三方登记，双向校验） | 2026-11-05 / fb6e8d74 | 【CI 常驻】唯一被 2 个 step 引用的脚本（普通策略 + `--assets` 资产档） |
| `tools/check-licenses.py` | 许可证正文门禁：APK 的 `assets/licenses/` 必须**恰好等于**当前随包清单。 | `[APK路径]` `--docs-only` | [verify] 许可证正文门禁（APK assets/licenses/ 与仓库逐字节一致） | 2026-11-04 / 1abe0db3 | 【CI 常驻】CI 环境依赖：排在 `assembleDebug` 之后、需要 APK |
| `tools/check-lint-budget.py` | P0-2（2026-10-19）：Android Lint 预算闸 —— 不许新增告警。 | `--report` `--baseline` `--write-baseline` `--base-rev` | [verify] Android Lint（预算闸：不许新增告警；报告入 artifact） | 2026-10-23 / d2656a4e | 【CI 常驻】ci.yml 先跑 `lintDebug`（该步允许 lint 自身失败、由 echo 兜底，见 ci.yml:472），再由本脚本判预算 |
| `tools/check-localcontext-res.py` | 源码门禁：LocalContext 取资源（第 133 批 A 段的落地判据）。 | `--root` | [verify] LocalContext 取资源门禁（第 133 批；2026-10-29 起跳过注释行） | 2026-10-29 / 681af643 | 【CI 常驻】docstring 明写「只读、不联网、不依赖 gradle 产物，CI 与本地都能跑」 |
| `tools/check-memory-asof-migration.py` | check-memory-asof-migration.py —— 451 批 `memory.as_of` 旧库升级的**真跑**自检。 | `--selftest` | [verify] 记忆 as_of 迁移真跑（451 批：旧库 NULL 可读／复核边界／变异负例） | 2026-11-05 / 2462d83c | 【CI 常驻】CI 只跑 `--selftest`；SQL 从 Kotlin 源码提取，不在脚本里手写副本 |
| `tools/check-native-so.py` | check-native-so.py —— 入库 .so 的门禁：16KB 页对齐 + JNI 入口符号。 | `[仓库根]` | [verify] 入库 .so 门禁（16KB 页对齐 + JNI 入口符号） | 2026-10-28 / d698ea03 | 【CI 常驻】`build.yml:218` 亦调用（不计入 CI 列） |
| `tools/check-public-surface.py` | check-public-surface.py —— public 面清单闸（352③，2026-11-01）。 | `--registry` `--selftest` | [verify] public 面清单闸（:policy/:core 顶层声明，未登记即红） | 2026-11-01 / 4846fbbe | 【CI 常驻】注册表 `tools/public-surface.json` |
| `tools/check-r8-mapping.py` | check-r8-mapping.py —— 对 R8 **构建产物**的"承重墙"断言（防 E-022 类闪退复发）。 | `[仓库根]` `--allow-missing` | [verify] R8 产物断言（关键类未被改名 + onProgress 保住） | 2026-10-27 / 64ed46bd | 【CI 常驻】CI 环境依赖：step 仅在 PR / 手动触发时跑；`build.yml:247` 亦调用 |
| `tools/check-radius-tokens-selftest.py` | check-radius-tokens-selftest.py —— 圆角令牌门禁的自测（第七十批 · UI 第六批）。 | `--keep` | [verify] 圆角令牌门禁自测（第七十批：钉住"门禁自己会不会误杀/放水"） | 2026-10-20 / f46acaad | 【CI 常驻】ci.yml 只跑自测脚本，主脚本 `check-radius-tokens.py` 未进 CI |
| `tools/check-radius-tokens.py` | check-radius-tokens.py —— 圆角令牌门禁：拦「ui/ 下新增裸写圆角」，放行令牌、胶囊与白名单。 | `--paths` `--whitelist` `--json` | — | 2026-10-29 / e27deed8 | 本地/人工工具；CI 只跑它的自测脚本（上一行） |
| `tools/check-sbom-direct-deps.py` | check-sbom-direct-deps.py —— 断言 SBOM 覆盖了全部直接依赖（第四十三批 · 任务 A 验收）。 | `--sbom` `--app-build` `--macro-build` `--catalog` | [supply-chain] 直连依赖断言（验收下限） | 2026-11-01 / 2ef09398 | 【CI 常驻】该 step `continue-on-error: true`（report-only 验收件） |
| `tools/check-so-provenance.py` | check-so-provenance.py —— 入库 .so 与 Rust 源码的「同源」门禁（第⑬刀，E-128）。 | `--write` `[仓库根]` | [verify] 入库 .so 同源门禁（so 与 rust/core/src 同源基线） | 2026-10-28 / d698ea03 | 【CI 常驻】CI 只校验；`--write` 仅用于确认重编后刷新基线，勿在 CI 用 |
| `tools/check-string-text.py` | 文案门禁（第 133 批新增）：已有 `<string>` 的值不许改。 | `--base` `--root` | — | 2026-10-23 / 18a257be | 本地/人工工具；docstring 用法 `python tools/check-string-text.py [--base origin/main]` |
| `tools/check-test-platform-gate.py` | check-test-platform-gate.py —— 测试平台无关闸（362 批，2026-11-02）。 | `--root` `--selftest` `--dump` | [verify] 测试平台无关闸（T1 反斜杠／T2 盘符／T3 os.name／T4 写死路径，只扫测试源） | 2026-11-02 / 07d4cd9f | 【CI 常驻】step 内先 `--selftest` 再空参 |
| `tools/ci-test-annotations.py` | ci-test-annotations.py —— 把失败的单元测试**变成 CI 注解**（纯标准库）。 | `--dir` `--json` | [verify] 单测失败清单（注解：类名#用例名 + 首行消息） | 2026-10-30 / 7e527a5f | 【CI 常驻】CI 环境依赖：读 Gradle 的 JUnit XML、写 `::error::` 注解；step `if: always()` |
| `tools/dependency-report.py` | dependency-report.py —— 把 `./gradlew :app:dependencies` 的文本整理成「依赖体检三档表」。 | `--deps` `--config` `--out` | [verify] 依赖体检（三档清单 → dep-report artifact） | 2026-10-28 / 87735048 | 【CI 常驻】CI 环境依赖：CI 传 `--deps build/deps/deps.txt`；零引用候选只是候选，删依赖另需验证 |
| `tools/gen-notices.py` | gen-notices.py —— 从依赖真源生成 THIRD-PARTY-LICENSES.md 的「依赖各节」（第四百二十四批）。 | `--check` `--stats` `--refresh` `--selftest` `--gradle-home` `--root` | [supply-chain] 第三方声明生成门禁（424：清单与依赖不一致 ⇒ 红） | 2026-11-04 / d840e223 | 【CI 常驻】step 内先 `--selftest` 再 `--check` |
| `tools/impact-check.py` | impact-check.py —— 影响面反查（437，2026-11-04）。 | `<target>` `--root` `--json` `--max-hops` `--selftest` | — | 2026-11-04 / 0bb00e5d | 本地/人工工具；输出定义／调用点／受影响测试／暗引用四块 |
| `tools/law-replay.py` | law-replay.py —— 法条回放闸（第三百零九批 §二＋§三）。 | 子命令 `replay` `evolve`；`--candidate` `--ledger` `--baseline` `--days` `--ask-cap-per-day` `--json` `--out` | — | 2026-10-31 / c4d5c96c | 本地/人工工具；准入阈值「少拦 ≥1 直接否决」写在 docstring |
| `tools/license-report.py` | license-report.py —— 许可证分布与依赖版本新鲜度报告（第四十三批 · 任务 C）。 | `--sbom` `--out` | [supply-chain] 许可与版本新鲜度报告（report-only） | 2026-10-19 / 203a845b | 【CI 常驻】该 step `continue-on-error: true`；只报告、不自动升级、不判红 |
| `tools/make-summary.py` | 证道项目工具：把第一方代码汇总成 TXT（供其他 AI 评审）。 | 无参数（docstring 用法：`python tools/make-summary.py`） | — | 2026-10-28 / d698ea03 | 本地/人工工具；输出到仓库外的 `证道-完整代码汇总.txt` |
| `tools/osv-scan.py` | osv-scan.py —— 离线 OSV 漏洞扫描（第四十三批 · 任务 B；report-only，不阻塞 CI）。 | `--sbom` `--out` `--selftest` `--update-db` | [supply-chain] 离线 OSV 漏洞扫描（report-only）；[supply-chain] OSV 夹具自检 | 2026-10-26 / fbbe4b57 | 【CI 常驻】被 2 个 step 引用（空参扫描 + `--selftest`），两步都 `continue-on-error: true` |
| `tools/perf-scan.py` | 第一百一十八批 · 性能基线测量脚本（纯标准库，只读源码，不改任何东西）。 | `--section` `--format` `--root` | — | 2026-10-21 / 28aad88a | 本地/人工工具；不测时间，只从源码数出三类结构读数 |
| `tools/r8-usage-summary.py` | r8-deadcode-summary.py —— 把 R8 的 `usage.txt` / `resources.txt` 整理成「类/成员/资源」三张表。 | `--usage` `--resources` `--source` `--out` | — | 2026-10-28 / 87735048 | 本地/人工工具；**docstring 自述名 `r8-deadcode-summary.py` 与文件名不同**；ci.yml 注释说 R8 清单「本机拿不到…在 CI 出、从 artifact 下载」，本脚本是下载后本地整理用 |
| `tools/rebuild-and-compare.py` | rebuild-and-compare.py —— CI「重编比对」lane（第㊶刀；㉗② 方案 A′，观察期） | `[repo]` `--offline` `--update` `--keep` `--no-remap` `--emit-baseline` | [verify] 重编比对 lane（第㊶刀 · B3+ 按主机基线：硬门禁） | 2026-10-28 / 276f9f46 | 【CI 常驻】CI 前一步专为其装 NDK；按主机各钉一份基线（Windows / ubuntu 不逐字节一致） |
| `tools/rootfs-manifest.py` | rootfs-manifest.py —— 证道（zhengdao）Debian rootfs 的「清单 / 差分包 / 索引」工具。 | 子命令 `manifest` `env` `patch` `index`；`--root` `--out` `--base` `--new` `--pkg` `--patch` `--repo` `--version` `--asset` `--built-at` `--zstd` | — | 2026-10-08 / 787bc171 | 本地/人工工具；4 个子命令见 docstring「用途」段 |
| `tools/safe-worktree-remove.py` | 安全清工作树：先抢救交付报告，再删树（2026-10-20 蓝总管自做）。 | `<worktree>` `--reports-dir` `--dry-run` `--selftest` | — | 2026-10-21 / 8bc5fdbb | 本地/人工工具；`docs/协作规约.md` 规定删工作树一律走它 |
| `tools/sbom.py` | sbom.py —— 生成证道 :app 的 CycloneDX 1.5 SBOM（第四十三批 · 任务 A）。 | `--out` `--commit` `--no-gradle-dump` | [supply-chain] 生成 SBOM（report-only） | 2026-11-01 / 2ef09398 | 【CI 常驻】CI 环境依赖：CI 传 `--commit "${GITHUB_SHA::7}"`；step `continue-on-error: true` |
| `tools/sign-agents-manifest.py` | **未自述，需看源码**（无模块 docstring、无 `--help`）；文件头注释：「用途：对 rootfs/agents.json 做 ed25519 签名（M3 manifest 验签链）」 | 无参数（头注释用法：`python tools/sign-agents-manifest.py`，在仓库根执行） | — | 2026-10-23 / 74e119bb | 本地/人工工具；**未自述条目 1/1**；私钥在仓库外，见文件头注释 |
| `tools/zd.py` | zd.py —— 证道（zhengdao）仓库的**统一工具入口**。 | 子命令 `preflight` `doctor` `install-hooks` `hooks-status`；`preflight -k` `-p` `--max-behind` `--no-fetch` | — | 2026-11-02 / 07d4cd9f | 本地/人工工具；`docs/协作规约.md §5` 指定的统一入口，`.ps1` 是它的薄壳 |
| `tools/zd_ci_anno.py` | 取 GitHub check-runs / annotations（匿名，仓库公开）并把 CI 基线落地。 | `[sha]` `--wait` `--save-baseline` | — | 2026-10-21 / 8bc5fdbb | 本地/人工工具；与 `ci-test-annotations.py` 反向：它从 GitHub 往本地取读数 |
| `tools/必读清单生成.py` | 必读清单生成.py —— `/init` 式的「开工先读哪几页」清单生成器（第四百三十六批 A · ②）。 | `--repo` `--workspace` `--out` `--private-out` `--today` `--max-pits` `--max-rulings` `--public` `--private` `--leak-check` `--dry-run` `--selftest` | — | 2026-11-05 / 5e1f8e92 | 本地/人工工具；硬判据「已存在 AGENTS.md 则增改不覆盖」写在 docstring |

## 未自述条目（共 1 条）

| 名 | 情况 | 处理方式 |
| --- | --- | --- |
| `tools/sign-agents-manifest.py` | 无模块 docstring，静态检查未见 `--help`；按派工单指定来源（docstring / `--help`）取不到用途 | 用途列照写「未自述，需看源码」；**同时摘录文件头注释原文**（`# 用途：对 rootfs/agents.json 做 ed25519 签名…`），读者无需真的去看源码 |

> 其余 43 个脚本均有模块 docstring 且首句即自述用途，故「未自述」计数 = 1。

## 维护约定

- 新脚本入库 ⇒ 在本表加一行；脚本删除 ⇒ 删对应行；**行数必须始终等于 `ls tools/*.py | wc -l`**。
- ci.yml 增删 step ⇒ 同步改「是否 CI 里跑」列与备注的【CI 常驻】标记。
- 本表只做索引，**不改任何脚本、不改 CI**；表内容与脚本不一致时，以脚本 docstring 与 ci.yml 为准并回来修表。
