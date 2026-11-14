# 第一方资产名册（`licenses/FIRST-PARTY.md`）

> 本文件是 `tools/check-license-policy.py --assets` 的**第一方名册**：名册里标 `GZ-AG` 的行
> ＝ 该文件是**第一方**（本仓自产），不按第三方判许可。
> 双向校验（440）：**名册有、`assets/` 里没有 ⇒ 红**（名册过期）；
> **`assets/` 里有、名册与 `licenses/ASSETS.md` 都没有 ⇒ 红**（漏登记）。

## 来源标识 GZ-AG

`GZ-AG`（同义写作 `GZAG`）＝ G（用户姓首字母）＋ Z（其先生姓首字母）＋ AG（AI agents）
—— **本仓唯一的第一方来源标识**（定义见 `AGENTS.md`「第一方标识 GZAG」）。
门禁只认这一个记号（连字符可有可无、大小写不论，归一后必须恰好是 `GZAG`）；
别的写法（`GZ-AG-2`、`自有`、空着……）一律判「名册不合格」⇒ 红，**不搞变体暗号**。

## 与 ASSETS.md 的分工（为什么是两张表）

- **第一方**（本仓自产）：登记在这里，**跳过许可判定**。第一方文件没有「上游许可」可查，
  硬往 `ASSETS.md` 六列表里填一行「版本/许可/来源」就是编造——台账第一诫是不许编。
- **第三方**（上游来的模型/字体/rootfs）：登记在 `licenses/ASSETS.md` 六列表，
  走 `ALLOWED` / `DENY_FAMILIES` / `classify()` 判定，豁免路径照旧关着。
- **同一文件不许同时在两表**（两表冲突 ⇒ 红）：一个文件不能既是自己的又是别人的。

## 怎么登记一行

在下面的名册表里加一行，三列**缺一不可**（空格子那一行不算登记，门禁会红）：

| 列 | 填什么 |
| --- | --- |
| 资产路径 | **相对 `app/src/main/assets/` 的精确文件路径**（正斜杠；可用反引号包裹；不许目录、通配、`..`） |
| 来源标识 | `GZ-AG`（每行都必须是它；这是名册的全部意义） |
| 说明 | 一句话写清这是什么；没有可写的填 `—`（不许空着） |

三条规矩：

1. **一文件一行**。目录、通配都不算登记；名册不认「以后会加」的文件。
2. **名册行的唯一校验就是「文件真在」**。删了文件必须删行——名册有、磁盘没有 ⇒ 红
   （这点比 `ASSETS.md` 严：第三方行过期只提醒，因为删第三方资产是合法动作；
   第一方行过期说明台账在撒谎，而它没有任何别的判定兜底）。
3. **新增第一方文件当天就登记**。文件先落 `assets/`、后进名册的窗口期门禁就是红的，
   这是设计好的：红点必须有人看见并签字，不许静默放行。


## 当前名册（13 条 · 2026-11-15 · 第 440 批＋验收补 1 条＋547 产出卡 1 条＋637 Mermaid 离线页 1 条＋645 观察脚本 1 条）

（起步即全量：433 建资产门禁时 `assets/` 下只有这 9 个第一方文件，当时按「不编条目」
判了漏登记红；本批把它们逐条签字入册，`ASSETS.md` 保持空表头，等 414 解冻登记真第三方。）

| 资产路径 | 来源标识 | 说明 |
| --- | --- | --- |
| baopu/cards/logistics.waybill.json | GZ-AG | 抱朴卡片：物流面单查询卡（212 批，模板 JSON） |
| baopu/cards/study.knowledge.json | GZ-AG | 抱朴卡片：学生知识点卡（演示样例，默认折答案） |
| baopu/cards/write.topic.json | GZ-AG | 抱朴卡片：选题方案卡（只借 190 的方案字段做样例） |
| baopu/cards/output.produce.json | GZ-AG | 抱朴卡片：产出卡（547 批 OutputCards 首次进生产；依据闸对产出卡生效） |
| baopu/policy_v1.json | GZ-AG | 证道政策表 v1（只读随包发布，只装可调参数） |
| baopu/skills/T9取数/SKILL.md | GZ-AG | 抱朴技能：T9 导入建当日本地表再答（查今天的车） |
| baopu/skills/产物清理/SKILL.md | GZ-AG | 抱朴技能：列清单勾选后移回收站（清发完的） |
| baopu/skills/微头条流程/SKILL.md | GZ-AG | 抱朴技能：按台账查重再动笔（写微头条） |
| persona/factory_c.md | GZ-AG | 出厂项目人设（只读）：先给结论再给理由 |
| policy/anchor-set.jsonl | GZ-AG | 锚定集：人工判例（旧判据 vs 人工裁定逐条对照） |
| mermaid/mermaid.html | GZ-AG | 637 批：Mermaid 离屏渲染页（离线页：CSP 全禁＋strict／htmlLabels:false＋源码只走 JS 字符串参数） |
| models/model-labels.json | GZ-AG | 模型显示名映射表（443 批：13 条映射 6 厂商，每条带 source+asOf；本仓自产、随包读一次） |
| bridge/observe_v1.js | GZ-AG | 玄览 bridge：内置观察脚本 v1（645 批，随包 assets 一份；脚本 SHA-256 写死进端口门禁） |
