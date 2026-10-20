# 第七十三批 · 结构拆分第二阶段：HomeScreen 纯逻辑抽取（§B 80 切片）

- 派单：2026-10-20 蓝总管（用户：「再给qoder派个大活」）
- 基线：开工时最新 `main`（出单时 `72f278a1`）。先 `git fetch origin`，确认 `git rev-list --left-right --count origin/main...HEAD` **左侧为 0**。
- 承接：qoder
- 排期：**68（交互三件）合入后、70（圆角令牌）收口后**再做本单；65（安装体验）可并行，但别动同一文件。63 已收，HomeScreen 当前无其他在飞改动。
- 红线：只动 `ui/HomeScreen.kt` ＋ 新建的纯逻辑 helper/test 文件；**不碰** `TerminalActivity.kt`、`RunEngine.kt`、`BaopuScreen.kt`、`ui/browser/**`、`rootfs/**`、`AndroidManifest.xml`、`build.gradle.kts`、`libs.versions.toml`、版本号；**视觉/布局/文案零变化**；不合并、不推送；不跑真机。

## 0. 为什么是它

`HomeScreen()` 是 §B 80 里最大的单个函数（1,128 行，`ui/HomeScreen.kt:117`–`:1244`）。68 批刚改过它的交互，正好在 68 合入后、把里面**纯逻辑**抽出来，给后续拆 Composable 打地基；本单不拆布局、不改视觉。

## 1. 先出方法清单，再动手

- 统计 `HomeScreen.kt` 里 >80 行的方法/局部块，列「方法/块 | 行数 | 依赖（state/Compose/Android/纯） | 可抽性」；
- 只挑 **3–5 个纯逻辑片段**（输入普通值、输出普通值/数据类，不碰 Compose state 写回、不碰 Context/IO）：
  - Agent 列表的过滤/排序/分组（含 `opencode` 剔除、安装状态归类）；
  - 安装状态 → 文案/可点性的判定（只抽判定，不抽文案资源）；
  - 权限项分组与计数；
  - 体检结果汇总/严重度排序；
  - 卸载/安装命令的**参数构造**（不执行、不拼 UI）。
- 每个片段抽到新文件（如 `ui/home/HomeAgentRules.kt` / `HomeInstallRules.kt`），`HomeScreen` 只留调用；**不改调用顺序、不改 state 写回、不改视觉**。
- 不拆 Composable、不新建第二套 UI 状态、不把 IO/Context 搬进 helper。

## 2. 测试

- 每个抽出的纯函数：JVM 单测覆盖正常/边界/空列表/异常输入（至少 3 例）；
- 源码门禁：HomeScreen 对应逻辑只剩调用，不得留两份实现；
- 负例：把 helper 行为改一处 ⇒ 对应用例红；删掉调用 ⇒ 编译/用例红；
- 全量单测 0 失败/0 错误，跳过数不恶化；lint 预算不升；diff 只应出现「等值搬移 + 调用替换」。

## 3. 交付物

- 分支 `refactor/home-pure`，从开工时最新 `main` 拉；建议「清单 / 抽取 / 测试」三笔提交。
- 报告 `证道设计报告/结构切片-HomeScreen纯逻辑-交付-2026-10-20.md`：方法清单、抽了哪 3–5 个、diff 等值证据、用例与负例、全量读数、未动项。
- 不合并、不推送、不升版本。

## 4. 打回

- 动了布局/视觉/文案/state 写回；拆 Composable；把 IO/Context 搬进 helper；动了在飞文件；没有负例；合并/推送。

## 5. 交接

先读 `AGENTS.md` 与 `docs/协作规约.md`，开工前跑 `python tools\zd.py preflight`。干完把分支、提交、报告交给用户；验收/合并由用户安排（蓝总管只做账与派工单）。
