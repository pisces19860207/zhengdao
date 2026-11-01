// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第十七批 §1.1/§1.2（A 段报计划 ＋ B 段落库）＋ §3（J1–J4 全压本内核）。
// 房型照 CompressionRunner：**内核零 Android**（store 是接口），引擎壳只接线
// （sessionId ＋ 轮次事件）；JVM 单测注假 store 就能把判据全部跑绿。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.PlanStore
import com.example.zhengdao.baopu.plan.PlanContract
import com.example.zhengdao.baopu.plan.PlanContractBoard
import com.example.zhengdao.baopu.plan.PlanContractRules
import com.example.zhengdao.baopu.plan.PlanPledgeBoard
import com.example.zhengdao.baopu.plan.PlanPledgeCards
import com.example.zhengdao.baopu.plan.PlanPledgeRules
import com.example.zhengdao.baopu.plan.PlanPledgeRules.PledgeParse
import com.example.zhengdao.baopu.plan.PlanPledgeRules.PledgeSnapshot
import org.json.JSONArray
import org.json.JSONObject
import com.example.zhengdao.core.RunLog

/**
 * update_plan 的内核（第十七批）：**校验归一化 ⇒ sticky done ⇒ 落库 ⇒ 回灌**。
 *
 * 三条判据的落地处（派工单 §3）：
 * - J1：参数校验（空 items 拒绝；超 8 条 / 超 60 字截断并 notice）——在
 *   [PlanRules.parseItems]（纯函数，本类只转发）；
 * - J2：全量覆盖——[PlanRules.applyOverwrite] 的 incoming 就是整张表；
 * - J3：勾选只增不减——同上（sticky done）；
 * - J4：落库可回读——[PlanStore.insert] / [PlanStore.latest]（实现侧 SQL 见
 *   `BaopuSql.planPayloadsBySession`）。
 *
 * 线程：submit 跑在 `zd-baopu-tool`（GuardPipeline 的 ToolRunner 里）；
 * newRound/seedFromStore 跑在 `zd-baopu-run`（引擎循环）。两者不并发（工具执行
 * 是引擎循环顺序调的，单活跃门全局只有一个会话）⇒ current 一个 [Volatile] 够用。
 *
 * @param sessionId 当前轮会话（引擎喂；落库要知道落到哪）
 * @param store 计划存取（接口；Android 侧实现 SessionPlanStore，单测用假件）
 * @param workspaceRoot 工作区根绝对路径（第二百四十二批：契约里相对写法的目标要按它解析）；
 *   缺省空串 ⇒ 目标按原样比较（存量调用点与单测一个字不改、行为不变）
 * @param crossSession 是否跨会话续跑（第三百四十七批喂；军令状的立状线是「≥5 步
 *   **或** 跨会话」——续跑中的长计划照样要过开工卡，缺省 false ⇒ 存量调用点不变）
 */
