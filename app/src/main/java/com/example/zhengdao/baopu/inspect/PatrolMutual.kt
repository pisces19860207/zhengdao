// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百七十九批 §3／§4／§5：互查、冲突并列入账、监督者自身误报率、额度调整复核。
//
// 三件事：
// 1. **互查**：金吾卫的结论可被暗卫抽查，暗卫的抽检记录可被金吾卫查——
//    两侧对**同一条证据**给出不同认定 ⇒ 冲突；
// 2. **冲突并列**：两条都进账，各带自己的证据与对方证据 id，标「待回放」。
//    延后队列是 277 的产出且**未合并** ⇒ 本批只入账、**不自建第二本队列**（防两本队列漂移）；
//    这期间冲突**只在报告里列，不进御案**；
// 3. **误报率**：分母＝暗卫报出的条数（[NightRound.hits]，`NO_DATA` 本来就不在里面），
//    分子＝复核判为误报的条数，窗 30 天（照 [InspectLimits.RATE_WINDOW_DAYS]）。
//
// 额度那一条（2026-10-29 用户裁）：[AllowanceLadder] **不算处置权**（只调免问额度，
// 不改法条判定），但**每次调整要入账**、要有上限、可回滚、可被暗卫复核。
// 本文件只出**草稿与审计行**，一笔也不执行——所以它做不成「隐形升降」。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import java.io.File
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject

/** 互查的两侧。 */
internal enum class MutualSide(val wire: String, val cn: String) {
    SHADOW("shadow", "暗卫"),
    GUARD("guard", "金吾卫"),
}

/** 一侧对某条证据的认定（互查的输入；**只认证据 id，不认谁的口气**）。 */
internal data class MutualFinding(
    val side: MutualSide,
    val check: InspectCheck,
    val verdict: InspectVerdict,
    val evidence: List<String>,
)

/** 冲突的一侧（两条并列，谁也不压谁）。 */
internal data class ConflictRow(
    val id: String,
    val atMs: Long,
    /** 两侧共同指向的那条证据 id。 */
    val target: String,
    val side: MutualSide,
    val check: InspectCheck,
    val verdict: InspectVerdict,
    /** 本侧证据。 */
    val evidence: List<String>,
    val peerSide: MutualSide,
    /** 对侧证据 id（277 落地后一次性导入，不用重新扫）。 */
    val peerEvidence: List<String>,
    /** 「待回放」标记：延后队列还没接上时恒为 true。 */
    val pendingReplay: Boolean = true,
)

/** 监督者自身的误报率（分母只有「报出来的」条数）。 */
internal data class FalsePositiveRate(
    val pct: Double,
    val falsePositives: Int,
    val reported: Int,
    val windowDays: Int,
) {
    val known: Boolean get() = reported > 0
}

/** 一次额度调整的审计行（**拒了的也留痕**）。 */
internal data class AllowanceAuditRow(
    val id: String,
    val atMs: Long,
    val basisPatrolId: String,
    val before: String,
    val after: String,
    val samples: Int,
    val passRatePct: Double,
    /** null = 可以落；非 null = 拒绝的理由（照样记账）。 */
    val refused: String?,
    /** 回滚靠它（照 [AllowanceLadder] 的口径：依据的巡按 id）。 */
    val rollbackRef: String,
)

internal object PatrolMutual {

    private const val DAY_MS = 86_400_000L

    // ── 一、互查：两侧对同一条证据认定不同 ───────────────────────────────────

    /**
     * 找冲突。返回的长度恒为偶数：**每条冲突两行**（各侧一行）——冲突时丢一条就是负例。
     *
     * 配对口径：同检查项＋至少共享一条证据 id。认定相同（都 HIT／都 CLEAR）不算冲突；
     * 一侧 CLEAR 一侧 HIT 才算。
     */
    fun conflicts(
        shadow: List<MutualFinding>,
        guard: List<MutualFinding>,
        nowMs: Long,
    ): List<ConflictRow> {
        val out = mutableListOf<ConflictRow>()
        val seen = mutableSetOf<String>()
        shadow.forEach { s ->
            guard.forEach { g ->
                val shared = sharedEvidence(s, g) ?: return@forEach
                val key = "${s.check.wire}|$shared"
                if (!seen.add(key)) return@forEach
                out += rowOf(nowMs, shared, s, g)
                out += rowOf(nowMs, shared, g, s)
            }
        }
        return out
    }

