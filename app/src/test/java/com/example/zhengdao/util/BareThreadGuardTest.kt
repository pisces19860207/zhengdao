package com.example.zhengdao.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.text.Charsets

/**
 * P4-2（issue #17）：裸 Thread 纪律守卫。
 *
 * 审计原文：「裸 Thread 35 处」。两次实测（证据等级②，2026-10-14，基线 88c9a2a）：
 * - 首轮（账本记载）：报「真实创建点 7 处，其余是 Thread.currentThread() 误报」——**漏数**。
 *   漏因：只 grep 了 `Thread(` 一种形态；`Thread {`（Kotlin 尾随 lambda，无括号）整个
 *   没数，而名字走属性赋值（`name = "…"`）的两处连 `Thread(` grep 都看不见。
 * - 本轮（写门禁时逐点复核）：**28 处** Kotlin 创建点，全部有名字、全部 daemon。
 *   其中 18 处（`Thread { … }.start()` 房型，散在 9 个文件）原本**既无名字也无
 *   daemon**，本刀按既有房型统一补上：`}.apply { isDaemon = true; name = "zd-…" }.start()`
 *   （与 ZhengdaoApp.kt 保活线程同款写法；每处只改收尾一行，不碰线程体）。
 *   台账点名的「第 7 处」= TerminalActivity.kt:596 的 zd-session-start，一并在内。
 *
 * 纪律（本守卫钉这条）：**每条裸 Thread 必须有名字 + 必须 daemon**。
 * - 无名线程在 ANR 堆栈里是一团 "Thread-12"，查不出是谁；
 * - 非 daemon 线程挂着会让进程退不干净——保活巡检那种 while(true) 长循环反而更必须
 *   daemon：它本来就要活到进程死，但不能拦着进程死。
 *
 * 立场（判断，证据等级③，写进交付）：这 28 处**不强行改成协程**。它们分两类——
 * 一次性 detached worker（安装/检查/修复/刷新/粘贴/起会话）与进程级长循环
 * （保活巡检/归档）。改用协程要先给每条找一条寿命正确的 scope，收益（少一个
 * Thread）配不上风险（scope 生命周期 / 取消语义）。
 *
 * 范围：只扫 `com/example/zhengdao` 自己的 Kotlin。vendored termux 的
 * `com/termux/terminal/TerminalSession.java` 有 3 条上游原装的**命名但非 daemon**
 * 线程（:139/:156/:172）——按本批红线不碰 termux，留待单独评估。Kotlin 标准库
 * 小写 `thread { }`（默认非 daemon）同样纳入纪律；当前全仓零使用（证据②）。
 *
 * 对账单冻结创建点（`Thread` 关键字行号；第⑪批 D-1 新增心跳执行器与第⑯刀 R-25 消除
 * 存储页三处裸 Thread 后 26 个；2026-10-16 旧抱朴线清场消除 2 个后 **24 个**）。
 * 将来文件拆分/移动会让它失败——那是提醒回来更新对账单，不是 bug。
 *
 * 行号回填记录（本文件自己的规矩：平移就回填，不是新增线程）：
 * - 2026-10-14（第⑧刀 P4-1 抱朴拆分后）：SettingsStorageActivity / TerminalActivity 等平移，已回填；
 * - 2026-10-14（审计第 2 期配套 / 第⑩批A）：`SettingsStorageActivity.kt`（B-3 加 2 行说明）与
 *   `TerminalActivity.kt`（N-2 原子拷贝 +15 行）平移 ⇒ 回填 4 个行号；
 * - 2026-10-14（第⑨刀 B）：ZhengdaoApp.kt 插入 `initWebViewDataDirectory()`（+30 行）
 *   ⇒ 两条创建点 47→77 / 97→127，本次回填；
 * - 2026-10-14（第⑪批 D-1）：KeepaliveWatcher.kt 新增心跳执行器 zd-keepalive-io（:66，
 *   有名+daemon）⇒ 对账单 28→29 处；巡检线程平移 187→230 一并回填；
 * - 2026-10-14（第⑯刀 R-25/R-29，合并第⑪批后重算）：`SettingsStorageActivity.kt` 三处裸
 *   Thread 改协程（zd-cache-reload / zd-cache-clean / zd-rollback **消除**）⇒ 29→26 处；
 *   `SettingsEnvActivity.kt`（R-29 +4 行）557→561 / 652→656；
 *   `TerminalActivity.kt`（R-29 +12 行）1550→1562 / 1610→1622 / 1696→1708。本次回填。
 * - 2026-10-15（第⑲刀 OC 关停策略）：ZhengdaoApp.kt 插入 `registerOcShutdownOnFinish()`（+2 import
 *   ＋ onCreate 一行调用，共 +3 行）⇒ 两条创建点 77→80 / 127→130，本次回填。
 * - 2026-10-15（第㉚刀）：TerminalActivity 的 ComponentCaller import（+1）与 onNewIntent
 *   平台签名适配（+2）⇒ 六个创建点 79→80 / 596→599 / 811→814 / 1562→1565 / 1622→1625 /
 *   1708→1711，无新增线程，本次回填。
 * - 2026-10-16（B-补-13 ＋ B-补-21）：`TerminalActivity.kt` 横幅加「可点」线索与
 *   `liveRegion`（前半 +11 行）、`showBanner` 加读屏提示 ＋ `bannerSpoken()`（后半 +22 行）
 *   ⇒ 五个创建点 622→633 / 837→848 / 1588→1610 / 1648→1670 / 1734→1756，
 *   **无新增线程**（逐处核过仍是同一段 `Thread {`），本次回填。
 * - 2026-10-16（B-补-15）：`MainActivity.kt` 加 `RESTORABLE_ROUTES` 白名单 KDoc（+17 行）
 *   ⇒ `MainActivity.kt` 的唯一创建点 242→259，**无新增线程**，本次回填。
 * - 2026-10-16（B-补-22）：`SettingsEnvActivity.kt` 顶部 import +2 与 `@OptIn` KDoc +5（共 +7 行）、
 *   运行内存上限卡的三个芯片由裸 `Row` 改 `FlowRow`（+11 行）⇒ 四个创建点
 *   207→214 / 261→268 / 561→572 / 656→667，**无新增线程**（逐处核过仍是同一段 `Thread {`），本次回填。
 * - 2026-10-18（第二十一批「真闸门与空壳」）：`TerminalActivity.kt` 加会话信任簿字段（+6）、
 *   onResume 清失败计数（+8）、onLinkOpen 抽 `openInXuanlan` 与确认卡（+46 前后）⇒
 *   五个创建点 633→650 / 848→865 / 1609→1672 / 1669→1732 / 1755→1818，
 *   **无新增线程**（逐处核过仍是同一段 `Thread {`），本次回填。
 *   ⚠️ 记账方法坑（留给下一次）：对账单扫的是 **`Thread(`／`Thread {` 那一行**，
 *   不是线程**名字**所在行——`val worker = Thread({ … }, "zd-session-start")` 里名字在
 *   结尾、两者差着 60 多行。我第一遍按名字行回填成 719/875/…，测试照红（这正是这条
 *   对账单能抓错的价值）。以后数行号请用 `grep -nE "Thread *[({]"`，别按线程名 grep。
 * - 2026-10-16（B-补-19）：`MainActivity.kt` 冷启动权限块加「本次冷启动最多一个系统框」标记
 *   与 KDoc（+21 行）⇒ 唯一创建点 259→280，**无新增线程**（仍是 `zd-update-check` 那一个），本次回填。
 * - 2026-10-16（B-补-8）：`MainActivity.kt` 设置页顶栏 `height(48.dp)` 改
 *   `defaultMinSize(minHeight = 48.dp)`，顶部新增 `defaultMinSize` import（+1 行；顶栏那行注释
 *   在 `Thread {` 之后，不影响行号）⇒ 唯一创建点 280→281，
 *   **无新增线程**（逐行核过仍是同一段 `Thread {`），本次回填。
 * - 2026-10-16（B-补-17）：`HomeScreen.kt` 大标题改随滚动收缩（import +3、阈值常量 KDoc +8、
 *   展开/收起状态与插值 +23、顶栏注释 +2）⇒ 两个创建点 177→189 / 579→620，
 *   **无新增线程**（逐行核过 `:189` / `:620` 仍是同一段 `Thread {`），本次回填。
 * - 2026-10-16（旧抱朴线清场 · 台账 §A #99）：**消除 2 条创建点**——
 *   `HomeScreen.kt` 的 `zd-taiji-retry`（serve 重试流删除，-1）、
 *   `KnowledgeBaseSummarizerRunner.kt` 的 `zd-kb-summarize`（整文件删除，-1）⇒ 26→24 处；
 *   `HomeScreen.kt` 另一条 620→599（删掉 28 行的重试流又补回 7 行）；
 *   `ZhengdaoApp.kt` 两条 80→82 / 130→132（删 `registerOcShutdownOnFinish()` 及其 import
 *   共 -3，补「启动即自愈」说明 +5）；清场批注（`SettingsDataActivity.kt` / `SettingsEnvActivity.kt`
 *   各 +1 行）⇒ 后者两条 572→573 / 667→668 再平移。本次回填。
 * - 2026-10-16（抱朴刀 0）：新建 `baopu/BaopuDb.kt` 单写者线程 zd-baopu-db（:258）与
 *   `baopu/LlmClient.kt` 网络 lane zd-baopu-net（:315）⇒ 对账单 25→27 处（上游清场注记 24 差一，实为 25），
 *   两条都有名 + daemon（`Thread(r, "…").apply { isDaemon = true }` 房型），本次登记。
 *   zd-baopu-tool（工具单线程）**未创建**：刀 0 不带工具集，归刀 1 的 ToolRegistry，
 *   到时随工具线程一起登记，不预先占位。
 * - 2026-10-16（抱朴刀 0b）：**无新增线程**；BaopuDb.kt 因加 memory 表 ＋ v1→v2 迁移 ＋ 缓存两列，
 *   单写者创建点 258→366→393 两次平移，本次回填（逐处核过仍是同一段 Thread(r, …)）。
 * - 2026-10-16（抱朴刀 1）：**新增 2 条**——`agent/RunEngine.kt` 引擎循环 zd-baopu-run（:161）、
 *   `agent/GuardPipeline.kt` 工具执行 zd-baopu-tool（:63，即刀 0 预告"到时随工具线程
 *   一起登记"的那条），都有名 ＋ daemon ⇒ 对账单 27→29 处（红线 32 内）。
 * - 2026-10-17（抱朴刀 1 真机修）：**无新增线程**；RunEngine.kt 因加 `settle()` 与 `EngineEvent.Settled`
 *   （收尾顺序 + UI 刷新信号），zd-baopu-run 创建点 161→171 平移，本次回填。
 * - 2026-10-17（抱朴刀 2）：**无新增线程**；三处行号平移，本次回填：
 *   `MainActivity.kt` 281→308（接静态快捷方式：handleBaopuShortcut + isTaskRoot 收栈 + onNewIntent）、
 *   `BaopuDb.kt` 393→460（加资料库两表 + 章节索引表）、
 *   `SettingsDataActivity.kt` 589→642（加「抱朴工具」开关卡）。逐处核过仍是同一段 Thread。
 * - 2026-10-17（抱朴刀 3 · B 段模型参数）：**无新增线程**；三处行号平移，本次回填：
 *   `BaopuDb.kt` 460→510（run_log 加 reasoning_tokens 列 ＋ updateRunLogUsage 语句 ＋ v4 迁移）、
 *   `LlmClient.kt` 450→658（usage/reasoning 解析、stream 循环重构、effort 进请求体）、
 *   `SettingsDataActivity.kt` 642→699（「模型参数」只读卡 ＋「思考深度」四档卡）。
 *   逐处核过仍是同一段 Thread(r, "…").apply { isDaemon = true }，有名 + daemon 都没变。
 * - 2026-10-17（抱朴刀 3 · A 段确认制）：**无新增线程**（ConfirmFlow 的等待是轮询，
 *   不建线程；恢复执行复用 zd-baopu-run）；两处行号平移，本次回填：
 *   `BaopuDb.kt` 510→554（session 加 pending 三列 ＋ v5 迁移 ＋ PendingStore/SessionPendingStore）、
 *   `RunEngine.kt` 215→508（ConfirmRequest 事件 ＋ ConfirmFlow ＋ HistoryCodec ＋ 确认制接线）。
 * - 2026-10-18（抱朴第十三批 · 压缩桩通电）：**无新增线程**——压缩走 `completeOnce`
 *   的一次 `execute()`（同步，在 zd-baopu-run 上跑），不新建线程。`LlmClient.kt`
 *   的 zd-baopu-net 创建点因新增 `completeOnce` / `completeBody` / `plainTextOf`
 *   （+134 行）平移 658→792，本次回填；`RunEngine.kt` 的 zd-baopu-run 创建点因加
 *   压缩字段与 `runCompression` 平移 151→152（实测 `grep -n 'Thread('`），本次回填。
 *   `BaopuDb.kt` 的 zd-baopu-db 创建点因 v6 compression 表 + 幂等迁移 + CompressionStore
 *   （+81 行）平移 554→635，本次回填。
 * - 2026-10-17（抱朴刀 3 · A 段拆文件）：**无新增线程**；`RunEngine.kt` 把 RunRules（纯判据）、
 *   HistoryCodec（历史回灌编解码）、ConfirmFlow（确认制内核）拆去三个同包新文件
 *   （派工单 §9 行数纪律「单文件新增 ≤400 行；超了拆文件」；白名单 §6/§12.5 本就按
 *   `agent/RunRules.kt` 独立文件列过）＋ 清 4 个失效 import ⇒ zd-baopu-run 创建点
 *   508→151，本次回填。三个新文件逐行核过无裸 Thread（确认等待是轮询，不建线程；
 *   恢复执行复用 zd-baopu-run）。
 *   逐处核过仍是同一段 Thread(r, "…").apply { isDaemon = true }，有名 + daemon 都没变。
 * - 2026-10-18（抱朴第二十批 · 会话库与工作区）：**无新增线程**；`BaopuDb.kt` 因
 *   session 加 workspace 列（加列纯函数 + setSessionWorkspace + 会话库 SELECT）＋
 *   v8 迁移函数（+37 行），zd-baopu-db 创建点 721→758 平移，本次回填
 *   （逐处核过仍是同一段 Thread(r, "zd-baopu-db")）。`BaopuChatController.kt` 新增
 *   会话库三函数与 send 接线（+130 行）不影响任何创建点——zd-baopu-shell 是协程
 *   CoroutineName，不是裸 Thread。
 * - 2026-10-18（刀 4 预览槽）：**新增 1 条**——`preview/PreviewServer.kt` 的 acceptor
 *   `zd-preview-http`（:112，`Thread({ acceptLoop() }, "zd-preview-http").apply { isDaemon = true }`
 *   房型；名字必须写字符串字面量——本扫描器判「有名」认的是构造函数里的字面量，传常量判不出）
 *   ⇒ 对账单 29→30 处（红线 32 内）。派工单 §2.1 明写「线程名登记 `zd-preview-*`（必须在
 *   `util/BareThreadGuardTest.kt` 里加登记，否则门禁红）」，本条即那次登记；其余 29 条创建点未动。
 * - 2026-10-19（第四十八批 · 抱朴 P0 四件）：**无新增线程**。`agent/GuardPipeline.kt`
 *   加 `import com.example.zhengdao.rootfs.PathGuard`（P0-2 原始区红线）⇒ import 区 +1，
 *   zd-baopu-tool 创建点 67→68，本次回填；`RunEngine.kt` 删 `runLoop` 内同名局部
 *   var（P0-3）只动 757 行之后的代码，zd-baopu-run（:192）**未平移**。
 * - 2026-10-19（第五十三批 · 终端/外壳 P1 五件）：**无新增线程**。`TerminalActivity.kt`
 *   P1-4 attach 分支读 `SessionManager.isFallback` 并条件化标题（+4 行）⇒ 五个创建点
 *   650→654 / 865→869 / 1672→1676；P1-6 系统文本菜单粘贴改走 `pasteText` 的 KDoc 与
 *   单行调用（+7 行）⇒ 后三个再 1676→1683 / 1732→1743 / 1818→1829。`MainActivity.kt`
 *   P1-9 的 `onNewIntent` 分支（+7 行）落在 :316 之后 ⇒ 该创建点不动；`HomeScreen.kt`
 *   P1-10 只改一行判据 ⇒ :667 不动。逐处核过仍是同一段 `Thread {`，本次回填。
 */