internal class PlanController(
    private val sessionId: () -> Long,
    private val store: PlanStore,
    private val log: (String) -> Unit = { com.example.zhengdao.core.RunLog.log(it) },
    private val workspaceRoot: () -> String = { "" },
    private val crossSession: () -> Boolean = { false },
) : PlanSink {

    /** 当前轮的计划（内存态；库里的真相是 kind='plan' 的 part）。 */
    @Volatile
    private var current: List<PlanItem> = emptyList()

    // ── 契约（242）：上限声明，只在用户批准之后生效 ────────────────────────
    //
    // 三条口径（派工单 B 段＋红线）：
    // - **批准才封顶**：模型自己发的契约没有"她批过"这一步 ⇒ 落库、卡上看得见，
    //   但不进 [PlanContractBoard]（不设上限）——回灌里明说没生效，不许它以为批了；
    // - **封顶不授权**：批准契约**不**给任何 R2/R3 放行，动作照走各自动作自己的确认卡；
    // - **进度更新不洗掉上限**：只改 status 的那一版通常不带 contract，
    //   此时沿用上一版——否则每打一个勾就把上限关掉一次。

    /** 当前这份计划声明的契约（null = 没声明）。 */
    @Volatile
    private var contract: PlanContract? = null

    /** 用户刚批准过一份契约（御批卡「批准计划」）；下一次带新契约的 submit 消费掉。 */
    @Volatile
    private var contractAuthorized = false

    /** 她明确拒过的那一版契约（同一版重发不再打扰；改一版就重新问）。 */
    @Volatile
    private var rejectedContract: PlanContract? = null

    // ── 军令状（312 判定层 ＋ 347 接线）：立状／结项／重签的工具层 ───────────
    //
    // 三条口径（347 派工单 §二）：
    // - **不许自动立状**：冻结只认用户在开工卡上点过的那一次（引擎在真执行前弹卡，
    //   [answerPledgeCard] 记裁决；[submit] 把这版计划**落库成功**那一刻才调
    //   [PlanPledgeBoard.freeze]）——模型自己发一份长计划立不上状；
    // - **没弹过卡＝无状**：无状任务的 closeout/resign 按无状处理（说明性忽略，
    //   不许事后补状掩盖漏项）；
    // - **resign 不等于 closeout**：弃约改的是计划（走 140 变更卡＋显式重签），
    //   结项申报才算结局——两者连在一次调用里都算非法组合（工具层拒）。

    /** 本轮已为立状弹过开工卡（一次问清；新轮清零）。 */
    @Volatile
    private var pledgeCardSettled = false

    /** 用户刚点过「立状并冻结」⇒ 下一次成功落库的那版计划冻结成快照（消费掉即清）。 */
    @Volatile
    private var pledgeApprovedPending = false

    /** 开工卡收场后留给 ack 的一句（declinedAck；submit 消费掉）。 */
    @Volatile
    private var pledgeAckLine: String? = null

    // ── 软档（140）：结构变更过卡 ＋ 连续 3 步偏离提示 ──────────────────────
    // 计数只统计「工具步」：一步跑完既没推进计划（update_plan 成功）也没更新计划
    // ⇒ +1；满 3 步弹一次卡。所有判据只提示，不拒绝任何工具调用（不绑一步一勾）。

    /** 连续几步工具跑完还没更新计划。 */
    private var stepsWithoutPlanUpdate = 0

    /** 本段偏离已经弹过卡（同一段不重复弹；计划更新/新轮后清零）。 */
    private var deviationPrompted = false

    /** 用户选了「继续」⇒ 本段偏离不再打扰，直到计划真的更新或新开一轮。 */
    private var deviationMuted = false

    /**
     * 用户刚批准过一次计划结构变更（御批卡「更新计划」/ 偏离卡「更新计划」）。
     * 下一次成功的 submit 消费掉——一次批准只放行一次改动。
     */
    @Volatile
    private var changeAuthorized = false

    /**
     * 用户对某一版结构变更**真点过按钮**的最近裁决（161 降噪）。
     *
     * 与 `baopu/Memory.kt` 那条记忆能力无关：进程内、不落盘、不跨进程，换一轮就清。
     * 它**不改变授权粒度**——放行照旧只认 [changeAuthorized] 那一张一次性的票。
     */
    private val changeMemory = PlanChangeMemory()

    /**
     * 一次 update_plan（工具执行器入口）。
     *
     * Failed（空 items / 坏条目）⇒ **不落库、不动内存态**：错误原文回灌给模型，
     * 它下一版自己改（与其它工具的「可重试×1」同口径）。
     */
    override fun submit(args: JSONObject): ToolOutcome {
        // 第三百四十七批①（工具层）：立状/结项/重签是**三个时刻**，一次调用只许带
        // 一件——非法组合在这里先拒（引擎还在本闸之前再拦一道，卡都不为它弹）。
        PlanPledgeSchemas.comboError(args)?.let {
            log("抱朴军令状：拒绝（$it）")
            return ToolOutcome.Failed(it)
        }
        val parsed = PlanRules.parseItems(args)
        if (parsed is PlanRules.Parse.Error) {
            log("抱朴计划：拒绝（${parsed.message}）")
            return ToolOutcome.Failed(parsed.message)
        }
        val ok = parsed as PlanRules.Parse.Ok
        // 软档（140）：文字增删＝结构变更，必须过用户那张卡；没有批准 ⇒ 不落库、不动内存。
        // 初始计划（上一版为空）与只改 status 的进度更新不走这里（不打扰）。
        val change = PlanChangeRules.classify(current, ok.items)
        if (change.needsCard && !changeAuthorized) {
            // 161 降噪：这一版改动用户本轮已经明确拒过 ⇒ 不再重复要授权，也不落库。
            // 只记「真点过按钮」的裁决；没点／超时不在这里（那不等于她答过）。
            if (changeMemory.lookup(change) == PlanChangeDecision.REJECTED) {
                log(
                    "抱朴计划：同一改动已被用户拒过（新增 ${change.added.size} 条 / " +
                        "删去 ${change.removed.size} 条）⇒ 不再重复打扰",
                )
                return ToolOutcome.Failed(PlanGuardRules.repeatRejected(change))
            }
            log(
                "抱朴计划：拒绝未经确认的改动（新增 ${change.added.size} 条 / " +
                    "删去 ${change.removed.size} 条）",
            )
            return ToolOutcome.Failed(PlanGuardRules.changeBlocked(change))
        }
        // 一次批准只放行一次改动：哪怕这次只是进度更新，也算用掉了。
        changeAuthorized = false
        // 能带着 needsCard 走到这里 ⇒ 一定是她批的；记下批准，覆盖此前的同版拒录。
        if (change.needsCard) changeMemory.record(change, PlanChangeDecision.APPROVED)
        // ── 第二百四十二批：这一版带的契约（上限声明）──────────────────────────
        // Error ⇒ 整次 submit 退回（不落库）：写了 contract 就是它想立上限，
        // 缺字段的"半张契约"静默当成没契约＝悄悄放弃封顶，那是骗人（负例①）。
        val contractParse = PlanContractRules.parse(args, ok.items)
        if (contractParse is PlanContractRules.ContractParse.Error) {
            log("抱朴契约：拒绝（${contractParse.message}）")
            return ToolOutcome.Failed(contractParse.message)
        }
        // ── 第三百四十七批：军令状三键的解析（全在落库**之前**——半张申报不落库）──
        // 立状：长程任务（≥5 步或跨会话）缺 pledge 是 Error 不是 Skip（判定层口径：
        // 静默当成没立状＝模型靠写长计划绕开结项门）。开工卡是引擎在真执行前弹的；
        // 卡已问过（pledgeCardSettled）或板上已有状 ⇒ 本版走 Skip。
        val pledgeParse = PlanPledgeRules.parsePledge(
            args,
            ok.items.size,
            crossSession(),
            pledged = PlanPledgeBoard.activeSnapshot() != null || pledgeCardSettled,
        )
        if (pledgeParse is PledgeParse.Error) {
            log("抱朴军令状：拒绝（${pledgeParse.message}）")
            return ToolOutcome.Failed(pledgeParse.message)
        }
        // 重签：立了状之后删项/加项必须显式重签（判据①；channel 只认 140 卡的人批——
        // 结构变更能走到这里 ⇒ 一定过了上面那张卡。resign 缺原因在这里就拒，落库前）。
        val resignGuard = PlanPledgeBoard.guardEdit(
            ok.items,
            args,
            if (change.needsCard) PlanPledgeRules.ResignChannel.HUMAN else null,
        )
        if (resignGuard is PlanPledgeRules.EditGuard.Reject) {
            log("抱朴军令状：拒绝（${resignGuard.message}）")
            return ToolOutcome.Failed(resignGuard.message)
        }
        // 结项申报：abandoned 缺原因在这里就拒（判据⑤）；DONE 缺证据不在这里拒——
        // 收口时按缺口回灌，模型知道补什么。
        val closeoutParse = PlanPledgeRules.parseCloseout(args)
        if (closeoutParse is PlanPledgeRules.CloseoutParse.Error) {
            log("抱朴军令状：拒绝（${closeoutParse.message}）")
            return ToolOutcome.Failed(closeoutParse.message)
        }
        // 声明了但没批准 ⇒ 只落库不进上限；同一版被拒过还原样重发，不再重复弹卡。
        val declared = (contractParse as? PlanContractRules.ContractParse.Ok)?.contract
            ?: contract
        val freshContract = declared != null && declared != contract
        val inForce = declared != null && (!freshContract || contractAuthorized)
        contractAuthorized = false
        val applied = PlanRules.applyOverwrite(current, ok.items)
        current = applied.items
        contract = declared
        val payload = PlanRules.toJson(applied.items, declared)
        val sid = sessionId()
        store.insert(sid, payload)
        // 契约进板（生效）／同步步骤勾选状态（已生效时上限跟着"现在在做哪一步"走）。
        //
        // 「生效」认两条路：新版本＋她批过（正常路），**或**板上还没装着上限而她批过——
        // 后者是"第一次问没人答、她后来在重发的那次上点了批准"：版本没变（fresh=false），
        // 但上限从来没装上过，只认 fresh 就永远装不上，ack 还会说"沿用上一版"那句谎话。
        // 反过来，换版没批 ⇒ 旧上限必须跟着旧计划一起退场（计划条目已经换成新的了，
        // 旧契约的步骤格子对不上新计划；赖在板上等于拿上一份计划的边界管这一次）。
        val activated = inForce && (freshContract || !PlanContractBoard.hasContract())
        if (activated) {
            PlanContractBoard.activate(declared, applied.items, workspaceRoot(), sid)
            log("抱朴契约：用户已批准 ⇒ 进上限（${declared.steps.size} 步）")
        } else if (inForce) {
            PlanContractBoard.notePlan(applied.items)
        } else if (freshContract) {
            PlanContractBoard.clear()
            log("抱朴契约：这一版还没经用户批准 ⇒ 本次不设执行上限（卡面上照旧看得见）")
        } else if (declared != null) {
            log("抱朴契约：这一版还没经用户批准 ⇒ 本次不设执行上限（卡面上照旧看得见）")
        }
        // 计划真的更新了（不论推进还是结构变更）⇒ 偏离计数清零、解除静音。
        stepsWithoutPlanUpdate = 0
        deviationPrompted = false
        deviationMuted = false
        // ── 第三百四十七批：落库成功之后的军令状动作（冻结／重签／申报进板）——
        // 冻结只在这时发生（人在卡上点过头＋这版计划真的落了库），账上不留没落地的状。
        val pledgeNotes = pledgeAfterLanding(applied.items, args, change, pledgeParse, closeoutParse, sid)
        val cardLine = pledgeAckLine
        pledgeAckLine = null
        log(
            "抱朴计划：已更新（session=$sid，共 ${applied.items.size} 条、" +
                "${PlanRules.doneCount(applied.items)} 完成" +
                if (ok.notices.isEmpty() && applied.stickyNotes.isEmpty()) {
                    "）"
                } else {
                    "；notice ${ok.notices.size + applied.stickyNotes.size} 条）"
                },
        )
        return ToolOutcome.Ok(
            PlanRules.renderAck(applied.items, ok.notices + applied.stickyNotes) +
                contractStateLine(declared, activated, PlanContractBoard.hasContract()) +
                (cardLine ?: "") +
                pledgeNotes.joinToString(""),
        )
    }

    /**
     * 契约状态那一句（跟在计划 ack 后面回灌给模型；没声明契约 ⇒ 空串，字节与今天相同）。
     *
     * 实话以**板上有没有上限**为准，不以"声明长什么样"为准：同一版重发她拒过 ⇒
     * 板上没有，就得说"没生效"，不能因为声明没变就说"沿用上一版"。模型必须知道
     * 自己头上有没有上限——不知道就会把"这次没弹卡"读成"用户已经授权"（负例②的成因）。
     */
    private fun contractStateLine(
        declared: PlanContract?,
        justActivated: Boolean,
        boardActive: Boolean,
    ): String = when {
        declared == null -> ""
        justActivated -> "契约已生效：之后的动作必须落在计划声明的步骤与白名单里，" +
            "超出会立刻停下来问用户——这**不减少**任何该有的确认卡（批准计划不等于授权改盘）。"
        boardActive -> "契约沿用上一版（这次没重发）：上限仍然生效，同上。"
        else -> "契约**没有生效**（用户还没批准这份计划）：本次运行不设执行上限，" +
            "但这不等于你可以动计划外的东西——该有的确认卡一张都不会少。" +
            "要让它生效：先在正文里把目标和白名单讲清楚，请用户批准。"
    }

    /**
     * 新一轮开始（用户发了新消息）：清内存计划。
     *
     * 为什么不从库继承上一轮的计划：轮是「一个用户请求」的单位，新请求的计划
     * 是新任务；继承会让上一轮打过的勾把这一轮的同名条目提前钉成 done。
     */
    /**
     * 还剩几条没勾（DONE 之外都算）。
     *
     * 用途：跑完一轮时给用户一句实话——「计划里还有 N 条没勾」。
     * 2026-10-19 用户真机：「完成了没有把计划里的全部勾完，就像是没有按计划走」——
     * 不许把没做完的装成做完了，也不许静默。
     */
    fun unfinishedCount(): Int = current.count { it.status != PlanRules.STATUS_DONE }

    // ── 软档 API（140；引擎只调这几个口子，判据全在 PlanGuardRules） ──────────

    /** 当前计划快照（卡面文案与测试用；调用方只读，不许拿它改内存态）。 */
    fun currentPlan(): List<PlanItem> = current

    /**
     * 预览这次 update_plan 的变更性质（**不落库、不动内存**）。
     * 解析失败返 null（让 submit 去回灌错误原文，别在预览层抢答）。
     */
    fun previewChange(args: JSONObject): PlanChange? {
        val parsed = PlanRules.parseItems(args) as? PlanRules.Parse.Ok ?: return null
        return PlanChangeRules.classify(current, parsed.items)
    }

    /** 是否已有一次待消费的「同意改计划」授权。 */
    fun isChangeAuthorized(): Boolean = changeAuthorized

    /** 用户点了「更新计划」⇒ 放行下一次结构变更（一次一张）。 */
    fun authorizeNextChange() {
        changeAuthorized = true
        log("抱朴计划：用户同意更新计划（下一次改动放行）")
    }

    /** 引擎弹卡前先问：这一版改动用户表过态吗（161；只有真点过按钮的才算）。 */
    fun decisionFor(change: PlanChange): PlanChangeDecision? = changeMemory.lookup(change)

    /**
     * 记下用户对确认卡的**显式**裁决（161）。
     *
     * 只有真点过的两条键进这里——超时／没点**不许调**：那不等于她答过，
     * 记下去等于替用户做决定，同一版改动她就没机会改主意了。
     */
    fun noteDecision(change: PlanChange, decision: PlanChangeDecision) {
        changeMemory.record(change, decision)
        log(
            "抱朴计划：记下用户裁决 $decision（新增 ${change.added.size} 条 / " +
                "删去 ${change.removed.size} 条）",
        )
    }

    /** 引擎在一整步工具调用结束后调用：这一步成功 update_plan 过 ⇒ 清零。 */
    fun notePlanUpdated() {
        stepsWithoutPlanUpdate = 0
        deviationPrompted = false
        deviationMuted = false
    }

    /**
     * 引擎在一整步工具调用结束后调用（这一步**没有**成功 update_plan）。
     *
     * @return true = 该弹偏离卡（同一段只弹一次；没有未完成计划 / 已静音 ⇒ 不弹）
     *
     * 只返回「要不要提示」，**不做任何阻断**——工具已经跑完了，这里绝不回退或终止。
     */
    fun noteToolStep(): Boolean {
        if (deviationMuted) return false
        val hasUnfinished = current.any { it.status != PlanRules.STATUS_DONE }
        if (!hasUnfinished) {
            stepsWithoutPlanUpdate = 0
            deviationPrompted = false
            return false
        }
        stepsWithoutPlanUpdate++
        if (!deviationPrompted && PlanGuardRules.shouldPromptDeviation(stepsWithoutPlanUpdate)) {
            deviationPrompted = true
            return true
        }
        return false
    }

    /**
     * 偏离卡回答完：按选择收尾。
     * - 回计划 / 更新计划：计数清零，满 3 步仍可再提示；
     * - 更新计划：额外放行下一次结构变更；
     * - 继续：本段偏离静音（不再弹），直到计划更新或新轮。
     */
    fun noteDeviationAnswered(action: PlanDeviationAction) {
        stepsWithoutPlanUpdate = 0
        deviationPrompted = false
        when (action) {
            PlanDeviationAction.UPDATE_PLAN -> changeAuthorized = true
            PlanDeviationAction.CONTINUE -> deviationMuted = true
            PlanDeviationAction.BACK_TO_PLAN -> Unit
        }
        log("抱朴计划：偏离卡选择 $action")
    }

    /**
     * 回灌给模型的「计划提醒」（2026-10-19 用户报「做完一步不打勾」；真机库读数：
     * 6 条 plan 快照里 `done` 出现 0 次——**模型从没标**，不是 UI 不刷新）。
     *
     * 只加在**普通工具结果**后面（`update_plan` 自己的 ack 已经有同类话术，别叠）；
     * 内容刻意短：剩余条数＋前两条＋一句「不标等于没做」。
     *
     * 第二百四十二批再挂一句「上次对账读数」：契约跑完发现预估骗人（问太少／风险估低），
     * 下一版计划必须看见这句话，否则同一份错估算每轮重来一遍（派工单 D 段后半句）。
     */
    fun reminderLine(): String? {
        if (current.isEmpty()) return null
        val hint = PlanContractBoard.estimateHint()
        val todo = current.filter { it.status != PlanRules.STATUS_DONE }
        if (todo.isEmpty()) return hint
        val names = todo.take(2).joinToString("；") { it.text }
        val more = if (todo.size > 2) "…" else ""
        return "【计划提醒】还有 ${todo.size} 条没勾（$names$more）。" +
            "打勾是干活的一部分：一步开始时先标 doing，做完那一刻立刻再调一次 update_plan、整表重发标 done；" +
            "卡住就留在 doing 并说明。" +
            "不许攒到最后批量补、不许偷偷划掉、不许假装做完——不标就等于没做（用户看到的是没打勾）。" +
            (hint ?: "")
    }

    // ── 契约 API（242；判据全在 PlanContractRules，这里只管「批没批」这一票）────
    //
    // 引擎在 update_plan **执行之前**问 needsContractApproval、弹批准卡；她点了「批准」
    // 才 authorizeContract()，随后重跑的那次 submit 把上限装进 [PlanContractBoard]。
    // 这条链路是红线①的落地处：**批准计划 ≠ 授权任何 R2/R3**——契约只封上限，
    // 动作该走的确认卡一张不少（见 GuardPipeline 的契约段与 RunEngine 的偏离分支）。

    /**
     * 批准卡收场。引擎把自己弹出去那一版还回来（[shown]），按选择收尾：
     * - 批准 ⇒ 放行下一次 submit 装上限；
     * - 不批／超时 ⇒ 记版次（同版不再重复打扰；超时不记，下次照问——见下）。
     *
     * 「没点／超时」不等于她答过 ⇒ 传 [shown] = null 时**不记拒**，只把放行清掉。
     */
    fun answerContractCard(shown: PlanContract?, accepted: Boolean) {
        if (accepted) {
            contractAuthorized = true
            log("抱朴契约：用户批准 ⇒ 本次运行设执行上限")
            return
        }
        contractAuthorized = false
        if (shown != null) {
            rejectedContract = shown
            log("抱朴契约：用户明确不批准这一版上限声明 ⇒ 本次不设执行上限")
        }
    }

    /** 当前声明的契约（内存态；null = 这一版计划没声明上限）。 */
    fun currentContract(): PlanContract? = contract

    /** 上限是否真的装上了（板上有契约 ⇒ 引擎此刻正在按契约设限）。 */
    fun isContractInForce(): Boolean = PlanContractBoard.hasContract()

    /**
     * 这次 update_plan 带的是不是一份「还没经她批准」的契约（**不落库、不动内存**）。
     *
     * 三种不弹卡：没带／带的就是板上正在用那一版（进度更新）／同一版她明确拒过。
     * **没点或超时不算拒**（那不等于她答过）⇒ 下次照样问，不许替她做决定。
     * 坏契约（缺字段）也返 null：错误原文由 [submit] 回灌，别在预览层抢答。
     */
    fun previewContract(args: JSONObject): PlanContract? {
        val parsed = PlanRules.parseItems(args) as? PlanRules.Parse.Ok ?: return null
        val declared = (PlanContractRules.parse(args, parsed.items)
            as? PlanContractRules.ContractParse.Ok)?.contract ?: return null
        if (declared == rejectedContract) return null
        if (contractAuthorized) return null
        if (declared == contract && PlanContractBoard.hasContract()) return null
        return declared
    }

    // ── 军令状 API（347；判据全在 PlanPledgeRules/Board，这里只管「问没问过人」）──
    //
    // 引擎在 update_plan **真执行之前**问 previewPledge、弹开工卡；她点了「立状并冻结」
    // 才 answerPledgeCard(approved=true)，随后 submit 把落库成功的那版计划冻结进板。
    // 这条链路与 242 契约卡同房型；差别只有一处——**冻结发生在落库之后**（账上不许
    // 留一份没落地的状）。

    /**
     * 这次 update_plan 是不是该弹开工卡（**不落库、不动内存、不冻结**）。
     *
     * 非 null ＝ 长程任务的立状候选（引擎拿它出卡）；null ＝ 不弹：不是 update_plan、
     * 解析不出来（错误原文由 [submit] 回灌，预览层不抢答）、短任务、板上已有状、
     * 本轮已问过、同一版候选她明确拒过（[PlanPledgeBoard.isDeclined]）。
     */
    fun previewPledge(args: JSONObject): PledgeSnapshot? {
        if (pledgeCardSettled || PlanPledgeBoard.activeSnapshot() != null) return null
        val parsed = PlanRules.parseItems(args) as? PlanRules.Parse.Ok ?: return null
        val parse = PlanPledgeRules.parsePledge(args, parsed.items.size, crossSession())
        if (parse !is PledgeParse.Need) return null
        val candidate = PlanPledgeRules.snapshotOf(
            parsed.items,
            parse.accepts,
            1,
            contractGoalOf(args, parsed.items),
            System.currentTimeMillis(),
            sessionId(),
        )
        return if (PlanPledgeBoard.isDeclined(candidate)) null else candidate
    }

    /**
     * 开工卡收场（照 242 批准卡房型；「拒/超时 ⇒ noteDeclined＋declinedAck」照 312 §4）。
     *
     * 「立状并冻结」⇒ 只记批准票：冻结在 [submit] 落库成功那一刻发生。
     * 「先不立状」／超时／没点 ⇒ [PlanPledgeBoard.noteDeclined]（同版候选本轮不再问；
     * 军令状沉默＝不立——没弹过卡就算无状任务，不设结项门）。
     */
    fun answerPledgeCard(candidate: PledgeSnapshot, approved: Boolean) {
        pledgeCardSettled = true
        if (approved) {
            pledgeApprovedPending = true
            log("抱朴军令状：用户同意立状（${candidate.entries.size} 项）⇒ 这版计划落库后冻结")
            return
        }
        PlanPledgeBoard.noteDeclined(candidate)
        pledgeAckLine = PlanPledgeCards.declinedAck(candidate)
        log("抱朴军令状：用户没立状（${candidate.entries.size} 项候选）⇒ 本任务不设结项门")
    }

    /** 计划卡上已打勾的条目原文（收口把缺口说得准一点；**打勾本身不构成结局**）。 */
    fun doneTexts(): Set<String> = current
        .filter { it.status == PlanRules.STATUS_DONE }
        .map { it.text }
        .toSet()

    /**
     * 落库成功之后的军令状三动作，按序：**冻结 → 重签 → 申报进板**。
     *
     * 冻结与重签互斥（有状才谈重签，没状才谈冻结）；申报与两者不同调用（工具层
     * 非法组合先拦过了）。每一步都往 ack 里留一句实话，模型必须知道自己头上
     * 有没有结项门。
     */
    private fun pledgeAfterLanding(
        items: List<PlanItem>,
        args: JSONObject,
        change: PlanChange,
        pledgeParse: PledgeParse,
        closeout: PlanPledgeRules.CloseoutParse,
        sid: Long,
    ): List<String> {
        val notes = mutableListOf<String>()
        // 立状的票没被消费掉（这版不是落库成功的那版形状变了也要清），不许漏到下一次。
        val approvedNow = pledgeApprovedPending
        pledgeApprovedPending = false
        val atMs = System.currentTimeMillis()
        // ① 冻结：她点过头、板上还没状——accepts 从本版参数重读（卡后模型改了写法也照实冻结）。
        if (approvedNow && PlanPledgeBoard.activeSnapshot() == null) {
            val accepts = (PlanPledgeRules.parsePledge(args, items.size, crossSession(), pledged = false)
                as? PledgeParse.Need)?.accepts ?: emptyList()
            val snapshot = PlanPledgeRules.snapshotOf(
                items, accepts, 1, contractGoalOf(args, items), atMs, sid,
            )
            PlanPledgeBoard.freeze(snapshot)
            notes += PlanPledgeCards.frozenAck(snapshot)
        }
        // ② 重签：140 卡人批过的结构变更 ⇒ 旧版原样留账，新版成为现役（343 衔接口径：
        // 之后按新版本快照结项）。验收方式没变的条目沿用旧条，新条以条目原文兜底。
        if (change.needsCard && PlanPledgeBoard.activeSnapshot() != null) {
            val reason = (PlanPledgeRules.parseResign(args)
                as? PlanPledgeRules.ResignParse.Ok)?.reason.orEmpty()
            if (reason.isNotEmpty()) {
                val old = PlanPledgeBoard.activeSnapshot()!!
                val oldAccept = old.entries.associate { it.text to it.accept }
                PlanPledgeBoard.applyResign(
                    items,
                    items.map { oldAccept[it.text] ?: it.text },
                    reason,
                    PlanPledgeRules.ResignChannel.HUMAN,
                    atMs,
                )?.let { next ->
                    notes += "【军令状重签】第 ${next.version} 版快照生效（${next.entries.size} 项）：" +
                        "旧版原样留在账上，之后按新版结项。"
                }
            }
        }
        // ③ 结项申报进板：无状 ⇒ 说明性忽略（无状任务不设结项门，不许事后补状）。
        if (pledgeParse is PledgeParse.Skip) notes += pledgeParse.notices
        if (closeout is PlanPledgeRules.CloseoutParse.Ok) {
            val active = PlanPledgeBoard.activeSnapshot()
            if (active == null) {
                notes += "【无状任务】未立军令状：closeout 申报已忽略，不设结项门（不许事后补状）。"
            } else {
                PlanPledgeBoard.noteCloseout(closeout.declarations)
                val known = active.texts.toSet()
                val unknown = closeout.declarations.map { it.text }.filter { it !in known }.distinct()
                if (unknown.isNotEmpty()) {
                    notes += "有 ${unknown.size} 条申报对不上快照（按条目原文逐字对齐）：" +
                        "${unknown.take(2).joinToString("；")}——这些在收口时不计。"
                }
            }
        }
        // 开工卡没弹过的直调（非引擎路径）：如实说没立状，不许让模型以为状生效了。
        if (pledgeParse is PledgeParse.Need) {
            notes += "【军令状未立】长程任务要立状，但开工卡没有弹出（非引擎直调路径）：" +
                "本次按无状处理——不冻结、不设结项门，该有的确认卡一张不少。"
        }
        return notes
    }

    /** 这版参数里契约声明的 goal（军令状快照的「目标」一行；没声明契约 ⇒ 空串）。 */
    private fun contractGoalOf(args: JSONObject, items: List<PlanItem>): String =
        (PlanContractRules.parse(args, items) as? PlanContractRules.ContractParse.Ok)
            ?.contract?.goal.orEmpty()

    fun newRound() {
        current = emptyList()
        stepsWithoutPlanUpdate = 0
        deviationPrompted = false
        deviationMuted = false
        changeAuthorized = false
        changeMemory.clear()
        // 契约（242）与计划同生命周期：新请求＝新上限，上一轮的封顶不许带到这一轮。
        contract = null
        contractAuthorized = false
        rejectedContract = null
        PlanContractBoard.clear()
        // 军令状（347）同一条生命周期：新请求＝新任务，旧状不带过来（收尾该记的
        // 缺口在上一轮的收尾路径已经进了未办成单——这里只管「这一轮从零开始」）。
        PlanPledgeBoard.clear()
        pledgeCardSettled = false
        pledgeApprovedPending = false
        pledgeAckLine = null
    }

    /**
     * 续跑回种（进程被杀后）：从库读该会话最后一条计划。
     *
     * 不回种的后果：模型重发计划时 sticky done 的 previous 是空表 ⇒ 已完成的
     * 条目被打回 doing，用户看到「打完的勾又亮了」（派工单 §1.2「重启/回看就
     * 没了」的另一半——内存态这一半）。
     *
     * 第二百四十二批：契约**只回种到卡面，不重装上限**。「她批过」这一步是进程内的
     * 票，没有落盘（落盘等于替她记住一次授权）⇒ fail-closed：上限关掉，下次它重发
     * 同一版时 [previewContract] 照常再问一次。上限关闭不等于随意——动作该走的确认卡
     * 一张不少（与「声明了但没批准」同一口径）。
     */
    fun seedFromStore(sessionId: Long) {
        val payload = store.latest(sessionId) ?: return
        current = PlanRules.fromJson(payload)
        contract = PlanRules.contractFromJson(payload)
        stepsWithoutPlanUpdate = 0
        deviationPrompted = false
        deviationMuted = false
        changeAuthorized = false
        changeMemory.clear()
        contractAuthorized = false
        rejectedContract = null
        PlanContractBoard.clear()
        // 军令状（347）：进程重启后内存是空的，账上的状由引擎的 seed 从
        // 计划军令状.jsonl 接回（seedFromStore 只清内存视图，不猜账里有什么）。
        PlanPledgeBoard.clear()
        pledgeCardSettled = false
        pledgeApprovedPending = false
        pledgeAckLine = null
    }
}

