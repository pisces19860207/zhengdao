// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-27-第二百四十二批-计划即契约 §1 C 段（偏离卡三选一）＋ §1 D 段
// （勘验对账 ⇒ finding 写 208）＋ §4 判据 3/4/5 ＋ §5 负例②③⑦。
//
// 分工：判据在 `PlanContract.kt`（纯函数，零 IO 零时钟），**这一层是装配与账**——
// 卡面文案、活动契约板（本次运行到底有没有契约、已经动过哪些手、问过几次人）、
// 以及 208 的记账。这三件事都必须碰时钟/落盘/界面，所以不能塞进纯判据层。
//
// 两条不可让的口径（写在代码结构里，不只写在注释里）：
// 1. **偏离卡不替风险确认卡说话**（负例②）：用户点「扩计划」只是往白名单里加一项，
//    引擎随后**重跑一遍完整管线**（不是走 `executeApproved`），于是 R2/R3 的确认卡
//    照旧弹——批准计划从来不构成本次动作的授权。
// 2. **没点／超时按「取消」**（fail-closed）：契约外的动作默认不执行。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.agent.AskUserOption
import com.example.zhengdao.baopu.agent.AskUserRequest
import com.example.zhengdao.core.RunLog

/** 偏离卡三个选项之后的走法（派工单 C 段②：人定「扩计划／取消／换做法」）。 */
internal enum class ContractDeviationAction {
    /** 扩计划：把这个目标加进本次契约的白名单，动作**照走完整管线**（该问的还会问）。 */
    EXTEND_CONTRACT,

    /** 取消：这次动作不做。 */
    CANCEL_ACTION,

    /** 换做法：这次动作不做，让它给一条在计划里的路。 */
    CHANGE_WAY,
}

/**
 * 偏离卡的判据与文案（纯函数，JVM 直测）。
 *
 * 选项 id 是稳定契约（同 [com.example.zhengdao.baopu.agent.PlanGuardRules] 的纪律）：
 * UI 只回传 id，引擎只按 id 走，改文案不动 id。
 */
internal object ContractDeviationRules {

    const val OPT_EXTEND = "plan-contract-extend"
    const val OPT_CANCEL = "plan-contract-cancel"
    const val OPT_CHANGE = "plan-contract-change"

    /**
     * 点了哪个 ⇒ 怎么走。
     *
     * **默认是取消**：没点、超时、传了个没认识的 id，一律按「这次不做」——
     * 契约外动作没有"沉默即同意"这条路（负例③「超出 touches 继续跑」的最后一道）。
     */
    fun actionFor(pickedId: String?): ContractDeviationAction = when (pickedId) {
        OPT_EXTEND -> ContractDeviationAction.EXTEND_CONTRACT
        OPT_CHANGE -> ContractDeviationAction.CHANGE_WAY
        else -> ContractDeviationAction.CANCEL_ACTION
    }

    /** 一句话：实际要做什么 vs 计划里没有（卡面问题行；判据 3 的"立刻停"就靠它说清停在哪）。 */
    fun whyText(check: ContractCheck): String = when (check.breach) {
        ContractBreach.NONE -> "这次动作在计划里"
        // 白名单是计划卡上那一串目标声明；按组件比，兄弟目录骗不过（见 PlanContract.kt）
        ContractBreach.OUT_OF_TOUCHES -> "它要动的「${check.faceBrief}」不在计划的白名单里"
        ContractBreach.WRITE_IN_READONLY -> "它要改的「${check.faceBrief}」在计划的只读区里（可以读，不可以写）"
        ContractBreach.RISK_OVER_ESTIMATE ->
            "这一步计划里预估 ${check.ceiling}，实际要动到 ${check.actual}（比说好的重）"
        ContractBreach.BEYOND_STEPS -> "计划里的步骤已经全部勾完，它还要改盘"
    }

    /** 卡面第一行（问题）。 */
    fun question(check: ContractCheck, toolName: String): String = buildString {
        append("计划外动作：$toolName——")
        append(whyText(check))
        append("。")
        val intent = check.stepIntent
        if (intent.isNotBlank()) {
            append("当前这一步按计划是做「$intent」。")
        }
        append("要它怎么做？")
    }

