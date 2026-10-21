// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百零九批（2026-10-21，qoder）：L3-lite **影子模式** v1。
//
// 一句话：对每条候选**额外**算一遍「**若**把 88 里那条「低风险自动批准」的闸开起来，这一条
// 会被批准／会被拒绝／还是会搁置」，把结论＋理由写进账本，**然后什么都不做**。
// 攒的是「人工一致率」这种开闸前该看的数，不是开闸本身。
//
// 三条边界（`EvolutionShadowGateTest` 逐条钉，每条都配负例）：
//   1. **不另造第二套策略**：影子结论只是对 88 的 [EvolutionDecision]（同一份
//      [EvolutionPolicy.judge] 的输出）做一次**分类**。本文件不重新读证据、不重算阈值、
//      不 new 策略实例——EvolutionPolicy 的生产实例化仍然只有决策口那一处（99 的门禁）；
//   2. **不碰开关**：[EvolutionPolicy.L3_AUTO_LOW_RISK] 仍是 `false`。本文件只**读**它的值
//      写进账本当时间戳用的事实，不写它、不接设置页、不读环境变量、不加 setter；
//   3. **零副作用**：不改候选状态、不写技能／人设、不调 apply／restore、不触发采纳。
//      整个影子唯一的落盘动作是往 `抱朴记忆/进化影子.jsonl` **追加**一行账。
//
// 为什么不塞进 `决策审计.jsonl`：那行的顶层键集是 88 的用例逐个钉死的，而且影子行是
// 「假想结论」，跟真结论混在一行里，读账的人得先分辨哪半是发生过的事。第一百批把采纳
// 事件单开一条流是同一个理由（见 `PostAdoptionLog` 头注）。
//
// ⚠️ 候选每轮收尾都会被重判 ⇒ 同一个候选在账本里会有**多行**。汇总一律
// [latestByCandidate]（按候选取最后一行）再算，否则一致率会被重复行灌满。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 影子的三值结论（派工单 §1 原文）。它描述的是**假想**的自动口，不是发生过的动作。 */
internal enum class ShadowVerdict {
    /** 若开闸：低风险＋证据齐＋eval 过线＋效果正向＋回滚可用 ⇒ 这条会被自动批准。 */
    WOULD_APPROVE,

    /** 若开闸：有硬证据反对（自检失败／eval 未过线／效果为负）⇒ 这条不该要。 */
    WOULD_REJECT,

    /** 若开闸：证据不足、回滚不可用，或风险压根不在自动口范围内 ⇒ 这条仍然留给人。 */
    WOULD_DEFER,
}

/** 没到「会批准」的原因档（只为汇总分组，不参与任何执行判定）。 */
internal enum class ShadowHold {
    /** 无保留：影子会说「批准」。 */
    NONE,

    /** 证据够了，但风险是中／高——88 的自动口只管低风险，根本不接管它。 */
    RISK_NOT_LOW,

    /** 证据不足／回滚不可用／采纳后已被判变差 ⇒ 自动口也只会 DEFER。 */
    EVIDENCE_GAP,

    /** 硬证据反对（自检失败／eval 未过线／效果为负）。 */
    HARD_NO,
}

/**
 * 一条候选的影子结论。
 *
 * 字段全部**抄自**决策口那一次判定（`recommendation`／`confidence`／`riskClass`／
 * `reasons`／`requiresApproval`）＋输入里的候选元数据（`kind`／`riskCategory`／证据 id）。
 * 正文一个字节都不进来——理由链只带证据 id，口径与 88 的审计同一条尺。
 *
 * @param verdict 影子三值结论
 * @param hold 没到「会批准」的档；与 verdict 互相对应（不变式由 init 钉）
 * @param requiresApproval **真**结论（本批恒为 true，因为开关是 false）；正例就靠它证明
 *   「影子说会批，人还是得批」
 */