/**
 * 军令状三键的 schema 片段 ＋ 非法组合判（第三百四十七批①的工具层）。
 *
 * 为什么 schema 挂在这里而不动 `PlanTool`：`agent/tools/` 是 341 的在飞租约，
 * `PlanPledgeRules.ARG_*` 的三个键从**这里**以「同名覆盖再注册」的方式并进
 * update_plan 的 inputSchema——注册表本来就支持同名覆盖（后注册的赢），键名与
 * 枚举取值只认 [PlanPledgeRules] 一处，两边各写一份必漂移。
 */
internal object PlanPledgeSchemas {

    /** 把三个可选键并进 update_plan 的 inputSchema（深拷贝后再改，不动原件）。 */
    fun decorate(base: ToolSpec): ToolSpec {
        val schema = JSONObject(base.inputSchema.toString())
        val props = schema.getJSONObject("properties")
        props.put(PlanPledgeRules.ARG_PLEDGE, pledgeSchema())
        props.put(PlanPledgeRules.ARG_CLOSEOUT, closeoutSchema())
        props.put(PlanPledgeRules.ARG_RESIGN, resignSchema())
        return base.copy(inputSchema = schema)
    }

    /**
     * 非法组合（工具层拒）：立状／结项／重签是**三个时刻**——开工、收尾、改约，
     * 一次调用只许带一件。每条拒绝都教正确拆法（不许只说「不行」）。
     */
    fun comboError(args: JSONObject): String? = when {
        args.has(PlanPledgeRules.ARG_PLEDGE) && args.has(PlanPledgeRules.ARG_CLOSEOUT) ->
            "「${PlanPledgeRules.ARG_PLEDGE}」与「${PlanPledgeRules.ARG_CLOSEOUT}」不能同一次调用：" +
                "立状在开工、结项在收尾。先发计划＋pledge 把状立起来，收尾时再单独申报 closeout。"
        args.has(PlanPledgeRules.ARG_PLEDGE) && args.has(PlanPledgeRules.ARG_RESIGN) ->
            "「${PlanPledgeRules.ARG_PLEDGE}」与「${PlanPledgeRules.ARG_RESIGN}」不能同一次调用：" +
                "还没立状就谈不上重签。先发计划＋pledge 立状，改约是之后的事（要经人批或自决单）。"
        args.has(PlanPledgeRules.ARG_CLOSEOUT) && args.has(PlanPledgeRules.ARG_RESIGN) ->
            "「${PlanPledgeRules.ARG_CLOSEOUT}」与「${PlanPledgeRules.ARG_RESIGN}」不能同一次调用：" +
                "申报对的是现行快照，改约之后快照就换了版本——两次说不清哪版算数。" +
                "先带 resign 重签（经人批或自决单），下一次再按新快照申报 closeout。" +
                "弃约不等于完成：不许用 resign 代替 closeout。"
        else -> null
    }

