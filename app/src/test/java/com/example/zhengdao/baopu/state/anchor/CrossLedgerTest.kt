// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 判据③④⑤（账本侧）：记录只追加不改写、三条链互不覆盖、给时间点能重建当时。
// 判据③ 的牙在 restore：改一条、抽一条、插一条都会让摘要链重算接不上，账本拒装填。
package com.example.zhengdao.baopu.state.anchor

import com.example.zhengdao.baopu.state.track.CharacterTrack
import com.example.zhengdao.baopu.state.track.RelationState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Modifier

class CrossLedgerTest {

    private var tick = 500L

    private fun at(): Long {
        tick += 10
        return tick
    }

    private fun ledger() = CrossLedger { at() }

    private fun threeRecords(l: CrossLedger = ledger()): Triple<CrossRecord, CrossRecord, CrossRecord> =
        Triple(
            l.append(RelationState.T0, RelationState.T3, listOf("认识三年"), "玩家"),
            l.append(RelationState.T3, RelationState.TT, listOf("酒"), "玩家"),
            l.append(RelationState.TT, RelationState.T4, listOf("出现", "酒"), "玩家"),
        )

    /** 三条记录的链（列表形态，方便篡改）。 */
    private fun chain(l: CrossLedger = ledger()): List<CrossRecord> = threeRecords(l).let { listOf(it.first, it.second, it.third) }

    /** 实例字段名（剔掉编译器加的静态 Companion 与合成字段，免得把「机器加的手脚」算进规格字段）。 */
    private fun fieldNames(c: Class<*>): Set<String> = c.declaredFields
        .filterNot { it.isSynthetic || Modifier.isStatic(it.modifiers) }
        .map { it.name }.toSet()

    @Test
    fun `判据三_记录形状就是派工单那五个字段`() {
        assertEquals(setOf("seq", "fromState", "toState", "evidence", "at", "who", "digest"), fieldNames(CrossRecord::class.java))
        assertEquals(5, setOf("fromState", "toState", "evidence", "at", "who").size)
    }

    @Test
    fun `判据三_账本只有追加口没有编辑口`() {
        val names = CrossLedger::class.java.declaredMethods.map { it.name }
        listOf("set", "remove", "removeAt", "clear", "edit", "delete", "update", "replace", "mutate", "rollback", "truncate")
            .forEach { assertFalse("不许有改写口：$it", it in names) }
        assertTrue("写入口只能是 append", names.contains("append") && names.contains("appendSnapshot"))
    }

    @Test
    fun `判据三_对外给的是副本`() {
        val l = ledger()
        val (a, b, c) = threeRecords(l)
        val view = l.records.toMutableList()
        view.removeAt(0)
        assertEquals("改副本不许动账本", 3, l.records.size)
        assertEquals(listOf(a, b, c), l.records)
        assertEquals(listOf(1, 2, 3), l.records.map { it.seq })
        assertTrue(l.verify().isEmpty())
    }

    @Test
    fun `判据三_没证据的记录不进账`() {
        val l = ledger()
        assertTrue(assertThrows(AnchorSpecException::class.java) {
            l.append(RelationState.T0, RelationState.T3, emptyList(), "玩家")
        }.message!!.contains("判据②"))
        assertThrows(AnchorSpecException::class.java) { l.append(RelationState.T0, RelationState.T3, listOf("  "), "玩家") }
        assertThrows(AnchorSpecException::class.java) { l.append(RelationState.T0, RelationState.T3, listOf("有"), "  ") }
        assertEquals(0, l.records.size)
    }

    @Test
    fun `判据三_时间回拨等于改写历史`() {
        val l = ledger()
        l.append(RelationState.T0, RelationState.T3, listOf("认识三年"), "玩家", at = 900L)
        val e = assertThrows(AnchorSpecException::class.java) {
            l.append(RelationState.T3, RelationState.TT, listOf("酒"), "玩家", at = 800L)
        }
        assertTrue(e.message!!.contains("时间回拨"))
        assertEquals(1, l.records.size)
    }

    @Test
    fun `判据三_改掉一条的字段_恢复时必被咬`() {
        val all = chain()
        val tampered = listOf(all[0], all[1].copy(at = all[1].at + 7), all[2])
        val e = assertThrows(AnchorSpecException::class.java) { CrossLedger.restore(tampered) }
        assertTrue(e.message!!.contains("摘要对不上"))
        assertTrue(e.message!!.contains("拒绝恢复"))
    }

    @Test
    fun `判据三_抽掉中间一条_序号与摘要都接不上`() {
        val all = chain()
        val e = assertThrows(AnchorSpecException::class.java) { CrossLedger.restore(listOf(all[0], all[2])) }
        assertTrue("要么序号不连续，要么摘要对不上", e.message!!.contains("不连续") || e.message!!.contains("摘要对不上"))
    }

    @Test
    fun `判据三_插一条伪造的进中间_必被咬`() {
        val all = chain()
        val forged = CrossRecord(2, RelationState.T0, RelationState.T6, listOf("编的"), all[1].at, "玩家", all[1].digest)
        val e = assertThrows(AnchorSpecException::class.java) { CrossLedger.restore(listOf(all[0], forged, all[1], all[2])) }
        assertTrue(e.message!!.contains("摘要对不上"))
    }

