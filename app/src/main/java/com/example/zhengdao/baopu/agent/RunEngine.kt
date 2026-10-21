// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §2 状态机（每步迁移一次 SQLite 事务；max_steps=120（2026-10-19 两次上调 16→48→120，见 RunRules）；
// 一步 = 一次模型轮次；确认批不计步；全局单活跃会话；通道熔断）+ §8 错误矩阵。
//
// 2026-10-17（抱朴刀 3）：纯判据拆去 `RunRules.kt`、历史编解码拆去 `HistoryCodec.kt`、
// 确认制内核拆去 `ConfirmFlow.kt`——派工单 §9 行数纪律「单文件新增 ≤400 行；
// 超了拆文件」。本文件只剩引擎壳（状态机 + 接线 + 落库）。
package com.example.zhengdao.baopu.agent

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.example.zhengdao.baopu.AssembledPrompt
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuPreset
import com.example.zhengdao.baopu.BaopuSql
import com.example.zhengdao.baopu.Compressor
import com.example.zhengdao.baopu.ContextAssembler
import com.example.zhengdao.baopu.SkillRefine
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.HardFacts
import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.VisionFiles
import com.example.zhengdao.baopu.verify.ArtifactVerifier
import com.example.zhengdao.baopu.verify.Finding
import com.example.zhengdao.baopu.verify.VerifyTargets
import com.example.zhengdao.baopu.LlmClient
import com.example.zhengdao.baopu.LlmEvent
import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.LlmUsage
import com.example.zhengdao.baopu.MemoryRules
import com.example.zhengdao.baopu.MemoryStore
import com.example.zhengdao.baopu.PendingStore
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaRules
import com.example.zhengdao.baopu.PlanStore
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.SessionCompressionStore
import com.example.zhengdao.baopu.SessionPendingStore
import com.example.zhengdao.baopu.SessionPlanStore
import com.example.zhengdao.rootfs.RunLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.runBlocking

/** 引擎状态（程枢版 §2 状态表；UI 只读这些）。 */
enum class RunState { IDLE, THINKING, ACTING, AWAIT_CONFIRM, COMPRESSING, ABORTED, DONE }

/**
 * 全局单活跃会话门（程枢版 §2 补录：一次只允许一个活跃会话；
 * 后台会话不允许自动跑）。
 *
 * 进程级单例：两个入口（Tab 重进/深链）抢同一个会话时要看到同一扇门。
 */
internal object ActiveSessionGate {
    private val active = java.util.concurrent.atomic.AtomicReference<String?>(null)

    /** @return true = 抢到（或本就持有）；false = 别的会话正活跃。 */
    fun tryAcquire(sessionKey: String): Boolean {
        while (true) {
            val cur = active.get()
            if (cur == null) {
                if (active.compareAndSet(null, sessionKey)) return true
            } else {
                return cur == sessionKey
            }
        }
    }

    /**
     * 释放（2026-10-18 真机修正）：**必须按值比较**再 CAS。
     *
     * 原来写的是 `active.compareAndSet(sessionKey, null)` —— `AtomicReference`
     * 用的是**引用相等**，而 `"id:$sessionId"` 每次都是新字符串 ⇒ CAS 永远失败，
     * 门放不掉。症状：跑完一轮后**换一个会话**发送被挡（「已有另一个会话在跑」），
     * 同一会话重发却能过（[tryAcquire] 用的是 `==`）——真机排查了两次才定位。
     */
    fun release(sessionKey: String) {
        while (true) {
            val cur = active.get() ?: return
            if (cur != sessionKey) return
            if (active.compareAndSet(cur, null)) return
        }
    }

    fun current(): String? = active.get()
}

/** 给 UI 的事件（引擎 → 界面；界面不回数据给引擎，只观察）。 */
sealed interface EngineEvent {
    /** 一条消息落库完成（UI 从 db 重读列表——先落库再渲染）。 */
    data class MessagePersisted(val messageId: Long, val role: String) : EngineEvent

    /** 工具调用可见卡（入参出参摘要；UI 渲染后仍从 db 读全量）。 */
    data class ToolCallVisible(val tool: String, val summary: String, val ok: Boolean) : EngineEvent

    /** 需要人看一眼（停下问人 / 熔断 / 步数耗尽——都是"可报告地失败"）。 */
    data class AskUser(val message: String) : EngineEvent

    /** 收尾时把抱朴自己造的 `临时/` 垃圾移进回收站，搬走了几个（2026-10-20 用户口径）。 */
    data class ScratchLeft(val count: Int) : EngineEvent

    /**
     * 本轮彻底收尾（单活跃门**已释放**、`run_log` 收尾行**已落库**）：UI 该重读一次库。
     *
     * 为什么必须单独有这一条（2026-10-17 真机实测）：收尾那一下如果没人通知 UI，
     * 界面就会一直停在"运行中最后一次刷新"的值上——正常完成的会话仍显示
     * 「上次中断于第 N 步（进程当时被杀了）」。判据本身没错（`run_log` 最后一条是
     * `Done` 就算没中断），错在**没人去重算**。
     */
    data object Settled : EngineEvent
}

/**
 * 运行引擎（刀 1 · A 段）——**状态机 + 刹车 + 每步一次 SQLite 事务**。
 *
 * ## 线程模型
 * - 循环跑在 `zd-baopu-run`（daemon 单线程）：一次只有一个会话在跑（单活跃门）；
 * - 网络阻塞解析在 `LlmClient.netExecutor`（`zd-baopu-net`）：sink 回调在它上面，
 *   本类用 [CountDownLatch] 等本轮流结束（run 线程等，不占 UI）；
 * - 工具执行在 `zd-baopu-tool`（GuardPipeline 的 ToolRunner 里）。
 *
 * ## 每步一次事务（程枢版 §2）
 * 步迁移（IDLE→THINKING→ACTING→…）+ 该步落库（message/part + session.interrupted_step）
 * 包在一个事务里 ⇒ 进程在任何时刻被杀，重进都能看到一致的一步。
 *
 * ## UI 状态绑定（第⑮刀经验）
 * [state]/[step] 是 `mutableStateOf` + [State]，**不裸 @Volatile**。
 */
