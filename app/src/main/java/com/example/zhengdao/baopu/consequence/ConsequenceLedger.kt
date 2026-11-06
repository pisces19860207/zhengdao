// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零六批（2026-11-07）· 后果治理的落账口。
//
// 这一层的唯一职责：把判据层的成品折成 208 的 [DecisionDraft]，并且**只折、不落**——
// 落盘仍由 `DecisionLedger` 那一处写口做（208 的通用账全仓只有一个 `record(` 调用点，
// 多开一处就是 `TaskGrantWiringGateTest` 与 291 部位普查点名的那种「假绿」）。
//
// kind 的取舍（派工单没点，本批自己定的，逐条给理由）：
//   · 灰度档／深度档 ⇒ **复用** [DecisionKind.TOOL_GRANT]：这两件事记的都是「这批动作被放行到哪一步」，
//     与 104 那一行同语义（样板：`AllowanceLadder.draftOf` 同款复用，见其 KDoc「复用，不新造语义」）；
//   · 赔付／身份状态／参谋意见 ⇒ **新开三个 kind**（`compensation`／`identity_state`／`advisor_opinion`）：
//     那三件事的主语不是「一批动作」，硬塞进授权行会让抽检筛错——
//     尤其身份撤销，写成 tool_grant 就长得像「收回一批额度」，而后果完全不是一件事。
//
// 分家靠 evidence 前缀（一格一眼）：`canary:`／`depth:`／`compensation:`／`identity:`／`advisorOpinion:`。
// 前缀是**稳定键**：改字面量＝老账读不出来（同 `AllowanceLadder.EVIDENCE_PREFIX` 的纪律）。
//
// 档位读回来读的是账、不是 SharedPreferences（派工单 §二·② 的字面意思）：
// 见 [currentCanaryBand]——理由抄 `PatrolSources.currentBand` 那条，一个字都不必改。
package com.example.zhengdao.baopu.consequence

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionRecord

/** 208 里这一族账的 evidence 前缀（五族各一个，互不覆盖）。 */
internal object ConsequenceEvidence {
    const val CANARY: String = "canary:"
    const val CANARY_RUN: String = "canary-run:"
    const val DEPTH: String = "depth:"
    const val COMPENSATION: String = "compensation:"
    const val IDENTITY: String = "identity:"
    const val ADVISOR: String = "advisorOpinion:"

    /** 前缀后面第一格是能力名，读到下一个冒号为止（能力名里没有冒号 ⇒ 两格就够）。 */
    fun capabilityOf(evidence: String, prefix: String): String? {
        if (!evidence.startsWith(prefix)) return null
        val rest = evidence.removePrefix(prefix)
        return rest.substringBefore(':').ifBlank { null }
    }
}

/**
 * 落账口。
 *
 * 每个 `draftOf` 都遵守同三条：
 *   1. **判据层拒的，这里就不产草稿**（返回 null）——不给「先落一行再说」留路；
 *   2. `evidence` 一定非空（208 的缺一不落会把它挡在写口外，那是兜底，不是设计）；
 *   3. 可回滚的行必须给 `rollbackRef`（`L16ReversibleLedgerGateTest` 门④钉全仓构造点）。
 */
internal object ConsequenceLedger {

    // ── ① 灰度档位（复用 tool_grant）──────────────────────────────────────

    /** 档位行的可回滚点：依据那一轮读数的 evidence 串（退回上一档＝拿它反查）。 */
    fun draftOf(c: CanaryChange): DecisionDraft? {
        if (CanaryLadder.refusalOf(c) != null) return null
        val from = CanaryBand.ofWire(c.before) ?: return null
        val to = CanaryBand.ofWire(c.after) ?: return null
        return DecisionDraft(
            kind = DecisionKind.TOOL_GRANT,
            actor = DecisionActor.ofWire(c.actor),
            reason = "灰度档位 ${from.cn}→${to.cn}（${c.capability}：${c.metrics.runs} 条／失败 ${CanaryLadder.pct(c.metrics.failRatePct)}%）",
            evidence = "${ConsequenceEvidence.CANARY}${c.capability}:${to.wire}:${c.trigger}",
            before = from.wire,
            after = to.wire,
            atMs = c.atMs,
            rollbackRef = "${ConsequenceEvidence.CANARY}${c.capability}:${from.wire}",
            // 取不到读数时不许落，已由 CanaryLadder.refusalOf 挡在前面；这一格是给门④的回滚点声明。
            reversible = true,
        )
    }

