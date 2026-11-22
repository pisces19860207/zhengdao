# 773 训练集 · 规则合成集（段①＋段②）

> 本目录只做两件事：**段①样本格式** 与 **段②规则合成集**。段③训练档位空间模型不做（等数据与 786 评测口径稳定后另立）。
> 不做训练 · 不做评测（786 管）· 不改 731/734/730/748 判据 · 不联网 · 纯 Python 标准库。

## 文件

| 文件 | 作用 |
| --- | --- |
| `schema.py` | 段①：六字段定义、档位字段白名单（引用既有字段）、缺 source 拒收 |
| `rules.py` | 段②：五类转移的可执行规则 `apply(before, action) -> after`（纯函数、确定性） |
| `synth.py` | 段②：生成器，枚举五类 × 等距取样，产出 220 条 |
| `verify.py` | 七项判据自证 + 六个反例自检 |
| `data/train-synth.jsonl` | 交付数据，220 行，每行一条样本 |

## 段① 样本格式（每条必带六字段）

```json
{"id":"SYN-TUR-000001","stateBefore":{"turgorGrade":0},"action":{"op":"up","step":1},
 "stateAfter":{"turgorGrade":1},"ruleVersion":"731-GenitalModel.turgorGrade","source":"rule-synth"}
```

- `id`：`SYN-<类>-<6位序号>`，类前缀 `TUR/DES/THE/FAT/INJ`，与 786 的 `NAR-` 前缀天然异域。
- `stateBefore/After`：**档位向量**，键全部来自既有字段、不新造：
  - turgor：`turgorGrade`（660，EHS 0–4）
  - 734：`sensitivityGrade` 0–3 / `discomfortGrade` 0–2 / `frequencyGrade` 0–3
  - 730：`scrotumSagGrade` 1–5 / `scrotumVolumeGrade` 1–5
  - 658：`fatigue` 0–3 / `heartRateBand` 0–3
  - 748：`injuryGrade` 0–3 / `painGrade` 0–3 / `recoveryGrade` 1–3（0＝GONE 终态）
- `action`：规则的可执行描述（`op` 必填，可带 `step/intensity/tier/elapsedRounds`）。
- `ruleVersion`：指向产生该转移的规则来源（批次号＋既有符号）。
- `source ∈ {rule-synth, llm, human}`：**缺 source 拒收**。本集全部 `rule-synth`。

## 段② 五类转移（各 44 条，合计 220）

| 类 | 规则来源 | 输入档 → 规则 → 输出档（可执行） |
| --- | --- | --- |
| TUR 充血 | 731 GenitalModel | `turgorGrade ±step` → 钳 0–4 |
| DES 适应脱敏 | 734 SensitivityCurve | `SENS_TABLE[intensity]=[0,1,2,3,2,1]`；intensity>3 进饱和反转；discomfort=intensity−3 钳 0–2 |
| THE 温湿提睾 | 730 body-zones | cold 收紧 sag−1 / heat 松弛 sag+1 / volume 动作改容积档 → 钳 1–5 |
| FAT 疲劳累积 | 658 PhysioGrades | exert +step / rest −step，心率档同向跟随 → 钳 0–3 |
| INJ 受伤恢复 | 748 InjuryGrades | impact 经 `INJURY_START=[0,0,1,2,3]` 起档；recover 按 `budgetRounds={1:1,2:3,3:6}` 单调回落，到点回 0（GONE） |

等距取样保证每个起点档／强度档都进集（含 734 饱和反转点 intensity=4/5）。

## 复跑

```bash
python3 synth.py --out data/train-synth.jsonl        # 重新生成（确定性）
python3 verify.py --selfcheck                        # 七判据 + 六反例，全绿退出 0
python3 verify.py --data data/train-synth.jsonl \
  --eval-manifest ../../docs/evals/narrative/manifest.json \
  --eval-items ../../docs/evals/narrative/items.jsonl
```

确定性证据：两次独立进程生成的 JSONL 逐字节 `cmp` 一致，sha256 见交付报告。
