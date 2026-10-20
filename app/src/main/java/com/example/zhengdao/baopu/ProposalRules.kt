// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第八十六批（2026-10-20，§B 92 进化地基 ④）：**纯判据**——已有记录进，候选出。
// 零 I/O、零网络、确定性输出；控制器只负责把库里的行读成这里的入参，落盘由
// `ProposalCandidateStore` 做（JSON，不碰技能/人设文件）。
//
// 三条来源（派工单 §1，最小规则，不猜）：
//   ① 学习卡：引擎收尾已经出的 `LearnProposal`（它本身就是「已有记录」）；
//   ② 用户纠正：同会话里同一对象被**明确否决** ≥2 次（skill_event decision ok=0）；
//   ③ 失败模式：同会话里同一工具重复失败 ≥2 次（run_log 工具行 ok=0）；
//   ④ 成功模式：同会话里同一技能**连续成功** ≥2 次（skill_event use ok=1）。
// 没有足够证据 ⇒ 不生成；阈值写死在本文件，报告逐条抄。
package com.example.zhengdao.baopu

internal object ProposalRules {

    /** 统计类候选的最小重复次数（同会话、同目标）。派工单 §1「至少 N 次/同会话」的 N。 */
    const val MIN_REPEATS = 2

    /** 学习卡候选的最小证据：事件 id 或「用户明说」卡；只有空卡不出。 */
    const val LEARN_MIN_EVIDENCE = 1

    /** 一次收尾最多生成几条（防候选刷屏；超出的计入 dropped，不静默）。 */
    const val MAX_CANDIDATES_PER_RUN = 5

    /** 每条候选最多引用几个事件 id（证据要能查，不该把整本账抄进来）。 */
    const val MAX_EVIDENCE_IDS = 8

    /** skill_event 一行（use / decision 共用形状；decision 才用 target）。 */
    data class SkillEvent(
        val id: Long,
        val name: String,
        val ok: Boolean,
        val atMs: Long,
        val step: Int? = null,
        val target: String = "skill",
    )

    /** run_log 一条工具步（只要工具名与成败；err 原文不进来）。 */
    data class ToolRun(
        val id: Long,
        val tool: String,
        val ok: Boolean,
        val atMs: Long,
    )

    /** 生成器入参：全部来自调用方已读出的记录，生成器自身不摸库。 */
    data class Input(
        val sessionId: Long,
        val nowMs: Long,
        val learn: List<SkillRefine.LearnProposal> = emptyList(),
        val skillUses: List<SkillEvent> = emptyList(),
        val decisions: List<SkillEvent> = emptyList(),
        val toolRuns: List<ToolRun> = emptyList(),
        val existingSkillBodies: Map<String, String> = emptyMap(),
        val personaText: String = "",
        val rejectedIds: Set<String> = emptySet(),
        val pendingIds: Set<String> = emptySet(),
    )

    /** 被跳过的原因（给人看；不落候选档）。 */
    data class Skip(val target: String, val reason: String)

    /** 生成结果：候选 + 跳过原因 + 超上限被丢的条数（不静默）。 */
    data class Result(
        val candidates: List<ProposalCandidate>,
        val skipped: List<Skip>,
        val dropped: Int,
    )

    /**
     * 稳定 id：与第五十七批采纳/丢弃留痕同一把尺（名称＋正文摘要 SHA-256 前 16 位）。
     *
     * 学习卡候选的 id 因此**恰好等于** [SkillRefine.dedupeKeyOf]——旧账里被丢过的
     * 同一条候选，这一轮直接挡掉。
     */
    fun idOf(target: String, payload: String): String = SkillRefine.dedupeKey(target, payload)

