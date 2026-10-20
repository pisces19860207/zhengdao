# 第七十九批 · 结构拆分第八阶段：RunEngine.runLoop 纯逻辑抽取（§B 80 切片）

- 派单：2026-10-20 蓝总管（用户：「给workbuddy国内版派个大活」）
- 基线：开工时最新 `main`（出单时 `8f4abf15`）。先 `git fetch origin`，确认 `git rev-list --left-right --count origin/main...HEAD` **左侧为 0**。
- 承接：workbuddy（**国内版**）
- 排期：**国内版：76 → 74 → 79**（76 优先，74 随后，79 最后）；国际版的 72/75/77 不在此队列。
- 红线：只动 `baopu/agent/RunEngine.kt` ＋ 新建的纯逻辑 helper/test 文件；**不碰** `baopu/agent/tools/**`、`GuardPipeline.kt`、`ToolRegistry.kt`、`baopu/agent/ChannelStateMachine*`（64 刚合，状态机原样）、`ui/baopu/BaopuChatController.kt`（72）、`TerminalActivity.kt`（74）、`MainActivity.kt`（76）、`HomeScreen.kt`、`Settings*`、`rootfs/**`、Rust、`AndroidManifest.xml`、版本号；**运行行为/状态机/日志语义零变化**；不合并、不推送；不跑真机。

## 0. 为什么是它

`RunEngine.kt` 约 1,793 行，`runLoop()` 319 行，是 §B 80 里最后一个核心大函数；64 批状态机已合入 main，`RunEngine` 目前无在飞改动。它管的是任务机（THINKING/ACTING/COMPRESSING/DONE/ABORTED）与工具批循环——本单只抽**值进值出**的判定，不碰状态机、不碰工具执行、不碰 64 的资源机。

## 1. 先出方法清单，再动手

- 统计 `runLoop()` 及周边 >80 行的块，列「块 | 行数 | 依赖（db/Llm/工具/Compose/纯） | 可抽性」；
- 只挑 **3–5 个纯逻辑片段**（输入普通值，输出普通值/数据类，不碰 db、Llm、工具执行、Compose）：
  - 停止/继续/收尾的判定（`stopRequested`、`truncated`、`findings`、`left` 的组合决策，只算「下一步该走哪」）；
  - 收尾闸重修次数/工具失败预算的推进（纯计数）；
  - 计划未勾数/步骤摘要的聚合；
  - 工具结果的成功/失败/需确认分类映射（只映射，不执行）；
  - usage 合并/累计的口径（纯值）。
- 每个片段抽到新文件（如 `baopu/agent/RunLoopRules.kt`），`RunEngine` 只留调用；**不改调用顺序、不改副作用、不改状态机迁移**。
- 不拆 `runLoop`、不新建第二套状态、不把 db/Llm 搬进 helper。

## 2. 测试

- 每个抽出的纯函数：JVM 单测 ≥3 例（正常/边界/空值/冲突优先级）；
- 源码门禁：`RunEngine` 对应逻辑只剩调用，不得留两份实现；64 的状态机迁移表用例必须全绿、不得改断言；
- 负例：把停止判定改一处 ⇒ 对应用例红；删掉调用 ⇒ 编译/用例红；
- 全量单测 0 失败/0 错误，跳过数不恶化；lint 预算不升；diff 只应出现「等值搬移 + 调用替换」。

## 3. 交付物

- 分支 `refactor/runengine-pure`，从开工时最新 `main` 拉；建议「清单 / 抽取 / 测试」三笔提交。
- 报告 `证道设计报告/结构切片-RunEngine纯逻辑-交付-2026-10-20.md`：方法清单、抽了哪 3–5 个、diff 等值证据、用例与负例、全量读数、未动项（状态机/工具执行原样）。
- 不合并、不推送、不升版本。

## 4. 打回

- 改了状态机/工具执行/停止语义/日志；拆 runLoop；把 db/Llm 搬进 helper；动了在飞文件；没有负例；合并/推送。

## 5. 交接

先读 `AGENTS.md` 与 `docs/协作规约.md`，开工前跑 `python tools\zd.py preflight`。干完把分支、提交、报告交给用户；验收/合并由用户安排（蓝总管只做账与派工单）。