class BareThreadGuardTest {

    /** 一条裸 Thread 创建点（line = `Thread` 关键字所在行）。 */
    private data class Site(val file: String, val line: Int, val named: Boolean, val daemon: Boolean)

    // ── 守卫本体 ──

    @Test
    fun `门禁_每条裸 Thread 都有名字且是 daemon`() {
        val sites = scanAppSources()
        assertTrue(
            "范围内一条裸 Thread 都没扫到——守卫自己失效了（工作目录/路径变了？）",
            sites.isNotEmpty(),
        )
        val bad = sites.filterNot { it.named && it.daemon }
        assertTrue(
            "以下裸 Thread 不合纪律（必须有名字 + 必须 daemon）：\n" +
                bad.joinToString("\n") {
                    "  ${it.file}:${it.line}  名字=${if (it.named) "有" else "缺"}  daemon=${if (it.daemon) "有" else "缺"}"
                },
            bad.isEmpty(),
        )
    }

    @Test
    fun `门禁_创建点数量收敛在红线以内`() {
        val total = scanAppSources().size
        // 2026-10-16 实测 29 处（刀 0b 后 27；抱朴刀 1 新增 zd-baopu-run / zd-baopu-tool 两条 => 29）；
        // 2026-10-18 刀 4 新增 zd-preview-http（预览服务 acceptor）=> 30；
        // 红线 32——允许零星新增，但谁把数量拉回审计原文（35 处）那种状态，必须先在账本上说清楚。
        assertTrue("裸 Thread 创建点 $total 处，超过红线 32——先收敛再谈新增", total in 1..32)
    }

