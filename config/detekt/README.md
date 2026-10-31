# Detekt（P1-3 · 2026-10-19 立 · 2026-10-28 进 CI 上闸 · 2026-10-29 预算只降不升）

## 是什么

只开两条复杂度规则：

- `LongMethod`（阈值 60 行）
- `CyclomaticComplexMethod`（阈值 15）

> ⚠️ **2026-10-29 更新（本段替代原来的「report-only / CI 不接」）**：
> 266 批（2026-10-28）已把 detekt **接进 CI 并上闸**——`detektReport` 仍 `isIgnoreExitValue = true`
> （只出报告、不自己红），判红改由 `tools/check-detekt-budget.py` 比
> `config/detekt/issue-budget.json` 完成：**只比总量，超了才红**；指纹对不上只打诊断 annotation。
> `baseline.xml` 自 2026-10-19 冻结后**一次都没在流水线里跑过**，函数签名一改 ID 就失配。
> **324（2026-10-31）把 baseline 文件整条删除**：它一直压着 101 条真读数（详见下面 324 节）；
> 门禁 `tools/check-detekt-no-baseline.py` 盯着 build 脚本与 config，那个 CLI 参数字面量再现即红。
>
> **判据只有一条：总量**（2026-10-29 按用户口径收紧）。`当前总数 > 预算总数` ⇒ 红。
> 逐条指纹**只作诊断**（打印对不上的前 15 条，方便看是重构还是改名），**不参与判红**、
> 规则分布超预算也只 warning —— 因为 detekt 自己的 ID 带行号，行号一漂移键就全变
> （266 那批的 `baseline.xml` 就是这样整体失效的：266 当年实测它压不住、只拦到 330 条里的一小部分；
> 324 逐条对照才发现它其实还**压着 101 条真读数**——所以干脆整条删除，见下面 324 节）。
>
> **预算只能降不能升，但留一个带理由的抬升通道**：`--write` 默认拒绝变大，
> 确实要接受（例如新代码真多一个长函数）时用
> `--allow-raise "为什么这次必须涨"`，理由会写进预算文件的 `raised_reason`，
> 并要一起写进提交信息。修掉存量时闸只打 notice 提醒「顺手降预算」。
>
> **`--write` 只许用 CI 的读数**：本机与 CI 的 JDK/工具链未必一致，本机总数可能与 CI 差几十条，
> 用本机重记 CI 第二天就红。流程固定为：
> CI 跑 `ci.yml` 的「detekt 预算闸」步骤 → 下载 artifact **`detekt-report`**（含 `detekt.xml`）
> → `python3 tools/check-detekt-budget.py --write --xml <解压出的 detekt.xml> --from-ci "run #N"`
> → 提交（预算 diff ＋ 理由一起进提交信息）。`--write` 不给 `--xml` 会直接拒绝。
>
> **指纹（仅诊断／供 `--write` 记录）**：`规则|文件|函数名[#重载序号]`——不含行号、不含绝对路径、
> 也不含测出来的数字（`too long (527)` / `complexity: 106` 只属于报告）。同名重载按行号顺序编号
> （`fromJson#1` / `fromJson#2`），不会两条撞成一条。

**当前读数**：**435 条**（324 用无 baseline 真读数重记，2026-10-31；引擎 304／冻结 UI 131。
带/无 baseline 双跑对照 334→435，被压的 101 条已如实回账——口径修正，非放宽，见下节）。

## 324（2026-10-31）：删 baseline、真读数重记账——**口径修正，非放宽**

- 10-19 首跑把 171 条违规冻结进 `baseline.xml`，之后 detekt 跑法一直带着指到它的参数。
- 324 双跑对照（同一份代码，唯一差别是那个参数）：带 baseline **334** 条、无 baseline **435** 条
  ⇒ baseline 实际仍压着 **101 条**（引擎 64：CCM 47＋LM 17；UI 冻结区 37：LM 23＋CCM 14），
  其中任何一条改签名就会悄悄掉进「新增」，账永远对不上。
- **工单预估 15 条（12 条 UI）有误**：那是按指纹比对估的，与真跑不符；以双跑对照为准。
- 处置：baseline 文件与参数整条删除（原文证据存 `证道设计报告/324-baseline原文存档-2026-10-31.xml`）；
  预算用真读数重记（`raised_reason` 写明口径修正）；**只降不升自 435 起算，下一批至少降 5 条**。
- 门禁：`tools/check-detekt-no-baseline.py`（CI 与本地同一条命令）——build 脚本与 config 里
  那个 CLI 参数字面量**再现即红**；`detektBaselineUpdate` 任务一并删除（它会一把把全部违规
  重新冻成新 baseline，等于撕账本重写）。

## 怎么跑

需要一个 **JDK 21**（Detekt 1.23.8 内置的 Kotlin 编译器不认 JDK 25：
`JavaVersion.parse("25.0.3")` 直接抛；本仓 Gradle daemon 钉的是 25，所以 Detekt 走
`JavaExec` + Java 21 toolchain 子进程）。

