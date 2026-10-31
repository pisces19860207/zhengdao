package com.example.zhengdao.baopu.inspect

import java.text.Normalizer
import java.util.Locale

internal data class OpinionMergeResult(
    val merged: List<Opinion>,
    val suggestions: List<Opinion>,
)

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
        return OpinionMergeResult(merged, suggestions)
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
