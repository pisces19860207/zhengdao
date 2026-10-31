// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.baopu.inspect

/**
 * 336 · 意见生命周期（纯函数状态机；零 IO、零时钟、零 Android）。
 *
 * 根因（282 D 组实测，runs-282.jsonl）：意见只有「产出」没有「下场」——同一事件的重试提交
 * 每次产出一条新意见（幂等键是 callId+lawRef，重试＝新 callId），右相 C5 每次都因同一条
 * **已被拦下**的 L9 意见打回，2 次打回上限打满，纯烧轮次（8 次打回里 4 次是这种死循环）。
 *
 * 修法分两层（336 口径）：
 * - **评测侧（立刻）**：按 [OpinionMerger.eventKeyOf] 事件键去重＋RESOLVED 不进 C5——
 *   在评测 runner 里接线；
 * - **生产侧（真修）**：本状态机。OPEN→DEFERRED→RESOLVED→SETTLED；DEFERRED 满 3 轮
 *   自动 SETTLED 并出记账行。**接线点在 OpinionWiring（不在 336 租约）**——本文件只交付
 *   机制与判据，生产接线后才能写「已闭合」。
 *
 * 红线：不改 Opinion 形状、不改 OpinionJudge 判定、不碰 RunEngine/JinwuPatrol/ThreeCourts。
 */
internal object OpinionLifecycle {

    /** 相位（闭集；wire 是记账的稳定键，改字面量＝老账读不出）。 */
    enum class Phase(val wire: String) {
        OPEN("open"),           // 已产出，未处置
        DEFERRED("deferred"),   // 被预算压过（队列里），等窗口
        RESOLVED("resolved"),   // 已处置（御案拍板/模拟用户应答）——不再拦验收、不再弹卡
        SETTLED("settled"),     // 结案（defer 满 3 轮自动结案，或将来其它结案规则）
    }

    /**
     * 驱动事件（由调用方从 OpinionDecision 映射；**本文件不 import agent 包**——
     * inspect 不许反向依赖调解器，将来 :policy 抽模块时这条方向是硬边界）。
     */
    enum class Event(val wire: String) {
        POPPED("popped"),           // 弹卡了（等处置；仍是 OPEN）
        DEFERRED("deferred"),       // 预算压队列（每轮 ≤1／每日 ≤3 没排上）
        DISPOSITIONED("resolved"),  // 御案处置完成（拍板/应答都算）
        SKIPPED("skipped"),         // 记档不打扰（不改相——SKIP 不是处置）
    }

    /** DEFERRED 连续满这么多轮 ⇒ 自动 SETTLED（336 口径写死；改数=改口径）。 */
    const val DEFER_AUTO_SETTLE_ROUNDS = 3

    data class State(
        val phase: Phase = Phase.OPEN,
        val deferRounds: Int = 0,
        /** SETTLED 时的结案原因（人话，进记账行；非 SETTLED 为空）。 */
        val settleWhy: String = "",
    ) {
        val wire: String get() = phase.wire
    }

    fun initial(): State = State()

    /**
     * 迁移。RESOLVED/SETTLED 是**终态**：后续事件不改相（已处置的意见不会因为重试
     * 又变回待处置——那是死循环的另一种回来方式）。
     */
    fun advance(state: State, event: Event): State {
        if (state.phase == Phase.RESOLVED || state.phase == Phase.SETTLED) return state
        return when (event) {
            Event.POPPED -> state.copy(phase = Phase.OPEN, deferRounds = 0)
            Event.DISPOSITIONED -> State(phase = Phase.RESOLVED, deferRounds = 0)
            Event.DEFERRED -> {
                val rounds = state.deferRounds + 1
                if (rounds >= DEFER_AUTO_SETTLE_ROUNDS) {
                    State(phase = Phase.SETTLED, deferRounds = rounds, settleWhy = autoSettleWhy())
                } else {
                    state.copy(phase = Phase.DEFERRED, deferRounds = rounds)
                }
            }
            Event.SKIPPED -> state
        }
    }

    /** 自动结案原因（记账行用；grep 键，格式是判据）。 */
    fun autoSettleWhy(): String = "defer 满 $DEFER_AUTO_SETTLE_ROUNDS 轮自动结案"

    /** 该相位的意见是否还拦验收（右相 C5 的口径：已处置/已结案的不拦）。 */
    fun blocksAcceptance(state: State): Boolean =
        state.phase == Phase.OPEN || state.phase == Phase.DEFERRED

    /** DEFERRED 自动结案的记账行（调用方落账；本文件不碰盘）。 */
    data class Settlement(val eventKey: String, val why: String, val deferRounds: Int)

    fun autoSettlement(eventKey: String, state: State): Settlement? =
        if (state.phase == Phase.SETTLED && state.settleWhy == autoSettleWhy()) {
            Settlement(eventKey = eventKey, why = state.settleWhy, deferRounds = state.deferRounds)
        } else {
            null
        }
}
