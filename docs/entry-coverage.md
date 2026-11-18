# 入口覆盖清点（373）

> 机器清点，不判生死：结论列只有三值——**接线（谁接）／不急（理由）／该删（理由）**，
> 由 tools/entry-coverage-conclusions.json 登记后合并进来（不许留空、不许待定）。
> 本文件与 entry-coverage.json 由 tools/check-entry-coverage.py 生成，**两次运行逐字节一致**；
> 清点脚本只读不写生产代码。

## 汇总

| 口径 | 数 |
|---|---:|
| 顶层公开面（app/src/main/java，剥注释后计数） | 565 |
| 生产可达（有生产引用，含同文件） | 552 |
| A 有定义无调用 | 1 |
| B 只有测试在调 | 12 |
| C 已接线默认关、无开启路径 | 4 |
| **NOT_WIRED（结论=接线，接线欠账）** | **3** |

## A 有定义无调用（生产侧零引用，含同文件）

查法三步：① 类名全语料词边界检索（剥注释/字符串后计数）→ ② 公开成员名逐个检索 →
③ 薄封装（工厂/别名函数）检索。**同文件的使用也算引用**（调用方经类型推断不必点名），
但「只被本文件死代码引用」的隐藏链仍在 A 清单邻居里可见。**不许只 grep 类名就判零引用**——
注释/字符串里的名字不算（曾实测：XML 属性值算引用、`""` 空串卡死剥除器，均已钉负例）。

| 符号 | 文件:行 | 结论 | 说明/证据 |
|---|---|---|---|
| `OffscreenCaptureProbe` | `app/src/main/java/com/example/zhengdao/browser/offscreen/OffscreenCaptureProbe.kt:47` | 不急 | 理由：263 批真机探针（量「后台新载入的页能不能渲染」的实证工具，BATCH=263），只有 app/src/debug/.../OffscreenProbeActivity.kt:21／:25 引用，release 不可达；属复现证据不是生产功能，留作对照不删（503 悬空清单同一件，508／509 未处置） |

## B 只有测试在调（生产不可达）