```powershell
# 若 JDK 21 不在 Gradle 自动探测路径，显式指一下（本机放在 ~/.gradle/jdks）
.\gradlew.bat :app:detektReport `
  -Porg.gradle.java.installations.paths=C:\Users\guoli\.gradle\jdks\jdk-21.0.12.1+1
```

报告：`app/build/reports/detekt/detekt.html`（另有 XML）。

baseline 重算任务（`detektBaselineUpdate`）已随 324 **删除**——没有 baseline 的世界里，
跑一次它就会把当前全部违规重新冻成新 baseline（撕账本重写）。要接受新增违规，
唯一合法路径是预算闸 `--allow-raise "理由"`，两本账（总量＋分区）同改。

## 首跑读数（2026-10-19，历史留档；**当前见上面 329 条**）

- **175 条**（`LongMethod` 78 / `CyclomaticComplexMethod` 97），涉及 **90 个文件**；
- 生产代码 225 个 Kotlin 文件、40,938 SLOC。

G7 点名的 5 个文件共 **16 条**：

| 文件 | 行 | 规则 | 读数 |
| --- | --- | --- | --- |
| `TerminalActivity.kt` | 117 / 646 / 921 / 1729 / 1815 | LongMethod | 88 / 60 / 68 / 68 / 72 行 |
| `RunEngine.kt` | 689 | LongMethod + CyclomaticComplexMethod | 271 行 |
| `HomeScreen.kt` | 117 | LongMethod + CyclomaticComplexMethod | 883 行 |
| `BaopuChatController.kt` | 1046 / 1532 | LongMethod / CyclomaticComplexMethod | 75 行 |
| `MainActivity.kt` | 118 / 413 / 692 | LongMethod（413/692 另有 Cyclomatic） | 70 / 163 / 110 行 |

> 行号取自首跑 XML，拆分后行号会漂；以重新生成的报告为准。

## 为什么不是 Gradle 插件

先试过 `io.gitlab.arturbosch.detekt` 1.23.8 插件：插件本身能在 Gradle 9.6 上配置成功，
但 `detekt` / `detektBaseline` 任务与 daemon 同 JVM，Detekt 内置 Kotlin 在 JDK 25 上
`JavaVersion.parse("25.0.3")` 抛 `IllegalArgumentException`；给任务设 `jvmTarget=17`
只改编译目标、不改运行时 JVM，绕不开。故改用 `JavaExec` + Java 21 toolchain 起子进程。

## 能不能清到 0 并锁 0（2026-10-29 结论）

- **锁 0 增量：已具备**。预算闸在 CI 里，任何新增当场红；`--write` 只许降不许升。
- **清到绝对 0：只能靠真重构**，不能靠配置免掉——detekt 1.23.8 的 `LongMethod` 只支持 `threshold`，
  `CyclomaticComplexMethod` 只支持 `threshold / ignoreSingleWhenExpression / ignoreSimpleWhenEntries /
  ignoreNestingFunctions`，**两者都没有 `ignoreAnnotated`**（官方文档：
  <https://detekt.dev/docs/1.23.8/rules/complexity/#longmethod>），想「免掉 @Composable」只能上
  `@Suppress` 或 config `excludes`，那是压制、与本仓 lint 的口径冲突。
- 因此路线是**逐批降**：每批认领 10–20 条（顺序建议：纯函数/解析器 → 控制器 → Compose 组合函数），
  重构＋用例，同批把 `issue-budget.json` 一起减下来；降到 0 时把 `total` 写成 0 并保留闸
  ⇒ 之后任何新增都红，与 lint 的 0/0 同款。
- 体量参考：329 条里约 80 条落在 `@Composable` 上（Compose 组合函数拆小常反而更难读，
  但这些也要按同口径处理或另立口径拍板）；重灾区 `RunEngine.runLoop`（527 行／复杂度 106）、
  `BaopuSettingsScreen`（992 行）、`RiskMatrix.classify`（185 行／复杂度 58）。


## 硬墙与互锁（276a D1–D4，2026-10-29）

**两道闸，缺一不可**：

| 闸 | 在哪 | 判据 |
|---|---|---|
| **硬墙** | `:app:detektWall`（CI 里单独一步，先跑） | 发现条数 > `maxIssues` ⇒ detekt 自己非 0 退出（`Analysis failed with N weighted issues.`），**不经 python 脚本** |
| **预算闸** | `tools/check-detekt-budget.py`（CI 里紧随其后） | 只看**总量**是否超 `issue-budget.json` 的 total；另做**互锁**：`maxIssues` 必须等于 total，不等即红 |

> 为什么要有硬墙：预算闸是脚本，脚本被跳过 / 从流水线里删掉就没有第二道闸；
> 上限交给 detekt 自己（CLI `--max-issues`，1.23.8 官方文档：
> 「return exit code 0 only when found issues count does not exceed specified issues count」）。

**值放哪**：`config/detekt/detekt.yml` 末尾一行 **注释形态**的 `# maxIssues: 435`。
它故意是注释——实测 detekt 1.23.8 把未注释的 `maxIssues:` 当**非法配置键**
（`Property 'maxIssues' is misspelled or does not exist.` ⇒ 退出码 3）⇒ 值由 `detektWall` 与预算闸
用正则读，再作为 CLI `--max-issues` 交给 detekt。