    /** 「扩计划」会把哪些目标加进白名单（取消／换做法时为空——不加就是不加）。 */
    fun extendSpecs(check: ContractCheck): List<String> =
        check.uncovered.map { it.value }.filter { it.isNotBlank() }.distinct()

    /**
     * 「扩计划」这一栏在这条违约上**成不成立**。
     *
     * 只有两种违约能靠扩计划解决：目标没写进白名单（加进去）、这一步预估偏保守
     * （把这一格抬到实际那一档——**她亲手点的**，不是模型自己抬的）。
     * 另外两种不成立，而且卡上不许摆一个做不到的按钮：
     * - 往只读区写：只读区是声明里的红线，加白名单也洗不掉（[PlanContractRules.check]
     *   第一条独立成立就是为这个）；要改必须重出计划、由用户批准；
     * - 计划已打完勾还要改盘：步骤维度上没有能装下它的格子，该走的是
     *   `update_plan` 加一步（C 段③「只报不改」），不是这张卡代她加。
     */
    fun extendable(check: ContractCheck): Boolean = when (check.breach) {
        ContractBreach.OUT_OF_TOUCHES, ContractBreach.RISK_OVER_ESTIMATE -> true
        else -> false
    }

    /**
     * 点了「扩计划」但这张卡扩不动时的回灌（C 段③：**只报不改**）。
     *
     * 与 [PlanContractRules.newStepNotice] 同一口径——引擎绝不代模型往计划里写字。
     */
    fun notExtendableText(check: ContractCheck, toolName: String): String = when (check.breach) {
        ContractBreach.WRITE_IN_READONLY ->
            "【这次没做】$toolName 要改的「${check.faceBrief}」在计划的只读区里，" +
                "扩白名单洗不掉这条红线。这一步**没有发生**，别当成已完成：" +
                "要改它，必须重出一版计划（把只读区的声明改掉）并让用户批准。"

        else ->
            "【这次没做】$toolName 落在计划之外（${whyText(check)}）：计划里没有能装下这一步的格子，" +
                "这张卡也不代替用户加步骤。这一步**没有发生**。要它做：先在正文里说明为什么需要、" +
                "再调 update_plan 把这一步加进计划（会走计划变更确认卡），用户同意之后才动盘。"
    }

    /**
     * 扩过计划、重走一遍管线**仍然超限**时的回灌（一次扩计划只重跑一次，不循环弹卡）。
     *
     * 不许静默放行，也不许再问第二遍——同一件事连问两次是骚扰，第三次就是把人逼到随手点。
     */
    fun stillBreachText(check: ContractCheck, toolName: String): String =
        "【这次没做】扩过计划之后，$toolName 仍然超出契约（${whyText(check)}）。" +
            "这次动作**没有发生**，别当成已完成；停下来向用户说明缺什么、为什么，等她重新批准计划。"

    /**
     * 选完之后塞回模型的话（进内存历史，不落库、不进正文）。
     *
     * 三条各说一句实话：
     * - 扩计划：由**用户**加的，动作会执行——所以这里不回灌"没做"；但必须点明
     *   "这不等于你以后都能动它"（一次一张，下一次超了再问）；
     * - 取消／换做法：这一步没做，别把它当成做完了。
     */
    fun feedFor(
        action: ContractDeviationAction,
        check: ContractCheck,
        toolName: String,
    ): String? = when (action) {
        // 执行由引擎重跑管线完成，模型看到的是真实工具结果——这里不再叠一句假话
        ContractDeviationAction.EXTEND_CONTRACT -> null

        ContractDeviationAction.CANCEL_ACTION ->
            "【计划外动作已取消】$toolName 要动的「${check.faceBrief}」不在计划里，用户选择不做。" +
                "这一步**没有发生**，别把它当成已完成。按计划里剩下的步骤继续，" +
                "或者先在正文里说明为什么需要它、等用户同意扩计划再动。"

        ContractDeviationAction.CHANGE_WAY ->
            "【用户要求换做法】$toolName 这条路超出计划（${whyText(check)}），用户没有同意这次动作。" +
                "换一种**在计划白名单与步骤范围内**的做法；如果确实换不出来，直接停下来向用户说明" +
                "需要什么、为什么需要，不要试探性地先动手。"
    }

