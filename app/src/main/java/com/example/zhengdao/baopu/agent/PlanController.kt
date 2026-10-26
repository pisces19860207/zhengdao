// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第十七批 §1.1/§1.2（A 段报计划 ＋ B 段落库）＋ §3（J1–J4 全压本内核）。
// 房型照 CompressionRunner：**内核零 Android**（store 是接口），引擎壳只接线
// （sessionId ＋ 轮次事件）；JVM 单测注假 store 就能把判据全部跑绿。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.PlanStore
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
 */
internal class PlanController(
    private val sessionId: () -> Long,
    private val store: PlanStore,
    private val log: (String) -> Unit = { com.example.zhengdao.core.RunLog.log(it) },
) : PlanSink {

    /** 当前轮的计划（内存态；库里的真相是 kind='plan' 的 part）。 */
    @Volatile
    private var current: List<PlanItem> = emptyList()

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
        val applied = PlanRules.applyOverwrite(current, ok.items)
        current = applied.items
        val payload = PlanRules.toJson(applied.items)
        val sid = sessionId()
        store.insert(sid, payload)
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
        return ToolOutcome.Ok(PlanRules.renderAck(applied.items, ok.notices + applied.stickyNotes))
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
     */
    fun reminderLine(): String? {
        if (current.isEmpty()) return null
        val todo = current.filter { it.status != PlanRules.STATUS_DONE }
        if (todo.isEmpty()) return null
        val names = todo.take(2).joinToString("；") { it.text }
        val more = if (todo.size > 2) "…" else ""
        return "【计划提醒】还有 ${todo.size} 条没勾（$names$more）。" +
            "打勾是干活的一部分：一步开始时先标 doing，做完那一刻立刻再调一次 update_plan、整表重发标 done；" +
            "卡住就留在 doing 并说明。" +
            "不许攒到最后批量补、不许偷偷划掉、不许假装做完——不标就等于没做（用户看到的是没打勾）。"
    }

    fun newRound() {
        current = emptyList()
        stepsWithoutPlanUpdate = 0
        deviationPrompted = false
        deviationMuted = false
        changeAuthorized = false
        changeMemory.clear()
    }

    /**
     * 续跑回种（进程被杀后）：从库读该会话最后一条计划。
     *
     * 不回种的后果：模型重发计划时 sticky done 的 previous 是空表 ⇒ 已完成的
     * 条目被打回 doing，用户看到「打完的勾又亮了」（派工单 §1.2「重启/回看就
     * 没了」的另一半——内存态这一半）。
     */
    fun seedFromStore(sessionId: Long) {
        current = PlanRules.fromJson(store.latest(sessionId) ?: return)
        stepsWithoutPlanUpdate = 0
        deviationPrompted = false
        deviationMuted = false
        changeAuthorized = false
        changeMemory.clear()
    }
}
