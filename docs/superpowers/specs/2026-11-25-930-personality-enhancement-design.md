# 930 人格维度增强设计

- 批次：930
- 基线：`390fed25561b90564841ae6f779a42c8b21272ed`
- 分支：`feat/personality`
- 工作树：`wt-930`
- 方案：同包内部增强聚合，不新建平行人格包
- 外部依赖：无第三方 API、SDK、模型或平台政策；无需官方 changelog 核验

## 1. 背景与边界

819 已在 `baopu/state/persona/` 提供唯一的人物模型：`CoreLayer`、`TraitLayer`、`SurfaceState`、`SurfaceDeriver`、`PersonaVoice`、`VoiceSwap` 与 `PersonaChecks`。930 是增强单，不是第二套人格系统。

本单不修改 747 身体锚点、710 行为六条、921 人际状态，也不从职业（882）、关系（921）、身体锚点（747/817）推导 values。全部新增生产代码留在 `baopu/state/persona/`；不创建 `baopu/state/personality/`。

模型只做确定性记录与校验，不做心理评估、诊断或人格决定论。

## 2. 方案选择

采用内部聚合：

```text
PersonaEnhancement(
    base: Persona,
    values: PersonaValues,
    state: PersonaMoodState,
    contradictions: List<TraitContradiction>,
)
```

`base` 继续持有唯一 core/trait/surface/voice。聚合不复制、不改名、不重建这四类字段。新增类型全部 `internal`，不增加顶层公开入口。

不直接把新字段加到公开 `Persona`，因为那会迫使 values/state/contradiction 类型公开并扩大 819 API。也不采用“分别传参给检查器”的方案，因为调用方需要自行维护平行数据，容易产生漂移。

## 3. 四层字段与 core 对齐

| 层 | 唯一落点 | 930 改动 | 边界 |
|---|---|---|---|
| traits | `TraitLayer` | 保留 819 七轴，新增 Big-Five 五轴，均 `0..3` | 不新建第二套特质类型；变动必须带 927 三根证据 |
| values | `PersonaValues` | 家庭、事业、自由、安全、亲密、面子、金钱、信仰八轴，各一份 `0..3` 权重 | 不由职业、关系、身体锚点推导；只与 core 做显式冲突检查 |
| states | `PersonaMoodState` | 心境、情绪、压力与可选说明 | 动态输入，不属于 core/trait/surface；不反推任何稳定层 |
| expression | `SurfaceState` + `SurfaceDeriver` + `PersonaVoice` | `SurfaceState` 增加 `tags`，默认空 | 不新增 930 expression 推导器；二次元标签只落 surface |
| core | `CoreLayer` | 不改字段、枚举、排序或既有判据 | 只供 values/core 显式冲突检查 |

### 3.1 traits：819 七轴 → 930 十二轴

`TraitLayer` 新增五个 `AxisValue(tier, source)` 字段：

- `openness: AxisValue = AxisValue(1, "DEFAULT")`
- `conscientiousness: AxisValue = AxisValue(1, "DEFAULT")`
- `extraversion: AxisValue = AxisValue(1, "DEFAULT")`
- `agreeableness: AxisValue = AxisValue(1, "DEFAULT")`
- `emotionalStability: AxisValue = AxisValue(1, "DEFAULT")`

`AxisValue` 嵌套在 `TraitLayer` 内，不新增顶层公开类型。`tier` 强制 `0..3`；`source` 只接受 `DEFAULT` 或 `EXPLICIT`。五轴允许省略，省略时为中档 `1` 且明确标记 `source=DEFAULT`；显式构造使用 `source=EXPLICIT`。`TraitLayer` 提供稳定轴名常量与 `bigFiveLevels()`，不新增特质枚举。

`PersonaChecks.traitChecks` 的提示文本与注释同批改为“819 七轴 → 930 十二轴（新增五因子）”，并展示十二轴。既有七轴不排除、不改名。

### 3.2 values：八轴相对权重

```text
ValueAxis = FAMILY, CAREER, FREEDOM, SECURITY, INTIMACY, FACE, MONEY, FAITH
ValueWeight = axis + tier
PersonaValues = List<ValueWeight>
```

检查器要求：

