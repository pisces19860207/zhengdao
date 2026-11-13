// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第六百一十批（通读框架 · 断点与网络）。两条硬规则：
// 1. 断点续跑**不重跑已完成片**（判据⑥）——已完成集合是账本（.progress.json），
//    续跑只补缺；书换了内容（sha 变）⇒ 账本作废重跑，不拿旧账本接新书；
// 2. 网络质量差（RTT/失败率/重连）⇒ 降并发或暂停——暂停必须停在片边界，
//    checkpoint 落盘后如实报告进度，续跑接着跑（判据④）。
package com.example.zhengdao.baopu.readthrough

import com.example.zhengdao.baopu.library.FaceIndex
import org.json.JSONObject

/**
 * 断点账本（内存形状）。落盘是薄壳（工具层读写 `.progress.json`），判定全在纯函数。
 *
 * [bookSha] 是全书文本的 sha256——续跑前先对书：书没变才续，书变了账本作废
 * （拿旧账本接新书 = 半本旧事实＋半本新事实，比重跑还糟）。
 */
internal data class Checkpoint(
    val bookSha: String,
    val mode: String,
    val query: String,
    val doneSlices: Set<String>,
    val doneChapters: Set<Int>,
    val doneMerges: Set<String>,
    val updatedAt: Long,
) {
    fun withSlices(ids: Collection<String>, chapters: Collection<Int>, now: Long) = copy(
        doneSlices = doneSlices + ids,
        doneChapters = doneChapters + chapters,
        updatedAt = now,
    )

    fun withMerge(id: String, now: Long) = copy(
        doneMerges = doneMerges + id,
        updatedAt = now,
    )

    /** 这本书这份账本是否还能续（书一致＋模式一致——换了模式，已完成集合没有意义）。 */
    fun matches(bookSha: String, mode: String): Boolean =
        this.bookSha == bookSha && this.mode == mode
}

internal object CheckpointStore {

    fun toJson(cp: Checkpoint): String = JSONObject().apply {
        put("book_sha", cp.bookSha)
        put("mode", cp.mode)
        put("query", cp.query)
        put("done_slices", cp.doneSlices.joinToString(","))
        put("done_chapters", cp.doneChapters.sorted().joinToString(","))
        put("done_merges", cp.doneMerges.sorted().joinToString(","))
        put("updated_at", cp.updatedAt)
    }.toString()

    /** 宽松解析：坏账本不抛——返回 null ⇒ 当全新开跑（如实报告「账本读不出，重头跑」）。 */
    fun fromJson(text: String): Checkpoint? = runCatching {
        val o = JSONObject(text)
        Checkpoint(
            bookSha = o.optString("book_sha"),
            mode = o.optString("mode"),
            query = o.optString("query"),
            doneSlices = splitSet(o.optString("done_slices")),
            doneChapters = splitSet(o.optString("done_chapters")).mapNotNull { it.toIntOrNull() }.toSet(),
            doneMerges = splitSet(o.optString("done_merges")),
            updatedAt = o.optLong("updated_at"),
        )
    }.getOrNull()

    /** 续跑要跑的片＝计划片 − 已完成（账本不匹配 ⇒ 全部要跑）。 */
    fun pendingSlices(all: List<ReadSlice>, cp: Checkpoint?, bookSha: String, mode: String): List<ReadSlice> {
        if (cp == null || !cp.matches(bookSha, mode)) return all
        return all.filter { it.id !in cp.doneSlices }
    }

    /** 重跑只补缺章：计划章 − 输出目录里已有事实文件的章（账本丢了时的兜底口径）。 */
    fun missingChapters(planned: List<Int>, existingFiles: Set<Int>): List<Int> =
        planned.filter { it !in existingFiles }

    /** 全书文本的 sha（续跑对书用；与 ChapterDigest 同一套 hash）。 */
    fun bookSha(text: String): String = FaceIndex.sha256Bytes(text.toByteArray(Charsets.UTF_8))

