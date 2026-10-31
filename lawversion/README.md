# lawversion —— 法典版本化测试场（第三百零九批）

法条有两份：私有《法典》md（`../_zd-private/docs/抱朴-法典-2026-10-27.md`，L1–L33＋L30b）
与 `tools/check-law-gates.py` 的门禁覆盖。两边分离 ⇒ 早晚漂移。本目录放的是
**让漂移会红、让候选法条先回放、让演进必落三态**的测试与样本。

## 三个闸

| 闸 | 实现 | 判据 |
|---|---|---|
| md ↔ 门禁同笔提交 | `tools/check-law-gates.py`（判据 7） | md 的 L 号集合 == 门禁实扫覆盖（law/ ∪ law/spec/）的 L 号集合，不等即红 |
| 法条回放闸 | `tools/law-replay.py replay` | 新旧法条各跑一遍过去 30 天账本，出 **多拦／少拦／多问** 三组数 |
| 三态收敛 | `tools/law-replay.py evolve` | 结论只落 **已应用／已回滚／显式无变更**，没有"再讨论一轮"这个第四态 |

## 准入阈值（309 §二·3 初值；改这里必须同步 `tools/law-replay.py` 的常量并御批）

- **少拦 ≥ 1 直接否决**——安全只能收紧，不能放松。沿严格度阶梯 `allow < ask < block`，
  新法条每**下**一步记一条少拦（`block→ask` 也算：把"拦"降成"问"就是放松了一道）。
- **多问 ≤ 每日 1 次**（`--ask-cap-per-day 1.0`，窗口默认 30 天 ⇒ 上限 30 次）。
- 通过 ⇒ 进御案排队（**仍要人批**）；不通过 ⇒ 退回候选区，附差异清单。

## 跑法（纯标准库；不联网、不调模型、不跑 gradle）

```bash
python lawversion/run_tests.py                     # 三组测试全跑（47 条）
python tools/check-law-gates.py --selftest         # 门禁形状机检自证会红（含两向演练）
python tools/check-law-gates.py                    # 真核：本机会拿私有侧真《法典》比对
python tools/law-replay.py replay --candidate lawversion/fixtures/candidates/tighten.json \
    --ledger lawversion/fixtures/ledger-sample.jsonl --baseline lawversion/fixtures/baseline.json
python tools/law-replay.py evolve  --candidate ... --ledger ... --baseline ... --out rec.json
```

## md↔门禁：两向演练

- **方向一（只加 md ⇒ 红）**：`test_md_gate_sync.py::EndToEnd::test_direction_one_md_only_is_red`
  ——给一份与门禁同号的 md 加一行 `| **L34** | … |`，脚本退出码必须 1，且点名 L34。
- **方向二（只加门禁 ⇒ 红）**：`…::test_direction_two_gate_only_is_red`
  ——往 law/ 塞一个 L99 的 GateTest，脚本退出码必须 1，且点名 L99。
- 同款演练在 `check-law-gates.py --selftest` 里还有一份（函数级）。

**md 的找法**：`--md` 显式路径 > 环境变量 `ZHENGDAO_LAW_MD` > 仓内 `抱朴-法典*.md`
> 私有侧 `../_zd-private/docs/抱朴-法典*.md`。
**md 不可得 ≠ 通过**：本地（私有侧在）严格比对；GitHub CI 没有私有侧，此时打一条明说
"没跑"的状态（JSON 里 `md_sync.status = "md_not_found"`），**不是静默跳过**；
要连"找不到 md"也红，加 `--md-strict`。
**现状（2026-10-31）是诚实红**：md 34 条 vs 门禁覆盖 23 条，md 独有
`L1 L3 L6 L16 L19 L28 L29 L30 L31 L32 L33`——这 11 条要么补门禁、要么在 md 标注
"约定未门禁化"，下一批两边同笔提交销账。

## 法条的表达（账本级回放，诚实声明）

法条 = 对 208 账本记录字段的显式规则，按序求值、**首条命中生效**、无命中＝放行。
`add_rule` 追加到末尾（现行规则优先，候选盖不掉旧法——要盖就 `replace_rule`）。

- 现行规则集（`--baseline`，缺省＝无显式规则全放行）：
  `{"rules": [{"id", "law", "action": allow|ask|block, "match": {kinds?, groups?, actors?, reversible?}}]}`
- 候选（`--candidate`，308 生成或人提）：
  `{"id", "title", "source", "ops": [{"op": add_rule|replace_rule|remove_rule, …}]}`
- 账本（`--ledger`）：208 决策记录流**原样 JSONL**（`抱朴记忆/决策记录.jsonl`，
  字段形状见 `baopu/DecisionLedger.kt`；`fixtures/ledger-sample.jsonl` 是同形状样本）。
  坏行/缺 `id`/`at` ⇒ 退出码 2（坏行会让回放悄悄少看账，宁可红）。
- match 只认 `kinds/groups/actors/reversible` 四个字段——多一个都是打错字静默不命中，宁可红。

回放窗口 = **账本最新一条**往回 30 天（不取挂钟：同一份账本任何时候跑，三组数一致）。

## 三态收敛（309 §三）

`evolve` 的结论函数只从 `STATES = ("已应用", "已回滚", "显式无变更")` 里出：

- 不达标（少拦≥1 或多问超阈）⇒ **已回滚**（退出码 1），附差异清单退回候选区；
- 达标且规则集有实改 ⇒ **已应用**（退出码 0），进御案排队、仍要人批；
- 达标但规则集逐字段没动 ⇒ **显式无变更**（退出码 0），写明为什么不用改。

输入坏（文件读不到／格式不对／op 指向不存在的规则）⇒ 退出码 2、**不产出演进状态、不落盘**
——宁可没有结论，不许落一个暧昧结论。`conclude` 里有一条恒真守卫：
谁往 `STATES` 塞第四态，守卫当场炸（`test_evolution.py::test_guard_fires_if_states_widened`）。

## 离线纪律（判据⑤，怎么证明）

不是口头承诺：`test_law_replay.py::OfflinePurity` 扫两个脚本的源码——
出现 `urllib/socket/http/requests/subprocess/…` 或 `openai/anthropic` 等 import、
`os.system/popen/eval/exec` 调用 ⇒ 红；`law-replay.py` 的 import 必须落在
`{argparse, json, sys, datetime, pathlib, __future__}` 白名单内。

## 本批没做的（诚实账）

- 11 条 md 独有法条的门禁化／标注：下一批两边同笔提交。
- 回放是**账本级**（行为上更严/更松），量不了"语义上更严"——那部分仍靠人批与勘验。
- CI（`.github/workflows/ci.yml`）暂未跑 `lawversion/run_tests.py`（309 只许碰三个路径）；
  本地门禁脚本里建议加一行。