- 缺轴、重轴 ⇒ 红 `VALUES_AXES`；
- 任一档位不在 `0..3` ⇒ 红 `VALUES_TIER`；
- 同轴出现互相冲突的档位（例如家庭同时 0 和 3）⇒ 红，归入 `VALUES_AXES`，消息写明冲突轴；
- 八轴完整且档位合法才进入 values/core 冲突检查。

values/core 冲突只记录，不判红：

- core 的 `SECURITY` 排前二，且 values 为“安全 0／自由 3” ⇒ WARN `VALUES_CORE_CONFLICT`；
- core 的 `FREEDOM` 排前二，且 values 为“自由 0／安全 3” ⇒ WARN `VALUES_CORE_CONFLICT`；
- 消息写清 core 需求、core rank、两个 values 轴及档位；
- 其余组合不联想、不推断。

### 3.3 states：动态层

```text
MoodTier = 低落, 平稳, 轻快, 高涨
Emotion = 平静, 喜悦, 悲伤, 愤怒, 害怕, 厌恶
PersonaMoodState = mood + emotion + pressure(0..3) + note?
```

`pressure` 越界 ⇒ 红 `STATE_PRESSURE`。`note` 可空，若填写则接受临床词、污名词、二次元标签与决定论文本的同一套位置检查。

`PersonaMoodState.kt` 不得引用 `TraitLayer`、`CoreLayer`、`SurfaceState`、`Persona`、`SurfaceDeriver`，不得出现 `derive`、`infer`、`predict`。

### 3.4 expression：只扩 surface 标签

`SurfaceState` 改为：

```text
SurfaceState(levels: List<SurfaceLevel>, tags: List<String> = emptyList())
```

- 819 的 `levels` 构造方式不变；
- `SurfaceDeriver.derive` 源码逐字节不改；
- 新 `tags` 只收静态二次元标签表；
- 非 surface 的 core、values、state、矛盾说明、voice 参数或 narration 文本命中标签 ⇒ 红 `ANIME_TAG_OFF_SURFACE`；
- 标签词表只做精确词组匹配，不做同义词扩展。

## 4. 硬判据

### 4.1 927 特质变动三根

新增 `TraitChangeEvidence(eventRef, latentRef, longtermRef)` 与 `TraitChanges.review(before, after, evidence)`。

- 比较完整 `TraitLayer` 十二轴，变化轴顺序固定；
- 只有 `eventRef`，没有 `latentRef`/`longtermRef` ⇒ 红 `TRAIT_CHANGE_SINGLE_EVENT`；
- `latentRef` 或 `longtermRef` 非空，可同时带事件引用 ⇒ 通过；
- 三种引用全空 ⇒ 红 `TRAIT_CHANGE_NO_ROOT`；
- 前后无变化 ⇒ WARN `TRAIT_CHANGE_NONE`；
- 同输入重复调用，`changedAxes` 与 `CheckReport.line()` 逐字节一致。

`TraitChanges.kt` 不得引用 mood、emotion、pressure 或 `PersonaMoodState`。

### 4.2 内部矛盾

新增 `TraitContradiction(highAxis, lowAxis, note)`。

- 轴名必须来自 `TraitLayer` 新增的五个 Big-Five 轴；既有七轴是枚举，不伪造数值档位；
- high 与 low 不得相同；
- high 档位 `>=2`，low 档位 `<=1`；
- 说明非空；
- 不满足 ⇒ 红 `TRAIT_CONTRADICTION_INVALID`；
- 列表为空 ⇒ WARN `TRAIT_CONTRADICTION_MISSING`，消息包含“可能是人设不是人格”。

主夹具固定为“高宜人性／低尽责性”：`agreeableness=AxisValue(3, "EXPLICIT")`、`conscientiousness=AxisValue(0, "EXPLICIT")`。测试同时读取实际档位、source 与检查结果，不用注释代替证据。

### 4.3 临床词：静态四列表

`ClinicalTermRule` 固定四列：

1. `phrase`：精确词组；
2. `category`：人格障碍／依恋类型／精神科诊断／伪心理标签；
3. `allowedPosition`：台词；
4. `exemption`：叙述者＋显式医生或心理从业者＋同句不确定词 ⇒ WARN。

匹配不区分英文大小写；不用单字匹配，不做语义判断，不做同义词、向量或模型扩展。词表不含 `Big-Five`；本单五因子是模型字段，不是伪心理标签。

