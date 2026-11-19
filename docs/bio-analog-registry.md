# 六体仿生登记表(626 批一定稿 · 523 批三件套升级)

> **表头口径:九件都无判定权。**
> 杏仁核、海马、前额叶、纹状体、岛叶、丘脑这六件,只产出**信号与读数**;
> 下意识、灵感、重放这三件(676/677/678,非器官、不进六件闭集)只出**建议信号**。
> 判定权一律留在现有裁决面(御案、调解器、各既有门禁)。任何一件想"说了算",
> 必须先过本表评审改口径,再改 `tools/check-bio-analog.py`——机器闸不认表外的事。

本表是仿生件的**唯一登记入口**。源码里带 `@BioAnalog("件名")` 标记的类,件名必须能在
本表第一列找到,否则 CI 红(判据见 `tools/check-bio-analog.py` 头注释)。
六件为**闭集**:不多不少,第七件出现即红——加新件先改本表、过评审,再动代码。

523(M3 收口)起,每个仿生件交付必须过**三件套**,缺一不收:

1. **可测差异**:同一输入下,有无该件行为不同,且被测试钉住——表上"怎么测"栏给出钉住它的测试名;
2. **不越权声明**:源码声明并断言"本件无判定权"(不产生 RiskDecision/PROCEED/REJECT、
   不改档、不写权威账)——九件主判据文件带头声明行,`BioTripleGateTest` 断言声明在、
   剥注释后的代码里判定词/档写/权威账写零出现(六体另有 `BioSourceAssertTest`,三件接线件
   另有 `SubconsciousGateTest`/`InspirationRecallSourceGateTest`/`HippocampalReplaySourceGateTest`);
3. **失败兜底**:该件失败/超时/不可用时,主链路行为与"没有它"逐字一致——表上"兜底证据"栏
   给出证据;接线件的兜底壳(`safe("回响")`/`runCatching`)由 `BioTripleGateTest` 钉死,拆壳即红。

三件套三栏(怎么测 / N/A理由 / 兜底证据)**缺一栏即红**——闸对 §二的六行与 §四的三行同款盯非空。

基线:origin/main @ 16a59e859。626 定稿与立闸;675/694 建六件读数源;
676/677/678 建三件非器官仿生件(已接线,接线处自带兜底壳);523 升级本表为三件套口径;
714 接线六件读数(纹状体/岛叶由 521 消费层接线,杏仁核/丘脑/前额叶/海马由 714 收工链接线——只读只 log)。

## 一、列口径(十列,一格都不许空)

| 列 | 含义 |
| --- | --- |
| 件 | 登记名,`@BioAnalog("件名")` 与之逐字相等 |
| 仿生自 | 借的是哪个脑区的哪条机理(只借机理,不借神话) |
| 只做 | 该件被允许的动作边界(产出物形态) |
| 不做 | 点名禁止的事(与"只做"互为对偶,评审时两条一起看) |
| 可测差异 | 相对现有朴素做法,用什么指标证明"装了它不一样" |
| 失败兜底 | 该件挂掉/读数缺失时的降级路径(必须等于现状,不许新增失败面) |
| 状态 | 施工事实(闸只判非空,不锁词表) |
| 怎么测 | 三件套①的证据:同输入装/不装的差异由哪个测试钉住(写测试名,须真实存在) |
| N/A理由 | 三件套三栏里若有对本件不适用的,写明哪栏为何不适用;全适用写 — |
| 兜底证据 | 三件套③的证据:失败/不可用时"等于没装"由哪个测试或兜底壳钉住 |

## 二、登记表(六行)

