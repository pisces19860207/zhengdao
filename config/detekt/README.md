# Detekt（P1-3 · 2026-10-19 立 · 2026-10-28 进 CI 上闸 · 2026-10-29 预算只降不升）

## 是什么

只开两条复杂度规则：

- `LongMethod`（阈值 60 行）
- `CyclomaticComplexMethod`（阈值 15）

> ⚠️ **2026-10-29 更新（本段替代原来的「report-only / CI 不接」）**：
> 266 批（2026-10-28）已把 detekt **接进 CI 并上闸**——`detektReport` 仍 `isIgnoreExitValue = true`
> （只出报告、不自己红），判红改由 `tools/check-detekt-budget.py` 比
> `config/detekt/issue-budget.json` 完成：**只比总量，超了才红**；指纹对不上只打诊断 annotation。
> `baseline.xml` 自 2026-10-19 冻结后**一次都没在流水线里跑过**，函数签名一改 ID 就失配，
> 现已**退役**（保留文件只为历史；判定口径全部走预算闸）。
>
> **判据只有一条：总量**（2026-10-29 按用户口径收紧）。`当前总数 > 预算总数` ⇒ 红。
> 逐条指纹**只作诊断**（打印对不上的前 15 条，方便看是重构还是改名），**不参与判红**、
> 规则分布超预算也只 warning —— 因为 detekt 自己的 ID 带行号，行号一漂移键就全变
> （266 那批的 `baseline.xml` 就是这样整体失效的：带 `--baseline` 跑仍报 330 条）。
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

**当前读数**：预算仍记 **330 条**（266 批记下的、当时在 CI 出的读数——本机读数不进预算，
见下「`--write` 只许用 CI 的读数」）。2026-10-29 本机复核为 **329 条**（`CyclomaticComplexMethod` 182 ＋
`LongMethod` 147，真有一个长方法被改短了），闸按「总量」判仍通过，并会提示「下次用 CI 读数
`--write` 顺手降预算」。

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

重算基线（只在有意接受新违规时做，做完把 `baseline.xml` 的 diff 一并提交）：

```powershell
.\gradlew.bat :app:detektBaselineUpdate -Porg.gradle.java.installations.paths=<JDK21>
```

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
