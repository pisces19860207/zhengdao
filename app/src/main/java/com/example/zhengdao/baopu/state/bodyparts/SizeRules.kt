// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百二十批 · 判据：只回答「这张尺寸单自相矛盾不矛盾」，不下生理结论、不进闸、不落库。
//
// 本单的判据形状与 805 相反：805 是「检出矛盾」，本单是「证明没有规则」。
// 所以这里把「没有推导边」写成一张**可数的空矩阵**（[IndependenceMatrix]）：
// 埋没四档 × 三条尺寸轴各四档 ＝ 48 格，每一格都是过。空规则也要有形状，
// 否则「两个字段独立」这句话无法被测试遍历，也没法在有人偷加规则时立刻变红。
//
// 唯一真会变红的来源是四向推导（身高／体重／骨架／年龄）。那四条不在数据里判，
// 在装配点与源码门禁里判：SizeGate 只读 context.adult 一处，SizeSourceAssertTest 数着它。
package com.example.zhengdao.baopu.state.bodyparts

/**
 * 一条结论。构造即守：有代码、有理由，理由里没有长度单位、没有判定词、没有因果句、没有混写颜色。
 */
data class SizeFinding(
    val verdict: Verdict,
    val code: String,
    val why: String,
    val source: String = SIZE_SOURCE,
    val asOf: String = SIZE_AS_OF,
) {
    init {
        require(code.isNotBlank()) { "结论缺代码：$NO_SOURCE" }
        require(why.isNotBlank()) { "结论缺理由：$NO_SOURCE" }
        BodyPartGuard.rejectMeasures(why)
        BodyPartGuard.rejectDiagnosis(why)
        SizeGuard.rejectCausation(why)
        SizeGuard.rejectMergedColor(why)
    }

    val attributedSource: String get() = source.ifBlank { NO_SOURCE }

    fun line(): String =
        "[" + verdict.label + "] " + code + "：" + why + "（" + attributedSource + " " + asOf + "）"
}

/**
 * 独立性矩阵：埋没档 × 尺寸档。
 *
 * **每一格都是过**——这就是判据③的机器形状：埋没看起来怎么样，与本体尺寸多大之间没有边。
 * 两条被点名的禁令都落在这里（写法上不是加判断，而是「不加判断」）：
 * · 埋没明显 ⇒ 尺寸小：矩阵里 (明显, 小) 是过；
 * · 尺寸大 ⇒ 无埋没：矩阵里 (无, 大) 与 (很埋, 大) 都是过。
 */
object IndependenceMatrix {

    /** 埋没四档。 */
    const val BURIED_COUNT: Int = 4

    /** 长短／粗细／龟头相对各四档。 */
    const val SIZE_COUNT: Int = 4

    /** 三条尺寸轴各一张四乘四矩阵。 */
    const val CELLS: Int = BURIED_COUNT * SIZE_COUNT * 3

    fun verdict(buried: BuriedLook, tier: Int): Verdict {
        require(tier in 0 until SIZE_COUNT) { "尺寸档只有 0–3：收到 $tier" }
        require(buried.level in 0 until BURIED_COUNT) { "埋没档只有 0–3" }
        return Verdict.PASS
    }
}

/** 判据集合。全部纯函数：同一张单子跑两遍，结论逐字一致。 */
object SizeRules {

    /** 三条尺寸轴与各自取值，给矩阵逐条过一遍。 */
    fun inspect(profile: SizeProfile): List<SizeFinding> {
        val checks = listOf(
            AxisCheck("长短", profile.buriedLook, profile.length.level),
            AxisCheck("粗细", profile.buriedLook, profile.girth.level),
            AxisCheck("龟头相对大小", profile.buriedLook, profile.glans.level),
        )
        return checks.mapNotNull { check -> contradiction(check) }
    }

    /** 一行汇总，给报告用。空列表＝这张单子自洽。 */
    fun summary(findings: List<SizeFinding>): String {
        val deny = findings.count { it.verdict == Verdict.DENY }
        val check = findings.count { it.verdict == Verdict.NEED_CHECK }
        return "红 " + deny + "／须核 " + check + "／独立矩阵 " + IndependenceMatrix.CELLS + " 格全过"
    }

    /** 报告用唯一出口：尺寸层一行＋判据汇总。 */
    fun report(profile: SizeProfile, context: BodyContext): String =
        SizeGate.line(profile, context) + "｜" + summary(inspect(profile))

    private fun contradiction(check: AxisCheck): SizeFinding? {
        if (IndependenceMatrix.verdict(check.buried, check.tier) == Verdict.PASS) return null
        return SizeFinding(
            Verdict.DENY,
            "SIZE-BURIED-" + check.name,
            "埋没" + check.buried.label + "与" + check.name + "第 " + check.tier +
                " 档被判成互相矛盾：两个字段各记各的",
        )
    }

    /** 一次轴检查：哪条尺寸轴、埋没读数、该轴档序。 */
    private data class AxisCheck(val name: String, val buried: BuriedLook, val tier: Int)
}
