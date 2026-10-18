// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十九批 §2.2/§2.3/§2.4（写索引三路、提炼口径、会话封卷信号）
// 与 §4 判据 K6/K7。房型照 `MemoryRefine`：纯函数、不跑模型、确定性输出。
package com.example.zhengdao.baopu

/**
 * 会话封卷的**技能提炼**（第十九批）——纯函数：会话事实进、技能候选出。
 *
 * 与记忆提炼（[MemoryRefine]，每轮 Settled 触发）的分工（派工单 §2.4-4 定稿）：
 * **记忆候选走每轮，技能提案只走会话封卷**——封卷一次最多 3 条；「用户明说」的
 * 句子优先，确认卡上注明「这是你说的」。
 */
internal object SkillRefine {

    /** 一次封卷最多出几条技能候选（与记忆提炼 MAX_CANDIDATES=3 同口径，派工单 §2.3）。 */
    const val MAX_CANDIDATES = 3

    /** 场景一句话的字符上限（索引要短；索引行整体还受人设层 8000 tokens 约束）。 */
    const val SCENARIO_LIMIT = 80

    /**
     * 「用户明说要做技能」的标记词（派工单 §2.2-2 例句：记住这个流程）。
     * 命中 ⇒ 高置信候选（确认卡上注明「这是你说的」）。
     */
    val REQUEST_MARKERS = listOf(
        "记住这个流程", "记住流程", "记住这套流程", "沉淀成技能", "做成技能", "存成技能",
    )

    /** 技能提炼的触发点（K6 的靶子）。 */
    enum class Trigger {
        /** 点「新会话」＝旧会话封卷 ⇒ 立即提炼一次。 */
        CLOSE_AND_REFINE,

        /** 启动自检补算（封了卷没提炼成功的）。 */
        SELF_CHECK,
    }

    /**
     * 触发点判据（**K6 的钉子**）：技能提炼只挂在「会话封卷」与「启动补算」；
     * **每轮结束（Settled）留给记忆候选**——在那触发技能提案＝确认卡每轮刷屏
     * （派工单 §2.4-4 原话「禁止在每轮结束触发技能提炼」）。控制器接线必须走这里。
     */
    fun skillTriggerFor(engineEvent: String): Trigger? = when (engineEvent) {
        "settled" -> null
        "new_session" -> Trigger.CLOSE_AND_REFINE
        "startup" -> Trigger.SELF_CHECK
        else -> null
    }

    /** 一条技能候选（scope 决定进 B 层还是 C 层；fromUser = 用户明说）。 */
    data class Candidate(
        val scope: MemoryScope,
        val name: String,
        val scenario: String,
        val fromUser: Boolean,
    )

    /** 提炼结果：候选（≤[MAX_CANDIDATES]）＋ 被丢的条数（同批去重与超限；不静默）。 */
    data class Result(val candidates: List<Candidate>, val dropped: Int)

    /**
     * 封卷提炼（**纯函数、确定性**）：用户原话里命中 [REQUEST_MARKERS] 的句子 ⇒
     * 高置信候选；同批同名去重、已有同名技能索引的跳过（不是丢，是不重复提）；
     * 截到 [MAX_CANDIDATES]。
     *
     * 「值得做成流程」的确定性判据就一条：**用户明说**。模型自行脑补的「这值得沉淀」
     * 不出候选（确认卡每轮刷屏是事故，宁可漏——派工单 §2.3「用户明说优先」的落地）。
     */
    fun refine(facts: MemoryRefine.SessionFacts, existing: List<SkillRules.IndexLine>): Result {
        val all = mutableListOf<Candidate>()
        val seenNames = LinkedHashSet<String>()
        for (text in facts.userTexts) {
            for (sentence in MemoryRefine.sentences(text)) {
                if (REQUEST_MARKERS.none { sentence.contains(it) }) continue
                val scenario = sentence.take(SCENARIO_LIMIT)
                val name = MemoryRules.slugOf(sentence.take(18).ifBlank { "skill" })
                    .ifEmpty { "skill" }
                if (!seenNames.add(name)) continue // 同批同名去重（先见先立）
                if (existing.any { it.name == name }) continue // 已有同名索引 ⇒ 不重复提（不算丢）
                val scope = if (sentence.contains("全局")) MemoryScope.GLOBAL else MemoryScope.PROJECT
                all += Candidate(scope, name, scenario, fromUser = true)
            }
        }
        val kept = all.take(MAX_CANDIDATES)
        // 丢的 = 收集到的候选 − 留下的（超限截断的那截；不静默，壳层说出来）
        return Result(kept, (all.size - kept.size).coerceAtLeast(0))
    }
}
