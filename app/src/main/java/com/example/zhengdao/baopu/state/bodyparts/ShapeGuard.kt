// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百三十五批 · 文字口径闸门与唯一出口。
//
// 出口只有一处：[ShapeFinding.line]。构造结论时就过三道闸，写坏的话根本造不出来：
// · 长度／面积／百分比 ⇒ 引 805 [BodyPartGuard]（同一套口径，不另立第二套）；
// · 诊断词 ⇒ 同上；
// · 评分词 ⇒ 本文件补（判据⑥「不做吸引力评分」）。
//
// 禁词表一律拼接写（"c" + "m"），免得源码门禁扫到自己——805／658 的做法沿用。
package com.example.zhengdao.baopu.state.bodyparts

/**
 * 判定门的三值沿用 805 的 [Verdict]：过／须核／红。
 * 对外说的 WARN 就是 [Verdict.NEED_CHECK]——不另立第四种判定词。
 */
object ShapeGuard {

    const val SCORE_REJECT: String = "不做吸引力评分：结论里不许出现分值与综合口径"
    const val CAUSAL_REJECT: String = "描写行只写看得见什么，不写推导"

    /** 评分词。拼接写：本文件自己被门的源码闸扫到时不许自咬。 */
    private val SCORE_TERMS: List<String> = listOf(
        "评" + "分", "打" + "分", "综合" + "分", "满" + "分", "分" + "值", "吸引力指" + "数",
    )

    /** 推导词：描写行里出现即拒（形态 → 评价那一步就藏在这个词后面）。 */
    private val CAUSAL_TERMS: List<String> = listOf("所" + "以", "因" + "此", "可" + "见", "说" + "明", "注" + "定")

    fun scoreHit(text: String): String? = SCORE_TERMS.firstOrNull { text.contains(it) }

    fun rejectScore(text: String) {
        val hit = scoreHit(text)
        require(hit == null) { "$SCORE_REJECT（命中「$hit」）" }
    }

    fun causalHit(text: String): String? = CAUSAL_TERMS.firstOrNull { text.contains(it) }

    fun rejectCausal(text: String) {
        val hit = causalHit(text)
        require(hit == null) { "$CAUSAL_REJECT（命中「$hit」）" }
    }

    /** 三道闸一起过：长度／百分比、诊断词、评分词。 */
    fun rejectAll(text: String) {
        BodyPartGuard.rejectMeasures(text)
        BodyPartGuard.rejectDiagnosis(text)
        rejectScore(text)
    }

    /**
     * 形态行的唯一写法：部位＋档名。
     * 档名本身不许再带推导词——「腹肌明显」可以，「腹肌明显所以很强」在这里就写不出来。
     */
    fun shapeLine(aspect: ShapeAspect, tierLabel: String): String {
        require(tierLabel.isNotBlank()) { "形态行缺档名：" + aspect.label }
        rejectAll(tierLabel)
        rejectCausal(tierLabel)
        BodyPartGuard.rejectAbsoluteColor(tierLabel)
        return aspect.label + "：" + tierLabel
    }
}

/**
 * 一条结论。构造即守：有代码、有理由，理由里没有长度单位、没有诊断词、没有评分词。
 *
 * 为什么不复用 805 的 [Finding]：那个类型的 part/dim 是八部位与二十三维修正案，
 * 本单判的是六个评价维；另立一个同形类型比往旧类型塞空值清楚，且能多加一道评分闸。
 */
data class ShapeFinding(
    val verdict: Verdict,
    val code: String,
    val why: String,
    val source: String = SHAPE_SOURCE,
    val asOf: String = SHAPE_AS_OF,
) {
    init {
        require(code.isNotBlank()) { "结论缺代码：$code" }
        require(why.isNotBlank()) { "结论缺理由：$code" }
        ShapeGuard.rejectAll(why)
    }

    /** 唯一出口：判定＋代码＋理由（来源 日期）。 */
    fun line(): String =
        "[" + verdict.label + "] " + code + "：" + why + "（" + source + " " + asOf + "）"

    companion object {
        const val COUNT_VERDICTS: Int = 3
    }
}
