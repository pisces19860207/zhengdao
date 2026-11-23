// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百四十四批 · ConsiderLog 覆盖扩展（批次 1）：主池单元测试（滚动/配额/截断/查询/导出）。
package com.example.zhengdao.baopu.state.consider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ConsiderLogTest {

    private fun rec(
        gen: String = "g1",
        digest: String = "d",
        verdict: ConsiderVerdict = ConsiderVerdict.ACCEPTED,
        atMs: Long = 1L,
        sampled: Int = 8192,
        strategy: ConsiderStrategy = ConsiderStrategy.STRIDE,
    ) = ConsiderRecord(gen, digest, verdict, atMs, sampled, strategy)

    @Test
    fun 判据二_六字段齐且JSONL六键齐() {
        val log = ConsiderLog(capacity = 10)
        val r = rec("bearing-consider", "[1,2,3]", ConsiderVerdict.BELOW_BAR, 42L, 8192, ConsiderStrategy.STRIDE)
        assertEquals(ConsiderVerdict.BELOW_BAR, log.record(r))
        val got = log.query().single()
        assertEquals("bearing-consider", got.generatorId)
        assertEquals("[1,2,3]", got.inputDigest)
        assertEquals(ConsiderVerdict.BELOW_BAR, got.verdict)
        assertEquals(42L, got.atMs)
        assertEquals(8192, got.sampledFrom)
        assertEquals(ConsiderStrategy.STRIDE, got.strategy)

        val line = log.toJsonl().trimEnd('\n')
        for (key in listOf("generatorId", "inputDigest", "verdict", "atMs", "sampledFrom", "strategy")) {
            assertTrue("JSONL 缺键 $key：$line", line.contains("\"$key\""))
        }
        assertTrue("strategy 必须写 wire 值 stride", line.contains("\"strategy\":\"stride\""))
        assertTrue("sampledFrom 必须是 8192", line.contains("\"sampledFrom\":8192"))
    }

    @Test
    fun 判据一_被否决的候选也入池_空池即红() {
        val log = ConsiderLog(capacity = 100)
        val rejected = listOf(
            rec(verdict = ConsiderVerdict.BELOW_BAR, atMs = 1),
            rec(verdict = ConsiderVerdict.BLOCKED, atMs = 2),
            rec(verdict = ConsiderVerdict.INVALID, atMs = 3),
        )
        rejected.forEach { log.record(it) }
        val pool = log.query()
        assertTrue("空池⇒红：候选全被否决也必须看得到", pool.isNotEmpty())
        assertEquals(3, pool.size)
        assertEquals(rejected.map { it.verdict }, pool.map { it.verdict })
    }

    @Test
    fun 判据三_单产生器占池不超20_超配额记截断计数() {
        val log = ConsiderLog(capacity = 10) // 配额 = 10/5 = 2
        assertEquals(2, log.quotaPerGenerator)
        assertEquals(ConsiderVerdict.ACCEPTED, log.record(rec("g1", atMs = 1)))
        assertEquals(ConsiderVerdict.ACCEPTED, log.record(rec("g1", atMs = 2)))
        assertEquals("第三条同产生器应被配额截断", ConsiderVerdict.OVER_LIMIT, log.record(rec("g1", atMs = 3)))
        assertEquals("截断计数必须可查＝1", 1L, log.truncationCount())
        assertEquals("超配额那条不许进池", 2, log.query("g1").size)
        assertEquals(2, log.usedBy("g1"))
        assertEquals("另一产生器不受影响", ConsiderVerdict.ACCEPTED, log.record(rec("g2", atMs = 4)))
        assertEquals(8192, log.query(generatorId = "g2").first().sampledFrom)
    }

    @Test
    fun 判据三_截断计数不静默_对账恒等式成立() {
        val log = ConsiderLog(capacity = 5) // 配额 = 1
        repeat(4) { log.record(rec("g", atMs = it.toLong())) }
        assertEquals(3L, log.truncationCount())
        assertEquals(1, log.size())
        assertEquals(1L, log.totalAccepted())
        assertTrue("入池＝在池＋淘汰", log.accountingHolds())
        assertEquals(0L, log.evictedCount())
    }

    @Test
    fun 滚动_满池挤最旧且淘汰计数可查() {
        val log = ConsiderLog(capacity = 3)
        (1..3).forEach { log.record(rec("g$it", atMs = it.toLong())) }
        assertEquals(3, log.size())
        assertEquals(0L, log.evictedCount())
        log.record(rec("g4", atMs = 4))
        assertEquals(3, log.size())
        assertEquals(1L, log.evictedCount())
        assertFalse("最旧 g1 应被挤出", log.query(generatorId = "g1").isNotEmpty())
        assertEquals(4L, log.totalAccepted())
        assertTrue(log.accountingHolds())
    }

    @Test
    fun 查询_按产生器原因码策略时间窗组合() {
        val log = ConsiderLog(capacity = 30)
        log.record(rec("a", verdict = ConsiderVerdict.ACCEPTED, strategy = ConsiderStrategy.STRIDE, atMs = 1))
        log.record(rec("a", verdict = ConsiderVerdict.BELOW_BAR, strategy = ConsiderStrategy.STRIDE, atMs = 5))
        log.record(rec("b", verdict = ConsiderVerdict.BELOW_BAR, strategy = ConsiderStrategy.RESERVOIR, atMs = 9))
        assertEquals(2, log.query(generatorId = "a").size)
        assertEquals(2, log.query(verdict = ConsiderVerdict.BELOW_BAR).size)
        assertEquals(1, log.query(strategy = ConsiderStrategy.RESERVOIR).size)
        assertEquals(1, log.query(fromMs = 5, toMs = 5).size)
        assertEquals(1, log.query("a", ConsiderVerdict.BELOW_BAR, ConsiderStrategy.STRIDE, 5, 5).size)
    }

    @Test
    fun 判据六_确定性_同输入同输出逐字节一致() {
        fun build(): String {
            val log = ConsiderLog(capacity = 20)
            (1..8).forEach { log.record(rec("g", atMs = it.toLong())) }
            return log.toJsonl()
        }
        assertEquals(build(), build())
    }

    @Test
    fun 重置_清池并复位配额与截断计数() {
        val log = ConsiderLog(capacity = 5)
        repeat(4) { log.record(rec("g", atMs = it.toLong())) }
        log.reset()
        assertEquals(0, log.size())
        assertEquals(0L, log.truncationCount())
        assertEquals(0L, log.totalAccepted())
        assertEquals(0, log.usedBy("g"))
    }

    @Test
    fun 闭集_原因码与策略枚举钉死() {
        assertEquals(
            setOf("ACCEPTED", "DUPLICATE", "BELOW_BAR", "INVALID", "OVER_LIMIT", "BLOCKED"),
            ConsiderVerdict.entries.map { it.name }.toSet(),
        )
        assertEquals(setOf("firstK", "stride", "reservoir", "diagnosticOnly"), ConsiderStrategy.WIRES)
    }
}
