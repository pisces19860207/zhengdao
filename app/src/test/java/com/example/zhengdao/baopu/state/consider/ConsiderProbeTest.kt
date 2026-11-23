// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十四批 · ConsiderLog 覆盖扩展（批次 1）：产生器降采样探针测试。
package com.example.zhengdao.baopu.state.consider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ConsiderProbeTest {

    @Test
    fun 判据五_8192全量不进主池_单次写入远低于2000() {
        val log = ConsiderLog(capacity = 3000)
        val probe = ConsiderProbe("g", 8192, ConsiderStrategy.STRIDE, log, maxRecords = 64)
        repeat(8192) { probe.record({ "d$it" }, ConsiderVerdict.BELOW_BAR, false) }
        assertTrue("单次写入不得超过 2000", probe.writtenMain() <= ConsiderLog.MAX_WRITE_PER_CALL)
        assertEquals("stride 硬顶 64", 64, probe.writtenMain())
        assertEquals("主池只留降采样那批", 64, log.size())
        assertTrue("每条 sampledFrom=8192", log.query().all { it.sampledFrom == 8192 })
        assertTrue("每条 strategy=stride", log.query().all { it.strategy == ConsiderStrategy.STRIDE })
    }

    @Test
    fun 降采样_firstK留头_stride等距_reservoir确定() {
        fun run(s: ConsiderStrategy) = ConsiderLog(capacity = 2000).let { log ->
            val p = ConsiderProbe("g", 100, s, log, maxRecords = 10)
            (0 until 100).forEach { p.record({ "d$it" }, ConsiderVerdict.ACCEPTED, false) }
            log.query().map { it.inputDigest }
        }
        val firstK = run(ConsiderStrategy.FIRST_K)
        assertEquals((0 until 10).map { "d$it" }, firstK)
        val stride = run(ConsiderStrategy.STRIDE)
        assertEquals((0 until 100 step 10).map { "d$it" }, stride)
        assertEquals("reservoir 两次必须逐字节一致", run(ConsiderStrategy.RESERVOIR), run(ConsiderStrategy.RESERVOIR))
        assertEquals(10, run(ConsiderStrategy.RESERVOIR).size)
        assertEquals("diagnosticOnly 不进主池", 0, run(ConsiderStrategy.DIAGNOSTIC_ONLY).size)
    }

    @Test
    fun 判据二_诊断关_无diag池无diag文件() {
        val log = ConsiderLog(capacity = 2000)
        val probe = ConsiderProbe("g", 8192, ConsiderStrategy.STRIDE, log, diagnostic = false)
        repeat(64) { probe.record({ "d$it" }, ConsiderVerdict.ACCEPTED, true) }
        assertEquals(null, probe.diag)
        assertEquals(0, probe.diagSize())
        val target = File.createTempFile("zd844-diag-off-", ".jsonl")
        try {
            assertEquals("开关关 ⇒ 导出 0 行且不碰文件", 0, probe.exportDiag(target))
            assertEquals("开关关 ⇒ 文件不被写入", 0L, target.length())
        } finally {
            target.delete()
        }
    }

    @Test
    fun 判据四_诊断开_diag独立文件不混主池() {
        val log = ConsiderLog(capacity = 2000)
        val probe = ConsiderProbe("g", 100, ConsiderStrategy.STRIDE, log, maxRecords = 10, diagnostic = true)
        (0 until 100).forEach { i -> probe.record({ "d$i" }, ConsiderVerdict.BELOW_BAR, i % 2 == 0) }
        assertEquals(10, probe.writtenMain())
        assertTrue("diag 圈内 50 条全记", probe.diagSize() == 50)
        assertTrue("主池策略全是 stride", log.query().all { it.strategy == ConsiderStrategy.STRIDE })
        val diagLog = probe.diag!!
        assertTrue("diag 池策略全是 diagnosticOnly", diagLog.query().all { it.strategy == ConsiderStrategy.DIAGNOSTIC_ONLY })
        assertFalse("主池里不许出现 diagnosticOnly", log.query(strategy = ConsiderStrategy.DIAGNOSTIC_ONLY).isNotEmpty())

        val mainFile = File.createTempFile("zd844-main-", ".jsonl")
        val diagFile = File.createTempFile("zd844-diag-on-", ".jsonl")
        try {
            assertEquals(10, probe.exportMain(mainFile))
            assertEquals(50, probe.exportDiag(diagFile))
            val mainLines = mainFile.readLines().filter { it.isNotBlank() }
            val diagLines = diagFile.readLines().filter { it.isNotBlank() }
            assertFalse("diag 文件不许混进主池行", diagLines.any { mainLines.contains(it) })
            assertTrue("diag 文件行带 diagnosticOnly", diagLines.any { it.contains("\"strategy\":\"diagnosticOnly\"") })
        } finally {
            mainFile.delete()
            diagFile.delete()
        }
    }

    @Test
    fun 判据六_确定性_同输入同输出_含诊断() {
        fun run(): Pair<String, String> {
            val log = ConsiderLog(capacity = 2000)
            val p = ConsiderProbe("g", 200, ConsiderStrategy.RESERVOIR, log, maxRecords = 16, diagnostic = true, baseAtMs = 100L)
            (0 until 200).forEach { i -> p.record({ "d$i" }, ConsiderVerdict.ACCEPTED, i % 3 == 0) }
            return log.toJsonl() to (p.diag?.toJsonl() ?: "")
        }
        val a = run()
        val b = run()
        assertEquals("主池逐字节一致", a.first, b.first)
        assertEquals("diag 逐字节一致", a.second, b.second)
    }

    @Test
    fun 硬顶_safeFail_单次不超2000() {
        val log = ConsiderLog(capacity = 3000)
        val probe = ConsiderProbe("g", 8192, ConsiderStrategy.STRIDE, log, maxRecords = 1999)
        repeat(8192) { probe.record({ "d$it" }, ConsiderVerdict.ACCEPTED, false) }
        assertEquals(1999, probe.writtenMain())
        assertTrue(probe.writtenMain() <= ConsiderLog.MAX_WRITE_PER_CALL)
    }

    @Test
    fun 命名_诊断文件名带日期且独立() {
        assertEquals("consider-2026-11-23-diag.jsonl", diagFileName("2026-11-23"))
        assertEquals("consider-2026-11-23.jsonl", mainFileName("2026-11-23"))
        assertFalse(diagFileName("2026-11-23") == mainFileName("2026-11-23"))
    }
}
