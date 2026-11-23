// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 因果链骨架：确定性回放。
//
// 回放规则只有三条，写死在这里，别处不再解释：
//   ① **立即项先落地**（秩 -1），再按 825 跨度由短到长落地滞后项；同秩同刻度按日志次序。
//      为什么让「跨度」压过「刻度」：跨度是这条后果**自己声明的到期时间**，
//      刻度只是场景里的先后。一条「过夜才退」的滞后项，不该比后一条事件的立即项更早生效。
//   ② **同一维度后来的写入覆盖先前的**（后写者赢），并记下最后一条写入的事件 id 作溯源。
//   ③ **增量写入没有基线就判红并跳过**——绝不用 0 顶上（顶上等于替他宣布「本来完好」）。
//
// 于是「同输入同输出」不需要额外机制：没有随机、没有时钟、没有浮点，排序是全序的。
// 「回放到任意事件」＝取日志前 k 条（按 [CausalLog.ordered] 的全序），其余规则一模一样。
package com.example.zhengdao.baopu.state.causal

/** 一条还没到期的滞后写入：渲染时列出来，免得读者以为「已经没事了」。 */
internal data class PendingWrite(val dim: CausalDim, val span: Span825, val eventId: String)

/**
 * 一次回放的投影结果。
 *
 * [levels] 只装**被写过**的维度；没被写过的维度不在表里（渲染写「未给」），
 * 这样「他没出汗」和「没人算过汗」在输出上长得不一样——825 §二 的「缺项要显式」。
 */
internal data class CausalState(
    val elapsed: Span825,
    val site: SiteRef,
    val levels: Map<CausalDim, Int>,
    val origin: Map<CausalDim, String>,
    val pending: List<PendingWrite>,
    val issues: List<CausalIssue>,
) {
    fun level(dim: CausalDim): Int? = levels[dim]

    /** 这一维度在本次回放窗口里是否已「结算完」（没有未到期的滞后项）。825 §三② 用它判「假恢复」。 */
    fun settled(dim: CausalDim): Boolean = pending.none { it.dim == dim }

    /** 判定一次「已恢复」的声称：还有未到期的滞后项就红。 */
    fun recoveryClaimIssue(dim: CausalDim): CausalIssue? =
        if (settled(dim)) {
            null
        } else {
            val p = pending.first { it.dim == dim }
            CausalIssue(
                "causal.early-recovery",
                "${dim.label} 在 ${elapsed.label} 就声称已恢复，但 ${p.eventId} 的滞后项要到 ${p.span.label}",
                p.eventId,
            )
        }
}

/** 回放入口。构造即审计；`at` / `toEvent` 都是纯函数。 */
internal class CausalReplay private constructor(val log: CausalLog, val issues: List<CausalIssue>) {

    /** 回放到「已经过去 [elapsed]」这一刻的投影。 */
    fun at(elapsed: Span825): CausalState = run(log.ordered(), elapsed)

    /**
     * 回放到指定事件为止（含该事件的立即项与**已到期**的滞后项）。
     * 默认按最短跨度（「时间几乎没走」）——回放到某一步，问的是「那一刻是什么样」，
     * 不是「全部后果都落地之后是什么样」；要看后者请显式传 `Span825.CROSS_DAY`。
     */
    fun toEvent(eventId: String, elapsed: Span825 = Span825.MINUTES): CausalState {
        val upto = log.indexOf(eventId)
        require(upto >= 0) { "日志里没有事件 $eventId" }
        val head = log.ordered().filter { log.indexOf(it.eventId) <= upto }
        return run(head, elapsed)
    }

    private fun run(events: List<CausalEvent>, elapsed: Span825): CausalState {
        val steps = steps(events, elapsed).sortedWith(
            compareBy({ it.rank }, { it.at }, { it.order }, { it.index }),
        )
        val levels = LinkedHashMap<CausalDim, Int>()
        val origin = HashMap<CausalDim, String>()
        val dyn = ArrayList<CausalIssue>()
        steps.forEach { s -> apply(s, levels, origin, dyn) }
        val pending = events.flatMap { e ->
            e.delayed.filter { !it.span.reachedBy(elapsed) }
                .map { PendingWrite(it.write.dim, it.span, e.eventId) }
        }
        return CausalState(
            elapsed = elapsed,
            site = events.lastOrNull()?.site ?: SiteRef.Unnamed,
            levels = sortedLevels(levels),
            origin = origin.toMap(),
            pending = pending,
            issues = dyn,
        )
    }

    private fun apply(
        s: Step,
        levels: MutableMap<CausalDim, Int>,
        origin: MutableMap<CausalDim, String>,
        dyn: MutableList<CausalIssue>,
    ) {
        val next = s.write.appliedTo(levels[s.write.dim])
        if (next == null) {
            dyn.add(
                CausalIssue(
                    CausalChecks.DELTA_WITHOUT_BASE,
                    "${s.write.dim.label} 用增量写法但此前没有基线（不许默认 0）",
                    s.from,
                ),
            )
            return
        }
        levels[s.write.dim] = next
        origin[s.write.dim] = s.from
    }

    /** 一条待落地的写入；[rank] -1＝立即项。 */
    private data class Step(
        val rank: Int,
        val at: Int,
        val order: Int,
        val index: Int,
        val from: String,
        val write: GradeWrite,
    )

    private fun steps(events: List<CausalEvent>, elapsed: Span825): List<Step> {
        val out = ArrayList<Step>()
        events.forEachIndexed { order, e ->
            e.immediate.forEachIndexed { i, w -> out.add(Step(-1, e.at, order, i, e.eventId, w)) }
            e.delayed.forEachIndexed { i, d ->
                if (d.span.reachedBy(elapsed)) out.add(Step(d.span.rank, e.at, order, i, e.eventId, d.write))
            }
        }
        return out
    }

    /** 按 [CausalDim] 的固定次序收表：渲染与断言都不依赖插入顺序。 */
    private fun sortedLevels(raw: Map<CausalDim, Int>): Map<CausalDim, Int> {
        val out = LinkedHashMap<CausalDim, Int>()
        CausalDim.values().forEach { d -> raw[d]?.let { out[d] = it } }
        return out
    }

    companion object {
        /** 构造即静态审计；返回的 [issues] 是「字段层面」的问题，回放期的增量问题在 [CausalState.issues]。 */
        fun of(log: CausalLog): CausalReplay = CausalReplay(log, CausalChecks.audit(log))

        fun of(vararg events: CausalEvent): CausalReplay = of(CausalLog(events.toList()))
    }
}