    fun generate(input: Input): Result {
        val out = LinkedHashMap<String, ProposalCandidate>()
        val skipped = mutableListOf<Skip>()
        var dropped = 0

        fun offer(c: ProposalCandidate) {
            if (c.payload.isBlank()) {
                skipped += Skip(c.target, "正文为空，不生成")
                return
            }
            if (c.id in input.rejectedIds) {
                skipped += Skip(c.target, "同 hash 已被拒绝过")
                return
            }
            if (c.id in input.pendingIds) {
                skipped += Skip(c.target, "同 hash 已在 PENDING")
                return
            }
            if (out.containsKey(c.id)) {
                skipped += Skip(c.target, "本轮重复")
                return
            }
            if (alreadyPresent(c, input)) {
                skipped += Skip(c.target, "已有技能/人设已包含这条内容")
                return
            }
            if (out.size >= MAX_CANDIDATES_PER_RUN) {
                dropped++
                return
            }
            out[c.id] = c
        }

        // ① 学习卡 → 技能正文候选（payload 用卡上的 content；id 与丢弃留痕同一把尺）
        val sessionRunIds = input.toolRuns.takeLast(MAX_EVIDENCE_IDS / 2).map { it.id }
        val sessionEventIds = (input.skillUses.map { it.id } + input.decisions.map { it.id })
            .distinct()
            .takeLast(MAX_EVIDENCE_IDS - sessionRunIds.size)
        for (p in input.learn) {
            val hasIds = sessionRunIds.isNotEmpty() || sessionEventIds.isNotEmpty()
            if (LEARN_MIN_EVIDENCE > 0 && !hasIds && !p.fromUser) {
                skipped += Skip(SkillRefine.nameOf(p), "学习卡没有事件证据，也不是用户明说")
                continue
            }
            offer(
                ProposalCandidate(
                    id = SkillRefine.dedupeKeyOf(p),
                    kind = ProposalKind.SKILL_BODY,
                    target = SkillRefine.nameOf(p),
                    payload = p.content,
                    evidence = ProposalEvidence(
                        runLogIds = sessionRunIds,
                        skillEventIds = sessionEventIds,
                        atMs = input.nowMs,
                    ),
                    createdAt = input.nowMs,
                ),
            )
        }

        // ② 用户纠正：同会话、同一技能名被明确否决 ≥ MIN_REPEATS 次 ⇒ 技能规则
        val rejected = input.decisions.filter { !it.ok }
        val byName = rejected.filter { it.name.isNotBlank() }.groupBy { it.name }
        for ((name, rows) in byName) {
            if (rows.count { it.target != "memory" && it.target != "persona" } < MIN_REPEATS) continue
            val skillRows = rows.filter { it.target != "memory" && it.target != "persona" }
            offer(
                ProposalCandidate(
                    id = idOf(name, correctionPayload(name)),
                    kind = ProposalKind.SKILL_BODY,
                    target = name,
                    payload = correctionPayload(name),
                    evidence = ProposalEvidence(
                        skillEventIds = skillRows.map { it.id }.takeLast(MAX_EVIDENCE_IDS),
                        atMs = skillRows.maxOfOrNull { it.atMs } ?: input.nowMs,
                    ),
                    createdAt = input.nowMs,
                ),
            )
        }
        // ②b 跨对象的纠正 / 人设类决策 ⇒ 人设追加（同一会话里至少两条不同对象的否决）
        val memoryRejects = rejected.filter { it.target == "memory" || it.target == "persona" }
        val distinctSkillNames = byName.keys.filter { name ->
            byName.getValue(name).count { it.target != "memory" && it.target != "persona" } >= MIN_REPEATS
        }.size
        if (memoryRejects.size >= MIN_REPEATS || distinctSkillNames >= MIN_REPEATS) {
            val payload = personaCorrectionPayload()
            offer(
                ProposalCandidate(
                    id = idOf(PERSONA_TARGET, payload),
                    kind = ProposalKind.PERSONA_APPEND,
                    target = PERSONA_TARGET,
                    payload = payload,
                    evidence = ProposalEvidence(
                        skillEventIds = rejected.map { it.id }.takeLast(MAX_EVIDENCE_IDS),
                        atMs = rejected.maxOfOrNull { it.atMs } ?: input.nowMs,
                    ),
                    createdAt = input.nowMs,
                ),
            )
        }

        // ③ 失败模式：同会话、同一工具重复失败 ≥ MIN_REPEATS 次 ⇒ 工具使用约束
        val failures = input.toolRuns.filter { !it.ok && isRealTool(it.tool) }.groupBy { it.tool }
        for ((tool, rows) in failures) {
            if (rows.size < MIN_REPEATS) continue
            val payload = failurePayload(tool)
            offer(
                ProposalCandidate(
                    id = idOf(tool, payload),
                    kind = ProposalKind.TOOL_RULE,
                    target = tool,
                    payload = payload,
                    evidence = ProposalEvidence(
                        runLogIds = rows.map { it.id }.takeLast(MAX_EVIDENCE_IDS),
                        atMs = rows.maxOfOrNull { it.atMs } ?: input.nowMs,
                    ),
                    createdAt = input.nowMs,
                ),
            )
        }

        // ④ 成功模式：同会话、同一技能连续成功 ≥ MIN_REPEATS 次，且工具链也有成功 ⇒ 正文补充
        val successfulToolIds = input.toolRuns.filter { it.ok && isRealTool(it.tool) }.map { it.id }
        for ((name, rows) in input.skillUses.filter { it.name.isNotBlank() }.groupBy { it.name }) {
            val streak = longestSuccessStreak(rows)
            if (streak.size < MIN_REPEATS) continue
            if (successfulToolIds.size < MIN_REPEATS) {
                skipped += Skip(name, "技能连续成功，但同会话工具链证据不足")
                continue
            }
            val payload = successPayload(name)
            offer(
                ProposalCandidate(
                    id = idOf(name, payload),
                    kind = ProposalKind.SKILL_BODY,
                    target = name,
                    payload = payload,
                    evidence = ProposalEvidence(
                        runLogIds = successfulToolIds.takeLast(
                            (MAX_EVIDENCE_IDS - streak.size).coerceAtLeast(0),
                        ),
                        skillEventIds = streak.map { it.id }.takeLast(MAX_EVIDENCE_IDS),
                        atMs = streak.last().atMs,
                    ),
                    createdAt = input.nowMs,
                ),
            )
        }

        return Result(out.values.toList(), skipped, dropped)
    }

