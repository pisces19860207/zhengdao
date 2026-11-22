// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百三十一批 · 判据④（未定率可复算、>5% 视为档表缺陷并提示补档）的机器证据。
// 正例与负例都测：恰好 5% 不算缺陷，严格大于才算；空样本当场红。
package com.example.zhengdao.baopu.state.prepuce

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PrepuceUnknownStatsTest {

    /** 造 n 条结论，其中 u 条为带治理字段的未定。 */
    private fun sample(n: Int, u: Int): List<CoverageArea> = (0 until n).map { i ->
        if (i < u) {
            CoverageArea(null, "X1", "零星覆盖", "半区计 1–3", UnknownAction.HUMAN_FILL)
        } else {
            CoverageArea(AreaLevel.FULL_COVER, "A1", "龟头五区全 covered")
        }
    }

    @Test
    fun `判据四_零未定不算缺陷`() {
        val report = PrepuceUnknownStats.of(sample(20, 0))
        assertEquals(20, report.total)
        assertEquals(0, report.unresolved)
        assertEquals(0.0, report.percent, 1e-9)
        assertFalse(report.isDefect)
        assertNull(report.hint)
    }

    @Test
    fun `判据四_恰好百分之五不算缺陷`() {
        val report = PrepuceUnknownStats.of(sample(20, 1))
        assertEquals(5.0, report.percent, 1e-9)
        assertFalse("严格大于才算缺陷，正好 5% 不红", report.isDefect)
        assertNull(report.hint)
    }

    @Test
    fun `判据四_超过百分之五视为档表缺陷并提示补档`() {
        val report = PrepuceUnknownStats.of(sample(20, 2))
        assertEquals(10.0, report.percent, 1e-9)
        assertTrue("10% > 5% 必须报缺陷", report.isDefect)
        assertEquals(PrepuceUnknownStats.DEFECT_HINT, report.hint)
        assertTrue("提示须要求补档且不许长期挂着",
            report.hint?.contains("补档") == true && report.hint?.contains("不许长期") == true)
        assertTrue("快照须带上提示", report.line().contains("补档"))
    }

    @Test
    fun `判据四_二十比十九越过红线`() {
        val report = PrepuceUnknownStats.of(sample(19, 1))
        assertTrue("1/19≈5.26% 必须越线", report.isDefect)
        assertEquals(PrepuceUnknownStats.DEFECT_HINT, report.hint)
    }

    @Test
    fun `判据四_未定率逐条可复算`() {
        val results = sample(40, 3)
        val report = PrepuceUnknownStats.of(results)
        val recount = results.count { !it.resolved }
        assertEquals(recount, report.unresolved)
        assertEquals(recount * 100.0 / results.size, report.percent, 1e-9)
        assertEquals(40, report.total)
    }

    @Test
    fun `判据四_空样本与越界计数当场红`() {
        val empty = runCatching { PrepuceUnknownStats.of(emptyList()) }.exceptionOrNull()
        assertEquals(PrepuceUnknownStats.EMPTY_SAMPLE, empty?.message)

        val badCount = runCatching {
            PrepuceUnknownStats.Report(total = 2, unresolved = 3, percent = 150.0, isDefect = true,
                hint = PrepuceUnknownStats.DEFECT_HINT)
        }.exceptionOrNull()
        assertEquals(PrepuceUnknownStats.BAD_COUNT, badCount?.message)

        val badHint = runCatching {
            PrepuceUnknownStats.Report(total = 20, unresolved = 2, percent = 10.0, isDefect = true, hint = null)
        }.exceptionOrNull()
        assertEquals(PrepuceUnknownStats.Report.HINT_MISMATCH, badHint?.message)
    }

    @Test
    fun `判据四_同一批结论两跑逐字一致`() {
        val results = sample(20, 2)
        assertEquals(PrepuceUnknownStats.of(results), PrepuceUnknownStats.of(results))
        assertEquals(PrepuceUnknownStats.of(results).line(), PrepuceUnknownStats.of(results).line())
    }
}
