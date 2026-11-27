// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百二十一批 · R1–R10 判据：纯函数，同一输入两次跑结论逐字一致。
// 只回答「这张关系单自相矛盾不矛盾」，不下心理诊断、不进闸、不落库。
// 唯一出口是 RelFinding.line()；每条结论带码、理由、来源、日期。
package com.example.zhengdao.baopu.state.relation

/** 一条结论。构造即守：有码、有理由、有来源。 */
internal data class RelFinding(
    val verdict: RelVerdict,
    val code: String,
    val why: String,
    val source: String = RELATION_SOURCE,
    val asOf: String = RELATION_AS_OF,
) {
    init {
        require(code.isNotBlank()) { "结论缺代码" }
        require(why.isNotBlank()) { "结论缺理由：$code" }
    }

    fun line(): String = "[" + verdict.label + "] " + code + "：" + why + "（" + source + " " + asOf + "）"
}

internal object RelationChecks {

    /** R1：四维＋两心理维齐且各自在档内。 */
    fun fields(cell: DirectedCell): RelFinding? {
        val d = cell.dims
        val p = cell.psych
        val ok = d.trust in 0..3 && d.dependency in 0..3 && d.intimacy in 0..3 &&
            d.conflict in 0..100 && p.fearOfAbandon in 0..3 && p.needToBeNeeded in 0..3
        if (ok) return null
        return RelFinding(
            RelVerdict.DENY, "R1-DIMS",
            cell.from + "→" + cell.to + " 六维不齐或越档：" + d.lines().joinToString(" "),
        )
    }

    /** R2：A→B 与 B→A 必须两格；合成一格即红。 */
    fun direction(shape: PairShape): RelFinding? = when (shape) {
        is PairShape.Merged -> if (RelationRuleSwitch.treatMergedAsValid) {
            null
        } else {
            RelFinding(RelVerdict.DENY, "R2-DIR-MERGED", "A→B 与 B→A 被合成一格：两方向的档不许共用")
        }
        is PairShape.Two -> {
            val a = shape.a
            val b = shape.b
            val paired = a.from == b.to && a.to == b.from && a !== b
            if (paired) null else RelFinding(RelVerdict.DENY, "R2-DIR-UNPAIRED", "两格不是同一对人的反向格")
        }
    }

    /** R3：无锚定只有陌生人低值，其余起始值必须有出处。 */
    fun anchored(cell: DirectedCell): RelFinding? {
        if (cell.anchor.anchored || cell.dims == RelationCatalog.STRANGER_START) return null
        return RelFinding(
            RelVerdict.DENY, "R3-NO-ANCHOR",
            cell.from + "→" + cell.to + " trust=" + cell.dims.trust +
                "：无人物设定也无 670 的格，只能是陌生人低值",
        )
    }

    /** R4：每类 relKind 的 ceiling；同事 trust 上限中（2）。 */
    fun ceiling(cell: DirectedCell): RelFinding? {
        if (!RelationRuleSwitch.enforceCeiling) return null
        val cap = RelationCatalog.CEILINGS.getValue(cell.kind)
        val rows = listOf(
            Triple("trust", cell.dims.trust, cap.trust),
            Triple("dependency", cell.dims.dependency, cap.dependency),
            Triple("intimacy", cell.dims.intimacy, cap.intimacy),
        )
        val hit = rows.firstOrNull { (_, value, limit) -> value > limit } ?: return null
        val (dim, value, limit) = hit
        return RelFinding(
            RelVerdict.DENY, "R4-CEILING-" + cell.kind.name + "-" + dim.uppercase(),
            cell.kind.label + "的 " + dim + " 上限" + Ceiling.tier(limit) + "，实为 " + value,
        )
    }

