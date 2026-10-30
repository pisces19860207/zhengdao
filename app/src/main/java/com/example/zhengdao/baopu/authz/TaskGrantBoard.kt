// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十六批 P1（接线用**内存板**）：把「这次会话批的是哪一张授权、绑的是哪一份三元组、
// 六步最近一次判在哪、这一单的账由谁落」这四件事放在一处，供执行路径与 debug 探针读。
//
// ## 为什么还要一块板——249 不是已经有 TaskGrantRuntime 了吗
// `TaskGrantRuntime` 管的是**授权活体**（装没装、用了几次、什么时候结束）；本板管的是
// **接线现场**那几个只有生产路径才知道的事实：
// · `sessionId` 从哪来——引擎的 `prepare/execute/executeApproved` 三个签名**不许改**
//   （2026-10-29 用户裁定 Q6：照 `PlanContractBoard` 的做法走内存板，不牵动全量测试面）；
// · **L26 的三元组**绑在哪一份上（runtime 里只有快照，没有"批准时看见的那一份参数"）；
// · **N7 那一条**：六步第⑥步已经把这个 `opId` 的账记成 `<opId>:applied` 了，
//   `GuardPipeline.finish()` 那条通用 `auto_act` 就必须**不再补第二行**——这格"谁落账"
//   的事实得有个地方放，否则两笔账会在同一轮里漂着。
//
// ## 三条纪律
// 1. **只在内存**（与 249 §4 同一条硬线）：没有 `save`／`load`／`restore`，进程没了就没了；
//    重启后老授权连装都装不进来（那是 `ProcessEpoch` 的活，本板不越权）。
// 2. **不判放行**：本板一个"能不能做"的结论都不下——判定归 `TaskGrantGate`（六步）
//    与 `ApprovalBindings`（L26）。板子只记事实，免得出现第二个口径。
// 3. **一个进程一张板**（与 `PlanContractBoard` 同房型：`@Volatile` ＋ 单活跃运行）：
//    抱朴全局只有一个活跃会话，不存在两块板互相覆盖。
package com.example.zhengdao.baopu.authz

/**
 * 一张候选授权的开单事实（由 [com.example.zhengdao.baopu.authz.TaskGrantWire] 算好后交给板）。
 *
 * 三件一起放是因为它们**必须同源**：[grant] 是快照、[facts] 是卡面形状、[binding] 是
 * 这份快照绑定的三元组。分开传就会有人只传两件，于是"卡上看见的"与"绑住的"不是一件事
 * ——那正是 L26 要拦的那一格。
 */
internal data class GrantOffer(
    val grant: TaskGrant,
    val facts: TaskGrantFacts,
    val binding: ApprovalBinding,
)

/**
 * 六步最近一次判定的**读数**（交付报告那句「六步真实读数」就是它原样打出来）。
 *
 * [bindingNote] 带的是 L26 那一格的结论：三元组相符／哪一种不符。
 */
internal data class GrantReading(
    val opId: String,
    val outcome: GateOutcome,
    val code: String,
    val steps: List<String>,
    val bindingNote: String,
    val grantId: String,
    val atMonoMs: Long,
) {
    /** 一行日志／探针输出（不带路径、不带正文——L26 的摘要已经替正文站好岗）。 */
    fun line(): String =
        "六步 ${outcome.cn}（$code）· $bindingNote · opId=${opId.take(12)} · 单=${grantId.take(10)} · " +
            steps.joinToString("｜")
}

/**
 * 接线现场（见文件头「为什么还要一块板」）。
 *
 * 公开面只有读与"记事实"：真正装上/结束授权的仍然是 `TaskGrantRuntime`——本板不代它下结论。
 */
internal object TaskGrantBoard {

    @Volatile
    private var offer: GrantOffer? = null

    /** 已装上的那张单的 id（没装＝空串；与 offer 不同——offer 可以挂着不点「开工」）。 */
    @Volatile
    private var openedGrantId: String = ""

    /** 这张单绑定的三元组（L26；没开单＝null）。 */
    @Volatile
    private var binding: ApprovalBinding? = null

    /** 撤销那一格的原因（撤销成功＝空串；被 L26 拦下＝拦下的那句话）。 */
    @Volatile
    private var revokeNote: String = ""

    @Volatile
    private var lastReading: GrantReading? = null

    /**
     * 本进程里**账已由授权链落过**的 opId（N7 判据的载体）。
     *
     * 为什么记了不释放：`finish()` 若在同一个 opId 上跑第二遍（引擎重试／lane 切换），
     * 释放过的那一份会让通用 `auto_act` 再补一行 ⇒ 一动作两账又回来了。
     * 这张表只在 [resetForTest] 与开单换人（[beginOpen]）时清——都是"另一件事"的边界。
     */
    private val accounted: MutableSet<String> = mutableSetOf()