    private fun sharedEvidence(a: MutualFinding, b: MutualFinding): String? {
        if (a.check != b.check || a.verdict == b.verdict) return null
        return a.evidence.firstOrNull { it in b.evidence }
    }

    private fun rowOf(atMs: Long, target: String, self: MutualFinding, peer: MutualFinding): ConflictRow =
        ConflictRow(
            id = "conflict:$atMs:${self.side.wire}:${self.check.wire}",
            atMs = atMs,
            target = target,
            side = self.side,
            check = self.check,
            verdict = self.verdict,
            evidence = self.evidence,
            peerSide = peer.side,
            peerEvidence = peer.evidence,
        )

    // ── 二、监督者自身误报率 ────────────────────────────────────────────────

    /**
     * 误报率（`pct = 分子／分母 × 100`）。
     *
     * 分母不塞 `NO_DATA`：查不了既不是通过也不是报错，算进去会把「没查成」洗成低误报。
     * 分子只认 [ReviewVerdict.FALSE_POSITIVE]；[ReviewVerdict.NO_DATA] 的复核两边都不进。
     * 一轮只算一次（复核是按轮记的，同一轮重复复核以集合去重），且不超过分母。
     */
    fun falsePositiveRate(
        rounds: List<NightRound>,
        reviews: List<DigestReview>,
        nowMs: Long,
        windowDays: Int = InspectLimits.RATE_WINDOW_DAYS,
    ): FalsePositiveRate {
        val from = nowMs - windowDays * DAY_MS
        val reported = rounds.asSequence().filter { it.atMs in from..nowMs }.sumOf { it.hits }
        val wrongRounds = reviews.asSequence()
            .filter { it.atMs in from..nowMs && it.verdict == ReviewVerdict.FALSE_POSITIVE }
            .map { it.roundId }
            .distinct()
            .count()
        val falsePositives = minOf(wrongRounds, reported)
        if (reported == 0) {
            return FalsePositiveRate(PassRate.UNKNOWN_PCT, falsePositives, 0, windowDays)
        }
        return FalsePositiveRate(falsePositives * 100.0 / reported, falsePositives, reported, windowDays)
    }

    // ── 三、额度调整：入账、有上限、可回滚、可复核 ───────────────────────────

    /**
     * 审计一笔额度调整。**不接受就不执行，但照样留痕**——拒了不记账等于偷偷放过。
     *
     * 三条上限（`refusalOf` 之外的补充）：
     * ①同一勘验依据只许调一次（防一次巡按被反复引用反复升降）；
     * ②只能走**相邻档**（跨两档需人确认，防一次读盘把额度从收回直接放到放开）。
     */
    fun allowanceAudit(
        change: AllowanceLadder.AllowanceChange,
        earlier: List<AllowanceAuditRow> = emptyList(),
    ): AllowanceAuditRow = AllowanceAuditRow(
        id = "allow-audit:${change.atMs}",
        atMs = change.atMs,
        basisPatrolId = change.basisPatrolId,
        before = change.before.wire,
        after = change.after.wire,
        samples = change.samples,
        passRatePct = change.passRatePct,
        refused = refusalOf(change, earlier),
        rollbackRef = change.basisPatrolId,
    )

    private fun refusalOf(
        change: AllowanceLadder.AllowanceChange,
        earlier: List<AllowanceAuditRow>,
    ): String? = AllowanceLadder.refusalOf(change)
        ?: duplicatedBasis(change, earlier)
        ?: jumpedBand(change)

    private fun duplicatedBasis(
        change: AllowanceLadder.AllowanceChange,
        earlier: List<AllowanceAuditRow>,
    ): String? = earlier.firstOrNull {
        it.refused == null && it.basisPatrolId == change.basisPatrolId
    }?.let { "同一勘验依据只许调一次（${it.id} 已用）" }

    private fun jumpedBand(change: AllowanceLadder.AllowanceChange): String? =
        if (abs(change.after.ordinal - change.before.ordinal) > 1) "跨两档：需人确认" else null

