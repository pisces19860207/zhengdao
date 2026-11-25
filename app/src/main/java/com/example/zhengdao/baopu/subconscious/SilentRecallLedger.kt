// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十五批 · 自身账（只写这一本）与三账共享去重（只读那三本）。
//
// 边界：676 的 回响.jsonl、677 的 灵感闪回.jsonl 只读不改；本单的 静默回想.jsonl
// 用起居注同形 ACTION 行，浮出/点开各一行且都带账行 id。共享去重的窗口是滚动 24h
// （不是日历日），日闸另按 PatrolScheduler.dayBucket 判——两把尺各管一件事。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.inspect.PatrolScheduler
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.safety.DiaryCodec
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import java.io.File

/** 本单账的 why 前缀（不误吃别人的 ACTION 行）。 */
internal const val SILENT_WHY = "625 静默回想"

/** 本单账文件名（在 `<工作区>/产物/` 下，用户可见）。 */
internal const val SILENT_LEDGER_FILE = "静默回想.jsonl"

/** 本单自身的账（浮出/点开两行，均带账行 id）。 */
internal object SilentRecallLedger {

    /** 浮出一行的 why 状态格。 */
    const val FLOATED = "浮出"

    /** 点开一行的 why 状态格。 */
    const val OPENED = "点开"

    fun fileOf(workspace: File): File =
        File(File(workspace, BaopuKernel.Data.DIR_PRODUCTS), SILENT_LEDGER_FILE)

    fun append(file: File, event: DiaryEvent): Boolean = runCatching {
        file.parentFile?.mkdirs()
        file.appendText(DiaryCodec.encode(event) + "\n")
        true
    }.getOrDefault(false)

    fun read(file: File): List<DiaryEvent> = runCatching {
        if (!file.isFile) emptyList()
        else file.readLines(Charsets.UTF_8).mapNotNull(DiaryCodec::parse)
    }.getOrDefault(emptyList())

    fun floatedEvent(atMs: Long, line: SilentLine): DiaryEvent = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$SILENT_WHY·$FLOATED·${line.ledgerId}",
        what = line.title,
        result = "${line.sourceLabel}·账行 ${line.ledgerId}",
    )

    fun openedEvent(atMs: Long, line: SilentLine): DiaryEvent = DiaryEvent(
        kind = DiaryKind.ACTION,
        atMs = atMs,
        why = "$SILENT_WHY·$OPENED·${line.ledgerId}",
        what = "账行 ${line.ledgerId} 的想起缘由已放行一次",
        result = line.reason,
    )

    /** 最近一次浮出在哪个日历日桶（≤1 行/天的读侧）。 */
    fun lastFloatedDay(events: List<DiaryEvent>): Long? = events
        .filter { it.why.contains("·$FLOATED·") }
        .maxOfOrNull { PatrolScheduler.dayBucket(it.atMs) }
}

/**
 * 676/677/625 共享的去重读口（**只读**）：把三本账的 why 归一成账行键，
 * 取 [WINDOW_MS] 滚动窗内的键集。同一账行 24h 只出现一次——这是三单共用的尺，
 * 谁都不用改自己的落账格式（前缀各自保留，只在这里归一）。
 */
internal object SharedRecallDedupe {

    /** 同一账行的共享抑制窗（滚动 24 小时）。 */
    const val WINDOW_MS: Long = 24L * 60 * 60 * 1000

    /**
     * 账行键归一化：676 `676 回响 · echo:L2-x`、677 `534 灵感闪回·浮出·insp-x`、
     * 625 `625 静默回想·浮出·L2-x` ⇒ 分别落到 `L2-x` / `insp-x` / `L2-x`。
     * 认不出的前缀返回 null（不把别人的账误算成自己的键）。
     */
    fun keyOf(why: String): String? {
        val raw = why.trim()
        val known = listOf(SubconsciousRunner.ACTION_WHY, InspirationLedger.WHY, SILENT_WHY)
        if (known.none(raw::startsWith)) return null
        return raw.substringAfterLast('·').trim().removePrefix("echo:").takeIf(String::isNotBlank)
    }

    /** 滚动 24h 内出现过的账行键（未来时刻/过期行都不算）。 */
    fun recentKeys(events: List<DiaryEvent>, nowMs: Long): Set<String> = events.mapNotNull { e ->
        val key = keyOf(e.why) ?: return@mapNotNull null
        val age = nowMs - e.atMs
        if (age in 0L..WINDOW_MS) key else null
    }.toSet()

    /** 从三本账的文件路径读回键集；坏文件/坏行跳过（不静默：调用方会记日志）。 */
    fun readKeys(files: List<File>, nowMs: Long): Set<String> = recentKeys(
        files.flatMap(SilentRecallLedger::read),
        nowMs,
    )
}
