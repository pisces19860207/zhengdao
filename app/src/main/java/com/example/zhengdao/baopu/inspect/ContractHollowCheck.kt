// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十九批 I3 · 契约空洞。
//
// 查什么（派工单 §1 原行）：技能的 `appliesWhen`/`success` 为空或恒真
// （如 `success` 写成"完成"）⇒ 静态检查＋人工抽检，**抽 5 条**，命中 1 条即报。
//
// 判据**一条都不自造**：什么叫"空"、什么叫"恒真"，233 批已经写在 `SkillContract` 里了——
// `VAGUE_SUCCESS_VALUES`（"完成/成功了/done/completed…"那一组）在 parse 期就被判
// "不是可观察判据"，`missingSections()` 管三段里哪段一条都没有。纠察只做一件事：
// 把抽到的技能逐条喂给 `SkillContract.validate`，非空即空洞。
//
// 为什么必须复用它而不是另写一份模糊词表：同一句 `success=完成`，两处口径若不同，
// 技能页显示"齐"而巡按显示"空洞"，人就会开始不信巡按——那第三权就白立了。
//
// 只读：本文件连一个写出口都没有。契约空洞的修法是**建议**（§2.3），
// 由人或模型自己改（233 的修订门槛 + 236 的 C 类禁自改也都还在原位）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.SkillContract
import com.example.zhengdao.baopu.SkillStore

/** 一条待抽的技能样本（正文取自 `SKILL.md`，两层都要：项目优先、全局兜底）。 */
internal data class SkillSample(
    val name: String,
    val body: String,
    /** `project`／`global`（只给人看归层，不参与判定）。 */
    val scope: String = "",
)

/** I3 的结果。 */
internal data class ContractHollowResult(
    val sampled: Int,
    val hollow: List<Pair<SkillSample, List<String>>>,
)

internal object ContractHollowCheck {

    /** 抽几条（派工单写死 5）。 */
    const val SAMPLE_K = InspectLimits.SKILL_SAMPLE

    /** 草稿态的字面值（`SkillStore.statusOf` 交的是 String，没有导出常量；这里只读不写）。 */
    const val STATUS_DRAFT = "draft"

    /** 这一条技能空不空洞；空 ⇒ 给出问题清单（复用 233 的验收清单，一条不新增）。 */
    fun problemsOf(sample: SkillSample): List<String> = SkillContract.validate(sample.body)

    fun sample(pool: List<SkillSample>, k: Int = SAMPLE_K): List<SkillSample> =
        InspectSample.spread(pool, k)

    fun run(pool: List<SkillSample>, k: Int = SAMPLE_K): ContractHollowResult {
        val picked = sample(pool, k)
        val hollow = picked.mapNotNull { s -> problemsOf(s).takeIf { it.isNotEmpty() }?.let { s to it } }
        return ContractHollowResult(picked.size, hollow)
    }

    /** 明细那一行：技能名＋层＋状态短标签＋逐条问题（问题原文来自 233，不改写）。 */
    private fun evidenceOf(sample: SkillSample, problems: List<String>): String {
        val layer = if (sample.scope.isEmpty()) "" else "（${sample.scope}层）"
        val draft = if (SkillStore.statusOf(sample.body) == STATUS_DRAFT) "·草稿" else ""
        return "「${sample.name}」$layer$draft：${problems.joinToString("；")}"
    }

    fun check(pool: List<SkillSample>, k: Int = SAMPLE_K): InspectFinding {
        val res = run(pool, k)
        if (res.sampled == 0) {
            return InspectFinding(
                check = InspectCheck.CONTRACT_HOLLOW,
                verdict = InspectVerdict.NO_DATA,
                oneLine = InspectText.line("契约空洞：技能目录是空的，无从抽"),
                suggestion = "有技能了再巡（空目录不算通过）",
                count = 0,
            )
        }
        val hits = res.hollow.size
        val evidence = res.hollow.take(InspectDetailLimits.MAX_EVIDENCE_PER_FINDING)
            .map { (s, p) -> evidenceOf(s, p) }
        return if (!InspectSample.reportable(hits, InspectLimits.SAMPLE_MIN_HITS)) {
            InspectFinding(
                check = InspectCheck.CONTRACT_HOLLOW,
                verdict = InspectVerdict.CLEAR,
                oneLine = InspectText.line("契约空洞 0 条（抽 ${res.sampled} 条技能）"),
                suggestion = "无需处理",
                evidence = listOf("抽样 ${res.sampled}/${pool.size} 条技能，契约三段齐且判据可观察"),
                count = 0,
            )
        } else {
            val first = res.hollow.first()
            InspectFinding(
                check = InspectCheck.CONTRACT_HOLLOW,
                verdict = InspectVerdict.HIT,
                oneLine = InspectText.line("契约空洞 $hits 条：「${first.first.name}」${first.second.first()}"),
                suggestion = "补一条可观察判据（element_appears/text_nonempty），空口「完成」不算",
                evidence = evidence,
                count = hits,
            )
        }
    }
}