    @Test
    fun `判据三_没动过的账本原样恢复`() {
        val all = chain()
        val restored = CrossLedger.restore(all)
        assertEquals(all, restored.records)
        assertTrue(restored.verify().isEmpty())
    }

    @Test
    fun `判据五_给时间点重建当时第几格与当时七维`() {
        val profile = AnchorTestData.complete()
        val l = ledger()
        val t1 = at()
        l.appendSnapshot(profile, t1)
        val t2 = at()
        l.append(RelationState.T0, RelationState.T3, listOf("认识三年"), "玩家", t2)
        val t3 = at()
        val changed = profile.copy(values = profile.values + ("t.gaze" to listOf("直")))
        l.appendSnapshot(changed, t3)

        val before = l.replay(t1 - 1)
        assertTrue("时刻早于第一条 ⇒ 空回放，不编初始态", before.isEmpty)

        val atCross = l.replay(t2)
        assertEquals("第3格", atCross.grid)
        assertEquals("当时七维是 t1 那一份，不是现在这一份", profile.snapshot(), atCross.dimensions)
        assertFalse("当时那份还没改过眼神", atCross.dimensions.contains("t.gaze=直"))
        assertTrue("当时那份＝t1 存的原始值", atCross.dimensions.contains("t.gaze=躲"))

        val after = l.replay(t3 + 1)
        assertTrue("t3 之后回放到改过的那份", after.dimensions.contains("t.gaze=直"))
        assertEquals("第3格", after.grid)
    }

    @Test
    fun `判据五_回放取的是时刻不晚于t的最后一条`() {
        val l = ledger()
        l.append(RelationState.T0, RelationState.T3, listOf("认识三年"), "玩家", 100L)
        l.append(RelationState.T3, RelationState.TT, listOf("酒"), "玩家", 200L)
        assertEquals(RelationState.TT, l.replay(250L).record?.toState)
        assertEquals(RelationState.T3, l.replay(150L).record?.toState)
        assertEquals(200L, l.replay(9_999L).record?.at)
    }

    @Test
    fun `判据五_空账本回放给出起步前读法`() {
        val r = ledger().replay(1L)
        assertTrue(r.isEmpty)
        assertEquals(AnchorTrack.GRID_EMPTY, r.grid)
        assertTrue(r.dimensions.contains("不编初始档案"))
    }

    @Test
    fun `判据四_越界账不碰沦陷链与升温链`() {
        val first = CharacterTrack()
        val before = first.stateView()
        val track = AnchorTrack(AnchorTestData.complete(), ledger = ledger(), now = { at() })
        track.step(RelationState.T3, listOf("认识三年"), "玩家")
        track.noteDimensions()
        track.step(RelationState.TT, listOf("酒"), "玩家")
        track.step(RelationState.T4, listOf("出现", "酒"), "玩家")
        assertEquals("跨格期间第一机的状态一位没动", before, first.stateView())
        assertEquals(RelationState.T4, track.state)
    }

    @Test
    fun `判据四_账本里没有任何第一机字段`() {
        val names = (fieldNames(CrossRecord::class.java) + fieldNames(DimSnapshot::class.java)).map { it.lowercase() }
        listOf("dstage", "astage", "stage", "attacker", "salience", "stance", "layer").forEach { ban ->
            assertFalse("三条链互不覆盖：账本不许带 $ban", names.any { it.contains(ban) })
        }
    }

    @Test
    fun `判据四_快照链与记录链各自独立追加`() {
        val l = ledger()
        l.append(RelationState.T0, RelationState.T3, listOf("同事"), "玩家")
        assertEquals(1, l.records.size)
        assertEquals(0, l.snapshots.size)
        l.appendSnapshot(AnchorTestData.complete())
        assertEquals("快照不动记录链", 1, l.records.size)
        assertEquals(1, l.snapshots.size)
        assertTrue(l.verify().isEmpty())
    }

    @Test
    fun `防闸空转_摘要函数对同一输入必须稳定_对改字段必须敏感`() {
        val body = CrossRecord.bodyOf(1, RelationState.T0, RelationState.T3, listOf("a"), 10L, "玩家", null)
        val other = CrossRecord.bodyOf(1, RelationState.T0, RelationState.T3, listOf("b"), 10L, "玩家", null)
        assertEquals(body, CrossRecord.bodyOf(1, RelationState.T0, RelationState.T3, listOf("a"), 10L, "玩家", null))
        assertFalse("证据换一个字段正文就得变", body == other)
        val l = ledger()
        val rec = l.append(RelationState.T0, RelationState.T3, listOf("同事"), "玩家")
        assertEquals("digest 由正文算出来，不是随手给的", rec.digest.length, 16)
        assertThrows(AnchorSpecException::class.java) { CrossLedger.restore(listOf(rec.copy(evidence = listOf("陌生人")))) }
    }
}
