// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十八批 · 已退役条目的**读数投影**（757 图纸 §2.2 施工）：
// 入口行 `已退役 N 条` 与列表页三列（标题／退役时间／原因）。
//
// 两条硬口径（判据②的反例靶）：
//   1. **N 扫当前记忆库**（[retiredCountOf] 数 RETIRED 态条目），不是账行累计、
//      不是状态文件的 lastRetired——「账里 5 条退役历史行但当前库只有 2 条」必须显示 2；
//   2. 三列各有唯一来源，不许编造：标题＝条目本体（[MemoryEntry.title]）；退役时间＝
//      账里该 id **最近一次**退役/合并吸收行的 atMs，找不到 ⇒ null（界面显示
//      「时间未知」，**不许拿当前时间补**）；原因＝FLAG_MERGED 有无二选一。
//
// 投影是纯函数（752 [pruneReadoutOf] 同房型：数据不是文案，JVM 直测）；
// 本文件零 Android、零 IO、零时钟。
package com.example.zhengdao.baopu.memoryprune

import com.example.zhengdao.baopu.MemoryEntry
import com.example.zhengdao.baopu.MemoryQuality
import com.example.zhengdao.baopu.MemoryStatus
import com.example.zhengdao.baopu.safety.DiaryEvent

/** 一条已退役条目的三列投影（757 §2.2：三列各有唯一来源）。 */
internal data class RetiredRow(
    val id: String,
    /** 列 1：条目本体标题（原地文件）。 */
    val title: String,
    /** 详情展开用：正文原文（不加工）。 */
    val body: String,
    /**
     * 列 2：最近一次退役/合并吸收的账面时刻；账里找不到 ⇒ null
     * （界面渲染「时间未知」，不许拿当前时间补——757 §2.2 原话）。
     */
    val retiredAtMs: Long?,
    /** 列 3：true ⇒ 冗余合并吸收（[MemoryQuality.FLAG_MERGED]）；false ⇒ 超期未引用。 */
    val merged: Boolean,
)

/** 入口计数：扫当前记忆库的 RETIRED 态条目数（757 §2.2——不是账行累计）。 */
internal fun retiredCountOf(entries: List<MemoryEntry>): Int =
    entries.count { it.status == MemoryStatus.RETIRED }

/**
 * 入口行文案（数据投影，752 读数卡「读数是数据不是文案」同房型）；
 * N ≤ 0 ⇒ null（入口行不显示，不留无意义入口——757 §2.2）。
 */
internal fun retiredEntryTextOf(retiredCount: Int): String? =
    if (retiredCount <= 0) null else "已退役 $retiredCount 条"

/** 列表投影：当前库的 RETIRED 条目逐条 ＋ 账里最近一次退役时刻。 */
internal fun retiredRowsOf(entries: List<MemoryEntry>, ledger: List<DiaryEvent>): List<RetiredRow> {
    val times = retireTimesOf(ledger)
    return entries.filter { it.status == MemoryStatus.RETIRED }
        .sortedBy { it.id }
        .map { e ->
            RetiredRow(
                id = e.id,
                title = e.title,
                body = e.body,
                retiredAtMs = times[e.id],
                merged = e.qualityFlags.contains(MemoryQuality.FLAG_MERGED),
            )
        }
}

/**
 * 账 ⇒ 每条 id 的最近退役时刻：直接退役行（why＝`752 记忆修剪·退役·<id>`）取整段 id；
 * 被合并吸收的（带 FLAG_MERGED）从合并行 result 的**指针全集**里解析（757 §2.2 列 2 口径）。
 * 同 id 多行取**最近一条**（max atMs——撤销后再退役的时刻要盖过旧的）。
 */
internal fun retireTimesOf(ledger: List<DiaryEvent>): Map<String, Long> {
    val directPrefix = "${PruneLedger.WHY}·退役·"
    val mergedPrefix = "${PruneLedger.WHY}·合并·"
    val times = mutableMapOf<String, Long>()
    for (row in ledger) {
        val ids: List<String> = when {
            row.why.startsWith(directPrefix) -> listOf(row.why.removePrefix(directPrefix))
            row.why.startsWith(mergedPrefix) -> mergedPointerIds(row.result)
            else -> emptyList()
        }
        ids.forEach { id -> times.merge(id, row.atMs, ::maxOf) }
    }
    return times
}

/**
 * 合并行 result 的指针全集解析（752 [PruneLedger.mergedEvent] 的 result 形态：
 * 「指针全集：a,b（merged_from 全保留，原件只标退役未删）」）。解析不出 ⇒ 空表
 * （宁可显示「时间未知」也不猜）。
 */
internal fun mergedPointerIds(result: String): List<String> {
    val key = "指针全集："
    val start = result.indexOf(key)
    if (start < 0) return emptyList()
    val from = start + key.length
    val end = result.indexOf('（', from)
    val body = if (end < 0) result.substring(from) else result.substring(from, end)
    return body.split(",").map { it.trim() }.filter { it.isNotEmpty() }
}
