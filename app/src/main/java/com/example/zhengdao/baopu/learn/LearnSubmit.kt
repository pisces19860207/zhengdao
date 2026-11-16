// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百五十四批 · 模型提交口（`learn_draft_submit` 的落点）。
//
// 背景：624 阶段二把「模型可归纳」的口挂账了——[DraftInducer] 是**脚本侧**归纳，
// 模型侧没有提交通道。本文件补的正是这条通道，但**只补通道，不重做阶段二**：
//
//   · 提交的形状 = 草稿区落盘的形状（`zl-draft/1`）：模型交什么、盘上就是什么，
//     **没有第二套 JSON**，解析也就只有 [SkillDraft.fromJson] 一处；
//   · **不直接写盘**：本文件只做「解析 → 结构门 → 交草稿区」。[SkillDraftStore] 是
//     唯一写口（它写 `草案/`，永远不写 `技能/`）；本类一个字节都不落；
//   · **不转正**：进的是草稿区，转正仍要人批（ObserveCenter.confirmDraft，与 643 同一条路）；
//   · **轨迹引用必须对得上**：草案得像 231 原文那样「每步 evidence 指回观察账」，
//     整体还要指出是哪一次教学会话（`sessions`）；指不到 ⇒ 拒收。
//
// 拒收原因是**分类的**（派工单出参原话：缺定位／缺证据／结构不合法／含不可逆动作）：
// 结构门给的是逐条细账（[SkillDraftRules]），本类把它翻成「欠哪一类」的人话——
// 分类词只在这里加，判据本体仍只有 [SkillDraftRules.validate] 一处。
package com.example.zhengdao.baopu.learn

import org.json.JSONArray
import org.json.JSONObject

/** 模型提交的一步：解析 → 结构门 → 草稿区（不写盘、不转正）。 */
internal object LearnSubmit {

    /** 拒收原因的分类词（判据②③④断言到这几个词上，改字＝改判据）。 */
    const val REASON_SHAPE = "结构不合法"
    const val REASON_LOCATOR = "缺定位"
    const val REASON_EVIDENCE = "缺证据"
    const val REASON_IRREVERSIBLE = "不可逆"
    const val REASON_SESSION = "轨迹引用"

    /** 提交结果（闭集两态）。 */
    sealed interface Outcome {
        /** 收进草稿区（[withN] ≥2 ＝ 与同意图草案归并后的观察次数）。 */
        data class Accepted(val draft: SkillDraft, val withN: Int, val merged: Boolean) : Outcome

        /** 拒收（每条都已带分类前缀；原因具体，能照着改）。 */
        data class Rejected(val reasons: List<String>) : Outcome
    }

    /**
     * 提交。
     *
     * @param payload 模型交的草案（`zl-draft/1` 形状；`schema`／`id`／`mergedFrom` 可省）
     * @param newId 新草案 id（调用方给，草稿区用它做文件名）
     * @param lastSessionId 最近一次收口的教学会话 id（null＝还没有过教学会话）
     * @param store 草稿区（唯一写口）
     */
    fun submit(
        payload: JSONObject,
        newId: String,
        lastSessionId: String?,
        store: SkillDraftStore,
    ): Outcome {
        val refs = stringList(payload.optJSONArray("sessions"))
        sessionProblem(refs, lastSessionId)?.let { return Outcome.Rejected(listOf(it)) }
        val withRefs = if (refs.isEmpty() && lastSessionId != null) listOf(lastSessionId) else refs
        val draft = parse(payload, newId, withRefs)
            ?: return Outcome.Rejected(
                listOf("$REASON_SHAPE：草案读不出来（goal/params/skeleton 的字段名或类型不对，按 zl-draft/1 交）"),
            )
        when (val check = SkillDraftRules.validate(draft)) {
            is DraftCheck.Ok -> Unit
            is DraftCheck.Invalid -> return Outcome.Rejected(check.problems.map { classify(it) })
        }
        return when (val added = store.add(draft, newId)) {
            is SkillDraftStore.AddOutcome.Created ->
                Outcome.Accepted(added.draft, added.draft.mergedFrom, merged = false)
            is SkillDraftStore.AddOutcome.Merged ->
                Outcome.Accepted(added.draft, added.withN, merged = true)
            is SkillDraftStore.AddOutcome.Rejected -> Outcome.Rejected(added.problems.map { classify(it) })
            is SkillDraftStore.AddOutcome.Full ->
                Outcome.Rejected(listOf("$REASON_SHAPE：草稿区满了（${added.cap} 条）——先在人那边弃掉几条旧草案"))
        }
    }