    /**
     * 回滚草稿（**不执行**：写 208 的手在执行侧，纠察替自己改额度＝既当运动员又当裁判）。
     *
     * 只给「已入账」的那一笔出草稿；被拒的没有可回滚的东西。
     */
    fun rollbackOf(row: AllowanceAuditRow): DecisionDraft? {
        if (row.refused != null) return null
        val before = AllowanceBand.ofWire(row.before) ?: return null
        val after = AllowanceBand.ofWire(row.after) ?: return null
        return DecisionDraft(
            kind = DecisionKind.TOOL_GRANT,
            actor = DecisionActor.USER,
            reason = "撤销额度调整 ${after.cn}→${before.cn}（依据 ${row.basisPatrolId}）",
            evidence = "${AllowanceLadder.EVIDENCE_PREFIX}${before.wire}:${row.basisPatrolId}",
            before = after.wire,
            after = before.wire,
            atMs = row.atMs,
            rollbackRef = row.basisPatrolId,
            reversible = true,
            rollbackOf = row.id,
        )
    }

    /**
     * 暗卫复核一笔额度调整（「每次调整可被暗卫复核」的落点）：拿当时的档与通过率重算一次。
     *
     * @return 复核结论（永远有字：复核也算「查不了」要说得出来）
     */
    fun recheck(row: AllowanceAuditRow, rate: PassRate): String {
        if (row.refused != null) return "已拒：${row.refused}"
        val before = AllowanceBand.ofWire(row.before) ?: return "复核不了：档位读不出（${row.before}）"
        if (!rate.known) return "复核不了：通过率算不出，不能反推档位"
        val expected = AllowanceLadder.nextBand(before, rate)
        return if (expected.wire == row.after) "属实：${before.cn}→${expected.cn}" else "不符：按当前读数应到 ${expected.cn}"
    }
}

/**
 * 互查台账（`互查记录.jsonl`）：冲突行与额度审计行**同一本账**，`kind` 分两种。
 *
 * 为什么一本：两件事都是「监督者之间/监督者自己的留痕」，分两本就会出现
 * 「查冲突忘了查额度」；一行一条，`kind` 是稳定键。
 */
internal object MutualLedger {

    const val FILE_NAME = "互查记录.jsonl"
    const val KIND_CONFLICT = "conflict"
    const val KIND_ALLOWANCE = "allowance"

    fun fileIn(globalRoot: File): File = File(globalRoot, FILE_NAME)

    @Synchronized
    fun append(globalRoot: File, row: JSONObject): Boolean = runCatching {
        val f = fileIn(globalRoot)
        f.parentFile?.mkdirs()
        f.appendText(row.toString() + "\n", Charsets.UTF_8)
        true
    }.getOrDefault(false)

    fun conflictJson(row: ConflictRow): JSONObject = JSONObject()
        .put("kind", KIND_CONFLICT)
        .put("id", row.id)
        .put("at", row.atMs)
        .put("target", row.target)
        .put("side", row.side.wire)
        .put("check", row.check.wire)
        .put("verdict", row.verdict.wire)
        .put("evidence", JSONArray(row.evidence))
        .put("peer_side", row.peerSide.wire)
        .put("peer_evidence", JSONArray(row.peerEvidence))
        .put("pending_replay", row.pendingReplay)

    fun allowanceJson(row: AllowanceAuditRow): JSONObject = JSONObject()
        .put("kind", KIND_ALLOWANCE)
        .put("id", row.id)
        .put("at", row.atMs)
        .put("basis_patrol_id", row.basisPatrolId)
        .put("before", row.before)
        .put("after", row.after)
        .put("samples", row.samples)
        .put("pass_rate", row.passRatePct)
        .put("refused", row.refused ?: "")
        .put("rollback_ref", row.rollbackRef)

    fun read(globalRoot: File, limit: Int = 500): List<JSONObject> = runCatching {
        val f = fileIn(globalRoot)
        if (!f.isFile) return@runCatching emptyList()
        f.readLines(Charsets.UTF_8).asSequence()
            .mapNotNull { runCatching { JSONObject(it) }.getOrNull() }
            .filter { it.optString("id").isNotEmpty() }
            .take(limit)
            .toList()
    }.getOrDefault(emptyList())
}