| 词组 | 类目 | 允许位置 | 豁免条件 |
|---|---|---|---|
| 人格障碍 | 人格障碍 | 台词 | 医生/心理从业者+同句不确定词⇒WARN |
| 边缘型人格 | 人格障碍 | 台词 | 同上 |
| 自恋型人格 | 人格障碍 | 台词 | 同上 |
| 表演型人格 | 人格障碍 | 台词 | 同上 |
| 偏执型人格 | 人格障碍 | 台词 | 同上 |
| 分裂样人格 | 人格障碍 | 台词 | 同上 |
| 依赖型人格 | 人格障碍 | 台词 | 同上 |
| 强迫型人格 | 人格障碍 | 台词 | 同上 |
| 回避型人格 | 人格障碍 | 台词 | 同上 |
| 分裂型人格 | 人格障碍 | 台词 | 同上 |
| 依恋类型 | 依恋类型 | 台词 | 同上 |
| 安全依恋 | 依恋类型 | 台词 | 同上 |
| 焦虑依恋 | 依恋类型 | 台词 | 同上 |
| 回避依恋 | 依恋类型 | 台词 | 同上 |
| 回避型依恋 | 依恋类型 | 台词 | 同上 |
| 混乱依恋 | 依恋类型 | 台词 | 同上 |
| 痴迷型依恋 | 依恋类型 | 台词 | 同上 |
| 恐惧型依恋 | 依恋类型 | 台词 | 同上 |
| attachment | 依恋类型 | 台词 | 同上 |
| NPD | 精神科诊断 | 台词 | 同上 |
| PTSD | 精神科诊断 | 台词 | 同上 |
| BPD | 精神科诊断 | 台词 | 同上 |
| 抑郁症 | 精神科诊断 | 台词 | 同上 |
| 双相障碍 | 精神科诊断 | 台词 | 同上 |
| 精神分裂 | 精神科诊断 | 台词 | 同上 |
| 多动症 | 精神科诊断 | 台词 | 同上 |
| 创伤后应激障碍 | 精神科诊断 | 台词 | 同上 |
| 人格量表 | 伪心理标签 | 台词 | 同上 |
| 心理量表 | 伪心理标签 | 台词 | 同上 |
| 心理测量 | 伪心理标签 | 台词 | 同上 |
| 人格测验 | 伪心理标签 | 台词 | 同上 |
| MBTI | 伪心理标签 | 台词 | 同上 |
| 色彩性格 | 伪心理标签 | 台词 | 同上 |
| 九型人格 | 伪心理标签 | 台词 | 同上 |

位置判定顺序固定：

1. `VoiceLine.text` 直接算台词；
2. 其他文本中，命中位置落在完整配对的 `「」`、`“”` 或 ASCII 双引号内，或“他说／她说／问／答／低声说”引导的完整直接引语内 ⇒ 台词；
3. 其余位置 ⇒ 叙述者；
4. 不完整引号不猜测，按叙述者处理。

医生资格不从文本推断，只接受显式 `ClinicalSubjectRole.DOCTOR` 或 `MENTAL_HEALTH_PROFESSIONAL` 输入。同句边界为 `。！？!?；;` 与换行；不确定词只认“可能／也许／看起来像”。

三态结果：

- 台词：放行，不产生临床词 finding；
- 叙述者＋显式医疗角色＋同句不确定词：WARN `CLINICAL_MEDICAL_UNCERTAIN`；
- 叙述者＋无豁免：ERROR `DIAGNOSIS`（沿用 819 稳定红码）；
- 同一输入同时含红与黄时只保留红，不降级。

### 4.4 污名词

静态词组表只收针对人的定型蔑称：直男癌、舔狗、精神病、神经病、娘炮、废物、太监。“恶心／活该／该死／变态”是普通情绪词，不进污名词表。

- 台词 ⇒ WARN `STIGMA_DIALOGUE`，只提示人物语言偏激，不评价人物；
- 叙述者或其他非台词位置 ⇒ ERROR `STIGMA_NARRATOR`；
- 不做语义理解，不因“他骂了一句”而放行或定罪。

### 4.5 决定论禁区

“人格决定命运／性格决定命运”在所有位置判红 `DETERMINISM_FORBIDDEN`。

