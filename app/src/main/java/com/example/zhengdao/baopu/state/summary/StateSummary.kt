// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百八十九批 · 状态摘要的**渲染器**（纯函数：零 IO、零时钟、零随机）。
//
// 形状照派工单 653 §三逐字：
//   「目标：…｜阶段：…(3/7)｜刚完成：…（证据:账行#123）｜待办：…｜卡住：…（原因:…）」
// 三条口径（判据②③⑤）：
//   ① **≤400 字**：先按段拼，再按「从尾往前丢段」收进预算——不腰斩半条证据引用
//      （腰斩的 `账行#1` 指不回任何一行，等于判据③自己破功）；
//   ② **每个数字都有出处**：段里的行号全部来自 [Evidence]，本文件不自己造数；
//   ③ **空状态 ⇒ 「无」**：不编造步骤、不填占位阶段（判据⑤负例的靶心）。
package com.example.zhengdao.baopu.state.summary

/** 摘要的渲染（纯函数；同一 [RunState] 两次渲染逐字节相同）。 */
internal object StateSummary {

    /** 硬上限（判据②：≤400 字；按 Kotlin `String.length` 计，CJK 一字一码位）。 */
    const val MAX_CHARS: Int = 400

    /** 空状态那一句（判据⑤：不编造）。 */
    const val EMPTY: String = "无"

    private const val SEP = "｜"

    /** 神轨那一行自己的长度上限（670 的 summaryLine 预算 ≈ 40 token，这里留足余量）。 */
    private const val TRACK_MAX = 120

    private const val GOAL_MAX = 60
    private const val ACTION_MAX = 24
    private const val REASON_MAX = 30
    private const val NOTE_MAX = 40

    /** 待办最多列两条（多了对模型没用，反而挤掉「卡住」）。 */
    private const val MAX_TODO = 2

    /**
     * 一次渲染。
     *
     * 神轨那一行**单独占一行**（派工单 689 ③「有神轨状态时把它作为一行插入」）：
     * 先给它留出预算，剩下的才归上面那段，所以 ≤400 是整段（含那一行）的总账。
     */
    fun render(state: RunState): String {
        if (state.isEmpty) return EMPTY
        val track = state.trackLine?.takeIf { it.isNotBlank() }?.let { clip(it, TRACK_MAX) }
        val budget = MAX_CHARS - (track?.let { it.length + 1 } ?: 0)
        val head = fit(segments(state), budget)
        return if (track == null) head else head + "\n" + track
    }

    /** 摘要的段（按优先级排；收预算时从尾往前丢——丢的总是「未学会／待决策」这类先让步的）。 */
    fun segments(state: RunState): List<String> = listOfNotNull(
        "目标：" + clip(state.goal.ifBlank { "未说" }, GOAL_MAX),
        "阶段：" + state.stage.text,
        state.justDone?.let { "刚完成：" + clip(it.text, ACTION_MAX) + evidenceOf(it) },
        todoSegment(state.steps),
        state.lastFailure?.let {
            "卡住：" + clip(it.action, ACTION_MAX) +
                "（原因:" + clip(it.reason, REASON_MAX) + "｜证据:" + it.evidence.text + "）"
        },
        state.decisions.firstOrNull()?.let { "待决策：" + clip(it.text, NOTE_MAX) + evidenceOf(it) },
        state.unlearned.firstOrNull()?.let { "未学会：" + clip(it.text, NOTE_MAX) + evidenceOf(it) },
    )

    /** 待办那一段（每一条都带自己的计划行号；超过两条用省略号收，不造「等 N 条」这种无出处的数）。 */
    private fun todoSegment(steps: List<RunStep>): String? {
        val todo = steps.filter { it.state == StepState.TODO }
        if (todo.isEmpty()) return null
        val shown = todo.take(MAX_TODO).joinToString("、") { clip(it.action, ACTION_MAX) + evidenceOf(it) }
        return "待办：" + shown + if (todo.size > MAX_TODO) "…" else ""
    }

    private fun evidenceOf(step: RunStep): String = "（证据:" + step.evidence.text + "）"

    private fun evidenceOf(note: Note): String = "（证据:" + note.evidence.text + "）"

    /** 收进预算：只整段整段地丢（至少留「目标＋阶段」两段），最后才允许截目标。 */
    private fun fit(segs: List<String>, budget: Int): String {
        val kept = segs.toMutableList()
        while (kept.size > 2 && kept.joinToString(SEP).length > budget) kept.removeAt(kept.lastIndex)
        val text = kept.joinToString(SEP)
        return if (text.length <= budget) text else clip(text, budget)
    }

    /** 超长就截断并以省略号收尾（`max` 是含省略号的总长）。 */
    fun clip(text: String, max: Int): String =
        if (text.length <= max) text else text.take((max - 1).coerceAtLeast(0)) + "…"
}
