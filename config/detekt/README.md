# Detekt（P1-3 · report-only · 2026-10-19）

## 是什么

只开两条复杂度规则：

- `LongMethod`（阈值 60 行）
- `CyclomaticComplexMethod`（阈值 15）

**report-only**：`detektReport` 任务带 `isIgnoreExitValue = true`，发现问题也不会让构建红；
CI **不接**这一步（质量工程评估 P1-3 原话「不设门槛前不影响 CI」）。
首跑违规已冻结进 `baseline.xml`，以后新增违规只会出现在报告里。

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

## 首跑读数（2026-10-19，`origin/main` 7884ab0a + 本次清债）

- **156 条**（`LongMethod` 73 / `CyclomaticComplexMethod` 83），涉及 **85 个文件**；
- 生产代码 217 个 Kotlin 文件、38,931 SLOC。

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
