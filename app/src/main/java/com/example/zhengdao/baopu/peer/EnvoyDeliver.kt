// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十九批 · **使臣团的委派执行**（派工单 549 ③：A2A 委派必须过 GuardPipeline 的闸）。
//
// 入口形状与 548 的探针同一条法：「任何对外的请求在发出第一个字节之前必须先过闸」。
// 一次委派（[deliver]）的闸序——**顺序是判据**：
//   ⓪ 禁区闸（[SubAgentWhitelist.forbiddenAtEndpoint]）：端点里挂着子代理禁区清单 ⇒ 不发起；
//   ① 外发租约（`peer/delegate`）：管线批准这次委派 ⇒ 入口代记授予；闸未命中 ⇒ 一个字节不发；
//   ② 拉卡（第一段被授权的字节）；卡到手再过一次禁区闸（工具/能力两类，[SubAgentWhitelist.forbiddenOnCard]）；
//   ③ 准入闸（[EnvoyAdmission.ensure]）：没考过/没画像/能力指纹变了重考——**Reject ⇒ 一件活不派**；
//   ④ 档位闸：写类活（writes=true）只有 FULL 档接（READ_ONLY 档是降权不是禁用，读活照派）；
//   ⑤ 发活 → 五条流迁移（[EnvoyTrack]）→ 收场结算 ＋ 事件账 ＋ 租约账。
//
// 第六百四十八批两条改动都在这条闸序上，都不动 549 的状态机语义：
//   · ⓪/② 那两道**禁区闸**（G-572-3）：572 取证的事实是「清单在、委派路径没查它」——
//     本类不自建清单，只调子代理那份判据（[SubAgentWhitelist]），命中 ⇒ 拒 ＋ 一行 `gate/refused`
//     账（要说清命中哪条）＋ 零字节。
//   · [exam]（G-572-4）：准入考试的**独立生产入口**——把上面 ⓪①② 走完，到 ③ 那一格
//     换 [EnvoyAdmission.retest]（真考、旧卷不作数），一件任务字节不发。
//
// 「不许另开 agent 专用通道」的结构保证：本类没有任何直接暴露给模型/外部调用的口——
// 唯一的生产入口是 [EnvoyTool.spec] 的执行体，而 spec 只从注册表经 GuardPipeline 到达
// （停机闸/授权闸/租约闸都在管线里跑完才轮到执行体）。本类只做"闸后的下半段"。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.agent.multiagent.SubAgentWhitelist
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

/**
 * 单独出一场准入考（648 §二 G-572-4）：只给 endpoint——**不派活**，考卷之外的任务字节一个不发。
 * [grantOnApproval]／[examChannelOf] 与 [EnvoyRequest] 同义（负例可注入"没人批"与"没通道"）。
 */
internal data class EnvoyExamRequest(
    val endpoint: String,
    val sessionId: Long,
    val root: File?,
    val credential: A2aCredential? = null,
    val grantOnApproval: Boolean = true,
    val examChannelOf: (A2aClient, A2aCard) -> ExamChannel? = { c, card -> A2aExamChannel(c, card) },
)