    /**
     * 「发现契约外必要步骤」的回灌（C 段③：**只报不改**，负例④的靶心）。
     *
     * 引擎只调这一句，绝不代模型写进计划——加步骤必须走 `update_plan` ＋ 变更确认卡。
     */
    fun beyondPlanNotice(intent: String): String = PlanContractRules.newStepNotice(intent)
}

/**
 * 批准卡的判据与文案（242 B 段：计划声明的契约**要她点头才成为本次运行的上限**）。
 *
 * 为什么要在 `update_plan` 执行之前问，而不是执行之后：之后就已经落库、卡面已经画出来了，
 * 「批不批」就变成既成事实。红线①（批准计划不构成 R2/R3 授权）必须写在**卡面上**，
 * 不能只写在代码注释里——她看到的每个字都要能自证这张卡不给改盘授权。
 */
internal object ContractApprovalRules {

    const val OPT_APPROVE = "plan-contract-approve"
    const val OPT_DECLINE = "plan-contract-decline"

    /**
     * 这张卡的回答算不算批准。
     *
     * **只有真点了「批准」才算**：没点／超时／传了个没认识的 id ⇒ 不批准（fail-closed）。
     * 与 [ContractDeviationRules.actionFor] 同一口径——沉默绝不等于同意。
     */
    fun approved(pickedId: String?): Boolean = pickedId == OPT_APPROVE

    /** 卡面正文：目标＋逐步风险＋白名单＋只读区＋预估问人＋停线条件（判据 1 的卡面侧）。 */
    fun body(contract: PlanContract): String = buildString {
        append("目标：${contract.goal}")
        append("\n步骤（每步预估的最高档）：")
        contract.steps.forEach { s ->
            append("\n${s.n}. ${s.intent}——${s.risk}")
            if (s.mayTouch.isNotEmpty()) append("（限 ${s.mayTouch.joinToString("、")}）")
        }
        append("\n允许动到：${contract.touches.joinToString("、")}")
        if (contract.readonly.isNotEmpty()) {
            append("\n只读（可以看、不可以改）：${contract.readonly.joinToString("、")}")
        }
        append("\n预估要问你：${contract.expectedAsks} 次")
        if (contract.stopIf.isNotEmpty()) {
            append("\n碰到就停：${contract.stopIf.joinToString("、")}")
        }
    }

    /** 批准那一栏的点明话：这张卡只装上限，不装授权。 */
    const val APPROVE_DETAIL: String =
        "之后的动作必须落在上面这些步骤与白名单里，超出会立刻停下来问你——" +
            "但改盘、删除、外发这类动作**照旧一次一问**，批准计划不等于授权改盘"

    /** 不批准那一栏：上限不装，但计划照旧显示、该有的确认卡一张不少。 */
    const val DECLINE_DETAIL: String =
        "计划照常列出来，本次不设执行范围上限（不封顶≠可以乱来：每个动作仍按风险档该问就问）"

    /** 卡面第一行（问题＋正文；多行，UI 的 title 会折行显示）。 */
    fun question(contract: PlanContract): String =
        "要把这份计划当本次运行的上限吗？\n" + body(contract)
}

/**
 * 偏离卡的组装（纯函数；文案定版在这里，引擎只负责弹与收）。
 *
 * 房型照 [com.example.zhengdao.baopu.agent.PlanGuardCards]：inline 中文，不走 strings
 * ——非 `@Composable` 位置拿不到 Context，硬搬字符串会让门禁的双语对账多一张表。
 */
internal object PlanContractCards {

    /** 契约批准卡（242 B 段）：批准 ⇒ 这一次 submit 把上限装上；不批 ⇒ 只落库不封顶。 */
    fun approvalRequest(contract: PlanContract): AskUserRequest = AskUserRequest(
        question = ContractApprovalRules.question(contract),
        options = listOf(
            AskUserOption(
                id = ContractApprovalRules.OPT_APPROVE,
                label = "批准，按这个范围走",
                detail = ContractApprovalRules.APPROVE_DETAIL,
                recommended = true,
            ),
            AskUserOption(
                id = ContractApprovalRules.OPT_DECLINE,
                label = "不批准",
                detail = ContractApprovalRules.DECLINE_DETAIL,
            ),
        ),
    )

