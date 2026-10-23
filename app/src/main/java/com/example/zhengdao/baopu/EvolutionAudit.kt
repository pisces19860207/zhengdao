// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八十八批（2026-10-20，§B 92 进化地基 ⑤）：**决策审计**——每条决策追加一行 JSONL。
//
// 记什么（派工单 §1）：
//   - 输入摘要：候选 id/kind/风险类别/证据 id＋自检结果/eval 分数/效果计数（只有 id 与数值）；
//   - 输出：建议/置信度/风险等级/是否人批/原因链（原因只带证据 id）；
//   - 操作者与时刻；以及当时的策略快照（L3 开关/过线值/样本下限），
//     这样以后开关变了也能解释「当时为什么免人批」。
//
// 不记什么：候选正文、target、用户原话、技能/人设内容、工具参数——一个字都不进来。
// 写失败不吞：决策口必须先落审计再把决策交出去，宁可这次决策不返回，也不留无账的决策。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** 当时生效的策略参数（审计用；只读快照）。 */
internal data class EvolutionPolicySnapshot(
    val l3AutoLowRisk: Boolean,
    val evalFloor: Double,
    val minEffectSamples: Int,
)

/** 一条决策审计：输入摘要＋输出＋是否人批＋操作者＋策略快照。 */
internal data class EvolutionAuditEntry(
    val atMs: Long,
    val operatorId: String,
    val policy: EvolutionPolicySnapshot,
    val input: EvolutionDecisionInput,
    val decision: EvolutionDecision,
) {
    init {
        require(operatorId.isNotBlank()) { "审计必须记操作者" }
    }

    /** JSONL 的一行（键名固定，测试逐个钉）。 */
    fun toJson(): JSONObject {
        val reasons = JSONArray()
        for (reason in decision.reasons) {
            reasons.put(
                JSONObject()
                    .put("code", reason.code.name)
                    .put("evidence_ids", JSONArray(reason.evidenceIds)),
            )
        }
        return JSONObject()
            .put("at_ms", atMs)
            .put("operator", operatorId)
            .put(
                "policy",
                JSONObject()
                    .put("l3_auto_low_risk", policy.l3AutoLowRisk)
                    .put("eval_floor", policy.evalFloor)
                    .put("min_effect_samples", policy.minEffectSamples),
            )
            .put("candidate_id", input.candidateId)
            .put("kind", input.kind.name)
            .put("risk_category", input.riskCategory.name)
            .put("evidence_ids", JSONArray(input.evidenceIds))
            .put("post_adoption", postAdoptionJson(input.postAdoption))
            .put(
                "input",
                JSONObject()
                    .put("self_check", selfCheckJson(input.selfCheck))
                    .put("eval", evalJson(input.eval))
                    .put(
                        "effect",
                        JSONObject()
                            .put("successes", input.effect.successes)
                            .put("failures", input.effect.failures)
                            .put("corrections", input.effect.corrections)
                            .put("retries", input.effect.retries)
                            .put("signal_ids", JSONArray(input.effect.signalIds)),
                    ),
            )
            .put("recommendation", decision.recommendation.name)
            .put("confidence", decision.confidence)
            .put("risk_class", decision.riskClass.name)
            .put("requires_approval", decision.requiresApproval)
            .put("reasons", reasons)
    }

    private fun selfCheckJson(selfCheck: SelfCheckEvidence?): Any =
        if (selfCheck == null) {
            JSONObject.NULL
        } else {
            JSONObject()
                .put("passed", selfCheck.passed)
                .put("rollback_available", selfCheck.rollbackAvailable)
                .put("case_ids", JSONArray(selfCheck.caseIds))
                .put("rule_ids", JSONArray(selfCheck.failedRuleIds))
        }

    private fun evalJson(eval: EvolutionEval?): Any =
        if (eval == null) {
            JSONObject.NULL
        } else {
            JSONObject()
                .put("score", eval.score)
                .put("case_ids", JSONArray(eval.caseIds))
                // 第一百六十八批：来源与日期**键恒在**（同 postAdoptionJson 的口径）——
                // 「这条决策当时看的是谁产的证据」以后要能回答；缺键＝老版本写的行，
                // 与「有证据但不知道日期」是两件事。
                .put("source", eval.source.name)
                .put("ranAt", eval.ranAt ?: JSONObject.NULL)
        }

    /**
     * 第一百批：采纳后效果（null ⇒ `null`，键**恒在**）。
     *
     * 键恒在是有意的：以后要问「这条决策当时看过采纳后效果吗」，缺键和 null 是两件事
     * （缺键＝老版本写的行，null＝看了、没有）。键名与 [PostAdoptionEvent] 的 JSONL 同一把尺。
     */
    private fun postAdoptionJson(post: PostAdoptionEffect?): Any =
        if (post == null) {
            JSONObject.NULL
        } else {
            JSONObject()
                .put("event_id", post.eventId)
                .put("verdict", post.verdict.name)
                .put("successes", post.successes)
                .put("failures", post.failures)
                .put("corrections", post.corrections)
                .put("retries", post.retries)
                .put("window_uses", post.windowUses)
                .put("window_days", post.windowDays)
        }
}

/** 审计落点（测试注入内存实现）。 */
internal fun interface EvolutionAuditSink {
    fun record(entry: EvolutionAuditEntry)
}

/**
 * 追加式 JSONL 审计（`抱朴记忆/决策审计.jsonl`）。
 *
 * 只追加、不覆盖、不改历史行；父目录不存在就建。写入失败向上抛——
 * 由决策口决定「这次决策不返回」，而不是静默丢账。
 */
internal class EvolutionAuditLog(private val file: File) : EvolutionAuditSink {

    @Synchronized
    override fun record(entry: EvolutionAuditEntry) {
        file.parentFile?.mkdirs()
        file.appendText(entry.toJson().toString() + "\n", Charsets.UTF_8)
    }

    /**
     * 第一百零四批（UI P0）：**用户动作**审计行（99 批报告 §6「随 UI 批补」）。
     *
     * 与判定行（[record]）同仓不同形：判定行记「机器建议什么」，动作行记
     * 「人对着卡按了什么」。键名固定（candidate_id/action/risk_class/
     * recommendation），写入失败只由调用方 log——动作本身已经发生了，
     * 账本卡住不回滚用户的选择（留痕是账本不是闸门）。
     *
     * @param action adopt / reject / defer（小写稳定键；效果卡的 keep/later/rollback
     *   走 [com.example.zhengdao.baopu.PostAdoptionLog]，不在这里重复记账）
     */
    @Synchronized
    fun recordAction(
        candidateId: String,
        action: String,
        riskClass: String,
        recommendation: String,
    ) {
        file.parentFile?.mkdirs()
        val row = JSONObject()
            .put("at_ms", System.currentTimeMillis())
            .put("candidate_id", candidateId)
            .put("action", action)
            .put("risk_class", riskClass)
            .put("recommendation", recommendation)
        file.appendText(row.toString() + "\n", Charsets.UTF_8)
    }

    fun file(): File = file

    companion object {
        /** 审计文件名；与记忆/技能同一棵目录，用户可看可备份。 */
        const val FILE_NAME = "决策审计.jsonl"

        /** 生产落点：`<App私有>/抱朴记忆/决策审计.jsonl`（本批不接线，由调用方传根）。 */
        fun under(globalRoot: File): EvolutionAuditLog = EvolutionAuditLog(File(globalRoot, FILE_NAME))
    }
}