/** 一场考试的收场（档位与结论都在 [admission] 里，本类不重判）。 */
internal data class EnvoyExamOutcome(
    val opId: String,
    val endpoint: String,
    /** 准入账上的稳定身份（`ag-…`；闸拒在前 ⇒ 没算出来就是空）。 */
    val agentId: String,
    /** 闸拒的人话（非 null ⇒ 考卷字节一个没发）。 */
    val refusal: String?,
    /** 线上失败的人话（非 null ⇒ 拉卡/出题路上断了）。 */
    val failure: String?,
    val admission: EnvoyAdmissionReading?,
) {
    /** 这一场真考成了没有（考出 FAIL 也叫考成——判决是考卷的事，不是这里的事）。 */
    val examined: Boolean get() = refusal == null && failure == null && admission?.examined == true
}

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
    /**
     * 第六百四十四批 ② 生效值消费缝：路由解析口（装配层注入 `RouteEvo::resolveEndpoint`）——
     * 生效路由行的偏好 peer 在名册上 ⇒ 返回它的 endpoint（请求真发往新 peer）；不挪 ⇒ null。
     * null 注入（缺省）＝未接进化路由，行为与接线前逐字节一致。
     */
    private val routeOf: ((requested: String) -> String?)? = null,
) {

    private val seq = AtomicLong(0)

    /**
     * 派一件活（完整闸序：**路由 → 禁区（端点） → 租约 → 卡 → 禁区（工具/能力） → 准入 → 档位 → 发送/轮询 → 结算**）。
     *
     * 第六百四十四批 ②：路由消费缝在最前——生效路由行的偏好 peer 在名册上 ⇒ 请求真发往新 peer，
     * 之后每一道闸（租约/卡/准入/对端账）都走**实际发往的 endpoint**（账面上的 endpoint 也是它）。
     * 只挪**新派活**；[reply]/[cancel] 续既有任务——任务住在原 peer 上，挪了就找不到它。
     *
     * 第六百四十八批 ①：禁区闸分两段——端点那一段在租约之前（命中 ⇒ 连卡都不拉），
     * 工具/能力那一段在准入考试之前（命中 ⇒ 连考卷都不发）。两段都在发任务字节之前。
     */
    fun deliver(req: EnvoyRequest, nowMs: Long = System.currentTimeMillis()): EnvoyOutcome {
        val opId = newOpId(nowMs)
        val track = EnvoyTrack("")
        val endpoint = routeOf?.invoke(req.endpoint)
            ?.takeIf { AgentIds.normalize(it) != AgentIds.normalize(req.endpoint) }
            ?.also { log("[envoy] 路由生效：${req.endpoint} → $it（进化路由行）") }
            ?: req.endpoint
        forbiddenAtEndpoint(endpoint)?.let { hit ->
            return forbiddenOutcome(opId, endpoint, track, hit, grant = null, nowMs = nowMs)
        }
        val (grant, refused) = authorizeOrRefuse(opId, endpoint, req.sessionId, req.grantOnApproval, track, nowMs)
        if (grant == null) return refusalOutcome(opId, endpoint, track, refused.orEmpty())
        val client = A2aClient(endpoint, http, log, req.credential)
        val card = try {
            client.fetchCard()
        } catch (e: A2aException) {
            return lineFailed(opId, endpoint, grant, track, e.failureCode, e.message.orEmpty(), nowMs)
        }
        forbiddenOnCard(card)?.let { hit ->
            return forbiddenOutcome(opId, endpoint, track, hit, grant, nowMs)
        }
        val admission = EnvoyAdmission.ensure(
            root = req.root,
            endpoint = endpoint,
            executorVersion = card.capabilityFingerprint(),
            channel = req.examChannelOf(client, card),
            claimed = card.skills.map { it.id },
            nowMs = nowMs,
            log = log,
        )
        if (!admission.admitted) {
            return admissionRefused(opId, endpoint, grant, track, admission, nowMs)
        }
        val kind = if (req.writes) TaskKind.WRITES else TaskKind.READ_ONLY
        if (!admission.allows(kind)) {
            return admissionRefused(opId, endpoint, grant, track, admission, nowMs, kindRefusal(admission, kind))
        }
        val task = try {
            client.messageSend(card, req.task)
        } catch (e: A2aException) {
            return lineFailed(opId, endpoint, grant, track, e.failureCode, e.message.orEmpty(), nowMs)
        }
        track.taskId = task.id
        if (!track.observe(task.state, EnvoyAction.SEND)) {
            return anomaly(opId, endpoint, grant, track, task, nowMs)
        }
        return settlePoll(
            opId, endpoint, grant, track, admission, client, card, task,
            PollKnobs(req.pollEveryMs, req.maxWaitMs, req.sleeper), nowMs,
        )
    }

    /** 回一条话续任务（input-required 的回复回路）：同一 taskId，闸照过一遍。 */
    fun reply(req: EnvoyReply, nowMs: Long = System.currentTimeMillis()): EnvoyOutcome {
        val opId = newOpId(nowMs)
        val track = req.track ?: EnvoyTrack(req.taskId)
        // 648 §一：续话也是**新发一批任务字节**，禁区照查（取消不查——那是逃生口，挡了就出不来）。
        forbiddenAtEndpoint(req.endpoint)?.let { hit ->
            return forbiddenOutcome(opId, req.endpoint, track, hit, grant = null, nowMs = nowMs)
        }
        val (grant, refused) = authorizeOrRefuse(opId, req.endpoint, req.sessionId, req.grantOnApproval, track, nowMs)
        if (grant == null) return refusalOutcome(opId, req.endpoint, track, refused.orEmpty())
        val client = A2aClient(req.endpoint, http, log, req.credential)
        val card = try {
            client.fetchCard()
        } catch (e: A2aException) {
            return lineFailed(opId, req.endpoint, grant, track, e.failureCode, e.message.orEmpty(), nowMs)
        }
        forbiddenOnCard(card)?.let { hit ->
            return forbiddenOutcome(opId, req.endpoint, track, hit, grant, nowMs)
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

    /**
     * 648 ② · 单独出一场准入考（G-572-4 的**独立生产入口**：不必等首次委派）。
     *
     * 闸序与 [deliver] 同一条法：端点禁区 → 租约（**外发即租约**：考卷也是字节，也要批）→
     * 拉卡 → 卡禁区 → 准入那一格换 [EnvoyAdmission.retest]（真考，旧卷不作数）→ 结算。
     * 本方法一件任务都不派：`message/send` 一次都不发，账上落的是 `peer.exam` 那三行租约账，
     * 考卷与结论落既有的 `准入出题账.jsonl` ＋ `准入账.jsonl`（派工单「不新造第二本账」）。
     */
    fun exam(req: EnvoyExamRequest, nowMs: Long = System.currentTimeMillis()): EnvoyExamOutcome {
        val opId = newOpId(nowMs)
        val track = EnvoyTrack("")
        forbiddenAtEndpoint(req.endpoint)?.let { hit ->
            forbiddenOutcome(opId, req.endpoint, track, hit, grant = null, nowMs, EXAM_LEDGER_TOOL)
            return examOutcome(opId, req.endpoint, null, refusal = hit.text, failure = null)
        }
        val (grant, refused) = authorizeOrRefuse(
            opId, req.endpoint, req.sessionId, req.grantOnApproval, track, nowMs, what = "出题",
        )
        if (grant == null) return examOutcome(opId, req.endpoint, null, refusal = refused, failure = null)
        val client = A2aClient(req.endpoint, http, log, req.credential)
        val card = try {
            client.fetchCard()
        } catch (e: A2aException) {
            gate.settle(grant, e.failureCode.wire, EXAM_LEDGER_TOOL, nowMs)
            return examOutcome(opId, req.endpoint, null, refusal = null, failure = e.message.orEmpty())
        }
        forbiddenOnCard(card)?.let { hit ->
            forbiddenOutcome(opId, req.endpoint, track, hit, grant, nowMs, EXAM_LEDGER_TOOL)
            return examOutcome(opId, req.endpoint, null, refusal = hit.text, failure = null)
        }
        val reading = EnvoyAdmission.retest(
            root = req.root,
            endpoint = req.endpoint,
            executorVersion = card.capabilityFingerprint(),
            channel = req.examChannelOf(client, card),
            claimed = card.skills.map { it.id },
            nowMs = nowMs,
            log = log,
        )
        gate.settle(grant, PeerFailureCode.OK.wire, EXAM_LEDGER_TOOL, nowMs)
        return examOutcome(opId, req.endpoint, reading, refusal = null, failure = null)
    }

    private fun examOutcome(
        opId: String,
        endpoint: String,
        reading: EnvoyAdmissionReading?,
        refusal: String?,
        failure: String?,
    ): EnvoyExamOutcome = EnvoyExamOutcome(
        opId = opId,
        endpoint = endpoint,
        agentId = reading?.agentId.orEmpty(),
        refusal = refusal,
        failure = failure,
        admission = reading,
    )

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
        /** 日志里说这是哪一路外发（委派／出题）——账的形状一样，嘴上的名字不能混。 */
        what: String = "委派",
    ): Pair<OutboundGrant?, String?> {
        if (grantOnApproval) LeaseOutboundGate.grantDelegateLease(endpoint, sessionId, opId, nowMs)
        return when (val d = gate.authorize(endpoint, opId, sessionId, nowMs, LeaseOutboundGate.DELEGATE_CLS)) {
            is GateDecision.Approved -> d.grant to null
            is GateDecision.Refused -> {
                PeerEventsLedger.append(PeerEventKinds.GATE, PeerFailureCode.REFUSED.wire, endpoint, "租约：${d.reason}", nowMs)
                log("[envoy] $what 未发起（租约闸）：${d.reason}")
                null to d.reason
            }
        }
    }

    /**
     * 648 ① · 端点那一档的禁区扫描（委派/出题**出发前**，租约都还没问）。
     * 清单与判定都不在这里：只调子代理那一份 [SubAgentWhitelist]（派工单：两条路径同一份）。
     */
    private fun forbiddenAtEndpoint(endpoint: String): SubAgentWhitelist.ForbiddenHit? =
        SubAgentWhitelist.forbiddenAtEndpoint(endpoint)

    /**
     * 648 ① · 卡上那两档（工具/能力）的禁区扫描——**拉到卡之后、准入考试之前**。
     * 查 id 也查 name：对端把禁区工具换个 id 挂上来，名字仍要说真话（换皮是这条路唯一走得通的绕法）。
     */
    private fun forbiddenOnCard(card: A2aCard): SubAgentWhitelist.ForbiddenHit? = SubAgentWhitelist.forbiddenOnCard(
        skillIds = card.skills.map { it.id },
        skillNames = card.skills.map { it.name },
        capabilities = card.capabilities,
    )

    /**
     * 禁区闸拒的收场（派工单 648 一②：**要落账并说清命中哪条**）。
     *
     * [grant] 非 null＝已过租约闸（卡那一档才命中）⇒ 租约照结算成 refused，账上两行配得上；
     * null＝端点那一档，租约还没问 ⇒ 无租约可结。两种都一个字节不发。
     */
    private fun forbiddenOutcome(
        opId: String,
        endpoint: String,
        track: EnvoyTrack,
        hit: SubAgentWhitelist.ForbiddenHit,
        grant: OutboundGrant?,
        nowMs: Long,
        tool: String = LEDGER_TOOL,
    ): EnvoyOutcome {
        grant?.let { gate.settle(it, PeerFailureCode.REFUSED.wire, tool, nowMs) }
        PeerEventsLedger.append(PeerEventKinds.GATE, PeerFailureCode.REFUSED.wire, endpoint, hit.text, nowMs)
        log("[envoy] ${hit.text}（${hit.cls.wire}·清单＝SubAgentWhitelist 那一份禁区项，委派侧不自带清单）")
        return EnvoyOutcome(
            opId = opId, endpoint = endpoint, taskId = "", state = A2aTaskState.UNKNOWN,
            track = track, artifacts = emptyList(), statusMessage = "", timedOut = false,
            refusal = "${hit.text}——子代理禁区清单与委派用的是同一份，这一件不派",
            failure = null, admission = null,
        )
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

        /**
         * 648 ② · **单独出题**在账面上的工具名（同一本租约账、同一类目 `peer/delegate`，
         * 只换 `tool` 那一格的值——出题与派活是两次外发，账上要分得清是哪一次花的字节）。
         */
        const val EXAM_LEDGER_TOOL = "peer.exam"

        /** 委派的默认轮询节奏（与 548 客户端的轮询同量级）。 */
        const val POLL_EVERY_MS = 1_000L

        /** 一次委派的默认轮询上限。 */
        const val MAX_WAIT_MS = 120_000L
    }
}