| 符号 | 文件:行 | 测试引用 | 结论 | 说明/证据 |
|---|---|---|---|---|
| `AnatomyZones` | `app/src/main/java/com/example/zhengdao/baopu/body/AnatomyZones.kt:28` | `app/src/test/java/com/example/zhengdao/baopu/body/AnatomyZonesTest.kt:17`、`app/src/test/java/com/example/zhengdao/baopu/body/AnatomyZonesTest.kt:22`、`app/src/test/java/com/example/zhengdao/baopu/body/AnatomyZonesTest.kt:24` | 接线 | 谁接：660 形体线的**唯一真源**（docs/facts/anatomy-zones.json，头部 shared_with 声明 660 共用）。660 未交付 ⇒ 本单只交口径库与解析器（fail-closed、单一加载点），660 落地直接读它、不另抄一份。用途：13 区口径（可观察特征／AI 常见误写），供 660 形体模型与 669 误写检测共用。 |
| `SilhouetteWire` | `app/src/main/java/com/example/zhengdao/baopu/body/SilhouetteWire.kt:14` | `app/src/test/java/com/example/zhengdao/baopu/bio/InsulaTest.kt:11`、`app/src/test/java/com/example/zhengdao/baopu/bio/InsulaTest.kt:26`、`app/src/test/java/com/example/zhengdao/baopu/bio/InsulaTest.kt:27` | 接线 | 谁接：666 派工单明写「接线：把 silhouette(H,C,pos,pose) 接到 660 的高度场输出」——660 几何内核尚未交付，故本单只交接缝（HeightFieldSource）＋夹具高度场（腰线/裆缝/大腿根），生产侧换成实测高度场即生效；核心 Silhouette.kt 是纯函数，不依赖接缝。用途：形轨.轮廓可见度（三态＋一句话），解决 12.9 记的「轮廓该不该看得出」实际故障。 |
| `VocabGuard` | `app/src/main/java/com/example/zhengdao/baopu/body/VocabGuard.kt:37` | `app/src/test/java/com/example/zhengdao/baopu/body/VocabGuardTest.kt:20`、`app/src/test/java/com/example/zhengdao/baopu/body/VocabGuardTest.kt:225` | 不急 | 理由：本单是**规则库＋纯函数扫描器**（十条规则子句级扫描、只报不改写、宾转主 suggest 不拦），生产侧还没有调用点——它要做的事是「写的时候给提示」，那需要一个提示入口（下一批）。先行落库是为了让 660 与写作侧共用同一份规则，避免各自写一套；判据已由 29 例夹具＋2 例源码断言钉住。 |
| `IntakeProfile` | `app/src/main/java/com/example/zhengdao/baopu/intake/IntakeProfile.kt:18` | `app/src/test/java/com/example/zhengdao/baopu/intake/IntakeProfileTest.kt:26`、`app/src/test/java/com/example/zhengdao/baopu/intake/IntakeProfileTest.kt:28`、`app/src/test/java/com/example/zhengdao/baopu/intake/IntakeProfileTest.kt:33` | 该删 | 该删：345 批画像层（AgentProfile＋四条硬规矩）已被 365 的 policy/CapabilityProfile.kt 整套取代——365 文件头自述「把 345 批立下的四层设计做成 policy 侧的真对象」、三条硬规矩同口径；427 接进生产的准入链走的正是 365 那套（AdmissionWiring.kt:91 → AdmissionFlow.run → CapabilityProfile），345 这件的 intake／canAccept／needsReprobe／onTaskResult／reprobe／reinstate／portrait 全仓零外部引用（只有 IntakeProfileTest 在调）。只列不删：另立清理批删 intake/IntakeProfile.kt 整件＋intake/IntakeModels.kt:103 的 AgentProfile 与 :124 的 ViolationRecord 及连带测试 |
| `JointNames` | `app/src/main/java/com/example/zhengdao/baopu/state/geometry/JointChain.kt:79` | `app/src/test/java/com/example/zhengdao/baopu/state/geometry/JointChainTest.kt:120`、`app/src/test/java/com/example/zhengdao/baopu/state/geometry/JointChainTest.kt:121`、`app/src/test/java/com/example/zhengdao/baopu/state/geometry/JointChainTest.kt:121` | 唯一实现·待消费 | 谁接：660 形体线（几何内核是其数学真源）与 670 角色轨。两者未接 ⇒ 生产暂无调用点，但本件是**唯一实现**（零第三方物理库、零时间积分、零随机/时钟，源码断言钉住），660/670 落地直接读它、不另抄一份。用途：关节名闭集（限位链的数据契约），660 关节链按名查限位。【706 sole-impl 档：本件全仓唯一实现（机检声明计数=1）；待消费方 670 接线，合入即须回填；到期未回填自动降 NOT_WIRED。】 |
| `PoseValidity` | `app/src/main/java/com/example/zhengdao/baopu/state/geometry/PoseValidity.kt:53` | `app/src/test/java/com/example/zhengdao/baopu/state/geometry/GeometrySourceAssertTest.kt:101`、`app/src/test/java/com/example/zhengdao/baopu/state/geometry/GeometrySourceAssertTest.kt:102`、`app/src/test/java/com/example/zhengdao/baopu/state/geometry/PoseValidityTest.kt:20` | 唯一实现·待消费 | 谁接：660 形体线（几何内核是其数学真源）与 670 角色轨。两者未接 ⇒ 生产暂无调用点，但本件是**唯一实现**（零第三方物理库、零时间积分、零随机/时钟，源码断言钉住），660/670 落地直接读它、不另抄一份。用途：姿态合法性判定（关节限位＋可达三态的综合结论），660 姿态校验用它。【706 sole-impl 档：本件全仓唯一实现（机检声明计数=1）；待消费方 670 接线，合入即须回填；到期未回填自动降 NOT_WIRED。】 |
| `Sphere` | `app/src/main/java/com/example/zhengdao/baopu/state/geometry/Shapes.kt:17` | `app/src/test/java/com/example/zhengdao/baopu/state/geometry/EpaTest.kt:19`、`app/src/test/java/com/example/zhengdao/baopu/state/geometry/EpaTest.kt:22`、`app/src/test/java/com/example/zhengdao/baopu/state/geometry/EpaTest.kt:47` | 唯一实现·待消费 | 谁接：660 形体线（几何内核是其数学真源）与 670 角色轨。两者未接 ⇒ 生产暂无调用点，但本件是**唯一实现**（零第三方物理库、零时间积分、零随机/时钟，源码断言钉住），660/670 落地直接读它、不另抄一份。用途：球支撑映射（最简形状，GJK/EPA 的测试基准与近似体）。【706 sole-impl 档：本件全仓唯一实现（机检声明计数=1）；待消费方 660 接线，合入即须回填；到期未回填自动降 NOT_WIRED。】 |
| `ConvexHull` | `app/src/main/java/com/example/zhengdao/baopu/state/geometry/Shapes.kt:26` | `app/src/test/java/com/example/zhengdao/baopu/state/geometry/GjkTest.kt:63`、`app/src/test/java/com/example/zhengdao/baopu/state/geometry/GjkTest.kt:64` | 唯一实现·待消费 | 谁接：660 形体线（几何内核是其数学真源）与 670 角色轨。两者未接 ⇒ 生产暂无调用点，但本件是**唯一实现**（零第三方物理库、零时间积分、零随机/时钟，源码断言钉住），660/670 落地直接读它、不另抄一份。用途：凸包支撑映射（GJK/EPA 的形状基础），660 形体与两体接触都用它。【706 sole-impl 档：本件全仓唯一实现（机检声明计数=1）；待消费方 660 接线，合入即须回填；到期未回填自动降 NOT_WIRED。】 |
| `Capsule` | `app/src/main/java/com/example/zhengdao/baopu/state/geometry/Shapes.kt:46` | `app/src/test/java/com/example/zhengdao/baopu/state/geometry/EpaTest.kt:53`、`app/src/test/java/com/example/zhengdao/baopu/state/geometry/EpaTest.kt:54`、`app/src/test/java/com/example/zhengdao/baopu/state/geometry/GjkTest.kt:55` | 唯一实现·待消费 | 谁接：660 形体线（几何内核是其数学真源）与 670 角色轨。两者未接 ⇒ 生产暂无调用点，但本件是**唯一实现**（零第三方物理库、零时间积分、零随机/时钟，源码断言钉住），660/670 落地直接读它、不另抄一份。用途：胶囊支撑映射（关节 limb 近似体），660 关节链与 670 姿态用它。【706 sole-impl 档：本件全仓唯一实现（机检声明计数=1）；待消费方 670 接线，合入即须回填；到期未回填自动降 NOT_WIRED。】 |
| `Ellipsoid` | `app/src/main/java/com/example/zhengdao/baopu/state/geometry/Shapes.kt:58` | `app/src/test/java/com/example/zhengdao/baopu/state/geometry/GjkTest.kt:80` | 唯一实现·待消费 | 谁接：660 形体线（几何内核是其数学真源）与 670 角色轨。两者未接 ⇒ 生产暂无调用点，但本件是**唯一实现**（零第三方物理库、零时间积分、零随机/时钟，源码断言钉住），660/670 落地直接读它、不另抄一份。用途：椭球支撑映射（躯干近似体），660 躯干与可达性用它。【706 sole-impl 档：本件全仓唯一实现（机检声明计数=1）；待消费方 660 接线，合入即须回填；到期未回填自动降 NOT_WIRED。】 |
| `TwoBodyCollision` | `app/src/main/java/com/example/zhengdao/baopu/state/geometry/TwoBodyCollision.kt:24` | `app/src/test/java/com/example/zhengdao/baopu/state/geometry/GeometrySourceAssertTest.kt:103`、`app/src/test/java/com/example/zhengdao/baopu/state/geometry/GeometrySourceAssertTest.kt:104`、`app/src/test/java/com/example/zhengdao/baopu/state/geometry/TwoBodyCollisionTest.kt:25` | 唯一实现·待消费 | 谁接：660 形体线（几何内核是其数学真源）与 670 角色轨。两者未接 ⇒ 生产暂无调用点，但本件是**唯一实现**（零第三方物理库、零时间积分、零随机/时钟，源码断言钉住），660/670 落地直接读它、不另抄一份。用途：两体解析碰撞（动量/能量守恒 ±5%），660 双人互动姿态校验用它。【706 sole-impl 档：本件全仓唯一实现（机检声明计数=1）；待消费方 660 接线，合入即须回填；到期未回填自动降 NOT_WIRED。】 |
| `RelationTrack` | `app/src/main/java/com/example/zhengdao/baopu/state/track/RelationTrack.kt:75` | `app/src/test/java/com/example/zhengdao/baopu/state/track/RelationTrackTest.kt:42`、`app/src/test/java/com/example/zhengdao/baopu/state/track/RelationTrackTest.kt:55`、`app/src/test/java/com/example/zhengdao/baopu/state/track/RelationTrackTest.kt:59` | 不急 | 670 批关系轨迹（11 态 13 边＋守卫＋触发牌＋T4 不可逆），与 CharacterTrack 同一形态：引擎只校验边与证据、不自动推进（工单边界），生产调用方等 660 形轨线与 653 摘要线；本批只提供数据缝 coupling_to_form_track，8 例判据（表钉死／守卫证据／触发牌词表＋脱敏护栏「触发词只许出现在卡列表内」／回流三条件四态／不可逆／修饰符；御裁④ 后由 7 例增到 8 例）全部只从测试调。 |