    /** 契约外动作的三选一卡：扩计划 / 取消 / 换做法（默认不执行）。 */
    fun deviationRequest(check: ContractCheck, toolName: String): AskUserRequest {
        val specs = ContractDeviationRules.extendSpecs(check)
        val extendDetail = if (specs.isEmpty()) {
            "把这一步的预估抬到它实际要动的档，然后照走完整管线（该问的还会问）"
        } else {
            "把「${specs.joinToString("、")}」加进这次计划的白名单，然后照走完整管线（该问的还会问）"
        }
        val cancel = AskUserOption(
            id = ContractDeviationRules.OPT_CANCEL,
            label = "取消这一步",
            detail = "这次动作不做；按计划里剩下的走",
            recommended = true,
        )
        val change = AskUserOption(
            id = ContractDeviationRules.OPT_CHANGE,
            label = "换做法",
            detail = "不做这件事，让它换个在计划范围内的办法（或停下来解释）",
        )
        // 扩不动的违约（只读区／步骤已勾完）不摆「扩计划」——卡上不许有一个做不到的按钮，
        // 摆了就是骗她点；回灌里如实说明该走哪条路（见 notExtendableText）。
        val extend = if (ContractDeviationRules.extendable(check)) {
            listOf(
                AskUserOption(
                    id = ContractDeviationRules.OPT_EXTEND,
                    label = "扩计划",
                    detail = extendDetail,
                ),
            )
        } else {
            emptyList()
        }
        return AskUserRequest(
            question = ContractDeviationRules.question(check, toolName),
            options = listOf(cancel) + extend + listOf(change),
        )
    }
}

/**
 * 一次契约外判定 ＋ 它的卡（管线交给引擎的那只手）。
 *
 * 为什么两个都要带：卡是给**人看**的文案，[ContractCheck] 是给**机器走**的事实——
 * 引擎点完「扩计划」要拿它去 [PlanContractBoard.extendFor]（加哪些目标、抬哪一格），
 * 只回一个 AskUserRequest 就等于让引擎反过来从文案里 parse 事实。
 */
internal data class ContractDeviation(
    val check: ContractCheck,
    val request: AskUserRequest,
)

/**
 * 契约事实的读取口（闸在判上限时只要这三件事；单测注假件，不碰全局态）。
 *
 * 为什么做成接口而不是让 `GuardPipeline` 直接引 `PlanContractBoard`：
 * 管线是执行层，全局单例进它的参数表就成了"偷偷读世界"。假件让契约判定的
 * 五条判据能在管线这一层直接测（负例②③必须打在闸上才证明接线通了）。
 */
internal interface ContractSource {
    /** 本次运行的契约；null ＝ 没有契约 ⇒ 不设上限（今天的执行路径逐字节不变）。 */
    fun activeContract(): PlanContract?

    /** 当前计划条目（定位"现在在做哪一步"）。 */
    fun planItems(): List<PlanItem>

    /** 工作区根绝对路径（相对写法的目标按它解析）。 */
    fun workspaceRoot(): String

    /** 记一次真实发生的动作（跑成了什么样都记，收工时对账用）。 */
    fun noteAction(action: PlanContractRules.ActualAction)
}

/**
 * 活动契约板（本次运行的事实：契约 ＋ 做过的动作 ＋ 问过几次人）。
 *
 * 形状与 `RiskGate`/`PlanTool.sink` 同一房：进程内单例 ＋ `@Volatile`，因为
 * 抱朴全局只有一个活跃运行（单活跃门），不存在两个会话同时写这块板。
 * 一轮结束（[reconcile]）或新一轮开始（[clear]）就收摊，**不落盘**——
 * 落盘的那部分已经进了 `part(kind='plan')` 的 payload 与 208 的账。
 */
internal object PlanContractBoard : ContractSource {

    @Volatile
    private var contract: PlanContract? = null

    @Volatile
    private var items: List<PlanItem> = emptyList()

    @Volatile
    private var root: String = ""

    @Volatile
    private var session: Long = -1L

    /** 这一轮真实发生过的动作（收工时喂给 [reconcile]）。 */
    private val actions = mutableListOf<PlanContractRules.ActualAction>()

    /** 这一轮实际问了几次人（确认卡 ＋ ask_user 都算：D 段第三项的"实际"那一侧）。 */
    @Volatile
    private var asks = 0

