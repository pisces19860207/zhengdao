// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.consider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * ConsiderLog 通用件单测（第八百一十一批）。
 *
 * 钉死工单四项硬约束：
 * - 四字段齐、原因码是**枚举**（判据②）；
 * - 滚动保留 + **淘汰计数可查**，静默截断是红（判据③）；
 * - 可查询（产生器／原因码／时间窗）、可导出 JSONL；
 * - 被否决的候选也在池里——池不是「只留入选」的荣誉榜。
 */
class ConsiderLogTest {

    private fun rec(
        gen: String = "g",
        digest: String = "d",
        verdict: ConsiderVerdict = ConsiderVerdict.ACCEPTED,
        atMs: Long = 1_000L,
    ) = ConsiderRecord(gen, digest, verdict, atMs)

    @Test
    fun `记录_四字段齐且原因码是枚举`() {
        val log = ConsiderLog(capacity = 10)
        log.record(rec("memory-refine", "以后都用中文回答", ConsiderVerdict.DUPLICATE, 1_700_000_000_000L))

        val got = log.query().single()
        assertEquals("memory-refine", got.generatorId)
        assertEquals("以后都用中文回答", got.inputDigest)
        assertEquals(ConsiderVerdict.DUPLICATE, got.verdict)
        assertEquals(1_700_000_000_000L, got.atMs)
        // 原因码闭集：只认这六个名字，自由文本进不来（类型即枚举，这里再钉一次名字面）
        assertEquals(
            listOf("ACCEPTED", "DUPLICATE", "BELOW_BAR", "INVALID", "OVER_LIMIT", "BLOCKED"),
            ConsiderVerdict.entries.map { it.name },
        )
    }

    @Test
    fun `查询_按产生器与原因码与时间窗可组合筛选`() {
        val log = ConsiderLog(capacity = 100)
        log.recordAll(
            listOf(
                rec("a", "x", ConsiderVerdict.ACCEPTED, 10),
                rec("a", "y", ConsiderVerdict.INVALID, 20),
                rec("b", "z", ConsiderVerdict.INVALID, 30),
                rec("a", "w", ConsiderVerdict.INVALID, 40),
            ),
        )

        assertEquals(4, log.query().size)
        assertEquals(listOf("x", "y", "w"), log.query(generatorId = "a").map { it.inputDigest })
        assertEquals(listOf("y", "z", "w"), log.query(verdict = ConsiderVerdict.INVALID).map { it.inputDigest })
        assertEquals(listOf("y"), log.query(generatorId = "a", verdict = ConsiderVerdict.INVALID, toMs = 25).map { it.inputDigest })
        assertEquals(listOf("w"), log.query(fromMs = 35).map { it.inputDigest })
        assertTrue(log.query(fromMs = 41).isEmpty())
    }

    @Test
    fun `滚动_满池挤掉最旧的一条且计数可查`() {
        val log = ConsiderLog(capacity = 3)
        repeat(5) { i -> log.record(rec(digest = "第${i}条", atMs = i.toLong())) }

        assertEquals(3, log.size())
        assertEquals(3, log.capacity())
        assertEquals(2L, log.evictedCount())
        assertEquals(5L, log.totalRecorded())
        // 对账恒等式：进过的 = 还在的 + 挤出去的（静默截断会打破这个式子）
        assertEquals(log.totalRecorded(), log.size().toLong() + log.evictedCount())
        // 剩下的必须是最新三条，旧的真的没了（不是「计数涨了但池里还留着」）
        assertEquals(listOf("第2条", "第3条", "第4条"), log.query().map { it.inputDigest })
        assertFalse(log.query().any { it.inputDigest == "第0条" })
    }

    @Test
    fun `滚动_扩容不改淘汰语义_恰好等于容量时不挤`() {
        val log = ConsiderLog(capacity = 2)
        log.record(rec(digest = "a"))
        log.record(rec(digest = "b"))
        assertEquals(2, log.size())
        assertEquals(0L, log.evictedCount())

        log.record(rec(digest = "c"))
        assertEquals(1L, log.evictedCount())
        assertEquals(listOf("b", "c"), log.query().map { it.inputDigest })
    }

