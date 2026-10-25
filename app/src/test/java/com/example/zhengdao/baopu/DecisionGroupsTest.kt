// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零八批 §2.5：记录页**按对象分组**的六条规则，逐条钉死。
//   ① 折叠态只有组卡头；② 有可撤销的组优先＋组内最近时间排序、空组不出现；
//   ③ 组内行可单条跳/撤销（撤销后计数由重算跟上）；④ 四个筛选 chips 先筛再分组；
//   ⑤ 组数上限 6，第 7 组起折进「更多」；⑥ 单页渲染 ≤50 行。
// 负例（各红）：组内混进别组的 kind／组头计数与真实条数不一致／空组出现／一次渲染 >50 行。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionGroupsTest {

    private val nowMs = 1_760_000_000_000L
    private val day = 86_400_000L

    private fun rec(
        id: String,
        kind: DecisionKind,
        atMs: Long,
        reversible: Boolean? = null,
        session: Long = 3L,
    ) = DecisionLedger.build(
        DecisionDraft(
            kind = kind,
            actor = DecisionActor.AUTO,
            reason = "$id 的动作",
            evidence = "ev:$id",
            before = "a",
            after = "b",
            atMs = atMs,
            sessionId = session,
            rollbackRef = "/x/$id",
            reversible = reversible,
        ),
    )!!

    /** 造 20 条：4 组 × 5 条（技能/授权/产物/评测各 5）。 */
    private fun twentyRecords(): List<DecisionRecord> {
        val kinds = listOf(
            DecisionKind.SKILL_PROMOTE,
            DecisionKind.READ_SCOPE,
            DecisionKind.ARTIFACT_DELETE,
            DecisionKind.EVAL_VERDICT,
        )
        val out = mutableListOf<DecisionRecord>()
        kinds.forEachIndexed { g, k ->
            repeat(5) { i ->
                out += rec("g$g-i$i", k, nowMs - (g * 5 + i) * 1_000L)
            }
        }
        return out
    }

    @Test
    fun `G1_造20条4组乘5条_首屏只有4张组卡头_展开某组才出现组内行`() {
        val records = twentyRecords()
        val folded = foldDecisionRecords(records, nowMs = nowMs)
        assertEquals("首屏 4 张组卡", 4, folded.cards.size)
        assertEquals("折叠态一行都不渲染", 0, folded.renderedRowCount)
        assertTrue("折叠态确实有行藏着", folded.cards.all { it.rows.isEmpty() })

        val opened = foldDecisionRecords(records, nowMs = nowMs, expanded = setOf(DecisionGroup.SKILL))
        assertEquals("只有展开的那组出现行", 5, opened.renderedRowCount)
        assertEquals(5, opened.cards.single { it.group == DecisionGroup.SKILL }.rows.size)
        assertEquals(0, opened.cards.filter { it.group != DecisionGroup.SKILL }.sumOf { it.rows.size })
    }

    @Test
    fun `G2_组头计数等于真实条数_组内行加未渲染数对得上`() {
        val records = twentyRecords()
        val folded = foldDecisionRecords(records, nowMs = nowMs, expanded = setOf(DecisionGroup.SKILL))
        folded.cards.forEach { c ->
            assertEquals("组头计数", 5, c.count)
            assertEquals("行 + 没渲染的 = 真实条数", c.count, c.rows.size + c.hiddenByPage)
        }
    }

    @Test
    fun `G3_负例_组必须纯净_每个组只装自己那类`() {
        val records = twentyRecords()
        val folded = foldDecisionRecords(records, nowMs = nowMs)
        folded.cards.forEach { c ->
            c.all.forEach { r ->
                assertEquals("${r.kind.wire} 混进了 ${c.group.wire} 组", c.group, r.kind.group)
            }
        }
        // 十类的归属表钉死（谁哪天把某类挪进别组就红）
        assertEquals(DecisionGroup.SKILL, DecisionKind.SKILL_PROMOTE.group)
        assertEquals(DecisionGroup.SKILL, DecisionKind.SKILL_AUTO_WRITE.group)
        assertEquals(DecisionGroup.SKILL, DecisionKind.SKILL_RETIRE.group)
        assertEquals(DecisionGroup.SKILL, DecisionKind.SKILL_DRAFT_RETIRE.group)
        assertEquals(DecisionGroup.GRANT, DecisionKind.READ_SCOPE.group)
        assertEquals(DecisionGroup.ARTIFACT, DecisionKind.ARTIFACT_DELETE.group)
        assertEquals(DecisionGroup.ARTIFACT, DecisionKind.AUTO_ACT.group)
        assertEquals(DecisionGroup.DIARY, DecisionKind.DIARY_MAINTAIN.group)
        assertEquals(DecisionGroup.DIARY, DecisionKind.DIARY_PURGE.group)
        assertEquals(DecisionGroup.EVAL, DecisionKind.EVAL_VERDICT.group)
    }

    @Test
    fun `G4_负例_空组不出现`() {
        val records = listOf(rec("only-skill", DecisionKind.SKILL_PROMOTE, nowMs))
        val folded = foldDecisionRecords(records, nowMs = nowMs)
        assertEquals(1, folded.cards.size)
        assertEquals(DecisionGroup.SKILL, folded.cards.single().group)
        assertTrue("没记录的组一个都不许冒出来", folded.cards.none { it.count == 0 })
    }

    @Test
    fun `G5_排序_有可撤销的组优先_其次组内最近时间`() {
        val records = listOf(
            rec("eval-new", DecisionKind.EVAL_VERDICT, nowMs), // 不可撤销，但最近
            rec("promo-old", DecisionKind.SKILL_PROMOTE, nowMs - 10 * day), // 可撤销，很旧
            rec("purge-old", DecisionKind.DIARY_PURGE, nowMs - 20 * day), // 不可撤销，最旧
        )
        val folded = foldDecisionRecords(records, nowMs = nowMs)
        assertEquals("可撤销的组排最前", DecisionGroup.SKILL, folded.cards.first().group)
        assertEquals("其余按最近时间", DecisionGroup.EVAL, folded.cards[1].group)
        assertEquals(DecisionGroup.DIARY, folded.cards[2].group)
    }

    @Test
    fun `G6_组数上限_超出上限的组折进更多_待抽检组排最前`() {
        // 现仓一共只有 6 个组（5 对象组 + 待抽检），上限也是 6 ⇒ 真数据永远溢不出来。
        // 所以这里用 maxGroups 参数把上限压到 4，证明「折进更多」这条机制**真的在**
        // （以后加组第一天就会用上；上限值本身由 MAX_GROUPS 常量管）。
        val records = listOf(
            rec("a", DecisionKind.SKILL_PROMOTE, nowMs),
            rec("b", DecisionKind.SKILL_AUTO_WRITE, nowMs - 1_000L),
            rec("c", DecisionKind.READ_SCOPE, nowMs - 2_000L),
            rec("d", DecisionKind.ARTIFACT_DELETE, nowMs - 3_000L),
            rec("e", DecisionKind.EVAL_VERDICT, nowMs - 4_000L),
            rec("f", DecisionKind.DIARY_PURGE, nowMs - 5_000L),
            rec("g", DecisionKind.DIARY_MAINTAIN, nowMs - 6_000L),
        )
        // 只把「真删」那条算成待抽检（配额判定本身在 DecisionSamplingTest 里，这里只验分组）
        val pending = setOf(records.first { it.kind == DecisionKind.DIARY_PURGE }.id)
        val full = foldDecisionRecords(records, nowMs = nowMs, pendingIds = pending)
        assertEquals("现仓六个组全在", 6, full.cards.size)
        assertFalse("上限 6 ⇒ 不溢出", full.hasOverflow)
        assertEquals("待抽检组排最前（要你动手的队列）", DecisionGroup.SAMPLING, full.cards.first().group)

        val squeezed = foldDecisionRecords(records, nowMs = nowMs, pendingIds = pending, maxGroups = 4)
        assertEquals("上限 4 ⇒ 只显示 4 张组卡", 4, squeezed.cards.size)
        assertTrue("多出来的要折进「更多」", squeezed.hasOverflow)
        assertEquals(2, squeezed.overflowGroups.size)
        assertTrue("更多里要说清还有多少条", squeezed.overflowRecords > 0)
    }

    @Test
    fun `G7_负例_一次渲染不许超过50行`() {
        // 5 组 × 40 条 = 200 条，全展开
        val kinds = listOf(
            DecisionKind.SKILL_PROMOTE, DecisionKind.READ_SCOPE, DecisionKind.ARTIFACT_DELETE,
            DecisionKind.EVAL_VERDICT, DecisionKind.DIARY_PURGE,
        )
        val records = mutableListOf<DecisionRecord>()
        kinds.forEachIndexed { g, k -> repeat(40) { i -> records += rec("g$g-$i", k, nowMs - (g * 40 + i) * 10L) } }
        val folded = foldDecisionRecords(
            records = records,
            nowMs = nowMs,
            expanded = DecisionGroup.entries.toSet(),
        )
        assertTrue("渲染行数必须 ≤ PAGE_LIMIT，实际 ${folded.renderedRowCount}", folded.renderedRowCount <= PAGE_LIMIT)
        assertTrue("超额的要进「组内还有 N 条」", folded.cards.any { it.hiddenByPage > 0 })
    }

    @Test
    fun `G8_四个筛选_先筛再分组`() {
        val records = listOf(
            rec("today-this", DecisionKind.SKILL_PROMOTE, nowMs, session = 3L),
            rec("old-this", DecisionKind.SKILL_PROMOTE, nowMs - 5 * day, session = 3L),
            rec("old-other", DecisionKind.SKILL_PROMOTE, nowMs - 5 * day, session = 9L),
            rec("today-other", DecisionKind.ARTIFACT_DELETE, nowMs, session = 9L, reversible = false),
        )
        assertEquals(4, foldDecisionRecords(records, DecisionFilter.ALL, nowMs, 3L).cards.sumOf { it.count })
        val today = foldDecisionRecords(records, DecisionFilter.TODAY, nowMs, 3L)
        assertEquals("今天只剩两条", 2, today.cards.sumOf { it.count })
        val mine = foldDecisionRecords(records, DecisionFilter.THIS_SESSION, nowMs, 3L)
        assertEquals("本会话两条（另两条属于别的会话）", 2, mine.cards.sumOf { it.count })
        val undoable = foldDecisionRecords(records, DecisionFilter.REVERSIBLE, nowMs, 3L)
        assertEquals("仅可撤销三条（那条产物删被降级）", 3, undoable.cards.sumOf { it.count })
    }

    @Test
    fun `G9_撤销一条后_该组可撤销数减一_回滚行落进同组`() {
        val records = twentyRecords().toMutableList()
        val before = foldDecisionRecords(records, nowMs = nowMs).cards
            .single { it.group == DecisionGroup.SKILL }
        assertEquals(5, before.reversibleCount)

        val target = records.first { it.kind == DecisionKind.SKILL_PROMOTE && it.reversible }
        // 回滚一条 = 目标变不可回滚 + 多一条 rollback_of（组跟着目标）
        val rolled = DecisionLedger.build(
            DecisionDraft(
                kind = DecisionKind.ROLLBACK_OF,
                actor = DecisionActor.USER,
                reason = "回滚：${target.reason}",
                evidence = target.id,
                before = target.after,
                after = target.before,
                atMs = nowMs,
                rollbackRef = target.rollbackRef,
                rollbackOf = target.id,
                groupOverride = target.group,
            ),
        )!!
        val after = foldDecisionRecords(
            records.map { if (it.id == target.id) it.copy(reversible = false) else it } + rolled,
            nowMs = nowMs,
        ).cards.single { it.group == DecisionGroup.SKILL }
        assertEquals("可撤销数减一", 4, after.reversibleCount)
        assertEquals("条数加一（多出那条回滚行）", 6, after.count)
    }

    @Test
    fun `G10_待抽检组的行只在展开后出现_且不属于别的组`() {
        val purge = rec("purge", DecisionKind.DIARY_PURGE, nowMs)
        val folded = foldDecisionRecords(
            records = listOf(purge),
            nowMs = nowMs,
            pendingIds = setOf(purge.id),
        )
        assertEquals(1, folded.cards.size)
        assertEquals(DecisionGroup.SAMPLING, folded.cards.single().group)
        // 待抽检的那条已被收进抽检组 ⇒ 日记组不再出现（不重复列）
        assertFalse(folded.cards.any { it.group == DecisionGroup.DIARY })
    }
}