    @Test
    fun `对账单_2026-10-16_实测 29 处创建点`() {
        val got = scanAppSources()
            .map { "${it.file.substringAfterLast('\\').substringAfterLast('/')}:${it.line}" }
            .sorted()
        val expected = listOf(
            "AgentInstaller.kt:54",                 // zd-agent-install
            "AgentManifest.kt:136",                 // zd-manifest-refresh
            "BaopuDb.kt:921",                       // zd-baopu-db（抱朴刀 0：SQLite 单写者；刀 0b 加 memory 表/迁移/缓存列后自 :258 两次平移；刀 2 加资料库两表+章节索引表 +67 ⇒ 393→460；刀 3 B 段 v4 迁移+usage 语句 +50 ⇒ 460→510；刀 3 A 段 pending 三列+v5 迁移+PendingStore +44 ⇒ 510→554；第十三批 v6 compression 表+迁移+CompressionStore +81 ⇒ 554→635；第十六批第一件 RUN_LOG_STEPS_SELECT +12 ⇒ 635→647；第十七批 plan part 读写 SQL +37 ⇒ 647→684；第十九批封卷两列+迁移函数 +37 ⇒ 684→721；第二十批 workspace 列+迁移+会话库 SELECT +37 ⇒ 721→758；第二十五批会话库 SQL 改 LEFT JOIN 计数 +6 ⇒ 758→764；第二十三批B段补 compression 删语句 +6 ⇒ 764→770；第四十批编辑上一条三条 SQL +29 ⇒ 770→799；第三十八批 §9 产物台账：VERSION 8→9 两行 +2、artifact DDL 两条＋ARTIFACT_LIST_MAX＋四条语句（upsert/count/list/delete）+90 ⇒ 799→891。**同一条线程**，只是被顶走，不是新增）
            "HomeScreen.kt:667",                    // zd-health-fix（清场删掉 zd-taiji-retry 后自 :620 平移 -21；第十八批 B 段体检区改合并行结构＋C 段顶栏 tagline ⇒ 599→667）
            "BrowserAgentBridge.kt:73",            // zd-bridge-http（2026-10-15 浏览器桥 acceptor 线程池；第㊷刀改字节级请求解析行号平移）
            "KeepaliveWatcher.kt:66",               // zd-keepalive-io（第⑪批 D-1 心跳执行器）
            "KeepaliveWatcher.kt:230",              // zhengdao-keepalive-patrol（D-1 编辑后行号平移）
            "KnowledgeBase.kt:152",                 // zd-knowledge-base-rebuild（B-补-24 换共享 formatter，import 少一行 ⇒ -1）
            "KnowledgeBase.kt:175",                 // zd-knowledge-base-index（同上 -1）
            "KnowledgeBase.kt:199",                 // zd-knowledge-base-auto（同上 -1）
            "KnowledgeBaseHints.kt:133",            // zd-kb-hints
            "GuardPipeline.kt:68",                  // zd-baopu-tool（抱朴刀 1：工具执行单线程，串行+超时；26-B 内核委托 import +1 ⇒ 66→67；第四十八批 P0-2 加 PathGuard import +1 ⇒ 67→68）
            "LlmClient.kt:862",                     // zd-baopu-net（抱朴刀 0：SSE 网络 lane；刀 1 加 tools/tool_calls 解析后自 :315 平移；刀 1 补丁 Failed.raw 再 +7；刀 3 B 段 usage/reasoning/effort +208 ⇒ 450→658；第十三批加 completeOnce/completeBody/plainTextOf +134 ⇒ 658→792；第二十四批加 LlmMessage.images 与 contentBlocks +70 ⇒ 792→862）
            "MainActivity.kt:316",                  // zd-update-check（第㉕刀 +7 / B-补-15 +17 / B-补-19 +21 / B-补-8 +1 / 抱朴刀 2 快捷方式接线 +27 ⇒ 281→308；2026-10-18 加屏幕常亮标志 +8 ⇒ 308→316）
            "NetSelfCheck.kt:94",                   // net-self-check-index
            "PreviewServer.kt:112",                 // zd-preview-http（刀 4 预览服务 acceptor：一条串行线程，有名 + daemon；登记于 2026-10-18）
            "RunEngine.kt:192",                     // zd-baopu-run（抱朴刀 1：引擎状态机循环单线程，全局单活跃；真机修复 161→171；刀 1 补丁换通道 +34 ⇒ 205→215；刀 3 A 段确认制（ConfirmFlow/HistoryCodec/接线）+293 ⇒ 215→508；刀 3 A 段拆文件（RunRules/HistoryCodec/ConfirmFlow 出笼 + import 清理 -357）⇒ 508→151；工具补强二收尾闸字段/闸口 +16 ⇒ 151→167；真机修正单活跃门 release 的 KDoc +12 ⇒ 167→179）；第十七批工作计划卡接线（imports +2 ⇒ 180→182）；2026-10-18 大图缩图批（构造器加 shrinkImage 形参 +7 ⇒ 183→190）；第三十七批 B 段「这次学到」提案卡（import SkillRefine/SkillRules +2 ⇒ 190→192）
            "SettingsDataActivity.kt:592",          // zd-net-check（清场批注 +1；抱朴刀 2 加「抱朴工具」开关卡 +53 ⇒ 589→642；刀 3 B 段模型参数+思考深度两卡 +57 ⇒ 642→699；工具补强二三开关加在它之后 ⇒ 行号不动；第十八批 A 段抱朴三块整搬进 BaopuSettingsScreen（-106 行）+ 搬迁批注 +3 ⇒ 699→592）
            "SettingsEnvActivity.kt:218",           // zd-hermes-repair（B-补-22 import +2 / @OptIn KDoc +5；第十八批 B 段 Hermes 卡条件化包裹 +4 ⇒ 214→218）
            "SettingsEnvActivity.kt:273",           // zd-env-check（同上 +7；第十八批 B 段 Hermes 卡条件化包裹再 +5 ⇒ 268→273）
            "SettingsEnvActivity.kt:586",           // zd-env-repair（第⑯刀 R-29 +4 / B-补-22 内存上限卡 +11 / 清场批注 +1；第十八批 B 段包裹 +5；第五十二批 P0-5 守卫 +8 ⇒ 578→586）
            "SettingsEnvActivity.kt:687",           // zd-env-update（同上 +11 / +1；第十八批 B 段包裹 +5；第五十二批 P0-5 守卫 +5 ⇒ 673→687）
            "TerminalActivity.kt:83",               // zd-paste（第㉕刀 insets +3 / 第㉚刀 import +1）
            "TerminalActivity.kt:654",              // zd-session-start（㉕ +23 / ㉚ +3 / B-补-13 ＋ B-补-21 横幅 +11；第二十一批 +17；第五十三批 P1-4 +4）
            "TerminalActivity.kt:869",              // zd-net-probe（同上；第五十三批 P1-4 +4）
            "TerminalActivity.kt:1683",             // zd-install-saf（㉕ +23 / ㉚ +3 / 横幅 +22；2026-10-18 终端标题居中回退 -1；第二十一批 +63；第五十三批 P1-4 +4 / P1-6 +7）
            "TerminalActivity.kt:1743",             // zd-install-cache（同上）
            "TerminalActivity.kt:1829",             // zd-install-download（同上）
            "ZhengdaoApp.kt:82",                    // zhengdao-keepalive-archive（第⑲刀 +3；清场删两 import −2、删 onCreate 调用 −1、补说明 +2 ⇒ +2）
            "ZhengdaoApp.kt:132",                   // zd-auto-clean（同上）
        )
        assertEquals(
            "裸 Thread 创建点与 2026-10-15 对账单不符——新增/移动了线程？" +
                "按纪律补 有名+daemon 后回来更新对账单（它不是 bug，是提醒）",
            expected.sorted(),
            got,
        )
    }

