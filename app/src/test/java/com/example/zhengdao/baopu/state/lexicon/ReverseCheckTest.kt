// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十一批 · 判据③④⑤ + 反向校验：三距离层齐、每条标区间、同档≥3写法、
// 重复句≤1、抽20条判回≥16；含负例（无信号词判不出、阈值抬到21判红、重复文本判红）。
package com.example.zhengdao.baopu.state.lexicon

import com.example.zhengdao.baopu.state.micro.MicroSeverity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ReverseCheckTest {

    // —— 判据③：三距离层齐、每条标区间 ——
    @Test
    fun `判据三_四档三层各五条共六十条`() {
        assertEquals(0, checkLayerCoverage().size)
        assertEquals(60, LexiconCorpus.ENTRIES.size)
        assertEquals(3, DistanceBand.COUNT)
        for (grade in 0..3) {
            for (band in DistanceBand.ALL) {
                assertEquals(
                    "$grade 档/${band.label} 应 5 条",
                    5,
                    LexiconCorpus.ENTRIES.count { it.grade == grade && it.band == band },
                )
            }
        }
        assertTrue("每条都有合法区间", LexiconCorpus.ENTRIES.all { it.span.lo in 0..3 && it.span.hi in 0..3 })
    }

    @Test
    fun `负例_删薄一层必红`() {
        val thin = LexiconCorpus.ENTRIES.filterNot { it.grade == 2 && it.band == DistanceBand.FAR }
        val findings = checkLayerCoverage(thin)
        assertTrue(findings.any { it.message.contains("2 档/远景") && it.message.contains("须 ≥5") })
        assertTrue(findings.all { it.severity == MicroSeverity.ERROR })
    }

    @Test
    fun `负例_空文本必红`() {
        val broken = LexiconCorpus.ENTRIES.mapIndexed { idx, e ->
            if (idx == 0) e.copy(text = "") else e
        }
        assertTrue(checkLayerCoverage(broken).any { it.message.contains("空") })
    }

    // —— 判据⑤：同档≥3写法 + 重复句≤1 ——
    @Test
    fun `判据五_同档多种写法且全批无重复`() {
        assertEquals(0, checkVariety().size)
        for (grade in 0..3) {
            val distinct = LexiconCorpus.ENTRIES.filter { it.grade == grade }.map { it.text }.toSet().size
            assertTrue("$grade 档 ≥3 种写法", distinct >= 3)
        }
        assertEquals("60 条 id 全唯一", 60, LexiconCorpus.ENTRIES.map { it.id }.toSet().size)
    }

    @Test
    fun `负例_重复文本判红`() {
        val dup = LexiconCorpus.ENTRIES.toMutableList()
        dup[1] = dup[0].copy(id = "dup-id") // 文本与第 0 条相同、id 不同 ⇒ 只触发重复文本
        val findings = checkVariety(dup)
        assertTrue(findings.any { it.message.contains("重复文本") })
    }

    @Test
    fun `负例_同档写法砍到两种判红`() {
        val thin = LexiconCorpus.ENTRIES.filterNot {
            it.grade == 0 && it.band == DistanceBand.FAR
        }.filterNot {
            it.grade == 0 && it.band == DistanceBand.MID
        }
        // 0 档只剩近景 5 条，仍 ≥3；再造只有 2 种的极端列表
        val two = thin.filter { it.grade != 0 } + List(2) { LexiconCorpus.ENTRIES[0] }
        assertTrue(checkVariety(two).any { it.message.contains("0 档只有") })
    }

    // —— 判据④：反向校验 ——
    @Test
    fun `判据四_抽二十条判回命中达门槛`() {
        val report = reverseCheck()
        System.err.println("ZD821_REVERSE_HITS=${report.hits}/${report.sampled} threshold=${report.threshold}")
        assertEquals(20, report.sampled)
        assertTrue("命中 ${report.hits}/20 须 ≥${report.threshold}", report.pass)
        assertTrue("命中数 ≥16", report.hits >= 16)
        assertEquals(20, report.details.size)
    }

    @Test
    fun `判据四_同输入逐字节可复现`() {
        val a = reverseCheck()
        val b = reverseCheck()
        assertEquals(a.hits, b.hits)
        assertEquals(a.details.map { it.first.id }, b.details.map { it.first.id })
    }

    @Test
    fun `负例_无信号词文本判不出且命中崩塌判红`() {
        val blank = LexiconCorpus.ENTRIES.map { it.copy(text = "无信号的普通句子") }
        val report = reverseCheck(blank)
        assertEquals(0, report.hits)
        assertFalse("命中 0 < 16 ⇒ 红", report.pass)
    }

    @Test
    fun `负例_阈值抬到二十一必红_证明阈值真咬`() {
        val report = reverseCheck(threshold = 21)
        assertFalse("最高 20 < 21 ⇒ 必红", report.pass)
    }

    @Test
    fun `反向判回只读文本不偷看档位标注`() {
        // 文本含「剧烈」(3)，但 entry.grade 故意标 0：判回仍须是 3（证明没偷看 grade）。
        val fake = LexiconEntry("fake", grade = 0, span = GradeSpan.G0, band = DistanceBand.NEAR, axis = "x", text = "剧烈起伏")
        assertEquals(3, inferGrade(fake.text).grade)
    }

    @Test
    fun `无信号词返回null`() {
        assertNull(inferGrade("一切照常，没有变化").grade)
    }

    @Test
    fun `信号词取最高档_多词共存时不取低档`() {
        assertEquals(3, inferGrade("先微微一动，继而剧烈起伏").grade)
    }
}