    /**
     * 现在生效的灰度档（**从账读回来**，不是从开关读）。
     *
     * 只认**没被回滚过**的档位行：`rolledBack` 是 222-G3 那条读侧降级，
     * 被回滚的档位行还有效就等于「撤销只改得动账、改不动开关」——那是本函数存在的理由。
     * 一条都没有 ⇒ 回落到登记表里的缺省档（①「缺省＝关闭或灰度」的落点就在这最后一行）。
     */
    fun currentCanaryBand(rows: List<DecisionRecord>, capability: String): CanaryBand {
        val prefix = "${ConsequenceEvidence.CANARY}$capability:"
        val latest = rows
            .filter { it.kind == DecisionKind.TOOL_GRANT && !it.rolledBack && it.evidence.startsWith(prefix) }
            .maxByOrNull { it.atMs }
            ?: return CanaryCapability.ofWire(capability)?.defaultBand ?: CanaryBand.OFF
        return CanaryBand.ofWire(latest.after) ?: CanaryCapability.ofWire(capability)?.defaultBand ?: CanaryBand.OFF
    }

    // ── ② 灰度观察样本（指标的唯一来源，派工单 §二·②）─────────────────────

    /**
     * 一次灰度执行的观察行。
     *
     * `reversible=false` 是**明写**的：观察行不回滚——回滚一次「它跑过」这件事没有意义，
     * 而这一格不声明就会被 [com.example.zhengdao.baopu.constitution.L16ReversibleLedgerGateTest]
     * 的门④按 AUTO_ACT 默认可回滚处理，逼出一条假回滚通道。
     */
    fun draftOf(s: CanaryObservation, atMs: Long): DecisionDraft {
        val mark = if (s.ok) "ok" else "fail"
        val ask = if (s.askedHuman) ":ask" else ""
        val loss = if (s.permanentLoss) ":loss" else ""
        return DecisionDraft(
            kind = DecisionKind.AUTO_ACT,
            actor = DecisionActor.AUTO,
            reason = "灰度观察 ${s.capability}：$mark$ask$loss",
            evidence = "${ConsequenceEvidence.CANARY_RUN}${s.capability}:$mark$ask$loss",
            before = "",
            after = mark,
            atMs = atMs,
            reversible = false,
        )
    }

    /** 账行 ⇒ 观察样本（读侧唯一一条路，内存计数从这里进不来）。 */
    fun samplesOf(rows: List<DecisionRecord>, capability: String): List<CanarySample> {
        val prefix = "${ConsequenceEvidence.CANARY_RUN}$capability:"
        return rows.filter { it.evidence.startsWith(prefix) }.map { r ->
            CanarySample(
                atMs = r.atMs,
                ok = r.after == "ok",
                askedHuman = r.evidence.contains(":ask"),
                permanentLoss = r.evidence.contains(":loss"),
            )
        }
    }

    // ── ③ 深度档（复用 tool_grant）────────────────────────────────────────

    fun draftOf(c: DepthChange): DecisionDraft? {
        if (DepthGate.refusalOf(c) != null) return null
        return DecisionDraft(
            kind = DecisionKind.TOOL_GRANT,
            actor = DecisionActor.ofWire(c.actor),
            reason = "进化深度 ${c.before}→${c.after}（依据 ${c.basis.joinToString("＋")}）",
            evidence = "${ConsequenceEvidence.DEPTH}${c.capability}:${c.after}:${c.readings.joinToString("；")}",
            before = c.before,
            after = c.after,
            atMs = c.atMs,
            rollbackRef = "${ConsequenceEvidence.DEPTH}${c.capability}:${c.before}",
            reversible = true,
        )
    }

    /** 现在生效的深度档（同样从账读；没账 ⇒ [EvolutionDepth.DEFAULT]＝浅，追加判据②）。 */
    fun currentDepth(rows: List<DecisionRecord>, capability: String): EvolutionDepth {
        val prefix = "${ConsequenceEvidence.DEPTH}$capability:"
        val latest = rows
            .filter { it.kind == DecisionKind.TOOL_GRANT && !it.rolledBack && it.evidence.startsWith(prefix) }
            .maxByOrNull { it.atMs }
            ?: return EvolutionDepth.DEFAULT
        return EvolutionDepth.ofWire(latest.after) ?: EvolutionDepth.DEFAULT
    }

    // ── ④ 赔付（新 kind）──────────────────────────────────────────────────

    /**
     * 赔付行。
     *
     * **四件缺一不落**（派工单 §一 的硬口径）：判据层的 [Compensation.defects] 非空 ⇒ 这里返回 null，
     * 于是「半条赔付」在代码里没有落盘路径。缺哪一件由调用方把 [Compensation.defects] 打进日志——
     * 这一句不许省：不落账也不说为什么，是这一族最容易烂成的样子。
     */
    fun draftOf(c: Compensation, atMs: Long): DecisionDraft? {
        if (c.defects().isNotEmpty()) return null
        val loss = if (c.lossList.isEmpty()) "" else "／永久损失 ${c.lossList.size} 项"
        val gate = if (c.complete && c.prevention?.isMachineChecked == true) "·机检" else ""
        return DecisionDraft(
            kind = DecisionKind.COMPENSATION,
            actor = DecisionActor.AUTO,
            reason = "赔付 ${c.verdict.cn}：${c.tool}／${c.opId}$loss",
            evidence = "${ConsequenceEvidence.COMPENSATION}${c.opId}:${c.verdict.wire}" +
                "${c.prevention?.let { ":${it.target.wire}:${it.entryId}$gate" } ?: ""}",
            before = c.tool,
            after = c.verdict.wire,
            atMs = atMs,
            // kind 自己说不可回滚（见 DecisionDraft.kt 的 COMPENSATION 注释），这里不声明也不给点。
            rollbackRef = "",
            reversible = false,
        )
    }

