# 第七十六批 · 结构拆分第五阶段：MainActivity 纯逻辑抽取（§B 80 切片）

- 派单：2026-10-20 蓝总管（结构拆分第五阶段；72→76 五单队列的末单）
- 基线：开工时最新 `main`。出单时 `origin/main` = `cdd924cc`（`docs(账): 立账 64/68/70 三批验收`）；本单全部行号按该版本 `MainActivity.kt`（**1,267 行**，`app/src/main/java/com/example/zhengdao/MainActivity.kt`）复核（证据等级②）。开工前 `git fetch origin`，确认 `git rev-list --left-right --count origin/main...HEAD` **左侧为 0**；若 main 又前进，按开工时版本重算行号再动手。
- 承接：workbuddy
- 排期：**64（已合入 main）→ 72 → 76（插队）→ 74 → 75**；用户 2026-10-20 13:xx 要求 76 先干，74/75 顺延。出单时实测：72 的 helper（`baopu/PlanRules.kt`／`SkillRules.kt`／`VisionRules.kt`）已在 main；74/75 尚无施工分支（`TerminalActivity.kt` 2,018 行、`terminal/ProotLauncher.kt` 843 行均未抽）；`MainActivity.kt` 无任何在飞分支/改动（`git worktree list`＋主要工作树 `git status` 全干净）。
- 红线：只动 `MainActivity.kt` ＋ 新建的纯逻辑 helper/test 文件；**不碰** `ui/HomeScreen.kt`（68/73 冲突区）、`ui/baopu/BaopuChatController.kt`（72）、`TerminalActivity.kt`（74）、`terminal/ProotLauncher.kt`（75）、`RootfsInstaller`（65）、`RunEngine.kt`（64）、`ui/browser/**`、Rust、`AndroidManifest.xml`、版本号；**行为/布局/导航结果零变化**；不合并、不推送；不跑真机。

## 0. 为什么是它

`MainActivity.kt` 1,267 行，是 §B 80 五大文件（审计报告 §6.2：TerminalActivity 1,979→现 2,018、RunEngine、HomeScreen、BaopuChatController、MainActivity）里队列最后一个没派的大文件。它管三件事：入口编排（权限引导/自更新/快捷方式收栈）、Compose 路由（起始页/恢复页/权限页叠加）、底栏四 Tab。本单只做**纯逻辑抽取**，给后续拆 Activity/Compose 打地基；不拆 View、不改导航行为。

## 1. 先出方法清单，再动手

### 1.1 现状清单（按出单时 `origin/main` 逐块核，证据等级②；晚于该版本的改动以开工时为准）

| 方法/块 | 行数（起止） | 依赖 | 可抽性 |
|---|---|---|---|
| `onCreate` | 174（118–291） | Intent/prefs/系统框/`setContent`/RunLog | 不可整体抽；内部无值得抽的纯判定（`open_terminal` 读取是一行 Intent 调用） |
| `checkAppUpdateInBackground` | 38（315–352） | 网络/AlertDialog | 不可抽；**内含两处纯逻辑**（tag 解析 :323、版本比较 :355） |
| `isNewerVersion` | 10（355–364） | 纯（String→Boolean） | ★ 直接可抽 |
| `ZhengdaoApp` | 251（420–670） | Compose/NavHost | 不可拆；**内含两处路由纯判定**（:450–454、:482–490） |
| `TabIndex` | 13（684–696） | 纯常量 | 已是独立 object，**不建议搬**（`TabIndexOrderTest` 按源码形态钉在 MainActivity 里，见 §1.3） |
| `HomeTabs` | 230（700–929） | Compose/生命周期/Scaffold | 不可拆；**双击窗口判定可抽**（:783–811） |
| `TerminalTabEmptyState` | 33（939–971） | Compose | 不抽 |
| 图标区（`leafPath`/四枚 Tab 图标/箭头） | ~295（973–1267） | Compose Canvas | 不抽；`leafPath`（:1043 起）虽是纯几何，但返回 `androidx.compose.ui.graphics.Path`，本项目无 Robolectric、JVM 单测实例化不了 ⇒ 列为**排除项**，报告里写明 |

### 1.2 只抽这 4 个纯逻辑片段

输入普通值、输出普通值/数据类，不碰 Compose、Context、IO、真实 Intent 发送：

1. **版本比较**（:355–364 `isNewerVersion`）→ 新文件纯函数（如 `AppUpdateRules.isNewer(remote, local)`）：语义化版本，remote **严格大于** local 才算有更新；`v` 前缀与缺失段容错（`toIntOrNull() ?: 0`）。⚠️ 保留现状全部容错行为，包括「local 不剥 `v` 前缀」——BuildConfig.VERSION_NAME 本来就没有 v，不许顺手"修"成对称。
2. **GitHub releases tag 解析**（:323 一行正则）→ 同一文件（如 `AppUpdateRules.parseLatestTag(body): String?`）：从 `releases/latest` 响应文本抓 `tag_name`，抓不到返回 null。⚠️ 正则字面量 ``Regex("\"tag_name\"\\s*:\\s*\"([^\"]+)\"")`` 与「取第一个匹配」语义原样搬移，对账用例给改前/改后字符串。
3. **路由决策**（:450–454 `startRoute` ＋ :482–490 恢复判定 ＋ :96 `RESTORABLE_ROUTES` 白名单）→ 新文件（如 `MainRouteRules`）：`startRoute(startInTerminal, lastRoute)` 与 `restoreRoute(lastRoute, startInTerminal, currentRoute): String?` 两个函数。⚠️ 现状是**两份语义**：`startRoute` 只要 `lastRoute != null` 就落 home（**不查白名单**，死值也落 home）；`restore` 才查白名单（死值 ⇒ 无可恢复页）。不许合并成一个函数把任一边改坏。
4. **底栏双击窗口**（:783–811 `lastTabClickAt` + `onTabClicked` 的 300ms 判定）→ 新文件（如 `TabClickRules`）：抽「是不是双击」的纯判定＋时间戳推进（`currentTab == target && nowMs - lastClickAtMs < 300L`；双击后戳清零、单击后戳取 now）；`now` 由调用方注入（`SystemClock.uptimeMillis()`），helper 零 Android 依赖。