| 件 | 仿生自 | 只做 | 不做 | 可测差异 | 失败兜底 | 状态 | 怎么测 | N/A理由 | 兜底证据 |
| --- | --- | --- | --- | --- | --- | --- | --- | --- | --- |
| 杏仁核 | 杏仁核威胁/突显快速通路(先于慢思考的粗标) | 给事件打"威胁强度"读数,即发即弃,不落权威账 | 不拦截、不判定、不写库;不宣布任何内容"该禁该删"(那是门禁的权) | 相对关键词过滤,对语义改写的漏报率与误报率(同一标注集对比) | 读数缺失即视为"无威胁信号",主路径行为与现状逐字一致 | 读数源已建(675);已接线·收工链只读只log(714);523 三件套齐 | AmygdalaTest.triple1SameInputDiffersFromNaiveKeywordCount(朴素关键词基线 vs 器官:同输入读数不同)＋threatBandsReachEveryLevel(全档可达) | — | AmygdalaTest.triple3FallbackTreatsMissingSignalAsNoThreat(读数缺失＝"无威胁信号";词表缝失效回默认表)＋BioSourceAssertTest(零写口零落库零模型) |
| 海马 | 海马情景编码与回放固化(短时→回放→长时) | 记录"何时发生了什么",给情景打突显标签,供回放候选 | 不改写记忆、不决定记什么忘什么(取舍在既有记忆分层)、不输出结论 | 相对全量向量库,同查询下回放筛选后的召回率与存储放大系数 | 不可用时退回"无突显标签的回放",记忆层行为与现状一致 | 读数源已建(694);已接线·收工链只读只log(714)；重放键已整缝交接共用本件键(714·G-708-2);523 三件套齐 | HippocampusTest.triple1KeyIsMetadataFaceNotBodyExcerpt(键＝地址＋元数据,朴素截引做不到)＋hintCarriesNoBodyTextEvenWithSensitiveFixture(正文零片段) | — | HippocampusTest.emptyTextGetsEmptyBandAndNoFingerprint(空文退化"-"不造数)＋issuingKeysTwiceLeavesNoStateBehind(无状态零残留)＋BioSourceAssertTest(禁引擎符号) |
| 前额叶 | 前额叶执行功能(慢思考、计划、抑制的候选生成) | 产出"候选计划＋风险清单"文本,供裁决面参考 | 不执行、不批准、不否决——无判定权(本表表头) | 相对单发直答,高风险操作经候选计划路径的比例与其后的翻案率 | 计划失败退回直答路径,等价于现状 | 读数源已建(694);已接线·收工链只读只log(714);523 三件套齐 | PrefrontalCortexTest.triple1StuckReadingOnlyWithFailureHistory(有失败史出卡步读数,没它读不出)＋stuckAtNeedsThreeFailuresOnSameUnfinishedPlannedStep | — | PrefrontalCortexTest.stuckAtIsNullWhenEvidenceIsInsufficientOrScattered(证据不够⇒null,判不了不猜)＋updatePlanPayloadIsByteIdenticalAroundReadCalls(读数前后计划 payload 逐字节一致) |
| 纹状体 | 纹状体奖赏预测误差(多巴胺"预期-实际"差信号) | 记"预测奖赏 vs 实际奖赏"的差读数,攒习惯候选分 | 不调模型参数、不决定强化哪个行为、不直接改路由 | 相对手工规则,常用任务路径的步数收敛速度与差读数准确率 | 读数缺失即"无奖赏信号",既有评分不受影响 | 读数源已建(694);已接线·521 习惯固化消费(收工链,rpe 行194);523 三件套齐 | StriatumTest.triple1CorrectedSuccessDistinguishableFromPlainSuccess(二值成败说不出的"成了但被打回"⇒0)＋fourCellTableMapsOutcomeToThreeBands | — | StriatumTest.askTierIsUntouchedByRpeCalls(全四格连打两轮,授权档逐字不变)＋valueStaysInsideDeclaredDomain(值域封死)＋BioSourceAssertTest(零写口) |
| 岛叶 | 脑岛内感受(对身体自身状态的集合感知) | 把本 app 体征(电量/存储/构建时长/门禁红黄绿)汇成一个可读取数面 | 不以体征决定接不接任务、不触发自我修复(修是御医的职,岛叶只递读数) | 相对散落日志,御医取一次全身快照所需的读数面覆盖指标数与获取耗时 | 读数面不可用时御医退回逐项排查,行为与现状一致 | 读数源已建(675);已接线·521 岛叶自调消费(收工链,snapshot 行127);523 三件套齐 | InsulaTest.triple1UnifiedVitalsFaceIsTheDifference(一次调用汇齐七体征;没它没有这个面)＋fullSnapshotReadsEveryVital | — | InsulaTest.emptySourcesGiveAllMissing／wiredButReadlessSaysMissingNotUnwired(缺缝＝"无读数"逐格,不造数不补零)＋BioSourceAssertTest(零写口) |
| 丘脑 | 丘脑感觉中继与门控(感觉信号先过中继再达皮层) | 把输入事件路由到对应下游,并打"新异性"读数;只中继不解读 | 不做内容过滤(过滤是既有门禁的事)、不决定是否处理、不丢弃事件 | 相对直接分发,路由错分率与转发 P95 延迟 | 中继失败退回直接分发,等价于现状 | 读数源已建(675);已接线·收工链只读只log(714);523 三件套齐 | ThalamusTest.triple1SameInputYieldsRouteReadingOnlyWithOrgan(有它＝路由＋新异读数面)＋routesCoverAllFourDownstreams(四路可达) | — | ThalamusTest.triple3WordSeamFailureFallsBackToShapeOnly(词表缝失效退形状中继,不拦不丢)＋guardBeatsToolShapeAndNothingIsDropped(事件必有出路) |