    // ── 扫描器 ──
    //
    // 为什么不能只用正则：首版门禁稿（本刀前任留的未完成件）用
    // `Thread\(\s*(\{|r\b|…)` + `[^)]*,\s*"…"` 两条正则，实测四处失抓/误抓：
    // ① `Thread {` 尾随 lambda 形态（无括号）整个漏掉——18 处非合规创建点全是
    //    这个房型，首轮"7 处"的漏数就是这么来的；
    // ② 名字走属性赋值（`name = "…"`）漏判（ZhengdaoApp / KeepaliveWatcher 两处）；
    // ③ 跨行 lambda 体内的括号让 `[^)]*` 提前截断，有名也判成没名；
    // ④ 注释里的字面量（`isDaemon = true` / `Thread(`）会虚增计数——台账坑：
    //    门禁被自己的注释带偏（TerminalActivity.kt 那条 P4-2 注释里就写着
    //    isDaemon=true）。因此改为：先去注释（保留字符串），再逐字符扫描
    //    （跳过字符串/字符字面量/字符串模板），括号配对用深度计数。

    /** 名字/daemon 的搜索窗口：创建处之后 400 字符（`.apply { … }`、`t.isDaemon = true` 都落在这里）。 */
    private val regionWindow = 400

    /** 扫 App 自己的 Kotlin 源码（vendored termux 是 Java，天然不在范围内）。 */
    private fun scanAppSources(): List<Site> {
        val root = File("src/main/java/com/example/zhengdao")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .flatMap { collectSites(it.readText(Charsets.UTF_8), it.path) }
            .toList()
    }

