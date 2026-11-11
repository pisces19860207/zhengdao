# 治理出口读数口径（metrics-registry）

- 立单：第五百二十七批＋第五百三十批（合并实施，2026-11-07）；本文件是**机器口 `governance_state` 工具读数的唯一口径**。
- 改版记录：**形状版本 1→2**（482 加 `goal` 段）；**2→3（第五百五十八批，2026-11-09）加 `lease` 租约段**——先改本节再动代码，旧七段（ratios/cards/gray/conflicts/identity/openLoops/goal）的键序与字节**一个不动**（金样例钉在 `GovernanceStateLeaseSectionTest`）。
- 改口径的规矩（照 `docs/METRICS.md` R2 同款）：先改本文件、写明改的理由与生效日期，再动代码；**不许看了数再改口径**。
- 与 `docs/METRICS.md` 的关系：METRICS.md 管**周报人读数**；本文件管**机器口**。两处的定义（分子/分母怎么数）同源，不另造第二套；**窗口不同**（机器口写死当日，见下），这不是另算，是两个出口各自的窗口，各写各的。

---

## 〇、三条写死（全表适用，不许参数化、不许配置化）

| # | 规矩 | 怎么在代码里成立 |
|---|---|---|
| W1 | **窗口＝当日 00:00 起**（本地时区日历天；起 `00:00`、止 `now`）。跨日只报当日：账里有昨天的行 ⇒ 不计入 | `GovernanceStateTool.dayStartOf(nowMs)`（`LocalDate` 切日，与 `AskGovernance.dayOf` 同一把尺）；带窗口的读数只有 W1 一格，没有 7 天/30 天档 |
| W2 | **分母 0 ⇒ `no_sample`**（**不编 0%**）。0 < 分母 < 10 ⇒ `unknown`（照 METRICS.md R1：样本不足只报分子/分母，不出现百分比）。分母 ≥ 10 才出 `%.1f%%`（`Metrics.decimals` 那把尺） | `GovernanceStateTool.rateOf(n, d)`；rate ∈ `no_sample` ／ `unknown` ／ 百分比串 |
| W3 | **计数只来自账本 replay**：每条读数由「读账」算出，不许从内存单例计数器、界面状态或现场推算拿数 | 各行「来源」列点名的函数就是取数路；`AskGovLedger.replay()`、`DecisionLedger.read()`、`MutualLedger.read()`、`PatrolLedger.read()`、`DecisionReview.read()`、`ConsequenceLedger.currentCanaryBand()`（从 208 账行读档位）、`CommitmentArchive.parse()`、`ConsequenceStore.load()`、`LeaseLedger.replay()`（467 租约账 `产物/.leases.jsonl`，558 加）；唯一非 jsonl 的一路是 `intervention` 的 `run_log`（SQLite 表、只读查询，`MetricsSources.logSteps`）——它是 METRICS.md §1.3 点名的唯一真相源，全仓不存在它的 jsonl 副本，**引用它不算另算，另造一套才算**；第二条例外是 `lease.live`（**内存权威**，467 的结构是"内存是权威、盘只是证据"——账可被她删改，进程重启不复活只有内存知道，拿账本算 `live` 会把"删了账"读成"没有活租约"，理由全文见 §8） |

**窗口的两种取值（就两种，不许第三种）**：
- `当日 00:00 起`——事件与率类读数（ratios 四行、cards 三行）；
- `不适用（状态读数）`——队列/档位/身份/欠账类（gray、conflicts、identity、openLoops）＋ `lease.live`（租约当下活着几张，558）。这些是**当下状态**，不是事件流：给它们切当日窗，昨天进的队、前天许的诺就会从读数里消失（「数变好看」的经典路），所以状态读数**不切窗**，如实报当下。

**读不到怎么办（527 判据原文）**：每段读不到 ⇒ 该段 `"unknown": "<原因>"`，**不抛异常**，其余段照常返回。判据的牙在 `GovernanceStateToolTest`（降级用例）。

---

## 一、读数清单（五列：名称／定义／来源 文件:函数／窗口／分母 0 怎么说）

### 1. `ratios` 四率

