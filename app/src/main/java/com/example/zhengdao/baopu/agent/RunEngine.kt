// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §2 状态机（每步迁移一次 SQLite 事务；max_steps=16；
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
import com.example.zhengdao.baopu.HardFacts
import com.example.zhengdao.baopu.HistoryMessage
import com.example.zhengdao.baopu.LlmClient
import com.example.zhengdao.baopu.LlmEvent
import com.example.zhengdao.baopu.LlmMessage
import com.example.zhengdao.baopu.LlmUsage
import com.example.zhengdao.baopu.MemoryRules
import com.example.zhengdao.baopu.MemoryStore
import com.example.zhengdao.baopu.PendingStore
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaRules
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.baopu.SessionPendingStore
import com.example.zhengdao.rootfs.RunLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
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

    fun release(sessionKey: String) {
        active.compareAndSet(sessionKey, null)
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
     * 熔断后的候选通道（刀 1 补丁）：入参是当前通道，返回下一个或 null。
     *
     * 为什么不给引擎一个"通道列表"：Key 只该在 ProviderPrefs 手里，引擎拿到的
     * 已经是拼好的 [Provider]（含 Key），它不需要知道 Key 从哪来。
     */
    private val channelFallback: (Provider) -> Provider? = { null },
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

    /** 给人看的一句话（错误号口径：不直出英文 err）。 */
    private val _notice = mutableStateOf<String?>(null)
    val notice: State<String?> get() = _notice

    var onEvent: ((EngineEvent) -> Unit)? = null

    private val runExecutor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "zd-baopu-run").apply { isDaemon = true }
    }

    @Volatile
    private var stopRequested = false

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

    /**
     * 确认制内核。回调全是引擎侧接线：
     * - [enterAwaitConfirm]：run_log 的 AwaitConfirm 行 + session pending 三列**同事务**；
     * - 出卡事件直接转 [onEvent]（UI 从事件拿卡，不轮询）；
     * - 前台计时见 [confirmForegroundMs]（屏幕生命周期经 [noteConfirmBackground] 喂）。
     */
    private val confirmFlow = ConfirmFlow(
        pipeline = pipeline,
        store = pendingStore,
        foregroundElapsedMs = { confirmForegroundMs },
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
    fun send(sessionId: Long, text: String) {
        if (text.isBlank()) return
        acquireOrNotice(sessionId) ?: return
        stopRequested = false
        _notice.value = null
        runExecutor.execute {
            try {
                runLoop(sessionId, userText = text, resumed = false)
            } catch (e: Exception) {
                log("抱朴引擎：循环崩了 ${e.javaClass.simpleName}: ${e.message}")
                _state.value = RunState.ABORTED
                _notice.value = "引擎内部错误（已记录日志）：${e.javaClass.simpleName}"
            } finally {
                ActiveSessionGate.release("id:$sessionId")
                onEvent?.invoke(EngineEvent.Settled) // 门已释放 ⇒ UI 重算中断标记（见 Settled 的说明）
            }
        }
    }

    /** 用户点「停止」：半成品保留（写工具归刀 2；本刀只读，停止即止循环）。 */
    fun stop() {
        stopRequested = true
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
        val tools = when (provider.protocol) {
            com.example.zhengdao.baopu.LlmProtocol.OPENAI_COMPAT -> registry.toolsJsonOpenAi()
            com.example.zhengdao.baopu.LlmProtocol.ANTHROPIC -> registry.toolsJsonAnthropic()
        }

        // 历史（续跑 = 重建上下文：人设 + 硬事实 + 最近 N 轮原文；C 段起含工具结果回灌）
        var history = loadHistory(sessionId).toMutableList()
        var stepCount = if (resumed) stepOf(sessionId) else 0
        if (userText != null) {
            persistUserMessage(sessionId, userText)
            history += HistoryMessage("local-user-${System.nanoTime()}", HistoryMessage.ROLE_USER, userText)
        }
        var compressionSummary: String? = null

        while (!stopRequested) {
            // ── Thinking 前：预算检查（> 窗口×0.85 ⇒ 先 Compressing）──
            val probe = assemble(persona, facts, history, "", windowTokens, compressionSummary)
            if (probe.needsCompressing) {
                _state.value = RunState.COMPRESSING
                persistStep(sessionId, stepCount, "Compressing")
                log("抱朴引擎：上下文估算 ${probe.estimatedTokens} > 触发线，进 Compressing")
                compressionSummary = compressWithFallback(history, provider) ?: compressionSummary
            }

            // ── 一步 = 一次模型轮次 ──
            stepCount++
            _step.value = stepCount
            _state.value = RunState.THINKING
            persistStep(sessionId, stepCount, "Thinking")

            val prompt = assemble(persona, facts, history, "", windowTokens, compressionSummary)
            // 唯一出口产出的渲染：前缀（人设+硬事实+记忆）作 system，其余作 user
            // （0b 的 renderHistory 已把角色写进文本 ⇒ 拆两半不改变模型看到的内容）
            val rendered = prompt.render()
            val prefix = prompt.prefixText()
            val rest = rendered.removePrefix(prefix).removePrefix(AssembledPrompt.SEP)
            val messages = buildList {
                if (prefix.isNotBlank()) add(LlmMessage(LlmMessage.ROLE_SYSTEM, prefix))
                add(LlmMessage(LlmMessage.ROLE_USER, rest.ifBlank { "（继续）" }))
            }

            // ── 流式一轮（内存累积，流结束一次性落库——判据 7）──
            when (val outcome = streamRound(sessionId, messages, tools)) {
                is RoundOutcome.Text -> {
                    persistAssistantMessage(sessionId, outcome.text)
                    history += HistoryMessage(
                        "local-a-${System.nanoTime()}",
                        HistoryMessage.ROLE_ASSISTANT,
                        outcome.text,
                    )
                    settle(sessionId, stepCount, RunState.DONE, "Done")
                    return
                }

                is RoundOutcome.ToolCalls -> {
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
                    for (call in outcome.calls) {
                        if (stopRequested) {
                            settle(sessionId, stepCount, RunState.ABORTED, "Aborted")
                            return
                        }
                        val result = pipeline.execute(call) { name, text ->
                            writeSpill(sessionId, name, text)
                        }
                        if (result is GuardPipeline.Result.NeedsConfirm) {
                            // 确认制（刀 3 A 段）：落 pending → 出卡 → 挂起等 → 执行/拒绝/超时。
                            // runBlocking 桥：循环跑在裸线程 zd-baopu-run 上（本来就常驻阻塞
                            // 等网络），这里等的是人点按钮——同一颗线程，等什么不是等。
                            when (
                                val oc = runBlocking {
                                    confirmFlow.enter(sessionId, stepCount, result.call) { name, text ->
                                        writeSpill(sessionId, name, text)
                                    }
                                }
                            ) {
                                is ConfirmFlow.Outcome.Approved -> {
                                    // 批准后执行的结果与在线路径**同形**处理（回灌/历史/可见卡）
                                    when (handleToolResult(sessionId, stepCount, oc.result, history)) {
                                        ToolResultFlow.CONTINUE -> Unit
                                        ToolResultFlow.TERMINATE_ROUND -> terminateRound = true
                                        ToolResultFlow.STOP_RUN -> return
                                    }
                                }

                                is ConfirmFlow.Outcome.Rejected -> {
                                    // 拒绝不执行；回灌进历史（模型下一轮看得见）＋ 落库可见；对话继续
                                    persistAssistantMessage(sessionId, oc.note)
                                    history += HistoryMessage(
                                        "local-note-${System.nanoTime()}",
                                        HistoryMessage.ROLE_ASSISTANT,
                                        oc.note,
                                    )
                                }

                                is ConfirmFlow.Outcome.Abandoned -> Unit // 用户点了停止：循环头部收尾
                                is ConfirmFlow.Outcome.TimedOut -> {
                                    _notice.value = oc.note
                                    persistAssistantMessage(sessionId, oc.note)
                                    settle(sessionId, stepCount, RunState.ABORTED, "Aborted")
                                    return
                                }

                                is ConfirmFlow.Outcome.Ignored -> Unit // 双保险：不该发生，跳过这个调用
                            }
                        } else {
                            when (handleToolResult(sessionId, stepCount, result, history)) {
                                ToolResultFlow.CONTINUE -> Unit
                                ToolResultFlow.TERMINATE_ROUND -> terminateRound = true
                                ToolResultFlow.STOP_RUN -> return
                            }
                        }
                        if (terminateRound) break
                    }
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
                    when (val d = RunRules.channelDecision(consecutiveChannelFailures, fallback)) {
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
                            continue // 重跑同一步，不涨步数
                        }

                        RunRules.ChannelDecision.BrokenAskUser -> {
                            channelBroken = true
                            val msg = if (channelSwitches >= RunRules.MAX_CHANNEL_SWITCHES) {
                                "同一轮已自动换过 ${RunRules.MAX_CHANNEL_SWITCHES} 次通道都不通：" +
                                    "换个通道或稍后再试（已停在第 $stepCount 步）"
                            } else {
                                "当前通道连续失败 ${consecutiveChannelFailures} 次（已熔断）：" +
                                    "换个通道或稍后再试"
                            }
                            _notice.value = msg
                            settle(sessionId, stepCount, RunState.ABORTED, "Aborted", err = errText)
                            onEvent?.invoke(EngineEvent.AskUser(msg))
                            return
                        }

                        RunRules.ChannelDecision.KeepCurrent -> {
                            // 没到熔断线：本轮失败即停（不硬重试，与 0b 口径一致）
                            _notice.value = outcome.userFacing
                            settle(sessionId, stepCount, RunState.ABORTED, "Aborted", err = errText)
                            onEvent?.invoke(EngineEvent.AskUser(_notice.value!!))
                            return
                        }
                    }
                }
            }

            // ── 步数刹车：16 步必停 ──
            if (RunRules.isStepExhausted(stepCount)) {
                val msg = "已到 ${RunRules.MAX_STEPS} 步上限，停下。继续说「继续」会重新计步。"
                _notice.value = msg
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
    ) = ContextAssembler.assemble(
        persona = persona,
        facts = facts,
        stickyMemories = stickyMemories,
        newMemories = recalledCache,
        history = history,
        current = current,
        windowTokens = windowTokens,
        compressionSummary = compressionSummary,
    )

    /** 本会话已注入的记忆（只增不改——0b 的 sticky 语义；候选确认后才进这里）。 */
    private val stickyMemories = mutableListOf<com.example.zhengdao.baopu.MemoryEntry>()

    /** 本轮召回到的记忆（循环内不变 ⇒ 每轮前缀一致，缓存友好）。 */
    private var recalledCache: List<com.example.zhengdao.baopu.MemoryEntry> = emptyList()

    // ── 流式一轮 ──────────────────────────────────────────────────────────

    private sealed interface RoundOutcome {
        data class Text(val text: String) : RoundOutcome
        data class ToolCalls(val text: String, val calls: List<ToolCall>) : RoundOutcome

        /**
         * @param userFacing 给人看的人话（UI 只显示这个）
         * @param raw 服务端原文（只落 `run_log.err`；空串 = 没有原文可取）
         */
        data class Failed(val userFacing: String, val raw: String = "") : RoundOutcome
    }

    /**
     * 跑一轮流式调用。
     * 内存累积（文本 + tool_calls 都在 LlmClient 内累积），流结束一次性给结果；
     * 流中断该轮作废（[RoundOutcome.Failed]——"看着成功其实没有"不接受）。
     */
    private fun streamRound(sessionId: Long, messages: List<LlmMessage>, tools: org.json.JSONArray): RoundOutcome {
        val textBuf = StringBuilder()
        val latch = CountDownLatch(1)
        var failure: String? = null
        var failureRaw: String? = null
        var rawCalls: List<com.example.zhengdao.baopu.RawToolCall> = emptyList()
        val client = LlmClient(provider)
        client.streamAsync(messages, tools) { ev ->
            when (ev) {
                is LlmEvent.Delta -> textBuf.append(ev.text)
                is LlmEvent.Finished -> rawCalls = ev.toolCalls
                is LlmEvent.Failed -> {
                    failure = ev.error.message ?: ev.error.javaClass.simpleName
                    failureRaw = ev.raw
                }
            }
            if (ev is LlmEvent.Finished || ev is LlmEvent.Failed) latch.countDown()
        }
        latch.await()
        // 原文取不到时退回异常 message（至少 err 列不为空，能看出是哪一类失败）
        failure?.let { return RoundOutcome.Failed(humanizeNetFailure(it), failureRaw ?: it) }
        if (rawCalls.isNotEmpty()) {
            val decoded = ToolCallCodec.decode(rawCalls, registry)
            return when (decoded) {
                is ToolCallCodec.DecodeResult.Ok -> RoundOutcome.ToolCalls(textBuf.toString(), decoded.calls)
                // 坏 JSON/坏参数：错误原文已在 decode 里备好 ⇒ 回灌走 Failed 通道（引擎只给一次重试）
                is ToolCallCodec.DecodeResult.Bad -> RoundOutcome.Failed(decoded.error)
            }
        }
        return RoundOutcome.Text(textBuf.toString())
    }

    /** 网络失败人话化（错误号口径：英文原文只进日志，UI 拿人话）。 */
    private fun humanizeNetFailure(raw: String): String = when {
        raw.contains("403") -> "该通道拒绝访问（403）：Key 不对、没权限，或这条通道在你的网络里不通——换通道"
        raw.contains("401") -> "该通道不认这把 Key（401）：检查 Key 是否填对，或换通道"
        raw.contains("429") -> "模型端限流（429）：稍等再试，或换一个 provider"
        raw.contains("HTTP 5") -> "模型端服务错误（5xx）：稍等再试"
        raw.contains("截断") -> "响应流中断：该轮作废重发（已花的 token 记在账里）"
        raw.contains("timeout", ignoreCase = true) -> "连接超时：检查网络或换一个 provider"
        else -> "模型调用失败（原文已记日志）"
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
    private fun settle(sessionId: Long, step: Int, state: RunState, runLogState: String, err: String = "") {
        // **必须等落库真的完成**：db.write 返回的是 Future（单写者线程 submit 排队），
        // 只调用不 get 就等于"先切状态、收尾行稍后才落"。2026-10-17 真机复现：
        // 一轮 3 步正常完成后显示「上次中断于第 3 步」——就是这一瞬被 UI 读到了。
        runCatching { persistStep(sessionId, step, runLogState, err).get() }
            .onFailure { log("抱朴引擎：收尾落库失败 ${it.javaClass.simpleName}: ${it.message}") }
        _state.value = state
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
            persistToolExchange(sessionId, step, result.call.name, result.wrapped)
            onEvent?.invoke(
                EngineEvent.ToolCallVisible(result.call.name, toolSummary(result.call, result.wrapped), true),
            )
            history += HistoryMessage(
                "local-tool-${System.nanoTime()}",
                HistoryMessage.ROLE_ASSISTANT,
                result.wrapped,
            )
            ToolResultFlow.CONTINUE
        }

        is GuardPipeline.Result.Failed -> {
            consecutiveToolFailures++
            val name = result.call?.name ?: "tool"
            persistToolExchange(sessionId, step, name, "失败：${result.error}")
            onEvent?.invoke(EngineEvent.ToolCallVisible(name, result.error, false))
            history += HistoryMessage(
                "local-tool-${System.nanoTime()}",
                HistoryMessage.ROLE_ASSISTANT,
                DataFence.wrap(name, System.currentTimeMillis(), "失败：${result.error}"),
            )
            if (RunRules.shouldAskUser(consecutiveToolFailures)) {
                val msg = "工具连续失败 ${consecutiveToolFailures} 次，已停下。" +
                    "换个说法或换个文件再试（不许硬试）。"
                _notice.value = msg
                settle(sessionId, step, RunState.ABORTED, "Aborted")
                onEvent?.invoke(EngineEvent.AskUser(msg))
                ToolResultFlow.STOP_RUN
            } else {
                ToolResultFlow.CONTINUE
            }
        }

        is GuardPipeline.Result.Rejected -> {
            // 越界/权限 ⇒ 终止本轮（§8）：回灌理由让模型知道边界，本轮到此
            val name = result.call?.name ?: "tool"
            persistToolExchange(sessionId, step, name, "被拒：${result.reason}")
            onEvent?.invoke(EngineEvent.ToolCallVisible(name, result.reason, false))
            history += HistoryMessage(
                "local-tool-${System.nanoTime()}",
                HistoryMessage.ROLE_ASSISTANT,
                DataFence.wrap(name, System.currentTimeMillis(), "被拒：${result.reason}"),
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

    /** 用户消息先落库再渲染（§3：顺序反了断点续跑就是空话）。 */
    private fun persistUserMessage(sessionId: Long, text: String) {
        db.write { d ->
            d.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                val seq = nextSeq(d, sessionId)
                val m = BaopuSql.insertMessage(sessionId, seq, HistoryMessage.ROLE_USER, now, "done")
                d.execSQL(m.sql, m.args)
                val msgId = lastInsertId(d)
                val p = BaopuSql.insertPart(msgId, 0, "text", text, now)
                d.execSQL(p.sql, p.args)
                val t = BaopuSql.touchSession(sessionId, now, text.take(80))
                d.execSQL(t.sql, t.args)
                d.setTransactionSuccessful()
                onEvent?.invoke(EngineEvent.MessagePersisted(msgId, HistoryMessage.ROLE_USER))
            } finally {
                d.endTransaction()
            }
        }
    }

    /** assistant 消息：流结束一次性落库（判据 7：流式期间内存累积，不每 token 写一次）。 */
    private fun persistAssistantMessage(sessionId: Long, text: String) {
        if (text.isBlank()) return
        db.write { d ->
            d.beginTransaction()
            try {
                val now = System.currentTimeMillis()
                val seq = nextSeq(d, sessionId)
                val m = BaopuSql.insertMessage(sessionId, seq, HistoryMessage.ROLE_ASSISTANT, now, "done")
                d.execSQL(m.sql, m.args)
                val msgId = lastInsertId(d)
                val p = BaopuSql.insertPart(msgId, 0, "text", text, now)
                d.execSQL(p.sql, p.args)
                val t = BaopuSql.touchSession(sessionId, now, text.take(80))
                d.execSQL(t.sql, t.args)
                d.setTransactionSuccessful()
                onEvent?.invoke(EngineEvent.MessagePersisted(msgId, HistoryMessage.ROLE_ASSISTANT))
            } finally {
                d.endTransaction()
            }
        }
    }

    /** 工具交换（入参 + 围栏后的出参）落一条 tool part：会话里看得见（设计 §9 施工细节）。 */
    private fun persistToolExchange(sessionId: Long, step: Int, tool: String, wrapped: String) {
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
                    ok = !wrapped.startsWith("失败") && !wrapped.startsWith("被拒"),
                    latencyMs = 0,
                    err = "",
                    createdAt = now,
                )
                d.execSQL(rl.sql, rl.args)
                d.setTransactionSuccessful()
            } finally {
                d.endTransaction()
            }
        }
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
     * 压缩摘要替身（最小接线）：通道降级留痕（Compressor.chooseChannel 的选择
     * 记日志）；**真跑压缩请求归刀 2**（切片①用不到，接了反而多一条未验路径）。
     */
    private fun compressWithFallback(
        history: List<HistoryMessage>,
        provider: Provider,
    ): String? {
        val choice = Compressor.chooseChannel(provider)
        val range = Compressor.selectCompressionRange(history)
        if (range.isEmpty()) return null
        log("抱朴引擎：压缩通道选择——${Compressor.channelNote(choice)}（真跑归刀 2）")
        return null
    }

    // ── spill（第 ⑤ 段配套） ──────────────────────────────────────────────

    /** spill 落盘：落工作区内 `.baopu-spill/`（read_file 够得着——范围读的前提）。 */
    private fun writeSpill(sessionId: Long, name: String, text: String): SpillResult? = runCatching {
        val dir = File(spillRoot(), ".baopu-spill")
        dir.mkdirs()
        val f = File(dir, "spill-s$sessionId-$name-${System.currentTimeMillis()}.txt")
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
     */
    private fun loadHistory(sessionId: Long): List<HistoryMessage> = db.read { d ->
        d.rawQuery(
            "SELECT message.id, message.role, part.kind, part.payload FROM message " +
                "LEFT JOIN part ON part.message_id = message.id " +
                "WHERE message.session_id = ? ORDER BY message.seq ASC, part.seq ASC",
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
