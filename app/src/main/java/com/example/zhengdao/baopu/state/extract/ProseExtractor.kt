// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百五十三批 · 抽取层：纯函数，零 IO／零网络／零随机／零时钟。
// 同输入必得同结论；比喻不解读、低档不越级、写法无档显式进 misses。
package com.example.zhengdao.baopu.state.extract

import com.example.zhengdao.baopu.state.prepuce.AreaLevel

/** 抽取器。无状态、无副作用；[extract] 是唯一入口。 */
object ProseExtractor {

    private const val HEDGE_WINDOW = 6
    private const val METAPHOR_WINDOW = 16

    /** 模式按长度降序：先长后短，避免「全露」抢在「完全露出」之前命中。 */
    private val rules: List<Rule> by lazy { ExtractLexicon.ALL.sortedByDescending { it.pattern.length } }
    private val missRules: List<MissRule> by lazy { ExtractLexicon.ALL_MISS.sortedByDescending { it.pattern.length } }
    private val stateRules: List<Rule> by lazy { ExtractLexicon.ALL_STATE.sortedByDescending { it.pattern.length } }

    /**
     * 从正文抽出三列表。纯函数：只读 [text] 与 [table]，不读时钟/随机/文件/网络。
     * [table] 决定字段名；默认 817 表。869 合入后注入新表即可。
     */
    fun extract(text: String, table: FieldNameTable = FieldNames.of817()): ExtractResult {
        val metaphors = metaphorSpans(text)
        val covered = BooleanArray(text.length)
        val misses = ArrayList<Miss>()
        markMetaphors(text, metaphors, covered, misses)

        val observed = scanRules(text, table, rules, covered)
        val traces = scanStates(text, table, covered)
        scanMissRules(text, covered, misses)
        checkExposeUnknown(text, observed, misses)

        return ExtractResult(
            observed.sortedWith(compareBy({ it.span.start }, { it.dim.name })),
            traces.sortedBy { it.span.start },
            misses.sortedWith(compareBy({ it.span.start }, { it.reason.name })),
        )
    }

    /** ① 比喻整段先记 miss 并占位，段内词不许当档位抽。 */
    private fun markMetaphors(
        text: String, spans: List<Span>, covered: BooleanArray, misses: MutableList<Miss>,
    ) {
        spans.forEach { span ->
            mark(covered, span)
            misses += Miss(null, MissReason.METAPHOR, span, note = "比喻不解读：不猜成任何既有档")
        }
    }

    /** ② 落档规则：逐条找模式，命中即取既有档 + span + 置信。 */
    private fun scanRules(
        text: String, table: FieldNameTable, list: List<Rule>, covered: BooleanArray,
    ): List<Observed> {
        val found = ArrayList<Observed>()
        list.forEach { rule ->
            val grade = requireNotNull(rule.grade)
            findAll(text, rule.pattern).forEach { span ->
                if (overlaps(covered, span)) return@forEach
                mark(covered, span)
                found += Observed(rule.dim, table.field(rule.dim), grade, span, confidenceOf(text, span, rule.exact))
            }
        }
        return found
    }

    /** ③ 810 状态词：只进 traces，grade 恒 null，pending:810。 */
    private fun scanStates(text: String, table: FieldNameTable, covered: BooleanArray): List<TraceHit> {
        val found = ArrayList<TraceHit>()
        stateRules.forEach { rule ->
            findAll(text, rule.pattern).forEach { span ->
                if (overlaps(covered, span)) return@forEach
                mark(covered, span)
                found += TraceHit(
                    rule.dim, table.field(rule.dim), rule.pattern, null, span,
                    confidenceOf(text, span, exact = true), "810",
                )
            }
        }
        return found
    }

    /** ④ 未落地与无档写法：显式 misses，逐条带单号或 NO_GRADE。 */
    private fun scanMissRules(text: String, covered: BooleanArray, misses: MutableList<Miss>) {
        missRules.forEach { rule ->
            findAll(text, rule.pattern).forEach { span ->
                if (overlaps(covered, span)) return@forEach
                mark(covered, span)
                misses += Miss(
                    rule.dim, rule.reason, span, rule.pending,
                    rule.note.ifBlank { "写法无既有档，不许自造" },
                )
            }
        }
    }

    /** ⑤ 四型判不出：写了「全露」但没有任何露出型命中 ⇒ 显式 miss，不猜型。 */
    private fun checkExposeUnknown(text: String, observed: List<Observed>, misses: MutableList<Miss>) {
        val allExposed = observed.any { it.dim == ExtractDim.AREA_LEVEL && it.grade == AreaLevel.FULLY_EXPOSED.label }
        val hasType = observed.any { it.dim == ExtractDim.EXPOSE_TYPE }
        if (!allExposed || hasType) return
        findAll(text, "完全露出").firstOrNull()?.let { span ->
            misses += Miss(
                ExtractDim.EXPOSE_TYPE, MissReason.AMBIGUOUS, span,
                note = "写了全露但没写成因（环切/自然/状态性/手动未复位），四型判不出，不猜",
            )
        } ?: run {
            findAll(text, "全露").firstOrNull()?.let { span ->
                misses += Miss(
                    ExtractDim.EXPOSE_TYPE, MissReason.AMBIGUOUS, span,
                    note = "写了全露但没写成因，四型判不出，不猜",
                )
            }
        }
    }

    /** 比喻窗口：起词 + 至多 16 字内有收词 ⇒ 整段（起..收）记 miss。 */
    private fun metaphorSpans(text: String): List<Span> {
        val found = ArrayList<Span>()
        for (startWord in ExtractLexicon.METAPHOR_START) {
            findAll(text, startWord).forEach { start ->
                val windowEnd = (start.start + METAPHOR_WINDOW).coerceAtMost(text.length)
                val window = text.substring(start.end, windowEnd)
                val endWord = ExtractLexicon.METAPHOR_END.firstOrNull { it in window } ?: return@forEach
                val end = start.end + window.indexOf(endWord) + endWord.length
                found += Span(start.start, end, text.substring(start.start, end))
            }
        }
        return found.distinctBy { it.start to it.end }
    }

    /** 模糊词在命中前后 6 字内 ⇒ 低置信；非 exact 词表中置信；其余高置信。 */
    private fun confidenceOf(text: String, span: Span, exact: Boolean): Confidence {
        val from = (span.start - HEDGE_WINDOW).coerceAtLeast(0)
        val to = (span.end + HEDGE_WINDOW).coerceAtMost(text.length)
        val near = text.substring(from, to)
        if (ExtractLexicon.HEDGES.any { it in near }) return Confidence.LOW
        return if (exact) Confidence.HIGH else Confidence.MIDDLE
    }

    /** 原文中 [pattern] 的全部出现位置（纯 indexOf 循环，无正则、无随机）。 */
    private fun findAll(text: String, pattern: String): List<Span> {
        if (pattern.isEmpty()) return emptyList()
        val found = ArrayList<Span>()
        var from = 0
        while (true) {
            val at = text.indexOf(pattern, from)
            if (at < 0) break
            found += Span(at, at + pattern.length, text.substring(at, at + pattern.length))
            from = at + pattern.length
        }
        return found
    }

    private fun overlaps(covered: BooleanArray, span: Span): Boolean =
        (span.start until span.end).any { covered[it] }

    private fun mark(covered: BooleanArray, span: Span) {
        for (i in span.start until span.end) covered[i] = true
    }
}