## 三、闸与用法

- 机器闸:`tools/check-bio-analog.py`(CI 每次跑,先 `--selftest` 再真空跑)。
  带 `@BioAnalog` 标记的类未在本表登记 ⇒ 红;标记不带件名 ⇒ 红;
  本表六行不齐/有空格/出现第七件 ⇒ 红;三件套三栏(怎么测/N/A理由/兜底证据)缺栏或空格 ⇒ 红;
  §四的档案表缺行/空格/多件 ⇒ 红;**删表(六体表或档案表) ⇒ 退出码 2,不许变绿**。
- 批二起建任何一件器官实现时,类上必须写 `@BioAnalog("件名")`,件名与本表第一列逐字相等。
- 三件套门禁(测试侧):`BioTripleGateTest`(九件声明＋三件接线件禁区词＋兜底壳三处,全部带变异负例)。

## 四、非器官仿生件三件套档案(523 新增;676/677/678)

这三件不是器官——不进六件闭集、不带 `@BioAnalog` 标记(六件闭集不受影响);
但它们同样是仿生件(M3 口径),同样要过三件套。闸对下表同款盯:**缺行/空格/多件 ⇒ 红**。

| 件 | 怎么测 | N/A理由 | 兜底证据 |
| --- | --- | --- | --- |
| 下意识 | SubconsciousRunnerTest.三件套①_同批账_执行器在场浮信号_无可回响时零变化 ＋ 模型正常挑_如实标注模型版 ＋ 一天只浮一次_同天第二趟什么都不做 | — | SubconsciousRunnerTest.模型不可用_退回规则版照常出信号(挑选口抛异常/没钥匙都退规则版)＋三件套③_候选空池_真跑零信号零行_页签零变化 ＋ TriggerWiring.safe("回响")兜底壳(BioTripleGateTest.兜底壳三处都在_件炸了只记一行 钉,拆壳即红)＋SubconsciousGateTest(零账写零推送) |
| 灵感 | InspirationRecallTest.判据2_正例_三重全中_浮一条带原文 ＋ 判据2_负例一/二/三(缺一不浮)＋判据4_当天已浮过_这轮不浮 | — | InspirationRecallTest.判据3_不点_信箱空_下一轮上下文零注入(提示词逐字节一致)＋判据1_坏文件跳过但计数_不静默(选择器不可用退规则版＝判据2 正例的 selectorMode"规则"那条路)＋BaopuChatController.refreshInspirationRows 的 runCatching 兜底壳(BioTripleGateTest 钉,拆壳即红)＋InspirationRecallSourceGateTest(offer 唯一放行口) |
| 重放 | HippocampalReplayTest.判据1_锚上的候选条目必带指针 ＋ 产物_行数与条目建议数一致 ＋ 判据2_负例_忙碌时跑一轮_必须零产物 ＋ 天闸_同一天第二趟什么都不做 | — | HippocampalReplayTest.产物_候选空表_真跑但零产物＋判据1_没锚上情景键的候选不进产物(缺指针即丢)＋ReplayStoresTest.状态_坏文件按没跑过处理／判据4_撤销后记忆层字节不动 ＋ replayOnScreenOff 的 runCatching 兜底壳(BioTripleGateTest 钉,拆壳即红)＋HippocampalReplaySourceGateTest(零记忆写口) |
