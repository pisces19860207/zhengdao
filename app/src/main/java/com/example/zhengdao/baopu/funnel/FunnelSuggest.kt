// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十七批（L3 轮）· 能力路由漏斗 **L3 建议注入**：把 {L1 route, L2 结果或 undecided}
// 作为**一句话建议**放进主循环上下文，并出一行读数（与 L1/L2 同一张 RunLog 表）。
//
// 本轮工单三条硬边界落在本文件的位置：
//   ① 建议只是一句话：≤1 行、≤[MAX_LINE_CHARS] 字、不给 schema、不列工具名、不说"你必须这么做"
//      （措辞恒为「仅供参考，工具仍由你自选」；超 60 字 ⇒ fail-closed，宁可不注入也不塞半句）；
//   ② 不改 L3 判定权：建议只进 extraNotes（提示一行），**不进 tools 链**——tools 数组仍走
//      注册表那条唯一可见性链（源级门禁 FunnelL3GateTest G2 把那一段钉零漏斗字样；
//      L1/L2 两闸的 G2 由本轮按判据②细化到同一段，"建议进上下文"正是本轮工单的明文要求）；
//   ③ 多意图保原文：本文件**不碰用户文本**——build 只吃 route/group/state 三个值，
//      主循环的本轮输入由引擎原样携带，建议层想砍掉"被识别出的那半句"都没有入口（判据③的构造性保证）。
//
// 信箱（publish/consume）进程内，与 L1 的会话去向同一口径：不落盘、重启从空开始。
// publish 由 L2 壳四出口各调一次（L2 判定逻辑一个字没动，只是顺路落一份建议）；
// consume 由引擎在本轮第一步调——最多等 [CONSUME_WAIT_MS] 让 L2 那一枪落账（壳侧预算 8s），
// 等不到就放弃（建议是加分项不是拦路项）；代际号让"移除只有一个消费者成功"，不重复消费。
// 零新依赖：java.util.concurrent 是现成的（L1 壳的 ConcurrentHashMap 同家）。
package com.example.zhengdao.baopu.funnel

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** L3 消费到的建议（读数三字段；[line] null ⇒ 本轮不注入上下文）。 */
internal data class FunnelSuggestion(
    /** 进主循环上下文的那一句话（≤[FunnelSuggest.MAX_LINE_CHARS] 字；超长 fail-closed＝null）。 */
    val line: String?,
    /** 建议组名（读数「建议组=」字段）；无建议＝[FunnelSuggest.NO_GROUP]。 */
    val groupLabel: String,
    /** L2 状态（读数「L2=」字段）：跳过(L1命中)／undecided／回退L1／命中组；没信箱＝无。 */
    val l2State: String,
)

/** L3 建议的纯判据＋进程内信箱（零 IO、零模型、零装配写口）。 */
internal object FunnelSuggest {

    /** 读数行前缀（与「抱朴漏斗L1：」／「抱朴漏斗L2：」同一张 RunLog 表）。 */
    const val LOG_PREFIX = "抱朴漏斗L3："

    /** 一句话建议的硬上限（工单① ≤60 字；超了不注入——判据①的断言就钉在这）。 */
    const val MAX_LINE_CHARS = 60

    /** 引擎首步等建议落账的时间预算（L2 一枪最多 8s——超过 2s 就不等，本轮不注入）。 */
    const val CONSUME_WAIT_MS = 2_000L

    /** 等待轮的轮询步长（信箱是内存写，25ms 足够勤；单测走假时钟不真睡）。 */
    const val POLL_INTERVAL_MS = 25L

    /** 没有建议时，读数的组/状态字段都读「无」。 */
    const val NO_GROUP = "无"

    /** L2 状态闭集（L2 壳四出口传进来；读数的「L2=」字段只许这四个＋NO_GROUP）。 */
    const val STATE_SKIP_L1 = "跳过(L1命中)"
    const val STATE_UNDECIDED = "undecided"
    const val STATE_FALLBACK = "回退L1"
    const val STATE_HIT = "命中组"

    /** 一格信箱（gen＝代际号：remove(key,value) 只对第一个消费者成功 ⇒ 不重复消费）。 */
    private data class Pending(val suggestion: FunnelSuggestion, val gen: Long, val atMs: Long)

    private val pending = ConcurrentHashMap<String, Pending>()
    private val genSeq = AtomicLong()