**当前值**：`# maxIssues: 435` ＝ `issue-budget.json` 的 `total`（324 口径修正后的真读数；
只降不升自 435 起算，下一批至少降 5 条）。

### 降上限（唯一合法路径：两处同改）

```bash
# 1) 用 CI 的读数重记预算（--write 只许 CI 的 XML；本机读数不进预算）
python3 tools/check-detekt-budget.py --write --xml <CI artifact detekt.xml> --from-ci "run #N"
#    ⚠️ 若新 total 与 detekt.yml 的 maxIssues 不等，会被互锁拒绝——这正是设计：
#       它逼你「两处同一提交一起改」，不允许只改一处让硬墙放水。

# 2) 同一提交里把 detekt.yml 那行改成新值
#    config/detekt/detekt.yml：  # maxIssues: <新 total>

# 3) 本地复核两道闸（都要绿）
./gradlew :app:detektWall                 # 硬墙：超上限即非 0
python3 tools/check-detekt-budget.py      # 预算闸 + 互锁
```

**抬升**：`--allow-raise "为什么这次必须涨"`，同样**两处同改**，理由写进提交信息。

### 负例（本机实做过，2026-10-29）

- `# maxIssues: 324`（低于真实 325）⇒ `:app:detektWall` **BUILD FAILED**，
  detekt 原文 `Analysis failed with 325 weighted issues.`（不经过脚本）。
- `# maxIssues: 327` 而预算 326 ⇒ 预算闸 **rc=1**，报「`maxIssues: 327` ≠ 预算 total 326，两处必须相等且同一提交一起改」。
- `--write` 后 total=325 但 yml 仍 326 ⇒ **rc=1** 并提示「把 detekt.yml 那行改成 325 再跑」。

### 改 `detektWall` 的坑：不要在 `doFirst` 里设 `args`

实测（2026-10-29）：把 `--max-issues` / `args` 放到 `doFirst {}` 里设，Gradle 9.6 的
configuration cache 会直接报

```
Configuration cache problems found in this build.
- Task `:app:detektWall` of type `org.gradle.api.tasks.JavaExec`:
  cannot serialize Gradle script object references ...
```

任务因此**失败**——而它本该只在"超上限"时失败。这类失败看起来像闸红了，实际是闸坏了（假红）；
反过来若被误判成"报告成功"，就成了假绿。**上限必须在配置期读**（`tasks.register` 体内直接算），
改 yml 后重跑即可（配置缓存会因 yml 变更自动失效）。

## 预算分两本账（方案 C，2026-10-31 用户拍板）

**为什么分**：334 条里 **94 条**落在 `ui/`（Compose 声明式界面）——用户已定「UI 还要重做，那些长函数不是债」；
它们和引擎侧 **240 条**混在一个数字里，红起来没有诊断价值（分不清是 UI 又长了还是引擎真坏了）。

**口径**（`config/detekt/issue-budget.json` 的 `zones`）
| 账本 | 范围 | 判据 |
|---|---|---|
| `engine` | 除 `ui/` 之外的全部（`baopu/agent`、`inspect`、`logistics`、`core`…） | **超预算 ⇒ 红**（这一红才是真该拆的） |
| `frozen_ui` | `app/src/main/java/com/example/zhengdao/ui/` | **超预算 ⇒ 红**（2026-10-31 用户追加：**冻结区也只降不升**——否则 UI 可以永远欠着新债） |
| 总量 | 两本之和 | 超 `total` ⇒ 红；且必须等于 `detekt.yml` 的 `# maxIssues`（D1/D2 互锁不变） |

**当前**：总量 435（引擎 **304** ／冻结 **131**）；`# maxIssues: 435`（324 口径修正重记；
旧账 334＝240＋94 少了被 baseline 压住的 101 条）。

**负例（本机实做，2026-10-31 两条追加后重跑）**
- 引擎压到 239（实际 240）⇒ **rc=1**；
- 冻结压到 93（实际 94）⇒ **rc=1**（改前只 warning；用户追加「冻结区也只降不升」后同为硬约束）；
- `--write` 把冻结从 93 写成 94 ⇒ **rc=1**「分区也只降不升；确实要涨请用 --allow-raise "理由"」；
- **合并回一本账**：294（UI 重做）收口时用
  `python3 tools/check-detekt-budget.py --write --xml <CI 的 xml> --from-ci "run #N" --single-ledger`
  ⇒ 预算里不再有 `zones`，回到一本账（长期两本等于默认 UI 可以永远欠债，用户 2026-10-31 明确不许）。

**以后怎么算账**：UI 推倒重做时，两本账一起重记（`ui/` 归零或大降，引擎侧按实际）；不许只改一本。
