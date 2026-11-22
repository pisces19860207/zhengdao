// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百九十八批 · 段 B · 命中层：从教材库条目里挑"当前命中"的条目。
// 纪律：① 只读段 A 的 TextbookEntry（不做 ingest/不改库）；② 90 天过期按段 A 的 ReviewExpiry 判；
//       ③ 被 supersedes 取代的旧条不命中（链尾才命中）；④ 关键词按 scope/content 确定性匹配，
//       不做检索/召回/打分；⑤ 反例由 content 里的显式标记判定（没有标记一律正例，绝不误标）。
package com.example.zhengdao.baopu.textbook.inject

import com.example.zhengdao.baopu.textbook.TextbookEntry
import java.time.LocalDate

/** "当前命中"的可断言定义（段 B 自定，派工单未细化）：本层唯一真相源。 */
internal object TextbookMatcher {

    /** content 里出现这些标记之一 ⇒ 反例；否则正例。标记本身不改写正文。 */
    private val NEGATIVE_MARKS = listOf("这是反例", "反例：", "反例:", "错误示范", "不要这样写", "错例")

    /**
     * 挑出当前命中条目并附命中理由。
     * @param entries 教材库 all() 快照；[context] 本轮上下文；[today] 供测试注入（判过期）。
     */
    fun match(
        entries: List<TextbookEntry>,
        context: TeachContext,
    ): List<TeachingLesson> {
        if (context.keywords.isEmpty()) return emptyList()
        val replaced = replacedIds(entries)
        val out = ArrayList<TeachingLesson>()
        for (e in entries) {
            if (e.id in replaced) continue // 被取代的旧条不命中
            if (expired(e.asOf, context.today)) continue // 90 天过期不命中
            if (context.domains.isNotEmpty() && e.domain !in context.domains) continue
            val reason = hitReason(e, context.keywords) ?: continue
            out += TeachingLesson(
                id = e.id,
                text = e.content,
                source = e.source,
                asOf = e.asOf,
                side = sideOf(e.content),
                reason = reason,
            )
        }
        return out
    }

    /** 被任意条目 supersedes 指向的 id 集合（链尾之外不命中）。 */
    private fun replacedIds(entries: List<TextbookEntry>): Set<String> =
        entries.map { it.supersedes }.filter { it.isNotBlank() }.toSet()

    /** 90 天过期：asOf 解析失败一律当过期（不许带病命中）。 */
    private fun expired(asOf: String, today: LocalDate): Boolean {
        val date = runCatching { LocalDate.parse(asOf) }.getOrNull() ?: return true
        return date.plusDays(REVIEW_DAYS).isBefore(today)
    }

    /** 第一个命中关键词落在哪个字段，写进命中理由（可解释行的 reason 来源）。 */
    private fun hitReason(e: TextbookEntry, keywords: List<String>): String? {
        for (k in keywords) {
            if (k.isBlank()) continue
            if (e.scope.contains(k, ignoreCase = true)) return "scope 命中「$k」"
            if (e.content.contains(k, ignoreCase = true)) return "content 命中「$k」"
        }
        return null
    }

    /** 反例判定：显式标记优先；无标记一律正例（防误标把正例学成反例）。 */
    private fun sideOf(content: String): TeachSide =
        if (NEGATIVE_MARKS.any { it in content }) TeachSide.NEGATIVE else TeachSide.POSITIVE

    const val REVIEW_DAYS = 90L
}
