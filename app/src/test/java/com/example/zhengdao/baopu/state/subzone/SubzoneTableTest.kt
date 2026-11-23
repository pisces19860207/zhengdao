// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百七十五批 · 子区层：功能测试（表齐／档域闭集／单子成单／出口干净）。
// 只读源码与 docs/facts，零写盘、零临时目录（水位口径）。
package com.example.zhengdao.baopu.state.subzone

import com.example.zhengdao.baopu.state.bodyparts.BodyPartGuard
import com.example.zhengdao.baopu.state.bodyparts.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SubzoneTableTest {

    // ── 判据②：每区三项齐 ────────────────────────────────────────────────

    @Test
    fun 判据二_十四个子区每区三项齐() {
        assertEquals("派工单 §① 的子区数是 14（龟头3＋沟1＋系带1＋柱身3＋根1＋尿口2＋包皮3）", 14, SubzoneId.COUNT)
        assertEquals("表必须一行一个子区", SubzoneId.entries.size, SubzoneTable.rows.size)
        for (row in SubzoneTable.rows) {
            assertTrue("${row.id.label}：形态档要在 1–3 条轴之间，实际 ${row.forms.size}", row.forms.size in 1..SubzoneRow.MAX_FORMS)
            assertEquals("${row.id.label}：同一形态轴不许登记两次", row.forms.size, row.forms.map { it.axis }.toSet().size)
            assertTrue("${row.id.label}：说明为空", row.note.isNotBlank())
        }
    }

    @Test
    fun 判据二_齐全时零结论_缺一行就点名报红() {
        assertTrue("控制项：齐了不该报红", SubzoneRules.completeness(SubzoneId.entries, SubzoneTable.rows).isEmpty())
        val cut = SubzoneTable.rows.dropLast(1)
        val findings = SubzoneRules.completeness(SubzoneId.entries, cut)
        assertEquals("少一行只报一条", 1, findings.size)
        assertEquals(Verdict.DENY, findings[0].verdict)
        assertEquals(SubzoneId.entries.last(), findings[0].id)
        assertTrue("必须点名缺哪个子区：${findings[0].why}", findings[0].why.contains(SubzoneId.entries.last().label))
    }

    // ── 静态值的口径（三条硬常识的数值底，判据落在 SubzoneRulesTest） ───────

    @Test
    fun 静态值_龟头三行光滑且深一档_柱身三行同色() {
        for (id in SubzoneId.ofGroup(SubzoneGroup.GLANS)) {
            assertEquals("${id.label}：龟头要记光滑", Texture.SMOOTH, SubzoneTable.row(id).texture)
            assertEquals("${id.label}：龟头要比柱身深一档", ColorShift.DEEPER_1, SubzoneTable.row(id).color)
        }
        for (id in SubzoneId.ofGroup(SubzoneGroup.SHAFT)) {
            assertEquals("${id.label}：柱身是参照物，记「同」", ColorShift.SAME, SubzoneTable.row(id).color)
        }
    }

    @Test
    fun 静态值_根部与柱身同档_内外板按黏膜与皮肤分开() {
        assertEquals("根部埋在体表之下", FormAxis.VISIBLE.tierOf("不可见"), SubzoneTable.row(SubzoneId.RADIX).formOf(FormAxis.VISIBLE)?.tier)
        assertEquals("内板是黏膜面，比柱身浅", ColorShift.LIGHTER, SubzoneTable.row(SubzoneId.PREPUCE_INNER).color)
        assertEquals("外板朝外，与柱身同色", ColorShift.SAME, SubzoneTable.row(SubzoneId.PREPUCE_OUTER).color)
        assertTrue(
            "内板不能比外板更糙",
            SubzoneTable.row(SubzoneId.PREPUCE_INNER).texture.rank >= SubzoneTable.row(SubzoneId.PREPUCE_OUTER).texture.rank,
        )
    }

    @Test
    fun 形态档_每条读数都落在闭集档名表内() {
        for (row in SubzoneTable.rows) {
            for (form in row.forms) {
                assertTrue("${row.id.label} ${form.axis.label}：档序越界 ${form.tier}", form.tier in 0..form.axis.maxTier)
                assertTrue("${row.id.label}：档名不在册「${form.label}」", form.label in form.axis.tiers)
            }
        }
        assertEquals("质地秩三档连续 0..2", 3, Texture.entries.size)
        assertEquals("色档四档步长 −1..+2", 4, ColorShift.entries.size)
        assertEquals("色档步长必须连续：浅/同/深一档/深两档",
            listOf(-1, 0, 1, 2), ColorShift.entries.map { it.step })
    }

    // ── 禁数值（判据④）与文字闸 ─────────────────────────────────────────

    @Test
    fun 每行说明与整表快照都过三道文字闸() {
        for (row in SubzoneTable.rows) {
            assertFalse("${row.id.label}：说明里出现长度单位", BodyPartGuard.measureHit(row.note) != null)
            assertTrue("${row.id.label}：说明里出现判定词", BodyPartGuard.diagnosisHits(row.note).isEmpty())
            assertFalse("${row.id.label}：说明里出现色号", BodyPartGuard.hasColorHex(row.note))
        }
        val sheet = SubzoneSheet.static().line()
        assertEquals("快照必须一行一个子区", 14, sheet.lines().size)
        assertFalse("出口快照里出现长度单位", BodyPartGuard.measureHit(sheet) != null)
        assertTrue("出口快照里出现判定词", BodyPartGuard.diagnosisHits(sheet).isEmpty())
    }

    // ── 结构与寻址 ───────────────────────────────────────────────────────

    @Test
    fun 七组都落到至少一行_组内id与组标一致() {
        for (group in SubzoneGroup.entries) {
            assertTrue("组「${group.label}」没有任何子区", SubzoneTable.rowsOfGroup(group).isNotEmpty())
        }
        assertTrue("每行的 id.group 必须与查组的入参一致",
            SubzoneGroup.entries.all { g -> SubzoneTable.rowsOfGroup(g).all { it.id.group == g } })
    }

    @Test
    fun 宿主区_八个区各有子区_区外返回空表() {
        val zones = setOf(ZoneRef.GLANS, ZoneRef.CORONA, ZoneRef.SULCUS, ZoneRef.FRENULUM,
            ZoneRef.SHAFT, ZoneRef.RADIX, ZoneRef.MEATUS, ZoneRef.PREPUCE)
        for (zone in zones) {
            assertTrue("区 $zone 没有子区行", SubzoneTable.rowsOfZone(zone).isNotEmpty())
            assertTrue("区 $zone 未登记为有子区", SubzoneId.hasZone(zone))
        }
        assertTrue("睾丸不在本单元范围（不许凭空造子区）", !SubzoneId.hasZone("testis"))
        assertTrue(SubzoneTable.rowsOfZone("testis").isEmpty())
        assertTrue(SubzoneId.ofZone("scrotum").isEmpty())
        assertEquals("龟头宿主两行（体部／尖端）", 2, SubzoneId.ofZone(ZoneRef.GLANS).size)
        assertEquals("冠部宿主是 corona 自成一区", 1, SubzoneId.ofZone(ZoneRef.CORONA).size)
        assertEquals("柱身三段", 3, SubzoneId.ofZone(ZoneRef.SHAFT).size)
    }

    @Test
    fun 快照两跑逐字一致() {
        assertEquals(SubzoneTable.line(), SubzoneTable.line())
        assertEquals(SubzoneSheet.static().line(), SubzoneSheet.static().line())
        assertEquals("行序＝声明序", SubzoneId.entries.map { it.code },
            SubzoneSheet.static().line().lines().map { it.substringBefore('|') })
    }

    // ── 子区单：只许改档，不许改轴 ────────────────────────────────────────

    @Test
    fun 子区单_默认十四行全齐且等于静态表() {
        val sheet = SubzoneSheet.static()
        assertEquals(14, sheet.size)
        for (id in SubzoneId.entries) {
            assertEquals(SubzoneTable.row(id).line(), sheet.reading(id).line())
        }
    }

    @Test
    fun 子区单_角色偏离常态只改档_改完仍是十四行() {
        val changed = SubzoneSheet.of(
            listOf(
                SubzoneReading(
                    SubzoneId.GLANS_BODY, Texture.SMOOTH, ColorShift.DEEPER_2,
                    listOf(FormValue(FormAxis.PLUMP, FormAxis.PLUMP.tierOf("适中"))),
                ),
            ),
        )
        assertEquals(14, changed.size)
        assertEquals("只改这一行", ColorShift.DEEPER_2, changed.reading(SubzoneId.GLANS_BODY).color)
        assertEquals("没给的子区保持常态", SubzoneTable.row(SubzoneId.APEX).color, changed.reading(SubzoneId.APEX).color)
    }

    @Test
    fun 子区单_给表上没登记的轴即拒() {
        val bad = SubzoneReading(
            SubzoneId.APEX, Texture.SMOOTH, ColorShift.DEEPER_1,
            listOf(FormValue(FormAxis.GROOVE, 1)),
        )
        val thrown = runCatching { SubzoneSheet.of(listOf(bad)) }.exceptionOrNull()
        assertTrue("尖端没登记「沟深浅」这条轴，应当拒收；实得 $thrown",
            thrown is IllegalArgumentException && thrown.message!!.contains("不许改轴"))
    }

    @Test
    fun 子区单_同一子区记两次即拒() {
        val twice = listOf(SubzoneReading.of(SubzoneId.APEX), SubzoneReading.of(SubzoneId.APEX))
        val thrown = runCatching { SubzoneSheet.of(twice) }.exceptionOrNull()
        assertTrue("两个真相应当拒收；实得 $thrown", thrown is IllegalArgumentException)
    }

    @Test
    fun 子区code取错即抛_不猜近义词() {
        assertEquals(SubzoneId.GLANS_CROWN, SubzoneId.ofCode("corona"))
        val thrown = runCatching { SubzoneId.ofCode("glans.crown") }.exceptionOrNull()
        assertTrue("改名的旧写法必须当场炸；实得 $thrown", thrown is IllegalArgumentException)
    }
}