    private fun pledgeSchema() = jsonObjectOf(
        "type" to "object",
        "description" to "立状：长程任务（≥5 步或跨会话）开工必带。提交后 App 弹一次开工卡，" +
            "用户点「立状并冻结」才生效；之后每一项都要有结局，收口时漏一项任务就记未完成",
        "properties" to jsonObjectOf(
            "acceptance" to jsonObjectOf(
                "type" to "array",
                "description" to "与 items 等长、按位对齐的验收方式数组：第 i 条说清第 i 项" +
                    "「做完凭什么算做完」（每条 ≤60 字）",
                "items" to jsonObjectOf("type" to "string"),
            ),
        ),
        "required" to jsonArrayOf("acceptance"),
        "additionalProperties" to false,
    )

    private fun closeoutSchema() = jsonObjectOf(
        "type" to "object",
        "description" to "结项申报：立了状的任务按快照条目**原文**逐条申报结局，可分多次给。" +
            "done 必附 evidence（文件/输出/账行）；abandoned 必填 reason。" +
            "漏报的项收口时按「没有结局」记，任务判未完成并进未办成单",
        "properties" to jsonObjectOf(
            "items" to jsonObjectOf(
                "type" to "array",
                "description" to "每条 {text, end, evidence?/reason?, detail?}",
                "items" to jsonObjectOf(
                    "type" to "object",
                    "properties" to jsonObjectOf(
                        "text" to jsonObjectOf(
                            "type" to "string",
                            "description" to "计划条目原文（逐字，改一个字都对不上账）",
                        ),
                        "end" to jsonObjectOf(
                            "type" to "string",
                            "enum" to jsonArrayOf(*PlanPledgeRules.EndKind.entries.map { it.wire }.toTypedArray()),
                            "description" to "done=做完（附证据）/ abandoned=放弃（必填原因）",
                        ),
                        "evidence" to jsonObjectOf(
                            "type" to "string",
                            "description" to "done 的证据：文件路径／工具输出／账行引用（必附）",
                        ),
                        "reason" to jsonObjectOf(
                            "type" to "string",
                            "enum" to jsonArrayOf(*PlanPledgeRules.AbandonReason.entries.map { it.wire }.toTypedArray()),
                            "description" to "abandoned 的原因：blocked/needs_human/unsupported 三选一（必填）",
                        ),
                        "detail" to jsonObjectOf(
                            "type" to "string",
                            "description" to "一句话补充（≤60 字）",
                        ),
                    ),
                    "required" to jsonArrayOf("text", "end"),
                    "additionalProperties" to false,
                ),
            ),
        ),
        "required" to jsonArrayOf("items"),
        "additionalProperties" to false,
    )

