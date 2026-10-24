// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十三批 · 报告（设计 §8／§9）。
//
// schema 1 的读写与落盘。判定只写在这一份 JSON 里：
// `autoPromote=false`、`requiresHumanApproval=true` 是硬编码常量，不随判定变化。
package com.example.zhengdao.baopu.evolution.eval

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object EvolutionReport {

    const val SCHEMA = "zhengdao-evolution-eval/1"

    /** 设计 §6：ADOPT 不是晋升，必须人批。 */
    const val AUTO_PROMOTE = false
    const val REQUIRES_HUMAN_APPROVAL = true

    /** 默认落在 app 模块的 `build/reports/evolution/`（gitignored）。 */
    fun defaultDir(): File = File("build/reports/evolution")

    fun build(
        candidate: EvolutionCandidate,
        config: EvolutionConfig,
        judgement: EvolutionJudgement,
        baseline: ArmResult?,
        candidateArm: ArmResult?,
        comparisons: List<CaseComparison>,
        ranAt: Long,
    ): JSONObject {
        val out = JSONObject()
            .put("schema", SCHEMA)
            .put("candidateId", candidate.candidateId)
            .put("kind", candidate.kind.name)
            .put("target", candidate.target)
            .put("autoPromote", AUTO_PROMOTE)
            .put("requiresHumanApproval", REQUIRES_HUMAN_APPROVAL)
            .put("config", config.toJson())
            .put("baseline", baseline?.let { armJson(it) } ?: JSONObject.NULL)
            .put("candidate", candidateArm?.let { armJson(it) } ?: JSONObject.NULL)
        when (judgement) {
            is EvolutionJudgement.Decided -> {
                out.put("verdict", judgement.verdict)
                out.put("reasons", reasonsJson(judgement.reasons))
                out.put("delta", deltaJson(baseline, candidateArm, comparisons))
                out.put("cases", casesJson(comparisons))
            }

            is EvolutionJudgement.Refused -> {
                out.put("verdict", JSONObject.NULL)
                out.put("reasons", reasonsJson(listOf(EvolutionReason(judgement.code))))
                out.put("delta", JSONObject.NULL)
                out.put("cases", JSONArray())
                out.put("refused", judgement.reason)
            }
        }
        out.put("ranAt", ranAt)
        return out
    }

    fun write(dir: File, report: JSONObject): File {
        val id = report.getString("candidateId")
        // 只挡路径分隔符与 Windows 非法字符；中文 id 原样保留（设计 §8 的 <候选id>.json）。
        val safe = id.replace(Regex("[\\\\/:*?\"<>|]"), "_")
        dir.mkdirs()
        val f = File(dir, "$safe.json")
        f.writeText(report.toString(2) + "\n", Charsets.UTF_8)
        return f
    }

    fun read(file: File): JSONObject = JSONObject(file.readText(Charsets.UTF_8))

    private fun armJson(a: ArmResult): JSONObject = JSONObject()
        .put("ran", a.ran)
        .put("passed", a.passed)
        .put("passRate", round(a.passRate, 3))
        .put("avgSteps", round(a.avgSteps, 2))
        .put("tokensIn", a.tokensIn)
        .put("tokensOut", a.tokensOut)
        .put("artifactOk", round(a.artifactOk, 3))
        .put("dangerAsked", a.dangerAsked)
        .put("dangerEscaped", a.dangerEscaped)
        .put("elapsedMs", a.elapsedMs)

    private fun deltaJson(
        baseline: ArmResult?,
        candidate: ArmResult?,
        comparisons: List<CaseComparison>,
    ): Any {
        if (baseline == null || candidate == null) return JSONObject.NULL
        return JSONObject()
            .put("passRate", round(candidate.passRate - baseline.passRate, 3))
            .put("avgSteps", round(candidate.avgSteps - baseline.avgSteps, 2))
            .put("tokensIn", candidate.tokensIn - baseline.tokensIn)
            .put("artifactOk", round(candidate.artifactOk - baseline.artifactOk, 3))
            .put("dangerAsked", candidate.dangerAsked - baseline.dangerAsked)
            .put("improvements", comparisons.count { !it.baselineOk && it.candidateOk })
            .put("regressions", comparisons.count { it.baselineOk && !it.candidateOk })
    }

    private fun casesJson(comparisons: List<CaseComparison>): JSONArray = JSONArray().also { arr ->
        for (c in comparisons) {
            arr.put(
                JSONObject()
                    .put("id", c.id)
                    .put("baselineOk", c.baselineOk)
                    .put("candidateOk", c.candidateOk)
                    .put("baselineSteps", c.baselineSteps)
                    .put("candidateSteps", c.candidateSteps)
                    .put("failures", JSONArray(c.failures)),
            )
        }
    }

    private fun reasonsJson(reasons: List<EvolutionReason>): JSONArray = JSONArray().also { arr ->
        for (r in reasons) {
            arr.put(JSONObject().put("code", r.code).put("caseIds", JSONArray(r.caseIds)))
        }
    }

    private fun round(v: Double, digits: Int): Double {
        var factor = 1.0
        repeat(digits) { factor *= 10 }
        return Math.round(v * factor) / factor
    }
}

/** 编排：候选 → 两臂 → 判定 → 报告（可选落盘）。 */
internal object EvolutionEval {

    data class Outcome(
        val report: JSONObject,
        val judgement: EvolutionJudgement,
        val baseline: ArmResult?,
        val candidate: ArmResult?,
    )

    fun evaluate(
        candidate: EvolutionCandidate,
        cases: List<EvolutionCase>,
        casesSha256: String,
        policy: FakePolicyModel,
        rootFactory: () -> File = { java.nio.file.Files.createTempDirectory("zhengdao-evo-").toFile() },
        reportDir: File? = null,
        ranAt: Long = System.currentTimeMillis(),
    ): Outcome {
        val config = EvolutionConfig.of(policy, casesSha256, candidate)

        // 设计 §14.3：162 未交付前，真实 MEMORY_NOTE / PLAN_RULE 候选没有生产来源 ⇒ DEFER。
        if (candidate.mechanismOnly) {
            val judgement = EvolutionJudgement.Decided(
                EvolutionVerdict.DEFER,
                listOf(EvolutionReason(EvolutionReasons.CANDIDATE_NOT_READY)),
            )
            val report = EvolutionReport.build(
                candidate, config, judgement, null, null, emptyList(), ranAt,
            )
            if (reportDir != null) EvolutionReport.write(reportDir, report)
            return Outcome(report, judgement, null, null)
        }

        val runner = EvolutionRunner(cases, policy, rootFactory)
        val baseline = runner.run(null, "baseline")
        val candidateArm = runner.run(candidate, "candidate")
        val comparisons = EvolutionCompare.compareCases(baseline, candidateArm, cases)
        val judged = EvolutionCompare.judge(baseline, candidateArm, cases, config, config)
        val report = EvolutionReport.build(
            candidate, config, judged, baseline, candidateArm, comparisons, ranAt,
        )
        if (reportDir != null) EvolutionReport.write(reportDir, report)
        return Outcome(report, judged, baseline, candidateArm)
    }
}