internal data class ShadowJudgement(
    val atMs: Long,
    val candidateId: String,
    val kind: ProposalKind,
    val riskCategory: EvolutionRiskCategory,
    val riskClass: EvolutionRisk,
    val verdict: ShadowVerdict,
    val hold: ShadowHold,
    val recommendation: EvolutionRecommendation,
    val confidence: Double,
    val requiresApproval: Boolean,
    val evidenceIds: List<String>,
    val reasons: List<EvolutionReason>,
) {
    init {
        require(candidateId.isNotBlank()) { "影子行必须带候选 id" }
        require(confidence in 0.0..1.0) { "置信度必须在 0–1" }
        require(reasons.isNotEmpty()) { "影子行必须带着真决策的原因链" }
        require((hold == ShadowHold.NONE) == (verdict == ShadowVerdict.WOULD_APPROVE)) {
            "只有「会批准」可以没有保留原因；其余三值必须带档"
        }
    }
}

/**
 * 88 的 ADOPT 分支会**恰好**凑齐这四条码（见 `EvolutionPolicy.recommend` 的 POSITIVE 支）。
 *
 * 所以影子核对「低风险自动批准的三项硬条件」时，用的是策略自己已经写进原因链的结论，
 * 不再自己比一遍 eval 分数／效果计数——那就是第二套策略了（派工单 §1 禁止）。
 * 少任何一条 ⇒ 影子弃权（EVIDENCE_GAP），不猜「大概是够的」。
 */
private val SHADOW_HARD_CODES: Set<EvolutionReasonCode> = setOf(
    EvolutionReasonCode.SELF_CHECK_PASSED,
    EvolutionReasonCode.EVAL_PASSED,
    EvolutionReasonCode.EFFECT_POSITIVE,
    EvolutionReasonCode.ROLLBACK_AVAILABLE,
)

/**
 * 影子判据（纯函数）：决策口那次判定的**分类**，不产生任何新证据判定。
 *
 * 分档（互斥，按 recommendation 先分，再看风险）：
 *   - `REJECT` ⇒ [ShadowVerdict.WOULD_REJECT]（硬证据反对）；
 *   - `DEFER` ⇒ [ShadowVerdict.WOULD_DEFER] ＋ [ShadowHold.EVIDENCE_GAP]；
 *   - `ADOPT` 但风险不是 LOW ⇒ [ShadowVerdict.WOULD_DEFER] ＋ [ShadowHold.RISK_NOT_LOW]
 *     （自动口只管低风险，中／高永远人批——88 的红线顺序，影子照抄不改动）；
 *   - `ADOPT` ＋ LOW 但四条码不齐 ⇒ [ShadowVerdict.WOULD_DEFER] ＋ EVIDENCE_GAP
 *     （今天的策略下不可达，是防御档：策略将来改了原因链，影子宁可弃权也不猜）；
 *   - 其余（ADOPT＋LOW＋四码齐）⇒ [ShadowVerdict.WOULD_APPROVE]。
 *
 * 与「假想开闸」的等价性不是嘴上说的：`EvolutionShadowTest` 在**测试侧**造一个把那条闸
 * 开起来的策略实例，逐风险类别、逐证据形状对账——影子说会批 ⟺ 那个假想实例说不需要人批。
 * 说不需要人批。构造只存在于测试里（红线门禁扫的是 main）。
 */
internal fun shadowVerdictOf(
    input: EvolutionDecisionInput,
    decision: EvolutionDecision,
    atMs: Long,
): ShadowJudgement {
    require(input.candidateId == decision.candidateId) { "影子必须判在同一条候选上" }
    val codes = decision.reasons.mapTo(mutableSetOf()) { it.code }
    val hold = when {
        decision.recommendation == EvolutionRecommendation.REJECT -> ShadowHold.HARD_NO
        decision.recommendation == EvolutionRecommendation.DEFER -> ShadowHold.EVIDENCE_GAP
        decision.riskClass != EvolutionRisk.LOW -> ShadowHold.RISK_NOT_LOW
        !codes.containsAll(SHADOW_HARD_CODES) -> ShadowHold.EVIDENCE_GAP
        else -> ShadowHold.NONE
    }
    val verdict = when (hold) {
        ShadowHold.NONE -> ShadowVerdict.WOULD_APPROVE
        ShadowHold.HARD_NO -> ShadowVerdict.WOULD_REJECT
        ShadowHold.RISK_NOT_LOW, ShadowHold.EVIDENCE_GAP -> ShadowVerdict.WOULD_DEFER
    }
    return ShadowJudgement(
        atMs = atMs,
        candidateId = decision.candidateId,
        kind = input.kind,
        riskCategory = input.riskCategory,
        riskClass = decision.riskClass,
        verdict = verdict,
        hold = hold,
        recommendation = decision.recommendation,
        confidence = decision.confidence,
        requiresApproval = decision.requiresApproval,
        evidenceIds = input.evidenceIds,
        reasons = decision.reasons,
    )
}

