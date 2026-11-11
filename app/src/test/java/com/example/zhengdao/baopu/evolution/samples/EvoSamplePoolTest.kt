// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十九批 · 判据①（池的形状）与判据④（可重放、删池不丢历史账）的盘上走法。
//
// 每条都真写盘、真读回（与 `LedgerRulesTest` 同款纪律：只有碰盘才算数）：
// 池文件在 `<工作区>/.zd-evo/<域>.jsonl`，append-only，坏行跳过，
// 删掉池只丢派生读数、208 决策账一行不少。
package com.example.zhengdao.baopu.evolution.samples

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.CanaryObservation
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class EvoSamplePoolTest {

    private val day = EvoSampleRules.DAY_MS
    private val now = 1_760_000_000_000L
    private val domain = CanaryCapability.REFLECTION_AUTO_EDIT.wire

    private lateinit var ws: File
    private lateinit var memory: File

    @Before
    fun setUp() {
        ws = TempSandbox.create("zd589-pool-")
        memory = File(ws, "抱朴记忆")
        DecisionLedger.install(memory)
        EvoSampleWiring.install(ws)
    }

    @After
    fun tearDown() {
        EvoSampleWiring.uninstallForTest()
        ConsequenceWiring.uninstallForTest()
        DecisionLedger.uninstallForTest()
        TempSandbox.deleteTree(ws)
    }

    /** 第 i 条：一天一条，都在过去（i 越大越新）。 */
    private fun event(i: Int, kind: String = "act", session: Long = 7_000L + i): EvoSampleEvent =
        EvoSampleEvent(domain, session, "e$i", kind, now - (30L - i) * day)

    /** 灌 [n] 条（间隔一天，条条独立）。 */
    private fun fill(n: Int): List<EvoSampleEvent> = (1..n).map { event(it) }

    // ── 判据①：每域一个文件、append-only ─────────────────────────────────

    @Test
    fun `判据①_池落在工作区点号目录_每域一个文件`() {
        assertTrue(EvoSampleWiring.record(event(1)))
        val f = EvoSamplePool.fileOf(ws, domain)
        assertEquals("落点必须是 <工作区>/.zd-evo/<域>.jsonl", File(File(ws, ".zd-evo"), "$domain.jsonl"), f)
        assertTrue("文件要真在盘上", f.isFile)
        assertEquals(1, EvoSamplePool.read(ws, domain).size)
    }

    @Test
    fun `判据①_append_only_只追加不改历史行`() {
        fill(3).forEach { EvoSampleWiring.record(it) }
        val before = EvoSamplePool.fileOf(ws, domain).readLines(Charsets.UTF_8)
        EvoSampleWiring.record(event(9))
        val after = EvoSamplePool.fileOf(ws, domain).readLines(Charsets.UTF_8)
        assertEquals("老行逐行一字不改", before, after.take(before.size))
        assertEquals("新行追加在末尾", 4, after.size)
    }

    @Test
    fun `判据①_坏行跳过不崩_好行照读`() {
        val f = EvoSamplePool.fileOf(ws, domain)
        f.parentFile!!.mkdirs()
        f.appendText("{\"v\":\"zd-evo/1\",\"domain\":\"$domain\"", Charsets.UTF_8) // 半截 JSON
        f.appendText("\n不是 json\n", Charsets.UTF_8)
        f.appendText(EvoSamplePool.toLine(event(1)) + "\n", Charsets.UTF_8)
        assertEquals("坏两行不致命，好一行照读", 1, EvoSamplePool.read(ws, domain).size)
    }

    @Test
    fun `负例_域与文件名对不上_跳过不算数`() {
        val f = EvoSamplePool.fileOf(ws, domain)
        f.parentFile!!.mkdirs()
        val other = CanaryCapability.MEMORY_IMPORT.wire
        f.appendText(EvoSamplePool.toLine(event(1).copy(domain = other)) + "\n", Charsets.UTF_8)
        assertEquals("把 A 域的行粘进 B 域文件 ⇒ 不许让 B 域虚高", 0, EvoSamplePool.read(ws, domain).size)
    }

    @Test
    fun `负例_没登记在册的域_不建文件不读数`() {
        assertNull("没在册的域建不出事件行", EvoSamplePool.parse("{\"domain\":\"nope\"}", "nope"))
        assertEquals(0, EvoSamplePool.read(ws, "nope").size)
        assertFalse("连文件都不该出现", EvoSamplePool.fileOf(ws, "nope").exists())
    }

    // ── 判据④：可重放、删池不丢历史账 ────────────────────────────────────

    @Test
    fun `判据④_池可重放_重放同一批行读数不变`() {
        fill(25).forEach { EvoSampleWiring.record(it) }
        val before = EvoSampleWiring.reading(domain)
        assertEquals(EvoSampleVerdict.PROMOTE_READY, before.verdict)
        val lines = EvoSamplePool.fileOf(ws, domain).readLines(Charsets.UTF_8)
        EvoSampleWiring.replay(domain, lines)
        EvoSampleWiring.replay(domain, lines)
        val after = EvoSampleWiring.reading(domain)
        assertEquals("重放两遍：独立事件数不许变（去重是读侧折叠）", before.events, after.events)
        assertEquals(before.verdict, after.verdict)
        assertEquals("重放吞掉的条数要能被看见", 50, after.droppedDuplicate)
    }

    @Test
    fun `判据④_删池不丢历史账_也不许凭空够样本`() {
        // 908 决策账先有 25 条观察行（历史账），池里也有 25 条样本。
        fill(25).forEach { EvoSampleWiring.record(it) }
        repeat(25) { i ->
            ConsequenceWiring.observe(CanaryObservation(domain, ok = true), now - i * day)
        }
        val ledgerRows = DecisionLedger.read(memory).size
        assertTrue("历史账要先有东西", ledgerRows >= 25)

        TempSandbox.deleteTree(File(ws, ".zd-evo"))

        assertEquals("删池只丢派生读数：历史账一行不少", ledgerRows, DecisionLedger.read(memory).size)
        val r = EvoSampleWiring.reading(domain)
        assertEquals("删了池不许还报「够转正」——那是拿旧读数当账", EvoSampleVerdict.INSUFFICIENT, r.verdict)
        assertEquals(0, r.events)
    }

    @Test
    fun `判据④_池被删后_从备份重放能回到原读数`() {
        fill(25).forEach { EvoSampleWiring.record(it) }
        val before = EvoSampleWiring.reading(domain)
        val lines = EvoSamplePool.fileOf(ws, domain).readLines(Charsets.UTF_8)
        TempSandbox.deleteTree(File(ws, ".zd-evo"))
        assertEquals(EvoSampleVerdict.INSUFFICIENT, EvoSampleWiring.reading(domain).verdict)

        assertEquals("25 行都写回去了", 25, EvoSampleWiring.replay(domain, lines))
        assertEquals(before, EvoSampleWiring.reading(domain))
    }

    @Test
    fun `没装配_攒样本返回false_读数报0条_不抛`() {
        EvoSampleWiring.uninstallForTest()
        assertFalse(EvoSampleWiring.record(event(1)))
        assertFalse(EvoSampleWiring.installed())
        assertEquals(0, EvoSampleWiring.reading(domain).events)
        assertEquals(0, EvoSampleWiring.replay(domain, listOf("x")))
        assertNotNull("没装配的读数字符串要如实说", EvoSampleWiring.readoutBlock())
    }
}
