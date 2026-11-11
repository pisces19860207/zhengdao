// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十九批 · **使臣团的委派执行**（派工单 549 ③：A2A 委派必须过 GuardPipeline 的闸）。
//
// 入口形状与 548 的探针同一条法：「任何对外的请求在发出第一个字节之前必须先过闸」。
// 一次委派（[deliver]）的闸序——**顺序是判据**：
//   ① 外发租约（`peer/delegate`）：管线批准这次委派 ⇒ 入口代记授予；闸未命中 ⇒ 一个字节不发；
//   ② 拉卡（第一段被授权的字节）；
//   ③ 准入闸（[EnvoyAdmission.ensure]）：没考过/没画像/能力指纹变了重考——**Reject ⇒ 一件活不派**；
//   ④ 档位闸：写类活（writes=true）只有 FULL 档接（READ_ONLY 档是降权不是禁用，读活照派）；
//   ⑤ 发活 → 五条流迁移（[EnvoyTrack]）→ 收场结算 ＋ 事件账 ＋ 租约账。
//
// 「不许另开 agent 专用通道」的结构保证：本类没有任何直接暴露给模型/外部调用的口——
// 唯一的生产入口是 [EnvoyTool.spec] 的执行体，而 spec 只从注册表经 GuardPipeline 到达
// （停机闸/授权闸/租约闸都在管线里跑完才轮到执行体）。本类只做"闸后的下半段"。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.intake.Clearance
import com.example.zhengdao.baopu.intake.ExamChannel
import com.example.zhengdao.baopu.intake.TaskKind
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import okhttp3.OkHttpClient

/** 一次派活的请求（闸要的事实全在出发前给齐；[grantOnApproval]=false 模拟"没人批"）。 */
internal data class EnvoyRequest(
    val endpoint: String,
    val task: String,
    /** 这活要不要对方动它的盘/外部资源（true=写类活：只有 FULL 档接）。 */
    val writes: Boolean = false,
    val sessionId: Long,
    /** 准入账落点（null＝没装配 ⇒ 准入闸 fail-closed 不放行）。 */
    val root: File?,
    val credential: A2aCredential? = null,
    /** 管线批准这次委派 ⇒ 入口代记授予一张真租约；false＝没人批（负例用）。 */
    val grantOnApproval: Boolean = true,
    /** 出题通道工厂（默认 A2A 真通道；负例可给 null＝没通道 ⇒ 空卷 UNVERIFIABLE ⇒ 门不开）。 */
    val examChannelOf: (A2aClient, A2aCard) -> ExamChannel? = { c, card -> A2aExamChannel(c, card) },
    val pollEveryMs: Long = EnvoyDeliver.POLL_EVERY_MS,
    val maxWaitMs: Long = EnvoyDeliver.MAX_WAIT_MS,
    val sleeper: (Long) -> Boolean = { ms -> runCatching { Thread.sleep(ms) }.isSuccess },
)

/** input-required 的回复：**续同一个任务**（带原 taskId 回一条话），不是重开。 */
internal data class EnvoyReply(
    val endpoint: String,
    val taskId: String,
    val text: String,
    val sessionId: Long,
    val root: File?,
    val credential: A2aCredential? = null,
    val grantOnApproval: Boolean = true,
    /** 续 [EnvoyDeliver.deliver] 带出来的那条轨迹（null＝新开一条记账）。 */
    val track: EnvoyTrack? = null,
    val pollEveryMs: Long = EnvoyDeliver.POLL_EVERY_MS,
    val maxWaitMs: Long = EnvoyDeliver.MAX_WAIT_MS,
    val sleeper: (Long) -> Boolean = { ms -> runCatching { Thread.sleep(ms) }.isSuccess },
)

/** 一次委派/续话/取消的收场。 */
internal data class EnvoyOutcome(
    val opId: String,
    val endpoint: String,
    val taskId: String,
    val state: A2aTaskState,
    val track: EnvoyTrack,
    val artifacts: List<A2aArtifact>,
    val statusMessage: String,
    val timedOut: Boolean,
    /** 非 null＝闸拒（任务字节一个没发；人话回给调用方）。 */
    val refusal: String?,
    /** 非 null＝字节发出去了但没走通（线上失败/状态机被讲乱的人话）。 */
    val failure: String?,
    val admission: EnvoyAdmissionReading?,
) {
    /** 任务字节发出去了没有（闸拒 ⇒ false）。 */
    val sent: Boolean get() = refusal == null

    /** 停下来要人（input-required / auth-required）——不是失败，回复可以续。 */
    val needsHuman: Boolean get() = sent && failure == null && state.needsHuman
}