    /** 最近一次对账读数（日志/报告回读；新一轮清空）。 */
    @Volatile
    private var lastReport: ReconReport? = null

    /** 这一轮的账已经结过了（[reconcile] 一轮只记一次 208）。 */
    @Volatile
    private var reconciled = false

    /** 「下次同类计划把这条计入估算」的那一句（派工单 D 段末句；进程内有效）。 */
    @Volatile
    private var hint: String? = null

    // ── 接线口（引擎/控制器调） ─────────────────────────────────────────────

    /** 有没有契约（UI 判"要不要画契约行"也用这个：没契约就一行都不加）。 */
    fun hasContract(): Boolean = contract != null

    override fun activeContract(): PlanContract? = contract

    override fun planItems(): List<PlanItem> = items

    override fun workspaceRoot(): String = root

    /**
     * 计划批准 ⇒ 本次运行契约生效（B 段）。
     *
     * 「批准」这个动作发生在引擎侧的确认流里，只有它会把契约推到这里：
     * 模型自己发的计划（没经过卡）不会让上限生效——这是负例②的另一半。
     */
    fun activate(newContract: PlanContract?, planItems: List<PlanItem>, workspaceRoot: String, sessionId: Long) {
        contract = newContract
        items = planItems
        root = workspaceRoot
        session = sessionId
        synchronized(actions) { actions.clear() }
        asks = 0
        lastReport = null
        reconciled = false
        log("抱朴契约：生效（session=$sessionId，步 ${newContract?.steps?.size ?: 0} 条、" +
            "白名单 ${newContract?.touches?.size ?: 0} 项、预估问 ${newContract?.expectedAsks ?: 0} 次）")
    }

    /** 每次 `update_plan` 成功后同步步骤勾选状态（上限跟着"现在在做哪一步"走）。 */
    fun notePlan(planItems: List<PlanItem>) {
        items = planItems
    }

    /** 换轮／收工：契约、动作流水、问人计数一起归零（[lastReport] 与 [hint] 另说）。 */
    fun clear() {
        contract = null
        items = emptyList()
        session = -1L
        synchronized(actions) { actions.clear() }
        asks = 0
        reconciled = false
    }

    /**
     * 一次动作跑没跑成都要记（对账问的是"实际做了什么"）。
     *
     * **同一次调用只记一条**（按 [PlanContractRules.ActualAction.id] 去重）：确认卡批准后
     * 会再走一遍 `prepare`、偏离卡扩完计划也要重跑管线——那是**同一个**动作被判定两次，
     * 不是盘上动了两次。不去重，对账会把"问了一次卡"报成"多干了一次活"。
     */
    override fun noteAction(action: PlanContractRules.ActualAction) {
        if (contract == null) return
        synchronized(actions) {
            if (actions.none { it.id == action.id }) actions += action
        }
    }

    /** 弹了一张要人点的卡（确认卡／求助卡／偏离卡）⇒ 问人次数 ＋1。 */
    fun noteAsk() {
        if (contract == null) return
        asks++
    }

    /** 实际问过几次（回读；日志与单测用）。 */
    fun askCount(): Int = asks

    /**
     * 用户点「扩计划」之后把上限放宽（一次一张卡，扩的是**这一次**动作的范围）。
     *
     * 两条边界，都写进代码而不只是注释：
     * - **只放宽她同意的那一格**：白名单加进这次没覆盖到的目标；风险上限只抬
     *   「当前这一步」的预估（[ContractBreach.RISK_OVER_ESTIMATE] 那一格），别的步骤一格不动；
     * - **扩不动的不许假扩**：只读区／步骤已勾完 ⇒ 返 false，调用方按
     *   [ContractDeviationRules.notExtendableText] 如实收场（见 [extendTouches] 上方的判据）。
     *
     * 返回 true 只表示"契约变宽了一点"，**不表示动作可以跑**：引擎接下来要重走完整管线，
     * R2/R3 的确认卡照旧弹（红线①：批准计划不构成任何改盘授权）。
     */
    fun extendFor(check: ContractCheck): Boolean {
        if (!ContractDeviationRules.extendable(check)) return false
        val c = contract ?: return false
        var widened = false
        val specs = ContractDeviationRules.extendSpecs(check)
        var next = c
        if (specs.isNotEmpty()) {
            next = next.copy(touches = (next.touches + specs).distinct())
            widened = true
        }
        if (check.breach == ContractBreach.RISK_OVER_ESTIMATE) {
            val no = check.stepNo
            val actual = check.actual
            if (no != null && actual != null) {
                val steps = next.steps.map { if (it.n == no) it.copy(risk = actual) else it }
                if (steps != next.steps) {
                    next = next.copy(steps = steps)
                    widened = true
                }
            }
        }
        if (!widened) return false
        contract = next
        log(
            "抱朴契约：用户同意扩计划（${check.breach}）——" +
                "白名单 ${next.touches.size} 项、这一步上限 ${check.ceiling}→${next.risks[check.stepNo] ?: check.ceiling}" +
                "；动作仍按完整管线重走一遍（该问的照问）",
        )
        return true
    }

