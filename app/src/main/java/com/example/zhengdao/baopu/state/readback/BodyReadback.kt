// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百二十二批 · 读回契约：三行渲染器（纯函数，零 IO、零时钟、零随机、零模型）。
//
// 三行的唯一真值来源：
//   ① 不变量行 —— BodyState（660 已合：spec.heightCm / bodyFatPct / muscleGrade，
//                  frame.ratios.shoulder、frame.sections 截面宽）。
//                  腹型 / 骨架 / 臀围：660 无此口径 ⇒ 留位「未接入」，不编。
//   ② 状态行  —— 658 未合（turgor / 出汗 / 心率档 / 疲劳）⇒ 四项留位「未接入」，不编数。
//   ③ 词档行  —— 669 词表 AnatomyZone.name（13 区正名），不硬编码进本文件。
//
// 边界：不改 660 的 BodyState 算法、不改 669 词表内容；三行只作文本上下文，不进任何闸。
package com.example.zhengdao.baopu.state.readback

import com.example.zhengdao.baopu.body.AnatomyZone
import com.example.zhengdao.baopu.state.form.BodyState
import java.util.Locale

internal object BodyReadback {

    /** 不变量行的标签（身份锚的开头，测试据此钉行头，不许被状态行顶替）。 */
    const val INVARIANT_TAG = "[身体]"
    const val STATUS_TAG = "[状态]"
    const val VOCAB_TAG = "[词档]"

    /** 截面 id：胸下 / 腰，取自 frame.sections（真源 body-proportions.json）。 */
    private const val SECTION_CHEST = "chest_lower"
    private const val SECTION_WAIST = "waist"

    /** 一条读回的渲染结果 + 该不变量行对应的身体摘要（给人批封印绑定用）。 */
    data class Rendered(val lines: ReadbackLines, val invariantBasis: String)

    /**
     * 渲染三行。纯函数：同一 [state] + [zones] 两次调用逐字节相同。
     * [approvedInvariant] 非空时，不变量行逐字取已批准的那一行（见 [InvariantSeal]）。
     */
    fun render(
        state: BodyState,
        zones: List<AnatomyZone>,
        approvedInvariant: String? = null,
    ): Rendered {
        val fresh = invariantLine(state)
        val basis = invariantBasis(state)
        val inv = approvedInvariant ?: fresh
        val lines = ReadbackLines(
            invariant = ReadbackLines.clip(inv),
            status = statusLine(),
            vocab = vocabLine(zones),
        )
        return Rendered(lines, basis)
    }

    /**
     * 不变量行（真读数 + 缺失留位）。格式：
     *   `[身体] 176cm · 体脂25% · 肌肉档3 · 肩峰宽41cm · 胸/腰:88/74cm · 腹型/骨架/臀围:未接入`
     * 每个数字都来自 BodyState，一个字都不由本文件发明。
     */
    fun invariantLine(state: BodyState): String {
        val spec = state.spec
        val height = fmt(spec.heightCm)
        val fat = fmt(spec.bodyFatPct)
        val muscle = spec.muscleGrade?.toString() ?: ReadbackLines.UNWIRED
        val shoulderCm = fmt(state.frame.ratios.shoulder.value * spec.heightCm)
        val chest = sectionWidth(state, SECTION_CHEST)
        val waist = sectionWidth(state, SECTION_WAIST)
        return listOf(
            "$INVARIANT_TAG ${height}cm",
            "体脂${fat}%",
            "肌肉档$muscle",
            "肩峰宽${shoulderCm}cm",
            "胸/腰:${chest}/${waist}cm",
            "腹型/骨架/臀围:${ReadbackLines.UNWIRED}",
        ).joinToString(" · ")
    }

    /**
     * 不变量的"身体摘要"：只由参与不变量行的真值拼成。
     * 人批封印绑的是它——BodyState 任一不变量字段变了，摘要就变，旧批准自动失效。
     */
    fun invariantBasis(state: BodyState): String {
        val spec = state.spec
        return listOf(
            spec.heightCm, spec.bodyFatPct, (spec.muscleGrade ?: -1).toDouble(),
            state.frame.ratios.shoulder.value,
            sectionWidthValue(state, SECTION_CHEST),
            sectionWidthValue(state, SECTION_WAIST),
        ).joinToString("|") { String.format(Locale.ROOT, "%.4f", it) }
    }

    /**
     * 状态行：658 未合，四项全部留位，一个数字都不写。
     * 留位本身是判据要求的形状，不是"编造"——它明确说"未接入"。
     */
    fun statusLine(): String = listOf(
        "turgor", "出汗", "心率档", "疲劳",
    ).joinToString(" · ") { "$it:${ReadbackLines.UNWIRED}" }.let { "$STATUS_TAG $it（658未合）" }

    /** 词档行：13 区正名全部来自 669 词表 [zones]，本文件不含任何一个区名。 */
    fun vocabLine(zones: List<AnatomyZone>): String =
        "$VOCAB_TAG " + zones.joinToString("/") { it.name }

    private fun sectionWidth(state: BodyState, id: String): String =
        sectionWidthValue(state, id).let { if (it.isNaN()) ReadbackLines.UNWIRED else fmt(it) }

    private fun sectionWidthValue(state: BodyState, id: String): Double =
        state.frame.sections.firstOrNull { it.id == id }?.widthCm?.value ?: Double.NaN

    /** 数字留一位小数、去掉无意义的 .0（176.0 → 176，25.0 → 25）。 */
    private fun fmt(v: Double): String = when {
        !v.isFinite() || v.isNaN() -> ReadbackLines.UNWIRED
        v % 1.0 == 0.0 -> v.toLong().toString()
        else -> String.format(Locale.ROOT, "%.1f", v)
    }
}