    /** 测试缝：假时钟与假睡（等待循环不许逼单测真睡）。 */
    internal var nowMs: () -> Long = { System.currentTimeMillis() }
    internal var pause: (Long) -> Unit = { ms -> Thread.sleep(ms) }

    /**
     * 纯出建议（判据①⑤的本体；无时钟无 IO ⇒ 同输入必同输出）。
     * **L1 优先**：L1 命中（含确定性执行）⇒ 用 L1 的 route；L1 未命中且 L2「命中组」⇒ 用 L2 的组；
     * L2 undecided／回退／跳过 ⇒ **不吃 L2 的组**（判据⑤：L2 undecided 时只用 L1 建议——
     * L1 也没命中就没有建议，line=null，但读数照记 L2=undecided）。
     */
    fun build(l1Route: String?, l2Group: String?, l2State: String): FunnelSuggestion {
        val fromL1 = l1Route != null && l1Route != FunnelL1Rules.ROUTE_MISS
        val group = when {
            fromL1 -> l1Route
            l2State == STATE_HIT && l2Group != null && l2Group != FunnelL2Judge.UNDECIDED -> l2Group
            else -> null
        }
        val line = group?.let { g -> lineOf(g, if (fromL1) "L1" else "L2") }
        return FunnelSuggestion(
            line = line,
            groupLabel = group ?: NO_GROUP,
            l2State = l2State,
        )
    }

    /** 一句话措辞（工单①：一行、无 schema、无"必须"；超 60 字 fail-closed 返回 null）。 */
    private fun lineOf(group: String, who: String): String? {
        val text = if (group == FunnelL1Rules.ROUTE_DETERMINISTIC) {
            "【能力路由建议】L1 命中可回放脚本（确定性执行）——仅供参考，工具仍由你自选"
        } else {
            "【能力路由建议】${who} 倾向组「${group}」——仅供参考，工具仍由你自选"
        }
        return if (text.length > MAX_LINE_CHARS) null else text
    }

    /** L2 壳四出口顺路落一份建议（覆盖即可：信箱只留最新一代）。 */
    fun publish(sessionId: String, l1Route: String, l2Group: String?, l2State: String) {
        pending[sessionId] = Pending(build(l1Route, l2Group, l2State), genSeq.incrementAndGet(), nowMs())
    }

    /**
     * 引擎在本轮第一步消费一次：最多等 [waitMs] 让建议落账。
     * 比 [waitMs] 还老的信箱格是上一轮的残值 ⇒ 丢掉继续等本轮的；
     * [stop] 真 ⇒ 立刻放弃等待（用户叫停，不让他等建议）。
     * @return null＝本轮没有建议（没落账／超时／残值后仍没来）——什么都不注入，行为与从前逐字一致。
     */
    fun consumeForTurn(sessionId: String, waitMs: Long = CONSUME_WAIT_MS, stop: () -> Boolean = { false }): FunnelSuggestion? {
        val deadline = nowMs() + waitMs
        while (true) {
            val p = pending[sessionId]
            if (p != null) {
                if (nowMs() - p.atMs > waitMs) {
                    pending.remove(sessionId, p)
                    continue
                }
                return if (pending.remove(sessionId, p)) p.suggestion else null
            }
            val left = deadline - nowMs()
            if (left <= 0L || stop()) return null
            pause(minOf(POLL_INTERVAL_MS, left))
        }
    }

    /**
     * 读数行（判据④的本体：五字段齐；纯函数，同输入两次同一行）。
     * 字段序固定：建议组 → 实际装载工具数 → 注入段数 → L2 → 耗时ms。
     */
    fun readingLineOf(
        sessionId: String,
        suggestion: FunnelSuggestion?,
        toolCount: Int,
        segments: Int,
        costMs: Long,
    ): String {
        val group = suggestion?.groupLabel ?: NO_GROUP
        val l2 = suggestion?.l2State ?: NO_GROUP
        return "${LOG_PREFIX}会话=$sessionId 建议组=$group 实际装载工具数=$toolCount 注入段数=$segments L2=$l2 耗时ms=$costMs"
    }

    /** 清一格信箱（单测用；生产没有清除口——进程没了自然没了，同 L1 会话去向口径）。 */
    internal fun clearForTest(sessionId: String) {
        pending.remove(sessionId)
    }
}
