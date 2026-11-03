// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百九十六批（补问答案回灌通道）：379 留下的缺口——补问（静默期／检查点等待后的追问）
// **答案只落日志账，不回灌模型**（RunEngine.drainQuietQueue 的原话注释）。模型那一轮早已带着
// 「没人答」走完，她后来答的那一句在系统里只是一行日志，谁也不消费。
//
// 用户 2026-11-03 派工单原话：「要让答复有用，需要一条显式通道，而不是『偷偷塞进历史』。」
//
// 通道必须带三样，缺一不许回灌（派工单 §1）：
// ① **哪一轮的补问**（turnId＋askId）：答复挂到当时那一个问题上；挂错轮次 ⇒ 拒（判据②）；
// ② **答复算不算授权**（AUTH／INFO／REJECT 闭集）：只有 AUTH 才许改变原动作的可执行性；
// ③ **授权范围**（一次／本任务／到某时刻）：AUTH 必填，**不许「默认无限」** ⇒ 缺范围即拒（判据③）。
//
// 回灌形态：派工单 §2 给了 A／B 两条，本批走 **B（上下文补丁）**，理由写在这里而不是只写在报告里：
// · A（新消息注入）会把一次过期问答永久留在历史里——之后每一轮都重带一遍，模型有充分机会
//   照着「她批过了」去**重做那个动作**，而派工单 §4 明令「不做自动重试原动作（重试是另一次决策）」；
// · 「可读、可回放」这一头 A 的优势本批已经给到了：AUTH／REJECT 各进一行 [CardDisposition]
//   处置账（append-only、落 `抱朴记忆/卡处置.jsonl`），那才是「谁在这张卡上表过态」的账本位；
// · B 的寿命写死在形状里：**收到答复那一轮 ＋ 下一轮**（[AnswerReflow.DELIVERY_ROUNDS]），
//   到点自然退场，不污染历史，也不用等压缩来收拾它。
//
// 四条落在文件形状上的纪律：
// 1. **一个 askId 只吃一次答复**：校验通过的**同一个锁里**把它从待答清单摘掉（[pending]），
//    第二张同 askId 的答复判「已经回灌过 ⇒ 同一问不重复灌」——两个检查点（轮次入口 vs 工具
//    线程放行）同时递答案也只落一行账。「不许篡改历史」在本层的机械形状：既不改写已收下的
//    那条，也不允许对它追补第二条；
// 2. **只有 AUTH 能改可执行性**：INFO 不写授权（[executable] 照旧 false），REJECT 取消动作——
//    判据①「三种答复各有测试」钉的就是这一格；
// 3. **范围没有保守兜底**：[ReflowScope.ofWire] 认不出返回 null，由 [AnswerReflow.submit] 拒。
//    这里刻意与 [com.example.zhengdao.baopu.ConfirmScope.fromKey] 分家——那处认不出按「仅这一次」
//    兜底，是因为确认卡总得给出一个答案；本通道的范围是**必填项**，把「没给范围」兜底成「一次」
//    等于替她签收了一张她没批过的授权；
// 4. **本文件零 IO、不自己取时钟**：落账走 [CardDisposition]（它自己认落点、自己吞异常），
//    处置时刻由调用方给；房型照 330 的 [QuietCardGate]——纯逻辑＋一把内部锁，IO 与时钟归引擎。
//
// 与 379 的边界（判据⑤「不改免打扰语义」）：本文件**不判**该不该弹卡、不碰队列顺序、不碰静默
// 上限。它只在闸的两头各接一句：压下一张卡时登记它（[register]），她在检查点答了时收下它
// （[submit]）。压与放的全部判据仍在 [QuietCardGate]，一个字没动。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.CardDisposition

/**
 * 答复算不算授权（派工单 §1②，闭集三格；「既不算授权也不算取消」的第四格不存在）。
 *
 * 只有 [AUTH] 才许改变原动作的可执行性：[INFO] 只把话带到，[REJECT] 把动作取消。
 */