    /** R5：上升必须挂可指事件，且一条事件最多推一档。 */
    fun eventRise(before: DirectedCell, after: DirectedCell): RelFinding? {
        val growth = after.dims.trust - before.dims.trust +
            after.dims.dependency - before.dims.dependency +
            after.dims.intimacy - before.dims.intimacy
        if (growth <= 0 || !RelationRuleSwitch.requireEvent) return null
        val usable = after.events.size - before.events.size
        if (usable <= 0) {
            return RelFinding(RelVerdict.DENY, "R5-NO-EVENT", "trust/依赖/亲密上升 $growth 档，却没有挂任何可指事件")
        }
        if (usable < growth) {
            return RelFinding(RelVerdict.DENY, "R5-FEW-EVENTS", "上升 $growth 档只挂了 $usable 条事件")
        }
        return null
    }

    /** R6：refusal 可构造；拒绝/退回/保持距离只降不升。 */
    fun refusal(before: DirectedCell, after: DirectedCell): RelFinding? {
        if (after.status != RelationStatus.REFUSAL) return null
        val rise = after.dims.trust > before.dims.trust ||
            after.dims.intimacy > before.dims.intimacy ||
            after.dims.conflict < before.dims.conflict
        if (!rise) return null
        return RelFinding(RelVerdict.DENY, "R6-REFUSAL-RISE", "拒绝/退回却把 trust 或亲密升高了")
    }

    /** R7：rapport 与 relKind 分离；聊得来不自动变亲近。 */
    fun rapport(cell: DirectedCell): RelFinding? {
        if (cell.topicOverlap < 2) return null
        if (cell.togetherCount >= 2 && cell.privateEventCount > 0) return null
        val rank = if (RelationRuleSwitch.rapportPromotesKind && cell.rapport >= 3) {
            maxOf(cell.kind.rank, RelationKind.FRIEND.rank)
        } else {
            cell.kind.rank
        }
        if (rank <= RelationKind.ACQUAINTANCE.rank) return null
        return RelFinding(
            RelVerdict.DENY, "R7-RAPPORT-LEAK",
            "topicOverlap=" + cell.topicOverlap + " 但缺多次共处＋私人事件，relKind 最多到熟人",
        )
    }

    /** R8：topicOverlap 不等于 valueAlign；兴趣只影响 rapport。 */
    fun topicValue(text: String): RelFinding? {
        val hit = RelationGuard.topicValueHit(text) ?: return null
        return RelFinding(RelVerdict.DENY, "R8-TOPIC-VALUE", "把共同兴趣当价值认同推 trust（命中「$hit」）")
    }

    /** R9：明显脸盲与「一眼认出」互斥。 */
    fun face(cell: DirectedCell, text: String): RelFinding? {
        if (cell.anchor.face != FaceRecognition.OBVIOUS) return null
        val hit = RelationGuard.faceClaimHit(text) ?: return null
        return RelFinding(RelVerdict.DENY, "R9-FACE-CLAIM", "明显脸盲却写「$hit」：脸认档与描写矛盾")
    }

    /** R10： intimacy 不推性反应（907 S5 分离清单）。 */
    fun relationSex(text: String): RelFinding? {
        if (!RelationRuleSwitch.enforceRelationSex) return null
        val hit = RelationGuard.relationSexHit(text) ?: return null
        return RelFinding(RelVerdict.DENY, "R10-REL-SEX", "关系档不许推性反应（907 S5，$hit）")
    }

    /** 一张单的全量结论；before 为空时跳过变化类判据。 */
    fun all(
        cell: DirectedCell,
        shape: PairShape,
        text: String = "",
        before: DirectedCell? = null,
    ): List<RelFinding> {
        val out = mutableListOf<RelFinding>()
        fields(cell)?.let { out += it }
        direction(shape)?.let { out += it }
        anchored(cell)?.let { out += it }
        ceiling(cell)?.let { out += it }
        if (before != null) {
            eventRise(before, cell)?.let { out += it }
            refusal(before, cell)?.let { out += it }
        }
        rapport(cell)?.let { out += it }
        topicValue(text)?.let { out += it }
        face(cell, text)?.let { out += it }
        relationSex(text)?.let { out += it }
        return out
    }
}
