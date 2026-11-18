// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 纹状体消费层的**纯判据**：习惯化——"同一流程重复 ≥5 次且每次都成功"
// ⇒ 出一张**固化建议卡**。只出建议：建议走既有许愿流水线（草稿 → 隔离试跑 → 自检 → 灰度），
// 转正动作永远是人批；本件没有任何写技能的路径（源码门禁 HabitSourceGateTest 钉死）。
//
// 判据本体三件：
//   ① 序列归一：一次运行的 ACT 边界按发生序串成"工具序列"；序列键＝工具名串。
//      参数不进键（args_hash 只在账行留证据）——同一流程不同目标是同一习惯；
//      太短（<2 步）不算流程，太长（>12 步）多半是杂事凑巧，也不算。
//   ② 达标判定：同一序列键在回看窗内 ≥ [HabitLimits.REPEAT_THRESHOLD] 次、
//      **每一次都成功**（差读数按 694 纹状体四格表：只有 +1「一次过」的运行计数——
//      失败或被纠正的那次掐断整条链）。
//   ③ 建议去重：同一条序列键只建议一次（状态文件记着），人没批之前不催第二遍。
//
// 纪律：零 Android、零 IO、零时钟——运行序列与"现在"全由调用方注入，JVM 直测。
package com.example.zhengdao.baopu.habit

/** 限值的唯一住处（改任何一条＝改习惯化制度；不许调用处各写一把）。 */
internal object HabitLimits {

    /** 达标次数（派工单默认 N=5）：同一序列 ≥5 次全成才算习惯。 */
    const val REPEAT_THRESHOLD = 5

    /** 序列至少几步才算"流程"（单工具不成流程）。 */
    const val MIN_STEPS = 2

    /** 序列最多几步（超过按杂事丢弃，不进候选池）。 */
    const val MAX_STEPS = 12

    /** 一轮回看的最近运行数（岛叶可把它调小；上限钉死，不许调大）。 */
    const val SCAN_RUNS_MAX = 30

    /** 习惯回看窗（30 天）：窗外的运行不计数——老黄历不算习惯。 */
    const val WINDOW_MS: Long = 30L * 24 * 60 * 60 * 1000

    /** 一轮最多浮几张建议卡（习惯化是低声提醒，不是清单）。 */
    const val SUGGEST_MAX = 3
}

/**
 * 一次运行的归一序列（调用方从 `run_trace` 装配；本件不摸库）。
 * [tools] ＝ ACT 边界的工具名，按发生序；[ok] ＝ 本次全成（所有 ACT 边界 OK，
 * 中途被拒/超时/取消都算不成）；[rpe] ＝ 694 纹状体四格表对本次的差读数（-1|0|+1）。
 */
internal data class RunSeq(val runId: String, val tools: List<String>, val ok: Boolean, val rpe: Int, val atMs: Long)

/** 一条达标习惯（判据①②都过了才有它）。 */
internal data class HabitCandidate(
    val seqKey: String,
    val tools: List<String>,
    val runs: List<RunSeq>,
) {
    val count: Int get() = runs.size
    val lastAtMs: Long get() = runs.maxOf { it.atMs }
}

/**
 * 一张固化建议卡（页签行数据源；[wishText] 是人点采纳后递进许愿流水线的那句话）。
 * [wishId] 非空 ＝ 已递进流水线，等人在会话里回「许愿确认」挑验收判据。
 */
internal data class HabitSuggestion(
    val id: String,
    val seqKey: String,
    val tools: List<String>,
    val count: Int,
    val lastAtMs: Long,
    val wishText: String,
    val wishId: String = "",
) {
    /** 卡的稳定 id（页签键前缀在装配层加；同一序列键恒同一卡）。 */
    companion object {
        fun idOf(seqKey: String): String = "habit:" + seqKey.hashCode().toUInt().toString(16)
    }
}

/** 序列键（判据①）：工具名按发生序串接；空序列与超长序列不造键。 */
internal fun sequenceKeyOf(tools: List<String>): String? {
    if (tools.size < HabitLimits.MIN_STEPS || tools.size > HabitLimits.MAX_STEPS) return null
    if (tools.any { it.isBlank() }) return null
    return tools.joinToString(">")
}

/**
 * 达标判定（判据①②③的合体，纯函数）：
 * 同一序列键、窗内、每次差读数都 +1（一次过）的运行 ≥ [HabitLimits.REPEAT_THRESHOLD]
 * ＝ 一条候选；已在 [suggestedKeys] 里的不再建议；一最多 [HabitLimits.SUGGEST_MAX] 张。
 * [nowMs] 只用来切回看窗（判据②）。
 */
internal fun habitCandidatesOf(
    runs: List<RunSeq>,
    suggestedKeys: Set<String>,
    nowMs: Long,
): List<HabitCandidate> = runs
    .asSequence()
    .filter { it.ok && it.rpe == RPE_PASS && nowMs - it.atMs <= HabitLimits.WINDOW_MS }
    .mapNotNull { run -> sequenceKeyOf(run.tools)?.let { it to run } }
    .groupBy({ it.first }, { it.second })
    .map { (key, group) -> HabitCandidate(key, group.first().tools, group.distinctBy { it.runId }) }
    .filter { it.count >= HabitLimits.REPEAT_THRESHOLD && it.seqKey !in suggestedKeys }
    .sortedByDescending { it.lastAtMs }
    .take(HabitLimits.SUGGEST_MAX)
    .toList()

/** 候选 ⇒ 建议卡（判据③的去重键由调用方记；wishText 在这里定稿，采纳方不许改写）。 */
internal fun suggestionOf(candidate: HabitCandidate): HabitSuggestion = HabitSuggestion(
    id = HabitSuggestion.idOf(candidate.seqKey),
    seqKey = candidate.seqKey,
    tools = candidate.tools,
    count = candidate.count,
    lastAtMs = candidate.lastAtMs,
    wishText = wishTextOf(candidate),
)

/** 采纳时递进许愿流水线的原话（写死在这里，谁也不许临时编——账行对得上才审得出）。 */
internal fun wishTextOf(candidate: HabitCandidate): String {
    val flow = candidate.tools.joinToString("、")
    return "我想要一个能把「${candidate.seqKey}」（$flow，连着 ${candidate.count} 次都一次过）" +
        "这条流程固化成技能的东西"
}

/** 694 纹状体差读数的"一次过"档（本件只认这一档计数，其余两档掐链）。 */
internal const val RPE_PASS = 1
