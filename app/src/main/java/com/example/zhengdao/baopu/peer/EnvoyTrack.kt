// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十九批 · 使臣团的**五条流状态机**：把一次委派的生命周期钉在 A2A 状态机上
// （submitted / working / input-required / completed / canceled——派工单 549 点名的五条）。
//
// 为什么要一台小状态机而不是散着记：548 的客户端已经把状态闭集收好了（[A2aTaskState]，
// 终态四格 ＋「停下来要人」两格），本机只欠一件事——**迁移是留痕的、非法迁移是认得出的**。
// 委派不是"调一次等返回值"：submitted→working→input-required→（回一条话）→working→completed
// 是一条链，判据要的正是这条链上每一步都断言得出（549 判据①「状态迁移断言，不只看返回值」）。
//
// 两条纪律：
// · **账是事实记录不是裁判**：非法迁移照样落进 trail（真相不许抹），但 [EnvoyTrack.observe]
//   返回 false——执行层收到 false 必须停手收场（对端说的不是一路话，归 protocol）；
// · **终态粘住**：completed/failed/canceled/rejected 之后再观察到别的状态，就是"终态被翻案"
//   ——只许同态幂等重读（取消后再查仍是 canceled），翻案即非法。
package com.example.zhengdao.baopu.peer

/** 一次状态观察经由的动作（委派语义：发活/轮询/回话续任务/取消）。 */
internal enum class EnvoyAction(val wire: String) {
    SEND("send"),
    POLL("poll"),
    REPLY("reply"),
    CANCEL("cancel"),
    ;

    companion object {
        fun ofWire(wire: String): EnvoyAction? = entries.firstOrNull { it.wire == wire }
    }
}

/** trail 上的一步：从 [from]（null＝第一步）观察到 [to]，经由 [action]，时刻 [atMs]。 */
internal data class EnvoyStep(
    val from: A2aTaskState?,
    val to: A2aTaskState,
    val action: EnvoyAction,
    val atMs: Long,
) {
    fun wire(): String = "${from?.wire ?: "∅"}→${to.wire}(${action.wire})"
}

/**
 * 五条流的**迁移法条**（纯函数；对齐 A2A v1.0 的任务状态机）。
 *
 * 五条流各自的合法链（判据①的夹具逐条打在这上面）：
 *   流 submitted：∅→submitted（发活收条）；
 *   流 working：submitted→working（开干）；working→working（还在干，轮询不算跳变）；
 *   流 input-required：working→input-required（停下来要人）；input-required→working（回话续任务——判据④的回复回路）；
 *   流 completed：→completed（终态收产物；submitted 直达＝对端一口气答完，spec 允许）；
 *   流 canceled：→canceled（终态；取消后再查仍 canceled＝同态幂等，合法）。
 *   auth-required 同 input-required 对待（A2aTaskState.needsHuman 的另一半）；failed/rejected 同终态。
 */
internal object EnvoyLaw {

    /**
     * 迁移合法性：null＝第一步（∅→任意都算收条）。
     *
     * 法条收口成三条（语义与逐态枚举等价，但复杂度压在阈内）：
     *   ① 终态粘住——只许同态幂等重读，不许翻案；
     *   ② 退回 submitted 只有"还没开干重读"一种（working/要人态退回收条＝乱跳）；
     *   ③ 其余非终态迁移（开干/要人/续话/终态/原地重读）都是 A2A 状态机的合法走法；
     *   UNKNOWN 不拦（读侧兜底：对端给了认不出的状态，账面原样记）。
     */
    fun legal(from: A2aTaskState?, to: A2aTaskState): Boolean = when {
        from == null -> true
        from.terminal -> from == to
        from == A2aTaskState.UNKNOWN -> true
        to == A2aTaskState.SUBMITTED -> from == A2aTaskState.SUBMITTED
        else -> true
    }
}

/** 一次委派（或一次续话）的迁移轨迹：按观察序追加，账行摘要与夹具断言共用。 */
internal class EnvoyTrack(
    @Volatile var taskId: String,
    private val nowMs: () -> Long = System::currentTimeMillis,
) {

    private val steps = mutableListOf<EnvoyStep>()

    /** 全部已观察的步（含非法迁移——账是事实记录）。 */
    val trail: List<EnvoyStep> get() = steps.toList()

    /** 当前所在状态（null＝一步都没走）。 */
    val current: A2aTaskState? get() = steps.lastOrNull()?.to

    /** 走没走到终态。 */
    val settled: Boolean get() = current?.terminal == true

    /**
     * 记一步。**非法迁移也记录**（返回 false），执行层收到 false 必须停手——
     * 对端把状态机讲乱了（终态翻案等），不许装没看见继续轮询。
     */
    @Synchronized
    fun observe(to: A2aTaskState, action: EnvoyAction): Boolean {
        val from = current
        steps += EnvoyStep(from, to, action, nowMs())
        return EnvoyLaw.legal(from, to)
    }

    /** 账行摘要（链条式，不重复报每步的 from）：`∅→submitted(send)→working(poll)→completed(poll)`。 */
    @Synchronized
    fun wire(): String = when (steps.size) {
        0 -> "（一步未走）"
        else -> "∅→" + steps.joinToString("→") { "${it.to.wire}(${it.action.wire})" }
    }
}
