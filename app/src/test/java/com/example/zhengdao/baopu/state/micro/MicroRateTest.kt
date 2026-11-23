// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十一批 · 判据①（速率档）：六轴齐全、每条有 rate/edge、骤变须给事件；含负例「改一半」。
package com.example.zhengdao.baopu.state.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicroRateTest {

    @Test
    fun `正例_六轴齐全且各有rate与edge`() {
        val findings = AxisRateTable.check()
        assertEquals("生产表零发现", 0, findings.size)
        assertEquals(6, AxisRateTable.PROFILES.size)
        assertEquals(6, DynamicAxis.COUNT)
        assertEquals(3, ChangeRate.COUNT)
        assertEquals(4, ChangeEdge.COUNT)
        AxisRateTable.PROFILES.forEach { p ->
            assertTrue("${p.axis.label} 有 rate", p.rate != null)
            assertTrue("${p.axis.label} 有 edge", p.edge != null)
        }
    }

    @Test
    fun `负例_改一半rate置空必红`() {
        // 六条里把三条的 rate 置空 => 至少三条 M1 error。
        val half = AxisRateTable.PROFILES.mapIndexed { idx, p ->
            if (idx % 2 == 0) p.copy(rate = null) else p
        }
        val findings = AxisRateTable.check(half)
        val rateErrors = findings.filter { it.rule == "M1" && it.message.contains("缺 rate") }
        assertEquals("三条被置空 ⇒ 三条缺 rate", 3, rateErrors.size)
        assertTrue(findings.all { it.severity == MicroSeverity.ERROR })
    }

    @Test
    fun `负例_缺一轴必红且指名`() {
        val five = AxisRateTable.PROFILES.dropLast(1)
        val findings = AxisRateTable.check(five)
        assertTrue("缺轴要报未登记", findings.any { it.message.contains("未登记") })
    }

    @Test
    fun `负例_骤变去事件必红`() {
        val noEvent = AxisRateTable.PROFILES.map { p ->
            if (p.edge == ChangeEdge.SUDDEN) p.copy(event = null) else p
        }
        val findings = AxisRateTable.check(noEvent)
        assertEquals("骤变去事件 ⇒ 红", 1, findings.filter { it.message.contains("骤变缺事件") }.size)
    }

    @Test
    fun `正例_只有骤变吃事件约束线性不需事件`() {
        val linearNoEvent = AxisRateTable.PROFILES.map { p ->
            if (p.edge == ChangeEdge.LINEAR) p.copy(event = null) else p
        }
        assertEquals("线性档无需事件仍零发现", 0, AxisRateTable.check(linearNoEvent).size)
    }

    @Test
    fun `负例_重复登记同一轴必红`() {
        val dup = AxisRateTable.PROFILES + AxisRateTable.PROFILES.first()
        val findings = AxisRateTable.check(dup)
        assertTrue(findings.any { it.message.contains("重复登记") })
    }
}