internal class RunEngine(
    /**
     * 当前通道（刀 1 补丁：熔断后会被换成候选通道）。
     * 构造期仍由 [com.example.zhengdao.baopu.BaopuRuntime.build] 注入**一份**，
     * 之后只在熔断降级时改写，别处不换。
     */
    private var provider: Provider,
    private val db: BaopuDb,
    private val registry: ToolRegistry,
    private val pipeline: GuardPipeline,
    private val personaFiles: PersonaFiles? = null,
    private val memoryStore: MemoryStore? = null,
    private val spillRoot: () -> File,
    /**
     * 缩图实现（第二十四批 C 段追加）：手机照片普遍 3 MB 上下，超过单张 2 MB 的图上送前先缩。
     *
     * 为什么由外部注入：解码/重编码要 Android `Bitmap`，引擎自己要能在 JVM 里跑
     * （单测喂假实现就能验「超限先缩、缩不成必须报原因」两条）；真实现是 `ImageShrink`。
     */
    private val shrinkImage: ((File) -> com.example.zhengdao.baopu.VisionFiles.ShrinkOutcome)? = null,
    /**
     * 熔断后的候选通道（刀 1 补丁）：入参是当前通道，返回下一个或 null。
     *
     * 为什么不给引擎一个"通道列表"：Key 只该在 ProviderPrefs 手里，引擎拿到的
     * 已经是拼好的 [Provider]（含 Key），它不需要知道 Key 从哪来。
     */
    private val channelFallback: (Provider) -> Provider? = { null },
    /**
     * 安全点切换效应（第六十四批）：入参 (当前通道 id, 目标通道 id)，
     * 返回重建后的新 Provider；null = 没切过去（调用方要回滚并留因）。
     *
     * 写只经 [com.example.zhengdao.baopu.ProviderPrefs]（BaopuRuntime 装配层
     * 注入，引擎不持 context——与 [channelFallback] 同一纪律：Key 不进引擎）。
     * 引擎的 [provider] 字段只在两处被写：熔断降级、本效应的安全点切换。
     */
    private val switchChannel: (String, String) -> Provider? = { _, _ -> null },
    /**
     * 并行读段的执行器（第九十二批）。默认就是生产口径（读池 `zd-baopu-tool-read` ＋ 墙钟）；
     * 单测要控时序/池大小时注入自己的（引擎本身要 Android SQLite，JVM 里起不来，
     * 所以并行段的时序用例都在 `ToolSchedulingTest` 里直接驱这个执行器）。
     */
    private val batchRunner: ToolBatchRunner = ToolBatchRunner(),
    private val log: (String) -> Unit = { RunLog.log(it) },
) {

    /** UI 观察用（Compose State）。 */
    private val _state = mutableStateOf(RunState.IDLE)
    val state: State<RunState> get() = _state

    private val _step = mutableStateOf(0)
    val step: State<Int> get() = _step

    private val _sessionKey = mutableStateOf<String?>(null)
    val sessionKey: State<String?> get() = _sessionKey

    /** 当前会话的 interrupted_step（-1 = 未中断；UI 标「中断于第 N 步」）。 */
    private val _interruptedStep = mutableStateOf(RunRules.NOT_INTERRUPTED)
    val interruptedStep: State<Int> get() = _interruptedStep

    /**
     * 收尾闸（2026-10-17）：本轮写过的相对路径。
     *
     * 为什么记在内存而不是查库：要验的是「这一轮到底落了什么盘」，重进 App 后
     * 该轮已经结束、也没有「继续验收」的义务；查库里 800 行 run_log 反而会把
     * 上一轮的文件算进来（假红比不验更坏）。
     */
    private val verifyTouched = LinkedHashSet<String>()

    /** 已经让模型「按自检报告修过一次」的次数（只给一次机会，修不好就如实说没通过）。 */
    private var verifyRepairs = 0

    /**
     * 连续几轮「工具参数不是合法 JSON」（P1-2，2026-10-19 第五十四批）。
     *
     * 只在**连续**坏的时候数：模型自己改对了（Text/ToolCalls 正常回来）就归零。
     * 为什么需要它：坏参数不是通道坏，给它一次看错误原文的机会；但同一个错误
     * 连着来第二次，说明这一轮状态不对，继续重试只是烧 token。
     */
    private var badArgsRetries = 0

    /**
     * 换通道后重跑同一步 ⇒ 这一步**不重复计**预算（P1-3，2026-10-19 第五十四批）。
     *
     * 旧行为：换通道分支 `continue` 回循环头，`stepCount++` 照跑 ⇒ 每次换通道白烧一步
     * （注释写着「不涨步数」，代码却涨了——典型的注释与实现打架）。
     */
    private var reuseStep = false


    /** 给人看的一句话（错误号口径：不直出英文 err）。 */
    private val _notice = mutableStateOf<String?>(null)
    val notice: State<String?> get() = _notice

    var onEvent: ((EngineEvent) -> Unit)? = null

    private val runExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zd-baopu-run").apply { isDaemon = true }
    }

    /**
     * spill 文件名序号（第九十二批）：并行读之后 [writeSpill] 会被几个读线程同时调，
     * 毫秒时间戳不足以区分「同一毫秒里的两个同名工具」⇒ 补一个自增序号（原子）。
     */
    private val spillSeq = java.util.concurrent.atomic.AtomicLong(0L)

    // ── 第八十七批 R1：运行轨迹 ────────────────────────────────────────────
    //
    // ⚠️ 同样只能写在冻结创建点（上面那个 `Thread(r, "zd-baopu-run")`）**之下**：
    // `BareThreadGuardTest` 的对账单按 file:line 钉着它，在它上面插一行就平移、门禁红。
    //
    // 记录器本身不含 IO（[TraceJournal] 只配对时刻、发号、拼行），落库经 [persistTrace]
    // 丢给 db 单写者线程，**不 `.get()`**：轨迹是账本，不该让一次运行等它写盘。
    private val trace = TraceJournal(emit = ::persistTrace)

    /**
     * 轨迹落库（唯一写入口）。
     *
     * 失败只记日志、绝不把整轮跑崩：轨迹是「观测」，观测坏掉不许连带任务失败
     * （与产物台账 [persistArtifactLedger] 同一房型）。不 `.get()` ⇒ 不等提交，
     * 顺序由 db 单写者线程保证（同一 run 的行天然有序）。
     */
    private fun persistTrace(row: TraceRow) {
        val stmt = row.toStmt()
        runCatching { db.write { d -> d.execSQL(stmt.sql, stmt.args) } }
            .onFailure { log("抱朴引擎：轨迹落库失败 ${it.javaClass.simpleName}: ${it.message}") }
    }

    // ── 第三十八批 A/B 段：用量透给壳层 ＋ 本轮文本产物视图 ──────────────────
    //
    // ⚠️ 这一段只能写在 runExecutor **之下**：`BareThreadGuardTest` 的冻结对账单按
    // `file:line` 钉住全部裸线程创建点，里面有 `RunEngine.kt:192`（就是上面那个
    // `Thread(r, "zd-baopu-run")`）。在它上面插任何一行 ⇒ 192 平移 ⇒ 门禁红。

    /**
     * 每步拿到 usage 就回调一次；null 表示这一步真没读到（不是 0）。
     *
     * 形制照 `AskUserTool.sink`：可空的 `var` lambda，由壳层在构造后挂上，引擎不假设有人听。
     * 调用点只有 [persistUsage] 一处（派工单 §1.1：同一处，别另起一条链），所以
     * 「落库的读数」与「界面上的读数」永远同源——不会出现库里 600、界面 500。
     *
     * **线程**：回调在 `zd-baopu-run` 上跑。实现体里只许改 `mutableStateOf`（Compose
     * 状态可跨线程写），不许读盘、不许起线程（判据 A6 用源码级用例钉着这条）。
     *
     * 与派工单片段的**一处偏差**（申报）：形参从 `LlmUsage` 换成 [BaopuUsageStep]——
     * §1.2 要求每步列表带「步骤号、耗时」，而步号与耗时只有引擎知道（壳层按回调
     * 到达顺序编号会把「中间有一步没读到 usage」的轮次编错号，那是编数）。
     */
    var onUsage: ((BaopuUsageStep) -> Unit)? = null

    /**
     * 本步开步时刻（`Thinking` 行落下时记）。0 = 还没开过步（断点续跑的第一个
     * 回调就是这种）⇒ [BaopuUsageStep.elapsedMs] 给 -1 = 未知，界面**不显示耗时**，
     * 不写「0.0s」假装知道。
     */
    private var stepStartedAtMs = 0L

    // ── 第六十四批：资源机（跨模型/通道切换状态机）────────────────────────
    //
    // ⚠️ 本段放在 `zd-baopu-run` 冻结创建点（BareThreadGuardTest 对账单按
    // file:line 钉）**下方**：在上面插行会平移创建点行号、把门禁弄红
    // （213–217 行那段警告说的是同一件事）。构造参 [switchChannel] 无法避开
    // （在类签名里），它的平移已按实测回填对账单。

    /**
     * 正交协作层。引擎只经它发请求（[requestChannelSwitch]）与落切换
     * （[drainChannelSwitch]，三个安全点共用）；「当前通道」真相仍是
     * ProviderPrefs ＋ 本类 [provider] 字段，本机不另存一份。
     */
    private val channelSwitch = ChannelSwitchCoordinator(
        applySwitch = ::applyChannelSwitch,
        hasPendingImages = { pendingImageRelPaths.isNotEmpty() },
        log = log,
    )

    private val _channelSwitchStatus = mutableStateOf(ChannelSwitchSnapshot())
    val channelSwitchStatus: State<ChannelSwitchSnapshot> get() = _channelSwitchStatus

    /**
     * 本轮**够格进「产物」行**的相对路径（写成功、文本、≤512 KB、前 8 KB 无 NUL）。
     *
     * 数据源就是 [verifyTouched]（收尾闸那份「这一轮真落了哪些盘」），只是在它之上
     * 加了一层筛选并把可变集合换成不可变快照给 Compose 读。**只读视图**：查看器
     * 一个字也不往盘上写（判据 B6）。
     */
    private val _roundArtifacts = mutableStateOf<List<String>>(emptyList())
    val roundArtifacts: State<List<String>> get() = _roundArtifacts

    /**
     * 换会话 / 开新会话时清空产物视图（引擎内那份 [verifyTouched] 不动——它只在
     * 一个 run 里有意义）。不清就会看到上个会话的文件名，点进去是别人的产物。
     */
    fun clearRoundArtifactView() {
        _roundArtifacts.value = emptyList()
    }

    /**
     * 把 [verifyTouched] 筛成产物列表（**在引擎线程上跑**：每个候选要开一次流读前
     * 8 KB 判二进制，所以不许搬到主线程去算）。
     *
     * 不在这里砍到 8 个——「最多 8 片 ＋ 还有 N 个」是展示层的事，在这儿砍了就
     * 数不出 N（判据 B5）。
     *
     * 用全限定名调 `com.example.zhengdao.baopu.selectTextArtifacts`：这批**不许在
     * 文件头加 import**——加一行就把 `Thread(r, "zd-baopu-run")` 从 192 行顶走，
     * `BareThreadGuardTest` 的冻结对账单立刻红（同文件里 `android.database.sqlite.SQLiteDatabase`
     * 早就是同一个写法）。
     */
    private fun publishRoundArtifacts() {
        _roundArtifacts.value =
            com.example.zhengdao.baopu.selectTextArtifacts(spillRoot(), verifyTouched.toList())
    }

    /**
     * 第三十八批 §9.1：把**这一子调用真写成功**的文本产物记进 `artifact` 台账。
     *
     * 「什么算产物」一个字都不在这里重写——直接复用 B 段那两个纯函数：
     * `selectTextArtifacts`（文本白名单＋≤512 KB＋前 8 KB 无 NUL）与
     * `artifactEntries`（字节数取自盘，不编）。顶栏、消息列表、抽屉三处看的是同一份
     * 规则（用户 2026-10-19 口径：两处共用同一份收集逻辑与查看器）。
     *
     * 表里只有**相对路径**与字节数（§9.4：不存绝对路径、不存文件内容）；
     * 同一 `(workspace, rel_path)` 再写 ⇒ `ON CONFLICT DO UPDATE` 只刷字节与时间，
     * 一份文件永远一行（判据 C1）。
     *
     * 线程：读盘跟 `publishRoundArtifacts` 一样在 `zd-baopu-run` 上；写库走 `db.write`
     * 的单写者线程（与本文件 usage 落库同房型）。落库失败只 log 一句——台账是账本，
     * 不该把整轮跑崩，更不许静默假装记上了。
     *
     * 全程全限定名调 `com.example.zhengdao.baopu.*`：本文件头**不许加 import**——加一行
     * 就把 `Thread(r, "zd-baopu-run")`（192 行）顶走，`BareThreadGuardTest` 的冻结
     * 对账单立刻红（同文件里 usage 那条链早就是同一个写法）。
     */
    private fun persistArtifactLedger(sessionId: Long, touched: List<String>) {
        val root = spillRoot()
        val kept = com.example.zhengdao.baopu.selectTextArtifacts(root, touched)
        if (kept.isEmpty()) return
        // workspace 存绝对路径**字符串**（与 session.workspace 同一取法：spillRoot() 就是
        // BaopuRuntime.workspaceRoot ⇒ 与 hostDir 同一个目录），产物按工作区隔离全靠它。
        val workspace = root.absolutePath
        val now = System.currentTimeMillis()
        val stmts = com.example.zhengdao.baopu.artifactEntries(root, kept).map { e ->
            com.example.zhengdao.baopu.BaopuSql.upsertArtifact(sessionId, workspace, e.relPath, e.fileBytes, now)
        }
        runCatching { db.write { d -> stmts.forEach { s -> d.execSQL(s.sql, s.args) } }.get() }
            .onFailure { log("抱朴引擎：产物台账落库失败 ${it.javaClass.simpleName}: ${it.message}") }
    }

    @Volatile
    private var stopRequested = false

    /**
     * 当前这一轮流式调用的句柄（P2-5，2026-10-19 第五十六批）。
     *
     * 只为「停止」服务：用户点停止时立刻 cancel 底层的 Call，不用干等
     * readTimeout（60s）。@Volatile：写在引擎线程、读在调用 stop() 的那条线程。
     */
    @Volatile
    private var activeCall: okhttp3.Call? = null

    /** 连续工具失败计数（到 [RunRules.MAX_TOOL_FAILURES] 停下问人）。 */
    private var consecutiveToolFailures = 0

    /** 当前通道连续失败计数（到 [RunRules.CHANNEL_BREAK_THRESHOLD] 熔断）。 */
    private var consecutiveChannelFailures = 0

    @Volatile
    private var channelBroken = false

    /** 本次运行里自动换过几次通道（到 [RunRules.MAX_CHANNEL_SWITCHES] 就把候选关掉）。 */
    private var channelSwitches = 0

    // ── 确认制（刀 3 A 段） ──────────────────────────────────────────────

    /** pending 三列的存取（清与读；"进 AWAIT_CONFIRM"的写在引擎事务里，见 [enterAwaitConfirm]）。 */
    private val pendingStore: PendingStore = SessionPendingStore(db)

    // ── 压缩（第十三批：桩换成真跑） ──────────────────────────────────────

    /** 压缩落库与留痕（薄壳，照 [pendingStore] 房型）。 */
    private val compressionStore: CompressionStore = SessionCompressionStore(db)

    /** 本会话已压过的消息 id（摘要替身；**也是**组装时要跳过的 id——同一个集合两用）。 */
    private var compressedIds: Set<String> = emptySet()

    /** 压缩摘要替身（null = 还没压过；进程被杀后由 [restoreCompression] 塞回来）。 */
    private var compressionSummary: String? = null

    /**
     * 压缩内核（真跑一次压缩；判据 J3/J6/J7 在内核层用假件验，引擎这里只接线）。
     *
     * `complete` 里每次 new 一个 [LlmClient]（与 [streamRound] 同款：压缩是低频动作，
     * 不值得为它常驻一个客户端；provider 换了也会跟着换，不会压到旧通道上）。
     */
    private val compression = CompressionRunner(
        complete = { messages, maxTokens, temperature ->
            LlmClient(provider).completeOnce(messages, maxTokens, temperature)
        },
        store = compressionStore,
        log = log,
    )

    /**
     * 会话恢复时把压过的摘要与跳过列表塞回来（第十三批 C 段 · 派工单 §4.3）。
     *
     * 为什么需要：进程被杀重进，内存里的 [compressionSummary] 是空的 ⇒ 摘要丢了、
     * 被压的旧消息又全量进 prompt，等于白压。控制器构造时从 `compression` 表读回来。
     */
    fun restoreCompression(summary: String?, ids: Set<String>) {
        compressionSummary = summary
        compressedIds = ids
    }

    // ── 工作计划（第十七批 A 段：update_plan 接线） ────────────────────────

    /**
     * 当前轮会话（[PlanController] 落库要知道落到哪；单活跃门保证同时只有一个）。
     * runLoop 开头设，工具执行线程（submit）读 ⇒ [Volatile]。
     */
    @Volatile
    private var planSessionId: Long = -1

    /**
     * update_plan 内核（第十七批 A+B 段）：校验归一化 ⇒ sticky done ⇒ 落库 ⇒ 回灌。
     *
     * 为什么内核单独一个类：它要 JVM 直测（派工单 §3「JVM，纯函数优先」）——
     * 判据全压 [PlanController] ＋ 假 store；引擎壳只喂 sessionId 与轮次事件
     * （照 [CompressionRunner] 的房型：内核零 Android、壳只接线）。
     */
    private val planController = PlanController(
        sessionId = { planSessionId },
        store = SessionPlanStore(db),
        log = log,
    )

    init {
        PlanTool.sink = planController
    }

    /**
     * 确认制内核。回调全是引擎侧接线：
     * - [enterAwaitConfirm]：run_log 的 AwaitConfirm 行 + session pending 三列**同事务**；
     * - 出卡事件直接转 [onEvent]（UI 从事件拿卡，不轮询）；
     * - 前台计时见 [confirmForegroundMs]（屏幕生命周期经 [noteConfirmBackground] 喂）。
     */
    private val confirmFlow = ConfirmFlow(
        pipeline = pipeline,
        store = pendingStore,
        foregroundElapsedMs = { RunRules.confirmElapsedMs(confirmForegroundMs, confirmResumeAt, System.currentTimeMillis()) },
        onPersistPending = { sid, step, tool, argsJson, at ->
            enterAwaitConfirm(sid, step, tool, argsJson, at)
            // 前台计时从进确认这一刻起算（进后台再由 noteConfirmBackground 暂停累计）
            confirmForegroundMs = 0L
            confirmResumeAt = at
        },
        onRequestCard = { req -> onEvent?.invoke(req) },
        log = log,
    )

    /** 确认等待的**前台累计毫秒**（后台不计时——切后台时 [noteConfirmBackground] 结账）。 */
    private var confirmForegroundMs = 0L

    /** 本次进前台的时刻（null = 当前在后台或没有确认在等）。 */
    private var confirmResumeAt: Long? = null

    // ── 对外动作 ──────────────────────────────────────────────────────────

    /**
     * 用户发一条消息（会话不存在则由调用方先 [createSession]）。
     * 前台入口：UI 线程调，循环丢 [runExecutor]。
     */
    fun send(
        sessionId: Long,
        text: String,
        imageRelPaths: List<String> = emptyList(),
    ) {
        if (text.isBlank()) return
        acquireOrNotice(sessionId) ?: return
        stopRequested = false
        _notice.value = null
        // 新一轮：上一轮的「这次学到」卡与信号都清掉（卡不跨轮保留，免得攒成一叠）
        learnSignals.clear()
        learnShown = false
        _learnNames.value = emptySet()
        _learn.value = null
        // 新一轮 ⇒ 上一轮的「尾部被截断」标记作废（第四十批）
        _tailTruncated.value = false
        // 第二十四批 C 段：本轮要随请求发出去的图片。**只收相对路径**，读盘在引擎线程里做
        // （调用方是主线程，不许在那里读 2 MB 文件）。
        pendingImageRelPaths = imageRelPaths
        runExecutor.execute {
            try {
                runLoop(sessionId, userText = text, resumed = false)
            } catch (e: Exception) {
                log("抱朴引擎：循环崩了 ${e.javaClass.simpleName}: ${e.message}")
                _state.value = RunState.ABORTED
                _notice.value = "引擎内部错误（已记录日志）：${e.javaClass.simpleName}"
            } finally {
                // 本轮图片与说明都不外溢：下一次 resume 若不清，会把上一轮的图和说明再发一遍
                turnImages = emptyList()
                turnNotes = emptyList()
                pendingImageRelPaths = emptyList()
                ActiveSessionGate.release("id:$sessionId")
                reapScratch()
            reapScratch()
            onEvent?.invoke(EngineEvent.Settled) // 门已释放 ⇒ UI 重算中断标记（见 Settled 的说明）
            }
        }
    }

    /**
     * `ask_user` 的选项卡状态（UI 拿它在输入栏上方渲染）。
     *
     * null = 现在没有卡。UI 只读；清空是引擎收到答案后自己做的。
     */
    private val _choice = mutableStateOf<AskUserRequest?>(null)
    val choice: State<AskUserRequest?> get() = _choice

    // ── 「这次学到」提案卡（第三十七批 B 段）─────────────────────────────
    //
    // 口径（用户 2026-10-19）：**收尾才出卡、只出卡不落盘、点采纳才写、拒绝一字不动**、
    // 同一次收尾只出一次；**被保护闸拦下的写尝试不算学到，不许进卡**——所以信号只在
    // GuardPipeline.Result.Done（真写成功）时记，Rejected/Failed 一律不记。
    private val learnSignals = mutableListOf<SkillRefine.LearnSignal>()
    private var learnShown = false
    private val _learn = mutableStateOf<List<SkillRefine.LearnProposal>?>(null)
    val learn: State<List<SkillRefine.LearnProposal>?> get() = _learn

    /** 出卡那一刻的「已有技能名」快照（C 段门禁判同名冲突用；纯卡片展示，不落盘）。 */
    private val _learnNames = mutableStateOf<Set<String>>(emptySet())
    val learnExistingNames: Set<String> get() = _learnNames.value

    /**
     * 用户点了「不记」⇒ **只清卡，一个字节都不写**（采纳才有落盘路径，且不在这里）。
     */
    fun dismissLearn() {
        _learn.value = null
    }

    /**
     * 收尾时才出卡：**没信号不出**（不许每轮都弹）、**同一次收尾只出一次**（[learnShown]）。
     *
     * 只生成卡片内容，**不落盘**——落盘是控制器收到「采纳」之后的事，走 `SkillStore.apply`
     * 那唯一一条写盘口（带备份、进索引、标草稿）。
     */
    private fun emitLearnCardIfDue() {
        if (!SkillRefine.shouldEmit(learnSignals, learnShown)) return
        learnShown = true
        // 先取一次已有技能名：既决定建议「草稿/转正式」，也是 C 段门禁判同名冲突的依据
        val names = existingSkillNames()
        _learnNames.value = names
        // 第五十七批 §1.3：被丢弃过的候选这轮直接跳过（读失败 ⇒ 空 set，不拦出卡）。
        // 只换 learnProposals 的入参，主循环语义一行没动。
        val rejected = runCatching {
            val s = com.example.zhengdao.baopu.BaopuSql.rejectedDecisionHashes()
            db.read { d ->
                d.rawQuery(s.sql, s.args).use { c ->
                    buildSet {
                        while (c.moveToNext()) c.getString(0)?.let { add(it) }
                    }
                }
            }
        }.getOrDefault(emptySet())
        _learn.value = SkillRefine.learnProposals(learnSignals, names, rejected)
    }

    /** 已有技能名（决定卡上建议「草稿」还是「转正式」）。 */
    private fun existingSkillNames(): Set<String> = runCatching {
        SkillRules.projectSkillDir(spillRoot()).listFiles()
            ?.filter { it.isDirectory }
            ?.map { it.name }
            ?.toSet() ?: emptySet()
    }.getOrDefault(emptySet())

    // ── 尾部被截断（第四十批）─────────────────────────────────────────────
    /**
     * 上一次回答是不是被服务端截在输出上限（`finish_reason=length` / Anthropic 的
     * `max_tokens`）。
     *
     * 为什么要单独一个状态：这种截断**不抛异常**，正文看起来完整，只有 finish_reason
     * 说了实话。壳层据此在输入栏上方给「继续说完」；不认它，用户只能自己发现「话说一半」。
     */
    private val _tailTruncated = mutableStateOf(false)
    val tailTruncated: androidx.compose.runtime.State<Boolean> get() = _tailTruncated

    /** 清掉截断标记（用户点了「继续说完」，或新开一轮）。 */
    fun clearTailTruncated() {
        _tailTruncated.value = false
    }

    private val choiceLock = Object()
    private var choiceResult: AskUserOption? = null

    /**
     * 弹卡并**阻塞**等用户点（`ask_user` 的执行体在工具线程上调它）。
     *
     * 为什么阻塞：选项是**同一步**里的决定，拆成两轮等于让模型带着半截上下文重来。
     * 阻塞有界（与确认卡同一超时），超时返回 null，由工具如实说「没人回答」。
     */
    fun askUser(req: AskUserRequest): AskUserOption? {
        synchronized(choiceLock) {
            _choice.value = req
            choiceResult = null
            val deadline = System.currentTimeMillis() + RunRules.CONFIRM_TIMEOUT_MS
            while (choiceResult == null && System.currentTimeMillis() < deadline) {
                runCatching { choiceLock.wait(RunRules.CONFIRM_POLL_MS) }
            }
            val picked = choiceResult
            _choice.value = null
            choiceResult = null
            return picked
        }
    }

    /** UI 点了一个选项（主线程调）。 */
    fun answerChoice(id: String) {
        synchronized(choiceLock) {
            choiceResult = _choice.value?.options?.firstOrNull { it.id == id }
            runCatching { choiceLock.notifyAll() }
        }
    }

    /** 用户点「停止」：半成品保留（写工具归刀 2；本刀只读，停止即止循环）。 */
    fun stop() {
        stopRequested = true
        // P2-5：取消正在飞的流式 Call。旧实现只置标志位 ⇒ 网络流照收，
        // 最长耗到 readTimeout（60s）才收工；用户看到的就是「点了停止还在写」。
        // 取消后 stream() 收到 IOException ⇒ 转 Failed，不会再回调成功。
        runCatching { activeCall?.cancel() }
        // 挂起的确认一并作废（不执行；循环头部随即收尾 ABORTED）
        confirmFlow.abandon()
        log("抱朴引擎：用户点停止（会话 ${_sessionKey.value}）")
    }

    /**
     * 一键续跑（进程被杀后重进）：先标「中断于第 N 步」，再从历史重建上下文继续。
     * @param interruptedStepValue 该会话落库的 interrupted_step（-1 = 其实没中断）
     */
    fun resume(sessionId: Long, interruptedStepValue: Int) {
        acquireOrNotice(sessionId) ?: return
        stopRequested = false
        // 续跑也要清：上一轮的截断提示不许跟着这一轮（第四十批）
        _tailTruncated.value = false
        runExecutor.execute {
            try {
                markInterrupted(sessionId, interruptedStepValue)
                runLoop(sessionId, userText = null, resumed = true)
            } catch (e: Exception) {
                log("抱朴引擎：续跑崩了 ${e.javaClass.simpleName}: ${e.message}")
                _state.value = RunState.ABORTED
                _notice.value = "引擎内部错误（已记录日志）：${e.javaClass.simpleName}"
            } finally {
                ActiveSessionGate.release("id:$sessionId")
                onEvent?.invoke(EngineEvent.Settled) // 门已释放 ⇒ UI 重算中断标记
            }
        }
    }

    /** 抢单活跃门；抢不到 ⇒ notice 说明原因并返回 null（调用方据此放弃）。 */
    private fun acquireOrNotice(sessionId: Long): Unit? {
        if (!ActiveSessionGate.tryAcquire("id:$sessionId")) {
            log("抱朴引擎：单活跃门占用（当前=，要=id:）")
            _notice.value = "已有另一个会话在跑（全局单活跃）：先等它结束或停止它"
            return null
        }
        _sessionKey.value = "id:$sessionId"
        return Unit
    }

    // ── 确认制对外动作（UI 线程调） ───────────────────────────────────────

    /**
     * 用户点批准/拒绝（派工单 §3.2 公开方法）。
     * callId 不匹配 = 陈旧点击 ⇒ 内核丢弃（J4）；匹配 ⇒ 挂起的等待苏醒。
     */
    fun resumeWithApproval(callId: String, approved: Boolean, note: String?) {
        confirmFlow.resumeWithApproval(callId, approved, note)
    }

    /** 确认等待期间App进后台：前台计时暂停（累计到现在的段落，回前台再续）。 */
    fun noteConfirmBackground() {
        val since = confirmResumeAt ?: return
        confirmForegroundMs += (System.currentTimeMillis() - since).coerceAtLeast(0L)
        confirmResumeAt = null
    }

    /** 确认等待期间App回前台：前台计时继续。 */
    fun noteConfirmForeground() {
        if (confirmResumeAt == null) confirmResumeAt = System.currentTimeMillis()
    }

    /**
     * 重启后批准落库的 pending（T3）：进程被杀过 ⇒ 内存里没有挂起，
     * 从库里恢复参数直接执行；执行完**把这一轮继续跑完**（同 [resume]——
     * 模型要看见工具结果才接着往下说，C 段回灌保证它看得见）。
     */
    fun approveRestoredPending(sessionId: Long) {
        acquireOrNotice(sessionId) ?: return
        stopRequested = false
        runExecutor.execute {
            try {
                val row = pendingStore.load(sessionId)
                if (row == null) {
                    _notice.value = "这条确认已经处理过了（没有待确认的动作）"
                    return@execute
                }
                val step = awaitConfirmStepOf(sessionId)
                _state.value = RunState.ACTING
                persistStep(sessionId, step, "Acting").get()
                val result = confirmFlow.restoredExecute(sessionId, row) { name, text ->
                    writeSpill(sessionId, name, text)
                }
                when (handleToolResult(sessionId, step, result, mutableListOf())) {
                    ToolResultFlow.CONTINUE, ToolResultFlow.TERMINATE_ROUND -> Unit
                    ToolResultFlow.STOP_RUN -> return@execute
                }
                // 继续这一轮（历史已含刚落的工具结果 ⇒ 模型接着往下说）
                runLoop(sessionId, userText = null, resumed = true)
            } catch (e: Exception) {
                log("抱朴引擎：恢复确认执行崩了 ${e.javaClass.simpleName}: ${e.message}")
                _state.value = RunState.ABORTED
                _notice.value = "引擎内部错误（已记录日志）：${e.javaClass.simpleName}"
            } finally {
                ActiveSessionGate.release("id:$sessionId")
                onEvent?.invoke(EngineEvent.Settled)
            }
        }
    }

    /**
     * 重启后拒绝落库的 pending：清 pending ＋ 回灌「用户拒绝」＋ 把这一轮继续
     * 跑完（与在线拒绝同口径：对话继续，模型知道这动作被拒了）。
     */
    fun rejectRestoredPending(sessionId: Long, summary: String) {
        acquireOrNotice(sessionId) ?: return
        runExecutor.execute {
            try {
                pendingStore.clear(sessionId)
                persistAssistantMessage(sessionId, RunRules.rejectNote(summary, null))
                runLoop(sessionId, userText = null, resumed = true)
            } catch (e: Exception) {
                log("抱朴引擎：恢复拒绝崩了 ${e.javaClass.simpleName}: ${e.message}")
                _state.value = RunState.ABORTED
                _notice.value = "引擎内部错误（已记录日志）：${e.javaClass.simpleName}"
            } finally {
                ActiveSessionGate.release("id:$sessionId")
                onEvent?.invoke(EngineEvent.Settled)
            }
        }
    }

    /** 新建会话（返回 sessionId；UI 在首次发送时调）。write 回 Future ⇒ .get() 等结果。 */
    fun createSession(title: String, now: Long): Long = db.write { d ->
        val stmt = BaopuSql.insertSession(title, provider.model, provider.id, now)
        d.beginTransaction()
        try {
            d.execSQL(stmt.sql, stmt.args)
            d.setTransactionSuccessful()
        } finally {
            d.endTransaction()
        }
        // execSQL 拿不到 insertId ⇒ 回头查（单写者线程内无并发，安全）
        d.rawQuery(
            "SELECT id FROM session WHERE created_at = ? ORDER BY id DESC LIMIT 1",
            arrayOf(now.toString()),
        ).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
    }.get()

    // ── 状态机主循环 ──────────────────────────────────────────────────────

    private fun runLoop(sessionId: Long, userText: String?, resumed: Boolean) {
        if (channelBroken) {
            _notice.value = "当前通道已熔断（连续失败）：换一个 provider 再试"
            _state.value = RunState.ABORTED
            return
        }
        // 工作计划（第十七批）：update_plan 落到哪个会话，由这里喂给 PlanController
        planSessionId = sessionId
        // 工具渐进披露（第五十九批 §1.4）：**同一处、同一个值**告诉注册表"这一轮是哪个会话"。
        // 为什么和 planSessionId 挤在一行下面：解锁状态按会话持久，而 capabilities 的
        // executor 拿不到 sessionId（它是工具层的 `JSONObject -> ToolOutcome`，签名不许改）⇒
        // 只能由引擎在每轮入口把身份喂进来，房型与 [planSessionId] 逐字一致（同一个 @Volatile
        // 落点、同一个 runLoop 入口）。下面步循环里每次组装 tools 数组时显式再传一次同一个
        // sessionId，读的是同一份「可见组 ∩ 用户开关」判据（[ToolRegistry.visibleSpecs]）——
        // 全仓只有那一处算 tools 数组，门禁 ProgressiveToolsTest 判据 G2 钉的就是那一处。
        registry.currentSessionId = sessionId
        // 用户明说「记住／记下来／以后都这样」⇒ 一条高置信信号（排卡时排最前）
        userText?.let { learnSignals += SkillRefine.learnMarkersIn(it) }
        val workspacePath = spillRoot().absolutePath
        val windowTokens = ContextAssembler.ContextBudgets.windowOf(provider)
        val persona = PersonaRules.assemble(
            BaopuPreset.personaText(workspacePath),
            personaFiles?.read(com.example.zhengdao.baopu.PersonaLayer.B_GLOBAL),
            personaFiles?.read(com.example.zhengdao.baopu.PersonaLayer.C_PROJECT),
        )
        val facts = HardFacts(workspacePath = workspacePath, model = provider.model)
        val recalled = memoryStore
            ?.let { MemoryRules.trimToBudget(it.loadAll(), ContextAssembler.RECALL_TOKEN_BUDGET) }
            ?.picked ?: emptyList()
        recalledCache = recalled
        // tools 数组**不在这里算**——它在下面步循环里、每次请求前算一次（第五十九批 §1.3）。
        // 为什么必须进循环：`capabilities` 解锁只改「模型能看见哪些组」，而解锁本身是一次
        // 工具调用——如果数组在循环外算死，模型这一整段（用户的一个回合）都拿不到刚解锁的
        // 工具，"按需解锁"就变成"解锁了但要等下一条用户消息才管用"，§7 那条
        // 「一轮里同时看到写 html 与玄览预览两张卡」也永远验不过。
        // 一个回合内数组通常只变 0–2 次（不解锁就一次都不变 ⇒ 前缀缓存不受扰动）。

        // 历史（续跑 = 重建上下文：人设 + 硬事实 + 最近 N 轮原文；C 段起含工具结果回灌）
        var history = loadHistory(sessionId).toMutableList()
        var stepCount = if (resumed) stepOf(sessionId) else 0

        // 第二十四批 C 段：本轮图片在**引擎线程**上读好一次，全程复用。
        // 续跑不带图（上一轮的图已经跟着那一轮发出去了，重发等于给模型看第二遍）。
        turnNotes = emptyList()
        turnImages = if (resumed) {
            emptyList()
        } else {
            val paths = pendingImageRelPaths
            pendingImageRelPaths = emptyList()
            if (paths.isEmpty()) {
                emptyList()
            } else {
                val loaded = runCatching { VisionFiles.load(spillRoot(), paths, shrink = shrinkImage) }
                    .onFailure { log("抱朴引擎：读图失败（本轮按纯文本走）${it.javaClass.simpleName}") }
                    .getOrNull()
                if (loaded == null) {
                    // 连 load 都炸了：那就**明说**这一轮没图，不留一句「用户附了 1 张图片」误导模型
                    turnNotes = listOf("（图片没读出来：本轮按纯文本处理，请让用户用文字说一遍图里内容）")
                    emptyList()
                } else {
                    if (loaded.shrunk.isNotEmpty()) {
                        log("抱朴引擎：${loaded.shrunk.size} 张图超限已缩图后发送")
                    }        // 逐张跳过的原因必须跟着本轮输入走——静默丢图正是 2026-10-18 的病
                    val note = loaded.note()
                    if (note.isNotEmpty()) {
                        log("抱朴引擎：${loaded.skipped.size} 张图没能发出去（原因已写进本轮输入）")
                        turnNotes = listOf(note)
                    }
                    loaded.payloads
                }
            }
        }
        if (userText != null) {
            // 新一轮 ⇒ 新计划：不继承上一轮的勾选状态（新任务不该因同名条目被钉 done）
            planController.newRound()
            persistUserMessage(sessionId, userText)
            history += HistoryMessage("local-user-${System.nanoTime()}", HistoryMessage.ROLE_USER, userText)
        }
        // 第四十八批 P0-3：这里**不许**再声明同名局部 `var compressionSummary`——
        // 局部会遮蔽字段：`restoreCompression()` 写的是字段，`runLoop` 读写的是局部 ⇒
        // 进程重启后摘要看不见、同进程第二轮又从 null 起，而 `compressedIds` 还在 ⇒
        // 组装时跳过被压消息却没有摘要替身，模型**永久丢掉那段上下文**。
        // 摘要的唯一存储是字段 `compressionSummary`；写入口只有两处：
        // `restoreCompression()`（重启回种）与 `runCompression()` 的返回值（压缩当场生成）。
        // 形状门禁：CompressionSummaryShadowGateTest（runLoop 内不得再出现同名局部 var）。

        // 收尾闸按「一次 run」重置：续跑是新 run，只验它自己写的东西
        verifyTouched.clear()
        verifyRepairs = 0
        // P1-2 / P1-3 的两个跨轮计数同样按 run 重置（续跑是新 run，不继承上一段的运气）
        badArgsRetries = 0
        reuseStep = false
        // 第三十八批 B 段：产物视图跟着 verifyTouched 一起清（上个 run 的文件名
        // 不许在这一轮的产物行里赖着不走）。
        clearRoundArtifactView()
        // 第八十七批 R1：**新 run ⇒ 新轨迹**。runId ＝ `r<sessionId>@<开跑时刻>`，
        // 回放时一眼看出是哪个会话、什么时候起的（续跑也是新 run，与 run_log 同口径）。
        trace.newRun(sessionId)

        // 工作计划（第十七批）：续跑＝同一轮的继续 ⇒ 计划态从库回种。进程被杀过
        // 内存是空的；不回种的话模型重发计划时 sticky done 失效，done 会被打回
        // doing（用户看到「打完的勾又亮了」）。
        if (resumed) planController.seedFromStore(sessionId)

        while (!stopRequested) {
            // ── Thinking 前：预算检查（第十五批口径：token 超触发线 **或** 可压旧
            //    消息 ≥ 60 条，任一满足 ⇒ 先 Compressing；都不满足 ⇒ 一定不压）──
            // 条数复用选区函数算（pendingOldMessageCount），并扣除已压进摘要的
            // 消息——否则压完条数线仍满足，每轮重压（C 段明令禁止的抖动）。
            val oldMessageCount = ContextAssembler.pendingOldMessageCount(history, compressedIds)
            val probe = assemble(
                persona, facts, history, "", windowTokens,
                compressionSummary, compressedIds, compressedIds,
                oldMessageCount = oldMessageCount,
            )
            if (probe.needsCompressing) {
                _state.value = RunState.COMPRESSING
                persistStep(sessionId, stepCount, "Compressing")
                // 双读数落一行：估算/触发线 ＋ 可压条数/条数线，T1 一眼看出是哪条触发
                log(
                    "抱朴引擎：压缩触发（估算 ${probe.estimatedTokens} tokens / 触发线 " +
                        "${ContextAssembler.compressionThreshold(windowTokens).toInt()}；" +
                        "可压旧消息 $oldMessageCount 条 / 线 ${RunRules.COMPRESS_OLD_MESSAGE_MIN}）" +
                        "进 Compressing",
                )
                compressionSummary = runCompression(sessionId, stepCount, history, provider, compressionSummary)
                    ?: compressionSummary
            }

            // ── 一步 = 一次模型轮次 ──
            // 安全点 1（第六十四批）：两步之间（stepCount 增长前）。用户点了
            // 「换通道」也只在这里生效 ⇒ 不打断当前步、不烧预算。
            drainChannelSwitch(SafePoint.StepBoundary)
            // P1-3：换通道后是**重跑同一步**（换了通道，模型的活没变）⇒ 不重复计预算。
            // 只在这一处跳计数：其他 continue（收尾闸重跑、坏参数重试）都是新的一步，照计。
            if (reuseStep) reuseStep = false else stepCount++
            _step.value = stepCount
            _state.value = RunState.THINKING
            persistStep(sessionId, stepCount, "Thinking")
            // 第三十八批 A 段：记开步时刻 ⇒ usage 到手时能说「这步花了多久」。
            // 口径写进界面文案（耗时 = 本步开步到模型读数到手），别当成整步含工具的墙钟时间。
            stepStartedAtMs = System.currentTimeMillis()
            // 第八十七批 R1：轨迹的开步时刻——与 usage 的 elapsedMs 同一个起点
            // （两处读数因此永远同源，不会出现「界面 3.2s、轨迹 2.1s」）。
            trace.beginStep(stepCount, stepStartedAtMs)

            val prompt = assemble(
                persona, facts, history, "", windowTokens,
                compressionSummary, compressedIds, compressedIds,
            )
            // 唯一出口产出的渲染：前缀（人设+硬事实+记忆）作 system，其余作 user
            // （0b 的 renderHistory 已把角色写进文本 ⇒ 拆两半不改变模型看到的内容）
            val rendered = prompt.render()
            val prefix = prompt.prefixText()
            val rest = rendered.removePrefix(prefix).removePrefix(AssembledPrompt.SEP)
            val messages = buildList {
                if (prefix.isNotBlank()) add(LlmMessage(LlmMessage.ROLE_SYSTEM, prefix))
                // 图片必须挂在这一条 user 上（第二十四批 A 段的本意）。
                // 2026-10-18 真机事故：这里从前只传文本，`prompt.images` 算出来就没人用 ⇒
                // 图一路缩好、选好、日志也写了「已发送」，请求体里却一个 image 块都没有，
                // 模型于是老实回「我看不到」。这是整条视觉链上唯一没接上的一根线。
                add(
                    LlmMessage(
                        role = LlmMessage.ROLE_USER,
                        content = rest.ifBlank { "（继续）" },
                        images = prompt.images,
                    ),
                )
            }

            // ── tools 数组：全仓**唯一**一处组装（第五十九批 §2 源码门禁 G2 的钉点）──
            // 判据只有一条链：`registry.toolsJsonXxx(sessionId)` 内部走
            // [ToolRegistry.visibleSpecs] = 可见组 ∩ 用户两种开关。这里不许出现任何 filter。
            // 放在步循环内 = 模型刚用 capabilities 解锁的组，**同一个回合的下一次请求**就看得见。
            val tools = when (provider.protocol) {
                com.example.zhengdao.baopu.LlmProtocol.OPENAI_COMPAT -> registry.toolsJsonOpenAi(sessionId)
                com.example.zhengdao.baopu.LlmProtocol.ANTHROPIC -> registry.toolsJsonAnthropic(sessionId)
            }

            // ── 流式一轮（内存累积，流结束一次性落库——判据 7）──
            when (val outcome = streamRound(sessionId, messages, tools)) {
                is RoundOutcome.Text -> {
                    // P1-1（第五十四批）：流式回答期间用户点了停止 ⇒ 状态与日志不许撒谎。
                    // 旧行为：这个分支一路走到 settle(DONE)——回答照出、run_log 记「正常完成」，
                    // 用户明明按了停止（`stopRequested` 只在循环头与工具批查，文本流是盲区）。
                    if (stopRequested) {
                        // token 真花了，账照记；但**不记 DONE**、不出学习卡、不提示计划未勾
                        persistUsage(sessionId, stepCount, outcome.usage)
                        // 第八十七批 R1：这一步是「被用户停掉」的，轨迹如实记 CANCELLED
                        // （记 OK 会让回放的人以为模型这一轮正常结束了）
                        trace.settleStep(stepCount, TraceStatus.CANCELLED, FailureKind.CANCELLED)
                        // 已收到的正文留下（用户不丢已经打出来的字），但不走收尾闸/学习卡
                        persistAssistantMessage(sessionId, outcome.text)
                        history += HistoryMessage(
                            "local-a-${System.nanoTime()}",
                            HistoryMessage.ROLE_ASSISTANT,
                            outcome.text,
                        )
                        _notice.value = "已停止（这轮的回答到这儿为止）"
                        log("抱朴引擎：流式期间用户点停止 ⇒ ABORTED（不记 DONE；已保留已收到的正文）")
                        settle(sessionId, stepCount, RunState.ABORTED, "Aborted")
                        return
                    }
                    // usage 接线（J13）：此刻该 step 的最新一行还是 Thinking 行
                    //（settle 的 Done 行还没落）⇒ 更到的就是它
                    persistUsage(sessionId, stepCount, outcome.usage)
                    // 第八十七批 R1：这一步的轨迹（模型正常答完；耗时/读数/状态一行收口）
                    trace.settleStep(stepCount, TraceStatus.OK)
                    // P1-2：正常回来一轮 ⇒ 坏参数计数归零（只算**连续**坏）
                    badArgsRetries = 0
                    // ── 收尾闸：模型说完了，机器先核对这一轮写过的产物 ──
                    // 过了 ⇒ 正常收尾；没过 ⇒ 给一次「按清单修」的机会，再不过就如实说没通过。
                    val findings = if (verifyTouched.isEmpty()) {
                        emptyList()
                    } else {
                        ArtifactVerifier.check(spillRoot(), verifyTouched)
                    }
                    if (findings.isNotEmpty() && verifyRepairs < RunRules.MAX_VERIFY_REPAIRS) {
                        verifyRepairs++
                        persistAssistantMessage(sessionId, outcome.text)
                        history += HistoryMessage(
                            "local-a-${System.nanoTime()}",
                            HistoryMessage.ROLE_ASSISTANT,
                            outcome.text,
                        )
                        val report = ArtifactVerifier.render(findings)
                        _notice.value = RunLoopRules.verifyRepairNotice(findings.size)
                        history += HistoryMessage(
                            "local-verify-${System.nanoTime()}",
                            HistoryMessage.ROLE_ASSISTANT,
                            DataFence.wrap("verify_output", System.currentTimeMillis(), report),
                        )
                        continue
                    }
                    if (findings.isNotEmpty()) {
                        // notice 的最终值在下方统一裁决（RunLoopRules.textSettleNotice）——
                        // 先落的日志保持原样：未过项摘要只进 run_log，文案以裁决结果为准
                        log("抱朴引擎：收尾闸未通过（已修过 ${verifyRepairs} 次），${findingBrief(findings)}")
                    }
                    // 2026-10-19：收尾时计划若还有没勾的条目，说一句实话（不许装成按计划走完了）。
                    // 覆盖顺序（改前源码的顺序，一级压一级）：截断 > 计划未勾 > 收尾闸摘要；
                    // 都不命中 ⇒ 不动 notice（保留本轮此前可能已贴的提示）。
                    RunLoopRules.textSettleNotice(
                        findingsCount = findings.size,
                        findingsSummary = if (findings.isEmpty()) null else ArtifactVerifier.summarize(findings),
                        unfinishedPlanCount = planController.unfinishedCount(),
                        truncated = outcome.truncated,
                    )?.let { _notice.value = it }

                    persistAssistantMessage(sessionId, outcome.text)
                    history += HistoryMessage(
                        "local-a-${System.nanoTime()}",
                        HistoryMessage.ROLE_ASSISTANT,
                        outcome.text,
                    )
                    // 第四十批：被输出上限截断 ⇒ 记下标记（壳层给「继续说完」）——
                    // 「看着写完了其实没写完」必须让用户看见（notice 文案在上面裁决）。
                    _tailTruncated.value = outcome.truncated
                    emitLearnCardIfDue()
                    settle(sessionId, stepCount, RunState.DONE, "Done")
                    return
                }

                is RoundOutcome.ToolCalls -> {
                    // usage 接线（J13；派工单 12.2 第 1 条）：**必须在本 step 的
                    // Acting 行插入之前**调——updateRunLogUsage 取该 (session, step)
                    // 最新一行做 UPDATE，Acting 行后落就更到它头上（时序硬约束）。
                    persistUsage(sessionId, stepCount, outcome.usage)
                    // 第八十七批 R1：模型这一轮**成功**了（要调工具是它的正常产出）——
                    // 后面的工具成败由 ACT 行单独记，不混进这一步的 THINK 行里。
                    trace.settleStep(stepCount, TraceStatus.OK)
                    // P1-2：正常回来一轮 ⇒ 坏参数计数归零（只算**连续**坏）
                    badArgsRetries = 0
                    if (outcome.text.isNotBlank()) {
                        persistAssistantMessage(sessionId, outcome.text)
                        history += HistoryMessage(
                            "local-a-${System.nanoTime()}",
                            HistoryMessage.ROLE_ASSISTANT,
                            outcome.text,
                        )
                    }
                    _state.value = RunState.ACTING
                    persistStep(sessionId, stepCount, "Acting")
                    var terminateRound = false
                    // ── 第九十二批：工具调度（读并行 ＋ 写单飞）──────────────────
                    // 先把这一批**排段**：连续的只读工具合成一段（并发上限见
                    // ToolScheduling.MAX_READ_PARALLEL），写工具各自独占一段。
                    // 段与段之间是硬屏障 ⇒「写与写不并行」「写之后的读一定读到新值」
                    // 是**结构**保证（不靠谁记得加锁）。排段是纯函数，JVM 直测。
                    val stages = ToolScheduling.plan(outcome.calls) { call -> laneOfCall(call) }
                    // 整批总时限：各段预算之和。为什么不拍一个固定秒数——单工具上限量级差很大
                    // （文件 5s / 浏览器 20s / ask_user 等用户 15 分钟），拍死一个值要么掐死
                    // 正经的等用户，要么形同虚设；按段求和在任何合法批次上都够用，又给
                    // 「线程卡死不返回」留了上界。
                    val batchDeadlineMs = System.currentTimeMillis() +
                        ToolScheduling.batchBudgetMs(stages) { call -> timeoutOfCall(call) }
                    for (stage in stages) {
                        val headCall = when (stage) {
                            is ToolStage.Serial -> stage.call
                            is ToolStage.Parallel -> stage.calls.first()
                        }
                        if (stopRequested) {
                            // 第八十七批 R1：这次工具调用**没执行**（批里前面那个还在跑就停了）
                            abortBeforeCall(sessionId, stepCount, headCall)
                            return
                        }
                        when (stage) {
                            is ToolStage.Serial ->
                                when (handleSerialCall(sessionId, stepCount, stage.call, history)) {
                                    ToolResultFlow.CONTINUE -> Unit
                                    ToolResultFlow.TERMINATE_ROUND -> terminateRound = true
                                    ToolResultFlow.STOP_RUN -> return
                                }

                            is ToolStage.Parallel ->
                                when (runParallelStage(sessionId, stepCount, stage, history, batchDeadlineMs)) {
                                    ToolResultFlow.CONTINUE -> Unit
                                    ToolResultFlow.TERMINATE_ROUND -> terminateRound = true
                                    ToolResultFlow.STOP_RUN -> return
                                }
                        }
                        if (terminateRound) break
                    }
                }

                is RoundOutcome.BadArgs -> {
                    // P1-2（第五十四批）：坏参数**不是通道坏**。
                    // 旧行为把它塞进 `Failed` ⇒ `consecutiveChannelFailures++`、可能切通道/熔断：
                    // 模型一次引号写错，整轮被判成「网络坏了」，三次还真熔断。
                    persistUsage(sessionId, stepCount, outcome.usage)
                    if (outcome.text.isNotBlank()) {
                        persistAssistantMessage(sessionId, outcome.text)
                        history += HistoryMessage(
                            "local-a-${System.nanoTime()}",
                            HistoryMessage.ROLE_ASSISTANT,
                            outcome.text,
                        )
                    }
                    val retry = RunRules.shouldRetryBadArgs(badArgsRetries)
                    // 第八十七批 R1/R2：坏参数这一步的轨迹（失败分类 ＋ 策略结论都留痕）。
                    // 判据仍走 [RunRules.shouldRetryBadArgs]（它已是策略表的适配器，两者不可能打架）。
                    trace.settleStep(
                        stepCount,
                        TraceStatus.FAILED,
                        FailureKind.BAD_JSON,
                        if (retry) FailureAction.Retry(0L, badArgsRetries + 1) else FailureAction.Abort,
                    )
                    // 第一百一十五批：Schema 错额外给一条归因/建议（只生成，不执行）。
                    // 语法层（JSON 都坏了）没有结构化的参数证据，不硬猜，沿用原文回灌。
                    val repair = if (outcome.kind == ToolCallCodec.BadArgsKind.SCHEMA) {
                        ToolFailureAdvisor.advise(
                            ToolFailureEvidence(
                                toolName = outcome.toolName,
                                errorText = outcome.error,
                                arguments = outcome.arguments,
                                failureKind = FailureKind.BAD_JSON,
                            ),
                        ).also { log(ToolFailureAdvisor.auditLine(it, outcome.error)) }
                    } else {
                        null
                    }
                    // 错误原文**回灌进历史**：只说「参数不对」模型改不对，得让它看见自己错在哪。
                    // 第九十五批：前缀按**坏在哪**分两说（JSON 语法 / Schema）——原文逐字不动。
                    val feed = RunRules.badArgsFeed(outcome.kind, outcome.error) +
                        (repair?.let { "\n${it.feedbackSuffix()}" } ?: "")
                    history += HistoryMessage(
                        "local-badargs-${System.nanoTime()}",
                        HistoryMessage.ROLE_ASSISTANT,
                        feed,
                    )
                    persistAssistantMessage(sessionId, feed)
                    log("抱朴引擎：坏参数 ${outcome.kind.name}（不记通道失败、不切通道）：${outcome.error.take(200)}")
                    if (!retry) {
                        _notice.value = "模型连续给的参数都不合法，这轮停下来（错误已记日志）"
                        settle(sessionId, stepCount, RunState.ABORTED, "Aborted")
                        return
                    }
                    badArgsRetries++
                    _notice.value = RunLoopRules.badArgsRetryNotice(badArgsRetries)
                    continue
                }

                is RoundOutcome.Failed -> {
                    consecutiveChannelFailures++
                    // 原文只进 run_log.err（≤300 字）；界面拿的仍是 humanize 后的人话。
                    val errText = outcome.raw.take(300)
                    // 换够次数就把候选关掉（防「两个坏通道来回切」的死循环，见 MAX_CHANNEL_SWITCHES）
                    val fallback = if (channelSwitches < RunRules.MAX_CHANNEL_SWITCHES) {
                        channelFallback(provider)
                    } else {
                        null
                    }
                    // ── 第八十七批 R2：先问**统一失败策略**（本批唯一新增的一层）──
                    // 旧口径：失败 ⇒ 直接进熔断三件套（没到熔断线就整轮停）。
                    // 新口径：先按分类问策略要不要「退避重试同一步」；不要重试才落到下面那套
                    // 既有出口（换通道/问人/停）——**没有第二套状态机**，仍然只有这一个循环。
                    // 分类只看原文里的状态码/关键词，不看正文（`classifyModel` 是纯函数）。
                    val failureKind = FailurePolicy.classifyModel(outcome.raw.ifEmpty { outcome.userFacing })
                    val action = FailurePolicy.decide(
                        kind = failureKind,
                        attempt = consecutiveChannelFailures,
                        fallbackAvailable = fallback != null,
                    )
                    // 第八十七批 R1：这一步失败点 ＋ 策略结论都进轨迹（回放时一眼看出当时判了什么）
                    trace.settleStep(stepCount, TraceStatus.FAILED, failureKind, action)
                    if (action is FailureAction.Retry) {
                        // 有界重试：延迟退避后**重跑同一步**（步数不涨，与换通道同一个 reuseStep 口子）。
                        // 为什么敢在这里睡：循环跑在 zd-baopu-run 这颗专用线程上（它本来就在等网络），
                        // 睡 500ms 不挡 UI；重试预算由策略表封顶（MAX_RETRY_ATTEMPTS），不会睡成死循环。
                        log(
                            "抱朴引擎：失败判为 ${failureKind.name}，${action.delayMs}ms 后重试同一步" +
                                "（第 ${action.attempt} 次失败；通道 ${provider.id}/${provider.model}）",
                        )
                        _notice.value = "这条通道刚才没通，${action.delayMs}ms 后重试同一步（第 ${action.attempt} 次）"
                        if (action.delayMs > 0) runCatching { Thread.sleep(action.delayMs) }
                        reuseStep = true
                        continue
                    }
                    when (val d = RunRules.channelDecision(action, fallback)) {
                        is RunRules.ChannelDecision.SwitchTo -> {
                            val next = d.next
                            channelSwitches++
                            consecutiveChannelFailures = 0
                            log(
                                "抱朴引擎：通道 ${provider.id}/${provider.model} 熔断，自动切到 " +
                                    "${next.id}/${next.model}（第 $channelSwitches 次）",
                            )
                            _notice.value = "当前通道不通，已自动切到 ${next.model} 重试"
                            provider = next
                            persistStep(sessionId, stepCount, "Switched", err = errText)
                            // P1-3：标记「这一步重跑」⇒ 循环头不再 stepCount++。
                            // 旧行为：continue 回循环头照涨一步，每次换通道白烧一步预算。
                            reuseStep = true
                            continue // 重跑同一步，不涨步数
                        }

                        RunRules.ChannelDecision.BrokenAskUser -> {
                            channelBroken = true
                            // 两个分支的文案判据（换过次数到顶 ⇄ 连续失败）搬去纯判据（第七十九批）
                            val msg = RunLoopRules.channelBrokenNotice(channelSwitches, consecutiveChannelFailures, stepCount)
                            _notice.value = msg
                            settle(sessionId, stepCount, RunState.ABORTED, "Aborted", err = errText)
                            onEvent?.invoke(EngineEvent.AskUser(msg))
                            return
                        }

                        RunRules.ChannelDecision.KeepCurrent -> {
                            // 还没到熔断线（重试预算也已用尽，见上面的策略判定）：本轮失败即停，
                            // 不硬试（0b 口径）；退避重试那一层是第八十七批新加的，走到这里说明它不同意。
                            _notice.value = outcome.userFacing
                            settle(sessionId, stepCount, RunState.ABORTED, "Aborted", err = errText)
                            onEvent?.invoke(EngineEvent.AskUser(_notice.value!!))
                            return
                        }
                    }
                }
            }

            // ── 步数刹车：RunRules.MAX_STEPS 步必停（现 120） ──
            if (RunRules.isStepExhausted(stepCount)) {
                val msg = "已到 ${RunRules.MAX_STEPS} 步上限，停下。继续说「继续」会重新计步。"
                _notice.value = msg
                emitLearnCardIfDue()
                settle(sessionId, stepCount, RunState.DONE, "Done")
                onEvent?.invoke(EngineEvent.AskUser(msg))
                return
            }
        }
        // 用户停止
        settle(sessionId, stepCount, RunState.ABORTED, "Aborted")
    }

    /** 组装（薄封装：调 0b 的唯一出口；本引擎不私拼——判据 1）。 */
    private fun assemble(
        persona: List<com.example.zhengdao.baopu.PersonaSegment>,
        facts: HardFacts,
        history: List<HistoryMessage>,
        current: String,
        windowTokens: Int,
        compressionSummary: String?,
        compressedIds: Set<String> = emptySet(),
        skipIds: Set<String> = emptySet(),
        oldMessageCount: Int = 0,
        /**
         * 本轮要随请求发出去的图片（第二十四批 C 段）。
         *
         * 默认取 [turnImages]：本轮开跑那一刻读好的一批。**每一步都发同一批**是刻意的——
         * 用户这一轮的内容里就包含那几张图，工具循环的后续步骤仍在回答同一个请求；
         * 省 token 的正确做法是 `VisionRules` 的 4 张/6 MB 上限，不是「第二轮就不给看」。
         */
        images: List<com.example.zhengdao.baopu.VisionRules.ImagePayload> = turnImages,
        /** 本轮额外说明（图没发出去的原因等）；默认取 [turnNotes]。 */
        extraNotes: List<String> = turnNotes,
    ) = ContextAssembler.assemble(
        persona = persona,
        facts = facts,
        stickyMemories = stickyMemories,
        newMemories = recalledCache,
        history = history,
        current = current,
        windowTokens = windowTokens,
        compressionSummary = compressionSummary,
        compressedIds = compressedIds,
        skipIds = skipIds,
        oldMessageCount = oldMessageCount,
        // 2026-10-19 最小版项目记忆：工作区根 `项目记忆.md`，每轮开工自动读（见 ProjectMemory）
        projectMemory = com.example.zhengdao.baopu.ProjectMemory.load(spillRoot()),
        images = images,
        extraNotes = extraNotes,
        // V5：视觉能力只许来自 `Provider.vision`，组装层不许自己猜模型名
        visionSupported = provider.vision,
    )

    /**
     * 本轮已读好的图片（第二十四批 C 段）。
     *
     * 为什么是字段而不是参数：`assemble` 在工具循环里被调用多次，参数一路传下去要改十几处
     * 调用点（那是本批不想要的风险）；字段只有「本轮开跑时赋值、本轮结束时清空」两个触点。
     */
    private var turnImages: List<com.example.zhengdao.baopu.VisionRules.ImagePayload> = emptyList()

    /**
     * 本轮要拼进输入末尾的说明（第二十四批 C 段追加）。
     *
     * 目前只有一个来源：图片没发出去的逐张原因（[com.example.zhengdao.baopu.VisionFiles.Loaded.note]）。
     * 与 [turnImages] 同寿命：本轮开跑时赋值、本轮结束时清空——否则上一轮丢图的说明会跟着
     * 下一轮的话重复出现，模型会以为用户这次也发了图。
     */
    private var turnNotes: List<String> = emptyList()

    /** 本轮附件里图片的**相对路径**（[send] 写入，[runLoop] 开跑时消费并清空）。 */
    private var pendingImageRelPaths: List<String> = emptyList()

    /** 本会话已注入的记忆（只增不改——0b 的 sticky 语义；候选确认后才进这里）。 */
    private val stickyMemories = mutableListOf<com.example.zhengdao.baopu.MemoryEntry>()

    /** 本轮召回到的记忆（循环内不变 ⇒ 每轮前缀一致，缓存友好）。 */
    private var recalledCache: List<com.example.zhengdao.baopu.MemoryEntry> = emptyList()

    // ── 流式一轮 ──────────────────────────────────────────────────────────

    private sealed interface RoundOutcome {
        /**
         * @param truncated 服务端把回复截在了输出上限（`finish_reason=length` /
         *   Anthropic 的 `max_tokens`）。**"看起来写完了其实没写完"必须让用户看见**，
         *   所以这里单独带一个标记，壳层据此给「继续说完」按钮（第四十批）。
         */
        data class Text(
            val text: String,
            val usage: LlmUsage? = null,
            val truncated: Boolean = false,
        ) : RoundOutcome
        data class ToolCalls(
            val text: String,
            val calls: List<ToolCall>,
            val usage: LlmUsage? = null,
        ) : RoundOutcome

        /**
         * 坏参数：模型这轮的 tool_calls 参数不是合法 JSON / 过不了 Schema（P1-2）。
         *
         * **刻意不与 [Failed] 合用**：`Failed` 的语义是「通道/网络坏了」——它会让
         * `consecutiveChannelFailures++`、触发换通道与熔断。模型写错一次引号不该
         * 把整轮判成网络故障，所以单开一类，走「回灌错误原文 + 重试一次」的路。
         *
         * @param error 错误原文（回灌给模型看的那句；用户侧只看到人话 notice）
         * @param text 同一轮里已经收到的正文（有就留下，别把打了一半的字丢了）
         * @param kind 坏在哪：JSON 语法 / Schema（第九十五批；回灌前缀按它二选一）
         */
        data class BadArgs(
            val error: String,
            val text: String = "",
            val usage: LlmUsage? = null,
            val kind: ToolCallCodec.BadArgsKind = ToolCallCodec.BadArgsKind.JSON_SYNTAX,
            /** 出错的那个调用（第一百一十五批：Schema 错时给归因/建议用；语法层为空）。 */
            val toolName: String = "",
            val arguments: org.json.JSONObject? = null,
        ) : RoundOutcome

        /**
         * @param userFacing 给人看的人话（UI 只显示这个）
         * @param raw 服务端原文（只落 `run_log.err`；空串 = 没有原文可取）
         */
        data class Failed(val userFacing: String, val raw: String = "") : RoundOutcome
    }

    /**
     * 跑一轮流式调用。
     * 内存累积（文本 + tool_calls + usage 都在 LlmClient 内累积），流结束一次性给结果；
     * 流中断该轮作废（[RoundOutcome.Failed]——"看着成功其实没有"不接受）。
     */
    private fun streamRound(sessionId: Long, messages: List<LlmMessage>, tools: org.json.JSONArray): RoundOutcome {
        val textBuf = StringBuilder()
        val latch = CountDownLatch(1)
        var failure: String? = null
        var failureRaw: String? = null
        var rawCalls: List<com.example.zhengdao.baopu.RawToolCall> = emptyList()
        var finishedUsage: LlmUsage? = null
        // 第四十批：服务端把它截在输出上限时，finish_reason 会说 length（OpenAI 系）/
        // max_tokens（Anthropic 系）。不认这个的话，"半截回复"会被当成正常写完存下来。
        var stopReason: String? = null
        val client = LlmClient(provider)
        // P2-5（第五十六批）：句柄存进字段 ⇒ stop() 能立刻 cancel。旧实现本地 Call
        // 用完即弃，用户点停止后最多要干等 readTimeout（60s）。
        val call = client.streamAsync(messages, tools) { ev ->
            when (ev) {
                is LlmEvent.Delta -> textBuf.append(ev.text)
                is LlmEvent.Finished -> {
                    rawCalls = ev.toolCalls
                    finishedUsage = ev.usage
                    stopReason = ev.reason
                }
                is LlmEvent.Failed -> {
                    failure = ev.error.message ?: ev.error.javaClass.simpleName
                    failureRaw = ev.raw
                }
            }
            if (ev is LlmEvent.Finished || ev is LlmEvent.Failed) latch.countDown()
        }
        activeCall = call
        // P2-5：等也要有上限。旧实现 `latch.await()` 无参 ⇒ 通道只连上不收数据时
        // 这里能挂死整个 run 线程（唯一一条引擎线程，后面全排队）。
        val arrived = latch.await(RunRules.STREAM_WAIT_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        activeCall = null
        if (!arrived) {
            // 超时按失败处理，并把 Call 砍掉（不砍的话底层连接还占着）
            runCatching { call.cancel() }
            log("抱朴引擎：等模型流超过 ${RunRules.STREAM_WAIT_TIMEOUT_MS / 1000}s，本轮作废")
            return RoundOutcome.Failed("等模型回复超时（${RunRules.STREAM_WAIT_TIMEOUT_MS / 1000}s）：这条通道只连上不收数据")
        }
        // 原文取不到时退回异常 message（至少 err 列不为空，能看出是哪一类失败）
        failure?.let { return RoundOutcome.Failed(RunLoopRules.humanizeNetFailure(it), failureRaw ?: it) }
        if (rawCalls.isNotEmpty()) {
            val decoded = ToolCallCodec.decode(rawCalls, registry)
            return when (decoded) {
                is ToolCallCodec.DecodeResult.Ok -> RoundOutcome.ToolCalls(textBuf.toString(), decoded.calls, finishedUsage)
                // 坏 JSON/坏参数：**不**走 Failed 通道（P1-2）。错误原文已在 decode 里备好，
                // 交给引擎回灌给模型看 + 重试一次；再坏才如实收尾。
                is ToolCallCodec.DecodeResult.Bad -> RoundOutcome.BadArgs(
                    decoded.error,
                    textBuf.toString(),
                    finishedUsage,
                    decoded.kind,
                    decoded.toolName,
                    decoded.arguments,
                )
            }
        }
        val truncated = RunLoopRules.isTruncatedStopReason(stopReason)
        return RoundOutcome.Text(textBuf.toString(), finishedUsage, truncated)
    }

    /** 工具调用可见卡的摘要（入参出参各取一段；全量在 db 的 tool part 里）。 */
    private fun toolSummary(call: ToolCall, wrapped: String): String = buildString {
        append(call.name).append("（")
        append(call.arguments.toString().take(120))
        append("）→ ")
        append(wrapped.take(160))
    }

    // ── 落库（每步一次事务） ──────────────────────────────────────────────

    /**
     * 收尾一步：**先落库、再切状态**（顺序不能反）。
     *
     * 为什么：UI 收到收尾事件时会重算「中断于第 N 步」，判据是 `run_log` 的最后一条
     * （见 `BaopuRuntime.interruptedStep`）。若先把状态切成 DONE/ABORTED、再去落
     * `Done`/`Aborted` 行，中间那一瞬 UI 会看到「状态已结束、最后一条还是 Acting」
     * ⇒ 把一轮**正常完成**显示成「上次中断于第 N 步（进程当时被杀了）」。
     * 2026-10-17 真机实测踩到（一轮 3 步正常完成后显示「中断于第 3 步」）。
     *
     * 反过来被杀时走不到这里 ⇒ `run_log` 最后一条停在 Thinking/Acting ⇒ 判为中断（判据 6）。
     */
    /** 收尾闸未通过时的 run_log 摘要（只写路径＋检查项名，绝不写文件内容）。 */
    private fun findingBrief(findings: List<Finding>): String =
        findings.take(10).joinToString("；") { f -> "${f.path}(${ArtifactVerifier.label(f.check)})" }
    /**
     * 收尾：把抱朴自己造的 `临时/` 垃圾**移进回收站**（2026-10-20 用户：「垃圾就让他自己弄到回收站」）。
     *
     * 只搬进回收站、不真删 ⇒ 可反悔；用户不需要为它点确认（那是它自己的草稿区）。
     */
    private fun reapScratch() {
        runCatching {
            val n = com.example.zhengdao.baopu.TempScratch.reap(spillRoot(), System.currentTimeMillis())
            if (n > 0) onEvent?.invoke(EngineEvent.ScratchLeft(n))
        }.onFailure { log("抱朴引擎：临时目录回收失败 ${it.javaClass.simpleName}") }
    }
    // ── 第六十四批：资源机的三个入口/效应（状态机本体在 ChannelSwitchFsm.kt）──

    /**
     * UI 入口：请求切换通道。任何时刻可调（含任务在跑）——只置 PENDING，
     * **到安全点才生效，不打断当前步**。返回给用户看的一句话。
     *
     * @param allowDropImages 用户在「去图再切 / 留在原通道」里显式选了前者才 true
     *   （派工单 §3 三选一：默认拒切，不静默剥图；历史消息一个字节不动）。
     */
    fun requestChannelSwitch(target: Provider, allowDropImages: Boolean = false): String {
        val msg = channelSwitch.requestSwitch(provider, target, allowDropImages)
        _channelSwitchStatus.value = channelSwitch.snapshot()
        log("资源机：请求切换 ⇒ ${target.id}（${msg}）")
        return msg
    }

    /**
     * 切换效应（引擎内部装配）：写只经注入的 [switchChannel]（Prefs 唯一真相），
     * 解析漂移/异常由装配层回滚；本方法只做「同一个通道别切」的前判与翻译。
     */
    private fun applyChannelSwitch(target: Provider): SwitchApplyOutcome {
        val oldId = provider.id
        if (target.id == oldId) {
            return SwitchApplyOutcome.Failed("目标通道就是当前通道（$oldId）")
        }
        val p = switchChannel(oldId, target.id)
            ?: return SwitchApplyOutcome.Failed("没切过去（已留在 $oldId）")
        return SwitchApplyOutcome.Applied(p)
    }

    /**
     * 安全点抽干：三个安全点（两步之间 / 出确认卡之前 / 本轮 Settled 之后）
     * 共用这一处。只有 PENDING 才真落切换；成功才改 [provider] 字段并提示，
     * 失败只提示（状态机留 FALLBACK ＋ lastResult，已留在原通道）。
     */
    private fun drainChannelSwitch(point: SafePoint) {
        val o = channelSwitch.atSafePoint(point)
        _channelSwitchStatus.value = channelSwitch.snapshot()
        if (o.switched && o.provider != null) {
            provider = o.provider
            _notice.value = "已切换到 ${o.provider.label}（${o.provider.model}）"
            log("资源机：安全点 ${point.name} 完成 ⇒ ${o.provider.id}")
        } else if (!o.switched && o.message != null) {
            _notice.value = o.message
            log("资源机：安全点 ${point.name} 未切成（${o.message}）")
        }
    }

    private fun settle(sessionId: Long, step: Int, state: RunState, runLogState: String, err: String = "") {
        // **必须等落库真的完成**：db.write 返回的是 Future（单写者线程 submit 排队），
        // 只调用不 get 就等于"先切状态、收尾行稍后才落"。2026-10-17 真机复现：
        // 一轮 3 步正常完成后显示「上次中断于第 3 步」——就是这一瞬被 UI 读到了。
        runCatching { persistStep(sessionId, step, runLogState, err).get() }
            .onFailure { log("抱朴引擎：收尾落库失败 ${it.javaClass.simpleName}: ${it.message}") }
        _state.value = state
        // 安全点 3（第六十四批）：本轮 Settled 之后——收尾后立刻切，不等下一轮。
        drainChannelSwitch(SafePoint.AfterSettled)
    }

    /** 步迁移 = 一次事务（session.interrupted_step + updated_at + run_log）。返回落库 Future。 */
    private fun persistStep(
        sessionId: Long,
        step: Int,
        state: String,
        err: String = "",
    ): java.util.concurrent.Future<Unit> {
        val rl = BaopuSql.insertRunLog(
            sessionId = sessionId,
            step = step,
            state = state,
            model = provider.model,
            tokensIn = 0,
            tokensOut = 0,
            tokensInEst = 0,
            tokensOutEst = 0,
            tool = "",
            // 有原文 = 这次失败（ok=0）；收尾成功行 ok=1 ⇒ 运维侧能直接按 ok 过滤失败
            ok = err.isEmpty(),
            latencyMs = 0,
            err = err,
            createdAt = System.currentTimeMillis(),
        )
        return db.write { d ->
            d.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                d.execSQL(
                    "UPDATE session SET updated_at = ?, interrupted_step = ? WHERE id = ?",
                    arrayOf<Any?>(now.toString(), step.toString(), sessionId.toString()),
                )
                d.execSQL(rl.sql, rl.args)
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
    }

    // ── 确认制落库（刀 3 A 段；与 run_log 同事务） ────────────────────────

    /**
     * 进 AWAIT_CONFIRM：**一个事务**里写 run_log 的 AwaitConfirm 行 ＋ session 的
     * pending 三列 ＋ interrupted_step（派工单 §3.3「同事务写」）。
     *
     * 为什么必须同事务：分成两次写，进程死在中间就会留下「run_log 说在等确认、
     * 却没有 pending 行」的半边状态 ⇒ 重启后卡丢了，那一轮永远等不到人。
     *
     * 先落库、再切状态（[settle] 的同一条纪律：反了会让 UI 闪到旧状态）。
     */
    private fun enterAwaitConfirm(sessionId: Long, step: Int, tool: String, argsJson: String, at: Long) {
        val rl = BaopuSql.insertRunLog(
            sessionId = sessionId,
            step = step,
            state = "AwaitConfirm",
            model = provider.model,
            tokensIn = 0,
            tokensOut = 0,
            tokensInEst = 0,
            tokensOutEst = 0,
            tool = tool,
            ok = true,
            latencyMs = 0,
            err = "",
            createdAt = at,
        )
        val pend = BaopuSql.savePending(sessionId, tool, argsJson, at)
        runCatching {
            db.write { d ->
                d.beginTransaction()
                try {
                    d.execSQL(
                        "UPDATE session SET updated_at = ?, interrupted_step = ?, " +
                            "pending_tool = ?, pending_args = ?, pending_at = ? WHERE id = ?",
                        arrayOf<Any?>(
                            at.toString(), step.toString(), tool, argsJson, at.toString(),
                            sessionId.toString(),
                        ),
                    )
                    d.execSQL(rl.sql, rl.args)
                    d.setTransactionSuccessful()
                } finally {
                    d.endTransaction()
                }
            }.get() // 等真落库（与 settle 同纪律：Future 不 get 等于没写）
        }.onFailure { log("抱朴引擎：AwaitConfirm 落库失败 ${it.javaClass.simpleName}: ${it.message}") }
        _state.value = RunState.AWAIT_CONFIRM
    }

    /** 那张卡原本挂在第几步（恢复执行时接着这一步记；没有 AwaitConfirm 行 ⇒ 0）。 */
    private fun awaitConfirmStepOf(sessionId: Long): Int = db.read { d ->
        d.rawQuery(
            "SELECT COALESCE(MAX(step), 0) FROM run_log WHERE session_id = ? AND state = 'AwaitConfirm'",
            arrayOf(sessionId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }
    }

    /**
     * 把一轮的用量读数更新进 run_log（J13；派工单 12.2 第 1 条，B 段交接项）。
     *
     * **时序硬约束**：必须在本 step 的 Acting 行插入**之前**调——
     * [BaopuSql.updateRunLogUsage] 取该 (session, step) 最新一行做 UPDATE。
     * usage = null（服务端没给）⇒ 直接跳过：不写半行空话（「没测到不编数」），
     * 也**不会**往 `tokens_in/out` 这些 NOT NULL 列绑 null（SQL 层只 SET 有读数的列）。
     */
    private fun persistUsage(sessionId: Long, step: Int, usage: LlmUsage?) {
        // 第八十七批 R1：读数先记进轨迹的内存态（这一步稍后 settle 时一起落行）。
        // 放在两道 `?: return` **之前**：没读到也要让「这一步用了多少」有个明确的 0，
        // 而不是等 settle 时才发现压根没记（那样只能编数）。
        trace.noteUsage(step, usage)
        val readings = usage ?: return
        val stmt = BaopuSql.updateRunLogUsage(sessionId, step, readings) ?: return
        runCatching { db.write { d -> d.execSQL(stmt.sql, stmt.args) }.get() }
            .onFailure { log("抱朴引擎：usage 落库失败 ${it.javaClass.simpleName}: ${it.message}") }
        // 第三十八批 A 段：同一处把这步的读数透给壳层（派工单 §1.1「别另起一条链」）。
        // 两道 `?: return` 已经把「没读到」的都在上面拦掉了：usage 为 null、或五列
        // 全空 ⇒ stmt 为 null ⇒ **不回调**（判据 A3 的「没有读数就不加这一条」，
        // 引擎侧才是源头）。elapsedMs 的口径见 [stepStartedAtMs]；负数 = 未知。
        onUsage?.invoke(
            BaopuUsageStep(
                step = step,
                usage = readings,
                elapsedMs = if (stepStartedAtMs == 0L) -1L else System.currentTimeMillis() - stepStartedAtMs,
            ),
        )
    }

    // ── 工具结果处理（在线路径与批准路径共用：回灌、历史、可见卡同形） ──────

    /** 一次工具结果的流向（调用方据此决定：继续 / 终止本轮 / 整轮停下）。 */
    private enum class ToolResultFlow { CONTINUE, TERMINATE_ROUND, STOP_RUN }

    /**
     * 处理一个工具执行结果（在线直行 与 批准后执行 共用同一函数 ⇒ 两条路径
     * 回灌给模型的东西**逐字节同形**——模型对不上账的根源就是两处写法漂移）。
     *
     * @param history 调用方的在途历史（就地追加；恢复路径传一次性空表）
     * @return 见 [ToolResultFlow]
     */
    private fun handleToolResult(
        sessionId: Long,
        step: Int,
        result: GuardPipeline.Result,
        history: MutableList<HistoryMessage>,
    ): ToolResultFlow = when (result) {
        is GuardPipeline.Result.Done -> {
            consecutiveToolFailures = 0
            // 收尾闸记账：只记真写成功的调用（失败/被拒不算产物）
            val touchedPaths = VerifyTargets.pathsOf(result.call.name, result.call.arguments)
            touchedPaths.forEach { verifyTouched += it }
            // 第三十八批 B 段：产物行就记账在这里——与收尾闸同一个源头（只有真写
            // 成功的才进来），失败走 Failed、被闸拦下走 Rejected，两个都不到这一行。
            publishRoundArtifacts()
            // 第三十八批 §9.1：抽屉「产物区」那本台账也记在这一行——与产物行同一个源头
            // （只有真写成功的才走到这里：失败走 Failed、被闸拦下走 Rejected，两条都不到
            // 这一行），所以判据 C2「写失败/被拒不记」靠的是**分支归属**，不是额外判断。
            persistArtifactLedger(sessionId, touchedPaths)
            // 只有**真写成功**的调用才算「学到」：被保护闸拦下的走 Rejected 分支、失败的走
            // Failed 分支，两个都不记信号 ⇒ 进不了「这次学到」卡（用户 2026-10-19 口径）。
            // 信号内容怎么取由 [SkillRefine.learnDetailFor] 一处决定（第一百零七批·EVO-D2）：
            // 技能类拿不到路径时用**技能名**，连名字都没有就不出信号——不许拿工具名兜底，
            // 否则会推出 `write-skill` 这种没有场景的垃圾技能。
            SkillRefine.signalKindFor(result.call.name)?.let { kind ->
                val skillName = result.call.arguments.optString("name")
                SkillRefine.learnDetailFor(kind, result.call.name, touchedPaths, skillName)?.let { detail ->
                    learnSignals += SkillRefine.LearnSignal(kind, detail)
                }
            }
            persistToolExchange(sessionId, step, result.call.name, result.wrapped)
            onEvent?.invoke(
                EngineEvent.ToolCallVisible(result.call.name, toolSummary(result.call, result.wrapped), true),
            )
            // 2026-10-19（用户报「打完一步不打勾」）：普通工具结果后面附一句计划提醒。
            // 只进**模型可见的历史**，不进 UI 的工具卡（卡片仍显示工具原文）。
            val planHint = if (result.call.name != "update_plan") planController.reminderLine() else null
            history += HistoryMessage(
                "local-tool-${System.nanoTime()}",
                HistoryMessage.ROLE_ASSISTANT,
                if (planHint != null) result.wrapped + "\n\n" + planHint else result.wrapped,
            )
            ToolResultFlow.CONTINUE
        }

        is GuardPipeline.Result.Failed -> {
            consecutiveToolFailures++
            val name = result.call?.name ?: "tool"
            // 第八十七批 R2：工具失败也归统一策略——**工具超时单独一类**（它不是通道坏，
            // 第五十四批 P1-2 的同一条教训：一次工具报错不该把整轮判成网络故障）。
            val kind = FailurePolicy.classifyTool(result.error)
            val action = FailurePolicy.decide(
                kind = kind,
                attempt = consecutiveToolFailures,
                fallbackAvailable = false, // 工具坏了换通道没用（不是通道的事）
            )
            // 第一百一十五批：先给出**可读的归因/建议**（只生成、只记日志，不执行）。
            // 建议文本随失败原文一起回灌（模型据此决定下一步），执行权仍在模型/用户手里。
            val repair = ToolFailureAdvisor.advise(
                ToolFailureEvidence(
                    toolName = name,
                    errorText = result.error,
                    arguments = result.call?.arguments,
                    failureKind = kind,
                ),
            )
            log(ToolFailureAdvisor.auditLine(repair, result.error))
            val failureText = "失败：${result.error}\n${repair.feedbackSuffix()}"
            persistToolExchange(sessionId, step, name, failureText)
            onEvent?.invoke(EngineEvent.ToolCallVisible(name, failureText, false))
            history += HistoryMessage(
                "local-tool-${System.nanoTime()}",
                HistoryMessage.ROLE_ASSISTANT,
                DataFence.wrap(name, System.currentTimeMillis(), failureText),
            )
            if (action is FailureAction.Retry) {
                // Retry 在这里的语义＝「回灌错误原文、让模型自己看着办」（与坏参数同款）：
                // 工具层没有「偷偷再跑一次」这回事——真重跑决定权在模型，不在引擎。
                ToolResultFlow.CONTINUE
            } else {
                val msg = "工具连续失败 ${consecutiveToolFailures} 次，已停下。" +
                    "换个说法或换个文件再试（不许硬试）。"
                _notice.value = msg
                settle(sessionId, step, RunState.ABORTED, "Aborted")
                onEvent?.invoke(EngineEvent.AskUser(msg))
                ToolResultFlow.STOP_RUN
            }
        }

        is GuardPipeline.Result.Rejected -> {
            // 越界/权限 ⇒ 终止本轮（§8）：回灌理由让模型知道边界，本轮到此
            // **不记进「这次学到」的信号**——被保护闸拦下的写尝试不算学到，不许进卡。
            val name = result.call?.name ?: "tool"
            // 第一百一十五批：被拒不等于可重试——建议层只负责把「该找用户」说清楚。
            val repair = ToolFailureAdvisor.advise(
                ToolFailureEvidence(
                    toolName = name,
                    errorText = result.reason,
                    arguments = result.call?.arguments,
                ),
            )
            log(ToolFailureAdvisor.auditLine(repair, result.reason))
            val rejectedText = "被拒：${result.reason}\n${repair.feedbackSuffix()}"
            persistToolExchange(sessionId, step, name, rejectedText)
            onEvent?.invoke(EngineEvent.ToolCallVisible(name, rejectedText, false))
            history += HistoryMessage(
                "local-tool-${System.nanoTime()}",
                HistoryMessage.ROLE_ASSISTANT,
                DataFence.wrap(name, System.currentTimeMillis(), rejectedText),
            )
            ToolResultFlow.TERMINATE_ROUND
        }

        is GuardPipeline.Result.NeedsConfirm -> {
            // 在线路径的 NeedsConfirm 由调用方先处理（挂起出卡）；走到这里说明
            // 有人拿未经批准的路径调了本函数（负例演练①的靶心）⇒ 记日志、不执行。
            log("抱朴引擎：handleToolResult 收到未经批准的 NeedsConfirm（${result.call.name}），已拒")
            ToolResultFlow.STOP_RUN
        }
    }

    // ── 第九十二批：工具调度的两条路（读并行 / 串行含确认卡） ────────────────

    /** 这个调用走哪条 lane（纯判据在 [ToolScheduling.laneOf]；这里只做一次注册表查询）。 */
    private fun laneOfCall(call: ToolCall): ToolLane = ToolScheduling.laneOf(registry.find(call.name))

    /** 这个调用的单工具时限（与管线第 ④ 段用的是**同一个数**：`ToolSpec.timeoutMs`）。 */
    private fun timeoutOfCall(call: ToolCall): Long =
        registry.find(call.name)?.timeoutMs ?: ToolTimeout.FILE_MS

    /**
     * 一个工具结果的收口（轨迹 ＋ 回灌/历史/可见卡）。
     *
     * 串行路与并行段**共用这一个函数** ⇒ 两条路径记的东西逐字节同形
     * （第八十七批 trace 的 ACT 行不会因为走了哪条 lane 而长成两个样子）。
     *
     * @param startedAtMs 实测起点；`0` = 未测（等用户点批准的那段不算工具耗时）
     */
    private fun handleResultAndTrace(
        sessionId: Long,
        step: Int,
        call: ToolCall,
        result: GuardPipeline.Result,
        history: MutableList<HistoryMessage>,
        startedAtMs: Long,
        endedAtMs: Long = System.currentTimeMillis(),
    ): ToolResultFlow {
        trace.noteTool(
            step = step,
            tool = call.name,
            args = call.arguments,
            startedAtMs = startedAtMs,
            endedAtMs = endedAtMs,
            status = traceStatusOf(result),
            failureKind = traceFailureOf(result),
        )
        return handleToolResult(sessionId, step, result, history)
    }

    /** 停止信号落在**执行前**：这次调用没执行，如实记一行 CANCELLED（批里剩下的不再记）。 */
    private fun abortBeforeCall(sessionId: Long, step: Int, call: ToolCall) {
        trace.noteTool(
            step = step,
            tool = call.name,
            args = call.arguments,
            startedAtMs = 0L,
            status = TraceStatus.CANCELLED,
            failureKind = FailureKind.CANCELLED,
        )
        settle(sessionId, step, RunState.ABORTED, "Aborted")
    }

    /**
     * 一个工具调用的**串行**处理（含确认卡）：第九十二批从工具批循环里整段抽出，
     * **行为逐字不变**——确认卡仍在执行前出、批准后只跳第 ③ 段（`executeApproved`）、
     * 拒绝/超时/放弃仍按原样收尾。抽出来只是让并行的读段能走另一条路，
     * 而不是让两种语义挤在一个 for 里互相将就。
     */
    private fun handleSerialCall(
        sessionId: Long,
        step: Int,
        call: ToolCall,
        history: MutableList<HistoryMessage>,
    ): ToolResultFlow {
        val toolStartedAt = System.currentTimeMillis()
        val result = pipeline.execute(call) { name, text ->
            writeSpill(sessionId, name, text)
        }
        if (result !is GuardPipeline.Result.NeedsConfirm) {
            // 在线路径（不经人看）：工具真跑了，耗时是实测的
            return handleResultAndTrace(sessionId, step, call, result, history, toolStartedAt)
        }
        // 第八十七批 R1：挂起那一刻先记一行 CONFIRM——它不是成功也不是失败，
        // 记成 OK 会让回放的人以为这次写已经生效了（P1-1 的同一类谎言）。
        trace.noteTool(
            step = step,
            tool = call.name,
            args = call.arguments,
            startedAtMs = toolStartedAt,
            status = TraceStatus.CONFIRM,
        )
        // 安全点 2（第六十四批）：出确认卡之前——不能卡在工具
        // 半途切（那一步的模型决定已经是旧通道做的，卡要出给
        // 当前用户看；切换影响的是下一步）。
        drainChannelSwitch(SafePoint.BeforeConfirmCard)
        // 确认制（刀 3 A 段）：落 pending → 出卡 → 挂起等 → 执行/拒绝/超时。
        // runBlocking 桥：循环跑在裸线程 zd-baopu-run 上（本来就常驻阻塞
        // 等网络），这里等的是人点按钮——同一颗线程，等什么不是等。
        val oc = runBlocking {
            confirmFlow.enter(sessionId, step, result.call) { name, text ->
                writeSpill(sessionId, name, text)
            }
        }
        return when (oc) {
            is ConfirmFlow.Outcome.Approved -> {
                // 第八十七批 R1：真执行完了补一行（`startedAtMs = 0` ⇒ 耗时
                // 记「未测」：那段时间耗在等人点按钮上，不是工具的耗时）。
                trace.noteTool(
                    step = step,
                    tool = call.name,
                    args = call.arguments,
                    startedAtMs = 0L,
                    status = traceStatusOf(oc.result),
                    failureKind = traceFailureOf(oc.result),
                )
                // 批准后执行的结果与在线路径**同形**处理（回灌/历史/可见卡）
                handleToolResult(sessionId, step, oc.result, history)
            }

            is ConfirmFlow.Outcome.Rejected -> {
                trace.noteTool(
                    step = step,
                    tool = call.name,
                    args = call.arguments,
                    startedAtMs = 0L,
                    status = TraceStatus.REJECTED,
                )
                // 拒绝不执行；回灌进历史（模型下一轮看得见）＋ 落库可见；对话继续
                persistAssistantMessage(sessionId, oc.note)
                history += HistoryMessage(
                    "local-note-${System.nanoTime()}",
                    HistoryMessage.ROLE_ASSISTANT,
                    oc.note,
                )
                ToolResultFlow.CONTINUE
            }

            is ConfirmFlow.Outcome.Abandoned -> ToolResultFlow.CONTINUE // 用户点了停止：循环头部收尾

            is ConfirmFlow.Outcome.TimedOut -> {
                trace.noteTool(
                    step = step,
                    tool = call.name,
                    args = call.arguments,
                    startedAtMs = 0L,
                    status = TraceStatus.TIMEOUT,
                    failureKind = FailureKind.CANCELLED,
                )
                _notice.value = oc.note
                persistAssistantMessage(sessionId, oc.note)
                settle(sessionId, step, RunState.ABORTED, "Aborted")
                ToolResultFlow.STOP_RUN
            }

            is ConfirmFlow.Outcome.Ignored -> ToolResultFlow.CONTINUE // 双保险：不该发生，跳过这个调用
        }
    }

    /**
     * 一个**并行读段**（第九十二批）：
     * 1. 先按提交顺序把 ①②③（注册表/Schema、路径守卫、权限）跑完——这三段带顺序语义，
     *    第一个越界就要终止本轮、后面的不许再跑，所以**不能**丢进并发里；
     * 2. 再把通过的那几个同时丢进读池（上限 `ToolScheduling.MAX_READ_PARALLEL`）；
     * 3. 最后仍按**提交顺序**收口（回灌给模型、落库、可见卡、轨迹的顺序都不变）。
     *
     * 停止信号落在段中间：已跑完的照常采信，没回来的 `cancel(true)` 并按 CANCELLED 收尾
     * ——读工具不写盘，中断是安全的（写工具根本不进这里）。
     */
    private fun runParallelStage(
        sessionId: Long,
        step: Int,
        stage: ToolStage.Parallel,
        history: MutableList<HistoryMessage>,
        batchDeadlineMs: Long,
    ): ToolResultFlow {
        val ready = ArrayList<GuardPipeline.Prepared.Ready>(stage.calls.size)
        for (call in stage.calls) {
            val before = System.currentTimeMillis()
            when (val p = pipeline.prepare(call)) {
                is GuardPipeline.Prepared.Ready -> ready += p
                // ①②③ 已定论（坏 Schema / 越界 / 需确认）：与串行走**同一个**收口函数
                is GuardPipeline.Prepared.Settled -> {
                    val flow = handleResultAndTrace(sessionId, step, call, p.result, history, before)
                    if (flow != ToolResultFlow.CONTINUE) return flow
                }
            }
        }
        if (ready.isEmpty()) return ToolResultFlow.CONTINUE
        val items = batchRunner.runParallel(
            stage = ToolStage.Parallel(ready.map { it.call }),
            timeoutOf = { call -> timeoutOfCall(call) },
            // 索引回查：并行读走 executeInline（④ 直跑；超时由本层封，
            // 因为段任务本身就跑在读池上，再 submit 一次就是拿线程等自己）
            execute = { index, _ ->
                pipeline.executeInline(ready[index]) { name, text ->
                    writeSpill(sessionId, name, text)
                }
            },
            shouldStop = { stopRequested },
            deadlineAtMs = batchDeadlineMs,
        )
        for (item in items) {
            if (item.cancelled) {
                // 停止落在这段里：如实记 CANCELLED（`startedAtMs = 0` ＝ 未采信它的耗时）
                abortBeforeCall(sessionId, step, item.call)
                return ToolResultFlow.STOP_RUN
            }
            val result = item.result ?: continue
            val flow = handleResultAndTrace(
                sessionId, step, item.call, result, history, item.startedAtMs, item.endedAtMs,
            )
            if (flow != ToolResultFlow.CONTINUE) return flow
        }
        return ToolResultFlow.CONTINUE
    }

    /**
     * 工具结果 → 轨迹状态（第八十七批 R1）。
     *
     * 为什么单列一个映射函数：`NeedsConfirm` 在**在线路径**这里不会出现（它走另一个分支），
     * 但类型上仍要穷尽——真出现就记 CONFIRM（绝不当成功）。
     */
    private fun traceStatusOf(result: GuardPipeline.Result): TraceStatus = when (result) {
        is GuardPipeline.Result.Done -> TraceStatus.OK
        is GuardPipeline.Result.Failed -> TraceStatus.FAILED
        is GuardPipeline.Result.Rejected -> TraceStatus.REJECTED
        is GuardPipeline.Result.NeedsConfirm -> TraceStatus.CONFIRM
    }

    /** 工具结果 → 失败分类（只在失败/被拒时有值；成功为 null）。 */
    private fun traceFailureOf(result: GuardPipeline.Result): FailureKind? = when (result) {
        is GuardPipeline.Result.Failed -> FailurePolicy.classifyTool(result.error)
        // 被保护闸拦下不是「失败」，是边界：分类留空，状态已经写了 REJECTED。
        is GuardPipeline.Result.Rejected -> null
        is GuardPipeline.Result.Done -> null
        is GuardPipeline.Result.NeedsConfirm -> null
    }

    // 第八十七批 R1：工具结果 → 轨迹状态/分类的两个映射**必须放在 handleToolResult 之后**。
    // 为什么：`BaopuArtifactShelfTest.C2` 用 `indexOf("is GuardPipeline.Result.Done ->")` 认
    // 「记账调用落在 Done 支、且在 Failed 支之前」——同形的 when 支写在前面会把那个窗口认错。
    /** 用户消息先落库再渲染（§3：顺序反了断点续跑就是空话）。 */
    private fun persistUserMessage(sessionId: Long, text: String) {
        var msgId = -1L
        // P2-3（第五十六批）：① `.get()` 等事务真的提交（旧实现把 Future 丢了，
        // 写失败静默）；② 事件移到 `endTransaction()` **之后**——旧实现在事务体内就发，
        // UI 可能先收到事件、库里还没有（用户看到一条"存在"的消息，杀进程就没了）。
        val ok = runCatching {
            db.write { d ->
                d.beginTransaction()
                try {
                    val now = System.currentTimeMillis()
                    val seq = nextSeq(d, sessionId)
                    val m = BaopuSql.insertMessage(sessionId, seq, HistoryMessage.ROLE_USER, now, "done")
                    d.execSQL(m.sql, m.args)
                    val id = lastInsertId(d)
                    val p = BaopuSql.insertPart(id, 0, "text", text, now)
                    d.execSQL(p.sql, p.args)
                    val t = BaopuSql.touchSession(sessionId, now, text.take(80))
                    d.execSQL(t.sql, t.args)
                    d.setTransactionSuccessful()
                    msgId = id
                } finally {
                    d.endTransaction()
                }
            }.get()
        }.isSuccess
        if (!ok) {
            log("抱朴引擎：用户消息落库失败（session=$sessionId），本次不发 MessagePersisted")
            return
        }
        onEvent?.invoke(EngineEvent.MessagePersisted(msgId, HistoryMessage.ROLE_USER))
    }

    /** assistant 消息：流结束一次性落库（判据 7：流式期间内存累积，不每 token 写一次）。 */
    private fun persistAssistantMessage(sessionId: Long, text: String) {
        if (text.isBlank()) return
        var msgId = -1L
        // P2-3：同 persistUserMessage——等提交 + 事件在事务外
        val ok = runCatching {
            db.write { d ->
                d.beginTransaction()
                try {
                    val now = System.currentTimeMillis()
                    val seq = nextSeq(d, sessionId)
                    val m = BaopuSql.insertMessage(sessionId, seq, HistoryMessage.ROLE_ASSISTANT, now, "done")
                    d.execSQL(m.sql, m.args)
                    val id = lastInsertId(d)
                    val p = BaopuSql.insertPart(id, 0, "text", text, now)
                    d.execSQL(p.sql, p.args)
                    val t = BaopuSql.touchSession(sessionId, now, text.take(80))
                    d.execSQL(t.sql, t.args)
                    d.setTransactionSuccessful()
                    msgId = id
                } finally {
                    d.endTransaction()
                }
            }.get()
        }.isSuccess
        if (!ok) {
            log("抱朴引擎：assistant 消息落库失败（session=$sessionId），本次不发 MessagePersisted")
            return
        }
        onEvent?.invoke(EngineEvent.MessagePersisted(msgId, HistoryMessage.ROLE_ASSISTANT))
    }

    /** 工具交换（入参 + 围栏后的出参）落一条 tool part：会话里看得见（设计 §9 施工细节）。 */
    private fun persistToolExchange(sessionId: Long, step: Int, tool: String, wrapped: String) {
        // P2-3：`.get()` 等提交；失败落日志（旧实现丢弃 Future ⇒ 工具结果丢了你都不知道）
        val ok = runCatching {
            db.write { d ->
                d.beginTransaction()
                try {
                    val now = System.currentTimeMillis()
                    val seq = nextSeq(d, sessionId)
                    val m = BaopuSql.insertMessage(sessionId, seq, HistoryMessage.ROLE_ASSISTANT, now, "done")
                    d.execSQL(m.sql, m.args)
                    val msgId = lastInsertId(d)
                    val payload = org.json.JSONObject()
                        .put("tool", tool)
                        .put("step", step)
                        .put("result", wrapped)
                        .toString()
                    val p = BaopuSql.insertPart(msgId, 0, "tool", payload, now)
                    d.execSQL(p.sql, p.args)
                    val rl = BaopuSql.insertRunLog(
                        sessionId = sessionId,
                        step = step,
                        state = "Acting",
                        model = provider.model,
                        tokensIn = 0,
                        tokensOut = 0,
                        tokensInEst = 0,
                        tokensOutEst = 0,
                        tool = tool,
                        ok = RunLoopRules.toolExchangeOk(wrapped),
                        latencyMs = 0,
                        err = "",
                        createdAt = now,
                    )
                    d.execSQL(rl.sql, rl.args)
                    d.setTransactionSuccessful()
                } finally {
                    d.endTransaction()
                }
            }.get()
        }.isSuccess
        if (!ok) log("抱朴引擎：工具交换落库失败（session=$sessionId, tool=$tool）")
    }

    /**
     * 标「中断于第 N 步」（续跑时）。
     *
     * **不再往 `notice` 里写一遍**：会话头已经有红字「上次中断于第 N 步（进程当时被杀了）」
     * ＋「续跑」按钮，两条同时出现就是同一句话说两遍（2026-10-17 真机实测）。
     */
    private fun markInterrupted(sessionId: Long, interruptedStepValue: Int) {
        _interruptedStep.value = interruptedStepValue
    }

    /** 续跑起点 = 落库的 interrupted_step（没有就从 0 起）。 */
    private fun stepOf(sessionId: Long): Int = db.read { d ->
        d.rawQuery(
            "SELECT COALESCE(interrupted_step, -1) FROM session WHERE id = ?",
            arrayOf(sessionId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else RunRules.NOT_INTERRUPTED }
            .coerceAtLeast(0)
    }

    // ── 压缩（最小接线） ──────────────────────────────────────────────────

    /**
     * **真跑一次压缩**（第十三批 A 段 —— 替换刀 0b 留下的恒返回 null 的桩）。
     *
     * 桩的问题（派工单 §1 现状证据）：只选区 + 记日志，恒 null ⇒ 长会话反复进
     * `COMPRESSING` 却什么都没压，最后靠 `MAX_STEPS`（现 120，见 RunRules）硬刹车——用户看到的
     * 「压缩中」一直是假的。
     *
     * 现在交给 [CompressionRunner]（内核：选区 → 判通道 → 一次非流式补全 → 解析 →
     * 落库）。引擎这里只做两件事：
     * 1. 成功 ⇒ 把被压的 id 并进 [compressedIds]（**组装时跳过它们**是压缩真正生效
     *    的那一半——不跳过，光有摘要等于没压，旧消息照样占满窗口）；
     * 2. 跳过/失败/冷却 ⇒ 返回 null，**本轮按没压继续跑**（压缩是优化，不配让整轮陪葬）。
     */
    private fun runCompression(
        sessionId: Long,
        step: Int,
        history: List<HistoryMessage>,
        provider: Provider,
        previousSummary: String?,
    ): String? {
        // 第八十七批 R1：压缩也是轨迹里的一种边界（耗时/成没成都要看得见——
        // 「压缩中」曾经假了十三批，就是因为没人记它到底跑没跑成）。
        val compressStartedAt = System.currentTimeMillis()
        val outcome = compression.runOnce(
            sessionId, history, provider, step,
            // 第十六批：把 compressedIds 传给内核扣选区（「压过的不再重发」）——
            // 组装跳过、条数判据、压缩选区三处从此同源，不再各算各的。
            skipIds = compressedIds,
            previousSummary = previousSummary,
        )
        trace.noteCompress(
            step = step,
            startedAtMs = compressStartedAt,
            status = when (outcome) {
                is CompressionRunner.Outcome.Ok -> TraceStatus.OK
                is CompressionRunner.Outcome.Skipped -> TraceStatus.SKIPPED
                is CompressionRunner.Outcome.Cooling -> TraceStatus.SKIPPED
                is CompressionRunner.Outcome.Failed -> TraceStatus.FAILED
            },
            failureKind = when (outcome) {
                is CompressionRunner.Outcome.Failed ->
                    FailurePolicy.classifyModel("HTTP ${outcome.code} ${outcome.message}")
                else -> null
            },
        )
        return when (outcome) {
            is CompressionRunner.Outcome.Ok -> {
                val added = outcome.record.compressedIds.size
                compressedIds = compressedIds + outcome.record.compressedIds
                log("抱朴引擎：压缩完成，累计已压 ${compressedIds.size} 条（本轮新增 $added 条）")
                outcome.record.summary
            }

            is CompressionRunner.Outcome.Skipped -> {
                log("抱朴引擎：本次不压——${outcome.reason}")
                null
            }

            // 失败与冷却：内核已记 run_log，这里连日志都不重复打（避免两处各说一遍口径）
            is CompressionRunner.Outcome.Failed -> null
            is CompressionRunner.Outcome.Cooling -> null
        }
    }

    // ── spill（第 ⑤ 段配套） ──────────────────────────────────────────────

    /**
     * spill 落盘：落工作区内 `.baopu-spill/`（read_file 够得着——范围读的前提）。
     *
     * 第九十二批：文件名后缀加了**自增序号**。并行读之后这个函数会被几个读线程同时调，
     * 而旧名字只到毫秒——同一毫秒里两个同名工具各溢出一份，后写的会把先写的覆盖掉，
     * 两条回灌于是指向同一个文件（内容只剩一份）。加序号是唯一不依赖时钟精度的解法。
     */
    private fun writeSpill(sessionId: Long, name: String, text: String): SpillResult? = runCatching {
        val dir = File(spillRoot(), ".baopu-spill")
        dir.mkdirs()
        val f = File(
            dir,
            "spill-s$sessionId-$name-${System.currentTimeMillis()}-${spillSeq.incrementAndGet()}.txt",
        )
        f.writeText(text, Charsets.UTF_8)
        SpillResult(f, text.length)
    }.getOrNull().also {
        if (it == null) log("抱朴引擎：spill 落盘失败（$name，${text.length} 字符）")
    }

    // ── db 小工具 ─────────────────────────────────────────────────────────

    /**
     * 读历史（C 段：**含工具结果回灌**——被杀续跑原来只取 `kind='text'`，
     * 模型因此对不上账；判据 J9）。
     *
     * SQL 取全部 part（不再按 kind 过滤），映射判据全在纯函数 [HistoryCodec]
     * （JVM 直测）；顺序 = `message.seq ASC, part.seq ASC` ⇒ 与在线时一致
     * （工具结果紧跟发起它的那轮——落库时就这个顺序）。
     *
     * 第十七批例外：`kind='plan'` 的 part **不进历史**——计划是给用户看的
     * （工作计划卡），模型侧的计划回灌是 update_plan 的 tool part（执行结果
     * 已经含摘要）。放它进去等于让模型读一嘴裸 JSON，白白吃上下文。
     */
    private fun loadHistory(sessionId: Long): List<HistoryMessage> = db.read { d ->
        d.rawQuery(
            "SELECT message.id, message.role, part.kind, part.payload FROM message " +
                "LEFT JOIN part ON part.message_id = message.id " +
                "WHERE message.session_id = ? AND (part.kind IS NULL OR part.kind <> 'plan') " +
                "ORDER BY message.seq ASC, part.seq ASC",
            arrayOf(sessionId.toString()),
        ).use { c ->
            val rows = mutableListOf<HistoryRow>()
            var n = 0
            while (c.moveToNext()) {
                rows += HistoryRow(
                    id = "db-${n++}",
                    role = c.getString(1),
                    kind = if (c.isNull(2)) null else c.getString(2),
                    payload = if (c.isNull(3)) "" else c.getString(3),
                )
            }
            HistoryCodec.decode(rows)
        }
    }

    private fun nextSeq(d: android.database.sqlite.SQLiteDatabase, sessionId: Long): Int =
        d.rawQuery(
            "SELECT COALESCE(MAX(seq), -1) + 1 FROM message WHERE session_id = ?",
            arrayOf(sessionId.toString()),
        ).use { c -> if (c.moveToFirst()) c.getInt(0) else 0 }

    private fun lastInsertId(d: android.database.sqlite.SQLiteDatabase): Long =
        d.rawQuery("SELECT last_insert_rowid()", null).use { c -> if (c.moveToFirst()) c.getLong(0) else -1L }
}

/**
 * 一步的用量读数 ＋ 步号 ＋ 这步耗时（第三十八批 A 段，[RunEngine.onUsage] 的参数）。
 *
 * 为什么不直接传 `LlmUsage`（派工单 §1.1 的片段就只写了它）：§1.2 要每步列表带
 * 「步骤号、耗时 ms」，而这两样只有引擎知道——壳层按回调到达顺序编号，遇到
 * 「中间某步服务端没返 usage」就把第 3 步标成第 2 步，那是编数。
 * 申报在交付报告的「与文档偏差」一节（形制没变：还是可空 `var` lambda、还是
 * `persistUsage` 同一处调）。
 *
 * `elapsedMs < 0` = 未知（续跑开局没有开步时刻）⇒ 界面不显示耗时那一截。
 */
data class BaopuUsageStep(
    val step: Int,
    val usage: LlmUsage,
    val elapsedMs: Long,
)