    /** 这一次动作对照契约的判定（闸在第 ③ 段里调；null 契约 ⇒ 恒不违反）。 */
    fun checkFor(
        faces: List<TouchFace>,
        actual: com.example.zhengdao.baopu.RiskClass?,
        isWrite: Boolean,
    ): ContractCheck = PlanContractRules.check(contract, faces, actual, isWrite, items, root)

    // ── D 段：勘验对账 ⇒ finding 进 208 ─────────────────────────────────────

    /**
     * 收工对账：三项读数 ＋ 逐条不符写 208。
     *
     * @return null ＝ 这一轮没有契约（不编读数、不记账、不打扰——契约缺席时全层惰性）
     *
     * **一轮只结一次**：`settle` 会被好几条路径叫到（正常收尾／熔断／用户停止），
     * 同一份账记两遍就是假账（208 是只追加的，删不掉）。第二次进来直接回最近那份读数。
     */
    fun reconcile(): ReconReport? {
        val c = contract ?: return null
        lastReport?.let { if (reconciled) return it }
        val snapshot = synchronized(actions) { actions.toList() }
        val report = PlanContractRules.reconcile(c, snapshot, asks, root)
        reconciled = true
        lastReport = report
        PlanContractRules.reconBrief(report)?.let { log("抱朴契约：$it") }
        PlanContractRules.estimateHint(report)?.let { hint = it }
        if (!report.isClean) recordFindings(report, c)
        return report
    }

    /** 最近一次对账读数（日志/报告/单测回读）。 */
    fun lastReport(): ReconReport? = lastReport

    /**
     * 「下次同类计划把这条计入估算」的那一句（由 [com.example.zhengdao.baopu.agent.PlanController]
     * 拼进计划提醒，让下一次写计划的模型看见上次真实的问次数/档位）。
     *
     * 只在同进程内有效——跨重启不猜"同类"是哪一次（派工单只要求"下次同类计划"，
     * 而把一次读数落盘当长期估算依据，会把某一次的偶然带进以后每一轮）。
     */
    fun estimateHint(): String? = hint

    /**
     * 每条不符落一行 208（派工单 D 段「出 finding 写 208」逐字照办）。
     *
     * `reason`/`evidence` 都齐（208 纪律①：缺一条整条不落）；`reversible=false`
     * 是**如实降级**——一次已经跑完的动作没有回滚引用可取。
     */
    private fun recordFindings(report: ReconReport, c: PlanContract): List<DecisionRecord?> =
        report.findings.map { f ->
            DecisionLedger.record(
                DecisionDraft(
                    kind = DecisionKind.PLAN_CONTRACT_FINDING,
                    actor = DecisionActor.AUTO,
                    reason = "计划即契约勘验：${f.detail}",
                    evidence = "plan_contract_finding:${f.code.id}:$session",
                    before = "预估：${c.steps.size} 步／上限 ${c.declaredMaxRisk}／问 ${c.expectedAsks} 次",
                    after = "实际：${report.totalActions} 次动作（写 ${report.writeActions}）／" +
                        "上限 ${report.actualMaxRisk ?: "无改盘"}／问 ${report.asks} 次",
                    atMs = System.currentTimeMillis(),
                    sessionId = session,
                    reversible = false,
                ),
            )
        }

    /** 直测收尾（生产不调：这块板是进程态）。 */
    fun resetForTest() {
        clear()
        root = ""
        lastReport = null
        hint = null
    }

    private fun log(line: String) = RunLog.log(line)
}
