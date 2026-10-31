// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十八批 · **两级裁决**的草稿层与验证层（治理机制细则 2026-10-30 定名）。
//
// 细则的原文口径：
//   - **草稿层**＝规则/查表给一个初步决定（弹／不弹／延后），**只能查表，不许用小模型或
//     任何生成式组件**（"律是尺子，模型是嘴"）；
//   - **验证层**＝四器意见＋**法典判据**校验这份草稿：一致 ⇒ 毫秒级采纳；冲突／高风险 ⇒
//     走完整宰相流程。
//
// 本批把它落成一次调用 [decide]：
//   1. 查表（[VerdictExperienceRuntime.current] 一次 volatile 读 + 一次 Map 取）；
//      表里没有这一类 ⇒ [DraftOutcome.MISS] ⇒ 调用方照原路走完整流程（**行为逐字不变**）；
//   2. 表里有这一类且窗内"关"够数 ⇒ 草稿＝**不弹**（学出来的只有这一条：什么时候别问）；
//   3. 验证层只有一条判据：**法典点名的必问集**（283 的 `LearnPolicy.mustAsk`——
//      L7 未分类／L8 硬拒／critical；`critical` 已含 L21 急案，所以"越预算该弹"也在里面）。
//      撞上 ⇒ [DraftOutcome.VETOED]，**完整流程优先**，调用方负责记账（判据③）；
//      没撞 ⇒ [DraftOutcome.ADOPTED]。
//
// 为什么草稿只能是"不弹"：283 学的东西按法条就不许改"什么必须问"，它只能调"什么时候问"。
// 反过来给草稿加一条"该弹"，等于让学到的次数去压法条与预算——那是 283 判据②明写的禁区，
// 本批不越（负例"撞法条仍按草稿放行"守的就是这一带）。
//
// 零 Android / 零 IO / 零模型：`policy/` 由 122 批的源级门禁逐文件钉死零 IO 零网络；
// 本批再补一条自己的门禁（`DraftSourceGateTest`）——草稿层里出现任何模型/生成式符号即红。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.agent.LearnPolicy
import com.example.zhengdao.baopu.agent.OpinionDecision
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.Severity
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** 一次草稿裁决的结果（判据④的三计数就按这三个值记）。 */
internal enum class DraftOutcome(val wire: String, val cn: String) {
    /** 表里没有这一类 ⇒ 走完整流程（与"没接表"逐字一致）。 */
    MISS("draft_miss", "未命中"),

    /** 草稿给出"不弹"且验证层没有反对 ⇒ 采纳（毫秒级，不进完整流程）。 */
    ADOPTED("draft_adopted", "草稿采纳"),

    /** 草稿与法典判据冲突 ⇒ **完整流程优先**，账上记"草稿被否"。 */
    VETOED("draft_vetoed", "草稿被否"),
}

/** 草稿本身（查表给的那个初步决定）。 */
internal data class Draft(
    val cardClass: String,
    val decision: OpinionDecision,
    /** 命中了表里的哪一条（机器键，回放按它分组；文案会改，键不许改）。 */
    val rule: String,
    /** 一句能落账的理由（含"命中经验表"字样——判据①要求的证据）。 */
    val reason: String,
)

/**
 * 一次裁决的落点（调用方按 [outcome] 三分支走）。
 *
 * @param consultNanos 查表那一段的耗时（**只含查表＋验证**，不含落账与 IO），体检按它算均值
 */
internal data class DraftVerdict(
    val outcome: DraftOutcome,
    val draft: Draft?,
    /** 被否的原因（法条点名；没被否就是 null）。 */
    val vetoWhy: String?,
    val consultNanos: Long,
) {
    /** 采纳时那句要进账的理由（其它分支为 null——不许给没发生的事编理由）。 */
    val adoptedReason: String? get() = if (outcome == DraftOutcome.ADOPTED) draft?.reason else null
}

internal object VerdictDraft {

