# 第七十八批 · 结构拆分第七阶段：BaopuScreen 纯逻辑抽取（§B 80 切片）

- 派单：2026-10-20 蓝总管（用户：「给qoder派个大活」）
- 基线：开工时最新 `main`（出单时 `37cdd967`）。先 `git fetch origin`，确认 `git rev-list --left-right --count origin/main...HEAD` **左侧为 0**。
- 承接：qoder
- 排期：**65（安装体验）可并行；68/70 已收口后，73（HomeScreen）→ 78（BaopuScreen）**；73 与 68 的冲突按账上口径先合 68、73 rebase 后再做。
- 红线：只动 `ui/baopu/BaopuScreen.kt` ＋ 新建的纯逻辑 helper/test 文件；**不碰** `ui/HomeScreen.kt`（73）、`ui/baopu/BaopuChatController.kt`（72）、`ui/baopu/BaopuNoticeSlot.kt`（63 已收，布局不动）、`ui/Settings*`（65/77）、`TerminalActivity.kt`、`RunEngine.kt`、`ui/browser/**`、`rootfs/**`、`AndroidManifest.xml`、版本号；**视觉/布局/文案零变化**；不合并、不推送；不跑真机。

## 0. 为什么是它

`BaopuScreen()` 约 740 行，是 §B 80 里 qoder 还没碰的最后一个 UI 大函数（HomeScreen 已派 73，MainActivity 76 国内版，Terminal 74 国内版，Controller 72 国际版）。63 批刚把通知位收进去，正好在它稳定后抽纯逻辑，给后续拆 Composable 打地基；本单**不拆布局、不动通知位**。

## 1. 先出方法清单，再动手

- 统计 `BaopuScreen.kt` 里 >80 行的方法/局部块，列「方法/块 | 行数 | 依赖（Compose state/Controller/Android/纯） | 可抽性」；
- 只挑 **3–5 个纯逻辑片段**（输入普通值，输出普通值/数据类，不碰 Compose state 写回、不碰 Controller 调用）：
  - 计划/步骤卡的展示聚合（未勾数、当前步、收起态判定，只算值）；
  - 记忆/技能/资料库卡片的筛选、计数与排序；
  - 输入区（Composer）可用性/发送条件的判定；
  - 顶栏跑马灯文本的选择与截断判定（只算文本，不改布局）；
  - 空态/加载态/错误态的互斥判定。
- 每个片段抽到新文件（如 `ui/baopu/BaopuScreenRules.kt`），`BaopuScreen` 只留调用；**不改调用顺序、不改 state 写回、不改视觉**。
- 不拆 Composable、不新建第二套 UI 状态、不把 Controller/IO 搬进 helper。

## 2. 测试

- 每个抽出的纯函数：JVM 单测 ≥3 例（正常/边界/空列表/冲突优先级）；
- 源码门禁：BaopuScreen 对应逻辑只剩调用，不得留两份实现；
- 负例：把 helper 判定改一处 ⇒ 对应用例红；删掉调用 ⇒ 编译/用例红；
- 全量单测 0 失败/0 错误，跳过数不恶化；lint 预算不升；diff 只应出现「等值搬移 + 调用替换」。

## 3. 交付物

- 分支 `refactor/baopuscreen-pure`，从开工时最新 `main` 拉；建议「清单 / 抽取 / 测试」三笔提交。
- 报告 `证道设计报告/结构切片-BaopuScreen纯逻辑-交付-2026-10-20.md`：方法清单、抽了哪 3–5 个、diff 等值证据、用例与负例、全量读数、未动项。
- 不合并、不推送、不升版本。

## 4. 打回

- 动了视觉/布局/文案/state 写回；拆 Composable；把 Controller/IO 搬进 helper；动了通知位/HomeScreen/Settings 等在飞文件；没有负例；合并/推送。

## 5. 交接

先读 `AGENTS.md` 与 `docs/协作规约.md`，开工前跑 `python tools\zd.py preflight`。干完把分支、提交、报告交给用户；验收/合并由用户安排（蓝总管只做账与派工单）。