| 名称 | 定义（分子/分母） | 来源 文件:函数 | 窗口 | 分母 0 怎么说 |
|---|---|---|---|---|
| `ratios.violation` | 违规率：被撤销的免问动作 ÷ 免问动作（按原账行 id 去重；口径照 METRICS.md §1.1，v1） | `app/src/main/java/com/example/zhengdao/baopu/agent/tools/GovernanceStateTool.kt`:`ratioViolation`（读 `DecisionLedger.read`；`ROLLBACK_OF` 行**不切窗**——回滚可能晚于当日，只认它指到的当日 `auto_act` 行） | 当日 00:00 起 | `rate="no_sample"`（分母 0）；1–9 ⇒ `rate="unknown"` |
| `ratios.misjudge` | 误判率：抽检判「错」÷ 已判（同 record_id 取最后一条；口径照 METRICS.md §1.2，与 `DecisionReview.latestPerRecord` 同源同算法） | `GovernanceStateTool.kt`:`ratioMisjudge`（读 `DecisionReview.read`） | 当日 00:00 起（按**判定时刻**切） | 同上 |
| `ratios.intervention` | 干预率：有摆卡（`AwaitConfirm`）的 (会话,步) ÷ 有动作痕迹的 (会话,步)（都按 (会话,步) 去重；口径照 METRICS.md §1.3） | `GovernanceStateTool.kt`:`ratioIntervention`（行来自 `MetricsSources.logSteps(run_log)`；装配层注入，见 `GovernanceStateTool.env`） | 当日 00:00 起 | 同上；取数壳未装配 ⇒ `{"unknown":"run_log 取数壳未装配"}` |
| `ratios.skip` | 跳过率：巡按判「查不了」（`no_data`）的检查项 ÷ 巡按跑过的检查项（无六项明细的老行不计入；口径照 METRICS.md §1.4） | `GovernanceStateTool.kt`:`ratioSkip`（读 `PatrolLedger.read`） | 当日 00:00 起（按巡按时刻切） | 同上 |

每个率的 JSON 形状：`{"n": <分子>, "d": <分母>, "rate": "no_sample"|"unknown"|"xx.x%"}`。

### 2. `cards` 弹卡三数（已有，别再造：`AskGovernance.healthLine()` 同源）

| 名称 | 定义 | 来源 文件:函数 | 窗口 | 分母 0 怎么说 |
|---|---|---|---|---|
| `cards.pass` | 今日自动放过次数（严格档恒 0——healthLine 同口径，不是没数） | `app/src/main/java/com/example/zhengdao/baopu/agent/AskGovernance.kt`:`AskGovernance.healthCounts`（本批从 `healthLine` 提出的同源数据函数；`healthLine` 自己也改调它，话行一字不变） | 当日 00:00 起 | 不适用（计数，0 就是 0） |
| `cards.card` | 今日问卡张数（`AskGovLedger.KIND_CARD` 行数） | 同上 | 当日 00:00 起 | 不适用 |
| `cards.compress` | 今日汇总压卡次数（`KIND_COMPRESS` 行数） | 同上 | 当日 00:00 起 | 不适用 |

JSON 形状：`{"pass": <n>, "card": <n>, "compress": <n>}`；账本根读不到 ⇒ `{"unknown": "<原因>"}`。

### 3. `gray` 灰度（506 登记表，逐能力当前档）

> **第五百八十八批（2026-11-11）补登记 `evolution`**：口径**一个字没改**，仍是「登记表全量，一项一格」，形状版本 `v=3` 不动。变的是登记表补齐了漏掉的一项：517 起 `BaopuRuntime` 的左相读数 `canaryAvailable` 就按线值 `evolution` 读 `currentCanaryBand`，但当批只写了常量、没在 `Canary.kt`:`CanaryCapability` 里登记 ⇒ 读侧认不出、恒回落 `OFF`，写侧又按「没登记在册」把它的档位行全拒，这格读数从来没有真值可读。588 把 `evolution`（进化深度，缺省灰度）补进闭集 ⇒ 本段随之多一格；金样例 `GovernanceStateLeaseSectionTest.goldenGray` 按登记表生长更新（与 568-A 补 `prompt_tail` 同款），**其余七段的键序与字节一个不动**。新增「读档线值必须在注册表内」的源级门禁见 `CanaryReadWireGateTest`。

