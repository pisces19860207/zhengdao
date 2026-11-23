// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 总表与计数锁：判据①（六维齐＋行业覆盖）、判据⑥（纯枚举与表）、
// 以及「派工单点名的档不能只列不用」——每个档名都得被某一行真取到，否则等于没建这一档。
package com.example.zhengdao.baopu.state.occupation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OccTableTest {

    @Test
    fun `正例_生产表零发现`() {
        val findings = OccupationGate.check()
        assertEquals("生产表必须零发现：" + findings.joinToString("；") { it.text() }, 0, findings.size)
        assertEquals(0, OccupationGate.redCount(findings))
        assertEquals(0, OccupationGate.warnCount(findings))
    }

    @Test
    fun `计数锁_五张分表与总行数`() {
        assertEquals(9, OccupationTable.TRADE.size)
        assertEquals(13, OccupationTable.SERVICE.size)
        assertEquals(21, OccupationTable.CIVILIAN.size)
        assertEquals(18, OccupationTable.UNIFORM.size)
        assertEquals(9, OccupationTable.FAITH.size)
        assertEquals(70, OccupationTable.COUNT)
        assertEquals(70, OccupationTable.TRADE.size + OccupationTable.SERVICE.size +
            OccupationTable.CIVILIAN.size + OccupationTable.UNIFORM.size + OccupationTable.FAITH.size)
    }

    @Test
    fun `计数锁_行业十六项与年代五项`() {
        assertEquals(16, Industry.COUNT)
        assertEquals(16, Industry.entries.size)
        assertEquals(5, Era.COUNT)
        assertEquals(5, Era.entries.size)
        assertEquals(5, EraTable.ROWS.size)
        assertEquals(4, DriveAxis.COUNT)
        assertEquals(4, DriveAxis.entries.size)
    }

    @Test
    fun `判据一_每个行业至少一个岗位示例`() {
        Industry.entries.forEach { ind ->
            val rows = OccupationTable.of(ind)
            assertTrue("${ind.label} 没有岗位示例：" + ind.key, rows.isNotEmpty())
        }
        assertEquals(70, Industry.entries.sumOf { OccupationTable.of(it).size })
    }

    @Test
    fun `档名全用_民用六维与环境负荷`() {
        val used = OccupationTable.usedGrades()
        assertAllUsed(used, Industry.entries.map { it.name }, "Industry")
        assertAllUsed(used, Posture.entries.map { it.name }, "Posture")
        assertAllUsed(used, Dirt.entries.map { it.name }, "Dirt")
        assertAllUsed(used, Dress.entries.map { it.name }, "Dress")
        assertAllUsed(used, Sched.entries.map { it.name }, "Sched")
        assertAllUsed(used, Risk.entries.map { it.name }, "Risk")
        assertAllUsed(used, Place.entries.map { it.name }, "Place")
        assertAllUsed(used, Noise.entries.map { it.name }, "Noise")
        assertAllUsed(used, Dust.entries.map { it.name }, "Dust")
        assertAllUsed(used, Temp.entries.map { it.name }, "Temp")
        assertAllUsed(used, Moist.entries.map { it.name }, "Moist")
        assertAllUsed(used, Chem.entries.map { it.name }, "Chem")
    }

    @Test
    fun `档名全用_着装负荷与驱动`() {
        val used = OccupationTable.usedGrades()
        assertAllUsed(used, Wear.entries.map { it.name }, "Wear")
        assertAllUsed(used, Closure.entries.map { it.name }, "Closure")
        assertAllUsed(used, Reach.entries.map { it.name }, "Reach")
        assertAllUsed(used, Carry.entries.map { it.name }, "Carry")
        assertAllUsed(used, Joint.entries.map { it.name }, "Joint")
        assertAllUsed(used, Stand.entries.map { it.name }, "Stand")
        assertAllUsed(used, Shift.entries.map { it.name }, "Shift")
        assertAllUsed(used, Dir.entries.map { it.name }, "Dir")
        assertAllUsed(used, Mech.entries.map { it.name }, "Mech")
        // 个体差异档只有一个取值：结构上写不出「个体差异小」，所以判据③不可能被绕开。
        assertAllUsed(used, Spread.entries.map { it.name }, "Spread")
    }

    @Test
    fun `档名全用_军警消细类与特有字段`() {
        val used = OccupationTable.usedGrades()
        assertAllUsed(used, Arm.entries.map { it.name }, "Arm")
        assertAllUsed(used, ArmyRank.entries.map { it.name }, "ArmyRank")
        assertAllUsed(used, PolicePost.entries.map { it.name }, "PolicePost")
        assertAllUsed(used, FirePost.entries.map { it.name }, "FirePost")
        assertAllUsed(used, ServiceScene.entries.map { it.name }, "ServiceScene")
        assertAllUsed(used, DutyPhase.entries.map { it.name }, "DutyPhase")
        assertAllUsed(used, DutyCycle.entries.map { it.name }, "DutyCycle")
        assertAllUsed(used, ReturnGap.entries.map { it.name }, "ReturnGap")
        assertAllUsed(used, Gear.entries.map { it.name }, "Gear")
        assertAllUsed(used, EnvExtra.entries.map { it.name }, "EnvExtra")
        assertAllUsed(used, BodyLoad.entries.map { it.name }, "BodyLoad")
    }

    @Test
    fun `档名全用_宗教教派七件`() {
        val used = OccupationTable.usedGrades()
        assertAllUsed(used, FaithBranch.entries.map { it.name }, "FaithBranch")
        assertAllUsed(used, Celibate.entries.map { it.name }, "Celibate")
        assertAllUsed(used, FaithDiet.entries.map { it.name }, "FaithDiet")
        assertAllUsed(used, HairRule.entries.map { it.name }, "HairRule")
        assertAllUsed(used, FaithDress.entries.map { it.name }, "FaithDress")
        assertAllUsed(used, FaithResidence.entries.map { it.name }, "FaithResidence")
        assertAllUsed(used, FaithBasis.entries.map { it.name }, "FaithBasis")
        assertEquals(9, OccupationTable.withFaith().size)
    }

    @Test
    fun `档名全用_年代四类可用集`() {
        val used = OccupationTable.eraUsedGrades()
        assertAllUsed(used, Era.entries.map { it.name }, "Era")
        assertAllUsed(used, EraForm.entries.map { it.name }, "EraForm")
        assertAllUsed(used, FabricClass.entries.map { it.name }, "FabricClass")
        assertAllUsed(used, GarmentSupply.entries.map { it.name }, "GarmentSupply")
        assertAllUsed(used, WashVenue.entries.map { it.name }, "WashVenue")
    }

    @Test
    fun `交接面_禁区与第二期三条一个都没被用`() {
        val used = OccupationTable.usedGrades()["Handoff"] ?: emptySet()
        Handoff.banned().forEach { assertTrue("禁区交接被用了：" + it.key, !used.contains(it.name)) }
        listOf(Handoff.ODOR_SOURCE_827, Handoff.TRACE_BODY_805, Handoff.HABITAT_883).forEach {
            assertTrue("第二期交接提前用了：" + it.key, !used.contains(it.name))
        }
        listOf(
            Handoff.DRIVE_BUILD_815, Handoff.DRIVE_SKIN_805, Handoff.DRIVE_WEAR_881, Handoff.DRIVE_SWEAT_827,
            Handoff.STRUCTURE_FLY_881, Handoff.DUTY_WINDOW_883, Handoff.ERA_GARMENT_881, Handoff.ERA_VENUE_883,
        ).forEach { assertTrue("该用的交接没用：" + it.key, used.contains(it.name)) }
        assertEquals(Handoff.ALLOWED_COUNT, Handoff.entries.count { it.allowed })
        assertEquals(Handoff.BANNED_COUNT, Handoff.banned().size)
        // 第二期状态位：只许 PLACEHOLDER，SET 一出现就说明有人提前填了内容。
        assertEquals(setOf(SlotStatus.PLACEHOLDER.name), OccupationTable.usedGrades()["SlotStatus"])
    }

    @Test
    fun `行数对得上_军警消十八行按臂分布`() {
        val rows = OccupationTable.withService()
        assertEquals(18, rows.size)
        assertEquals(8, rows.count { it.service?.arm == Arm.ARMY })
        assertEquals(6, rows.count { it.service?.arm == Arm.POLICE })
        assertEquals(4, rows.count { it.service?.arm == Arm.FIRE })
        assertEquals(0, OccupationTable.plain().count { it.industry == Industry.UNIFORM })
    }

    @Test
    fun `负例_删掉某行业的唯一一行必红`() {
        val withoutBeauty = OccupationTable.ROWS.filter { it.industry != Industry.BEAUTY }
        val findings = OccupationGate.check(withoutBeauty)
        val c8 = findings.filter { it.rule == "C8" }
        assertEquals("美发美容整行业没了 ⇒ 一条 C8" , 1, c8.size)
        assertTrue(c8.first().message.contains("美发美容"))
        assertEquals(OccSeverity.ERROR, c8.first().severity)
    }

    @Test
    fun `负例_键重复构造不出表`() {
        // init 里的自证：同一个键出现两次说明表开始有第二份真相。
        val dupes = OccupationTable.ROWS.groupBy { it.key }.filterValues { it.size > 1 }
        assertEquals(0, dupes.size)
        val poisoned = OccupationTable.ROWS + OccupationTable.byKey("rebar")!!
        val again = poisoned.groupBy { it.key }.filterValues { it.size > 1 }.keys
        assertEquals("复制一行必然落在重复键上（说明这一枪抓得住）", 1, again.size)
        assertTrue(again.contains("rebar"))
    }

    private fun assertAllUsed(used: Map<String, Set<String>>, all: List<String>, bucket: String) {
        val got = used[bucket] ?: emptySet()
        val unused = all.toSet() - got
        assertEquals("$bucket 有档名没被任何一行取到：" + unused.joinToString("、"), emptySet<String>(), unused)
        assertTrue("$bucket 出现了枚举里没有的档位", all.toSet().containsAll(got))
    }
}
