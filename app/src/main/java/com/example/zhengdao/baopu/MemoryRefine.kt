// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十批 §1 C 段（会话 Done/Aborted 时提炼一次：候选记忆一律 PENDING、
// 顺带产出一条 kind='introspection' 自省，不额外多跑一轮模型）＋ §5 判据 8
// （恰好一条自省；不进 A 内核——PersonaRules.canWrite(A_KERNEL) 仍为 false）。
package com.example.zhengdao.baopu

/**
 * 会话结束提炼（刀 2 C 段）——**纯函数**：会话事实进、候选记忆 ＋ 一条自省出。
 *
 * ## 为什么是纯函数而不是再跑一轮模型
 * 派工单原话：「顺带产出一条『这次学到什么』……**不额外多跑一轮模型**」。
 * 所以本类不看 Key、不发请求，全部结论从 [SessionFacts]（消息原文、用过的工具、
 * 步数、结束状态）**确定性推导**。代价说在明处：自省是「骨架句」不是模型自述，
 * 真·模型自省要等用户自带 Key 的通道可用后再议（交付报告挂账）。
 *
 * ## 候选记忆的状态机（派工单：CONFIRMED 之前一律 PENDING）
 * 提炼出的候选**不落盘**（[MemoryStore.write] 只收 CONFIRMED），由
 * [BaopuRuntime] 挂在进程里等用户过一眼；用户点「记住」才转 CONFIRMED 落盘，
 * 点「不记」直接丢弃。没有确认就不落盘——这条纪律在 0b 就定了，本类不破它。
 *
 * ## 自省的落点（禁区）
 * 自省**不进 A 内核**（[Introspection.layer] 恒为 [PersonaLayer.C_PROJECT]）、
 * 不改工具集、不改纪律——那三样是派工单点名的禁区。判据 8 的断言钉两件事：
 * 恰好一条自省；`PersonaRules.canWrite(A_KERNEL)` 仍为 false。
 */
internal object MemoryRefine {

    /** 自省的 kind 字面值（派工单 §1 C 段原文：`kind='introspection'`）。 */
    const val INTROSPECTION_KIND = "introspection"

    /** 单次提炼最多产出几条候选（UI 同屏也只放得下 3 张，见 BaopuMemoryCard）。 */
    const val MAX_CANDIDATES = 3

    /** 自省正文字符上限（「短句」的机器判据）。 */
    const val INTROSPECTION_LIMIT = 60

    /** 句子最短长度（短于此的碎片不成句，提炼出来也是噪声）。 */
    private const val MIN_SENTENCE = 6

    /** 偏好类标记（命中 ⇒ 候选记忆 kind = PREFERENCE）。先查这一类。 */
    private val PREFERENCE_MARKERS = listOf(
        "以后", "请叫我", "我喜欢", "我不喜欢", "习惯", "偏好", "希望你", "都给我", "统一用", "换成",
    )

    /** 纠正类标记（命中 ⇒ 候选记忆 kind = LESSON）。 */
    private val LESSON_MARKERS = listOf(
        "不对", "错了", "别这样", "不要", "不能这", "禁止", "严禁", "说错了",
    )

    /** 句末标点（按它切句；中英混排都要认）。 */
    private val SENTENCE_END = charArrayOf('。', '！', '？', '!', '?', '\n')

    /**
     * 一次会话的**事实**（壳层从库里读出来喂进来；本类不碰 Android、不碰 IO）。
     *
     * @param userTexts 用户消息原文（按时间序；提炼只认用户说的话——助理说的话
     *   是产出不是规矩，拿它当记忆会把模型自己的措辞钉成用户偏好）
     * @param toolsUsed 本次会话用过的工具名（去重保序）
     * @param steps 本次会话跑到的最大步号（run_log 的 MAX(step)）
     * @param endState 结束状态字面值（`Done`/`Aborted`，与 run_log 收尾行一致）
     */
    data class SessionFacts(
        val sessionId: Long,
        val userTexts: List<String>,
        val toolsUsed: List<String> = emptyList(),
        val steps: Int = 0,
        val endState: String = "Done",
        val now: Long = 0L,
    )