落位惯例：沿用仓内 `*Rules.kt` 先例（`baopu/PlanRules.kt`／`SkillRules.kt`／`VisionRules.kt`：`internal object`＋零 Android 依赖＋KDoc 写明判据来源与批次）；测试镜像放 `app/src/test/java/com/example/zhengdao/...` 同包（先例 `baopu/PlanRulesTest.kt`）。具体包路径（root/`ui/`/`util/`）由施工者定，报告里写清。

### 1.3 ⚠️ 两个既有门禁按「源码形态」钉 MainActivity——动手前必读全文

- `app/src/test/java/com/example/zhengdao/ui/RouteRestoreGuardTest.kt`：用正则钉 ① `private val RESTORABLE_ROUTES = setOf(...)` 声明必须留在 MainActivity.kt；② 其 KDoc（前 1600 字符内）须含 `welcome`/`permission_setup`/`plugins` 字样；③ 恢复块（`LaunchedEffect(lastRoute)` 至 `// 记录当前页面`，剥注释后）必须含字面 `lastRoute?.takeIf { it in RESTORABLE_ROUTES }`，且不得出现 `lastRoute == "settings"`／`nav.navigate(lastRoute`。
  ⇒ 抽 `restoreRoute` 时**二选一**：(a) 白名单声明与 `takeIf` 行原样留在 MainActivity，只抽周边判定（当前置条件 `!startInTerminal`、`cur == "home" || cur == "welcome"`）；(b) 判定搬去 helper，**同批**把门禁改判到 helper 文件并写明理由。**不许删断言、不许弱化判据凑绿**；报告必须写明选了哪条。
- `app/src/test/java/com/example/zhengdao/TabIndexOrderTest.kt`：钉 `TabIndex` 常量值（抱朴0/逍遥1/玄览2/怡然3）与底栏 `nav_browser` 先于 `nav_agents` 的源码顺序，并禁裸索引字面量。⇒ `TabIndex` 不搬、底栏顺序一个字不动。

## 2. 测试

- 每个抽出的纯函数：JVM 单测 ≥3 例（正常/边界/空值/冲突优先级）：
  - `isNewer`：`v1.2.3` vs `1.2.3`（等 ⇒ false）、`1.2.10` vs `1.2.9`（数值比非字典序）、`1.2` vs `1.2.0`、缺段/非数字（`1.x.3` ⇒ 0）、空串；
  - `parseLatestTag`：正常响应体、无 `tag_name`、空格变体（`"tag_name" : "v..."`）、多字段并存时取第一个匹配（锁现状）；
  - `startRoute`/`restoreRoute`：`startInTerminal` 优先、`lastRoute="plugins"`（死值）时 startRoute 仍 `home` 而 restore 返回 null、`cur` 非起始页不恢复、`startInTerminal=true` 不恢复；
  - 双击判定：同 Tab 300ms 内 ⇒ 双击、不同 Tab 只切不双击、超窗 ⇒ 单击并重置、首点（`lastClickAtMs=0`）不误判。
- 源码门禁：Activity 对应逻辑只剩调用，不得留两份实现；抽出的正则/白名单字面量与改前逐字一致（典型用例给「改前/改后」字符串）。
- 负例：把 `>` 改成 `>=` ⇒ `isNewer` 用例红；把 300ms 窗口改大 ⇒ 双击用例红；删掉调用 ⇒ 编译/用例红。
- 全量单测 0 失败/0 错误，跳过数不恶化；lint 预算不升（`tools/check-lint-budget.py`）；diff 只应出现「等值搬移＋调用替换」＋（若 §1.3 选 (b)）门禁同批更新。

## 3. 交付物

- 分支 `refactor/mainactivity-pure`，从开工时最新 `main` 拉；建议「清单/抽取/测试」三笔提交。
- 报告 `证道设计报告/结构切片-MainActivity纯逻辑-交付-2026-10-20.md`（MD+HTML 双份，按仓库惯例）：方法清单、抽了哪 4 个、diff 等值证据、§1.3 门禁处置选择及理由、用例与负例、全量读数、未动项（含 `leafPath` 排除原因）。
- 不合并、不推送、不升版本。

## 4. 打回

- 改了路由/导航结果/权限/自更新行为；拆 Activity；把 Context/IO/Compose 搬进 helper；动了 64/65/68/72/73/74/75 在飞文件；绕过、删除或弱化 §1.3 两个门禁的断言；没有负例；合并/推送。

## 5. 交接

先读 `AGENTS.md` 与 `docs/协作规约.md`，开工前跑 `python tools\zd.py preflight`。干完把分支、提交、报告交给用户；验收/合并由用户安排（蓝总管只做账与派工单）。

---

版本注记：本版为仓库正式版（2026-10-20 回写，基线 origin/main = 360ce83e、复核基线 cdd924cc；MainActivity 1,267 行与 cdd924cc 一致）。旧占位版（提交 8e5769f8）由本版替换；两份不一致时以本版为准。
