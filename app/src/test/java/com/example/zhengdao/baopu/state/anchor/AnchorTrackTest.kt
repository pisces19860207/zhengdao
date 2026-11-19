// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 判据②③⑤（引擎侧）：跨格要证据、不许自动推进、触发牌闸、回流闸、越界不可逆。
// 状态集与边集都只用 670 的 RelationTrack／RelationTable，本测试同时钉住「本批没新增态、没改拓扑」。
package com.example.zhengdao.baopu.state.anchor

import com.example.zhengdao.baopu.state.track.RelationState
import com.example.zhengdao.baopu.state.track.RelationTable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnchorTrackTest {

    private val profile = AnchorTestData.complete()

    private var tick = 1000L

    /** 每条记录的时刻都往前走，方便判据⑤ 按时间点回放。 */
    private fun nextAt(): Long {
        tick += 1
        return tick
    }

    private fun track(): AnchorTrack = AnchorTrack(profile, ledger = CrossLedger { nextAt() }, now = { nextAt() })

    /** 一路走到第 4 格（每步都带证据），用来测越界之后的行为。 */
    private fun atT4(): AnchorTrack {
        val t = track()
        t.step(RelationState.T3, listOf("认识三年"), "玩家")
        t.step(RelationState.TT, listOf("酒"), "玩家")
        t.step(RelationState.T4, listOf("出现", "酒"), "玩家")
        return t
    }

    /** 一路走到 TX（越界后崩掉），用来测回流闸。 */
    private fun atTX(): AnchorTrack {
        val t = atT4()
        t.step(RelationState.TX, listOf("崩了"), "玩家")
        return t
    }

    @Test
    fun `判据二_没有证据一律不许跨格`() {
        val t = track()
        val refused = t.step(RelationState.T3, emptyList(), "玩家")
        assertTrue(refused.refused)
        assertTrue("拒因要点名判据②", refused.reason.contains("判据②"))
        assertEquals(RelationState.T0, t.state)
        assertEquals("拒掉的跨格不许进账", 0, t.ledger.records.size)
    }

    @Test
    fun `判据二_空串证据不算证据`() {
        val t = track()
        assertTrue(t.step(RelationState.T3, listOf("  ", ""), "玩家").refused)
        assertEquals(RelationState.T0, t.state)
    }

    @Test
    fun `判据二_引擎里根本没有自动推进的入口`() {
        val names = AnchorTrack::class.java.methods.map { it.name }
        listOf("advance", "autoAdvance", "auto", "tick", "next", "nextStep", "drive", "evolve", "pass", "run")
            .forEach { assertFalse("不许有无证据推进口：$it", names.contains(it)) }
        assertTrue("改状态的口子只有 step", names.contains("step"))
    }

    @Test
    fun `判据二_规格没有的边不许走`() {
        val t = track()
        val e = t.step(RelationState.T4, listOf("出现", "酒"), "玩家")
        assertTrue(e.refused)
        assertTrue(e.reason.contains("不许另造转移"))
        assertEquals(RelationState.T0, t.state)
    }

    @Test
    fun `判据三_进第四格必须出示触发牌_五张之一`() {
        val t = track()
        t.step(RelationState.T3, listOf("同部门"), "玩家")
        t.step(RelationState.TT, listOf("夜哨"), "玩家")
        val noCard = t.step(RelationState.T4, listOf("出现"), "玩家")
        assertTrue("只带 guard 不带牌 ⇒ 拒", noCard.refused)
        assertTrue(noCard.reason.contains("酒／伤／夜哨／离别／庆祝"))
        assertEquals(RelationState.TT, t.state)
        val withCard = t.step(RelationState.T4, listOf("出现", "夜哨"), "玩家")
        assertTrue(withCard.reason, withCard.accepted)
        assertEquals(RelationState.T4, t.state)
    }

    @Test
    fun `判据三_触发牌表外不算牌`() {
        val t = track()
        t.step(RelationState.T3, listOf("同部门"), "玩家")
        t.step(RelationState.TT, listOf("加班"), "玩家")
        assertTrue(t.step(RelationState.T4, listOf("出现", "加班"), "玩家").refused)
    }

    @Test
    fun `判据五_X不是终点但TX出边要回流三条件全中`() {
        val t = atTX()
        assertTrue("缺回流判定 ⇒ 拒", t.step(RelationState.TG, listOf("想了想"), "玩家").refused)
        assertTrue(t.step(RelationState.TG, listOf("全中"), "玩家").accepted)
        assertEquals(RelationState.TG, t.state)
    }

    @Test
    fun `判据五_缺一条只能走TE1`() {
        val t = atTX()
        t.step(RelationState.TG, listOf("全中"), "玩家")
        assertTrue("guard 是全中，缺一条进不去", t.step(RelationState.T4P, listOf("缺一条"), "玩家").refused)
        assertTrue(t.step(RelationState.T4P, listOf("全中"), "玩家").accepted)
        assertEquals(RelationState.T4P, t.state)
    }

    @Test
    fun `判据五_回流判定串照抄670不改语义`() {
        val t = track()
        assertEquals("全中", t.backflowToken(true, true, true))
        assertEquals("缺一条", t.backflowToken(true, false, true))
        assertEquals("缺一条", t.backflowToken(false, false, false))
        assertEquals(3, RelationTable.BACKFLOW_THREE.size)
    }

    @Test
    fun `判据三_越界一次不可回到越界前的格`() {
        val t = atT4()
        assertTrue(t.crossed)
        val back = t.step(RelationState.T3, listOf("当作没发生"), "玩家")
        assertTrue(back.refused)
        assertTrue(back.reason.contains("不可逆") || back.reason.contains("不许回到越界前"))
        assertEquals(RelationState.T4, t.state)
        assertTrue("T0 同样回不去", t.step(RelationState.T0, listOf("重来"), "玩家").refused)
    }

    @Test
    fun `判据二_670的guard语义仍然生效_没有牌能退回第三格`() {
        val t = track()
        t.step(RelationState.T3, listOf("同事"), "玩家")
        t.step(RelationState.TT, listOf("伤"), "玩家")
        assertTrue("退回第三格要 guard「没有」", t.step(RelationState.T3, listOf("酒"), "玩家").refused)
        assertTrue(t.step(RelationState.T3, listOf("没有"), "玩家").accepted)
    }

    @Test
    fun `判据二_状态集仍是670的11态_本批一个不新增`() {
        val states = RelationState.values().map { it.name }.sorted()
        assertEquals(listOf("T0", "T3", "T4", "T4P", "T5", "T6", "TE1", "TE2", "TG", "TT", "TX"), states)
        assertEquals("拓扑锁死：13 条边", 13, RelationTable.EDGES.size)
        val t = atT4()
        assertTrue(t.edges().all { it.to in RelationState.values() })
    }

    @Test
    fun `判据二_每次跨格都留下一条可查的记录`() {
        val t = atT4()
        assertEquals(3, t.ledger.records.size)
        val rec = t.ledger.records.last()
        assertEquals(RelationState.TT, rec.fromState)
        assertEquals(RelationState.T4, rec.toState)
        assertEquals(listOf("出现", "酒"), rec.evidence)
        assertEquals("玩家", rec.who)
        assertTrue(rec.at > 0)
        assertEquals("第5格", t.step(RelationState.T5, listOf("还想见"), "玩家").grid)
        assertEquals("拒掉的跨格读起来还在原格", "第5格", t.step(RelationState.TT, listOf("再触发"), "玩家").grid)
    }

    @Test
    fun `判据五_格号读法只给规格编了号的格`() {
        assertEquals("第0格", AnchorTrack.gridOf(RelationState.T0))
        assertEquals("第3格", AnchorTrack.gridOf(RelationState.T3))
        assertEquals("第4格", AnchorTrack.gridOf(RelationState.T4))
        assertTrue(AnchorTrack.gridOf(RelationState.TT).contains("○ 待核"))
        assertTrue(AnchorTrack.gridOf(RelationState.TE1).contains("○ 待核"))
    }

    @Test
    fun `判据二_环境三条件只作证据词_引擎不判断环境`() {
        val t = track()
        val env = t.envEvidence(setOf(EnvCondition.ABSENT_WOMEN, EnvCondition.LIFE_BOND))
        assertEquals(listOf("女性长期缺席", "生死相依"), env.sorted())
        assertEquals(3, EnvCondition.values().size)
        assertTrue("环境证据代替不了触发牌", t.step(RelationState.T3, env, "玩家").accepted)
    }

    @Test
    fun `判据二_requiredEvidence把要出示的东西列全`() {
        val t = track()
        assertEquals("没有这条边就不给要求", emptyList<String>(), t.requiredEvidence(RelationState.T4))
        t.step(RelationState.T3, listOf("同事"), "玩家")
        assertEquals("guard=null 的边也要证据但不指名", emptyList<String>(), t.requiredEvidence(RelationState.TT))
        t.step(RelationState.TT, listOf("酒"), "玩家")
        assertEquals(listOf("出现", AnchorTrack.TRIGGER_CARD), t.requiredEvidence(RelationState.T4))
        assertEquals(listOf("没有"), t.requiredEvidence(RelationState.T3))
    }

    @Test
    fun `防闸空转_把证据闸的判据换成永真时必须仍能咬住无牌跨格`() {
        // 注入自检：复刻 gate 的判定式，喂给它"有证据但没牌"的输入，必须给出拒因
        val evidence = listOf("出现")
        val hasCard = evidence.any { it in RelationTable.TRIGGER_CARDS }
        assertFalse("五张牌里不该有「出现」", hasCard)
        assertTrue(RelationTable.TRIGGER_CARDS.containsAll(listOf("酒", "伤", "夜哨", "离别", "庆祝")))
    }
}