    /**
     * 扫出文件中所有裸 Thread 创建点。
     * 算创建点：`Thread(…)` / `Thread {…}`，以及标准库小写 `thread {…}`（默认非 daemon，同纪律）。
     * 不算：`Thread.currentThread()` / `Thread.sleep(…)`（点是成员访问）、
     * `startSessionOffMainThread()`（词边界）、`HandlerThread(…)`（另一个类）、
     * 注释与字符串里的一切（先去注释 + 扫描跳过字符串）。
     */
    private fun collectSites(raw: String, path: String): List<Site> {
        val code = stripComments(raw)
        val sites = mutableListOf<Site>()
        var i = 0
        var line = 1
        val n = code.length
        while (i < n) {
            val c = code[i]
            if (c == '\n') { line++; i++; continue }
            if (c == '"' || c == '\'') {
                val end = if (c == '"' && code.startsWith("\"\"\"", i)) skipRawString(code, i) else skipString(code, i, c)
                line += code.substring(i, end).count { it == '\n' }
                i = end
                continue
            }
            val kwLen = when {
                c == 'T' && code.startsWith("Thread", i) -> 6
                c == 't' && code.startsWith("thread", i) -> 6
                else -> 0
            }
            val prevOk = i == 0 ||
                (!code[i - 1].isLetterOrDigit() && code[i - 1] != '_' && code[i - 1] != '.')
            if (kwLen > 0 && prevOk) {
                var j = i + kwLen
                while (j < n && (code[j] == ' ' || code[j] == '\t')) j++
                if (j < n && (code[j] == '(' || code[j] == '{')) {
                    sites += inspectSite(code, j, line, path)
                    i = j + 1
                    continue
                }
            }
            i++
        }
        return sites
    }

