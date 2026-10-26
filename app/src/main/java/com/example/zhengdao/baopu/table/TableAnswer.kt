// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十二批 · 答案的形状（**出处是结构，不是文案**）。
//
// 派工单 §3 的硬要求：「答案必带出处（文件·sheet·行号）」＋「表里没有就说表里没有
// （禁推算、禁补 0）」＋「.pdf ⇒ 直接回暂不支持 PDF」。
//
// 这三条在本文件里是**类型**，不是提醒：
// - [AnswerKind.ANSWER] 的答案必须有 [Evidence]（构造时就要求非空，见 [TableAnswer.answer]）；
// - 「没有」不是 0：[AnswerKind.NOT_IN_TABLE] 的 [TableAnswer.numbers] 恒为空——
//   下游（含 227 的 AnswerGate）拿不到任何可被当成读数的数；
// - PDF 只有一条出口 [TableAnswer.pdf]：它连表都不打开，所以「硬解 PDF」在结构上不存在。
//
// 出处只念**相对工作区**的路径与 sheet 名、行号，不念绝对路径（绝对路径是隐私面）。
package com.example.zhengdao.baopu.table

/** 一次问答的结局种类（闭集：日志、面板、工具回灌都按它归类）。 */
enum class AnswerKind {
    /** 有答案（必带出处）。 */
    ANSWER,

    /** 表里没有（不是 0，不是推算）。 */
    NOT_IN_TABLE,

    /** 这条问法本层算不了（要求换个问法，**不许硬猜一个数**）。 */
    UNSUPPORTED,

    /** 有多张候选表/没指定表 ⇒ 先问一句（不猜）。 */
    NEED_TABLE,

    /** 表头认不出 ⇒ 问第几行是表头（不猜）。 */
    NEED_HEADER,

    /** 问法里的列命中多列（重名/像两列）⇒ 问是哪一列（不猜）。 */
    NEED_COLUMN,

    /** 明确不支持：PDF。 */
    PDF,
}

/** 出处。 */
data class Evidence(
    val path: String,
    val sheet: String,
    /** 表头在第几行（1 起；0 ＝ 用户指定/未认出时不写）。 */
    val headerRow: Int,
    /** 命中行在文件里的行号（1 起）。 */
    val rows: List<Int>,
    /** 这张表一共有多少数据行（「第 3 行」离开总行数就没法核）。 */
    val totalRows: Int,
) {

    /** `表：成绩表.xlsx · sheet「Sheet1」· 第 2–11 行（共 10 行）`。 */
    fun text(): String {
        val head = "表：$path · sheet「$sheet」"
        val tail = if (totalRows > 0 && rows.size == totalRows) {
            "全表 ${rows.size} 行（$rangePhrase）"
        } else {
            "${rangePhrase}（这张表共 $totalRows 行）"
        }
        return "$head · $tail"
    }

    private val rangePhrase: String
        get() {
            if (rows.isEmpty()) return "没有命中行"
            val sorted = rows.sorted()
            val contiguous = sorted.size > 2 && sorted.last() - sorted.first() == sorted.size - 1
            return if (contiguous) {
                "第 ${sorted.first()}–${sorted.last()} 行"
            } else {
                TableText.rowsText(sorted)
            }
        }
}

/**
 * 一条答案。
 *
 * @param headline 先给答案的那一句（「3 个人不及格」）
 * @param criteria 口径（「不及格按 <60 分算」）——凡是本层**自己定了一个阈值**就必须写在这里
 * @param evidence 出处；[AnswerKind.ANSWER] 时必非 null
 * @param numbers 这次算出来的数（给 AnswerGate 做「数字来自表」的核对）
 * @param digest 命中的行摘要（进工具回灌的 ground，让数字有据可查）
 */
data class TableAnswer(
    val kind: AnswerKind,
    val headline: String,
    val criteria: List<String> = emptyList(),
    val evidence: Evidence? = null,
    val numbers: List<Double> = emptyList(),
    val digest: List<String> = emptyList(),
    /** 给用户看的追问/提示（NEED_* 与 NOT_IN_TABLE 用）。 */
    val hint: String = "",
) {

    val ok: Boolean get() = kind == AnswerKind.ANSWER

    /**
     * 整段话（面板、工具、播报**共用这一条出话路径**）。
     *
     * 三行纪律：① 答案；② 口径（有才写）；③ 出处（有才写——「没有」也要写出处，
     * 否则用户不知道我查的是哪张表）。
     */
    fun text(): String {
        val sb = StringBuilder(headline)
        if (criteria.isNotEmpty()) sb.append("\n（口径：").append(criteria.joinToString("；")).append("）")
        evidence?.let { sb.append('\n').append(it.text()) }
        if (hint.isNotEmpty()) sb.append('\n').append(hint)
        return sb.toString()
    }

    companion object {

        fun answer(
            headline: String,
            evidence: Evidence,
            numbers: List<Double> = emptyList(),
            criteria: List<String> = emptyList(),
            digest: List<String> = emptyList(),
            hint: String = "",
        ): TableAnswer = TableAnswer(
            kind = AnswerKind.ANSWER,
            headline = headline,
            criteria = criteria,
            evidence = evidence,
            numbers = numbers,
            digest = digest,
            hint = hint,
        )

        /**
         * 表里没有。
         *
         * [numbers] **恒为空**——这是「禁推算、禁补 0」在结构上的解：没有数可给，
         * 就不会有哪个下游把它当成 0 报出去。
         */
        fun notInTable(what: String, evidence: Evidence?, hint: String = ""): TableAnswer = TableAnswer(
            kind = AnswerKind.NOT_IN_TABLE,
            headline = "表里没有：$what",
            evidence = evidence,
            hint = hint,
        )

        fun unsupported(reason: String, hint: String = "这条我算不了，能不能换个问法？"): TableAnswer =
            TableAnswer(kind = AnswerKind.UNSUPPORTED, headline = reason, hint = hint)

        fun needTable(headline: String, options: List<String>): TableAnswer = TableAnswer(
            kind = AnswerKind.NEED_TABLE,
            headline = headline,
            hint = if (options.isEmpty()) "" else "候选表：${options.joinToString("、")}（说一句是哪一张，我就按那张答）",
        )

        fun needHeader(ask: String, path: String, sheet: String): TableAnswer = TableAnswer(
            kind = AnswerKind.NEED_HEADER,
            headline = ask,
            hint = "表：$path · sheet「$sheet」（你回一个行号，我就按那一行当表头）",
        )

        fun needColumn(term: String, options: List<String>): TableAnswer = TableAnswer(
            kind = AnswerKind.NEED_COLUMN,
            headline = "「$term」对上了好几列，我不替你选。",
            hint = "是这一列吗：${options.joinToString("、")}？",
        )

        /** PDF 的唯一出口（**不打开文件、不解析**）。 */
        fun pdf(path: String): TableAnswer = TableAnswer(
            kind = AnswerKind.PDF,
            headline = "PDF 暂不支持（本批范围外）：$path",
            hint = "能读的是 xlsx / csv / tsv；PDF 里的表请先另存成 xlsx 或 csv 再放进来。",
        )
    }
}
