// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 第二期四类字段：占位齐（C11）、痕迹停在痕迹（C10）、少数派只作事实（C13）。
//
// 第一期**不填内容**，但四槽与状态位一个都不许缺——派工单判据①后半句写的就是「第二期字段占位；缺 ⇒ 红」。
// 轨道同时铺好：某一槽从占位转成已填时，交接面必须跟着补上（C11 反向一条），
// ⑧的四件事实（处境类别·占比·目光·称谓）缺任何一件都算没做到「只作事实」（C13）。
package com.example.zhengdao.baopu.state.occupation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OccPhase2Test {

    private val rebar = OccupationTable.byKey("rebar")!!

    private fun findings(rows: List<OccupationRow>, rule: String) =
        OccupationGate.check(rows).filter { it.rule == rule }

    @Test
    fun `正例_七十行四槽齐且状态为占位`() {
        OccupationTable.ROWS.forEach { row ->
            val p2 = row.phase2
            assertTrue("${row.key} 第二期整格缺失", p2 != null)
            assertEquals("${row.key} 缺槽：" + p2!!.missingNames(), emptyList<String>(), p2.missingNames())
            assertEquals("${row.key} 缺状态位：" + p2.statusMissingNames(), emptyList<String>(), p2.statusMissingNames())
            assertEquals(emptyList<String>(), p2.falselySet())
        }
        assertEquals(0, findings(OccupationTable.ROWS, "C11").size)
    }

    @Test
    fun `正例_第二期档名先建齐`() {
        assertEquals(5, Habitat.COUNT)
        assertEquals(4, BathPrivacy.COUNT)
        assertEquals(9, TraceKind.COUNT)
        assertEquals(3, AcheTier.COUNT)
        assertEquals(3, HearingFact.COUNT)
        assertEquals(5, OdorSource.COUNT)
        assertEquals(4, MinorityKind.COUNT)
        assertEquals(3, ShareTier.COUNT)
        assertEquals(3, GazeFact.COUNT)
        assertEquals(6, AddressForm.COUNT)
        assertEquals(Habitat.entries.size, Habitat.COUNT)
        assertEquals(TraceKind.entries.size, TraceKind.COUNT)
        assertEquals(OdorSource.entries.size, OdorSource.COUNT)
        assertEquals(MinorityKind.entries.size, MinorityKind.COUNT)
    }

    @Test
    fun `痕迹词表全是痕迹没有病名`() {
        TraceKind.entries.forEach { kind ->
            OccWords.DISEASE.forEach { word -> assertTrue("${kind.label} 含 $word", !kind.label.contains(word)) }
        }
        AcheTier.entries.forEach { tier ->
            OccWords.DISEASE.forEach { word -> assertTrue("${tier.label} 含 $word", !tier.label.contains(word)) }
        }
        HearingFact.entries.forEach { fact ->
            OccWords.DISEASE.forEach { word -> assertTrue("${fact.label} 含 $word", !fact.label.contains(word)) }
        }
        assertEquals("腰背只写体感档", listOf("无", "偶发", "经常"), AcheTier.entries.map { it.label })
    }

    @Test
    fun `负例_四槽缺一样必红`() {
        val noOdor = rebar.copy(phase2 = rebar.phase2!!.copy(odor = null))
        val red = findings(listOf(noOdor), "C11")
        assertEquals(1, red.size)
        assertTrue(red.first().message.contains("⑦气味来源"))
    }

    @Test
    fun `负例_状态位抽掉必红`() {
        val noStatus = rebar.copy(phase2 = rebar.phase2!!.copy(traces = TraceSlot(null, null, null, null)))
        val red = findings(listOf(noStatus), "C11")
        assertEquals(1, red.size)
        assertTrue(red.first().message.contains("占位缺状态位"))
        assertTrue(red.first().message.contains("⑥痕迹"))
    }

    @Test
    fun `负例_填了状态没填内容算假填`() {
        val fake = rebar.copy(
            phase2 = rebar.phase2!!.copy(habitat = HabitatSlot(SlotStatus.SET, null, null)),
        )
        val red = findings(listOf(fake), "C11")
        // 两条：内容没填（假填）＋没补交接（轨道断了）——同一次漏填不能只报一半。
        assertEquals(2, red.size)
        assertEquals(1, red.count { it.message.contains("填了状态没填内容") })
        assertEquals(1, red.count { it.message.contains("填了内容却没交接") })
    }

    @Test
    fun `负例_填了内容却没补交接必红`() {
        val filled = rebar.phase2!!.copy(odor = OdorSlot(SlotStatus.SET, listOf(OdorSource.SWEAT)))
        val orphan = rebar.copy(phase2 = filled)
        val red = findings(listOf(orphan), "C11")
        assertEquals(1, red.size)
        assertTrue(red.first().message.contains("填了内容却没交接"))
    }

    @Test
    fun `正例_第二期补内容时轨道现成`() {
        // 四槽转已填 + 交接由 handsOf 算出来 ⇒ 全绿：第二期不需要再改结构，也不需要改判据。
        val filled = Phase2Slots(
            habitat = HabitatSlot(SlotStatus.SET, Habitat.DORM, BathPrivacy.PUBLIC_FACILITY),
            traces = TraceSlot(SlotStatus.SET, listOf(TraceKind.HAND_CALLUS), AcheTier.OCCASIONAL, HearingFact.UNTESTED),
            odor = OdorSlot(SlotStatus.SET, listOf(OdorSource.SWEAT)),
            minority = MinoritySlot(
                SlotStatus.SET, listOf(MinorityKind.MALE_IN_FEMALE_POST),
                ShareTier.VERY_FEW, GazeFact.NOTED, AddressForm.GENERIC,
            ),
        )
        val wired = rebar.copy(phase2 = filled, hands = handsOf(null, rebar.attire, filled))
        assertEquals(0, OccupationGate.check(listOf(wired)).size)
        assertTrue(wired.hands!!.contains(Handoff.HABITAT_883))
        assertTrue(wired.hands!!.contains(Handoff.TRACE_BODY_805))
        assertTrue(wired.hands!!.contains(Handoff.ODOR_SOURCE_827))
    }

    @Test
    fun `正例_气味来源与汗味交接给827`() {
        assertEquals(listOf("油烟", "消毒水", "金属", "溶剂", "汗"), OdorSource.entries.map { it.label })
        assertEquals("827 味型来源（第二期）", Handoff.ODOR_SOURCE_827.target)
    }

    @Test
    fun `少数派四件缺一件必红`() {
        val filled = rebar.phase2!!.copy(
            minority = MinoritySlot(
                SlotStatus.SET, listOf(MinorityKind.MALE_IN_FEMALE_POST),
                ShareTier.VERY_FEW, null, AddressForm.GENERIC,
            ),
        )
        val wired = rebar.copy(phase2 = filled, hands = handsOf(null, rebar.attire, filled))
        val c13 = findings(listOf(wired), "C13")
        assertEquals(1, c13.size)
        assertTrue(c13.first().message.contains("目光"))
    }

    @Test
    fun `少数派写成玩笑或贬损必红`() {
        val facts = MinoritySlot(
            SlotStatus.SET, listOf(MinorityKind.MALE_IN_FEMALE_POST),
            ShareTier.FEW, GazeFact.NOTED, AddressForm.GENERIC,
        )
        listOf("男护士很好笑", "男幼师是个反差", "男美甲师娘娘腔", "男护士被调侃").forEach { bad ->
            val poisoned = rebar.copy(phase2 = rebar.phase2!!.copy(minority = facts), label = bad)
            val c13 = OccupationGate.check(listOf(poisoned)).filter { it.rule == "C13" }
            assertTrue("$bad 没被咬住", c13.isNotEmpty())
        }
    }

    @Test
    fun `负例_痕迹写成病名必红`() {
        val poisoned = rebar.copy(label = "钢筋工腰肌劳损")
        val c10 = findings(listOf(poisoned), "C10")
        assertEquals(1, c10.size)
        assertTrue(c10.first().message.contains("劳损"))
    }

    @Test
    fun `居住条件只写条件不写阶层`() {
        Habitat.entries.forEach { h ->
            OccWords.STEREOTYPE.forEach { word -> assertTrue("${h.label} 含 $word", !h.label.contains(word)) }
        }
        assertEquals("883 条件档的客观输入（第二期）", Handoff.HABITAT_883.target)
        assertTrue(Handoff.HABITAT_883.allowed)
    }

    @Test
    fun `全表对第二期三条交接零引用`() {
        val used = OccupationTable.usedGrades()["Handoff"] ?: emptySet()
        listOf(Handoff.HABITAT_883, Handoff.TRACE_BODY_805, Handoff.ODOR_SOURCE_827).forEach {
            assertTrue("${it.key} 不该在第一期出现", !used.contains(it.name))
        }
    }
}
