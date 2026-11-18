// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第五百二十批 · 杏仁核·快探的**有状态半**：习惯化＋敏化。
//
// 与 675 的分工（派工单原文口径）：675 的杏仁核只出读数（无状态、即发即弃）；
// 本件是有状态的那一半——同类刺激的重复出现会改变快探对它的警戒度：
//   习惯化（判据②）：同类无害刺激反复出现，单位时间标记率超阈值 ⇒ 自动降敏并落账；
//   敏化（判据③）：该类刺激出过一次事故（轨迹行 FAILED/REJECTED/TIMEOUT）后
//     提高警戒 N 天，到期**自动衰减**回默认（不靠人，靠时刻比较），衰减也落账。
//   一键回默认（判据④）：resetToDefault 之后读回的一切都是出厂值。
//
// 事故盖过习惯化：出过事故的类三天内不许降敏（敏化期间习惯化闸门关着）；
// 事故同时把已降敏的乘子拉回 1.0——"无害"的证据被一次事故作废，这是机理不是策略。
//
// 账挂了怎么办（判据⑥）：记账统一走 [ledger] 缝——状态先改、记账在后、写失败吞掉只留一行
// stdout（BioLog 六体唯一 log 落点）。账从来不是快探的失败面：账好着与账坏着，读数逐字一致。
package com.example.zhengdao.baopu.amygdala

import com.example.zhengdao.baopu.bio.BioLog

/**
 * 一类刺激（classKey＝工具名，无工具则轨迹相位）的当前状态。
 * 不可变 data class：状态变更＝整值替换，读侧永远拿到一致的快照。
 */
internal data class ClassState(
    val marksInWindow: Int = 0,
    val eventsInWindow: Int = 0,
    val windowStartMs: Long = 0,
    val habituationFactor: Double = DEFAULT_FACTOR,
    val sensitizedUntilMs: Long = 0,
    val decayRecorded: Boolean = false,
) {
    val isSensitized: Boolean get() = sensitizedUntilMs > 0

    companion object {
        const val DEFAULT_FACTOR = 1.0
    }
}

/**
 * 状态机本体。全方法收 nowMs 显式入参（时钟由快探注入）——本类零时钟零随机，
 * 同样的事件序列喂两遍，账与状态逐字一致（判据③时间夹具因此好钉）。
 */
internal class AmygdalaState(private val account: ProbeAccount) {
    private val classes = HashMap<String, ClassState>()

    /** 当前生效乘子（习惯化×敏化）。敏化到期即视为 1.0——衰减是时刻比较，不靠清理任务。 */
    @Synchronized
    fun factorsFor(classKey: String, nowMs: Long): Pair<Double, Double> {
        val cs = classes[classKey] ?: ClassState()
        val sens = if (cs.sensitizedUntilMs > nowMs) SENSITIZE_FACTOR else ClassState.DEFAULT_FACTOR
        if (cs.isSensitized && cs.sensitizedUntilMs <= nowMs && !cs.decayRecorded) {
            classes[classKey] = cs.copy(decayRecorded = true)
            ledger(
                AccountEntry(
                    kind = AccountKind.DECAY,
                    classKey = classKey,
                    atMs = nowMs,
                    factor = ClassState.DEFAULT_FACTOR,
                    note = "敏化到期自动衰减（N=${SENSITIZE_DAYS} 天），乘子回 1.0",
                ),
            )
        }
        return cs.habituationFactor to sens
    }

    /** 批量标签探针：本类当前窗口事件数＋1 是否到批量阈值（只读，计数在 recordEvent 里走）。 */
    @Synchronized
    fun batchTripped(classKey: String, nowMs: Long): Boolean {
        val cs = rolled(classes[classKey] ?: ClassState(), nowMs)
        return cs.eventsInWindow + 1 >= BATCH_THRESHOLD
    }

    /**
     * 事件落账（判定完成后调用）：滚窗口→计数→触发降敏/敏化。
     * [marked]＝本次判了举手；[accident]＝本次轨迹行是事故状态。
     */
    @Synchronized
    fun recordEvent(classKey: String, marked: Boolean, accident: Boolean, nowMs: Long) {
        var cs = rolled(classes[classKey] ?: ClassState(), nowMs)
        cs = cs.copy(eventsInWindow = cs.eventsInWindow + 1)
        if (accident) {
            sensitize(classKey, cs, nowMs)
            return
        }
        cs = if (marked) cs.copy(marksInWindow = cs.marksInWindow + 1) else cs
        classes[classKey] = cs
        // "敏化期间不降敏"用到期口径：衰减过的类回到可习惯化（isSensitized 只看时间戳设没设，会永久挡住）。
        if (marked && cs.sensitizedUntilMs <= nowMs && cs.marksInWindow >= DESSENSITIZE_MARKS &&
            cs.habituationFactor > HABIT_FLOOR
        ) {
            desensitize(classKey, cs, nowMs)
        }
    }