    private fun splitSet(s: String): Set<String> =
        if (s.isBlank()) emptySet() else s.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
}

/** 一次网络采样（两次决策之间积累的观测）。 */
internal data class NetSample(
    /** 本窗口每次调用的 RTT（毫秒；失败调用不记）。 */
    val rttsMs: List<Long> = emptyList(),
    /** 本窗口逐次调用成败（true=成功）。 */
    val results: List<Boolean> = emptyList(),
    /** 连续失败次数（跨窗口累计；成功清零）。 */
    val consecutiveFailures: Int = 0,
    /** 本窗口内的重连次数（连接层断线重连——比单次失败重得多）。 */
    val reconnects: Int = 0,
) {
    val failureRate: Double get() = if (results.isEmpty()) 0.0 else results.count { !it }.toDouble() / results.size
}

internal sealed interface NetDecision {
    /** 继续跑（可能已降并发）。 */
    data class Proceed(val concurrency: Int) : NetDecision

    /** 暂停：停在片边界，checkpoint 落盘，如实报告（不是失败——是等网络喘口气）。 */
    data class Pause(val reason: String) : NetDecision
}

/**
 * 网络质量守卫（**纯函数**：观测进、决策出；策略同源才可测）。
 *
 * 三档：
 * - 失败率 ≥0.8 / 连败 ≥3 / 重连 ≥3 ⇒ **暂停**（再跑就是烧额度）；
 * - 失败率 ≥0.5 / RTT p90 ≥10s ⇒ **降并发**（减半，地板 1——并发救不了烂网，但减半能止血）；
 * - 其余 ⇒ 按当前并发继续。
 */
internal object NetworkGuard {

    const val DEFAULT_CONCURRENCY = 2
    const val MAX_CONCURRENCY = 4
    const val MIN_CONCURRENCY = 1

    const val PAUSE_FAILURE_RATE = 0.8
    const val DEGRADE_FAILURE_RATE = 0.5
    const val PAUSE_CONSECUTIVE_FAILURES = 3
    const val PAUSE_RECONNECTS = 3
    const val RTT_DEGRADE_MS = 10_000L

    fun decide(sample: NetSample, currentConcurrency: Int): NetDecision {
        if (sample.consecutiveFailures >= PAUSE_CONSECUTIVE_FAILURES) {
            return NetDecision.Pause("连续失败 ${sample.consecutiveFailures} 次")
        }
        if (sample.reconnects >= PAUSE_RECONNECTS) {
            return NetDecision.Pause("窗口内重连 ${sample.reconnects} 次")
        }
        if (sample.results.size >= 2 && sample.failureRate >= PAUSE_FAILURE_RATE) {
            return NetDecision.Pause("窗口失败率 ${"%.0f".format(sample.failureRate * 100)}%")
        }
        val rttP90 = percentile(sample.rttsMs, 0.9)
        if ((sample.results.size >= 2 && sample.failureRate >= DEGRADE_FAILURE_RATE) || rttP90 > RTT_DEGRADE_MS) {
            val halved = (currentConcurrency / 2).coerceAtLeast(MIN_CONCURRENCY)
            val why = buildString {
                if (sample.failureRate >= DEGRADE_FAILURE_RATE) append("失败率 ${"%.0f".format(sample.failureRate * 100)}%")
                if (rttP90 > RTT_DEGRADE_MS) {
                    if (isNotEmpty()) append("，")
                    append("RTT p90 ${rttP90}ms")
                }
                append(" ⇒ 并发 $currentConcurrency→$halved")
            }
            return NetDecision.Proceed(halved)
        }
        return NetDecision.Proceed(currentConcurrency)
    }

    /** 最近窗口 p90（样本 <10 时取最大——小样本别假装有分位数）。 */
    private fun percentile(values: List<Long>, p: Double): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        return if (sorted.size < 10) sorted.last()
        else sorted[((sorted.size - 1) * p).toInt()]
    }
}
