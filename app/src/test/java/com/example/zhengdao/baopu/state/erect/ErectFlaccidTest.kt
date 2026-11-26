// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第九百零六批 · EHS 0 归位：疲软是已知状态，不是缺数据。
// 三条硬判据：① 810 的 EHS 1..4 零改；② 疲软列不再 absent；③ 疲软 ≠ 未知（两条夹具分开）。
package com.example.zhengdao.baopu.state.erect

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ErectFlaccidTest {

    // ── ① 810 语义零改 ─────────────────────────────────────────
    @Test
    fun `810硬度四级语义零改_断言1到4仍成立`() {
        assertEquals(4, HardnessTier.COUNT)
        assertEquals(listOf("稍硬", "可插入", "坚挺", "极硬"), HardnessTier.entries.map { it.label })
        assertEquals((1..4).toList(), HardnessTier.entries.map { it.ehs })
        // 0 依旧进不去 EHS：疲软另立字段，不改这里
        assertThrows(IllegalArgumentException::class.java) { HardnessTier.of(0) }
        assertThrows(IllegalArgumentException::class.java) { HardnessTier.of(5) }
    }

    @Test
    fun `疲软档闭集_只有一个FLACCID且对齐EHS零`() {
        assertEquals(1, FullnessTier.COUNT)
        assertEquals(0, FullnessTier.FLACCID_EHS)
        assertEquals(listOf("疲软"), FullnessTier.entries.map { it.label })
        assertEquals(listOf(0), FullnessTier.entries.map { it.fullness })
        assertEquals(FullnessTier.FLACCID, FullnessTier.of(0))
        assertThrows(IllegalArgumentException::class.java) { FullnessTier.of(1) }
    }

    // ── ② 疲软列有明确档，不再 absent ──────────────────────────
    @Test
    fun `疲软列有明确档_输出FLACCID不是absent不是EHS1`() {
        val line = ErectFixtures.flaccid().line()
        assertEquals("硬度=疲软(FLACCID)／勃起=坚挺", line)
        assertTrue(flaccidColumnClear(line))
        assertFalse(line.contains(FULLNESS_ABSENT))
        assertFalse(line.contains(HardnessTier.EHS1.label))
    }

    @Test
    fun `两态对照表整表与判据出口_疲软列也不absent`() {
        val sheet = ErectSheet.of(
            ErectFixtures.size(), ErectFixtures.angle(), ErectFixtures.flaccid(),
            ErectFixtures.prepuce(), ErectFixtures.corona(),
        )
        assertTrue(flaccidColumnClear(sheet.line()))
        val person = ErectPerson("疲软角色", sheet)
        assertTrue(flaccidColumnClear(ErectChecks.sceneLine(person)))
        assertTrue(flaccidColumnClear(person.line()))
    }

    // ── ③ 疲软 ≠ 未知：两条夹具分开 ────────────────────────────
    @Test
    fun `疲软与未知两条夹具分开_只有未知才absent`() {
        val known = ErectFixtures.flaccid()
        val unknown = ErectFixtures.unknownHardness()

        assertNotEquals(known.flaccidness, unknown.flaccidness)
        assertEquals(FullnessTier.FLACCID, known.flaccidness)
        assertEquals(null, unknown.flaccidness)

        assertTrue("疲软夹具必须给明确档", flaccidColumnClear(known.line()))
        assertTrue(known.line().contains("FLACCID"))
        assertFalse(known.line().contains(FULLNESS_ABSENT))

        assertTrue("只有未知才 absent", unknown.line().contains(FULLNESS_ABSENT))
        assertFalse(unknown.line().contains("FLACCID"))
        assertFalse(flaccidColumnClear(unknown.line()))
    }

    @Test
    fun `闸门自检_把疲软换成absent必被咬住`() {
        val poisoned = "硬度=" + FULLNESS_ABSENT + "／勃起=坚挺"
        assertFalse("负例必须红", flaccidColumnClear(poisoned))
        assertFalse("负例必须红", flaccidColumnClear("硬度=未知(absent)／勃起=坚挺"))
        assertTrue("正例必须绿", flaccidColumnClear(ErectFixtures.flaccid().line()))
    }

    // ── 边界：不做 826 欲望/能力，不做医学判断 ──────────────────
    @Test
    fun `疲软输出不出现欲望能力字段与医学词`() {
        val line = ErectFixtures.flaccid().line()
        assertTrue("医学词闸不过", BodyPartGuard.diagnosisHits(line).isEmpty())
        listOf("欲望", "能力", "性欲", "功能障碍", "阳痿", "病因", "治疗").forEach { tok ->
            assertFalse("810 不写 $tok：$line", line.contains(tok))
        }
    }

    @Test
    fun `闸门自检_注入医学词必被咬住`() {
        val poisoned = "疲软属勃起功能障碍，建议治疗"
        assertTrue("负例必须红", BodyPartGuard.diagnosisHits(poisoned).isNotEmpty())
    }

    /** 疲软列判据：明确档在、absent 不在。正例绿、两种负例红。 */
    private fun flaccidColumnClear(line: String): Boolean =
        line.contains(FullnessTier.FLACCID.name) && !line.contains(FULLNESS_ABSENT)
}
