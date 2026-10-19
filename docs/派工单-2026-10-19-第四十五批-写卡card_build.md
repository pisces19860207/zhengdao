# 第四十五批 · 写卡 `card_build`（从母档到三套格式的卡）

- 派单：2026-10-19 蓝总管
- 基线：`63c5a28e`（或开工时最新 main）
- 承接：qoder
- 红线：公开仓库，真实卡零字节入库；产物只写工作区；**不覆盖已有文件**；不改 39 批的 `card/` 判据（可以复用，不许改其语义）。

## 0. 现状

- 39 批给了「看懂卡」：`CardFormat`（认脸）、`CardCheck`（体检）、`CardPng`（酒馆 PNG 导出）、`CardRoster`（外部名册）。
- 缺「做卡」：目前只能由模型在聊天里写五件套，人工粘到平台/文件——**没有组装与校验闭环**。

## 1. 目标

新增工具 **`card_build`**（CONFIRM）：读工作区里的一份**母档 JSON**，产出对应格式的卡/世界书/五件套骨架，并**自动过一遍 `CardCheck`** 自检。

### 1.1 母档（输入）约定

工作区文件 `母档.json`（路径可传参），形状自定但至少要有：

```json
{
  "format": "fengyue | tavo | st",
  "name": "作品名",
  "persona": "人设",
  "world": ["世界设定/规则条目…"],
  "keywords": ["触发词1", "触发词2"],
  "style": "文风/叙述要求",
  "html": "配置器 HTML 或空",
  "css": "内置 CSS 或空"
}
```

### 1.2 产出（按 format）

| format | 产出文件（写到 `outDir`，默认 `成品/<name>/`） |
|---|---|
| `fengyue` | `world_book.json`（12 字段：`key/value/group/key_region/value_region/sort/depth/probability/match_type/enable/value_type/value_configs`；`key` 按 `_or_…@wb@…` 生成）＋ `前置词.txt`／`提示词.txt`／`后置词.txt`／`内置CSS.txt`／`详细介绍.html`（模板骨架，填空） |
| `tavo` | `card.json`（CCv3：`name/description/first_mes/personality/scenario/mes_example/extensions`）＋ `lorebook.json`（entries 数组，26 键最小集） |
| `st` | `card.json`（CCv3 卡）＋ `card.png`（调 `CardPng` 写 `chara`+`ccv3` 两块） |

**生成纪律**：
- 世界书 `key` 一律走合法分隔符；单字关键词**拒绝**并回话（`_or_` 单字＝积分刺客）；
- 概率模式默认关闭（`value_type=0`、`value_configs=[]`）；
- 所有文本字段按母档原样写入，**不做二次改写**。

### 1.3 自检（同一次调用里做）

- 生成后立刻 `CardCheck.check(parsed, roster)`（名册按 §17 规则从 outDir 找）；
- 报告里列出 findings（ERROR/WARN/INFO）＋「认脸结果」；
- 有 ERROR ⇒ **不删产物**，但明确回报「自检未过，见上」；
- 不许为了自检通过而改母档内容。

## 2. 工具契约

- 名字：`card_build`；权限 **CONFIRM**（会写盘）；
- 参数：`spec`（母档相对路径，默认 `母档.json`）、`out_dir`（可选，默认 `成品/<name>/`）；
- 输出：产物清单（路径＋字节）＋自检结论；
- 安全：路径必须 `BaopuGuardCore.checkPath` 在工作区内；**已存在同名文件一律拒绝**（不覆盖）；
- strings：中英各 4 条（名/说明/参数/结果），`ToolRegistrySwitchTest` 计数 24 → **25**。

## 3. 测试（全部合成夹具）

- 三套 format 各一条：产出文件齐、`card_inspect` 能认脸、`world_book.json` 过 `CardCheck` 无 ERROR；
- 单字关键词 ⇒ 拒绝并回话；
- `out_dir` 已有同名文件 ⇒ 拒绝；
- 路径越界 ⇒ 拒绝；
- 概率模式：`value_type=0` 与 `value_configs=[]` 默认值正确。

## 4. 验收与打回

- 全量单测 0 fail；lint 预算不升（当前 700/errors 178）；
- 打回：覆盖已有文件、越界写、单字关键词放行、自检结果被吞、工具计数不对。

## 5. 三句话交接

1. 先读 `app/src/main/java/com/example/zhengdao/baopu/card/`（CardFormat/CardCheck/CardPng/CardJson）与规格 §3（三套字段）、§7（无损）、§17（名册）。
2. 只新增 `card_build` 与测试；**不改卡包判据**；产物永不覆盖。
3. 验收要能跑出来（三套各产一套＋自检通过＋越界/覆盖被拒），不要口头结论。
