// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十四批 · ConsiderLog 覆盖扩展（批次 1）：产生器降采样探针测试。
// 口径含 2026-11-23 ERRATA：790 默认 strategy=diagnosticOnly（只收诊断圈内候选）。
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
        // ERRATA：790 默认诊断圈策略——圈内候选才算，8192 里硬顶 64。
        val probe = ConsiderProbe("g", 8192, ConsiderStrategy.DIAGNOSTIC_ONLY, log, maxRecords = 64)
        repeat(8192) { probe.record({ "d$it" }, ConsiderVerdict.BLOCKED, true) }
        assertTrue("单次写入不得超过 2000", probe.writtenMain() <= ConsiderLog.MAX_WRITE_PER_CALL)
        assertEquals("诊断圈硬顶 64", 64, probe.writtenMain())
        assertEquals("主池只留降采样那批", 64, log.size())
        assertTrue("每条 sampledFrom=8192", log.query().all { it.sampledFrom == 8192 })
        assertTrue("每条 strategy=diagnosticOnly", log.query().all { it.strategy == ConsiderStrategy.DIAGNOSTIC_ONLY })
    }

    @Test
    fun ERRATA_默认diagnosticOnly_只收圈内不收圈外() {
        fun run(inCircle: Boolean): Int {
            val log = ConsiderLog(capacity = 2000)
            val p = ConsiderProbe("g", 100, ConsiderStrategy.DIAGNOSTIC_ONLY, log, maxRecords = 64)
            (0 until 100).forEach { p.record({ "d$it" }, ConsiderVerdict.BELOW_BAR, inCircle) }
            return log.size()
        }
        assertEquals("诊断圈内才进主池", 64, run(true))
        assertEquals("诊断圈外一律不进主池（ERRATA 核心）", 0, run(false))
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
    }

    @Test
    fun 判据二_诊断关_无diag池无diag文件() {
        val log = ConsiderLog(capacity = 2000)
        val probe = ConsiderProbe("g", 8192, ConsiderStrategy.DIAGNOSTIC_ONLY, log, diagnostic = false)
        repeat(64) { probe.record({ "d$it" }, ConsiderVerdict.BLOCKED, true) }
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
        val probe = ConsiderProbe("g", 100, ConsiderStrategy.DIAGNOSTIC_ONLY, log, maxRecords = 10, diagnostic = true)
        (0 until 100).forEach { i -> probe.record({ "d$i" }, ConsiderVerdict.BLOCKED, i % 2 == 0) }
        assertEquals(10, probe.writtenMain())
        assertTrue("圈内 50 条全进 diag（诊断池更大现场）", probe.diagSize() == 50)
        assertTrue("主池策略全 diagnosticOnly", log.query().all { it.strategy == ConsiderStrategy.DIAGNOSTIC_ONLY })
        val diagLog = probe.diag!!
        assertTrue("diag 池策略全 diagnosticOnly", diagLog.query().all { it.strategy == ConsiderStrategy.DIAGNOSTIC_ONLY })
        assertFalse("主池里不许出现圈外候选（d 奇数）", log.query().any { it.inputDigest.drop(1).toInt() % 2 == 1 })

        val mainFile = File.createTempFile("zd844-main-", ".jsonl")
        val diagFile = File.createTempFile("zd844-diag-on-", ".jsonl")
        try {
            assertEquals(10, probe.exportMain(mainFile))
            assertEquals(50, probe.exportDiag(diagFile))
            val mainLines = mainFile.readLines().filter { it.isNotBlank() }
            val diagLines = diagFile.readLines().filter { it.isNotBlank() }
            // ERRATA：diag 与主池是两份独立文件/实例；同一批圈内候选可同时出现在两处，
            // 但 diag 是"更大现场"（50 > 10），且有主池没有的行。
            assertEquals("主文件 10 条", 10, mainLines.size)
            assertEquals("diag 文件 50 条", 50, diagLines.size)
            assertTrue("diag 有主池没有的行（更大现场）", diagLines.any { it !in mainLines })
            assertTrue("diag 文件行带 diagnosticOnly", diagLines.any { it.contains("\"strategy\":\"diagnosticOnly\"") })
            assertTrue("diag 文件 50 条 > 主池 10 条（更大现场）", diagLines.size > mainLines.size)
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
        val probe = ConsiderProbe("g", 8192, ConsiderStrategy.DIAGNOSTIC_ONLY, log, maxRecords = 1999)
        repeat(8192) { probe.record({ "d$it" }, ConsiderVerdict.ACCEPTED, false) }
        assertEquals("圈外不进主池", 0, probe.writtenMain())
        val log2 = ConsiderLog(capacity = 3000)
        val probe2 = ConsiderProbe("g", 8192, ConsiderStrategy.STRIDE, log2, maxRecords = 1999)
        repeat(8192) { probe2.record({ "d$it" }, ConsiderVerdict.ACCEPTED, true) }
        assertEquals(1999, probe2.writtenMain())
        assertTrue(probe2.writtenMain() <= ConsiderLog.MAX_WRITE_PER_CALL)
    }

    @Test
    fun 命名_诊断文件名带日期且独立() {
        assertEquals("consider-2026-11-23-diag.jsonl", diagFileName("2026-11-23"))
        assertEquals("consider-2026-11-23.jsonl", mainFileName("2026-11-23"))
        assertFalse(diagFileName("2026-11-23") == mainFileName("2026-11-23"))
    }
}
