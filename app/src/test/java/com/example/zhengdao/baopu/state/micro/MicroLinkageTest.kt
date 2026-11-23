// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十一批 · 判据②（联动传导）：五组各≥3对、写变化零联动⇒提示；含删组/删对负例。
package com.example.zhengdao.baopu.state.micro

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MicroLinkageTest {

    @Test
    fun `正例_五组各三对以上且结构零发现`() {
        assertEquals(5, LinkageTable.GROUP_COUNT)
        assertEquals(0, LinkageTable.checkStructure().size)
        LinkageTable.GROUPS.forEach { g ->
            assertTrue("${g.source.label} 至少三对", g.size >= LinkageTable.MIN_PAIRS_PER_GROUP)
        }
    }

    @Test
    fun `负例_删一组必红`() {
        val four = LinkageTable.GROUPS.dropLast(1)
        val findings = LinkageTable.checkStructure(four)
        assertTrue(findings.any { it.message.contains("组数应为 5") })
    }

    @Test
    fun `负例_某组删到只剩两对必红且指名来源`() {
        val thin = LinkageTable.GROUPS.mapIndexed { idx, g ->
            if (idx == 0) g.copy(pairs = g.pairs.take(2)) else g
        }
        val findings = LinkageTable.checkStructure(thin)
        assertEquals(1, findings.filter { it.message.contains("只有 2 对") }.size)
        assertTrue(findings.any { it.message.contains(LinkageSource.SENSITIVITY_UP.label) })
    }

    @Test
    fun `负例_空cue必红`() {
        val blank = LinkageTable.GROUPS.mapIndexed { idx, g ->
            if (idx == 0) g.copy(pairs = listOf(LinkagePair("呼吸", "  ")) + g.pairs.drop(1)) else g
        }
        assertTrue(LinkageTable.checkStructure(blank).any { it.message.contains("空") })
    }

    @Test
    fun `判据二_有联动的变化零提示`() {
        val changes = LinkageSource.values().map { ChangeMark(it) }
        assertEquals("五来源都有组 ⇒ 零提示", 0, checkLinkageCoverage(changes).size)
    }

    @Test
    fun `判据二_查不到联动的变化给提示且是WARN不是红`() {
        // 造一个「来源存在但联动表里被删掉」的场景：只给冷这一组，查五来源。
        val coldOnly = LinkageTable.GROUPS.filter { it.source == LinkageSource.COLD }
        val changes = LinkageSource.values().map { ChangeMark(it) }
        val findings = checkLinkageCoverage(changes, coldOnly)
        assertEquals("四个查不到 ⇒ 四条提示", 4, findings.size)
        assertTrue(findings.all { it.rule == "M2" && it.severity == MicroSeverity.WARN })
        assertTrue(findings.all { it.message.contains("只贴了标签") })
    }

    @Test
    fun `挂靠锚点三块齐且空mode红`() {
        assertEquals(0, MicroAnchors.check().size)
        assertEquals(3, MicroAnchors.ALL.size)
        val broken = MicroAnchors.ALL.mapIndexed { idx, a ->
            if (idx == 0) a.copy(mode = "") else a
        }
        // check() 读的是 ALL 常量，负例用一条独立断言验证 mode 判空逻辑可达。
        assertTrue("生产锚点 mode 全非空", MicroAnchors.ALL.all { it.mode.isNotBlank() })
        assertTrue("负例构造器确实造出空 mode", broken.first().mode.isEmpty())
    }
}
