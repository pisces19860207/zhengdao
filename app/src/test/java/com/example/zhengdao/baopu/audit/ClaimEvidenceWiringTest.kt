// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十五批 · 对账接线的行为夹具（判据①②③⑤＋派工单②③）。
//
// 夹具全是内存字符串／列表：**不建临时目录、不落盘**，跑完水位不动（607 口径）。
// 唯一真跑的外部命令是 git status（只读自证，且断言退出码 0 防假绿）。
package com.example.zhengdao.baopu.audit

import com.example.zhengdao.baopu.inspect.ClaimEvidenceAudit
import com.example.zhengdao.baopu.inspect.ClaimEvidenceEnv
import com.example.zhengdao.baopu.inspect.EvidenceNeed
import com.example.zhengdao.baopu.inspect.LeftAdvice
import com.example.zhengdao.baopu.inspect.PatrolScheduler
import com.example.zhengdao.baopu.inspect.TestXmlReading
import com.example.zhengdao.baopu.safety.DiaryCodec
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ClaimEvidenceWiringTest {

    /** 装配层读数接口的三格夹具：钟点／账行／commit／测试 XML 四个数都能单独注入。 */
    private class FakeReadings(
        override val roundStartMs: Long = 1_000L,
        override val hourOfDay: Int = 3,
        private val ledger: List<String> = emptyList(),
        private val commits: List<String> = emptyList(),
        private val xml: TestXmlReading? = null,
    ) : ClaimEvidenceReadings {
        override fun ledgerLines(): List<String> = ledger
        override fun commitShortIds(): List<String> = commits
        override fun testXml(): TestXmlReading? = xml
    }

    private val report3 = """
        - 已完成 甲模块
        - 已完成 乙模块
        - 已完成 丙模块
    """.trimIndent()

    @Before
    fun setUp() = ClaimEvidenceRowStore.resetForTest()

    @After
    fun tearDown() = ClaimEvidenceRowStore.resetForTest()

    // ── 判据①：称 3 件／账上 2 条 ⇒ 落 1 行，并指「第几件、缺什么证据」 ──────

    @Test
    fun `C1_称三件账两条_落一行并指第三件缺什么`() {
        val readings = FakeReadings(ledger = listOf("账行：已完成 甲模块", "账行：已完成 乙模块"))
        val written = mutableListOf<AuditRow>()
        val rows = claimEvidenceRound(report3, readings, true, 2_000L) { written += it }

        assertEquals("一轮至多一行", 1, rows.size)
        assertEquals("自身产出的写口也只落一行", 1, written.size)
        val r = rows[0]
        assertEquals("第三类行的类型签", "对账", r.typeLabel)
        assertEquals("只缺 1 条证据", 1, r.gapCount)
        assertTrue("缺口要指第几件：${r.subtitle}", r.subtitle.contains("第 3 件"))
        assertTrue("缺口要指缺什么证据：${r.subtitle}", r.subtitle.contains(EvidenceNeed.LEDGER_OR_COMMIT.cn))
        assertTrue("行要有 id", r.id.startsWith("audit:"))
        assertTrue("id 要带本轮时间", r.id.contains("2000"))
        assertEquals("行要带时间", 2_000L, r.atMs)
        assertEquals("页签读到的就是这一行", 1, ClaimEvidenceRowStore.latest().size)
        assertEquals(r.id, ClaimEvidenceRowStore.latest()[0].id)
    }

    @Test
    fun `C1b_commit摘要命中也算证据_零缺口零行`() {
        val readings = FakeReadings(
            ledger = listOf("甲模块 已收"),
            commits = listOf("a1b2c3d 已完成 乙模块", "e4f5g6h 已完成 丙模块"),
        )
        val rows = claimEvidenceRowsOf(report3, readings, true, 2_000L)
        assertTrue("账行＋commit 凑齐 3 条 ⇒ 0 缺口 ⇒ 0 行", rows.isEmpty())
    }

    @Test
    fun `C1c_多条缺口仍只落一行_行内逐条汇总`() {
        val readings = FakeReadings(ledger = emptyList())
        val rows = claimEvidenceRowsOf(report3, readings, true, 2_000L)
        assertEquals(1, rows.size)
        assertEquals(3, rows[0].gapCount)
        assertTrue(rows[0].subtitle.contains("第 1 件"))
        assertTrue(rows[0].subtitle.contains("第 2 件"))
        assertTrue(rows[0].subtitle.contains("第 3 件"))
    }

    // ── 判据②：0 缺口 ⇒ 0 行（负例） ────────────────────────────────────────

    @Test
    fun `C2_三件全有账_零缺口零行_写口一次都不调`() {
        val readings = FakeReadings(ledger = listOf("甲模块 已收", "乙模块 已收", "丙模块 已收"))
        var sinkCalls = 0
        val rows = claimEvidenceRound(report3, readings, true, 2_000L) { sinkCalls++ }
        assertTrue("0 缺口 ⇒ 0 行：$rows", rows.isEmpty())
        assertEquals("自身产出也一行不落", 0, sinkCalls)
        assertTrue("页签这一类行清空", ClaimEvidenceRowStore.latest().isEmpty())
    }

    @Test
    fun `C2b_左相不允许_有缺口也零行`() {
        val readings = FakeReadings(ledger = listOf("甲模块 已收", "乙模块 已收"))
        var sinkCalls = 0
        val rows = claimEvidenceRound(report3, readings, false, 2_000L) { sinkCalls++ }
        assertTrue("左相不许 ⇒ 0 行", rows.isEmpty())
        assertEquals(0, sinkCalls)
    }

    // ── 判据⑤：窗口外不跑（时间注入夹具，带负例） ──────────────────────────

    @Test
    fun `C3_窗口外不跑_连上一轮结果都不动`() {
        // 先放一行，证明「窗口外什么都不做」不是靠空表蒙混：store 必须仍是这一行。
        val seeded = AuditRow("audit:seed:1", 1_000L, "对账", "上一轮", "上一轮", 1)
        ClaimEvidenceRowStore.publish(listOf(seeded))
        var sinkCalls = 0
        listOf(0, 1, 5, 6, 10, 23).forEach { h ->
            val readings = FakeReadings(hourOfDay = h, ledger = listOf("甲模块 已收"))
            val rows = claimEvidenceRound(report3, readings, true, 3_000L) { sinkCalls++ }
            assertTrue("$h 点不该跑（窗口 02–05）", rows.isEmpty())
        }
        assertEquals("窗口外一行账都不写", 0, sinkCalls)
        assertEquals("窗口外连页签结果都不许动", 1, ClaimEvidenceRowStore.latest().size)
        assertEquals(seeded.id, ClaimEvidenceRowStore.latest()[0].id)
    }

    @Test
    fun `C3b_窗口内才跑_与239唯一钟点判据同源`() {
        listOf(2, 3, 4).forEach { h ->
            assertTrue("$h 点该在夜间窗口内", claimEvidenceShouldRun(h))
            assertEquals(PatrolScheduler.inNightWindow(h), claimEvidenceShouldRun(h))
        }
        assertFalse("01 点不在窗口", claimEvidenceShouldRun(1))
        assertFalse("05 点不在窗口（含头不含尾）", claimEvidenceShouldRun(5))
        val rows = claimEvidenceRound(
            report3, FakeReadings(hourOfDay = 3, ledger = listOf("甲模块 已收")), true, 2_000L,
        ) { }
        assertEquals("窗口内真跑 ⇒ 落 1 行", 1, rows.size)
    }

    @Test
    fun `C3c_时刻注入换算钟点_nowMs是真输入不是摆设`() {
        val zone = java.time.ZoneId.systemDefault()
        val at3 = java.time.LocalDate.now(zone).atTime(3, 0).atZone(zone).toInstant().toEpochMilli()
        val at10 = java.time.LocalDate.now(zone).atTime(10, 0).atZone(zone).toInstant().toEpochMilli()
        assertEquals(3, hourOfDayOf(at3))
        assertEquals(10, hourOfDayOf(at10))
        assertTrue("注入凌晨 3 点该跑", claimEvidenceShouldRun(hourOfDayOf(at3)))
        assertFalse("注入上午 10 点不该跑", claimEvidenceShouldRun(hourOfDayOf(at10)))
    }

    // ── 判据③：被检查对象前后不变（git 状态 ＋ 账表行数） ──────────────────

    private fun repoRoot(): File {
        val here = File(".").canonicalFile
        return if (File(here, "app/src/main").isDirectory) here else here.parentFile ?: here
    }

    private fun gitStatus(): String {
        val p = ProcessBuilder("git", "status", "--porcelain")
            .directory(repoRoot()).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText().replace("\r\n", "\n")
        assertEquals("git status 必须真跑起来（退出码 0）", 0, p.waitFor())
        return text
    }

    @Test
    fun `C4_被检查账表行数与git状态前后不变`() {
        // 被检查对象：一份可变账表（跑之前 2 行）。夹具只把它当只读输入递进去。
        val checkedLedger = mutableListOf("账行：已完成 甲模块", "账行：已完成 乙模块")
        val readings = object : ClaimEvidenceReadings {
            override val roundStartMs: Long = 1_000L
            override val hourOfDay: Int = 3
            override fun ledgerLines(): List<String> = checkedLedger.toList()
            override fun commitShortIds(): List<String> = emptyList()
            override fun testXml(): TestXmlReading? = null
        }
        val beforeSize = checkedLedger.size
        val beforeText = checkedLedger.toList()
        val beforeGit = gitStatus()
        val ownRows = mutableListOf<AuditRow>()

        val rows = claimEvidenceRound(report3, readings, true, 2_000L) { ownRows += it }

        assertEquals("本轮仍落 1 行", 1, rows.size)
        assertEquals("被检查账表行数不变", beforeSize, checkedLedger.size)
        assertEquals("被检查账表内容逐行不变", beforeText, checkedLedger.toList())
        assertEquals("自身产出另起一本账（不混进被检查账）", 1, ownRows.size)
        assertEquals("跑完 git 状态逐字一致", beforeGit, gitStatus())
    }

    // ── 派工单②：读数接口三格，测试 XML 四个数 ────────────────────────────

    @Test
    fun `C5_测试XML四个数_新鲜零缺口_过期报证据过期`() {
        val xml = TestXmlReading(
            path = "app/build/test-results/TEST-sample.xml",
            suites = 12,
            cases = 140,
            failures = 0,
            mtimeMs = 2_000L,
        )
        val report = "- 测试全绿"
        val fresh = claimEvidenceRowsOf(
            report, FakeReadings(roundStartMs = 1_000L, xml = xml), true, 3_000L,
        )
        assertTrue("suites/cases/failures/mtime 四数齐备且新鲜 ⇒ 0 行", fresh.isEmpty())

        val stale = claimEvidenceRowsOf(
            report, FakeReadings(roundStartMs = 1_000L, xml = xml.copy(mtimeMs = 500L)), true, 3_000L,
        )
        assertEquals("旧 XML ⇒ 1 行", 1, stale.size)
        assertTrue("缺口要点明证据过期：${stale[0].subtitle}", stale[0].subtitle.contains("证据过期"))

        val failed = claimEvidenceRowsOf(
            report, FakeReadings(roundStartMs = 1_000L, xml = xml.copy(failures = 2)), true, 3_000L,
        )
        assertEquals("失败数 >0 ⇒ 1 行", 1, failed.size)
    }

    /** 627 的 leftAdvice 在深夜是「只记账」；本单落行判据不看它（两条口径分开）。 */
    @Test
    fun `C6_夜间窗口也照落行_627的即时打扰建议不翻案`() {
        val readings = FakeReadings(hourOfDay = 3, ledger = listOf("甲模块 已收"))
        val outcome = ClaimEvidenceAudit.audit(report3, EnvOfForTest(readings), true)
        assertEquals("深夜 ⇒ 627 建议只记账（不即时打扰）", LeftAdvice.LEDGER_ONLY, outcome.leftAdvice)
        val rows = claimEvidenceRowsOf(report3, readings, true, 2_000L)
        assertEquals("但异步进页签的行照落", 1, rows.size)
    }

    /** 与生产 [EnvOf] 同款形状的测试适配器（只为读 627 的 leftAdvice，不参与落行）。 */
    private class EnvOfForTest(private val r: ClaimEvidenceReadings) : ClaimEvidenceEnv {
        override val roundStartMs: Long get() = r.roundStartMs
        override val hourOfDay: Int get() = r.hourOfDay
        override fun ledgerLines(): List<String> = r.ledgerLines()
        override fun commitShortIds(): List<String> = r.commitShortIds()
        override fun gitShowPaths(): Set<String> = emptySet()
        override fun mergedIntoMain(branch: String): Boolean = false
        override fun testXml(): TestXmlReading? = r.testXml()
        override fun deviceEvidenceFiles(): List<String> = emptyList()
    }

    // ── 判据④：自身新增行带 id 与时间（账行编解码，纯函数不落盘） ─────────

    @Test
    fun `C7_自身账行是起居注同形ACTION_带id与时间且不碰被检查账`() {
        val row = claimEvidenceRowsOf(
            report3, FakeReadings(ledger = listOf("甲模块", "乙模块")), true, 2_000L,
        ).single()
        val event = DiaryEvent(
            kind = DiaryKind.ACTION,
            atMs = row.atMs,
            why = "$AUDIT_DIARY_WHY · ${row.id}",
            what = row.title,
            result = row.subtitle,
        )
        val line = DiaryCodec.encode(event)
        val back = DiaryCodec.parse(line)
        assertEquals("编解码往返不丢格", event, back)
        assertEquals(DiaryKind.ACTION, back?.kind)
        assertEquals(row.atMs, back?.atMs)
        val parsed = requireNotNull(back) { "账行必须能解码回来" }
        assertEquals("账行带时间", row.atMs, parsed.atMs)
        assertTrue("账行带稳定 id", parsed.why.contains(row.id))
        assertTrue("账行带缺口正文（指缺什么）", parsed.result.contains("第 3 件"))
        assertFalse("账行不写被检查账的文件名", line.contains("决策记录.jsonl"))
    }
}
