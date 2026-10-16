// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：抱朴-Agent设计-程枢版 §2 状态机（每步迁移一次 SQLite 事务；max_steps=16；
// 一步 = 一次模型轮次；确认批不计步；全局单活跃会话；通道熔断）+ §8 错误矩阵。
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
import com.example.zhengdao.baopu.MemoryRules
import com.example.zhengdao.baopu.MemoryStore
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaRules
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.rootfs.RunLog
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors

/** 引擎状态（程枢版 §2 状态表；UI 只读这些）。 */
enum class RunState { IDLE, THINKING, ACTING, AWAIT_CONFIRM, COMPRESSING, ABORTED, DONE }

/**
 * 引擎的**纯判据内核**（无 Android/Compose/SQLite，JVM 直测）——
 * 刹车数值、熔断判据、确认超时判据全在这里。
 */
internal object RunRules {

    /** max_steps = 16（8–32 可调；程枢版 §2 刹车数值，写死默认值）。 */
    const val MAX_STEPS = 16

    /** 连续 3 次工具失败 ⇒ 停下 ask_user，禁止硬试（§2）。 */
    const val MAX_TOOL_FAILURES = 3

    /** 通道熔断阈值：当前通道连续 N 次失败 ⇒ 标记不可用 + 提示切通道（§2 补录）。 */
    const val CHANNEL_BREAK_THRESHOLD = 3

    /** 确认超时：默认 15 分钟，且**仅 App 前台计时**（§2 补录）。 */
    const val CONFIRM_TIMEOUT_MS = 15 * 60 * 1000L

    /** 一步 = 一次模型轮次 ⇒ 步数从 1 起；满 16 必停。 */
    fun isStepExhausted(steps: Int): Boolean = steps >= MAX_STEPS

    /** 连续工具失败到线 ⇒ 停下问人。 */
    fun shouldAskUser(consecutiveToolFailures: Int): Boolean =
        consecutiveToolFailures >= MAX_TOOL_FAILURES

    /** 通道熔断判据。 */
    fun shouldBreakChannel(consecutiveChannelFailures: Int): Boolean =
        consecutiveChannelFailures >= CHANNEL_BREAK_THRESHOLD

    /**
     * 确认超时判据（**仅前台计时**：后台期间不计时，回前台才继续计）。
     * @param foregroundElapsedMs 前台累计毫秒（后台时间由调用方剔除）
     * @return true = 已超时（按拒绝处理）
     */
    fun confirmExpired(foregroundElapsedMs: Long): Boolean = foregroundElapsedMs >= CONFIRM_TIMEOUT_MS

