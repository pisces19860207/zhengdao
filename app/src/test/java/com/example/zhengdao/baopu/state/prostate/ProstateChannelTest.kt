// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百三十五批 · 行为判据：感觉档四档与力学分离、三分类缺一不许出事件、
// unverified 默认关且放行带「未核实」、默认归流出、射出须显式条件、
// 流出行零节律词、唯一出口带非医疗判断、确定性。本测试零写盘，水位不受影响。
package com.example.zhengdao.baopu.state.prostate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ProstateChannelTest {

    private fun lineOf(o: ProstateOutcome): String {
        assertTrue("期望事件行，实得拒绝：$o", o is ProstateOutcome.Event)
        return (o as ProstateOutcome.Event).line
    }

    @Test
    fun `判据一_感觉通道严格四档且档名用派工单原词`() {
        assertEquals(4, ProstateSensation.COUNT)
        assertEquals(4, ProstateSensation.values().size)
        val labels = ProstateSensation.values().map { it.label }
        assertEquals(listOf("无感", "深处压感", "酸胀扩散", "沿会阴放射"), labels)
        for (s in ProstateSensation.values()) {
            assertTrue("${s.label} 缺可观察特征", s.observable.isNotBlank())
            assertTrue("${s.label} 依据须带来源", s.source.isNotBlank())
            assertEquals("2026-11-20", s.asOf)
        }
    }

    @Test
    fun `判据一_感觉出口没有任何冲击档入口`() {
        // 感觉档只由档位直接给：of 越界钳最重档，不存在 tier→sensation 的映射入口。
        assertEquals(ProstateSensation.RADIATE, ProstateSensation.of(99))
        assertEquals(ProstateSensation.NONE, ProstateSensation.of(-1))
        val kinds = ProstateChannel.emit(ProstateSensation.DEEP_PRESS, TriggerClass.EXTERNAL)
        assertTrue("默认出口须是流出事件：$kinds", kinds is ProstateOutcome.Event)
    }

    @Test
    fun `判据三_触发三分类缺一不许出事件`() {
        assertEquals(3, TriggerClass.COUNT)
        assertEquals(3, TriggerClass.values().size)
        assertEquals(listOf("external", "self", "unverified"), TriggerClass.values().map { it.id })
        for (sensation in ProstateSensation.values()) {
            for (kind in ExpulsionKind.values()) {
                val r = ProstateChannel.emit(
                    sensation, null, kind,
                    emissionCondition = if (kind == ExpulsionKind.EMISSION) "提肛收缩时" else null,
                )
                assertTrue("trigger 缺失必须拒：$sensation/$kind", r is ProstateOutcome.Rejected)
                assertTrue("拒绝理由须点明三分类：$r", "三分类" in (r as ProstateOutcome.Rejected).reason)
            }
        }
    }

    @Test
    fun `判据三_unverified默认不产生`() {
        val r = ProstateChannel.emit(ProstateSensation.SPREAD, TriggerClass.UNVERIFIED)
        assertTrue("默认必须不产生：$r", r is ProstateOutcome.Rejected)
        assertTrue("理由须带默认关口径：$r", "默认关" in (r as ProstateOutcome.Rejected).reason)
    }

    @Test
    fun `判据三_unverified放行必带未核实`() {
        val line = lineOf(
            ProstateChannel.emit(ProstateSensation.SPREAD, TriggerClass.UNVERIFIED, allowUnverified = true)
        )
        assertTrue("放行行须带未核实：$line", UNVERIFIED_TAG in line)
        assertTrue("须写明无任何刺激：$line", "无任何刺激" in line)
        // 另两档放行不带未核实。
        for (t in listOf(TriggerClass.EXTERNAL, TriggerClass.SELF)) {
            val l = lineOf(ProstateChannel.emit(ProstateSensation.NONE, t))
            assertFalse("$t 不该带未核实：$l", UNVERIFIED_TAG in l)
        }
    }

    @Test
    fun `判据二_前列腺相关默认归流出`() {
        for (sensation in ProstateSensation.values()) {
            val line = lineOf(ProstateChannel.emit(sensation, TriggerClass.SELF))
            assertTrue("默认须是流出：$line", "流出" in line)
            assertFalse("默认不得写射出：$line", "射出" in line)
        }
    }

    @Test
    fun `判据二_写射出不给触发条件必须拒`() {
        for (cond in listOf(null, "", "   ")) {
            val r = ProstateChannel.emit(ProstateSensation.DEEP_PRESS, TriggerClass.EXTERNAL, ExpulsionKind.EMISSION, emissionCondition = cond)
            assertTrue("cond=$cond 须拒", r is ProstateOutcome.Rejected)
            assertTrue("理由须点明显式条件：$r", "显式给触发条件" in (r as ProstateOutcome.Rejected).reason)
        }
        val line = lineOf(
            ProstateChannel.emit(ProstateSensation.DEEP_PRESS, TriggerClass.EXTERNAL, ExpulsionKind.EMISSION, emissionCondition = "提肛收缩时")
        )
        assertTrue("射出行须写明射出：$line", "射出" in line)
        assertTrue("射出须带由强到弱：$line", "由强到弱" in line)
        assertTrue("条件须进事件行：$line", "提肛收缩时" in line)
    }

    @Test
    fun `判据二_流出只有量少两档`() {
        assertEquals(2, OutflowVolume.COUNT)
        assertEquals(listOf("微量", "少量"), OutflowVolume.values().map { it.label })
        assertEquals(OutflowVolume.TRACE, OutflowVolume.of(null))
        assertEquals(OutflowVolume.SMALL, OutflowVolume.of(1))
        assertEquals(OutflowVolume.SMALL, OutflowVolume.of(9))
    }

    @Test
    fun `判据_流出行零节律词`() {
        for (sensation in ProstateSensation.values()) {
            for (volume in OutflowVolume.values()) {
                for (t in listOf(TriggerClass.EXTERNAL, TriggerClass.SELF)) {
                    val line = lineOf(ProstateChannel.emit(sensation, t, ExpulsionKind.OUTFLOW, volume))
                    for (w in RHYTHM_WORDS) assertFalse("流出行含节律词 $w：$line", w in line)
                }
            }
        }
        // 默认档（kind=null）也是流出，同样扫一遍。
        val d = lineOf(ProstateChannel.emit(ProstateSensation.RADIATE, TriggerClass.SELF, allowUnverified = true))
        assertTrue("默认流出未核实行也须干净", RHYTHM_WORDS.none { it in d })
    }

    @Test
    fun `判据_每条事件行自动带非医疗判断`() {
        for (sensation in ProstateSensation.values()) {
            for (t in TriggerClass.values()) {
                val line = lineOf(
                    ProstateChannel.emit(sensation, t, allowUnverified = true, emissionCondition = "提肛收缩时")
                )
                assertTrue("事件行须以非医疗判断收尾：$line", line.endsWith("（非医疗判断）"))
            }
        }
    }

    @Test
    fun `同输入两次出口逐字节一致`() {
        val a = ProstateChannel.emit(ProstateSensation.SPREAD, TriggerClass.EXTERNAL, ExpulsionKind.OUTFLOW, OutflowVolume.SMALL)
        val b = ProstateChannel.emit(ProstateSensation.SPREAD, TriggerClass.EXTERNAL, ExpulsionKind.OUTFLOW, OutflowVolume.SMALL)
        assertEquals(a, b)
        val c = ProstateChannel.emit(ProstateSensation.NONE, TriggerClass.UNVERIFIED)
        assertEquals(c, ProstateChannel.emit(ProstateSensation.NONE, TriggerClass.UNVERIFIED))
    }
}
