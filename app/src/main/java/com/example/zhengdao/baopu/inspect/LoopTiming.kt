// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百七十五批 · 闭环时延与振荡（收口计划 §5.1 要件⑥「时」：闭环时延／振荡）。
//
// 来历（派工单-2026-11-03-第三百七十五批）：四时闭环里"进行时"缺一个数——出了偏差，
// 多久才发现、多久才处置、多久恢复。363 落了偏差账（只记不判），386 接了检测侧
// （超步数／超时／挂起超时三类）；本文件是**纯函数统计器**：读既有账、只算不判、
// 不写任何账（拍板 2：**不落 `闭环时延.jsonl`**——统计永远能从账本重算，
// 落盘就是第二处真相；多一份账，就多一处漂移）。
//
// 四段时刻怎么对（派工单 §取数口径，逐条有测试；匹配不到 ⇒ 该段"缺失"，**不许当 0**）：
//   发生＝从偏差账**事实键**推：timeout 用 elapsed_ms（atMs−elapsed）、suspend_timeout
//         用 suspended_at；其余三类没有发生时刻的事实键 ⇒ 发现段缺失；
//   发现＝该偏差行的 atMs（记账即发现）；
//   处置＝卡处置账里第一条「时刻 ≥ 发现 且 subject == 偏差行 question」的行——
//         挂起超时的时钟收卡行与账面摘要同源（ConfirmFlow 超时分支先落偏差行、
//         同一刻落处置行）；其余类在处置账里没有可靠 join 键 ⇒ 缺失。
//         诚实标注未知区：Ruling 的 RESOLVED 行带的是 roundKey（OpinionWiring 轮源，
//         默认「进程级」），与 runId 不同命名空间，按键对不上 ⇒ 恢复只走恢复卡一条路；
//   恢复＝恢复卡处置行（326 那套：批准/拒绝落处置账，cardId == `restored-<suspended_at>`）
//         的时刻；该行缺失 ⇒ 恢复段缺失。恢复时延从处置行起算，处置行缺失时退回发现时刻。
//
// 振荡（判据③，**用户口径钉死不许自调**）：同一 kind 在任意 24h 窗口内 ≥3 次，
// 且出现「冻结→解除→再冻结」——窗口内某条已处置（解除），其后同窗内还有同 kind 新条目
// （再冻结；对挂起超时就是 挂起→收卡→再挂起）⇒ 打 OSCILLATION，**只标不处置**。
// 分辨率取外层口径「同一 kind」（派工单「同一 kind（或同一判据）」取 kind）。
//
// 四条纪律（承 363，InspectReadOnlyGateTest 的写口黑名单同样罩着本文件）：
//   1. 静默：零日志、零 UI、零卡——只算，读数行由调用方落日志；
//   2. 只读：不写任何账、不建目录、不改 DeviationLedger/CardDisposition 一行；
//   3. 只算不判：输出只有数字与 OSCILLATION 标记，零判决词；
//   4. 缺就是缺：匹配不到记"缺失"，不许拿 0 冒充——0 分钟和没对上是两回事。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.CardDisposition
import com.example.zhengdao.baopu.CardDisposition.CardDispositionRow
import java.io.File
import kotlin.math.ceil
import kotlin.math.roundToLong

/** 一段时延的统计（样本数／缺失数／p50／p90；分钟，四舍五入；无样本 ⇒ null）。 */
internal data class LoopSegmentStats(
    val samples: Int,
    val missing: Int,
    val p50Min: Long?,
    val p90Min: Long?,
)

/** 单类分桶（五类各一桶，没出现过的类 total=0 全空）。 */
internal data class KindLoopTiming(
    val kind: DeviationKind,
    val total: Int,
    val discover: LoopSegmentStats,
    val handle: LoopSegmentStats,
    val recover: LoopSegmentStats,
)

/** 振荡标记（判据③：只标不处置；from/to 是那个 24h 窗口的首尾时刻）。 */
internal data class LoopOscillation(
    val kind: DeviationKind,
    val count: Int,
    val fromMs: Long,
    val toMs: Long,
)

/** 全量统计：三段时延＋五类分桶＋振荡标记。 */
internal data class LoopTimingStats(
    val total: Int,
    val discover: LoopSegmentStats,
    val handle: LoopSegmentStats,
    val recover: LoopSegmentStats,
    val perKind: List<KindLoopTiming>,
    val oscillations: List<LoopOscillation>,
)

/**
 * 闭环时延统计器（375）：**纯函数、只读**。
 *
 * · 输入只有账目录（偏差账与卡处置账同挂 `抱朴记忆/` 一棵目录，各自文件名独立）；
 * · 输出 [LoopTimingStats] 与收工读数行 [reportLine]；体检层要重算就再调 [compute]；
 * · 不写任何账（判据④）、不许自调阈值（[OSCILLATION_WINDOW_MS]／[OSCILLATION_MIN_COUNT]
 *   是用户口径）。
 */