    @Test
    fun `JSONL_每行四键且顺序固定`() {
        val log = ConsiderLog(capacity = 10)
        log.record(rec("memory-refine", "以后都用中文", ConsiderVerdict.OVER_LIMIT, 1_000L))
        log.record(rec("memory-refine", "不对", ConsiderVerdict.BELOW_BAR, 2_000L))

        val lines = log.toJsonl().split("\n").filter { it.isNotEmpty() }
        assertEquals(2, lines.size)
        assertEquals(
            "{\"generatorId\":\"memory-refine\",\"inputDigest\":\"以后都用中文\",\"verdict\":\"OVER_LIMIT\",\"atMs\":1000}",
            lines[0],
        )
        // 每行 verdict 必属枚举闭集——JSONL 里不允许冒出自由文本原因
        val names = ConsiderVerdict.entries.map { it.name }.toSet()
        lines.forEach { line ->
            val v = Regex("\"verdict\":\"([A-Z_]+)\"").find(line)?.groupValues?.get(1)
            assertTrue("verdict 不是枚举名：$line", v != null && v in names)
        }
    }

    @Test
    fun `JSONL_引号反斜杠换行按JSON转义`() {
        val log = ConsiderLog(capacity = 10)
        log.record(rec(digest = "a\"b\\c\nd\te", atMs = 1L))

        val line = log.toJsonl()
        assertTrue(line, line.contains("a\\\"b\\\\c\\nd\\te"))
        // 行数必须仍是 1（换行没把一条记录撑成两行）
        assertEquals(1, line.split("\n").count { it.isNotEmpty() })
    }

    @Test
    fun `导出_文件行数与池内条数一致`() {
        val log = ConsiderLog(capacity = 10)
        log.recordAll(listOf(rec(digest = "a"), rec(digest = "b")))
        val tmp = Files.createTempFile("consider", ".jsonl").toFile()
        try {
            assertEquals(2, log.exportJsonl(tmp))
            assertEquals(log.toJsonl(), tmp.readText())
            assertEquals(2, tmp.readLines().count { it.isNotBlank() })
        } finally {
            assertTrue("临时文件删不掉：$tmp", tmp.delete() || !tmp.exists())
        }
    }

    @Test
    fun `导出_空池给空文件`() {
        val log = ConsiderLog(capacity = 10)
        val tmp = File.createTempFile("consider-empty", ".jsonl")
        try {
            assertEquals(0, log.exportJsonl(tmp))
            assertEquals("", tmp.readText())
        } finally {
            assertTrue(tmp.delete() || !tmp.exists())
        }
    }

    @Test
    fun `重置_清池并复位计数`() {
        val log = ConsiderLog(capacity = 1)
        log.record(rec(digest = "a"))
        log.record(rec(digest = "b"))
        assertEquals(1L, log.evictedCount())

        log.reset()
        assertEquals(0, log.size())
        assertEquals(0L, log.evictedCount())
        assertEquals(0L, log.totalRecorded())
        assertTrue(log.toJsonl().isEmpty())
    }

    @Test
    fun `摘要_截到上限且压平换行`() {
        val long = "啊".repeat(100)
        val digest = ConsiderLog.digestOf("  $long\n第二行  ")
        assertEquals(ConsiderLog.DIGEST_LIMIT + 1, digest.length) // 40 字 + 一个省略号
        assertTrue(digest.endsWith("…"))
        assertFalse(digest.contains("\n"))
        assertEquals("短句", ConsiderLog.digestOf("短句"))
    }

    @Test
    fun `容量必须为正_零或负直接拒`() {
        val zero = runCatching { ConsiderLog(capacity = 0) }.exceptionOrNull()
        val negative = runCatching { ConsiderLog(capacity = -3) }.exceptionOrNull()
        assertTrue(zero is IllegalArgumentException)
        assertTrue(negative is IllegalArgumentException)
        assertTrue(zero!!.message!!.contains("0"))
    }
}
