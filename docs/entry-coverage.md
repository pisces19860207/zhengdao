# 入口覆盖清点（373）

> 机器清点，不判生死：结论列只有三值——**接线（谁接）／不急（理由）／该删（理由）**，
> 由 tools/entry-coverage-conclusions.json 登记后合并进来（不许留空、不许待定）。
> 本文件与 entry-coverage.json 由 tools/check-entry-coverage.py 生成，**两次运行逐字节一致**；
> 清点脚本只读不写生产代码。

## 汇总

| 口径 | 数 |
|---|---:|
| 顶层公开面（app/src/main/java，剥注释后计数） | 389 |
| 生产可达（有生产引用，含同文件） | 384 |
| A 有定义无调用 | 3 |
| B 只有测试在调 | 2 |
| C 已接线默认关、无开启路径 | 3 |
| **NOT_WIRED（结论=接线，接线欠账）** | **5** |

## A 有定义无调用（生产侧零引用，含同文件）

查法三步：① 类名全语料词边界检索（剥注释/字符串后计数）→ ② 公开成员名逐个检索 →
③ 薄封装（工厂/别名函数）检索。**同文件的使用也算引用**（调用方经类型推断不必点名），
但「只被本文件死代码引用」的隐藏链仍在 A 清单邻居里可见。**不许只 grep 类名就判零引用**——
注释/字符串里的名字不算（曾实测：XML 属性值算引用、`""` 空串卡死剥除器，均已钉负例）。

| 符号 | 文件:行 | 结论 | 说明/证据 |
|---|---|---|---|
| `ExamTasks` | `app/src/main/java/com/example/zhengdao/baopu/intake/ConductExam.kt:17` | 接线 | 谁接：准入「品行考」出题链——345 批③的题面件（四道坏题，与 335 cases 同源），判卷 ConductExam 就绪、现零生产调用，等准入接线批 |
| `ProbeTasks` | `app/src/main/java/com/example/zhengdao/baopu/intake/IntakeProbes.kt:14` | 接线 | 谁接：准入「探针层」出题链——345 批②的五枚探针题面＋判卷，同 ConductExam 一批接 |
| `OffscreenCaptureProbe` | `app/src/main/java/com/example/zhengdao/browser/offscreen/OffscreenCaptureProbe.kt:47` | 不急 | 理由：263 批真机探针（量「后台新载入的页能不能渲染」的实证工具，BATCH=263），仅 debug 变体引用；非生产功能，留作复现证据，暂不删 |

## B 只有测试在调（生产不可达）

| 符号 | 文件:行 | 测试引用 | 结论 | 说明/证据 |
|---|---|---|---|---|
| `CardValidate` | `app/src/main/java/com/example/zhengdao/baopu/cards/ResultCardValidate.kt:59` | `app/src/test/java/com/example/zhengdao/baopu/cards/ResultCardValidateTest.kt:33`、`app/src/test/java/com/example/zhengdao/baopu/cards/ResultCardValidateTest.kt:42`、`app/src/test/java/com/example/zhengdao/baopu/cards/ResultCardValidateTest.kt:50` | 接线 | 谁接：各表单卡把算好的 CardFieldError 喂给本壳——212 批「校验样式」交付件（红框＋人话＋置灰小计），现仅测试在调，生产没有卡接它 |
| `IntakeProfile` | `app/src/main/java/com/example/zhengdao/baopu/intake/IntakeProfile.kt:18` | `app/src/test/java/com/example/zhengdao/baopu/intake/IntakeProfileTest.kt:26`、`app/src/test/java/com/example/zhengdao/baopu/intake/IntakeProfileTest.kt:28`、`app/src/test/java/com/example/zhengdao/baopu/intake/IntakeProfileTest.kt:33` | 接线 | 谁接：准入 intake 建档链——345 批准入件（TaskResult 同文件、经类型推断被同文件使用），建档接线批落地后复核 |

## C 已接线默认关、无开启路径

判据：`getBoolean(key, 默认 false)` 读点存在；生产侧**找不到活的开启路径**——
没有 putBoolean 写点，或写口（ObjectName.fun）自身零调用方（写口在但没人拨＝拨不开）。
设置页能拨的不算——那是有开启路径。键身份按解析出的**真实 prefs 键值**配对。

| 开关 key | 读点 | 开启路径 | 结论 | 说明/证据 |
|---|---|---|---|---|
| `orchestrate_engine_on` | `app/src/main/java/com/example/zhengdao/baopu/agent/OrchestrationGate.kt:37` | 写口 `OrchestrationSettings.set`（`app/src/main/java/com/example/zhengdao/baopu/agent/OrchestrationSettings.kt:19`）零调用方 | 接线 | 谁接：设置页「批量调用编排」开关调 OrchestrationSettings.set——214 批写口就绪但生产零调用方，闸默认关且永远拨不开（真欠账） |
| `eval_selfcheck_on` | `app/src/main/java/com/example/zhengdao/baopu/eval/SelfCheckGate.kt:42` | 无写点 | 不急 | 理由：84 批刻意设计——debug 构建 BuildConfig.DEBUG 并联开箱即跑，Release 无拨动口；pref 默认 false 是 Release 兜底而非漏接。要 Release 开关另立批 |
| `search_phone_on` | `app/src/main/java/com/example/zhengdao/baopu/search/SearchScopeSettings.kt:37` | 无写点 | 不急 | 理由：229 批停用的兼容读口——判定路径（phoneAccessOf/plan）已不调用，键名保留专为读老 prefs（删＝老账变哑键，文件头注明确）；无行为影响 |

## 口径与边界

- 语料：app/src/main 全部 .kt ＋ AndroidManifest.xml ＋ res/**/*.xml；注释/字符串不计数。
- app/src/debug 命中单独记「仅 debug 变体引用」（release 不可达仍入清单）；proguard keep 只作证据。
- 同名符号互相「救活」：宁可漏报不误杀；结论侧人工复核后登记。
- CI 只报不拦（先观察一版）；重新生成：`python3 tools/check-entry-coverage.py --check` 核对一致。