    /** 判定一条创建点的名字与 daemon（delimIdx 指向 `(` 或 `{`）。 */
    private fun inspectSite(code: String, delimIdx: Int, line: Int, path: String): Site {
        val open = code[delimIdx]
        val close = if (open == '(') ')' else '}'
        val closeIdx = scanBalanced(code, delimIdx, open, close)
        // 名字：构造函数里的字符串参数（Thread(r, "name")），或创建处之后窗口内的 name = "…" 属性赋值
        val args = if (closeIdx > 0) code.substring(delimIdx + 1, closeIdx) else ""
        val regionStart = if (closeIdx > 0) closeIdx + 1 else delimIdx + 1
        val region = code.substring(regionStart, minOf(regionStart + regionWindow, code.length))
        val named = (open == '(' && args.contains('"')) ||
            Regex("""\bname\s*=\s*["]""").containsMatchIn(region)
        // daemon：同一窗口内的 isDaemon = true（注释已被 stripComments 去掉，注释里的字面量不算）
        val daemon = Regex("""isDaemon\s*=\s*true""").containsMatchIn(region)
        return Site(path, line, named, daemon)
    }

    /** 从 code[start]（= open）深度配对到 close；跳过字符串/字符字面量。未闭合返回 -1。 */
    private fun scanBalanced(code: String, start: Int, open: Char, close: Char): Int {
        var depth = 0
        var i = start
        while (i < code.length) {
            val c = code[i]
            if (c == '"' || c == '\'') {
                i = if (c == '"' && code.startsWith("\"\"\"", i)) skipRawString(code, i) else skipString(code, i, c)
                continue
            }
            if (c == open) depth++
            else if (c == close) {
                depth--
                if (depth == 0) return i
            }
            i++
        }
        return -1
    }