| 名称 | 定义 | 来源 文件:函数 | 窗口 | 分母 0 怎么说 |
|---|---|---|---|---|
| `gray.<capability>` | 每项自动化能力**当前生效的灰度档**（off/canary/full；只认没被回滚的档位行，一条没有 ⇒ 登记表缺省档） | `app/src/main/java/com/example/zhengdao/baopu/consequence/ConsequenceLedger.kt`:`ConsequenceLedger.currentCanaryBand`（能力闭集＝`Canary.kt`:`CanaryCapability`；账本根读不到 ⇒ 整段 `unknown`） | 不适用（状态读数） | 不适用 |

JSON 形状：`{"<wire>": {"band": "off|canary|full", "cn": "关闭|灰度|全开"}, …}`（登记表全量，一项一格）。

### 4. `conflicts` 冲突队列条数

| 名称 | 定义 | 来源 文件:函数 | 窗口 | 分母 0 怎么说 |
|---|---|---|---|---|
| `conflicts.pending_replay` | 互查冲突**待回放**队列的当前深度：`互查记录.jsonl` 里 `kind=conflict` 且 `pending_replay=true` 的行数（279：冲突并列入账、标「待回放」，回放前一直在队） | `GovernanceStateTool.kt`:`conflictQueue`（读 `app/src/main/java/com/example/zhengdao/baopu/inspect/PatrolMutual.kt`:`MutualLedger.read`） | 不适用（队列深度，状态读数） | 不适用（0＝队空） |

JSON 形状：`{"pending_replay": <n>}`；账本根读不到 ⇒ `{"unknown": "<原因>"}`。

### 5. `identity` 执行体

| 名称 | 定义 | 来源 文件:函数 | 窗口 | 分母 0 怎么说 |
|---|---|---|---|---|
| `identity.id` / `identity.state` | 当前执行体的身份 id（`zdx-` 前缀）与状态（`active`/`revoked`/`rotated_out`）；没有在用行 ⇒ 报最新一行的 id 与状态（撤销/轮换都留痕不删） | `app/src/main/java/com/example/zhengdao/baopu/consequence/ConsequenceStore.kt`:`load`（476 身份账 `身份.json`）＋ `ExecutorIdentity.kt`:`IdentityRules.activeId` | 不适用（即时状态） | 不适用 |

JSON 形状：`{"id": "<zdx-…>", "state": "active|revoked|rotated_out"}`；身份账读不到/未生成 ⇒ `{"unknown": "<原因>"}`。

### 6. `openLoops` 未闭环 top-N

| 名称 | 定义 | 来源 文件:函数 | 窗口 | 分母 0 怎么说 |
|---|---|---|---|---|
| `openLoops.total` | 承诺档案里**未三问闭合**的键数（236「欠账」：有承诺、且 `closed(b)==false`——放弃必须带理由才算闭合） | `app/src/main/java/com/example/zhengdao/baopu/ReflectionCommitments.kt`:`ClosureRules.openBooks`（档案读 `CommitmentArchive.parse`，文件 `产物/进化档案-承诺.md`） | 不适用（欠账是跨日事实） | 不适用（0＝没有欠账） |
| `openLoops.items` | 未闭环 top-N：挂账**最久**的前 N 条（`openBooks` 按 `since` 升序取前 `TOP_N=10`）；每条 `{"key","since","stage"}`（stage＝该键最新一段的线值） | 同上 | 不适用 | 不适用 |

JSON 形状：`{"total": <n>, "items": [{"key": "…", "since": "yyyy-MM-dd", "stage": "承诺|改了没|有没有变好|问过人了"}, …]}`；工作区根不可用 ⇒ `{"unknown": "<原因>"}`。档案文件不存在 ⇒ `{"total": 0, "items": []}`（没写过的档案是真零，不是读不到）。

### 7. `goal` 目标（第四百八十二批新增，2026-11-08）