## 5. 单一真相与源码断言

新增源码断言测试至少覆盖以下六组：

1. 全仓 `CoreLayer`、`TraitLayer`、`SurfaceState`、`PersonaValues`、`PersonaMoodState` 各只有一个声明；不存在 `baopu/state/personality` 或 `baopu.state.personality`。
2. `PersonaMoodState.kt` 不引用稳定层与推导动词；`TraitChanges.kt` 不引用 states。
3. `PersonaValues.kt` 不引用 `Occupation`、`Relationship`、`RelationKind`、`BodyAxis`、`Anchor`、`personaRate`、`state.relation`、`intake.peer`，也不出现 `derive`／`infer`。
4. persona 包只有一个 `object SurfaceDeriver`；五个 930 新生产文件均无 `fun derive`、`object *Deriver` 或 `class *Deriver`。
5. `PersonaLanguage.kt` 无同义词、向量、embedding、语义模型或运行时联想入口；运行时断言词表 34 条、四类齐全。
6. `val tags` 在 persona 包只出现于 `SurfaceState`；非 surface 文本的标签越界由行为测试判红。

源码断言读注释剥离后的文本，并保留字符串字面量，避免注释制造假绿。

## 6. 819 兼容与逐字节证据

### 6.1 core 不动

`CoreNeed`、`CoreDrive`、`CoreLayer` 及 `coreChecks` 的字段、枚举、rank 规则、触发规则均不改。`git diff` 中这些声明必须零变化。

### 6.2 SurfaceDeriver 唯一且不改

`SurfaceDeriver.kt` 必须 `git diff --exit-code`。测试用三组固定快照把 `SurfaceLevel` 序列化为 `axis|tier|source`，与基线串逐字节比较：

```text
基线快照：
PATIENCE|3|轮数0·显性度Lv1|INTOXICATION|0|外部喂入酒意信号|FATIGUE|0|外部喂入疲惫信号|SHAME|0|防守阶段S0·立场中立|TENSION|0|显性度Lv1·硬否决=false

中负载快照（turns=4, salience=3, dStage=3, stance=排斥, veto=false, intoxication=1, fatigue=2）：
PATIENCE|1|轮数4·显性度Lv3|INTOXICATION|1|外部喂入酒意信号|FATIGUE|2|外部喂入疲惫信号|SHAME|2|防守阶段S3·立场排斥|TENSION|2|显性度Lv3·硬否决=false

高负载快照（turns=8, salience=4, dStage=6, stance=排斥, veto=true, intoxication=3, fatigue=3）：
PATIENCE|0|轮数8·显性度Lv4|INTOXICATION|3|外部喂入酒意信号|FATIGUE|3|外部喂入疲惫信号|SHAME|3|防守阶段S6·立场排斥|TENSION|3|显性度Lv4·硬否决=true
```

三组输出的 `tags` 都必须为空。报告同时贴 `SurfaceDeriver.kt` 零 diff、唯一声明 grep 与三组逐字节读数。

### 6.3 819 断言更新留痕

- 更新前：`TraitLayer` 七轴；`PersonaChecks.traitChecks` 输出“七轴齐”；
- 更新后：`TraitLayer` 十二轴；提示明确写“819 七轴 → 930 十二轴（新增五因子）”；
- 不把新轴排除在 `entries`、完整性检查或测试之外；
- `SurfaceState.tags` 默认空，既有 surface 构造与五轴顺序不变；
- 既有 `PersonaChecksTest` 的合法人物、core、voice、swap、归属、确定性测试继续通过。

## 7. 文件清单

### 7.1 生产

新增五个 internal 文件：

- `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaValues.kt`
- `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaMoodState.kt`
- `app/src/main/java/com/example/zhengdao/baopu/state/persona/TraitChanges.kt`
- `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaLanguage.kt`
- `app/src/main/java/com/example/zhengdao/baopu/state/persona/PersonaEnhancement.kt`

修改两个既有文件：

- `PersonaModel.kt`：TraitLayer 补五轴；SurfaceState 加 tags；补演进注释。
- `PersonaChecks.kt`：十二轴提示；临床词表改为引用 `PersonaLanguage`；台词/叙述者三态；保留既有公开函数与稳定错误码。