internal object LoopTiming {

    /** 毫秒 ⇒ 分钟。 */
    const val MS_PER_MINUTE: Long = 60_000L

    /** 振荡窗口：24h（用户口径，钉死不许自调——派工单 §边界）。 */
    const val OSCILLATION_WINDOW_MS: Long = 24L * 60L * MS_PER_MINUTE

    /** 振荡次数线：同 kind 窗口内 ≥3 次（用户口径，钉死不许自调）。 */
    const val OSCILLATION_MIN_COUNT: Int = 3

    private const val P50 = 0.5
    private const val P90 = 0.9
    private const val PERCENT = 100.0

    /**
     * 全量统计（整本偏差账；收工读数行与体检层重算走同一个口）。
     *
     * 派工单拍板 2：读数行里的时延是**整本账**口径（闭环时延是系统级数——收工那一刻
     * 本轮的处置/恢复多半还没发生，按轮算只会恒缺），条数 N 仍按 363 口径只数本轮。
     */
    fun compute(root: File): LoopTimingStats {
        val deviations = DeviationLedger.read(root, Int.MAX_VALUE).sortedBy { it.atMs }
        val disposals = readDisposals(root)
        val rows = deviations.map { resolve(it, disposals) }
        return LoopTimingStats(
            total = rows.size,
            discover = segment(rows) { it.discoverMs },
            handle = segment(rows) { it.handleMs },
            recover = segment(rows) { it.recoverMs },
            perKind = DeviationKind.entries.map { kind ->
                bucket(kind, rows)
            },
            oscillations = findOscillations(rows),
        )
    }

    /**
     * 收工读数一行（拍板 2 钉死的格式）：
     * `偏差 N 条｜发现 X 分 · 处置 Y 分 · 恢复 Z 分（缺 a/b/c 条）`。
     *
     * · N＝本轮偏差条数（363 口径原样，[DeviationLedger.countForRun] 单一来源）；
     * · X/Y/Z＝整本账三段时延的 p50（分钟；无有效样本 ⇒ "—"）；
     * · a/b/c＝三段各自「缺失」的条数（匹配不到不许当 0，判据②）。
     */
    fun reportLine(root: File, runId: String): String {
        val n = DeviationLedger.countForRun(root, runId)
        val s = compute(root)
        return "偏差 $n 条｜发现 ${fmt(s.discover)} 分 · 处置 ${fmt(s.handle)} 分 · 恢复 ${fmt(s.recover)} 分" +
            "（缺 ${s.discover.missing}/${s.handle.missing}/${s.recover.missing} 条）"
    }

    // ── 四段时刻的解析（私有；一条偏差 ⇒ 三段时延的毫秒值，对不上就是 null） ──

    private data class ResolvedRow(
        val entry: DeviationEntry,
        val occurredMs: Long?,
        val handledAtMs: Long?,
        val recoveredAtMs: Long?,
    ) {
        val discoverMs: Long? get() = occurredMs?.let { entry.atMs - it }
        val handleMs: Long? get() = handledAtMs?.let { it - entry.atMs }
        val recoverMs: Long? get() = recoveredAtMs?.let { it - (handledAtMs ?: entry.atMs) }
    }

    /** 发生时刻：只从事实键推（timeout＝atMs−elapsed_ms；suspend_timeout＝suspended_at）；没有键 ⇒ null。 */
    private fun occurredOf(e: DeviationEntry): Long? = when (e.kind) {
        DeviationKind.TIMEOUT ->
            e.facts[DeviationEntry.F_ELAPSED_MS]?.toLongOrNull()
                ?.takeIf { it > 0L }
                ?.let { elapsed -> (e.atMs - elapsed).takeIf { it >= 0L } }
        DeviationKind.SUSPEND_TIMEOUT ->
            e.facts[DeviationEntry.F_SUSPENDED_AT]?.toLongOrNull()?.takeIf { it > 0L }
        else -> null
    }

    /** 处置行：挂起超时按「subject == question 且时刻 ≥ 发现」取第一条；其余类没有键 ⇒ null。 */
    private fun handledOf(e: DeviationEntry, disposals: List<CardDispositionRow>): CardDispositionRow? {
        if (e.kind != DeviationKind.SUSPEND_TIMEOUT) return null
        val question = e.facts[DeviationEntry.F_QUESTION]?.trim().orEmpty()
        if (question.isEmpty()) return null
        return disposals.firstOrNull { it.atMs >= e.atMs && it.subject.trim() == question }
    }