// ── 落盘：一行 JSONL（只追加、不覆盖）────────────────────────────────────────

/** 影子行 ⇒ JSONL 的一行（键名固定，测试逐个钉）。 */
internal fun ShadowJudgement.toJson(): JSONObject {
    val reasonArray = JSONArray()
    for (reason in reasons) {
        reasonArray.put(
            JSONObject()
                .put("code", reason.code.name)
                .put("evidence_ids", JSONArray(reason.evidenceIds)),
        )
    }
    return JSONObject()
        .put("at_ms", atMs)
        .put("candidate_id", candidateId)
        .put("kind", kind.name)
        .put("risk_category", riskCategory.name)
        .put("risk_class", riskClass.name)
        .put("verdict", verdict.name)
        .put("hold", hold.name)
        .put("recommendation", recommendation.name)
        .put("confidence", confidence)
        .put("requires_approval", requiresApproval)
        .put("evidence_ids", JSONArray(evidenceIds))
        .put("reasons", reasonArray)
        // 记账那一刻开关的真实值。将来真开闸了，这一行就能说明「这条是影子期记的」。
        .put("l3_auto_low_risk", EvolutionPolicy.L3_AUTO_LOW_RISK)
}

/** 影子落点（测试注入内存实现，与 88 的审计落点同一个形状）。 */
internal fun interface EvolutionShadowSink {
    fun record(judgement: ShadowJudgement)
}

/**
 * 影子账本（追加式 JSONL，`抱朴记忆/进化影子.jsonl`）。
 *
 * 纪律照 `EvolutionAuditLog`／`PostAdoptionLog`：只追加、不覆盖、`@Synchronized`、
 * 写失败向上抛（由调用方决定「这一轮不记账」，绝不静默丢账）。
 * 读的一侧**宽进严出**：认不出的行（脏 JSON、未知枚举、缺主键）跳过并 log——
 * 不让一行坏账把整份账本读成空，但也不猜它的内容。
 */
internal class EvolutionShadowLog(private val file: File) : EvolutionShadowSink {

    fun file(): File = file

    @Synchronized
    override fun record(judgement: ShadowJudgement) {
        file.parentFile?.mkdirs()
        file.appendText(judgement.toJson().toString() + "\n", Charsets.UTF_8)
    }

