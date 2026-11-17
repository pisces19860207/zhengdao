// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十九批 · 状态摘要最小版（653 的 M1：**给模型看**的那一半）。
//
// 规格来源：派工单-2026-11-15-第六百五十三批 §一「单一真源 RunState」与 §三「状态摘要」；
// 本批只做 M1（文本摘要），M2（给人看的运行图）另立。两条边界写在类型里：
//   ① **不新造状态机**：这里全是**只读投影**——没有任何转移表、没有事件、不持久化、
//      不改任何既有状态（update_plan 的计划、起居注、六器账都只读）；
//   ② **数字必须能指回账行**：每个数字的出处是 [Evidence]，构造期 `require` 挡住空引用
//      （判据③：证据引用非空断言——空引用根本造不出来，不是靠测试事后发现）。
//
// 命名撞车说明：`agent.RunState` 是引擎的**单轮七态**（IDLE/THINKING/…），与本文件的
// [RunState]（一次任务快照）是两件东西。两者不同包、互不引用；本文件**不 import** 那个枚举，
// 也**不改**它（653 原文「不新造状态机」的另一半：既有那台一个字不动）。
package com.example.zhengdao.baopu.state.summary

/** 一步的状态（闭集四格；`待办/进行中/完成` 来自 update_plan 的三态，`失败` 来自六器账）。 */
internal enum class StepState(val cn: String) {
    TODO("待办"),
    DOING("进行中"),
    DONE("完成"),
    FAILED("失败"),
}

/**
 * 证据引用——摘要里每个数字的出处。
 *
 * 形状写死成「账名#行号」：行号从 1 起，空引用在构造期就被拒（判据③）。
 * 为什么不做成字符串：字符串能塞任何东西，而「指不回账」正是摘要最容易犯的毛病。
 */
internal data class Evidence(val text: String) {
    init {
        require(text.isNotBlank()) { "证据引用不许为空（判据③）" }
    }

    /** 这条引用指的那一行（解析不出 ⇒ -1；测试用它把数字与账行对上）。 */
    val line: Int get() = text.substringAfterLast('#').toIntOrNull() ?: -1

    companion object {
        /** 计划快照里的第 [seq] 条（`update_plan` 落库的那一份 part 里的序号）。 */
        fun plan(seq: Int): Evidence = Evidence("计划#${atLeastOne(seq)}")

        /** 起居注第 [line] 行（`diary.jsonl` 当前代的 1 起行号）。 */
        fun diary(line: Int): Evidence = Evidence("账行#${atLeastOne(line)}")

        /** 六器账（未办成单）第 [seq] 行。 */
        fun ledger(seq: Int): Evidence = Evidence("未办成#${atLeastOne(seq)}")

        /** 未学会清单第 [seq] 条（`未学会.json` 的 items 顺序）。 */
        fun unlearned(seq: Int): Evidence = Evidence("未学会#${atLeastOne(seq)}")

        private fun atLeastOne(n: Int): Int {
            require(n >= 1) { "行号从 1 起：$n" }
            return n
        }
    }
}

/** 步列表的一行（序号／动作／状态／证据引用）。 */
internal data class RunStep(
    val seq: Int,
    val action: String,
    val state: StepState,
    val evidence: Evidence,
) {
    init {
        require(seq >= 1) { "序号从 1 起：$seq" }
        require(action.isNotBlank()) { "动作不许为空" }
    }
}

/** 当前阶段：一句名字 ＋ `(已做/总数)`——总数恒等于步数（判据①的对账口）。 */
internal data class Stage(val label: String, val done: Int, val total: Int) {
    init {
        require(total >= 0) { "总数不许为负：$total" }
        require(done in 0..total) { "已做 $done 不在 0..$total" }
    }

    /** 摘要里那一段的原文（`执行（3/7）`；括号与派工单 653 §三同形——全角）。 */
    val text: String get() = "$label（$done/$total）"
}

/** 一条带出处的短句（待决策／未学会／刚完成都用它——三者形状相同，只是来路不同）。 */
internal data class Note(val text: String, val evidence: Evidence) {
    init {
        require(text.isNotBlank()) { "短句不许为空" }
    }
}

/** 最近一次失败与归因（`卡住：…（原因:…）`）。 */
internal data class Failure(val action: String, val reason: String, val evidence: Evidence) {
    init {
        require(action.isNotBlank()) { "失败的动作不许为空" }
        require(reason.isNotBlank()) { "失败必须有归因（原因不许空）" }
    }
}

/**
 * 一次任务的状态快照（653 §一的六个字段，一个不多）。
 *
 * [trackLine] 是 670 `CharacterTrack.summaryLine()` 那一行——有神轨状态时才有，
 * 没有就是 null（不插、不报错，判据③的接线口）。
 */
internal data class RunState(
    val goal: String,
    val stage: Stage,
    val steps: List<RunStep>,
    val justDone: Note?,
    val decisions: List<Note>,
    val unlearned: List<Note>,
    val lastFailure: Failure?,
    val trackLine: String?,
) {
    /** 空状态（没目标、没计划、没账、没神轨）——摘要显示「无」，一个字不编（判据⑤）。 */
    val isEmpty: Boolean
        get() = goal.isBlank() && steps.isEmpty() && decisions.isEmpty() &&
            unlearned.isEmpty() && lastFailure == null && justDone == null && trackLine == null
}