    /**
     * 一条自省。[kind] 恒为 [INTROSPECTION_KIND]（派工单点名的字面值）；
     * [layer] 恒为 [PersonaLayer.C_PROJECT]——**故意不给 A_KERNEL 的路径**。
     */
    data class Introspection(
        val kind: String = INTROSPECTION_KIND,
        val text: String,
        val sourceSession: String,
        val layer: PersonaLayer = PersonaLayer.C_PROJECT,
    )

    /**
     * 提炼结果。
     * @param candidates 候选记忆（status = [MemoryStatus.PENDING]，未落盘）
     * @param introspection **恰好一条**自省（空会话也给一条——「这次什么都没发生」也是事实）
     * @param dropped 因不合格被丢掉的候选条数（[MemoryRules.validate] 拒的；
     *   静默丢弃会让用户以为提炼坏了，壳层要把这个数说出来）
     */
    data class RefineResult(
        val candidates: List<MemoryEntry>,
        val introspection: Introspection,
        val dropped: Int,
    )

    /**
     * 提炼（**纯函数、确定性**：同一份 [facts] 两次调用结果完全相同——
     * 包括 id：id = `now + 序号 + slug`，不掺随机数）。
     *
     * 顺序（派工单 §1 C 段）：先扫用户原句出候选（[MemoryStatus.PENDING]），
     * 再产**恰好一条**自省。候选过去重（同正文只留第一条）、过校验、截到
     * [MAX_CANDIDATES]——超限的**计数进 [RefineResult.dropped]**，不静默。
     */
    fun refine(facts: SessionFacts): RefineResult {
        val candidates = extractCandidates(facts)
        val lessons = candidates.count { it.kind == MemoryKind.LESSON }
        val introspection = buildIntrospection(facts, lessons)
        return RefineResult(
            candidates = candidates,
            introspection = introspection,
            dropped = droppedCount(facts, candidates),
        )
    }

    /** 真正干活的部分（拆出来只为让 [refine] 的返回值好写）。 */
    private fun extractCandidates(facts: SessionFacts): List<MemoryEntry> {
        val out = mutableListOf<MemoryEntry>()
        val seenBody = LinkedHashSet<String>()
        for (text in facts.userTexts) {
            for (sentence in sentences(text)) {
                if (sentence.length < MIN_SENTENCE) continue
                val marker = matchMarker(sentence) ?: continue
                if (!seenBody.add(sentence)) continue // 同正文去重（先见先立）
                val entry = buildCandidate(facts, sentence, marker)
                if (entry != null) out += entry
                if (out.size >= MAX_CANDIDATES) return out
            }
        }
        return out
    }

    /** 被丢掉的候选数 = 扫到的句子数 − 真正留下的（同正文去重与超限都算丢）。 */
    private fun droppedCount(facts: SessionFacts, kept: List<MemoryEntry>): Int {
        val seen = LinkedHashSet<String>()
        var matched = 0
        for (text in facts.userTexts) {
            for (sentence in sentences(text)) {
                if (sentence.length < MIN_SENTENCE) continue
                if (matchMarker(sentence) == null) continue
                matched++
                seen.add(sentence)
            }
        }
        // 丢的 = 命中标记的去重后条数 − 留下的条数（去重砍掉的 ＋ 超限砍掉的）
        return (seen.size - kept.size).coerceAtLeast(0)
    }