    /**
     * 读整份账本。
     *
     * @return null ＝ **文件读不出来**（不是「没有账」）；文件不存在算空账本
     */
    fun load(): List<ShadowJudgement>? {
        val text = runCatching {
            if (file.isFile) file.readText(Charsets.UTF_8) else ""
        }.getOrElse {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴：影子账本读失败 ${it.javaClass.simpleName}: ${it.message}",
            )
            return null
        }
        val out = ArrayList<ShadowJudgement>()
        var dropped = 0
        for (line in text.lineSequence()) {
            val t = line.trim()
            if (t.isEmpty()) continue
            val obj = runCatching { JSONObject(t) }.getOrNull()
            if (obj == null) {
                dropped++
                continue
            }
            rowOf(obj)?.let { out += it } ?: run { dropped++ }
        }
        if (dropped > 0) {
            com.example.zhengdao.rootfs.RunLog.log("抱朴：影子账本有 $dropped 行认不出（已跳过）")
        }
        return out
    }

    private fun rowOf(o: JSONObject): ShadowJudgement? {
        val id = o.optString("candidate_id").takeIf { it.isNotBlank() } ?: return null
        val kind = ProposalKind.entries.firstOrNull { it.name == o.optString("kind") } ?: return null
        val category = EvolutionRiskCategory.entries
            .firstOrNull { it.name == o.optString("risk_category") } ?: return null
        val risk = EvolutionRisk.entries.firstOrNull { it.name == o.optString("risk_class") } ?: return null
        val verdict = ShadowVerdict.entries.firstOrNull { it.name == o.optString("verdict") } ?: return null
        val hold = ShadowHold.entries.firstOrNull { it.name == o.optString("hold") } ?: return null
        val recommendation = EvolutionRecommendation.entries
            .firstOrNull { it.name == o.optString("recommendation") } ?: return null
        val codes = o.optJSONArray("reasons") ?: return null
        if (codes.length() == 0) return null
        val reasons = ArrayList<EvolutionReason>(codes.length())
        for (i in 0 until codes.length()) {
            val item = codes.optJSONObject(i) ?: return null
            val code = EvolutionReasonCode.entries
                .firstOrNull { it.name == item.optString("code") } ?: return null
            val ids = item.optJSONArray("evidence_ids")?.let { arr ->
                (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
            }.orEmpty()
            reasons += EvolutionReason(code, ids)
        }
        val evidence = o.optJSONArray("evidence_ids")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        return ShadowJudgement(
            atMs = o.optLong("at_ms", 0L),
            candidateId = id,
            kind = kind,
            riskCategory = category,
            riskClass = risk,
            verdict = verdict,
            hold = hold,
            recommendation = recommendation,
            confidence = o.optDouble("confidence", 0.0),
            requiresApproval = o.optBoolean("requires_approval", true),
            evidenceIds = evidence,
            reasons = reasons,
        )
    }

    companion object {
        /** 账本文件名；与决策审计／采纳效果同一棵目录（用户可看可备份）。 */
        const val FILE_NAME = "进化影子.jsonl"

        fun under(globalRoot: File): EvolutionShadowLog = EvolutionShadowLog(File(globalRoot, FILE_NAME))
    }
}

// ── 指标（派工单 §2）：计数／按风险类别／与人工决定的一致率─────────────────────

/** 同一候选每轮都被重判 ⇒ 汇总只看每个候选**最后**那一行。 */
internal fun latestByCandidate(rows: List<ShadowJudgement>): List<ShadowJudgement> {
    val last = LinkedHashMap<String, ShadowJudgement>()
    for (row in rows.sortedBy { it.atMs }) last[row.candidateId] = row
    return last.values.toList()
}

/**
 * 影子计数（派工单 §2 第一行）。
 *
 * @param rows 已经按候选去重的行（[latestByCandidate] 之后）；本函数**不再**去重，
 *   免得有人把原始账本直接灌进来还以为数对
 * @param byCategory 全部行的风险类别分布
 * @param approveByCategory 只有「会批准」那部分的分布——开闸前真正要看的是这张表
 */
internal data class ShadowCounts(
    val rows: Int,
    val wouldApprove: Int,
    val wouldReject: Int,
    val wouldDefer: Int,
    val byCategory: Map<EvolutionRiskCategory, Int>,
    val approveByCategory: Map<EvolutionRiskCategory, Int>,
    val byKind: Map<ProposalKind, Int>,
)

internal fun tallyShadow(rows: Collection<ShadowJudgement>): ShadowCounts = ShadowCounts(
    rows = rows.size,
    wouldApprove = rows.count { it.verdict == ShadowVerdict.WOULD_APPROVE },
    wouldReject = rows.count { it.verdict == ShadowVerdict.WOULD_REJECT },
    wouldDefer = rows.count { it.verdict == ShadowVerdict.WOULD_DEFER },
    byCategory = rows.groupingBy { it.riskCategory }.eachCount(),
    approveByCategory = rows.filter { it.verdict == ShadowVerdict.WOULD_APPROVE }
        .groupingBy { it.riskCategory }.eachCount(),
    byKind = rows.groupingBy { it.kind }.eachCount(),
)

