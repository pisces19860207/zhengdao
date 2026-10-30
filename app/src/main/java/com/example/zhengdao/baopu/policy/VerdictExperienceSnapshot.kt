// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十八批 · 判决经验表：**不可变快照**（判决路径读的那一份）。
//
// 280 交了"存"，283 交了"学"，但判决路径（调解器）至今没读过那张表——每判一条就要
// `store.readAll()` 全本扫一遍（283 的 `onOpinion` 里那句），而且那个闸**生产零调用**。
// 本批把"学出来的阈值/次序"折成一份**建好就不再改**的表，判决只读它：
//
//   - 读：`suppresses(类, now)` = 一次 Map 取 ＋ 该类自己的"关"时刻列表（建表时截断到
//     [MAX_CLOSE_TIMES] 条）⇒ 代价与**表里有多少类**无关（判据④「快照查 O(1)」的本体）；
//   - 写：只有 [VerdictExperienceRuntime] 在建表完成后一次性换指针；表没有 setter、字段
//     全是 `val`＋只读集合，**"改了一半"的形状在类型上构造不出来**；
//   - 自检：构造器把跨字段不变式钉死（[closeTotal] 必须等于明细求和、抑制类数必须与明细
//     对得上、每条时刻必须落在建表那一刻的窗口里、阈值不许越界）。半截表不是"行为不对"，
//     是**根本建不出来**——负例④由结构兜住，不靠运气。
//
// 30 天窗口在这里是**尺子**不是过滤器：表里存"关"的原始时刻，读时按 `now` 现算窗内次数，
// 所以一张没人重算的老表也会自然出窗。窗口的算法照旧走 283 的 [LearnPolicy.stats]
// （同一把尺子；建表时把我这边的时刻列表与它数出来的次数**对一次账**，对不上就拒绝建表）。
//
// 零 Android / 零 IO / 零时钟：`rows`、`now` 全由调用方给；`policy/` 包由 122 批的
// `PolicySourceGateTest` 逐文件钉死这三条，本文件白拿那道门禁。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.agent.ExperienceKind
import com.example.zhengdao.baopu.agent.ExperienceRow
import com.example.zhengdao.baopu.agent.LearnPolicy

/** 信号行里"关"的机器键（283 定的：关卡与驳回都是"别再弹"；搁置是"以后"，不计）。 */
private val CLOSE_KEYS = setOf("signal:dismiss", "signal:reject")

private const val DAY_MS = 24L * 3_600_000L

/**
 * 30 天窗的起点（**尺子**：每次读都按 `now` 现算，与 `LearnPolicy.stats` 同一把）。
 *
 * 写成函数而不是存一个绝对时刻：表可能是三天前建的，窗必须跟着"此刻"走，
 * 否则一张没人重算的老表会把窗外的事当成窗内数。
 */
private fun closeWindowStart(now: Long): Long = now - LearnPolicy.WINDOW_DAYS * DAY_MS

/**
 * 一个类在表里的那一行（**只有类名、时刻与计数**，没有意见原文、没有用户原话）。
 *
 * @param closeTimes 该类的"关"时刻（窗内、最近一次重问之后），新→旧，最多 [VerdictExperienceSnapshot.MAX_CLOSE_TIMES] 条
 * @param windowStartMs 建表那一刻的窗口起点（判据①要能说出"这几次关是 30 天内数的"）
 */
internal data class ClassExperience(
    val cardClass: String,
    val closeTimes: List<Long>,
    val approves: Int,
    val rejects: Int,
    val windowStartMs: Long,
    val lastReaskAtMs: Long?,
) {
    /** 截至 `now` 仍在 30 天窗内的"关"次数（窗是尺子：读时现算，不靠谁定期重算）。 */
    fun closesAt(now: Long): Int = closeTimes.count { it <= now && it >= closeWindowStart(now) }
}

/**
 * 判决经验表的一份快照（**建好即不可变**）。
 *
 * [seq] 是"第几版表"：每次换指针 +1；读侧拿到的永远是完整的一版，不会读到"半改版"。
 */