    /**
     * 轨迹引用判定：没有会话可指 ⇒ 拒；给了但与最近一次会话对不上 ⇒ 拒。
     *
     * 为什么「对不上」也算拒：证据链的意义是「点开能回到那一行」（231 原文），指到一次
     * 不存在的会话等于假账。旧会话 24h 自清，所以这里只认**最近一次**（口径见报告）。
     */
    private fun sessionProblem(refs: List<String>, lastSessionId: String?): String? {
        if (refs.isEmpty() && lastSessionId == null) {
            return "$REASON_SESSION：指不到任何观察会话（先在玄览里操作几步，攒下一次教学轨迹再来）"
        }
        if (refs.isEmpty() || lastSessionId == null) return null
        val ok = refs.any { sameSession(it, lastSessionId) }
        return if (ok) null else "$REASON_SESSION：对不上最近一次教学会话（$lastSessionId）——" +
            "草案必须指回真跑过的那段轨迹，旧会话 24 小时就清了"
    }

    /** 会话引用同判：`<id>` / `会话-<id>` / `观察/会话-<id>.jsonl[#seqN]` 都算同一个。 */
    fun sameSession(ref: String, id: String): Boolean {
        var s = ref.trim()
        s = s.substringBefore('#')
        s = s.removePrefix("观察/")
        s = s.removePrefix(ObserveSession.FILE_PREFIX)
        s = s.removeSuffix(ObserveSession.FILE_SUFFIX)
        return s == id
    }

    /**
     * 解析：把模型交的 JSON 补齐三个「盘上必须有、模型可以省」的键，再走唯一解析器。
     *
     * 为什么补而不是另写一个解析器：两套 JSON 就是两套判据的温床（94 批的教训）；
     * 这里只补默认值，字段语义仍由 [SkillDraft.fromJson] 一处说了算。
     */
    private fun parse(payload: JSONObject, newId: String, sessions: List<String>): SkillDraft? = runCatching {
        val o = JSONObject(payload.toString())
        if (!o.has("schema")) o.put("schema", DRAFT_SCHEMA)
        if (o.optString("id").isBlank()) o.put("id", newId)
        if (!o.has("mergedFrom")) o.put("mergedFrom", 1)
        if (sessions.isNotEmpty()) o.put("sessions", JSONArray(sessions))
        SkillDraft.fromJson(o)
    }.getOrNull()

    /** 结构门的逐条细账 → 「欠哪一类」的人话（分类词不进结构门，门里只有判据本体）。 */
    fun classify(problem: String): String = when {
        problem.contains(SkillDraftRules.BLANK_LOCATOR) -> "$REASON_LOCATOR：$problem"
        problem.contains(SkillDraftRules.NO_EVIDENCE) -> "$REASON_EVIDENCE：$problem"
        problem.contains(SkillDraftRules.IRREVERSIBLE) -> "$REASON_IRREVERSIBLE：$problem"
        else -> "$REASON_SHAPE：$problem"
    }

    private fun stringList(a: JSONArray?): List<String> =
        a?.let { arr -> (0 until arr.length()).map { arr.optString(it).trim() }.filter { it.isNotEmpty() } }
            ?: emptyList()
}