    /** 人设追加的落点名（与技能名区分；本批不写盘）。 */
    const val PERSONA_TARGET = "人设"

    /**
     * 技能规则正文（不含用户原话、不含次数）。
     *
     * **为什么正文里不写次数**：id = hash(target, payload)。把「2 次」写进正文，
     * 同一会话里出现第 3 次否决就换了 hash ⇒ 同一件事生成第二条候选。
     * 次数属于**证据**（`skillEventIds` 引用的那几行），不属于建议正文。
     */
    internal fun correctionPayload(name: String): String =
        "用户明确否决过「$name」这条做法。" +
            "技能规则：这条做法不要作为默认流程；下次遇到同类任务先确认再动手。"

    /** 人设追加正文（不引用被否提案的正文，也不写条数）。 */
    internal fun personaCorrectionPayload(): String =
        "用户明确否决过多条技能提案。" +
            "人设追加：提出技能/做法前先确认范围与命名；被否决过的写法不要换个名字再提。"

    /** 工具约束正文（只引用工具名；次数在证据里）。 */
    internal fun failurePayload(tool: String): String =
        "工具「$tool」在同一会话里重复失败。" +
            "工具使用约束：同一工具连续失败两次就停下，换路径/换参数或问人，不要原样重试。"

    /** 技能正文补充正文（只引用技能名；连续成功的次数在证据里）。 */
    internal fun successPayload(name: String): String =
        "「$name」在同一会话内连续成功，同会话工具链也成功。" +
            "技能正文补充：把这次成功的步骤顺序写成固定流程，下次同类任务先按它做。"

    private fun alreadyPresent(c: ProposalCandidate, input: Input): Boolean {
        val p = c.payload.trim()
        if (p.isEmpty()) return true
        return when (c.kind) {
            ProposalKind.SKILL_BODY ->
                input.existingSkillBodies[c.target]?.contains(p) == true
            ProposalKind.PERSONA_APPEND, ProposalKind.TOOL_RULE -> input.personaText.contains(p)
        }
    }

    /** 最长连续成功段（按事件 id 时间序；失败会截断）。 */
    private fun longestSuccessStreak(rows: List<SkillEvent>): List<SkillEvent> {
        var best = emptyList<SkillEvent>()
        val cur = mutableListOf<SkillEvent>()
        for (r in rows.sortedBy { it.id }) {
            if (r.ok) {
                cur += r
                if (cur.size > best.size) best = cur.toList()
            } else {
                cur.clear()
            }
        }
        return best
    }

    /** run_log 里的内部原因码（compression_*）不是工具，不能生成工具约束。 */
    private fun isRealTool(name: String): Boolean =
        name.isNotBlank() && !name.startsWith("compression")
}