internal data class VerdictExperienceSnapshot(
    val seq: Long,
    val builtAtMs: Long,
    /** 学到的阈值：同类 30 天内被关到几次就"不弹"（283 的 `SUPPRESS_AFTER_CLOSES`）。 */
    val threshold: Int,
    val classes: Map<String, ClassExperience>,
    /** 建表扫过的总行数（审计：这张表是从多厚的账上折出来的）。 */
    val rowCount: Int,
    /** 明细求和（跨字段不变式的一条边：半截表在这儿对不上）。 */
    val closeTotal: Int,
    /** 按阈值应被抑制的类数（不变式的另一条边）。 */
    val suppressedTotal: Int,
) {

    init {
        // ── 自检（负例④「读到改了一半的表」的结构解）──────────────────────────
        require(seq >= 0L) { "快照序号不合法：$seq" }
        require(threshold in 1..MAX_THRESHOLD) { "学到的阈值越界：$threshold（只许 1–$MAX_THRESHOLD）" }
        val sumOfCloses = classes.values.sumOf { it.closeTimes.size }
        require(closeTotal == sumOfCloses) { "表不自洽：明细求和 $sumOfCloses ≠ 账面 $closeTotal（半截表）" }
        require(suppressedTotal == classes.count { it.value.closeTimes.size >= threshold }) {
            "表不自洽：抑制类数与明细对不上（半截表）"
        }
        classes.forEach { (key, row) ->
            require(key == row.cardClass) { "表键与行内类名不一致：$key ≠ ${row.cardClass}" }
            require(row.closeTimes.size <= MAX_CLOSE_TIMES) {
                "类 $key 的「关」明细 ${row.closeTimes.size} 条超上限 $MAX_CLOSE_TIMES（查表代价必须与账厚无关）"
            }
            require(row.closeTimes.all { it in row.windowStartMs..builtAtMs }) {
                "类 $key 的「关」时刻落在建表窗口之外（表与窗口不自洽）"
            }
        }
    }

    /** 这一类此刻该不该"不弹"（**判决路径唯一的查表动作**：一次 Map 取 + 有界小列表）。 */
    fun suppresses(cardClass: String, now: Long): Boolean =
        classes[cardClass]?.closesAt(now)?.let { it >= threshold } ?: false

    /** 命中时那句能落账的理由；**没命中返回 null**（不许编一句"学到了什么"）。 */
    fun reasonFor(cardClass: String, now: Long): String? {
        val closes = classes[cardClass]?.closesAt(now) ?: return null
        if (closes < threshold) return null
        return "同类 ${LearnPolicy.WINDOW_DAYS} 天内已被关 $closes 次（阈值 $threshold，命中经验表 v$seq）——学到的结论：不弹"
    }

    /** 次序读数（学习的另一半：批过的类往前排）。表里没这一类 ⇒ 全 0，与 283 的缺类口径一致。 */
    fun statsOf(cardClass: String, now: Long): LearnPolicy.ClassStats = LearnPolicy.ClassStats(
        closes = classes[cardClass]?.closesAt(now) ?: 0,
        approves = classes[cardClass]?.approves ?: 0,
        rejects = classes[cardClass]?.rejects ?: 0,
        lastReaskAtMs = classes[cardClass]?.lastReaskAtMs,
    )

    /** 命中抑制的类名（只有类名，没有明细——体检/审计读它，别把时刻递出去）。 */
    fun suppressedClasses(now: Long): List<String> = classes.keys.filter { suppresses(it, now) }.sorted()

    /** 一行结论（只出计数，不出明细；与 280 的 `conclusion()` 同一口径）。 */
    fun conclusion(now: Long): String =
        "经验表 v$seq：$rowCount 行折出 ${classes.size} 类，阈值=$threshold，" +
            "当前抑制 ${suppressedClasses(now).size} 类（关明细合计 $closeTotal 条）"

    companion object {
        /** 没表就是这一份：任何类都查不中 ⇒ 判决路径与"根本没接表"逐字一致（判据②的底）。 */
        val EMPTY = VerdictExperienceSnapshot(
            seq = 0L,
            builtAtMs = 0L,
            threshold = LearnPolicy.SUPPRESS_AFTER_CLOSES,
            classes = emptyMap(),
            rowCount = 0,
            closeTotal = 0,
            suppressedTotal = 0,
        )

        /** 一个类最多留几条"关"时刻：关到阈值就该压，留 64 条足够把 30 天数穿。 */
        const val MAX_CLOSE_TIMES = 64

        /** 阈值上限（写成 11 就等于"学到"变成"永远不弹"）。 */
        const val MAX_THRESHOLD = 10

        /**
         * 折表（**唯一建表入口**）：账上的行 ⇒ 一份自洽的表。
         *
         * 先按类分组、再逐类调 [LearnPolicy.stats]（窗口/重问清零的语义只在 283 那一处写），
         * 并把"我存下的时刻条数"与"它数出来的次数"对一次账：对不上 ⇒ 抛，由
         * [VerdictExperienceRuntime] 保留上一版好表（fail closed，不换半截）。
         */
        fun build(
            rows: List<ExperienceRow>,
            now: Long,
            seq: Long,
            threshold: Int = LearnPolicy.SUPPRESS_AFTER_CLOSES,
        ): VerdictExperienceSnapshot {
            val windowStart = now - LearnPolicy.WINDOW_DAYS * DAY_MS
            val entries = rows.groupBy { it.cardClass }.mapValues { (cls, own) ->
                val s = LearnPolicy.stats(own, cls, now)
                val closes = own
                    .filter { it.kind == ExperienceKind.SIGNAL && it.key in CLOSE_KEYS }
                    .filter { it.atMs >= windowStart && it.atMs <= now }
                    // 重问清零（283 同一条）：最近一次重问之前的"关"不再作数
                    .filter { s.lastReaskAtMs == null || it.atMs > s.lastReaskAtMs }
                    .map { it.atMs }
                    .sortedDescending()
                    .take(MAX_CLOSE_TIMES)
                require(closes.size == s.closes || closes.size == MAX_CLOSE_TIMES) {
                    "类 $cls 的时刻列表与 LearnPolicy.stats 对不上账（明细 ${closes.size} ≠ 读数 ${s.closes}）——两把尺子必红一条"
                }
                ClassExperience(
                    cardClass = cls,
                    closeTimes = closes,
                    approves = s.approves,
                    rejects = s.rejects,
                    windowStartMs = windowStart,
                    lastReaskAtMs = s.lastReaskAtMs,
                )
            }
            return VerdictExperienceSnapshot(
                seq = seq,
                builtAtMs = now,
                threshold = threshold,
                classes = entries,
                rowCount = rows.size,
                closeTotal = entries.values.sumOf { it.closeTimes.size },
                suppressedTotal = entries.count { it.value.closeTimes.size >= threshold },
            )
        }
    }
}
