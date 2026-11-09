# 入口覆盖清点（373）

> 机器清点，不判生死：结论列只有三值——**接线（谁接）／不急（理由）／该删（理由）**，
> 由 tools/entry-coverage-conclusions.json 登记后合并进来（不许留空、不许待定）。
> 本文件与 entry-coverage.json 由 tools/check-entry-coverage.py 生成，**两次运行逐字节一致**；
> 清点脚本只读不写生产代码。

## 汇总

| 口径 | 数 |
|---|---:|
| 顶层公开面（app/src/main/java，剥注释后计数） | 461 |
| 生产可达（有生产引用，含同文件） | 459 |
| A 有定义无调用 | 1 |
| B 只有测试在调 | 1 |
| C 已接线默认关、无开启路径 | 4 |
| **NOT_WIRED（结论=接线，接线欠账）** | **1** |

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
| `IntakeProfile` | `app/src/main/java/com/example/zhengdao/baopu/intake/IntakeProfile.kt:18` | `app/src/test/java/com/example/zhengdao/baopu/intake/IntakeProfileTest.kt:26`、`app/src/test/java/com/example/zhengdao/baopu/intake/IntakeProfileTest.kt:28`、`app/src/test/java/com/example/zhengdao/baopu/intake/IntakeProfileTest.kt:33` | 该删 | 该删：345 批画像层（AgentProfile＋四条硬规矩）已被 365 的 policy/CapabilityProfile.kt 整套取代——365 文件头自述「把 345 批立下的四层设计做成 policy 侧的真对象」、三条硬规矩同口径；427 接进生产的准入链走的正是 365 那套（AdmissionWiring.kt:91 → AdmissionFlow.run → CapabilityProfile），345 这件的 intake／canAccept／needsReprobe／onTaskResult／reprobe／reinstate／portrait 全仓零外部引用（只有 IntakeProfileTest 在调）。只列不删：另立清理批删 intake/IntakeProfile.kt 整件＋intake/IntakeModels.kt:103 的 AgentProfile 与 :124 的 ViolationRecord 及连带测试 |

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
