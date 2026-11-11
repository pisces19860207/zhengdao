// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十九批（2026-11-11）· 进化判定样本的**口径**（纯判据：零 IO、零时钟、零 Android）。
//
// 派工单原话：「进化判定样本量不够（几条沟只有个位数）⇒ 扩样本，让灰度/转正有统计意义」。
// 568-A 挂的账在这里兑现：四个低风险域接进既有链之后，观察账是落了，但**几条沟只有个位数**
// ——个位数上算出来的失败率不是读数，是噪声。本文件只定一件事：**多少次算够**。
//
// 三条线（写死在这里，不读配置、不读模型自述、不从别处抄）：
//   · **转正**：≥20 次独立事件 **且** ≥7 天窗口（两个都要：20 次挤在同一天＝一次性事件，
//     7 天里只跑 3 次＝没跑过，两种都不构成统计意义）；
//   · **灰度**：≥8 次（灰度是"能跑，但每一步都记账"，8 次够看清有没有坏消息）；
//   · **不足**：<8 次 ⇒ 只能判「样本不足」，**fail-closed**——不许拿"看起来还行"顶上去。
//
// 为什么 20/7/8 这三个数不跟 473（`CanaryLadder.MIN_RUNS`＝10、`WINDOW_DAYS`＝14）合并：
// 那是**指标窗口**（近 14 天跑成什么样），这是**样本门槛**（够不够格谈档位）。两件事各算各的，
// 合并任何一边都是改 495/517 的聚合口径——派工单 ⑤ 明令不许。本文件因此**一个既有常量都不引用**，
// 三个数在下面写成自己的常量；`EvoSampleGateTest` 里那两条「判据⑤」用例反向钉住 473/495/517
// 的三个字面量与"照数不重"的计数口径没被顺手改动。
//
// 去重（派工单 ③ 的两条，都在 [fold] 里）：
//   · **同会话同一事件只算一次**：键＝(会话, 事件)；同一行被读两遍、池被重放两遍都只算一次
//     （这条同时是"样本池可重放"的结构保证，见 ④）；
//   · **同一用户连续同类动作合并计数**（防刷）：同会话相邻两条、动作类相同、间隔 ≤[BURST_GAP_MS]
//     ⇒ 只算一次。人连点十下不是一个独立事件；但隔一天的同类动作是两次独立选择，照数。
//
// 边界：不碰 `Canary.kt`／`EvolutionDepth.kt`／`ConsequenceWiring.kt`／`LowRiskEvo.kt`；
// 本文件不落盘（落盘在 `EvoSamplePool`），不记账（记账在 `EvoSampleWiring.promote`）。
package com.example.zhengdao.baopu.evolution.samples

import com.example.zhengdao.baopu.consequence.CanaryCapability

/**
 * 样本门槛的裁决（**闭集三态**，没有第四种"再看看"）。
 *
 * 线值一旦落账/落读数就是稳定键（[wire]），改字面量＝老读数读不回来。
 */
internal enum class EvoSampleVerdict(val wire: String, val cn: String) {

    /** 够转正：≥20 次独立事件且 ≥7 天窗口。 */
    PROMOTE_READY("promote_ready", "够转正"),

    /** 只够灰度：≥8 次但还够不上转正的两条（次数或天数差一条）。 */
    CANARY_ONLY("canary_only", "只能灰度"),

    /** 样本不足：<8 次——这一档只许报"样本不足"，不许往前推。 */
    INSUFFICIENT("insufficient", "样本不足"),
    ;