## C 已接线默认关、无开启路径

判据：`getBoolean(key, 默认 false)` 读点存在；生产侧**找不到活的开启路径**——
没有 putBoolean 写点，或写口（ObjectName.fun）自身零调用方（写口在但没人拨＝拨不开）。
设置页能拨的不算——那是有开启路径。键身份按解析出的**真实 prefs 键值**配对。

| 开关 key | 读点 | 开启路径 | 结论 | 说明/证据 |
|---|---|---|---|---|
| `orchestrate_engine_on` | `app/src/main/java/com/example/zhengdao/baopu/agent/OrchestrationGate.kt:37` | 写口 `OrchestrationSettings.set`（`app/src/main/java/com/example/zhengdao/baopu/agent/OrchestrationSettings.kt:19`）零调用方 | 接线 | 谁接：设置页「批量调用编排」开关调 agent/OrchestrationSettings.kt:19 的 set——214 批写口就绪，但该 object 是 internal 且生产零调用方，设置页也没有这个开关（全仓只有 agent/OrchestrationGate.kt:23 的注释与 agent/BatchRules.kt:51 的提示文本）⇒ 闸默认关且永远拨不开（真欠账） |
| `eval_selfcheck_on` | `app/src/main/java/com/example/zhengdao/baopu/eval/SelfCheckGate.kt:42` | 无写点 | 不急 | 理由：84 批刻意设计——调试构建 BuildConfig.DEBUG 并联开箱即跑（ui/baopu/BaopuChatController.kt:2583 调 SelfCheckGate.enabledNow(BuildConfig.DEBUG, …)），Release 无拨动口；pref 默认 false 是 Release 兜底而非漏接。要 Release 开关另立批 |
| `prompt_export` | `app/src/main/java/com/example/zhengdao/baopu/promptexport/PromptExport.kt:170` | 无写点 | 不急 | 理由：459 批诊断件（导出「上一次请求的 system 段」做取证）——缺省跟构建类型（promptexport/PromptExport.kt:16），Release 默认关；App 内没有写口，唯一开启路径是 adb run-as 改 SP（459 报告 §五原文），广播口 PromptExportReceiver 受 DUMP 保护且只导出、不负责开关。属 debug 取证工具，Release 兜底默认关不是漏接；U 批要做设置页开关另立批 |
| `search_phone_on` | `app/src/main/java/com/example/zhengdao/baopu/search/SearchScopeSettings.kt:37` | 无写点 | 不急 | 理由：229 批停用的兼容读口——判定路径（SearchScopeRules.phoneAccessOf／plan）已不调用它（search/SearchScope.kt:27），键名保留专为读老 prefs（删＝老账变哑键，search/SearchScopeSettings.kt:4-10 文件头写明）；无行为影响 |

## 口径与边界

- 语料：app/src/main 全部 .kt ＋ AndroidManifest.xml ＋ res/**/*.xml；注释/字符串不计数。
- app/src/debug 命中单独记「仅 debug 变体引用」（release 不可达仍入清单）；proguard keep 只作证据。
- 同名符号互相「救活」：宁可漏报不误杀；结论侧人工复核后登记。
- CI 只报不拦（先观察一版）；重新生成：`python3 tools/check-entry-coverage.py --check` 核对一致。