### 7.2 测试

新增或更新：

- `Persona930Fixture.kt`：合法 values、动态 state、内部矛盾、surface tags。
- `Persona930ModelTest.kt`：values 完整性、冲突 WARN、同轴冲突红、927 三根、矛盾夹具、确定性。
- `Persona930LanguageTest.kt`：34 条三态、同形词负例、医疗 WARN、污名词三态、标签越界。
- `Persona930SourceAssertTest.kt`：单一真相、禁止推导边、唯一 SurfaceDeriver、静态词表、tags 单落点。
- `PersonaChecksTest.kt` 与 `PersonaFixture.kt`：十二轴构造、五轴默认 `1/DEFAULT` 与显式 `EXPLICIT`、既有断言更新留痕、surface 基线串；旧黑名单用例的“大五人格”改为“色彩性格”，避免与 930 五因子模型自相矛盾。

新测试不创建临时目录；源码断言只读仓库文件。

## 8. 验收与门禁

必须全部满足：

- values 缺轴、重轴、越界 ⇒ 红；合法八轴 ⇒ 绿；
- values/core 直接冲突 ⇒ WARN 且消息含两轴与档位；
- 单事件改 trait ⇒ 红；latent/longterm ⇒ 绿；
- 高宜人性／低尽责矛盾夹具有实际读数；
- 34 条临床词逐条：叙述者红、台词绿、医疗+不确定 WARN；四类齐全；
- “他依赖我”“回避（动词）”等负例不命中；
- 台词污名词 WARN，叙述者污名词红；
- 819 core 零改、SurfaceDeriver 零改且唯一、三组 surface 轴串逐字节一致；
- 单一真相 grep、禁止推导源码断言、唯一 SurfaceDeriver 断言通过；
- `detekt <=189`；
- AGP lint 原始 `errors=0/warnings=0`，预算闸同时通过；
- 全量 app/core 单测零失败；
- 全量单测后水位 `<=315`，不得用定向测试冒充全量读数；
- 新函数 `<=59` 行、复杂度 `<=14`，不得用 `@Suppress` 或抬高预算过关。

## 9. preflight 复查与查重留痕

2026-11-26 在 `wt-930` 重跑修订范围 preflight，原始日志为 `build/preflight-930-r2.log`，退出码 1。脚本把任何关键词命中都列为待处理项；本次剩余 5 项全部是已核实的查重证据，不是重复实现：

| 关键词 | 命中数 | 已核，非重复 |
|---|---:|---|
| 人格 | 28 | 05b1615be 是本单设计稿；6f595d779/114a94659 是 819 既有基座；其余是其他域用词，930 只做同包增强。 |
| 性格 | 56 | 819 提供唯一 TraitLayer/CoreLayer/SurfaceState；882 职业池等命中不提供八轴 values 或动态 mood，本单不重复其职责。 |
| personality | 45 | 命中是卡片人设、导入格式与终端历史等；没有第二套 traits/values/states/expression 模型。 |
| persona | 342 | 819 及既有 persona 包正是本单复用对象；新增文件在包内补 values/state/判据，不重建 core/trait/surface。 |
| PersonaValues | 1 | 仅命中本单设计提交 05b1615be；生产文件尚未创建，历史无同名实现。 |

其余检查全部通过：

- 私有账修复提交 `151f3fc` 已确认；
- 在飞表 3 行活动记录，两两占用无相交；
- 远端同步、复活检测、账本重复检测、测试平台无关闸均通过；
- 不修改共享在飞账，不走 hook bypass，不需要“带阻塞继续”批准。

## 10. 交付物

工作区根目录报告：`930-人格维度-交付-2026-11-26.md`。

第一行必须是：

```text
DONE · <9位提交SHA> · <提交标题原文>
```

报告至少包含：

- 四层字段表；
- values 完整性与冲突夹具读数；
- 34 条临床词三态读数；
- 内部矛盾夹具实际档位与检查结果；
- 819 断言更新前后对照；
- core 零改、唯一 SurfaceDeriver、surface 三组逐字节基线；
- 单一真相 grep 与源码断言清单；
- detekt、lint、全量单测、水位实测；
- preflight 复查日志与 5 组关键词“已核，非重复”逐条证据；
- 本地分支未推远端的证明。
