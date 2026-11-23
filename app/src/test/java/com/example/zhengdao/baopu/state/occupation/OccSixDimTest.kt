// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 判据①六维齐 与 判据③「具体工种必须挂到环境／着装／负荷」。
//
// 两条分工要分清楚：三样**全缺**才是派工单说的「只列名字」⇒ 提示；
// 挂上来了却没填齐（第一期②③④是本期必做）⇒ 红。所以每个负例都指定自己该吃哪一级。
package com.example.zhengdao.baopu.state.occupation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OccSixDimTest {

    private val rebar = OccupationTable.byKey("rebar")!!

    @Test
    fun `正例_每行六维五格都非空`() {
        OccupationTable.ROWS.forEach { row ->
            val six = row.six
            assertTrue("${row.key} 六维整格缺失", six != null)
            assertEquals("${row.key} 六维缺格：" + six!!.missingNames(), emptyList<String>(), six.missingNames())
            assertTrue("${row.key} 没挂行业", row.industry != null)
        }
    }

    @Test
    fun `正例_每行三挂都在且子格齐`() {
        OccupationTable.ROWS.forEach { row ->
            assertTrue("${row.key} 缺环境", row.env != null)
            assertEquals("${row.key} 环境缺格：" + row.env!!.missingNames(), emptyList<String>(), row.env!!.missingNames())
            assertTrue("${row.key} 缺着装", row.attire != null)
            assertTrue("${row.key} 防护件清单没显式给", row.attire!!.items != null)
            assertTrue("${row.key} 开合形态没给", row.attire!!.closure != null)
            assertEquals("${row.key} 负荷缺格：" + row.burden!!.missingNames(), emptyList<String>(), row.burden!!.missingNames())
        }
    }

    @Test
    fun `负例_六维整格抽掉必红`() {
        val findings = OccupationGate.check(listOf(rebar.copy(six = null)))
        val c1 = findings.filter { it.rule == "C1" }
        assertEquals(1, c1.size)
        assertEquals(OccSeverity.ERROR, c1.first().severity)
        assertTrue(c1.first().message.contains("六维整格缺失"))
    }

    @Test
    fun `负例_六维少一格且指名是哪一格`() {
        val findings = OccupationGate.check(listOf(rebar.copy(six = rebar.six!!.copy(risk = null))))
        val c1 = findings.filter { it.rule == "C1" }
        assertEquals("只缺危险暴露这一格", 1, c1.size)
        assertTrue(c1.first().message.contains("危险暴露"))
    }

    @Test
    fun `负例_整表改一半按行数红`() {
        val half = OccupationTable.ROWS.mapIndexed { idx, row ->
            if (idx % 2 == 0) row.copy(six = row.six!!.copy(sched = null)) else row
        }
        val expected = (OccupationTable.ROWS.size + 1) / 2
        val c1 = OccupationGate.check(half).filter { it.rule == "C1" && it.message.contains("六维缺格") }
        assertEquals("偶数行各缺作息一格", expected, c1.size)
    }

    @Test
    fun `负例_三样全缺是提示不是红`() {
        val findings = OccupationGate.check(
            listOf(rebar.copy(env = null, attire = null, burden = null)),
        )
        val c2 = findings.filter { it.rule == "C2" }
        assertEquals(1, c2.size)
        assertEquals("派工单把「只列名字」定为提示", OccSeverity.WARN, c2.first().severity)
        assertTrue(c2.first().message.contains("只列名字"))
    }

    @Test
    fun `负例_只缺环境一样是红`() {
        val c2 = OccupationGate.check(listOf(rebar.copy(env = null))).filter { it.rule == "C2" }
        assertEquals(1, c2.size)
        assertEquals(OccSeverity.ERROR, c2.first().severity)
        assertTrue(c2.first().message.contains("环境"))
    }

    @Test
    fun `负例_环境六格缺一格也是红`() {
        val c2 = OccupationGate.check(
            listOf(rebar.copy(env = rebar.env!!.copy(chem = null))),
        ).filter { it.rule == "C2" }
        assertEquals(1, c2.size)
        assertTrue(c2.first().message.contains("环境细节缺格"))
        assertTrue(c2.first().message.contains("化学"))
    }

    @Test
    fun `负例_防护件清单与开合形态都不给是两条红`() {
        val c2 = OccupationGate.check(
            listOf(rebar.copy(attire = AttireProfile(null, null))),
        ).filter { it.rule == "C2" }
        assertEquals(2, c2.size)
        assertTrue(c2.any { it.message.contains("防护件清单缺失") })
        assertTrue(c2.any { it.message.contains("开合形态缺格") })
    }

    @Test
    fun `负例_空防护清单是合法显式不给`() {
        // 机加工不该戴手套（旋转件），空表是结论不是漏填：抽掉 items 才红，给空表不红。
        val machinist = OccupationTable.byKey("machinist")!!
        assertEquals(0, OccupationGate.check(listOf(machinist)).size)
        val stripped = OccupationGate.check(
            listOf(machinist.copy(attire = machinist.attire!!.copy(items = null))),
        ).filter { it.rule == "C2" }
        assertEquals(1, stripped.size)
    }

    @Test
    fun `负例_负荷四格缺一格指名`() {
        val c2 = OccupationGate.check(
            listOf(rebar.copy(burden = rebar.burden!!.copy(shift = null))),
        ).filter { it.rule == "C2" }
        assertEquals(1, c2.size)
        assertTrue(c2.first().message.contains("作息细档"))
    }

    @Test
    fun `负例_驱动整格抽掉也是红`() {
        val c3 = OccupationGate.check(listOf(rebar.copy(drives = null))).filter { it.rule == "C3" }
        assertEquals(1, c3.size)
        assertTrue(c3.first().message.contains("驱动面整格缺失"))
    }
}