internal enum class ReflowKind(val wire: String, val cn: String) {
    /** 授权：原动作在她给的范围里可执行（要不要真做，仍是下一决策的事，见派工单 §4）。 */
    AUTH("auth", "授权"),

    /** 只补信息：模型看到她的答复，动作的可执行性一个字不变。 */
    INFO("info", "补信息"),

    /** 取消：原动作作废并落处置账。 */
    REJECT("reject", "取消"),
    ;

    companion object {
        /** 键 → 档位（认不出返回 null，由 [AnswerReflow.rejectReason] 拒；不静默猜一档）。 */
        fun ofWire(wire: String): ReflowKind? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 授权范围（派工单 §1③，闭集三格；AUTH 必填其一，「默认无限」这一档不存在）。
 *
 * 三档的**失效边界**（[AnswerReflow.liveGrant] 的惟一来源，别处不许再判一次）：
 * - [ONCE]：只覆盖一次——[AnswerReflow.useAuthorization] 取走即失效；
 * - [TASK]：本任务内有效——任务号换了即失效。任务号由引擎给，v1 与 330 免打扰授权同口径
 *   （`session:<会话号>`）；引擎哪天有真任务粒度，换喂法即可，三档语义不用改；
 * - [UNTIL]：到某时刻——到点失效，所以 [ReflowAnswer.untilMs] 必须是**将来**那一刻；
 *   给了过去或 0 就是「无限」的另一面，直接拒。
 */
internal enum class ReflowScope(val wire: String, val cn: String) {
    ONCE("once", "一次"),
    TASK("task", "本任务"),
    UNTIL("until", "到某时刻"),
    ;

    companion object {
        /**
         * 键 → 档位。认不出 ⇒ null（**不兜底**，理由见文件头纪律 3）。
         *
         * 空串同样走 null：AUTH 不带范围就是判据③拒掉的那一条。
         */
        fun ofWire(wire: String): ReflowScope? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一条答复（派工单 §1 的三样都在这里，缺一项 [AnswerReflow.submit] 拒收）。
 *
 * @param askId 哪一问：379 压下的那张卡的主键（[DeferredAsk.id]，`quiet:<时刻>#<序>`）
 * @param turnId 哪一轮问的：引擎开轮时盖的轮号（[com.example.zhengdao.baopu.authz.ActionStepClock]
 *        发的 roundId）。它是**问题**的身份，不是答复的时刻——答复到达那一轮另由调用方给
 * @param kind 算不算授权
 * @param scopeWire 授权范围键（[ReflowKind.AUTH] 必填；非 AUTH 却给了范围 ⇒ 形状错，也拒）
 * @param untilMs [ReflowScope.UNTIL] 的那一刻（必须是将来）
 * @param text 她答的话（选项 label／问题组答案；上下文补丁里引的就是这一句）
 */
internal data class ReflowAnswer(
    val askId: String,
    val turnId: Int,
    val kind: ReflowKind,
    val scopeWire: String = "",
    val untilMs: Long = 0L,
    val text: String = "",
)

/** 一张被压下、正等她答的卡（[AnswerReflow.register] 的产物；判据②的对照面）。 */
internal data class PendingAsk(
    val askId: String,
    val turnId: Int,
    val headline: String,
)

/** 一条已收下的授权（只有 AUTH 造得出；[AnswerReflow.executable] 按 [ReflowScope] 三档判）。 */
internal data class ReflowGrant(
    val askId: String,
    val scope: ReflowScope,
    val taskId: String,
    val untilMs: Long,
)

/**
 * 回灌的产物（闭集两格；「收下但不落账」的第三格不存在）。
 *
 * @param note 那条上下文补丁（[Rejected] 没有 note——没收下就不许进上下文）
 * @param dispositionRow 处置账那一行（INFO 不落账 ⇒ null；判据①的不对称由测试钉住）
 */
internal sealed class ReflowOutcome {
    abstract val askId: String

    /** 收下：已入上下文队列；授权／取消按 kind 落定。 */
    data class Accepted(
        override val askId: String,
        val kind: ReflowKind,
        val scope: ReflowScope?,
        val note: String,
        val dispositionRow: CardDisposition.CardDispositionRow?,
    ) : ReflowOutcome()

    /** 拒收：一句人话说清缺的是哪一样（派工单 §1「缺一不许回灌」）。 */
    data class Rejected(override val askId: String, val reason: String) : ReflowOutcome()
}

/**
 * 补问答案回灌通道（进程级单例；纯逻辑＋内存账，房型照 [AskBatchLedger]／[QuietCardGate]）。
 *
 * 三个口子，对应引擎里的三个接线位：
 * - [register]——379 把一张卡压进补问队列那一刻登记它（带当时的轮号）；
 * - [submit]——她答了（检查点补问收到答案／UI 批的三选一答复）；
 * - [notes]——拼下一轮上下文时取走补丁（派工单 §2B「下轮拼上下文时带上」）。
 *
 * 为什么只住内存不落库：与 [AskBatchLedger] 同一条保守判断——落库会把「她答过什么」变成
 * 跨重启事实；进程被杀后**重新问一遍**是更吵但更对的那一端，重启后旧授权还在生效才是危险的那一端。
 */
internal object AnswerReflow {

    /** 补丁的寿命：收到答复那一轮 ＋ 下一轮（再往后就是往历史里反复塞同一句话）。 */
    const val DELIVERY_ROUNDS = 2

    /** 在账上限（与 [AskBatchLedger] 的 AUDIT_CAP 同量级；到线淘汰最老一条＝最保守的一端）。 */
    private const val CAP = 100

    private val lock = Any()
    private val pending = LinkedHashMap<String, PendingAsk>()
    private val received = LinkedHashMap<String, ReceivedReflow>()

    private data class ReceivedReflow(
        val askId: String,
        val turnId: Int,
        val kind: ReflowKind,
        val scope: ReflowScope?,
        val arrivedRound: Int,
        val note: String,
        val grant: ReflowGrant?,
    )

    /** 一条已收下答复的读数视图（只读面的形状，判据表与报告用它对号）。 */
    internal data class ReceivedView(
        val askId: String,
        val turnId: Int,
        val kind: ReflowKind,
        val scope: ReflowScope?,
    )

    // ── ① 登记：379 压下一张卡的那一刻 ────────────────────────────────────

    /**
     * 登记一张被免打扰压下的卡，等她的答复挂到这一轮上。
     *
     * [turnId] 必须是**压下那一刻**的轮号：它就是判据②的对照面——答复带的轮号与这里不一致，
     * 说明那张卡在调用方手里已经过期，不许拿它回灌。
     * 同 askId 重复登记 ⇒ 什么都不改（第一次登记的轮号才是事实，改写它＝改历史）。
     */
    fun register(item: DeferredAsk, turnId: Int): PendingAsk = synchronized(lock) {
        pending[item.id]?.let { return@synchronized it }
        PendingAsk(askId = item.id, turnId = turnId, headline = item.headline).also {
            pending[item.id] = it
            // 队列被清空／进程续跑之外，还有一种让待答清单只涨不掉的情况：她一直不答。
            // 到线淘汰最老一张——摘掉的是「还能不能收她的答」，不是已发生的登记；
            // 最老那条正是最过期的一条，宁可让它变成「重新问一遍」，别让它悄悄生效。
            while (pending.size > CAP) pending.remove(pending.keys.first())
        }
    }

    /** 这张卡登记的轮号（检查点补问的自发回灌用它：引擎答的就是这一轮问的，不该被自己拒）。 */
    fun turnOf(askId: String): Int? = synchronized(lock) { pending[askId]?.turnId }

    /** 正等她答的清单（只读快照；巡按与报告读数用，改不动账）。 */
    fun pendingSnapshot(): List<PendingAsk> = synchronized(lock) { pending.values.toList() }

    // ── ② 收答：三字段校验 → 落账 → 入上下文队列 ──────────────────────────

    /**
     * 收下一条答复（派工单 §1 三样缺一即拒；判据②③都走 [rejectReason]）。
     *
     * @param currentRoundId 引擎此刻的轮号——补丁的寿命从这一刻起算（答复到达轮），
     *        与 [ReflowAnswer.turnId]（问题出身轮）是两码事，别并成一个
     * @param currentTaskId 引擎此刻的任务号（v1 ＝ `session:<会话号>`）；只有
     *        `AUTH ＋ 本任务` 才查它，空白 ⇒ 按「等于无限」拒
     */
    fun submit(
        answer: ReflowAnswer,
        currentRoundId: Int,
        currentTaskId: String,
        nowMs: Long,
    ): ReflowOutcome {
        val ask: PendingAsk = synchronized(lock) {
            val reason = rejectReason(answer, pending[answer.askId], currentTaskId, nowMs)
            if (reason != null) return ReflowOutcome.Rejected(answer.askId, reason)
            // 校验通过即**在锁内占牌**：并发递来的第二张同 askId 答复从这里判出去
            // （「不在待答清单里」），处置账因此不可能被同一次问答写两行——双记账比漏记更糟。
            pending.remove(answer.askId)
                ?: return ReflowOutcome.Rejected(answer.askId, "这张牌刚被同时递来的另一张答复占走（同一问不重复灌）")
        }
        val note = noteOf(ask, answer, currentRoundId, nowMs)
        val grant = grantOf(answer, ask, currentTaskId)
        // 落账在锁外：[CardDisposition.record] 自己带锁、自己认落点（可能做 IO）。
        // 本通道的锁里不许套 IO——与 [QuietCardGate] 「不碰别人的锁」同一条纪律。
        val row = ledgerRowOf(answer, ask, grant, nowMs)
        synchronized(lock) {
            received[answer.askId] = ReceivedReflow(
                askId = ask.askId,
                turnId = ask.turnId,
                kind = answer.kind,
                scope = ReflowScope.ofWire(answer.scopeWire),
                arrivedRound = currentRoundId,
                note = note,
                grant = grant,
            )
            trimLocked()
        }
        return ReflowOutcome.Accepted(
            askId = ask.askId,
            kind = answer.kind,
            scope = grant?.scope,
            note = note,
            dispositionRow = row,
        )
    }

    /**
     * 该不该拒（**纯函数**，JVM 直测；返回 null ＝ 三样齐、形状对，可以收）。
     *
     * 校验顺序＝派工单三样的顺序，每一步只拒一件事、只给一句人话：
     * 是哪一问 → 挂对轮次没有 → 算不算授权的形状（含 INFO 的空话）→ AUTH 的范围 → 非 AUTH 不许带范围。
     */
    fun rejectReason(
        answer: ReflowAnswer,
        ask: PendingAsk?,
        currentTaskId: String,
        nowMs: Long,
    ): String? {
        if (answer.askId.isBlank()) return "答复没带是哪一问（askId 空白）：不知道挂给谁的东西不许回灌"
        if (answer.turnId <= 0) return "答复没带轮次（turnId=${answer.turnId}）：轮号 0 是「不在任何轮里」，不许冒充"
        if (answer.kind == ReflowKind.INFO && answer.text.isBlank()) {
            return "补信息却一个字没补（答案空白）：空话不回灌，宁可再问一次"
        }
        if (ask == null) return "这个问题不在待答清单里（从没登记／已经回灌过 ⇒ 同一问不重复灌）"
        if (ask.turnId != answer.turnId) {
            return "挂错轮次：这问是第 ${ask.turnId} 轮问的，答复按第 ${answer.turnId} 轮回灌 ⇒ 拒"
        }
        return scopeProblem(answer, currentTaskId, nowMs)
    }

    /** AUTH 必须有范围、非 AUTH 不许有范围（判据③的两个方向都在这里）。 */
    private fun scopeProblem(answer: ReflowAnswer, currentTaskId: String, nowMs: Long): String? {
        if (answer.kind != ReflowKind.AUTH) {
            return if (answer.scopeWire.isBlank()) null else "${answer.kind.cn} 不许带授权范围（范围只对 AUTH 有意义）"
        }
        val scope = ReflowScope.ofWire(answer.scopeWire)
            ?: return "AUTH 缺授权范围（一次／本任务／到某时刻 三选一，不许「默认无限」）"
        return when {
            scope == ReflowScope.UNTIL && answer.untilMs <= nowMs ->
                "AUTH 选「到某时刻」却没给将来那一刻（untilMs=${answer.untilMs}）＝等于无限"

            scope == ReflowScope.TASK && currentTaskId.isBlank() ->
                "AUTH 选「本任务」却没说哪个任务（任务号空白）＝等于无限"

            else -> null
        }
    }

    // ── ③ 消费：下轮拼上下文时取走补丁 ────────────────────────────────────

    /**
     * 这一轮该带的上下文补丁（派工单 §2B）。
     *
     * 判据④的「前缀判据」在这里落地：**同一轮重复调用返回逐字相同**——不消费、不置标志位、
     * 不打乱顺序。拼提示词每一步都会重算，中途换批等于打破提示词前缀（[RunEngine.assemble]
     * 里日记段同一口径）。
     */
    fun notes(currentRoundId: Int): List<String> = synchronized(lock) {
        received.values
            .filter { currentRoundId in it.arrivedRound until it.arrivedRound + DELIVERY_ROUNDS }
            .map { it.note }
    }

    // ── ④ 可执行性：只有 AUTH 改得动（判据①） ─────────────────────────────

    /** 这条答复给的授权（INFO／REJECT／没收下 ⇒ null；不改状态，回读用）。 */
    fun authorization(askId: String): ReflowGrant? = synchronized(lock) { received[askId]?.grant }

    /**
     * 原动作此刻可执行吗（判据①「AUTH ⇒ 原动作可执行」的判据本体）。
     *
     * 三档各判各的失效边界（见 [ReflowScope]），**一次都不消费**——消费走 [useAuthorization]；
     * INFO／REJECT／没答复 ⇒ false（派工单 §1②：只有 AUTH 才许改变可执行性）。
     */
    fun executable(askId: String, currentTaskId: String, nowMs: Long): Boolean =
        liveGrant(authorization(askId), currentTaskId, nowMs)

    /** 取用一次授权（ONCE 用过即废；其余两档只验不扣）。 */
    fun useAuthorization(askId: String, currentTaskId: String, nowMs: Long): Boolean {
        val got = synchronized(lock) { received[askId] } ?: return false
        if (!liveGrant(got.grant, currentTaskId, nowMs)) return false
        if (got.grant?.scope == ReflowScope.ONCE) {
            synchronized(lock) { received[askId] = got.copy(grant = null) }
        }
        return true
    }

    private fun liveGrant(grant: ReflowGrant?, currentTaskId: String, nowMs: Long): Boolean = when (grant?.scope) {
        null -> false
        ReflowScope.ONCE -> true
        ReflowScope.TASK -> currentTaskId.isNotBlank() && currentTaskId == grant.taskId
        ReflowScope.UNTIL -> nowMs < grant.untilMs
    }

    // ── 只读面（报告与巡按的读数口；都不改状态） ──────────────────────────

    /** 已收下的答复（新→旧快照；判据表逐条对号用）。 */
    fun receivedSnapshot(): List<ReceivedView> = synchronized(lock) {
        received.values.reversed().map { ReceivedView(it.askId, it.turnId, it.kind, it.scope) }
    }

    /** 一行可查的通道状态（照 [QuietCardGate.stateLine]：恒一行，没东西也照印）。 */
    fun stateLine(currentRoundId: Int): String {
        val waiting = synchronized(lock) { pending.size }
        val auths = synchronized(lock) { received.values.count { it.grant != null } }
        return "[补问回灌] 待答 $waiting 问｜本轮带 ${notes(currentRoundId).size} 条补丁｜" +
            "在账 ${receivedSnapshot().size} 条（授权 $auths）｜上限 $CAP"
    }

    /** 直测收尾：全清（生产不调）。 */
    fun resetForTest() = synchronized(lock) {
        pending.clear()
        received.clear()
    }

    // ── 内部：造句与落账（每个函数只干一件事） ─────────────────────────────

    /** 授权对象（只有 AUTH 造得出；其余两档 null ＝ 可执行性一个字没动）。 */
    private fun grantOf(answer: ReflowAnswer, ask: PendingAsk, currentTaskId: String): ReflowGrant? {
        if (answer.kind != ReflowKind.AUTH) return null
        val scope = ReflowScope.ofWire(answer.scopeWire) ?: return null
        return ReflowGrant(
            askId = ask.askId,
            scope = scope,
            taskId = if (scope == ReflowScope.TASK) currentTaskId else "",
            untilMs = if (scope == ReflowScope.UNTIL) answer.untilMs else 0L,
        )
    }

    /**
     * 处置账那一行（判据①「AUTH／REJECT 落处置账」；**INFO 不落**——她没对动作表态，
     * 处置账记的是「这张卡的下场」，把「补了一句信息」记成批或驳都是造假）。
     */
    private fun ledgerRowOf(
        answer: ReflowAnswer,
        ask: PendingAsk,
        grant: ReflowGrant?,
        nowMs: Long,
    ): CardDisposition.CardDispositionRow? {
        val choice = when (answer.kind) {
            ReflowKind.AUTH -> CardDisposition.Choice.APPROVE
            ReflowKind.REJECT -> CardDisposition.Choice.REJECT
            ReflowKind.INFO -> return null
        }
        return CardDisposition.record(
            choice = choice,
            face = CardDisposition.Face.REFLOW,
            cardId = ask.askId,
            actor = CardDisposition.Actor.USER,
            subject = ask.headline,
            why = whyOf(answer, grant),
            atMs = nowMs,
        )
    }

    /** 为什么这么处置（范围必须写进账——「她批了」不带范围就等于「她批了所有」。） */
    private fun whyOf(answer: ReflowAnswer, grant: ReflowGrant?): String {
        val said = answer.text.take(120)
        return when (answer.kind) {
            ReflowKind.AUTH -> "补问回灌：她授权（范围=${grant?.scope?.cn}）｜答：$said"
            ReflowKind.REJECT -> "补问回灌：她说不用了，动作取消｜答：$said"
            ReflowKind.INFO -> "补问回灌：只补信息，动作不动｜答：$said"
        }
    }

    /** 那条上下文补丁（一行；带轮次与出身，让模型认得出这是「迟到的答复」而不是新指令）。 */
    private fun noteOf(ask: PendingAsk, answer: ReflowAnswer, currentRoundId: Int, nowMs: Long): String {
        val head = "[补问回灌·第 $currentRoundId 轮收] 第 ${ask.turnId} 轮那问「${ask.headline}」她答了：" +
            answer.text.take(200)
        return when (answer.kind) {
            ReflowKind.AUTH -> "$head｜她授权（范围=${scopeHint(answer, nowMs)}）。" +
                "原动作在她给的范围内可执行——要不要重做仍由你判断，这条不是让你自动重试。"

            ReflowKind.REJECT -> "$head｜她取消了这件事，别再推进；要再做请重新问她。"
            ReflowKind.INFO -> "$head｜只补信息，原动作的可执行性没变（她既没授权也没取消）。"
        }
    }

    /** 范围那一格的人话（含「到某时刻」还剩多久；不把内部时间戳抄进提示词）。 */
    private fun scopeHint(answer: ReflowAnswer, nowMs: Long): String {
        val scope = ReflowScope.ofWire(answer.scopeWire) ?: return "未给范围"
        if (scope != ReflowScope.UNTIL) return scope.cn
        val minutes = ((answer.untilMs - nowMs) / QuietLimits.MINUTE_MS).coerceAtLeast(0L)
        return if (minutes >= 60) "${scope.cn}（${minutes / 60} 小时 ${minutes % 60} 分后到期）"
        else "${scope.cn}（$minutes 分钟后到期）"
    }

    /** 到线淘汰最老一条（锁内调用；淘汰＝这条答复不再影响动作／上下文，保守的一端）。 */
    private fun trimLocked() {
        while (received.size > CAP) received.remove(received.keys.first())
    }
}