    /**
     * 两级裁决走一遍：查表 ⇒ 草稿 ⇒ 验证。
     *
     * 全程只有一次表读（[VerdictExperienceRuntime.current]）——不在中途再读第二次，
     * 否则一张表在一次裁决里可能前后不是同一版（细则要的"一次判定读同一版"）。
     */
    fun decide(opinion: Opinion, now: Long): DraftVerdict {
        val startedAt = System.nanoTime()
        val snap = VerdictExperienceRuntime.current()
        val cls = opinion.gist
        val reason = snap.reasonFor(cls, now)
        val outcome: DraftOutcome
        val draft: Draft?
        val vetoWhy: String?
        if (reason == null) {
            outcome = DraftOutcome.MISS
            draft = null
            vetoWhy = null
        } else {
            val d = Draft(
                cardClass = cls,
                decision = OpinionDecision.SKIP,
                rule = "suppress:v${snap.seq}",
                reason = reason,
            )
            draft = d
            if (LearnPolicy.mustAsk(opinion)) {
                outcome = DraftOutcome.VETOED
                vetoWhy = vetoWhyOf(opinion, d)
            } else {
                outcome = DraftOutcome.ADOPTED
                vetoWhy = null
            }
        }
        val nanos = System.nanoTime() - startedAt
        return DraftVerdict(outcome = outcome, draft = draft, vetoWhy = vetoWhy, consultNanos = nanos)
    }

    /** 被否的原因（一句能落账的人话：法条点名＋草稿想干什么＋谁优先）。 */
    internal fun vetoWhyOf(opinion: Opinion, draft: Draft): String {
        val mandate = when {
            opinion.lawRef == LawRef.L7 -> "L7 未分类（fail-closed 必问）"
            opinion.lawRef == LawRef.L8 -> "L8 硬拒（红线清单必问）"
            opinion.severity == Severity.CRITICAL -> "critical 急案（L33 禁区）"
            else -> opinion.lawRef.name
        }
        return "草稿想「${draft.decision.cn}」（${draft.rule}），但 $mandate 要问：完整流程优先，草稿作废"
    }
}

/** 三计数（命中／未命中／被否）。 */
internal data class DraftCounts(val hits: Int, val misses: Int, val vetoes: Int) {
    /** 一行结论（只出计数——判据④要求的可观测面，不外泄类名以外的明细）。 */
    fun text(): String = "命中=$hits 未命中=$misses 被否=$vetoes"
}

/**
 * 草稿裁决的计数与耗时（**判决路径的账，不是闸门**：写它不阻塞任何判定）。
 *
 * 两套计数：本轮（换轮清零）与进程累计（不清零）。换轮由调用方 `beginRound` 通知——
 * 轮的概念长在调解器那边，这里不自己造。
 */
internal object DraftTally {

    private val round = Counter3()
    private val total = Counter3()
    private val consultCount = AtomicInteger(0)
    private val nanosTotal = AtomicLong(0L)

    @Volatile
    private var roundKey: String = ""

    /** 记一次裁决（`roundMark` 是账上那一轮的来源：`process`／`round#N`）。 */
    fun note(roundMark: String, outcome: DraftOutcome, nanos: Long) {
        if (roundMark != roundKey) {
            roundKey = roundMark
            round.reset()
        }
        nanosTotal.addAndGet(nanos)
        consultCount.incrementAndGet()
        total.bump(outcome)
        round.bump(outcome)
    }

    /** 本轮三计数（判据④要求"每轮记"）。 */
    fun roundCounts(): DraftCounts = round.snapshot()

    /** 进程累计三计数。 */
    fun totals(): DraftCounts = total.snapshot()

    /** 查表次数与累计耗时（纳秒）——体检按它算均值，真机按它核"查表没把判决拖慢"。 */
    fun consultStats(): Pair<Int, Long> = consultCount.get() to nanosTotal.get()

    /** 一行可见结论（只出计数，不出类名/时刻）。 */
    fun conclusion(): String =
        "草稿裁决：本轮 ${roundCounts().text()}｜全程 ${totals().text()}｜查表 ${consultStats().first} 次"

    /** 清空（测试专用：单例状态会跨用例污染，谁用谁还）。 */
    fun reset() {
        round.reset()
        total.reset()
        consultCount.set(0)
        nanosTotal.set(0L)
        roundKey = ""
    }
}

/** 三个方向的计数（命中/未命中/被否）——用 AtomicInteger，判决路径不额外加锁。 */
private class Counter3 {
    private val a = AtomicInteger(0)
    private val b = AtomicInteger(0)
    private val c = AtomicInteger(0)

    fun bump(outcome: DraftOutcome) {
        when (outcome) {
            DraftOutcome.MISS -> b.incrementAndGet()
            DraftOutcome.ADOPTED -> a.incrementAndGet()
            DraftOutcome.VETOED -> c.incrementAndGet()
        }
    }

    fun snapshot(): DraftCounts = DraftCounts(hits = a.get(), misses = b.get(), vetoes = c.get())

    fun reset() {
        a.set(0)
        b.set(0)
        c.set(0)
    }
}