internal class EnvoyDeliver(
    private val http: OkHttpClient,
    private val gate: PeerOutboundGate = PeerOutboundGate.LIVE,
    private val log: (String) -> Unit = {},
) {

    private val seq = AtomicLong(0)

    /** 派一件活（完整闸序：租约 → 卡 → 准入 → 档位 → 发送/轮询 → 结算）。 */
    fun deliver(req: EnvoyRequest, nowMs: Long = System.currentTimeMillis()): EnvoyOutcome {
        val opId = newOpId(nowMs)
        val track = EnvoyTrack("")
        val (grant, refused) = authorizeOrRefuse(opId, req.endpoint, req.sessionId, req.grantOnApproval, track, nowMs)
        if (grant == null) return refusalOutcome(opId, req.endpoint, track, refused.orEmpty())
        val client = A2aClient(req.endpoint, http, log, req.credential)
        val card = try {
            client.fetchCard()
        } catch (e: A2aException) {
            return lineFailed(opId, req.endpoint, grant, track, e.failureCode, e.message.orEmpty(), nowMs)
        }
        val admission = EnvoyAdmission.ensure(
            root = req.root,
            endpoint = req.endpoint,
            executorVersion = card.capabilityFingerprint(),
            channel = req.examChannelOf(client, card),
            claimed = card.skills.map { it.id },
            nowMs = nowMs,
            log = log,
        )
        if (!admission.admitted) {
            return admissionRefused(opId, req.endpoint, grant, track, admission, nowMs)
        }
        val kind = if (req.writes) TaskKind.WRITES else TaskKind.READ_ONLY
        if (!admission.allows(kind)) {
            return admissionRefused(opId, req.endpoint, grant, track, admission, nowMs, kindRefusal(admission, kind))
        }
        val task = try {
            client.messageSend(card, req.task)
        } catch (e: A2aException) {
            return lineFailed(opId, req.endpoint, grant, track, e.failureCode, e.message.orEmpty(), nowMs)
        }
        track.taskId = task.id
        if (!track.observe(task.state, EnvoyAction.SEND)) {
            return anomaly(opId, req.endpoint, grant, track, task, nowMs)
        }
        return settlePoll(
            opId, req.endpoint, grant, track, admission, client, card, task,
            PollKnobs(req.pollEveryMs, req.maxWaitMs, req.sleeper), nowMs,
        )
    }

    /** 回一条话续任务（input-required 的回复回路）：同一 taskId，闸照过一遍。 */
    fun reply(req: EnvoyReply, nowMs: Long = System.currentTimeMillis()): EnvoyOutcome {
        val opId = newOpId(nowMs)
        val track = req.track ?: EnvoyTrack(req.taskId)
        val (grant, refused) = authorizeOrRefuse(opId, req.endpoint, req.sessionId, req.grantOnApproval, track, nowMs)
        if (grant == null) return refusalOutcome(opId, req.endpoint, track, refused.orEmpty())
        val client = A2aClient(req.endpoint, http, log, req.credential)
        val card = try {
            client.fetchCard()
        } catch (e: A2aException) {
            return lineFailed(opId, req.endpoint, grant, track, e.failureCode, e.message.orEmpty(), nowMs)
        }
        // 续话不重考：画像在且能力指纹对得上 ⇒ ensure 直接给读数（零考卷字节）；真变了下轮派活再考。
        val admission = EnvoyAdmission.ensure(
            root = req.root,
            endpoint = req.endpoint,
            executorVersion = card.capabilityFingerprint(),
            channel = null,
            claimed = emptyList(),
            nowMs = nowMs,
            log = log,
        )
        if (!admission.admitted) {
            return admissionRefused(opId, req.endpoint, grant, track, admission, nowMs)
        }
        val task = try {
            client.messageSend(card, req.text, req.taskId)
        } catch (e: A2aException) {
            return lineFailed(opId, req.endpoint, grant, track, e.failureCode, e.message.orEmpty(), nowMs)
        }
        track.taskId = task.id.ifBlank { req.taskId }
        if (!track.observe(task.state, EnvoyAction.REPLY)) {
            return anomaly(opId, req.endpoint, grant, track, task, nowMs)
        }
        return settlePoll(
            opId, req.endpoint, grant, track, admission, client, card, task,
            PollKnobs(req.pollEveryMs, req.maxWaitMs, req.sleeper), nowMs,
        )
    }

    /**
     * 取消一件委派中的活（tasks/cancel；幂等——再取消不炸，终态粘住）。
     * [track] 传 [deliver] 带出来的那条 ⇒ 取消这步接在同一条轨迹上（账上是一条完整的流）。
     */
    fun cancel(
        endpoint: String,
        taskId: String,
        sessionId: Long,
        root: File?,
        nowMs: Long = System.currentTimeMillis(),
        track: EnvoyTrack? = null,
    ): EnvoyOutcome {
        val opId = newOpId(nowMs)
        val line = track ?: EnvoyTrack(taskId)
        val (grant, refused) = authorizeOrRefuse(opId, endpoint, sessionId, grantOnApproval = true, line, nowMs)
        if (grant == null) return refusalOutcome(opId, endpoint, line, refused.orEmpty())
        val client = A2aClient(endpoint, http, log)
        val card = try {
            client.fetchCard()
        } catch (e: A2aException) {
            return lineFailed(opId, endpoint, grant, line, e.failureCode, e.message.orEmpty(), nowMs)
        }
        val task = try {
            client.taskCancel(card, taskId)
        } catch (e: A2aException) {
            return lineFailed(opId, endpoint, grant, line, e.failureCode, e.message.orEmpty(), nowMs)
        }
        if (!line.observe(task.state, EnvoyAction.CANCEL)) {
            return anomaly(opId, endpoint, grant, line, task, nowMs)
        }
        return settled(opId, endpoint, grant, line, null, task, timedOut = false, failure = null, nowMs)
    }

    // ── 内部：闸后的下半段（每段一件事，没有一段过 60 行）─────────────────────────

    /**
     * ① 外发租约：先代记授予（管线批准 ⇒ 真租约），再过闸。
     * 返回 (grant, null)＝放行；(null, 拒因)＝闸拒——拒因原样给调用方（548 探针同款：拒了要有具体的话）。
     * 撤销/过期/用尽/越 host 都在这现判——批过不等于现在还批着。
     */
    private fun authorizeOrRefuse(
        opId: String,
        endpoint: String,
        sessionId: Long,
        grantOnApproval: Boolean,
        track: EnvoyTrack,
        nowMs: Long,
    ): Pair<OutboundGrant?, String?> {
        if (grantOnApproval) LeaseOutboundGate.grantDelegateLease(endpoint, sessionId, opId, nowMs)
        return when (val d = gate.authorize(endpoint, opId, sessionId, nowMs, LeaseOutboundGate.DELEGATE_CLS)) {
            is GateDecision.Approved -> d.grant to null
            is GateDecision.Refused -> {
                PeerEventsLedger.append(PeerEventKinds.GATE, PeerFailureCode.REFUSED.wire, endpoint, "租约：${d.reason}", nowMs)
                log("[envoy] 委派未发起（租约闸）：${d.reason}")
                null to d.reason
            }
        }
    }

    /** 轮询到停（终态/要人/超时/喊停/线上失败），每一步都过五条流的法条。 */
    private fun settlePoll(
        opId: String,
        endpoint: String,
        grant: OutboundGrant,
        track: EnvoyTrack,
        admission: EnvoyAdmissionReading?,
        client: A2aClient,
        card: A2aCard,
        start: A2aTask,
        knobs: PollKnobs,
        nowMs: Long,
    ): EnvoyOutcome {
        var task = start
        val deadline = System.currentTimeMillis() + knobs.maxWaitMs
        while (!task.state.terminal && !task.state.needsHuman) {
            if (System.currentTimeMillis() >= deadline) {
                return settled(opId, endpoint, grant, track, admission, task, timedOut = true, failure = null, nowMs)
            }
            if (!knobs.sleeper(knobs.pollEveryMs)) {
                return settled(opId, endpoint, grant, track, admission, task, timedOut = false, failure = null, nowMs)
            }
            val next = try {
                client.taskGet(card, task.id)
            } catch (e: A2aException) {
                return lineFailed(opId, endpoint, grant, track, e.failureCode, e.message.orEmpty(), nowMs)
            }
            if (!track.observe(next.state, EnvoyAction.POLL)) {
                return anomaly(opId, endpoint, grant, track, next, nowMs)
            }
            task = next
        }
        return settled(opId, endpoint, grant, track, admission, task, timedOut = false, failure = null, nowMs)
    }

    /** 闸拒的收场（零任务字节；拒因是闸的原话）。 */
    private fun refusalOutcome(opId: String, endpoint: String, track: EnvoyTrack, why: String): EnvoyOutcome = EnvoyOutcome(
        opId = opId,
        endpoint = endpoint,
        taskId = "",
        state = A2aTaskState.UNKNOWN,
        track = track,
        artifacts = emptyList(),
        statusMessage = "",
        timedOut = false,
        refusal = "这次委派没有批准，没有发起（外发即租约）：$why",
        failure = null,
        admission = null,
    )

    /** 准入闸拒的收场（任务字节没发；考试的字节算准入的，不算派活的）。 */
    private fun admissionRefused(
        opId: String,
        endpoint: String,
        grant: OutboundGrant,
        track: EnvoyTrack,
        admission: EnvoyAdmissionReading,
        nowMs: Long,
        why: String = admission.human,
    ): EnvoyOutcome {
        gate.settle(grant, PeerFailureCode.REFUSED.wire, LEDGER_TOOL, nowMs)
        PeerEventsLedger.append(
            PeerEventKinds.GATE, PeerFailureCode.REFUSED.wire, endpoint,
            "准入（${admission.verdictWire}）：$why", nowMs,
        )
        log("[envoy] 委派未发起（准入闸）：${admission.verdictWire}")
        return EnvoyOutcome(
            opId = opId, endpoint = endpoint, taskId = "", state = A2aTaskState.UNKNOWN,
            track = track, artifacts = emptyList(), statusMessage = "", timedOut = false,
            refusal = why, failure = null, admission = admission,
        )
    }

    /** 写类活对不上档（READ_ONLY 档不接写活）的一句话。 */
    private fun kindRefusal(admission: EnvoyAdmissionReading, kind: TaskKind): String =
        if (kind == TaskKind.WRITES && admission.tier == Clearance.READ_ONLY) {
            "「${admission.agentId}」的准入档是只读（降权不是禁用）：写类活不派给它，读类活照派"
        } else {
            admission.human
        }

    /** 线上失败的收场（字节发出去了；租约照结算，账行按失败类落）。 */
    private fun lineFailed(
        opId: String,
        endpoint: String,
        grant: OutboundGrant,
        track: EnvoyTrack,
        code: PeerFailureCode,
        detail: String,
        nowMs: Long,
    ): EnvoyOutcome {
        gate.settle(grant, code.wire, LEDGER_TOOL, nowMs)
        PeerEventsLedger.append(
            PeerEventKinds.DELEGATE, code.wire, endpoint,
            "op=$opId trail=${track.wire()} $detail".trim(), nowMs,
        )
        return EnvoyOutcome(
            opId = opId, endpoint = endpoint, taskId = track.taskId, state = A2aTaskState.UNKNOWN,
            track = track, artifacts = emptyList(), statusMessage = "", timedOut = false,
            refusal = null, failure = detail, admission = null,
        )
    }

    /** 对端把状态机讲乱了（法条 false）：停手收场，归 protocol，不装没看见。 */
    private fun anomaly(
        opId: String,
        endpoint: String,
        grant: OutboundGrant,
        track: EnvoyTrack,
        task: A2aTask,
        nowMs: Long,
    ): EnvoyOutcome {
        val why = "对端的状态迁移对不上 A2A 状态机（${track.trail.lastOrNull()?.wire().orEmpty()}）——停手，不硬猜"
        return settled(opId, endpoint, grant, track, null, task, timedOut = false, failure = why, nowMs)
    }

    /** 唯一的正常收场：租约结算 ＋ 委派账行 ＋ 读数。 */
    private fun settled(
        opId: String,
        endpoint: String,
        grant: OutboundGrant,
        track: EnvoyTrack,
        admission: EnvoyAdmissionReading?,
        task: A2aTask,
        timedOut: Boolean,
        failure: String?,
        nowMs: Long,
    ): EnvoyOutcome {
        val code = when {
            failure != null -> PeerFailureCode.PROTOCOL
            timedOut -> PeerFailureCode.TIMEOUT
            else -> PeerFailureCode.OK
        }
        gate.settle(grant, code.wire, LEDGER_TOOL, nowMs)
        val note = task.statusMessage.takeIf { it.isNotBlank() }?.let { " note=$it" }.orEmpty()
        PeerEventsLedger.append(
            PeerEventKinds.DELEGATE, task.state.wire, endpoint,
            "task=${task.id} op=$opId trail=${track.wire()}$note", nowMs,
        )
        log("[envoy] 委派收场：${task.state.wire}（${track.wire()}）")
        return EnvoyOutcome(
            opId = opId, endpoint = endpoint, taskId = task.id, state = task.state,
            track = track, artifacts = task.artifacts, statusMessage = task.statusMessage,
            timedOut = timedOut, refusal = null, failure = failure, admission = admission,
        )
    }

    private fun newOpId(nowMs: Long): String = "ev-$nowMs-${seq.incrementAndGet()}"

    /** 轮询节奏（请求里带的）。 */
    private data class PollKnobs(val pollEveryMs: Long, val maxWaitMs: Long, val sleeper: (Long) -> Boolean)

    companion object {
        /** 委派在账面上的工具名（租约 use/outcome 行与事件账共用；与 peer.probe 同族）。 */
        const val LEDGER_TOOL = "peer.delegate"

        /** 委派的默认轮询节奏（与 548 客户端的轮询同量级）。 */
        const val POLL_EVERY_MS = 1_000L

        /** 一次委派的默认轮询上限。 */
        const val MAX_WAIT_MS = 120_000L
    }
}