    // ── 去注释（Kotlin 块注释可嵌套；字符串/字符字面量原样保留；换行保留以维持行号） ──

    private fun stripComments(text: String): String {
        val out = StringBuilder(text.length)
        var i = 0
        val n = text.length
        while (i < n) {
            val c = text[i]
            when {
                c == '/' && i + 1 < n && text[i + 1] == '/' -> {
                    while (i < n && text[i] != '\n') { out.append(' '); i++ }
                }
                c == '/' && i + 1 < n && text[i + 1] == '*' -> {
                    var depth = 1
                    out.append(' ').append(' ')
                    i += 2
                    while (i < n && depth > 0) {
                        if (text[i] == '/' && i + 1 < n && text[i + 1] == '*') {
                            depth++; out.append(' ').append(' '); i += 2
                        } else if (text[i] == '*' && i + 1 < n && text[i + 1] == '/') {
                            depth--; out.append(' ').append(' '); i += 2
                        } else {
                            out.append(if (text[i] == '\n') '\n' else ' '); i++
                        }
                    }
                }
                c == '"' && text.startsWith("\"\"\"", i) -> {
                    val end = skipRawString(text, i)
                    out.append(text, i, end)
                    i = end
                }
                c == '"' -> {
                    val end = skipString(text, i, '"')
                    out.append(text, i, end)
                    i = end
                }
                c == '\'' -> {
                    val end = skipString(text, i, '\'')
                    out.append(text, i, end)
                    i = end
                }
                else -> { out.append(c); i++ }
            }
        }
        return out.toString()
    }

