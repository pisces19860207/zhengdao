package com.example.zhengdao.baopu.inspect

import java.text.Normalizer
import java.util.Locale

internal data class OpinionMergeResult(
    val merged: List<Opinion>,
    val suggestions: List<Opinion>,
    /**
     * 第五百三十七批：合并时**已经发生的分歧**（旁证，不参与选择）。
     *
     * `merged` 仍由 `mostSpecific` 决定——这里只把「同一事件下本来就不一致」记下来，
     * 让御案卡能看见它，而不是被静默并成一条。
     */
    val conflicts: List<OpinionConflict> = emptyList(),
)

/** 冲突的一方（只留判断"不一致"所需的三格，不复制整条意见）。 */
internal data class OpinionConflictSide(
    val gist: String,
    val severity: Severity,
    val source: Source,
)

/**
 * 一次分歧（同 eventKey、gist 归一化不同或 severity 方向相反）。
 *
 * [eventKey] 用 [OpinionMerger.eventKeyOf] 的口径；[id] 是稳定卡键
 * （同一对分歧在重复合并/跨重启时认得出是同一条，御批账按它压制）。
 */
internal data class OpinionConflict(
    val eventKey: String,
    val a: OpinionConflictSide,
    val b: OpinionConflictSide,
    val at: Long = 0L,
    val evidence: List<String> = emptyList(),
) {
    val id: String get() = "cf:$eventKey:${a.source.name}:${a.gist}|${b.source.name}:${b.gist}"
}

internal object OpinionMerger {
    // 具体性是领域事实，不用句长或严重度冒充；选择器必须返回组内原意见。
    fun merge(
        opinions: List<Opinion>,
        mostSpecific: (List<Opinion>) -> Opinion,
    ): OpinionMergeResult {
        val (suggestions, eligible) = opinions.partition { it.suggestionOnly }
        val groups = mutableListOf<MutableList<Opinion>>()
        eligible.forEach { opinion ->
            val matching = groups.filter { group -> group.any { sameEvent(it, opinion) } }
            if (matching.isEmpty()) {
                groups.add(mutableListOf(opinion))
            } else {
                val first = matching.first()
                matching.drop(1).forEach { group ->
                    first.addAll(group)
                    groups.remove(group)
                }
                first.add(opinion)
            }
        }
        val merged = groups.map { group ->
            val selected = mostSpecific(group.toList())
            require(selected in group) { "mostSpecific must select an original opinion" }
            val lawRef = if (selected.lawRef != LawRef.NONE) selected.lawRef else {
                group.filter { it.lawRef != LawRef.NONE }
                    .maxByOrNull { it.severity.rank }?.lawRef ?: LawRef.NONE
            }
            selected.copy(
                severity = group.maxBy { it.severity.rank }.severity,
                lawRef = lawRef,
                evidence = group.flatMap { it.evidence }.distinct(),
                vote = Math.toIntExact(group.sumOf { it.vote.toLong() }),
            )
        }
        return OpinionMergeResult(merged, suggestions, conflictsOf(eligible))
    }

    /**
     * 同事件里已经发生的分歧（**纯函数、无时钟**；`at` 由调用方在落盘时盖章）。
     *
     * 口径（派工单第五百三十七批二·①）：
     * - 只在**同一 [eventKeyOf]** 内配对，跨事件不算冲突；
     * - `gist` 归一化后不同 ⇒ 两说；
     * - `severity` 方向相反（一个 CRITICAL、一个 MINOR）⇒ 两说；
     * - 同一对分歧只出一条（重试造成的重复意见不会把列表撑大）。
     */
    fun conflictsOf(opinions: List<Opinion>, at: Long = 0L): List<OpinionConflict> =
        opinions.groupBy { eventKeyOf(it) }
            .values
            .flatMap { group -> pairsIn(group, at) }
            .distinctBy { it.id }

    private fun pairsIn(group: List<Opinion>, at: Long): List<OpinionConflict> {
        val out = mutableListOf<OpinionConflict>()
        for (i in group.indices) {
            for (j in i + 1 until group.size) {
                val a = group[i]
                val b = group[j]
                if (!inconsistent(a, b)) continue
                val (first, second) = ordered(a, b)
                out += OpinionConflict(
                    eventKey = eventKeyOf(a),
                    a = OpinionConflictSide(first.gist, first.severity, first.source),
                    b = OpinionConflictSide(second.gist, second.severity, second.source),
                    at = at,
                    evidence = (a.evidence + b.evidence).distinct(),
                )
            }
        }
        return out
    }

    private fun inconsistent(a: Opinion, b: Opinion): Boolean =
        normalize(a.gist) != normalize(b.gist) || severityFlipped(a.severity, b.severity)

    /** "方向相反"的最小口径：一头 CRITICAL、一头 MINOR（MAJOR 与谁都不算相反）。 */
    private fun severityFlipped(a: Severity, b: Severity): Boolean =
        (a == Severity.CRITICAL && b == Severity.MINOR) ||
            (a == Severity.MINOR && b == Severity.CRITICAL)

    /** 稳定配对顺序：同一对意见不论谁先来，落出的 a/b 与 id 都一样。 */
    private fun ordered(a: Opinion, b: Opinion): Pair<Opinion, Opinion> {
        val ka = "${a.source.name}:${normalize(a.gist)}:${a.severity.name}"
        val kb = "${b.source.name}:${normalize(b.gist)}:${b.severity.name}"
        return if (ka <= kb) a to b else b to a
    }

    /**
     * 336 · 事件键：sameEvent 的**第一判据**（证据首项）做成可复用的键。
     *
     * 为什么只要第一判据：同一动作的重试提交（282 D 组 C5 死循环的根因）每次都会产出
     * 「证据完全相同」的意见——证据首项同键即可把它们并成一条。第二判据（source+gist 归一化）
     * **有意不进键**：不同证据的同名意见是不同事实，并错比漏并更坏（口径记录在 336 报告）。
     * 调解器按此键做生命周期（[OpinionLifecycle]）：已 RESOLVED/SETTLED 的事件不再重复弹卡、
     * 不再打回验收。
     */
    fun eventKeyOf(opinion: Opinion): String = "ev1:${opinion.evidence.first()}"

    private fun sameEvent(a: Opinion, b: Opinion): Boolean =
        a.evidence.first() == b.evidence.first() ||
            (a.source == b.source && normalize(a.gist) == normalize(b.gist))

    private fun normalize(gist: String): String =
        Normalizer.normalize(gist, Normalizer.Form.NFKC)
            .filterNot { it.isWhitespace() }.lowercase(Locale.ROOT)
}
