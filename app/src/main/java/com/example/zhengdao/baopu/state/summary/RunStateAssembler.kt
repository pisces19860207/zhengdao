// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十九批 · **组装器**（纯函数：入参是既有账本的快照，出参是 [RunState]）。
//
// 派工单 653 §一：「从既有 update_plan ＋ 起居注 ＋ 六器账组装，**不新造状态机**」。
// 所以本文件只做三件投影，一个转移都不写：
//   ① 计划条目（[PlanItem] 三态）⇒ 步列表 ＋ 阶段（阶段是「已勾/总数」的投影，不是状态机：
//      没有边、没有事件、不持久化，重启后重新算）；
//   ② 起居注最后一条 ACTION ⇒ 「刚完成」（证据＝那一行的账行号，正是 653 举例的 `账行#123`）；
//      没有 ACTION 就退回「最后一个已勾的计划步」（证据＝计划行号）——两种都有出处；
//   ③ 六器账（未办成单）⇒ 「卡住」（最近一次失败与归因）＋「待决策」（能补但要人点头的）；
//      未学会清单 ⇒ 「未学会」。
//
// 神轨那一行（670 `CharacterTrack.summaryLine()`）是本批唯一的**新消费**：
// 传了实例就取那一行（取值失败按「没有」处理，绝不把摘要搞崩），没传就不插（判据③）。
package com.example.zhengdao.baopu.state.summary

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.scout.UnfinishedItem
import com.example.zhengdao.baopu.scout.UnfinishedOutcome
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import com.example.zhengdao.baopu.state.track.CharacterTrack

/** 既有账本 ⇒ [RunState] 的投影（纯函数；同入参两次组装结果相同）。 */
internal object RunStateAssembler {

    /**
     * @param goal 目标一句话（来自已批准契约的 `goal`；没有契约就空串 ⇒ 摘要写「未说」）
     * @param plan `update_plan` 当前那份快照（[PlanRules.PlanItem]，顺序即步序）
     * @param diary 起居注全本（旧→新；行号＝列表下标＋1）
     * @param ledger 未办成单当前单（六器账；顺序即账行）
     * @param unlearned 未学会清单的问题（`NotLearnedStore` 的 items 顺序）
     * @param track 神轨（670）；null ＝ 没有神轨状态 ⇒ 摘要里不插那一行
     */
    fun assemble(
        goal: String?,
        plan: List<PlanItem> = emptyList(),
        diary: List<DiaryEvent> = emptyList(),
        ledger: List<UnfinishedItem> = emptyList(),
        unlearned: List<String> = emptyList(),
        track: CharacterTrack? = null,
    ): RunState {
        val steps = plan.mapIndexed { i, item -> stepOf(i + 1, item) }
        val done = steps.count { it.state == StepState.DONE }
        return RunState(
            goal = goal.orEmpty(),
            stage = Stage(stageLabel(steps, done), done, steps.size),
            steps = steps,
            justDone = lastActionIn(diary) ?: steps.lastOrNull { it.state == StepState.DONE }?.let {
                Note(it.action, it.evidence)
            },
            decisions = ledger.mapIndexedNotNull { i, item ->
                if (item.fixable) Note(item.intent, Evidence.ledger(i + 1)) else null
            },
            unlearned = unlearned.mapIndexed { i, question -> Note(question, Evidence.unlearned(i + 1)) },
            lastFailure = lastFailureIn(ledger),
            trackLine = trackLineOf(track),
        )
    }

    /** 计划条目 ⇒ 步（三态照搬；认不出的状态按「待办」——计划层已归一过，这里只兜底）。 */
    private fun stepOf(seq: Int, item: PlanItem): RunStep = RunStep(
        seq = seq,
        action = item.text,
        state = when (item.status) {
            PlanRules.STATUS_DONE -> StepState.DONE
            PlanRules.STATUS_DOING -> StepState.DOING
            else -> StepState.TODO
        },
        evidence = Evidence.plan(seq),
    )

    /**
     * 阶段名的投影（四个值，**不是状态机**：没有转移表、没有事件、不落盘）：
     * 没计划＝未立计划／全勾完＝收尾／有在做＝执行／其余＝起步。
     */
    private fun stageLabel(steps: List<RunStep>, done: Int): String = when {
        steps.isEmpty() -> "未立计划"
        done == steps.size -> "收尾"
        steps.any { it.state == StepState.DOING } -> "执行"
        else -> "起步"
    }

    /** 起居注最后一条动作行 ⇒ 刚完成（账行号＝它在当前代 `diary.jsonl` 里的行序）。 */
    private fun lastActionIn(diary: List<DiaryEvent>): Note? =
        diary.withIndex().lastOrNull { it.value.kind == DiaryKind.ACTION }?.let {
            Note(it.value.what, Evidence.diary(it.index + 1))
        }

    /** 六器账里最近一次「失败」⇒ 卡住（归因＝原因闭集的中文名 ＋ 那句事实补充）。 */
    private fun lastFailureIn(ledger: List<UnfinishedItem>): Failure? =
        ledger.withIndex()
            .filter { it.value.outcome == UnfinishedOutcome.FAILED }
            .maxByOrNull { it.value.atMs }
            ?.let {
                Failure(
                    action = it.value.intent,
                    reason = reasonOf(it.value),
                    evidence = Evidence.ledger(it.index + 1),
                )
            }

    private fun reasonOf(item: UnfinishedItem): String =
        if (item.reasonDetail.isBlank()) item.reason.cn else item.reason.cn + "·" + item.reasonDetail

    /** 神轨那一行（有实例才取；取值失败当没有——摘要不许因为神轨崩掉）。 */
    private fun trackLineOf(track: CharacterTrack?): String? {
        if (track == null) return null
        return runCatching { track.summaryLine() }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
