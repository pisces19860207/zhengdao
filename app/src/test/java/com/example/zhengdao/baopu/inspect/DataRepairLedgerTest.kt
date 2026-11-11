// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零八批 · 类② 账本补损坏行的**四段用例**。
//
// 判据的要害是"**不删旧行、不改旧行**"：坏行本身一个字节都不许动（删了不可回滚），
// 修的是"账上有损坏而没人知道"这件事。所以这里逐字节比对修前修后的原账。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.repair.DataDoctor
import com.example.zhengdao.baopu.repair.RepairLedger
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataRepairLedgerTest {

    private val root = File("build/tmp/repair408-ledger").apply {
        deleteRecursively()
        mkdirs()
    }

    private val ledgerName = "体检记录.jsonl"

    private val now = 1_790_000_000_000L

    /** 造问题：一本账里塞 [bad] 行读不懂的（好行照留，证明只数坏行）。 */
    private fun seed(bad: Int) {
        val f = File(root, ledgerName)
        f.writeText("{\"id\":\"health:1\",\"at\":1}\n", Charsets.UTF_8)
        repeat(bad) { f.appendText("{坏行$it\n", Charsets.UTF_8) }
    }

    @Test
    fun `账本有坏行_体检发现_只补一条损坏记录行_旧行一字未动`() {
        seed(bad = 2)
        val before = File(root, ledgerName).readText(Charsets.UTF_8)

        val found = DataDoctor.diagnoseLedger(root, ledgerName, now)
        assertEquals(RepairKind.LEDGER, found.kind)
        assertEquals(RepairVerdict.FIXABLE, found.verdict)
        assertTrue("要数得准：${found.reason}", found.reason.contains("2 行读不懂"))

        val out = DataDoctor.repairLedger(root, ledgerName, now)
        assertTrue("该修成：${out.note}", out.done)

        assertEquals(
            "旧行一个字节都不许动（不删不改）",
            before,
            File(root, ledgerName).readText(Charsets.UTF_8),
        )

        val rows = RepairLedger.read(root)
        assertEquals("一次修复两行", 2, rows.size)
        assertEquals("损坏记录指向哪本账", ledgerName, rows[0].target)
        assertTrue("账行要写清坏了几行：${rows[0].reason}", rows[0].reason.contains("2 行读不懂"))
        assertEquals(RepairLedger.OUTCOME_DONE, rows[0].outcome)
    }

    @Test
    fun `记过账就不再记_再体检变好`() {
        seed(bad = 1)
        assertTrue(DataDoctor.repairLedger(root, ledgerName, now).done)

        val again = DataDoctor.diagnoseLedger(root, ledgerName, now)
        assertEquals("已知损坏不再报「该修」", RepairVerdict.NOTHING, again.verdict)
        assertTrue("要说清是「已记过账」：${again.reason}", again.reason.contains("已记过账"))

        assertFalse(DataDoctor.repairLedger(root, ledgerName, now).done)
        assertEquals("不许重复记账（重复也是噪音）", 2, RepairLedger.read(root).size)
    }

    @Test
    fun `没有坏行_不修也不记账`() {
        seed(bad = 0)
        val plan = DataDoctor.diagnoseLedger(root, ledgerName, now)
        assertEquals(RepairVerdict.NOTHING, plan.verdict)
        assertFalse(DataDoctor.repairLedger(root, ledgerName, now).done)
        assertFalse(RepairLedger.fileIn(root).exists())
    }

    @Test
    fun `账本不在_是真的没有_不是未取样`() {
        val plan = DataDoctor.diagnoseLedger(root, "从没写过的账.jsonl", now)
        assertEquals("文件不在 ⇒ 0 行坏行（不是「测不出来」）", RepairVerdict.NOTHING, plan.verdict)
    }

    @Test
    fun `空白行不算坏行_尾换行不许被当成损坏`() {
        File(root, ledgerName).writeText("{\"id\":\"a\",\"at\":1}\n\n\n", Charsets.UTF_8)
        val plan = DataDoctor.diagnoseLedger(root, ledgerName, now)
        assertEquals(RepairVerdict.NOTHING, plan.verdict)
    }

    // ── 447 派工单判据②：「坏行前后字节不变 ＋ 新增一行」──两半分开钉 ────────
    //
    // "新增一行"新增在**哪一本**上最容易被读歪：往被修的那本账里补一行，下一轮体检就会
    // 把这条"记录"也算成损坏（越修越多，408 §② 说的自毒）。所以这里把两半分开钉：
    // 那本账连字节数都不许变；新增的那条损坏记录落在**修复账**上。

    @Test
    fun `被修的账一个字节都不变_新增的那条记录在修复账上`() {
        seed(bad = 3)
        val ledger = File(root, ledgerName)
        val linesBefore = ledger.readLines(Charsets.UTF_8).size
        val bytesBefore = ledger.length()

        val out = DataDoctor.repairLedger(root, ledgerName, now)
        assertTrue("该修成：${out.note}", out.done)
        assertTrue("给人的一句话要说清记了几行损坏：${out.note}", out.note.contains("已记损坏行 3"))
        assertTrue("还要说清原行没动：${out.note}", out.note.contains("原行一字未动"))
        assertFalse("第二趟不许重复记：${out.note}", DataDoctor.repairLedger(root, ledgerName, now).done)

        assertEquals("被修的账不许多一行", linesBefore, ledger.readLines(Charsets.UTF_8).size)
        assertEquals("连字节数都不许变（多了就是往里写了东西）", bytesBefore, ledger.length())

        val rows = RepairLedger.read(root)
        assertEquals("损坏记录＝修复账上的一次记账（intent＋result，408 的先记后修）", 2, rows.size)
        assertEquals(RepairKind.LEDGER.wire, rows[0].kind)
        assertEquals("指向被记的那本账", ledgerName, rows[0].target)
        assertEquals(RepairLedger.OUTCOME_DONE, rows[0].outcome)
        // 账行里的"凭什么"是判据层那句（含坏行数）；动手后的结果在 note 里，两样各归各。
        assertTrue("intent 行要带坏行计数：${rows[1].reason}", rows[1].reason.contains("3 行读不懂"))
    }

    /** cleanup-b571（2026-11-11）：本文件在 `build/tmp/` 下建的目录跑完必须删——
     *  它们是测试暂存区（564 的水位闸按这些目录计）；此前只建不删，水位只能一路涨。 */
    @After
    fun cleanTmpDirs571() {
        java.io.File("build/tmp/repair408-ledger").deleteRecursively()
    }

}
