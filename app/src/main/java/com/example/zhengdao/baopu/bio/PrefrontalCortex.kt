// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百九十四批 · 前额叶·计划（六体第④件，登记表第三行）。
//
// 机理：执行功能——计划与抑制的**候选生成**面。只做：对"计划走没走顺"出一条
// 读数 {stepsLeft, stuckAt, reason}，供裁决面参考。不做：不修改计划、不执行、
// 不批准、不否决——计划的写权在 update_plan（引擎）与人，本件连计划正文都收不到。
//
// 四条硬规矩（派工单①逐字落码）：
//   ① 输入只有步视图与失败步号：[PlanStepView] 里没有 text 字段——结构上抄不到正文，
//      输出 reason 只由计数与步号拼成（敏感夹具测试钉死"计划原文一个字不出"）；
//   ② 输出只有 {stepsLeft, stuckAt, reason}——stepsLeft 封底 0，stuckAt 要么步号要么 null；
//   ③ 不改计划：本 object 无状态、无落盘、不引用任何引擎计划类型（BioSourceAssertTest
//      钉禁引擎符号）——同输入两次读数逐字一致（判据④）；
//   ④ 只 log：每次读数走 [BioLog] 一行 stdout，不落权威账（BioSourceAssertTest 钉）。
//
// 本件无判定权（523 三件套②）：不产生 RiskDecision/PROCEED/REJECT，不改档，不写权威账。
package com.example.zhengdao.baopu.bio

/** 前额叶·计划读数（纯函数器官，零状态零落盘）。 */
@BioAnalog("前额叶")
internal object PrefrontalCortex {

    /** 一步的只读视图：只有步号与做完没做。没有 text 字段——本件收不到计划正文。 */
    data class PlanStepView(val no: Int, val done: Boolean)

    /**
     * 计划快照＋最近失败步号。
     * [recentFailures] 按时间序（早→晚，末条＝最近一条）；元素是失败所在的步号，
     * 步号不知道就记 null——判不了就不猜（与 SkillEffects 的纠正判据同一教条）。
     * 本件只看**最后 3 条**（[STUCK_WINDOW]），给多了自己截。
     */
    data class PlanView(
        val steps: List<PlanStepView>,
        val recentFailures: List<Int?> = emptyList(),
    )

    /** 读数形状。stepsLeft ≥ 0；stuckAt = 卡步读数（步号）或 null（无卡步读数）。 */
    data class PlanReading(val stepsLeft: Int, val stuckAt: Int?, val reason: String)

    /** 读数主入口。同一 view 调两次，[PlanReading] 逐字相等（判据④）。 */
    fun readPlan(view: PlanView): PlanReading {
        val left = view.steps.count { !it.done }
        val stuck = stuckAt(view)
        val reading = PlanReading(
            stepsLeft = left,
            stuckAt = stuck,
            reason = reasonOf(left, view, stuck),
        )
        BioLog.line(TAG, "stepsLeft=$left stuckAt=$stuck failures=${view.recentFailures.size}")
        return reading
    }

    /**
     * 卡步判据（纯机械，四条全满足才有步号，缺一条就是 null）：
     *   ① 最近 3 条失败凑得齐（不足 3 条 ⇒ 证据不够）；
     *   ② 三条步号都非 null（有一条不知道在哪步 ⇒ 判不了）；
     *   ③ 三条同指一步（分散 ⇒ 不成一线）；
     *   ④ 该步还在当前计划里且未完（计划已改／已完 ⇒ 旧证据作废）。
     */
    private fun stuckAt(view: PlanView): Int? {
        val last3 = view.recentFailures.takeLast(STUCK_WINDOW)
        if (last3.size < STUCK_WINDOW) return null
        val first = last3.first() ?: return null
        if (last3.any { it != first }) return null
        val step = view.steps.firstOrNull { it.no == first } ?: return null
        return if (step.done) null else first
    }

    /**
     * reason 只由计数与步号拼成——失败原文、计划正文一个字都进不来（结构保证，
     * 敏感夹具测试另钉一道）。尾巴一句把"不改计划"说在明处。
     */
    private fun reasonOf(left: Int, view: PlanView, stuck: Int?): String {
        val head = "剩 $left/${view.steps.size} 步未完"
        val middle = if (stuck != null) {
            "；近 $STUCK_WINDOW 条失败同指第 $stuck 步（在表、未完）"
        } else {
            noStuckWhy(view)
        }
        return "$head$middle；读数仅供参考——计划改动权在 update_plan 与人"
    }

    /** 无卡步读数时，把"为什么没有"按机械原因说出来（判不了 ≠ 卡住了）。 */
    private fun noStuckWhy(view: PlanView): String {
        val last3 = view.recentFailures.takeLast(STUCK_WINDOW)
        return when {
            last3.size < STUCK_WINDOW -> "；近 $STUCK_WINDOW 条失败只有 ${last3.size} 条，证据不够"
            last3.any { it == null } -> "；近 $STUCK_WINDOW 条失败有缺步号的，判不了就不猜"
            else -> divergedWhy(last3, view)
        }
    }

    /** 三条步号齐全但不出卡步读数的两种剩余形态：不在表里（计划改过）／该步已完。 */
    private fun divergedWhy(last3: List<Int?>, view: PlanView): String {
        val distinct = last3.filterNotNull().distinct()
        if (distinct.size > 1) {
            return "；近 $STUCK_WINDOW 条失败分散在 ${distinct.sorted().joinToString("/")} 步，不成一线"
        }
        val no = distinct.first()
        val step = view.steps.firstOrNull { it.no == no }
        return if (step == null) {
            "；近 $STUCK_WINDOW 条失败同指第 $no 步，但该步已不在当前表里（计划改过）"
        } else {
            "；近 $STUCK_WINDOW 条失败同指第 $no 步，但该步已完"
        }
    }

    private const val TAG = "前额叶"

    /** 卡步窗口：连续 3 条失败同指一步才给卡步读数（派工单①"最近 3 条失败"）。 */
    private const val STUCK_WINDOW = 3
}