| 名称 | 定义 | 来源 文件:函数 | 窗口 | 分母 0 怎么说 |
|---|---|---|---|---|
| `goal.sentence` | 这个任务的一句话目标（开工时立的原话，不转述、不改写） | `app/src/main/java/com/example/zhengdao/baopu/plan/GoalMode.kt`:`GoalStore.readGoal`（落点 `<工作区>/产物/.goal.json`） | 不适用（这次任务的状态） | 不适用 |
| `goal.progress` | `已达成项数/状里的项数`（**分子分母都来自军令状**：`done` 是收口读数里有结局的项数，`total` 是冻结那一刻快照的项数；目标自己不存清单） | 同上；收口写入口 `GoalBoard.settle`（引擎收尾调） | 不适用 | 分母 0（没立状/没项）⇒ 不出现这一格：整段回 `unknown` |
| `goal.budget` | 预算用量：`已用工具步数 ÷ 预算步数`，取整到整数百分比串（`40%`）。**算在 `GoalRules.percentText` 一处**，出口与屏幕都只抄字 | `GoalRules.percentText`；步数由 `GoalBoard.tickAndCheck` 数 | 不适用 | 预算步数为 0 ⇒ `未设`（**不编 0%**） |
| `goal.line` | 屏幕上那一行：`目标：… / 进度 3/5 步 / 预算用 40%`（由 `GoalRules.progressLine` 一次拼好，逐字给屏幕抄） | `GoalRules.progressLine` | 不适用 | 同上 |
| `goal.state` / `goal.stateCn` | 五值闭集：`active` 进行中／`achieved` 已达成／`failed` 未达成／`pending_review` 待验收／`stopped` 已停下。**`achieved` 只由 PASS 换来**（三态判据是 332 的 `Contracts.Status`，不另立一套） | `GoalRules.stateOf` | 不适用 | 不适用 |

JSON 形状：`{"sentence":"…","state":"active","stateCn":"进行中","progress":"3/5","budget":"40%","pledge":"<状 id>","line":"目标：… / 进度 3/5 步 / 预算用 40%"}`；没立目标 ⇒ `{"unknown":"这次会话还没有立目标"}`（**不补一行「暂无」**：那会被读成一个读数）。

### 8. `lease` 租约（第五百五十八批新增，2026-11-09）

> 单上的说法是「增第七段」——那是 527 时的段数；482 已把 `goal` 记为第七段，所以 `lease` 按**出口键序排在最后**（第八段、registry §8）。**旧七段的键序与字节一个不动**（金样例钉在 `GovernanceStateLeaseSectionTest`）。

| 名称 | 定义 | 来源 文件:函数 | 窗口 | 分母 0 怎么说 |
|---|---|---|---|---|
| `lease.live` | **当下活着**的租约张数（跨会话：未撤销且未过期；467 的"活"）。**取数路是内存权威 `LeaseStore`，是 W3 的明文例外**——467 的结构是"内存是权威，盘只是证据"：账可被她删改（判定不受影响正是靠这一点），进程重启不复活也只有内存知道；拿账本算 `live`，她删一次账就把"活着"读成"归零"，是把证据当权威。同一条理由下，`live` 不切窗（状态读数） | `app/src/main/java/com/example/zhengdao/baopu/authz/LeaseStore.kt`:`LeaseStore.liveCount`（558 新增的**只读**访问器；grant/hit/revoke/evict 一概没动，467 判定逻辑零改动） | 不适用（状态读数） | 不适用（0＝手里没有活租约） |
| `lease.grantedToday` | 今日授予张数（`kind=grant` 行数，按 `at` 切当日窗） | `LeaseLedger.replay()`（467 租约账 `产物/.leases.jsonl`；取数在 `app/src/main/java/com/example/zhengdao/baopu/authz/LeaseReadings.kt`:`counts`——558 从账本 replay 提出的**同源数据函数**，出口段与体检话行读同一份，没造第二遍） | 当日 00:00 起 | 不适用（计数，0 就是 0） |
| `lease.hits` | 今日命中次数：判定点 `LeaseVerdict.Hit` 且**收口落账**的次数（`kind=use` 行数；"问过≠做过"同款纪律——判定点命中但没收口的，不落账也不计） | 同上（`kind=use`） | 当日 00:00 起 | 不适用 |
| `lease.savedCards` | 今日免卡张数：**命中即免卡（467 判据原话）**，一次命中就是少弹一张卡——与 `hits` 是**同一行账的两个视角**（账本视角叫命中、卡面视角叫免卡），同源恒相等；这格是对账格，两格不等＝账损坏，出口照实报、不修数 | 同上（`kind=use`） | 当日 00:00 起 | 不适用 |
| `lease.revoked` | 今日撤销张数：`kind=revoke` 且 `why ∈ {user, evicted}`（人撤／打满淘汰——都是没到期就收回；467 的失效词表里这两类同属"撤了"） | 同上（`kind=revoke`） | 当日 00:00 起 | 不适用 |
| `lease.downgraded` | 今日降权次数：`kind=downgrade` 行数（越权满步降一档）＋ `kind=revoke` 且 `why=downgrade`（再满一轮降权到底）——两种降权事件都算，每行账只归一格（撤销/降权/过期三格**恰好分完**全部 `revoke` 行） | 同上（`kind=downgrade` ＋ `kind=revoke`） | 当日 00:00 起 | 不适用 |
| `lease.expired` | 今日过期张数：`kind=revoke` 且 `why=expired`（到期那一下落的就是撤销行、why=expired——467 的 `expire` 即此） | 同上（`kind=revoke`） | 当日 00:00 起 | 不适用 |

