// 第六百七十批 · 神轨第二机（关系轨迹）门禁：转移表逐条钉住＋guard 证据＋
// 回流三条件＋T4 不可逆＋修饰 C1–C4 数据携带。拓扑锁死，改表即红。
package com.example.zhengdao.baopu.state.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelationTrackTest {

    // ── 表 fidelity：11 态 13 转移逐条钉住（数据源：附件-关系轨迹/关系轨迹-转移表.json） ──
    @Test
    fun table_is_pinned_eleven_states_thirteen_edges() {
        assertEquals(11, RelationState.entries.size)
        val pinned = listOf(
            RelationEdge(RelationState.T0, RelationState.T3, null),
            RelationEdge(RelationState.T3, RelationState.TT, null),
            RelationEdge(RelationState.TT, RelationState.T3, "没有"),
            RelationEdge(RelationState.TT, RelationState.T4, "出现"),
            RelationEdge(RelationState.T4, RelationState.TX, null),
            RelationEdge(RelationState.T4, RelationState.T5, null),
            RelationEdge(RelationState.TX, RelationState.TG, null),
            RelationEdge(RelationState.TG, RelationState.TE1, "缺一条"),
            RelationEdge(RelationState.TG, RelationState.T4P, "全中"),
            RelationEdge(RelationState.T4P, RelationState.TE2, "不回"),
            RelationEdge(RelationState.T4P, RelationState.T5, "复联"),
            RelationEdge(RelationState.T5, RelationState.T6, "靠日常做饭·留灯·买药"),
            RelationEdge(RelationState.T5, RelationState.TX, "崩了"),
        )
        assertEquals(pinned, RelationTable.EDGES)
        assertEquals(listOf("酒", "伤", "夜哨", "离别", "庆祝"), RelationTable.TRIGGER_CARDS)
        assertEquals(3, RelationTable.BACKFLOW_THREE.size)
        assertEquals(8, RelationTable.BACKFLOW_SUPPLEMENT_CARDS.size)
        assertTrue("形轨耦合缝留数据", RelationTable.COUPLING_TO_FORM_TRACK.isNotEmpty())
    }

    // ── 推进语义：边存在＋guard 证据齐才走；不发明自动推进 ────────────────────
    @Test
    fun step_requires_edge_and_guard_evidence() {
        val track = RelationTrack()
        assertFalse("不存在的边拒绝", track.step(RelationState.T5))
        assertTrue("无 guard 边不需证据", track.step(RelationState.T3))
        assertTrue(track.step(RelationState.TT))
        assertFalse("guard「出现」缺证据拒绝", track.step(RelationState.T4))
        assertTrue("出示触发牌证据放行", track.step(RelationState.T4, setOf("出现")))
        assertTrue("T4→T5 无 guard", track.step(RelationState.T5))
        assertFalse("T5→T6 缺日常证据拒绝", track.step(RelationState.T6))
        assertTrue(track.step(RelationState.T6, setOf("靠日常做饭·留灯·买药")))
    }

    @Test
    fun trigger_cards_are_the_guard_vocabulary() {
        val track = RelationTrack()
        track.step(RelationState.T3)
        track.step(RelationState.TT)
        for (card in RelationTable.TRIGGER_CARDS) {
            val probe = RelationTrack()
            probe.step(RelationState.T3)
            probe.step(RelationState.TT)
            assertFalse("牌名本身不是 guard 证据（guard 是「出现」）", probe.step(RelationState.T4, setOf(card)))
        }
        assertTrue(track.step(RelationState.T4, setOf("出现")))
    }

    // ── 回流三条件：全中才进 T4P，缺一条走 TE1（三条件锁死） ──────────────────
    @Test
    fun backflow_three_conditions_gate() {
        assertEquals("全中", RelationTable.backflowEvidence(innerScoreAtLeast60 = true, crossedFourth = true, endedBadly = true))
        assertEquals("缺一条", RelationTable.backflowEvidence(innerScoreAtLeast60 = false, crossedFourth = true, endedBadly = true))
        assertEquals("缺一条", RelationTable.backflowEvidence(innerScoreAtLeast60 = true, crossedFourth = false, endedBadly = true))
        assertEquals("缺一条", RelationTable.backflowEvidence(innerScoreAtLeast60 = true, crossedFourth = true, endedBadly = false))
    }

    @Test
    fun backflow_paths_in_machine() {
        val track = RelationTrack()
        track.step(RelationState.T3)
        track.step(RelationState.TT)
        track.step(RelationState.T4, setOf("出现"))
        track.step(RelationState.TX)
        track.step(RelationState.TG)
        assertFalse("缺证据不进 T4P", track.step(RelationState.T4P))
        assertTrue("缺一条 ⇒ TE1", track.step(RelationState.TE1, setOf("缺一条")))
        val full = RelationTrack()
        full.step(RelationState.T3)
        full.step(RelationState.TT)
        full.step(RelationState.T4, setOf("出现"))
        full.step(RelationState.TX)
        full.step(RelationState.TG)
        val evidence = RelationTable.backflowEvidence(innerScoreAtLeast60 = true, crossedFourth = true, endedBadly = true)
        assertTrue("全中 ⇒ T4P", full.step(RelationState.T4P, setOf(evidence)))
        assertTrue("T4P→T5 复联", full.step(RelationState.T5, setOf("复联")))
    }

    // ── irreversible：T4 越界一次不可逆 ──────────────────────────────────────
    @Test
    fun crossing_t4_is_irreversible() {
        val track = RelationTrack()
        assertFalse(track.crossed)
        track.step(RelationState.T3)
        track.step(RelationState.TT)
        track.step(RelationState.T4, setOf("出现"))
        assertTrue("越界标记置位", track.crossed)
        track.step(RelationState.TX)
        track.step(RelationState.TG)
        assertFalse("越界后不许回 T0", track.step(RelationState.T0))
        assertFalse("越界后不许回 T3", track.step(RelationState.T3))
        assertFalse("越界后不许回 TT", track.step(RelationState.TT))
        assertEquals(RelationState.TG, track.state)
    }

    // ── 修饰 C1–C4：只作数据携带（数值语义欠约束，不发明） ────────────────────
    @Test
    fun modifiers_carried_as_data() {
        assertEquals(listOf("C1", "C2", "C3", "C4"), RelationModifier.entries.map { it.name })
        assertEquals("全控机构", RelationModifier.C1.label)
        assertEquals("半封闭", RelationModifier.C2.label)
        assertEquals("开放", RelationModifier.C3.label)
        assertEquals("高压特殊", RelationModifier.C4.label)
        val track = RelationTrack(modifier = RelationModifier.C2)
        assertEquals(RelationModifier.C2, track.modifier)
        assertTrue("修饰不改变拓扑", track.step(RelationState.T3))
    }
}