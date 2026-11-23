// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 判据④与 2026-11-24 补充更正：职业与清洁**彻底脱钩**，而且**双向刻板都算红**。
//
// 这一枪有三层，缺任何一层都只是文档上的一句话：
// ① 结构层——驱动轴只有四根，清洁／卫生／意愿根本不在轴上（写不出第五根轴）；
// ② 交接层——Handoff 里三条禁区值一旦出现在任何一行，C5 立即红；生产表对它们的引用数恒为 0；
// ③ 词表层——C4 的词表同时收「脏／邋遢」和「爱干净／讲究／洁癖」，
//    所以「工人脏」与「医生讲究」吃的是同一条判据，军警消既不脏也不铁血。
package com.example.zhengdao.baopu.state.occupation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OccHygieneTest {

    private val rebar = OccupationTable.byKey("rebar")!!

    private val physician = OccupationTable.byKey("physician")!!

    private fun c4(rows: List<OccupationRow>) = OccupationGate.check(rows).filter { it.rule == "C4" }

    private fun c5(rows: List<OccupationRow>) = OccupationGate.check(rows).filter { it.rule == "C5" }

    @Test
    fun `结构层_驱动轴只有四根且都不是清洁`() {
        assertEquals(4, DriveAxis.entries.size)
        assertEquals(listOf("build", "skinHand", "garmentWear", "sweatOdor"), DriveAxis.entries.map { it.key })
        DriveAxis.entries.forEach { axis ->
            OccWords.HYGIENE.forEach { word ->
                assertTrue("${axis.label} 里出现了 $word", !axis.label.contains(word) && !axis.key.contains(word))
            }
        }
    }

    @Test
    fun `结构层_往883的去向只有条件档一条口径`() {
        val to883 = Handoff.entries.filter { it.allowed && it.target.startsWith("883") }
        // 场所来源、时段来源、居住条件来源——三条都写「条件档」，一条都不写意愿与派生。
        assertEquals(3, to883.size)
        assertTrue(to883.all { it.target.contains("条件档") })
        Handoff.banned().forEach { assertTrue("禁区值的去处不许写成条件档：" + it.key, !it.target.startsWith("883 条件档")) }
    }

    @Test
    fun `生产表_三值禁区引用数为零`() {
        val used = OccupationTable.usedGrades()["Handoff"] ?: emptySet()
        Handoff.banned().forEach { assertTrue("禁区被引用：" + it.key, !used.contains(it.name)) }
        assertEquals(3, Handoff.banned().size)
        assertEquals(14, Handoff.entries.size)
    }

    @Test
    fun `负例_把职业接到意愿档上必红`() {
        val poisoned = rebar.copy(hands = rebar.hands!! + Handoff.BAN_HABIT_883)
        val findings = c5(listOf(poisoned))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("禁区交接"))
    }

    @Test
    fun `负例_三条禁区各来一次红三条`() {
        val poisoned = rebar.copy(hands = rebar.hands!! + Handoff.banned())
        assertEquals(3, c5(listOf(poisoned)).size)
    }

    @Test
    fun `负例_非军警消带了驻勤时段交接必红`() {
        val poisoned = rebar.copy(hands = rebar.hands!! + Handoff.DUTY_WINDOW_883)
        val findings = c5(listOf(poisoned))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("其余行业不填"))
    }

    @Test
    fun `负例_军警消丢了时段交接必红`() {
        val soldier = OccupationTable.byKey("conscript")!!
        val findings = c5(listOf(soldier.copy(hands = soldier.hands!!.minus(Handoff.DUTY_WINDOW_883))))
        assertEquals(1, findings.size)
        assertTrue(findings.first().message.contains("驻勤时段没交给条件档"))
    }

    @Test
    fun `词表层_贬低的一侧脏必红`() {
        assertEquals(1, c4(listOf(rebar.copy(label = "脏兮兮的钢筋工"))).size)
        assertEquals(1, c4(listOf(rebar.copy(label = "邋遢的工人"))).size)
        assertEquals(1, c4(listOf(OccupationTable.byKey("nurse")!!.copy(label = "不爱洗的护士"))).size)
    }

    @Test
    fun `词表层_抬高的一侧爱干净同样红`() {
        // 抬高的一边与贬低的一边吃的是同一条判据，一条都不许漏。
        listOf("很讲究的医生", "爱干净的医生", "近乎洁癖的医生", "讲卫生的医生").forEach { bad ->
            assertTrue("$bad 没被咬住", c4(listOf(physician.copy(label = bad))).isNotEmpty())
        }
        assertEquals("「讲究」单独一次命中", 1, c4(listOf(physician.copy(label = "讲究的医生"))).size)
    }

    @Test
    fun `词表层_性格智力阶层一律红`() {
        listOf("铁血", "服从", "粗鲁", "素质", "学历", "收入", "吃苦").forEach { word ->
            val findings = c4(listOf(OccupationTable.byKey("conscript")!!.copy(label = word + "的士官")))
            assertEquals("$word 必须被咬住", 1, findings.size)
        }
    }

    @Test
    fun `军警消十八行既不铁血也不脏`() {
        OccupationTable.withService().forEach { row ->
            assertEquals("${row.key} 出现刻板词", 0, c4(listOf(row)).size)
        }
    }

    @Test
    fun `宗教职不写成性格`() {
        OccupationTable.withFaith().forEach { row ->
            assertEquals("${row.key} 把宗教身份写成性格", 0, c4(listOf(row)).size)
        }
        OccWords.FAITH_WORDS.forEach { word ->
            val findings = c4(listOf(physician.copy(label = "医生是" + word)))
            assertTrue("$word 必须被咬住", findings.isNotEmpty())
        }
    }

    @Test
    fun `时段档与场所档只答能不能`() {
        ReturnGap.entries.forEach { gap ->
            OccWords.HYGIENE.forEach { word -> assertTrue("${gap.label} 混进了 $word", !gap.label.contains(word)) }
        }
        WashVenue.entries.forEach { venue ->
            OccWords.HYGIENE.forEach { word ->
                assertTrue("${venue.label} 混进了 $word", !venue.label.contains(word))
            }
        }
    }

    @Test
    fun `词表自身双向都有`() {
        assertTrue(OccWords.HYGIENE.contains("脏"))
        assertTrue(OccWords.HYGIENE.contains("爱干净"))
        assertTrue(OccWords.HYGIENE.contains("讲究"))
        assertTrue(OccWords.HYGIENE.contains("卫生"))
        assertTrue(OccWords.HYGIENE.contains("清洁"))
    }
}