    private fun resignSchema() = jsonObjectOf(
        "type" to "object",
        "description" to "重签：冻结后删项/加项必须显式重签并经人批（计划变更确认卡）或自决单；" +
            "旧快照原样留在账上。不许用重签把没做的项悄悄删掉再宣布完成",
        "properties" to jsonObjectOf(
            "reason" to jsonObjectOf(
                "type" to "string",
                "description" to "删哪项、加哪项、为什么（必填，≤120 字）",
            ),
        ),
        "required" to jsonArrayOf("reason"),
        "additionalProperties" to false,
    )
}

/**
 * 军令状跨会话接回的**读侧**（347；312 §4.5）。账是权威：本对象只读不写，
 * 落板归引擎调 [PlanPledgeBoard.seed]（同一版不写第二行）。账文件读不到／里面
 * 没有该会话的任何行 ⇒ null——无状续跑，照既有口径走，不编一份状出来。
 */
internal object PlanPledgeSeed {

    /** 接回的一份：快照 ＋ 各项此前收口未过的次数（只带快照里还幸存的项）。 */
    data class Recovered(val snapshot: PledgeSnapshot, val failures: Map<String, Int>)

    /**
     * 从账里读**本会话**最近一版快照；settle 行里记的轮数一并恢复（各项收口未过
     * 次数取最大——重签不清零幸存项，那是「对项」的记录）。
     *
     * 归属口径：settle 行**没有** session 字段（encodeSettle 的形状）——它记的是
     * 「当时对哪一版快照」（id），轮数跟它前面最近那行快照的会话走；会话不同的
     * 快照行会把「当前会话」翻过去，settle 行只跟着自己前面那一版走。
     */
    fun read(sessionId: Long, ledger: java.io.File?): Recovered? {
        if (ledger == null) return null
        val lines = runCatching { ledger.readLines() }.getOrDefault(emptyList())
        var snapshot: PledgeSnapshot? = null
        var currentSession = -1L
        val failures = LinkedHashMap<String, Int>()
        for (raw in lines) {
            val obj = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: continue
            if (obj.optString("event") == PlanPledgeRules.EVENT_SETTLE) {
                if (currentSession == sessionId) mergeGapFailures(obj, failures)
                continue
            }
            val decoded = PlanPledgeRules.decodeSnapshot(obj) ?: continue
            currentSession = obj.optLong("session", -1L)
            if (currentSession == sessionId) snapshot = decoded
        }
        val active = snapshot ?: return null
        return Recovered(active, failures.filterKeys { it in active.texts.toSet() })
    }

    /** settle 行的 gaps 里取各项「第几次未过」的最大值（跨版本累计——幸存项不清零）。 */
    private fun mergeGapFailures(row: org.json.JSONObject, failures: MutableMap<String, Int>) {
        val gaps = row.optJSONArray("gaps") ?: return
        for (i in 0 until gaps.length()) {
            val g = gaps.optJSONObject(i) ?: continue
            val text = g.optString("text")
            val n = g.optInt("failures", 0)
            if (text.isNotEmpty() && n > (failures[text] ?: 0)) failures[text] = n
        }
    }
}
