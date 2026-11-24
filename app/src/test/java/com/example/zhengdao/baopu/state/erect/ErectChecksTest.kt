// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第八百一十批 · 判据逐条：①六件齐 ②硬度尺寸独立 ③禁数值 ④包皮位804 ⑤占位。
package com.example.zhengdao.baopu.state.erect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ErectChecksTest {

    @Test
    fun `判据一_六件齐出账通过`() {
        val f = ErectChecks.completeness(ErectFixtures.person())
        assertEquals(ErectVerdict.PASS, f.verdict)
        assertEquals("ERECT-COMPLETE", f.code)
        assertTrue(f.why.contains("六件齐"))
    }

    @Test
    fun `判据二_硬度与尺寸独立_通过`() {
        val f = ErectChecks.hardnessIndependent(ErectFixtures.person())
        assertEquals(ErectVerdict.PASS, f.verdict)
        assertEquals("HARDNESS-INDEPENDENT", f.code)
    }

    @Test
    fun `判据三_禁数值_通过`() {
        val f = ErectChecks.noNumbers(ErectFixtures.person())
        assertEquals(ErectVerdict.PASS, f.verdict)
        assertEquals("NO-NUMBERS", f.code)
    }

    @Test
    fun `判据四_包皮位取804六档_通过`() {
        val f = ErectChecks.prepuceFrom804(ErectFixtures.person())
        assertEquals(ErectVerdict.PASS, f.verdict)
        assertEquals("PREPUCE-FROM-804", f.code)
    }

    @Test
    fun `判据五_占位逐字在册_通过`() {
        val f = ErectChecks.pendingValid(ErectFixtures.person())
        assertEquals(ErectVerdict.PASS, f.verdict)
        assertEquals("PENDING-OK", f.code)
    }

    @Test
    fun `all五条全跑_顺序固定`() {
        val findings = ErectChecks.all(ErectFixtures.person())
        assertEquals(5, findings.size)
        assertEquals(
            listOf("ERECT-COMPLETE", "HARDNESS-INDEPENDENT", "NO-NUMBERS", "PREPUCE-FROM-804", "PENDING-OK"),
            findings.map { it.code },
        )
        assertEquals(ErectVerdict.PASS, ErectChecks.worst(findings))
    }

    @Test
    fun `红压提示压通过_worst取最严`() {
        val verdictWorst = { vs: List<ErectVerdict> ->
            vs.fold(ErectVerdict.PASS) { acc, v -> ErectVerdict.worst(acc, v) }
        }
        assertEquals(ErectVerdict.DENY, verdictWorst(listOf(ErectVerdict.PASS, ErectVerdict.DENY, ErectVerdict.HINT)))
        assertEquals(ErectVerdict.HINT, verdictWorst(listOf(ErectVerdict.PASS, ErectVerdict.HINT)))
        // 从 findings 取最严走的是另一条入口
        val findings = ErectChecks.all(ErectFixtures.person())
        assertEquals(ErectVerdict.PASS, ErectChecks.worst(findings))
    }

    @Test
    fun `summary逐条计数`() {
        val findings = ErectChecks.all(ErectFixtures.person())
        assertEquals("通过 5／提示 0／红 0", ErectChecks.summary(findings))
    }

    @Test
    fun `sceneLine汇总与逐条_过闸`() {
        val line = ErectChecks.sceneLine(ErectFixtures.person())
        assertTrue(line.contains("通过 5"))
        assertTrue(line.contains("[通过] ERECT-COMPLETE"))
        assertTrue(line.contains(ERECT_SOURCE))
    }

    @Test
    fun `判据二_导致硬的独立判据可被单独触发`() {
        // 用一张硬度行合法的正常单，硬度判据为通过
        val p = ErectFixtures.person()
        assertEquals(ErectVerdict.PASS, ErectChecks.hardnessIndependent(p).verdict)
        // HardnessState 结构里没有 size 字段的编译期证明见 ErectReadingTest
    }
}
