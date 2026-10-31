// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十一批 · 右相反向监督②——反思器**定期审右相判据**的锚定回归件（派工单 311 §二）。
//
// 防「裁判被选手影响」：右相的判据不能被学习器/被审对象软化为它方便的样子。
// 手段＝**锚定样本**：一批带「标准答案」的案由（期望三态），定期拿右相重判一遍，
// 一致率比基线**下降**超过 [JudgeRegression.DROP_EPSILON] ⇒ 报「裁判退化」（判据④，造数据可验）。
//
// 与学习器的边界（同批反向监督①）：
//   - 锚定样本的**期望值只由人/反思器定**，学习器的结论没有喂进来的口子；
//   - 本文件在 verify/ 包里，同守 verify 包源级门禁（零模型、零时钟、零写盘——rate 的分母
//     是样本数，不是时间）。
//
// 房型纪律：零 IO（样本与判官都由调用方给）、判不了照实进 disagreements、不藏不抹。
package com.example.zhengdao.baopu.verify

/** 一条锚定样本：案由一句人话 ＋ 标准答案（期望三态）。期望值只由人/反思器定。 */
internal data class AnchoredCase(
    val id: String,
    /** 案由描述（交给判官的唯一输入；不携带任何「学习器怎么说」）。 */
    val claim: String,
    val expected: ChancellorVerdict,
) {
    init {
        require(id.isNotBlank()) { "锚定样本缺 id" }
        require(claim.isNotBlank()) { "锚定样本缺案由描述" }
    }
}

/** 判官口：生产接右相（ChancellorGate），单测注假判官造一致率读数。 */
internal fun interface AnchoredJudge {
    fun verdict(case: AnchoredCase): ChancellorVerdict
}

/** 一次锚定回归的读数（退化的判定只看 [rate] 对 [baselineRate]）。 */
internal data class RegressionReadout(
    val total: Int,
    val agreed: Int,
    /** 一致率 = 同意数 / 样本数（样本空在入口就拒，不产出 0/0 的假读数）。 */
    val rate: Double,
    /** 本次比对用的基线；首轮（还没有基线）为 null ⇒ 不判退化，由调用方把 rate 存成新基线。 */
    val baselineRate: Double?,
    /** 基线存在且一致率比基线掉得超过 [JudgeRegression.DROP_EPSILON] ⇒ 报「裁判退化」。 */
    val degraded: Boolean,
    /** 不一致的样本（id ＋ 期望 vs 实判）；退化报告必须带上，供人看错在哪。 */
    val disagreements: List<String>,
)

/** 反思器审右相判据的唯一入口（纯函数：样本、判官、基线全由调用方给）。 */
internal object JudgeRegression {

    /** 一致率比基线下降超过这个数（百分点）⇒ 退化。 */
    const val DROP_EPSILON = 0.05

    fun run(cases: List<AnchoredCase>, judge: AnchoredJudge, baselineRate: Double?): RegressionReadout {
        require(cases.isNotEmpty()) { "锚定回归没有样本：没有可判的东西" }
        require(baselineRate == null || baselineRate in 0.0..1.0) { "基线一致率不在 0..1：$baselineRate" }
        val disagreements = ArrayList<String>()
        for (c in cases) {
            val actual = judge.verdict(c)
            if (actual != c.expected) {
                disagreements += "${c.id}：期望 ${c.expected}，实判 $actual"
            }
        }
        val agreed = cases.size - disagreements.size
        val rate = agreed.toDouble() / cases.size
        val degraded = baselineRate != null && rate < baselineRate - DROP_EPSILON
        return RegressionReadout(
            total = cases.size,
            agreed = agreed,
            rate = rate,
            baselineRate = baselineRate,
            degraded = degraded,
            disagreements = disagreements,
        )
    }
}