JSON 形状：`{"live":0,"grantedToday":0,"hits":0,"savedCards":0,"revoked":0,"downgraded":0,"expired":0}`（键序照此）；租约账落点未装配（`LeaseLedger.root()==null`——生产侧落点由管线判定时 `rememberRoot` 喂，首启没跑过任何管线动作时就是 null）⇒ 整段 `{"unknown":"租约账落点未装配"}`，不编零；落点在而账文件没写过 ⇒ 全零（真零，同 `openLoops` 的口径）。

**同一份数的三个读口（不许各造一遍）**：① 本段（机器口）；② 体检话行 [AskGovernance.healthLine] 尾句 `租约 · 活 N/今日免卡 M`（**显示条件：`live>0 或 grantedToday>0`**——今天有过授予或手里还有活租约才出现；全新进程什么都没有 ⇒ 整句不出现，不是显示 0）；③ 自治理中心读数区那一行 `租约 · 活 N · 今日免卡 M · 降权 K`（显示条件同②；`K=0` 时只省第三段，整行仍显示）。三口取数都在 `LeaseReadings.counts` 一处。

---

## 二、工具出口形状（`governance_state` 返回的八段 JSON）

```
{
  "v": 3,                          // 558 加 lease 段 ⇒ 形状版本 2 → 3
  "day": "yyyy-MM-dd",            // 当日（本地时区）＝ W1 窗口的那一天，读数与它对账
  "ratios":   { "violation": {…}, "misjudge": {…}, "intervention": {…}, "skip": {…} },
  "cards":    { "pass": 0, "card": 0, "compress": 0 },
  "gray":     { "<capability>": { "band": "…", "cn": "…" }, … },
  "conflicts":{ "pending_replay": 0 },
  "identity": { "id": "zdx-…", "state": "active" },
  "openLoops":{ "total": 0, "items": [ … ] },
  "goal":     { "sentence": "…", "state": "active", "progress": "3/5", "budget": "40%", "line": "目标：…" },
  "lease":    { "live": 0, "grantedToday": 0, "hits": 0, "savedCards": 0, "revoked": 0, "downgraded": 0, "expired": 0 }
}
```

- `v` ＝ 本口径版本（**3**）；改口径先改本文件再升 `v`。`lease` 排在最后（键序追加，旧七段的键序逐字节未动）。
- **对拍判据**（527）：脚本（独立从账本原文重算）与工具出口**逐项相等**——`GovernanceStateToolTest` 是它的牙；558 的 `lease` 段对拍＋金样例在 `GovernanceStateLeaseSectionTest`（`live` 一格是内存权威，脚本侧不重算、以驱动状态钉期望值）。