    /** 一条候选（过 [MemoryRules.validate]；不合格 ⇒ null，由 dropped 计数报账）。 */
    private fun buildCandidate(facts: SessionFacts, sentence: String, marker: Marker): MemoryEntry? {
        val kind = when (marker) {
            Marker.PREFERENCE -> MemoryKind.PREFERENCE
            Marker.LESSON -> MemoryKind.LESSON
        }
        val entry = MemoryEntry(
            id = MemoryRules.newId(facts.now + outSeq(facts, sentence), titleOf(sentence)),
            scope = MemoryScope.PROJECT,
            kind = kind,
            title = titleOf(sentence),
            body = sentence,
            tags = listOf("会话提炼"),
            sourceSession = facts.sessionId.toString(),
            status = MemoryStatus.PENDING,
            createdAt = facts.now,
            updatedAt = facts.now,
        )
        return when (val v = MemoryRules.validate(entry)) {
            is MemoryRules.Validation.Ok -> v.normalized
            is MemoryRules.Validation.Bad -> null
        }
    }

    /**
     * id 的序号位：同一句在同一会话里永远同一个号（按它在全部用户文本里的
     * 出现位次算，不掺随机）⇒ 两次提炼同输入同 id（判据：确定性）。
     */
    private fun outSeq(facts: SessionFacts, sentence: String): Int {
        var seq = 0
        for (text in facts.userTexts) {
            for (s in sentences(text)) {
                if (s == sentence) return seq
                seq++
            }
        }
        return seq
    }

    /** 标题 = 句子前 18 字（超了加省略号；记忆标题要短才记得住）。 */
    private fun titleOf(sentence: String): String =
        if (sentence.length <= 18) sentence else sentence.take(18) + "…"

    private enum class Marker { PREFERENCE, LESSON }

    /** 命中哪类标记（**先偏好后教训**——固定优先级，确定性；两类同时中算偏好）。 */
    private fun matchMarker(sentence: String): Marker? = when {
        PREFERENCE_MARKERS.any { sentence.contains(it) } -> Marker.PREFERENCE
        LESSON_MARKERS.any { sentence.contains(it) } -> Marker.LESSON
        else -> null
    }

    /** 切句（按句末标点；保留非空片段，去掉首尾空白）。 */
    internal fun sentences(text: String): List<String> {
        val out = mutableListOf<String>()
        val sb = StringBuilder()
        for (ch in text) {
            if (ch in SENTENCE_END) {
                val s = sb.toString().trim()
                if (s.isNotEmpty()) out += s
                sb.setLength(0)
            } else {
                sb.append(ch)
            }
        }
        val tail = sb.toString().trim()
        if (tail.isNotEmpty()) out += tail
        return out
    }

    // ── 自省（确定性骨架句；不跑模型） ─────────────────────────────────────

    /**
     * 造**恰好一条**自省。句式按结束状态分支（派工单：一条、短句）：
     * - `Aborted`：说清停在第几步、随时能接着问；
     * - 有教训候选：点出「做法被纠正了 N 处，已记下等你过目」；
     * - 用了工具：说清几步里用了什么；
     * - 纯对话：明说没有动工具。
     *
     * 空会话（无消息）也有一条：「这次没有对话」——不返回 null，UI 就不必
     * 判空；要展示与否由壳层看 candidates/introspection 一起决定。
     */
    fun buildIntrospection(facts: SessionFacts, lessonCount: Int): Introspection {
        val tools = facts.toolsUsed.filter { it.isNotBlank() }
        val toolsPart = if (tools.isEmpty()) "" else "（用了 ${tools.joinToString("、")}）"
        val text = when {
            facts.userTexts.isEmpty() && facts.steps <= 0 -> "这次没有对话，没有可提炼的。"
            facts.endState == "Aborted" ->
                "这次在第 ${facts.steps} 步停下$toolsPart——随时可以接着问。"
            lessonCount > 0 ->
                "这次有 $lessonCount 处做法被纠正，已记进候选等你过目。"
            tools.isNotEmpty() ->
                "这次顺利完成：${facts.steps} 步里用了 ${tools.joinToString("、")}。"
            else ->
                "这次是纯对话，${facts.steps} 步收尾，没有动工具。"
        }
        return Introspection(
            kind = INTROSPECTION_KIND,
            text = text.take(INTROSPECTION_LIMIT),
            sourceSession = facts.sessionId.toString(),
            layer = PersonaLayer.C_PROJECT,
        )
    }
}
