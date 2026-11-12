// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第六百一十批（通读框架 · 实体护栏）。两条硬规则：
// 1. **合并只认 subjectId**——同名不同 id 是两个人（同名不同人不许自动合并，判据④），
//    撞名只报告、等人审，绝不在抽取层替人拍板；
// 2. 人物覆盖抽样 ≥95%（判据③）：确定性抽样口径——「说话动词」抓出的人名与
//    事实里的主体对账；这是抽样哨兵不是真值，抓不全的章节如实列出来。
package com.example.zhengdao.baopu.readthrough

/**
 * 一个实体的账（按 [FactLine.subjectId] 聚出）。
 *
 * [names] 是这个 id 下出现过的所有称呼（同一人的多种写法不合并成「正名」，
 * 那也需要人审——报告里全列出）。
 */
internal data class EntityRef(val id: String, val names: Set<String>, val factCount: Int)

/** 同名不同 id 的撞名（**只报告不合并**）。 */
internal data class NameClash(val name: String, val ids: List<String>)

internal data class MergeAudit(val entities: List<EntityRef>, val clashes: List<NameClash>) {
    /** 撞名数（报告里叫「待人审 N 处」）。 */
    val clashCount: Int get() = clashes.size
}

internal object EntityMerge {

    /**
     * 审计一组事实的实体。
     *
     * 为什么「同名不同 id」不自动合：抽取层的 id 是按上下文标的（张三#1、张三#2），
     * 两处上下文是否同一人需要全书人物表或人眼拍板——自动合并一次错的代价
     * （人物关系全错）远大于留着两条让人审的成本。
     */
    fun audit(facts: List<FactLine>): MergeAudit {
        val byId = facts.groupBy { it.subjectId }
        val entities = byId.map { (id, fs) -> EntityRef(id, fs.map { it.subject }.toSet(), fs.size) }
            .sortedByDescending { it.factCount }
        val nameOwners = mutableMapOf<String, MutableSet<String>>()
        entities.forEach { e ->
            e.names.forEach { n -> nameOwners.getOrPut(n) { mutableSetOf() }.add(e.id) }
        }
        val clashes = nameOwners.filterValues { it.size > 1 }
            .map { (name, ids) -> NameClash(name, ids.sorted()) }
            .sortedBy { it.name }
        return MergeAudit(entities, clashes)
    }
}

/**
 * 人物覆盖抽样（判据③的机检）。
 *
 * 抽样口径（**确定性**，可复算）：用「说话动词」正则从章正文抓说话人候选
 * （「林惊羽道」「他说」这类形状），与该章事实的 subject 对账：
 * coverage = |候选 ∩ 主体名| / |候选|。候选抓不准（代词、无动词说话）⇒
 * 分母虚高，coverage 偏低——那是**误报方向**，不会漏报；抽样哨兵宁可误报。
 */
internal object CharacterCoverage {

    /** 抽样覆盖率阈值（判据③：≥95%）。 */
    const val THRESHOLD = 0.95

    /** 每几章抽一章（默认 10 抽 1；夹具与测试可调）。 */
    const val SAMPLE_EVERY = 10

    private val SPEAKER = Regex(
        """([\u4e00-\u9fa5]{1,4})(?:说道|问道|答道|笑道|骂道|喊道|叹道|喝道|冷笑道|沉声道|低声道|喃喃道|道|说|问|答|喊|骂)""",
    )

    /** 从章正文抓说话人候选（去重）。 */
    fun speakers(text: String): Set<String> =
        SPEAKER.findAll(text).map { it.groupValues[1] }.filter { it.length >= 2 }.toSet()

    internal data class Sample(val chapter: Int, val text: String, val facts: List<FactLine>)

    internal data class Report(
        val pass: Boolean,
        val overall: Double,
        val threshold: Double,
        val sampled: Int,
        /** 不达标的章（章号 → 覆盖率），逐条报。 */
        val failing: List<Pair<Int, Double>>,
    )

    /** 单章覆盖率（该章没有说话人候选 ⇒ 1.0——没有可对账的候选就不冤枉它）。 */
    fun coverageOf(text: String, facts: List<FactLine>): Double {
        val candidates = speakers(text)
        if (candidates.isEmpty()) return 1.0
        val subjects = facts.map { it.subject }.toSet()
        val hit = candidates.count { it in subjects }
        return hit.toDouble() / candidates.size
    }

    /** 抽样出报告（every 取一；整体 ＝ 各章覆盖率均值）。 */
    fun sample(samples: List<Sample>, threshold: Double = THRESHOLD): Report {
        if (samples.isEmpty()) {
            return Report(pass = true, overall = 1.0, threshold = threshold, sampled = 0, failing = emptyList())
        }
        val per = samples.map { it.chapter to coverageOf(it.text, it.facts) }
        val overall = per.map { it.second }.average()
        val failing = per.filter { it.second < threshold }.sortedBy { it.first }
        return Report(
            pass = failing.isEmpty() && overall >= threshold,
            overall = overall,
            threshold = threshold,
            sampled = samples.size,
            failing = failing,
        )
    }
}