/**
 * 与人工实际决定的一致率（派工单 §2 第二行）。
 *
 * 口径（写死在这里，报告里同一段话）：
 *   - **配对**＝影子敢表态（`WOULD_APPROVE`／`WOULD_REJECT`）且这条候选在 57 批的
 *     采纳／丢弃留痕里有**同 hash** 的行（候选 id 与留痕 hash 是同一把尺：
 *     [SkillRefine.dedupeKey]）；
 *   - **一致**＝影子说批而人采纳了，或影子说拒而人丢弃了；
 *   - 影子 `WOULD_DEFER`（弃权）**不进分母**——它没表态，谈不上同意与否；
 *   - `paired == 0` ⇒ [rate] 是 **null**，不是 0.0。零样本不猜（派工单 §2）。
 *
 * 四个交叉格是给人核对用的原始分布，比率只是它的摘要——别只读一个百分数。
 *
 * @param approveAdopt 影子说批・人采纳（真一致）
 * @param approveReject 影子说批・人丢弃（影子过松——开闸最该盯的一格）
 * @param rejectAdopt 影子说拒・人采纳（影子过严）
 * @param rejectReject 影子说拒・人丢弃（真一致）
 * @param abstainedHumanDecided 影子弃权但人已决：证据缺口的人工代价，攒证据的理由
 * @param waitingHuman 影子有结论但人还没决：攒着，等将来配对
 */
internal data class ShadowAgreement(
    val paired: Int,
    val agreed: Int,
    val rate: Double?,
    val approveAdopt: Int,
    val approveReject: Int,
    val rejectAdopt: Int,
    val rejectReject: Int,
    val abstainedHumanDecided: Int,
    val waitingHuman: Int,
) {
    init {
        require(paired == approveAdopt + approveReject + rejectAdopt + rejectReject) {
            "配对数必须等于四个交叉格之和"
        }
        require(agreed == approveAdopt + rejectReject) { "一致格只能是批・采纳＋拒・丢弃" }
        require((rate == null) == (paired == 0)) { "零样本没有比率；有配对必须有比率" }
    }
}

/**
 * 一致率（纯函数）。
 *
 * @param rows 按候选去重后的影子行
 * @param humanDecisions `候选 id(hash) → 人是否采纳`（[EvolutionShadowHuman] 读 57 批留痕）
 */
internal fun agreementShadow(
    rows: Collection<ShadowJudgement>,
    humanDecisions: Map<String, Boolean>,
): ShadowAgreement {
    var approveAdopt = 0
    var approveReject = 0
    var rejectAdopt = 0
    var rejectReject = 0
    var abstainedHumanDecided = 0
    var waitingHuman = 0
    for (row in rows) {
        val adopted = humanDecisions[row.candidateId]
        when (row.verdict) {
            ShadowVerdict.WOULD_APPROVE -> when (adopted) {
                true -> approveAdopt++
                false -> approveReject++
                null -> waitingHuman++
            }

            ShadowVerdict.WOULD_REJECT -> when (adopted) {
                true -> rejectAdopt++
                false -> rejectReject++
                null -> waitingHuman++
            }

            ShadowVerdict.WOULD_DEFER -> if (adopted == null) waitingHuman++ else abstainedHumanDecided++
        }
    }
    val paired = approveAdopt + approveReject + rejectAdopt + rejectReject
    val agreed = approveAdopt + rejectReject
    return ShadowAgreement(
        paired = paired,
        agreed = agreed,
        rate = if (paired == 0) null else agreed.toDouble() / paired,
        approveAdopt = approveAdopt,
        approveReject = approveReject,
        rejectAdopt = rejectAdopt,
        rejectReject = rejectReject,
        abstainedHumanDecided = abstainedHumanDecided,
        waitingHuman = waitingHuman,
    )
}

/**
 * 一行汇总（派工单 §2「输出可汇总：报告／日志」）。
 *
 * 比率缺失时打 `—` 而不是 `0%`——零样本没有比率，把空位写成 0 就是猜。
 * 这行只进 app 日志，不构成任何决定；UI 本批不做。
 */
