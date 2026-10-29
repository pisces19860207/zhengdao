# Detekt（P1-3 · 2026-10-19 立 · 2026-10-28 进 CI 上闸 · 2026-10-29 预算只降不升）

## 是什么

只开两条复杂度规则：

- `LongMethod`（阈值 60 行）
- `CyclomaticComplexMethod`（阈值 15）

> ⚠️ **2026-10-29 更新（本段替代原来的「report-only / CI 不接」）**：
> 266 批（2026-10-28）已把 detekt **接进 CI 并上闸**——`detektReport` 仍 `isIgnoreExitValue = true`
> （只出报告、不自己红），判红改由 `tools/check-detekt-budget.py` 比
> `config/detekt/issue-budget.json` 完成：**条数或规则分布超预算就红**，新增指纹逐条打 annotation。
> `baseline.xml` 自 2026-10-19 冻结后**一次都没在流水线里跑过**，函数签名一改 ID 就失配，
> 现已**退役**（保留文件只为历史；判定口径全部走预算闸）。
>
> **预算只能降不能升**（2026-10-29 收紧）：`--write` 默认拒绝把总量变大，确需抬高得加
> `--allow-raise` 并在提交信息里写理由。修掉存量时闸只打 notice 提醒「顺手把预算降下来」。
>
> **指纹去噪**（同日）：指纹从「规则|文件|消息原文」改成「规则|文件|函数名[#重载序号]」——
> 旧口径把测出来的数字（`too long (527)` / `complexity: 106`）也算进去，同一个函数再长一行
> 就被报成「新增违规」（实锤：`runLoop` 526→527）。测出来的值只进报告，不进指纹；
> 同名重载按行号顺序编号（`fromJson#1` / `fromJson#2`），不会两条撞成一条。

**当前读数（2026-10-29，`origin/main`）**：**329 条**（`CyclomaticComplexMethod` 182 ＋
`LongMethod` 147），已与预算逐条对齐。

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