    // ── 候选与开单 ────────────────────────────────────────────────────────

    /** 这一轮有没有可开的单（卡面据此决定画不画下半张；没有＝卡与今天逐字相同）。 */
    fun hasOffer(): Boolean = offer != null

    fun currentOffer(): GrantOffer? = offer

    /** 挂上候选单（由 [TaskGrantWire.buildOffer] 算出；null＝这一轮不生成授权，照旧逐次问）。 */
    fun offerCandidate(newOffer: GrantOffer?) {
        offer = newOffer
    }

    /**
     * 开单成交（点「开工」/debug 探针同一个口）。
     *
     * 只做三件事：记下"这张单绑的是哪一份三元组"、把 opId 账本清场、把候选单收走
     * （装上了就不该再摆一张候选——同一件事不问第二遍）。
     * 真正的装槽与"装不进来"的原因由调用方拿 [TaskGrantRuntime.install] 的结果处理。
     */
    fun beginOpen(opened: GrantOffer, atMonoMs: Long) {
        if (openedGrantId != opened.grant.grantId) synchronized(accounted) { accounted.clear() }
        offer = opened
        openedGrantId = opened.grant.grantId
        binding = opened.binding
        revokeNote = ""
        lastReading = null
        pending = null
    }

    /** 当前绑定的三元组（执行路径拿它比 L26；没开单＝null ⇒ 一律视为未授权）。 */
    fun bindingOfOpened(): ApprovalBinding? = binding

    /** 开单那张快照的 id（没开＝空串）。 */
    fun openedGrantId(): String = openedGrantId

    /** 撤销结果的一句话（卡面/探针回读；空串＝没有撤销这个话题）。 */
    fun revokeBrief(): String = revokeNote

    /** 记一次撤销的结论（成功与拦下都记——拦下也是事实，不许悄悄吞）。 */
    fun noteRevoke(allowed: Boolean, note: String) {
        revokeNote = if (allowed) "" else note
        if (allowed) {
            binding = null
            openedGrantId = ""
            offer = null
            pending = null
            synchronized(accounted) { accounted.clear() }
        }
    }

    /** 收摊（换轮／任务结束）：候选、绑定、账本一起归零。 */
    fun clear() {
        offer = null
        openedGrantId = ""
        binding = null
        revokeNote = ""
        lastReading = null
        pending = null
        synchronized(accounted) { accounted.clear() }
    }

    // ── 六步读数与 N7 那一格 ──────────────────────────────────────────────

    fun reading(): GrantReading? = lastReading

    /** 读数一行（日志与探针共用；没有判过＝null，不编一句"没判过"的话）。 */
    fun readingLine(): String? = lastReading?.line()

    fun noteReading(reading: GrantReading) {
        lastReading = reading
    }

    /**
     * 六步刚放行、还没执行的那**一个**请求（执行成功后 `finish()` 拿它落授权账）。
     *
     * 为什么只放一格、不做表：写工具在调度层是**单飞**的（`ToolScheduling` 那条），
     * 同一进程里不会有两格并行的授权内写入；放成表反而会让"取哪一格"变成第二个口径。
     * 只有 [WiringDisposition.ALLOW] 才写它——第二百七十六批 P1 那会儿生产不可达（第⑤步恒没影子，
     * 这一格是空的）；第二百九十六批起这一格**有了真内容**：影子跑成 CONSISTENT 才免卡，免卡才挂它。
     */
    @Volatile
    private var pending: ActionRequest? = null

    /** 挂上「已放行待执行」的那一格（ALLOW 专用；null＝清场）。 */
    fun setPending(request: ActionRequest?) {
        pending = request
    }

    /** 看这一格是不是这个 opId 的（**不取走**——`finish()` 先问它，再决定这条账归谁落）。 */
    fun pendingFor(opId: String): ActionRequest? =
        pending?.takeIf { opId.isNotEmpty() && it.opId == opId }

    /**
     * 记一次「这一格的账由授权链落」（六步 ALLOW、即将执行时调用）。
     *
     * @return false＝这个 opId 本进程已经记过（同一件事不记第二遍）
     */
    fun markAccounted(opId: String): Boolean =
        opId.isNotBlank() && synchronized(accounted) { accounted.add(opId) }

    /** 这一格的账是不是授权链落的（[com.example.zhengdao.baopu.agent.GuardPipeline] 的通用记账问它）。 */
    fun isAccounted(opId: String): Boolean =
        opId.isNotEmpty() && synchronized(accounted) { accounted.contains(opId) }

    // ── 直测收尾（生产不调；与 TaskGrantRuntime.resetForTest 同款纪律） ──────

    fun resetForTest() {
        clear()
    }
}