    /** 普通字符串/字符字面量：从开引号到未转义的闭引号；`${…}` 模板里可能再嵌字符串，递归跳过。 */
    private fun skipString(text: String, start: Int, quote: Char): Int {
        var i = start + 1
        while (i < text.length) {
            when (val c = text[i]) {
                '\\' -> i += 2
                quote -> return i + 1
                '\n' -> return i // 未闭合就到行尾（异常代码）：交还主循环，行号不错
                '$' if i + 1 < text.length && text[i + 1] == '{' -> i = skipTemplate(text, i + 2)
                else -> i++
            }
        }
        return text.length
    }

    /** 字符串模板 `${` 之后：深度配对到 `}` 之后；里面可能有字符串/字符字面量。 */
    private fun skipTemplate(text: String, start: Int): Int {
        var depth = 1
        var i = start
        while (i < text.length) {
            when (val c = text[i]) {
                '{' -> { depth++; i++ }
                '}' -> { depth--; i++; if (depth == 0) return i }
                '"' -> i = if (text.startsWith("\"\"\"", i)) skipRawString(text, i) else skipString(text, i, '"')
                '\'' -> i = skipString(text, i, '\'')
                '\n' -> return i
                else -> i++
            }
        }
        return text.length
    }

    /** 原始字符串 `"""…"""`：到下一个三引号为止。 */
    private fun skipRawString(text: String, start: Int): Int {
        var i = start + 3
        while (i < text.length) {
            if (text.startsWith("\"\"\"", i)) return i + 3
            i++
        }
        return text.length
    }

    // ── 自检：扫描器自身靠得住 ──
    // （台账坑：文本门禁容易被自己注释里的字面量带偏——所以下面两例专门拿
    //  注释/字符串/模板/成员访问/大小写/另一个类名喂它。）

    @Test
    fun `自检_注释与字符串里的 Thread 不算创建点`() {
        val tricky = """
            // Thread({ foo() }, "line-comment") .apply { isDaemon = true }
            /* Thread({ bar() }, "block-comment") isDaemon = true
               Thread({ baz() }, "nested /* still comment */ tail") */
            val url = "https://example.com/Thread(not-a-thread)"
            val label = "say ${'$'}{if (ok) "Thread(" else "no"}"
            val a = Thread({ realWork() }, "real-name").apply { isDaemon = true }
            val b = Thread { work2() }.apply { isDaemon = true; name = "prop-name" }
            a.start(); b.start()
        """.trimIndent()
        val sites = collectSites(tricky, "synthetic.kt")
        assertEquals(2, sites.size)
        assertTrue("两条都应有名且 daemon：$sites", sites.all { it.named && it.daemon })
    }

    @Test
    fun `自检_违规形态与误报形态都能正确分辨`() {
        val mixed = """
            val t1 = Thread({ work() })
            t1.start()
            val t2 = Thread({ work() }, "has-name-no-daemon")
            t2.start()
            Thread.currentThread()
            startSessionOffMainThread()
            Thread.sleep(100)
            HandlerThread("factory")
            val t3 = thread { legacy() }
            t3.start()
        """.trimIndent()
        val sites = collectSites(mixed, "synthetic2.kt")
        // 只有 t1/t2/t3 是创建点；成员访问/函数名尾巴/另一个类都不算
        assertEquals(3, sites.size)
        assertTrue("三条违规都应被抓出来：$sites", sites.none { it.named && it.daemon })
    }
}
