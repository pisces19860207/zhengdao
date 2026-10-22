// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.baopu

/**
 * 情景记忆（日记）的**召回与维护判据**（147 阶段②，纯函数 JVM 直测）。
 *
 * 依据：方案 v1.2 §4/§8（判据 4/5/12/14/20）＋ 派工单-147 红线。
 * 与记忆召回（MemoryInjectionBudget）同款哲学：**读时挑选、不删除**——
 * 本对象没有任何写盘接口，没选上的条目只是不注入，文件一条不少（判据 5）。
 *
 * 段序铁律（判据 4）：EPISODE 段在 MEMORY 之后、COMPRESSION 之前，
 * 同会话内只增不改（提示词缓存判据）⇒ [recall] 的排序只用条目自带字段
 * （timeToMs/id），**不读时钟**——同一批输入必须得到同一批输出。
 */
internal object DiaryRecall {

    private const val DAY_MS = 86_400_000L

    /** 月度摘要条目的 id 前缀（`digest-<yyyy-MM>`；文件落 `记忆/日记/digest-<yyyy-MM>.md`）。 */
    const val DIGEST_PREFIX = "digest-"

    /** 默认最多注入几条日记（含月度摘要；实现批可调）。 */
    const val MAX_ITEMS = 5

    /** 默认字符预算（CJK 口径下 1 token ≈ 1 字符，与记忆召回 2k 同数量级取小头）。 */
    const val MAX_CHARS = 1_000

    /** 原文条目保留窗口（天）：超过 ⇒ 浓缩进月度摘要并转归档（软删）。 */
    const val RETAIN_DAYS = 30

    /** 归档件保留窗口（天）：超过且已被月度摘要完整承载 ⇒ 清理（只删日记归档件）。 */
    const val ARCHIVE_DAYS = 180

    data class Recall(val selected: List<DiaryEntry>, val omitted: Int)

    fun isDigest(id: String): Boolean = id.startsWith(DIGEST_PREFIX)

    /**
     * 召回（判据 4/5）：月度摘要优先（它承载更早历史的压缩形态），其余按 timeToMs 新的在前；
     * 条数/字符双上限挑，放不下的**只是不注入**（omitted 计数供段尾注明）。
     */
    fun recall(entries: List<DiaryEntry>): Recall {
        val ordered = entries.sortedWith(
            compareByDescending<DiaryEntry> { isDigest(it.id) }
                .thenByDescending { it.timeToMs }
                .thenBy { it.id },
        )
        var chars = 0
        val picked = mutableListOf<DiaryEntry>()
        var omitted = 0
        for (e in ordered) {
            if (picked.size >= MAX_ITEMS) {
                omitted++
                continue
            }
            val cost = renderForContext(e).length
            if (chars + cost > MAX_CHARS) {
                omitted++
                continue
            }
            chars += cost
            picked += e
        }
        return Recall(picked, omitted)
    }

    /**
     * 证据档后缀（判据 12）：**实测不带后缀**（它就是本会话真实发生的事）；
     * 摘要标「月度摘要」、推断标「（推断）」——推断不许伪装成事实。
     */
    fun evidenceSuffix(e: DiaryEntry): String = when (e.evidence) {
        DiaryEvidence.OBSERVED -> ""
        DiaryEvidence.CITED -> "（月度摘要）"
        DiaryEvidence.INFERRED -> "（推断）"
    }

    /** 回灌一行（带档标注＋产物引用——引用让模型说得出「哪来的」，核对有门）。 */
    fun renderForContext(e: DiaryEntry): String = buildString {
        append("【日记 ").append(e.date).append("】")
        if (e.title.isNotBlank()) append(e.title).append("：")
        append(e.summary)
        val suffix = evidenceSuffix(e)
        if (suffix.isNotEmpty()) append(suffix)
        if (e.artifacts.isNotEmpty()) {
            append("（产物：").append(e.artifacts.joinToString("、")).append("）")
        }
    }

    /** 归档候选（软删第一步，判据 20 的前奏）：超过保留窗口的**非摘要**条目。 */
    fun archiveCandidates(
        entries: List<DiaryEntry>,
        nowMs: Long,
        retainDays: Int = RETAIN_DAYS,
    ): List<DiaryEntry> =
        entries.filter { !isDigest(it.id) && nowMs - it.timeToMs > retainDays * DAY_MS }

    /**
     * 月度摘要（判据 14：**细节可以丢，产物引用一条不许丢**）。
     *
     * 摘要本身是 `CITED` 档——内容提炼自日记文件，不是实测；
     * sessionId = 0（它不是某个会话的流水，是浓缩物）。
     */
    fun composeDigest(month: String, entries: List<DiaryEntry>, nowMs: Long): DiaryEntry {
        val arts = DiaryRules.sanitizeArtifacts(entries.flatMap { it.artifacts })
        val titles = entries.map { it.title }.filter { it.isNotBlank() }.distinct()
        val shown = titles.take(5)
        return DiaryEntry(
            id = DIGEST_PREFIX + month,
            date = month,
            sessionId = 0L,
            timeFromMs = entries.minOfOrNull { it.timeFromMs } ?: nowMs,
            timeToMs = entries.maxOfOrNull { it.timeToMs } ?: nowMs,
            title = "月度摘要",
            summary = buildString {
                append("本月 ").append(entries.size).append(" 个会话")
                if (shown.isNotEmpty()) {
                    append("；主题：").append(shown.joinToString("、"))
                    val more = titles.size - shown.size
                    if (more > 0) append("等 ").append(more).append(" 条")
                }
            },
            artifacts = arts,
            evidence = DiaryEvidence.CITED,
            okSteps = entries.sumOf { it.okSteps },
            failedSteps = entries.sumOf { it.failedSteps },
            createdAt = nowMs,
        )
    }

    /**
     * 归档清理的**覆盖判据**（纯函数半边；判据 20 的另一半「归档满 [ARCHIVE_DAYS] 天」
     * 由 [DiaryStore] 按归档文件的时间戳把关——纯函数不读时钟，两条在存储层会合）。
     *
     * 产物引用已被同月月度摘要完整承载 ⇒ 线索不断，可删；
     * 没被承载（删了就真没了）⇒ 留着。只判日记，产物与用户文件天然不在视野里。
     */
    fun cleanupCandidates(archived: List<DiaryEntry>, digests: List<DiaryEntry>): List<DiaryEntry> =
        archived.filter { e ->
            digests.any { d -> d.date == e.date.take(7) && e.artifacts.all { it in d.artifacts } }
        }
}