    /** 恢复行：恢复卡处置行（326：cardId == `restored-<suspended_at>`）取第一条；没有挂起时刻 ⇒ null。 */
    private fun recoveredOf(e: DeviationEntry, disposals: List<CardDispositionRow>): CardDispositionRow? {
        if (e.kind != DeviationKind.SUSPEND_TIMEOUT) return null
        val suspendedAt = e.facts[DeviationEntry.F_SUSPENDED_AT]?.toLongOrNull() ?: return null
        return disposals.firstOrNull { it.cardId == "restored-$suspendedAt" && it.atMs >= e.atMs }
    }

    private fun resolve(e: DeviationEntry, disposals: List<CardDispositionRow>): ResolvedRow = ResolvedRow(
        entry = e,
        occurredMs = occurredOf(e),
        handledAtMs = handledOf(e, disposals)?.atMs,
        recoveredAtMs = recoveredOf(e, disposals)?.atMs,
    )

    /** 卡处置账只读（盘上那份；坏行跳过；上界与 [CardDisposition.READ_LIMIT] 同口径）。 */
    private fun readDisposals(root: File): List<CardDispositionRow> {
        val f = CardDisposition.fileIn(root)
        if (!f.isFile) return emptyList()
        return runCatching {
            val lines = f.readLines(Charsets.UTF_8)
            val capped = if (lines.size > CardDisposition.READ_LIMIT) lines.takeLast(CardDisposition.READ_LIMIT) else lines
            capped.mapNotNull { CardDispositionRow.parse(it) }.sortedBy { it.atMs }
        }.getOrDefault(emptyList())
    }

    // ── 分桶与百分位（最近邻秩，手算可对拍——判据①） ────────────────────────

    private fun segment(rows: List<ResolvedRow>, pick: (ResolvedRow) -> Long?): LoopSegmentStats {
        val values = rows.mapNotNull(pick).sorted()
        return LoopSegmentStats(
            samples = values.size,
            missing = rows.size - values.size,
            p50Min = percentileMin(values, P50),
            p90Min = percentileMin(values, P90),
        )
    }

    private fun bucket(kind: DeviationKind, rows: List<ResolvedRow>): KindLoopTiming {
        val sub = rows.filter { it.entry.kind == kind }
        return KindLoopTiming(
            kind = kind,
            total = sub.size,
            discover = segment(sub) { it.discoverMs },
            handle = segment(sub) { it.handleMs },
            recover = segment(sub) { it.recoverMs },
        )
    }

    /** 最近邻秩百分位（升序表，idx＝⌈p·n⌉−1）；分钟＝毫秒四舍五入；空表 ⇒ null。 */
    private fun percentileMin(sortedAscMs: List<Long>, p: Double): Long? {
        if (sortedAscMs.isEmpty()) return null
        val idx = ceil(p * sortedAscMs.size).toInt().coerceIn(1, sortedAscMs.size) - 1
        val ms = sortedAscMs[idx]
        return (ms.toDouble() / MS_PER_MINUTE.toDouble()).roundToLong()
    }

    // ── 振荡（判据③：≥3 次／24h ＋ 冻结→解除→再冻结；只标不处置） ─────────────

    private fun findOscillations(rows: List<ResolvedRow>): List<LoopOscillation> {
        val marks = mutableListOf<LoopOscillation>()
        for (kind in DeviationKind.entries) {
            val sub = rows.filter { it.entry.kind == kind }
            if (sub.size < OSCILLATION_MIN_COUNT) continue
            val mark = oscillationMark(kind, sub) ?: continue
            marks += mark
        }
        return marks
    }

    /** 在同 kind 的时间升序行里找第一个满足判据的 24h 窗口；没有 ⇒ null。 */
    private fun oscillationMark(kind: DeviationKind, sub: List<ResolvedRow>): LoopOscillation? {
        var i = 0
        while (i < sub.size) {
            val window = sub.drop(i).takeWhile { it.entry.atMs - sub[i].entry.atMs <= OSCILLATION_WINDOW_MS }
            if (window.size >= OSCILLATION_MIN_COUNT && hasRecurrenceAfterHandling(window)) {
                return LoopOscillation(
                    kind = kind,
                    count = window.size,
                    fromMs = window.first().entry.atMs,
                    toMs = window.last().entry.atMs,
                )
            }
            i += 1
        }
        return null
    }

    /** 冻结→解除→再冻结：窗口内某条已处置（解除），且其后同窗内还有同 kind 新条目（再冻结）。 */
    private fun hasRecurrenceAfterHandling(window: List<ResolvedRow>): Boolean = window.any { row ->
        val handledAt = row.handledAtMs ?: return@any false
        window.any { later -> later.entry.atMs > handledAt }
    }

    /** 读数行里的一个数：p50 分钟；无样本 ⇒ "—"（缺失数另有其位，不许拿 0 冒充）。 */
    private fun fmt(s: LoopSegmentStats): String = s.p50Min?.toString() ?: "—"
}
