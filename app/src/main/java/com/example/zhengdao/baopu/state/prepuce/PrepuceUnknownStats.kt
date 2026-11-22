// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百三十一批 · 未定率统计：未定项不许长期挂着。
//
// 口径三条（真源＝派工单 §②）：
// · 未定率 = 未定条数 / 样本条数 × 100，逐条按 ` CoverageArea.resolved ` 计数，可复算；
// · 严格大于 5% 才算档表缺陷（正好 5% 不算），命中即给提示、要求补档；
// · 空样本当场红——没有样本谈不上率，不许拿 0/0 冒充达标。
//
// 纯数据、零 IO、零随机、零时钟：同输入必得同结论（自检见单测）。
package com.example.zhengdao.baopu.state.prepuce

/** 未定率的三种输出：样本、计数、百分比、是否缺陷、提示语。 */
object PrepuceUnknownStats {

    /** 未定率红线：严格大于 5% 才算档表缺陷。 */
    const val RATE_LIMIT_PERCENT: Double = 5.0

    const val EMPTY_SAMPLE: String = "未定率样本为空：没有样本谈不上未定率，不许拿 0/0 冒充达标"
    const val BAD_COUNT: String = "未定数越界：未定数必须落在 0..样本总数之间"
    const val DEFECT_HINT: String = "未定率超过 5%：档表有缺陷，提示补档，不许长期挂未定"

    /**
     * 一条未定率报告。[total] 为样本总数、[unresolved] 为其中未定条数，
     * [percent] = unresolved × 100 / total；[isDefect] 为 percent 严格大于 5%。
     */
    data class Report(
        val total: Int,
        val unresolved: Int,
        val percent: Double,
        val isDefect: Boolean,
        val hint: String?,
    ) {
        init {
            require(total > 0) { EMPTY_SAMPLE }
            require(unresolved in 0..total) { BAD_COUNT }
            require((isDefect && hint != null) || (!isDefect && hint == null)) { HINT_MISMATCH }
        }

        /** 一行机读快照：未定 n/N＝x%，缺陷时附补档提示。 */
        fun line(): String = buildString {
            append("未定 ").append(unresolved).append('/').append(total)
                .append('=').append(percent).append('%')
            if (hint != null) append('|').append(hint)
        }

        companion object {
            const val HINT_MISMATCH: String = "提示语与缺陷标志必须一致：超线必给提示、未超线必不给"
        }
    }

    /**
     * 从一批面积结论复算未定率。空样本红；未定条数逐条按 [CoverageArea.resolved] 反读，
     * 与调用方怎么分类无关（避免两处口径各算各的）。
     */
    fun of(results: List<CoverageArea>): Report {
        require(results.isNotEmpty()) { EMPTY_SAMPLE }
        val unresolved = results.count { !it.resolved }
        val percent = unresolved * 100.0 / results.size
        val defect = percent > RATE_LIMIT_PERCENT
        return Report(results.size, unresolved, percent, defect, if (defect) DEFECT_HINT else null)
    }
}
