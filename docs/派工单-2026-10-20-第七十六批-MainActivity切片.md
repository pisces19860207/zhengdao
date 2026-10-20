# 第七十六批 · 结构拆分第五阶段：MainActivity 纯逻辑抽取（§B 80 切片）

- 派单：2026-10-20 蓝总管（用户：「给workbuddy派个大活」）
- 基线：开工时最新 `main`（出单时 `55f614d0`）。先 `git fetch origin`，确认 `git rev-list --left-right --count origin/main...HEAD` **左侧为 0**。
- 承接：workbuddy
- 排期：**64 → 72 → 74（已交待验）→ 75 → 76**；本单在 75 之后做。
- 红线：只动 `MainActivity.kt` ＋ 新建的纯逻辑 helper/test 文件；**不碰** `HomeScreen.kt`（68/73 冲突区）、`BaopuChatController.kt`（72）、`TerminalActivity.kt`（74）、`ProotLauncher.kt`（75）、`RootfsInstaller`（65）、`RunEngine.kt`（64）、`ui/browser/**`、Rust、`AndroidManifest.xml`、版本号；**行为/布局/导航结果零变化**；不合并、不推送；不跑真机。

## 0. 为什么是它

`MainActivity.kt` 约 1,259 行，是 §B 80 五个大文件里目前**唯一没有在飞改动**的（HomeScreen 有 68/73 冲突，Controller 72、Terminal 74、Proot 75、RunEngine 64 都在跑）。它管路由、快捷方式、权限引导、通知落点——正好适合纯逻辑抽取。

## 1. 先出方法清单，再动手

- 统计 `MainActivity.kt` 里 >80 行的方法/局部块，列「方法/块 | 行数 | 依赖（Intent/Nav/Compose/Android/纯） | 可抽性」；
- 只挑 **3–5 个纯逻辑片段**（输入普通值，输出普通值/数据类，不碰 Compose、不碰 Context、不碰真实 Intent 发送）：
  - 起始路由决策（`startInTerminal` / `lastRoute` / 欢迎页 / 权限页的优先级，只算 route 值）；
  - 快捷方式/`open_terminal` 的 intent 优先级与落点判定（只算「该走哪条」，不 startActivity）；
  - `BaopuIntents` 请求的合并/单次消费判定；
  - 权限引导 `guidePermissions` 的判定；
  - 通知/深链 extras 的映射表。
- 每个片段抽到新文件（如 `MainRoutes.kt` / `MainIntentRules.kt`），Activity 只留调用；**不改路由顺序、不改副作用、不改导航结果**。
- 不拆 Activity、不新建第二套路由状态、不把 Context/IO 搬进 helper。

## 2. 测试

- 每个抽出的纯函数：JVM 单测覆盖正常/边界/冲突优先级（至少 3 例）；
- 源码门禁：Activity 对应逻辑只剩调用，不得留两份实现；
- 负例：把优先级改一处 ⇒ 对应用例红；删掉调用 ⇒ 编译/用例红；
- 全量单测 0 失败/0 错误，跳过数不恶化；lint 预算不升；diff 只应出现「等值搬移 + 调用替换」。

## 3. 交付物

- 分支 `refactor/mainactivity-pure`，从开工时最新 `main` 拉；建议「清单 / 抽取 / 测试」三笔提交。
- 报告 `证道设计报告/结构切片-MainActivity纯逻辑-交付-2026-10-20.md`：方法清单、抽了哪 3–5 个、diff 等值证据、用例与负例、全量读数、未动项。
- 不合并、不推送、不升版本。

## 4. 打回

- 改了路由/导航结果/权限行为；拆 Activity；把 Context/IO 搬进 helper；动了 64/65/68/72/73/74/75 在飞文件；没有负例；合并/推送。

## 5. 交接

先读 `AGENTS.md` 与 `docs/协作规约.md`，开工前跑 `python tools\zd.py preflight`。干完把分支、提交、报告交给用户；验收/合并由用户安排（蓝总管只做账与派工单）。