    /** 一键回默认（判据④）：状态全清，账里记一行 RESET——账是历史，不清账。 */
    @Synchronized
    fun resetToDefault(nowMs: Long) {
        classes.clear()
        ledger(
            AccountEntry(
                kind = AccountKind.RESET,
                classKey = "*",
                atMs = nowMs,
                factor = ClassState.DEFAULT_FACTOR,
                note = "一键回默认：习惯化/敏化全部清零，读回即出厂值",
            ),
        )
    }

    /** 测试读回：某类当前状态（判据④"调用后读回原值"的读回口）。 */
    @Synchronized
    fun classView(classKey: String): ClassState = classes[classKey] ?: ClassState()

    /** 出厂读回：乘子、窗口、敏化全部默认（判据④断言用）。 */
    @Synchronized
    fun isAtDefault(classKey: String): Boolean = classView(classKey) == ClassState()

    // ── 状态变化的两条触发线 ──

    /** 记账唯一入口：状态已改完才调到这里；写失败＝无账续跑，异常绝不外漏。 */
    private fun ledger(entry: AccountEntry) {
        runCatching { account.append(entry) }.onFailure {
            BioLog.line(TAG, "探针账写失败，降级为无账续跑：${it.message}")
        }
    }

    private fun desensitize(classKey: String, cs: ClassState, nowMs: Long) {
        val next = cs.copy(
            habituationFactor = (cs.habituationFactor * HABIT_STEP).coerceAtLeast(HABIT_FLOOR),
            marksInWindow = 0,
            windowStartMs = nowMs,
        )
        classes[classKey] = next
        ledger(
            AccountEntry(
                kind = AccountKind.DESSENSITIZE,
                classKey = classKey,
                atMs = nowMs,
                factor = next.habituationFactor,
                note = "窗口 ${WINDOW_MS}ms 内标记 $DESSENSITIZE_MARKS 次且无事故 ⇒ 自动降敏",
            ),
        )
    }

    private fun sensitize(classKey: String, cs: ClassState, nowMs: Long) {
        val next = ClassState(
            windowStartMs = nowMs,
            habituationFactor = ClassState.DEFAULT_FACTOR,
            sensitizedUntilMs = nowMs + SENSITIZE_DAYS_MS,
            decayRecorded = false,
        )
        classes[classKey] = next
        ledger(
            AccountEntry(
                kind = AccountKind.SENSITIZE,
                classKey = classKey,
                atMs = nowMs,
                factor = SENSITIZE_FACTOR,
                note = "出过一次事故 ⇒ 同类刺激敏化 ${SENSITIZE_DAYS} 天（习惯化证据作废）",
            ),
        )
    }

    /** 窗口滚动：超过窗口宽 ⇒ 计数清零、窗口起点挪到当前时刻（按事件对齐的固定窗口）。 */
    private fun rolled(cs: ClassState, nowMs: Long): ClassState =
        if (cs.windowStartMs == 0L || nowMs - cs.windowStartMs > WINDOW_MS) {
            cs.copy(marksInWindow = 0, eventsInWindow = 0, windowStartMs = nowMs)
        } else {
            cs
        }

    private companion object {
        const val TAG = "杏仁核·快探"

        /** 标记率阈值：窗口 60s 内举手满 6 次 ⇒ 降敏一次（率≈0.1/s）。 */
        const val DESSENSITIZE_MARKS = 6
        const val WINDOW_MS = 60_000L

        /** 每次降敏的乘子步长与地板：1.0→0.5→0.25 封底（再无害也不彻底聋掉）。 */
        const val HABIT_STEP = 0.5
        const val HABIT_FLOOR = 0.25

        /** 敏化：乘子翻倍、持续 3 天、到期自动衰减。 */
        const val SENSITIZE_FACTOR = 2.0
        const val SENSITIZE_DAYS = 3
        const val SENSITIZE_DAYS_MS = SENSITIZE_DAYS * 24L * 60L * 60L * 1000L

        /** 批量阈值：同窗口同类事件满 8 次算批量。 */
        const val BATCH_THRESHOLD = 8
    }
}