internal fun shadowSummaryLine(counts: ShadowCounts, agreement: ShadowAgreement): String {
    val rate = agreement.rate?.let { String.format(java.util.Locale.ROOT, "%.2f", it) } ?: "—"
    val approveCats = counts.approveByCategory.entries
        .sortedByDescending { it.value }
        .joinToString(",") { "${it.key.name}=${it.value}" }
        .ifBlank { "无" }
    return "影子（去重后 ${counts.rows} 条）：会批 ${counts.wouldApprove} 会拒 ${counts.wouldReject} " +
        "会搁置 ${counts.wouldDefer}｜会批的风险档 $approveCats｜" +
        "人工配对 ${agreement.paired} 一致 ${agreement.agreed} 一致率 $rate｜" +
        "影子弃权人已决 ${agreement.abstainedHumanDecided} 等人 ${agreement.waitingHuman}｜" +
        "开关仍 ${EvolutionPolicy.L3_AUTO_LOW_RISK}"
}

// ── 人工决定的读数（只读一条 SELECT；正文一律不进）───────────────────────────

/**
 * 57 批「采纳／丢弃留痕」⇒ `候选 id(hash) → 是否采纳`。
 *
 * 为什么可以按 hash 配对：候选 id 就是 [SkillRefine.dedupeKey]（[ProposalRules.idOf]），
 * 留痕写的是同一把尺（`SkillRefine.dedupeKeyOf`），所以「人对这条候选表过态没有」在库里
 * 本来就有现成的连接键——不新建表、不加列、不猜模糊匹配。
 *
 * 同一条 hash 有多行 ⇒ **后写的覆盖先写的**（`ORDER BY at`，人改主意以最后一次为准）。
 *
 * ⚠️ 覆盖范围要说实话：只有留痕 hash 与候选 id 同源的那条通道（学习卡 ⇒ 技能正文候选）
 * 配得上；人设索引那路的留痕用的是 `dedupeKeyOfProposal`（名字＋scenario，另一把尺），
 * 对不上就落在 `waitingHuman` 格里，不硬凑。
 *
 * 读失败 ⇒ 空 map（＝这一轮没有配对样本，一致率无分母）。留痕是账本不是闸门，
 * 账本读不动也不该卡住收尾判定（与 57 批「写失败只 log」同一口径）。
 */
internal object EvolutionShadowHuman {

    /** 只读一条 SELECT（影子模式没有任何写口；源码门禁钉着不许出现 INSERT/UPDATE/DELETE）。 */
    fun decisionsStmt(): BaopuSql.Stmt = BaopuSql.Stmt(
        "SELECT hash, ok FROM skill_event WHERE kind = 'decision' AND hash IS NOT NULL ORDER BY at",
        emptyArray(),
    )

    /** `(hash, 是否采纳)` 行 ⇒ 覆盖式 map（后来的赢）。纯函数，JVM 直测。 */
    fun byHash(rows: List<Pair<String, Boolean>>): Map<String, Boolean> {
        val out = LinkedHashMap<String, Boolean>()
        for ((hash, adopted) in rows) {
            if (hash.isNotBlank()) out[hash] = adopted
        }
        return out
    }

    /** 库侧读数；失败只 log 并按「没有配对样本」处理。 */
    fun fromDb(db: BaopuDb): Map<String, Boolean> {
        val stmt = decisionsStmt()
        val rows: List<Pair<String, Boolean>> = runCatching {
            db.read { d ->
                d.rawQuery(stmt.sql, stmt.args).use { c ->
                    (0 until c.count).map {
                        c.moveToPosition(it)
                        c.getString(0).orEmpty() to (c.getInt(1) != 0)
                    }
                }
            }
        }.getOrElse {
            com.example.zhengdao.rootfs.RunLog.log(
                "抱朴：人工留痕读数失败（本轮按无配对样本处理）${it.javaClass.simpleName}: ${it.message}",
            )
            emptyList()
        }
        return byHash(rows)
    }
}