    companion object {
        /** 线值 ⇒ 三态；认不出 ⇒ null（自造态不渲染，也不静默当"不足"）。 */
        fun ofWire(wire: String): EvoSampleVerdict? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 一次**独立**样本事件（池里的一行）。
 *
 * @param domain 域＝473 登记表里的能力线值（[CanaryCapability.wire]）：不在册的域建不出事件，
 *               于是"每域一个文件"不会因为拼错一个字符串就多出一个域。
 * @param sessionId 会话号（与 `DecisionDraft.sessionId` 同一把尺）；**-1（无会话）不许进池**——
 *               没有会话就没有"同会话同一事件"这把尺，去重会退化成按事件 id 全局去重。
 * @param eventId 事件号：同一会话内唯一（一次被灰度的执行就是一个事件）。
 * @param kind 动作类（防刷合并按它比）。
 * @param atMs 观察时刻，由调用方给（本包零时钟）。
 */
internal data class EvoSampleEvent(
    val domain: String,
    val sessionId: Long,
    val eventId: String,
    val kind: String,
    val atMs: Long,
) {
    init {
        require(CanaryCapability.ofWire(domain) != null) { "没登记在册的域「$domain」：样本池按登记表分文件，不许自造域" }
        require(sessionId >= 0L) { "样本事件缺会话（sessionId=$sessionId）：没有会话就算不了「同会话同一事件只算一次」" }
        require(eventId.isNotBlank()) { "样本事件缺事件号：没有事件号，去重和重放都没有依据" }
        require(kind.isNotBlank()) { "样本事件缺动作类：防刷合并按动作类比，空类会把不相干的动作并成一条" }
        require(atMs >= 0L) { "样本时刻由调用方给（本包零时钟），且不许为负" }
    }
}

/**
 * 去重之后的清点（数字全在这里，读侧不许再数一遍）。
 *
 * @param kept 去重＋防刷合并之后剩下的**独立事件**条数（判据只看它）
 * @param droppedDuplicate 同会话同一事件被吞掉的条数（重放/重复读的痕迹）
 * @param droppedBurst 连续同类动作被合并的条数（防刷吞掉的）
 */
internal data class EvoSampleTally(
    val kept: Int,
    val droppedDuplicate: Int,
    val droppedBurst: Int,
    val firstMs: Long,
    val lastMs: Long,
) {
    init {
        require(kept >= 0 && droppedDuplicate >= 0 && droppedBurst >= 0) { "清点数不许为负" }
    }

    /** 窗口天数＝首末事件跨度（整天下取）。0 条 ⇒ 0 天。 */
    val days: Int get() = if (kept <= 1) 0 else ((lastMs - firstMs) / EvoSampleRules.DAY_MS).toInt()
}

/**
 * 一个域的样本读数（`evo_samples` 那一行的内容）。
 *
 * 四个数都要能一眼核对：次数、天数、去重吞了几条、结论。只给结论不给数＝没人验得动。
 */
internal data class EvoSampleReading(
    val domain: String,
    val events: Int,
    val days: Int,
    val droppedDuplicate: Int,
    val droppedBurst: Int,
    val verdict: EvoSampleVerdict,
) {
    /** 够不够转正（判据③的"强行转正"就撞这一句）。 */
    fun promotable(): Boolean = verdict == EvoSampleVerdict.PROMOTE_READY

    /** 读数的一行（`evo_samples|<域>|<次数>|<天数>|<结论>`；稳定键，别随手改格式）。 */
    fun line(): String = "$READING_KEY|$domain|$events 次|$days 天|${verdict.cn}"

    /** 人读的一句（报告与日志用；与 [line] 同源，不另算一遍）。 */
    fun describe(): String = "$domain：$events 次独立事件／$days 天窗口 ⇒ ${verdict.cn}" +
        (if (droppedDuplicate + droppedBurst == 0) "" else "（去重吞 $droppedDuplicate 条、防刷合并 $droppedBurst 条）")

    companion object {
        /** 读数键（派工单 ④ 点名的 `evo_samples`）。 */
        const val READING_KEY: String = "evo_samples"

        /** 一个域一条都没有时的读数（不是错误，是"还没跑过"）。 */
        fun empty(domain: String): EvoSampleReading =
            EvoSampleReading(domain, 0, 0, 0, 0, EvoSampleVerdict.INSUFFICIENT)
    }
}

/** 样本口径本体（纯函数；IO 在 `EvoSamplePool`，装配在 `EvoSampleWiring`）。 */
internal object EvoSampleRules {

    /** 转正要的独立事件条数（派工单 ② 写死）。 */
    const val PROMOTE_EVENTS: Int = 20

    /** 转正要的窗口天数（派工单 ② 写死）。 */
    const val PROMOTE_DAYS: Int = 7

    /** 灰度要的独立事件条数（派工单 ② 写死）。 */
    const val CANARY_EVENTS: Int = 8

    /** 「连续同类动作」的连续性上限：间隔不超过它才算一次连点（超过就是两次独立选择）。 */
    const val BURST_GAP_MS: Long = 10L * 60_000L

    /** 一天（读数与窗口共用一把尺）。 */
    const val DAY_MS: Long = 86_400_000L

    /** 行格式版本（池文件的一行；改它＝老行读不出来）。 */
    const val SCHEMA: String = "zd-evo/1"

    /** 池目录名：`<工作区>/.zd-evo/`（点号开头 ⇒ 不计入「产物 N 件」，与 `.zd-ledger.jsonl` 同口径）。 */
    const val DIR_NAME: String = ".zd-evo"

    /**
     * 去重：同会话同一事件只算一次 → 再按动作类合并同一用户的连续同类动作。
     *
     * 排序先按时刻、再按 (会话, 事件) —— 同一毫秒的两条也要有确定次序，
     * 否则"重放两遍结果一样"就成了碰运气（判据④的一半在这句里）。
     */
    fun fold(events: List<EvoSampleEvent>): EvoSampleTally {
        if (events.isEmpty()) return EvoSampleTally(0, 0, 0, 0L, 0L)
        val sorted = events.sortedWith(compareBy({ it.atMs }, { it.sessionId }, { it.eventId }))
        val seen = HashSet<String>()
        val kept = ArrayList<EvoSampleEvent>(sorted.size)
        var duplicate = 0
        var burst = 0
        sorted.forEach { e ->
            if (!seen.add(e.sessionId.toString() + "#" + e.eventId)) {
                duplicate++
                return@forEach
            }
            if (isBurstOf(kept.lastOrNull(), e)) {
                burst++
                return@forEach
            }
            kept += e
        }
        return EvoSampleTally(
            kept = kept.size,
            droppedDuplicate = duplicate,
            droppedBurst = burst,
            firstMs = kept.first().atMs,
            lastMs = kept.last().atMs,
        )
    }

    /** 是不是同一个人的一次连点（同会话＋同类＋间隔在 [BURST_GAP_MS] 内）。 */
    private fun isBurstOf(previous: EvoSampleEvent?, current: EvoSampleEvent): Boolean =
        previous != null &&
            previous.sessionId == current.sessionId &&
            previous.kind == current.kind &&
            current.atMs - previous.atMs <= BURST_GAP_MS

    /** 清点 ⇒ 三态（三条线在这里落地，别处不许再判一遍）。 */
    fun verdictOf(tally: EvoSampleTally): EvoSampleVerdict = when {
        tally.kept >= PROMOTE_EVENTS && tally.days >= PROMOTE_DAYS -> EvoSampleVerdict.PROMOTE_READY
        tally.kept >= CANARY_EVENTS -> EvoSampleVerdict.CANARY_ONLY
        else -> EvoSampleVerdict.INSUFFICIENT
    }

    /** 一批事件 ⇒ 一个域的读数（线上唯一入口；调用方拿到的就是 `evo_samples` 那一行）。 */
    fun reading(domain: String, events: List<EvoSampleEvent>): EvoSampleReading {
        val tally = fold(events)
        return EvoSampleReading(
            domain = domain,
            events = tally.kept,
            days = tally.days,
            droppedDuplicate = tally.droppedDuplicate,
            droppedBurst = tally.droppedBurst,
            verdict = verdictOf(tally),
        )
    }
}
