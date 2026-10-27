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
 */
internal class PlanController(
    private val sessionId: () -> Long,
    private val store: PlanStore,
    private val log: (String) -> Unit = { com.example.zhengdao.core.RunLog.log(it) },
    private val workspaceRoot: () -> String = { "" },
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
                contractStateLine(declared, activated, PlanContractBoard.hasContract()),
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
    }
}