    /**
     * 永久损失清单的文本行（①要求的那张表；装配层把它写进 `赔付/<opId>.txt`）。
     *
     * 为什么清单不进 `evidence` 而进文件：evidence 是**稳定键**，靠 `prefix:字段:字段` 切格读档，
     * 往里塞任意长度的路径列表＝把读侧的解析器交给别人的文件名。账行只留 opId＋条数，
     * 全文另存——「账指路、文件装内容」是本仓 262 证据快照已经用过的那一路。
     * [lastModifiedText] 由调用方按本地时区格式化后传入（本层不看时钟）。
     */
    fun lossLinesOf(c: Compensation, lastModifiedText: (Long) -> String): List<String> =
        c.lossList.map { it.line(lastModifiedText(it.lastModifiedMs)) }

    // ── ⑤ 身份状态（新 kind，**永不可回滚**）──────────────────────────────

    /**
     * 身份变更行（生成／撤销／解除撤销／轮换）。
     *
     * `reversible=false` 写死在这里，不是漏了：判据⑤要「模型调解除撤销 ⇒ 拒」，
     * 而决策中心那颗通用撤销钮是第二条解除通道。第二条通道存在，第一条那条判据就没有牙。
     */
    fun draftOf(e: IdentityEvent, rows: List<IdentityRow>, atMs: Long): DecisionDraft {
        val active = IdentityRules.activeId(rows)
        // 撤销那一支的 `after` 必须是**空**，不许回落成"刚被撤掉的那个 id"：
        // 决策中心按 before/after 读「现在在用谁」，回落一次就把作废的身份显示成在用。
        val after = if (e.op == IdentityOp.REVOKE) "" else active.ifBlank { e.targetId }
        return DecisionDraft(
            kind = DecisionKind.IDENTITY_STATE,
            actor = DecisionActor.ofWire(e.actor),
            reason = "身份${e.op.cn}：${e.previousId.ifBlank { "无" }}→${after.ifBlank { "无（没有在用身份）" }}",
            evidence = "${ConsequenceEvidence.IDENTITY}${e.op.wire}:${e.targetId}",
            before = e.previousId,
            after = after,
            atMs = atMs,
            rollbackRef = "",
            reversible = false,
        )
    }

    /** 撤销发生后，被这张闸拦住的凭据条数（②「全失效」的可核对读数）。 */
    fun denialNote(denied: Int): String = "身份撤销 ⇒ 名下 $denied 条凭据全失效（历史账照读，一行未删）"

    // ── ⑥ 参谋意见（新 kind；被拦也要记）──────────────────────────────────

    /**
     * 参谋意见行。`blocked=true` 时理由里带上被拦的原因——
     * 追加判据⑤要的是「被拦**并记账**」，只拦不记等于这条判据没人验过。
     */
    fun draftOf(o: AdvisorOpinion, rejection: String?): DecisionDraft {
        val state = if (rejection == null) "accepted" else "blocked"
        val why = rejection ?: o.reason
        return DecisionDraft(
            kind = DecisionKind.ADVISOR_OPINION,
            actor = DecisionActor.ofWire(o.advisor),
            reason = "参谋建议 ${o.advice}（$state）：$why",
            evidence = "${ConsequenceEvidence.ADVISOR}${o.advisor}:$state:${o.atMs}",
            before = if (o.aboutOwnOutput) "own-output" else "others-output",
            after = state,
            atMs = o.atMs,
            rollbackRef = "",
            reversible = false,
        )
    }
}

/** 一次灰度观察（落账形状；判据层用 [CanarySample]，两者字段一致，转换只在这一处）。 */
internal data class CanaryObservation(
    val capability: String,
    val ok: Boolean,
    val askedHuman: Boolean = false,
    val permanentLoss: Boolean = false,
)

/** 身份动作的账用名（线值与 [IdentityRules] 的三个 op 一一对应，不另造）。 */
internal enum class IdentityOp(val wire: String, val cn: String) {
    ISSUE("issue", "生成"),
    REVOKE("revoke", "撤销"),
    UNREVOKE("unrevoke", "解除撤销"),
    ROTATE("rotate", "轮换"),
    ;

    companion object {
        fun ofWire(wire: String): IdentityOp? = entries.firstOrNull { it.wire == wire }
    }
}

/** 一次身份事件（[previousId] 空＝首启；轮换时填旧 id）。 */
internal data class IdentityEvent(
    val op: IdentityOp,
    val actor: String,
    val targetId: String,
    val previousId: String = "",
)