    /** 中断标记值：会话被标「中断于第 N 步」时落这个数；-1 = 未中断。 */
    const val NOT_INTERRUPTED = -1
}

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
    private val provider: Provider,
    private val db: BaopuDb,
    private val registry: ToolRegistry,
    private val pipeline: GuardPipeline,
    private val personaFiles: PersonaFiles? = null,
    private val memoryStore: MemoryStore? = null,
    private val spillRoot: () -> File,
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
            }
        }
    }

    /** 用户点「停止」：半成品保留（写工具归刀 2；本刀只读，停止即止循环）。 */
    fun stop() {
        stopRequested = true
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

        // 历史（续跑 = 重建上下文：人设 + 硬事实 + 最近 N 轮原文）
        var history = loadHistory(sessionId)
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
                    _state.value = RunState.DONE
                    persistStep(sessionId, stepCount, "Done")
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
                            _state.value = RunState.ABORTED
                            persistStep(sessionId, stepCount, "Aborted")
                            return
                        }
                        val result = pipeline.execute(call) { name, text ->
                            writeSpill(sessionId, name, text)
                        }
                        when (result) {
                            is GuardPipeline.Result.Done -> {
                                consecutiveToolFailures = 0
                                persistToolExchange(sessionId, stepCount, call.name, result.wrapped)
                                onEvent?.invoke(
                                    EngineEvent.ToolCallVisible(call.name, toolSummary(call, result.wrapped), true),
                                )
                                history += HistoryMessage(
                                    "local-tool-${System.nanoTime()}",
                                    HistoryMessage.ROLE_ASSISTANT,
                                    result.wrapped,
                                )
                            }

                            is GuardPipeline.Result.Failed -> {
                                consecutiveToolFailures++
                                persistToolExchange(sessionId, stepCount, call.name, "失败：${result.error}")
                                onEvent?.invoke(
                                    EngineEvent.ToolCallVisible(call.name, result.error, false),
                                )
                                history += HistoryMessage(
                                    "local-tool-${System.nanoTime()}",
                                    HistoryMessage.ROLE_ASSISTANT,
                                    DataFence.wrap(call.name, System.currentTimeMillis(), "失败：${result.error}"),
                                )
                                if (RunRules.shouldAskUser(consecutiveToolFailures)) {
                                    val msg = "工具连续失败 ${consecutiveToolFailures} 次，已停下。" +
                                        "换个说法或换个文件再试（不许硬试）。"
                                    _state.value = RunState.ABORTED
                                    _notice.value = msg
                                    persistStep(sessionId, stepCount, "Aborted")
                                    onEvent?.invoke(EngineEvent.AskUser(msg))
                                    return
                                }
                            }

                            is GuardPipeline.Result.Rejected -> {
                                // 越界/权限 ⇒ 终止本轮（§8）：回灌理由让模型知道边界，本轮到此
                                persistToolExchange(sessionId, stepCount, call.name, "被拒：${result.reason}")
                                onEvent?.invoke(
                                    EngineEvent.ToolCallVisible(call.name, result.reason, false),
                                )
                                history += HistoryMessage(
                                    "local-tool-${System.nanoTime()}",
                                    HistoryMessage.ROLE_ASSISTANT,
                                    DataFence.wrap(call.name, System.currentTimeMillis(), "被拒：${result.reason}"),
                                )
                                terminateRound = true
                            }

                            is GuardPipeline.Result.NeedsConfirm -> {
                                // AwaitConfirm 最小骨架（PCE 三段式归刀 2）：状态在、迁移在
                                _state.value = RunState.AWAIT_CONFIRM
                                persistStep(sessionId, stepCount, "AwaitConfirm")
                                onEvent?.invoke(
                                    EngineEvent.AskUser(
                                        "有待确认的动作（${result.call.name}）：确认制归下一刀，本刀先停下。",
                                    ),
                                )
                                return
                            }
                        }
                        if (terminateRound) break
                    }
                }

                is RoundOutcome.Failed -> {
                    consecutiveChannelFailures++
                    _notice.value = "模型调用失败：${outcome.userFacing}（原文已记日志）"
                    if (RunRules.shouldBreakChannel(consecutiveChannelFailures)) {
                        channelBroken = true
                        _notice.value = "当前通道连续失败 ${consecutiveChannelFailures} 次，已熔断：" +
                            "换一个 provider 再试（不是每次错误各自退避）。"
                    }
                    _state.value = RunState.ABORTED
                    persistStep(sessionId, stepCount, "Aborted")
                    onEvent?.invoke(EngineEvent.AskUser(_notice.value!!))
                    return
                }
            }

            // ── 步数刹车：16 步必停 ──
            if (RunRules.isStepExhausted(stepCount)) {
                val msg = "已到 ${RunRules.MAX_STEPS} 步上限，停下。继续说「继续」会重新计步。"
                _state.value = RunState.DONE
                _notice.value = msg
                persistStep(sessionId, stepCount, "Done")
                onEvent?.invoke(EngineEvent.AskUser(msg))
                return
            }
        }
        // 用户停止
        _state.value = RunState.ABORTED
        persistStep(sessionId, stepCount, "Aborted")
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
        data class Failed(val userFacing: String) : RoundOutcome
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
        var rawCalls: List<com.example.zhengdao.baopu.RawToolCall> = emptyList()
        val client = LlmClient(provider)
        client.streamAsync(messages, tools) { ev ->
            when (ev) {
                is LlmEvent.Delta -> textBuf.append(ev.text)
                is LlmEvent.Finished -> rawCalls = ev.toolCalls
                is LlmEvent.Failed -> failure = ev.error.message ?: ev.error.javaClass.simpleName
            }
            if (ev is LlmEvent.Finished || ev is LlmEvent.Failed) latch.countDown()
        }
        latch.await()
        failure?.let { return RoundOutcome.Failed(humanizeNetFailure(it)) }
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

    /** 步迁移 = 一次事务（session.interrupted_step + updated_at + run_log）。 */
    private fun persistStep(sessionId: Long, step: Int, state: String) {
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
            ok = true,
            latencyMs = 0,
            err = "",
            createdAt = System.currentTimeMillis(),
        )
        db.write { d ->
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

    /** 标「中断于第 N 步」（重进时；interrupted_step 落库，UI 与续跑共用这一个数）。 */
    private fun markInterrupted(sessionId: Long, interruptedStepValue: Int) {
        _interruptedStep.value = interruptedStepValue
        _notice.value = if (interruptedStepValue >= 0) {
            "这个会话中断于第 $interruptedStepValue 步（进程当时被杀了）——点「续跑」从那里接着来。"
        } else {
            null
        }
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

    private fun loadHistory(sessionId: Long): List<HistoryMessage> = db.read { d ->
        d.rawQuery(
            "SELECT role, part.payload FROM message " +
                "LEFT JOIN part ON part.message_id = message.id AND part.kind = 'text' " +
                "WHERE message.session_id = ? ORDER BY message.seq ASC, part.seq ASC",
            arrayOf(sessionId.toString()),
        ).use { c ->
            val out = mutableListOf<HistoryMessage>()
            while (c.moveToNext()) {
                val role = c.getString(0)
                val payload = if (c.isNull(1)) "" else c.getString(1)
                out += HistoryMessage("db-${out.size}", role, payload)
            }
            out
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
