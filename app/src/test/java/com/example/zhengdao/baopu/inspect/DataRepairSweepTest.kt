// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百一十九批 · 工作区残留清点（御医自修）的**四段用例**＋四条判据的断言。
//
// 判据（2026-11-04 用户口令，一条一个用例）：
// - 白名单内 ∧ 超 7 天 ⇒ 清（搬回收站，可恢复）；
// - 白名单外 ⇒ 不动手，报需人处理并**列出看到的东西**；
// - 空集 ⇒ 永不清理（连账都不留）；
// - 主账（及其变体）⇒ 挡死，不许删。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.repair.DataDoctor
import com.example.zhengdao.baopu.repair.RepairLedger
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DataRepairSweepTest {

    private val now = 1_790_000_000_000L

    private val day = 86_400_000L

    private val wl = DataRepair.SweepWhitelist.USER_2026_11_04

    private fun ws(name: String): File = File("build/tmp/repair419-$name").absoluteFile.apply {
        deleteRecursively()
        mkdirs()
    }

    /** 造问题：放一个文件并把 mtime 拨到 [ageDays] 天前。 */
    private fun put(root: File, rel: String, ageDays: Long): File {
        val f = File(root, rel)
        f.parentFile?.mkdirs()
        f.writeText("x", Charsets.UTF_8)
        f.setLastModified(now - ageDays * day)
        return f
    }

    // ── 四段：造问题 → 体检发现 → 自修 → 再体检 ─────────────────────────────

    @Test
    fun `白名单内超七天才清_目录与后缀两把尺_计划外一个不动`() {
        val root = ws("sweep-basic")
        val staleDir = put(root, "cache/旧包.bin", 8)
        val freshDir = put(root, "cache/新包.bin", 0)
        val staleTmpDir = put(root, "baopu-tmp/草稿.bin", 9)
        val staleSuffix = put(root, "下载中.part", 8)
        val userDir = put(root, "记忆/笔记.md", 8)
        val userFile = put(root, "note.md", 8)

        // 体检发现：白名单内超龄恰好 3 个；白名单外只列顶层入口
        val plan = DataDoctor.diagnoseResidue(root, now)
        assertEquals(RepairVerdict.FIXABLE, plan.verdict)
        assertEquals("两把尺各自命中，计划内就这 3 个", listOf("baopu-tmp/草稿.bin", "cache/旧包.bin", "下载中.part"), plan.targets)
        assertTrue("白名单外要列出看到的东西：${plan.reason}", plan.reason.contains("白名单外 2 项"))
        assertTrue(plan.reason.contains("记忆") && plan.reason.contains("note.md"))
        assertFalse("外层目录不逐个抄进账：${plan.reason}", plan.reason.contains("笔记.md"))

        // 自修：先记后修，两行账
        val out = DataDoctor.repairResidue(root, now)
        assertTrue("该修成：${out.note}", out.done)
        assertEquals(2, RepairLedger.read(root).size)

        // 盘上对账：该走的走了（进回收站），不该动的一个没动
        assertFalse("超龄∧白名单 ⇒ 搬走", staleDir.exists())
        assertFalse(staleTmpDir.exists())
        assertFalse(staleSuffix.exists())
        assertTrue("没超龄 ⇒ 留着（可能正在用）", freshDir.exists())
        assertTrue("白名单外的目录一个字节不动", userDir.exists())
        assertTrue("白名单外的文件一个字节不动", userFile.exists())
        val trash = File(root, DataDoctor.TRASH_DIR)
        assertEquals("搬进回收站而不是真删", 3, trash.listFiles()!!.size)
    }

    @Test
    fun `修完再体检_结论由体检单给`() {
        val root = ws("sweep-again")
        // 修复账挂在外面（同生产：ledgerRoot=记忆区，不在工作区里）——
        // 否则自修自己的账会被下一趟清点当成"白名单外的东西"报出来。
        val ledger = root.resolveSibling("repair419-ledger-again").apply {
            deleteRecursively()
            mkdirs()
        }
        put(root, "cache/旧包.bin", 8)
        put(root, "技能/法典.md", 1)

        val first = DataDoctor.diagnoseResidue(root, now)
        assertEquals(RepairVerdict.FIXABLE, first.verdict)
        assertTrue(DataDoctor.repairResidue(root, now, ledger).done)

        // 再体检：该清的清完了——计划里不再有目标；剩下的白名单外照实报需人处理，
        // 结论念的是这一张单子自己的话，不是上一次的余音。
        val again = DataDoctor.diagnoseResidue(root, now)
        assertEquals("该清的没了", emptyList<String>(), again.targets)
        assertEquals(RepairVerdict.NEED_HUMAN, again.verdict)
        assertTrue("结论由体检单给：${again.reason}", again.reason.contains("白名单外 1 项没动（需人处理）：技能"))

        // 需人处理不动手也不写账：第二趟不许给账上添行
        val rowsBefore = RepairLedger.read(ledger).size
        assertFalse(DataDoctor.repairResidue(root, now, ledger).done)
        assertEquals(rowsBefore, RepairLedger.read(ledger).size)
    }

    @Test
    fun `空集_永不清理_连账都不留`() {
        val root = ws("sweep-empty")

        val plan = DataDoctor.diagnoseResidue(root, now)
        assertEquals("空集 ≠ 清过了", RepairVerdict.NOTHING, plan.verdict)
        assertTrue("要说清为什么没清：${plan.reason}", plan.reason.contains("空集"))

        val out = DataDoctor.repairResidue(root, now)
        assertFalse(out.done)
        assertFalse("没动手就不许有账", RepairLedger.fileIn(root).exists())
    }

    // ── 主账挡死 ─────────────────────────────────────────────────────────────

    @Test
    fun `主账与它的变体不许删_白名单再匹配也不行`() {
        val root = ws("sweep-ledger")
        val mainCopy = put(root, "cache/决策记录.jsonl", 8)
        val mainPartial = put(root, "决策记录.jsonl.part", 8)
        val stale = put(root, "cache/旧包.bin", 8)

        val plan = DataDoctor.diagnoseResidue(root, now)
        assertEquals(RepairVerdict.FIXABLE, plan.verdict)
        assertEquals("主账和变体都不在计划里", listOf("cache/旧包.bin"), plan.targets)
        assertTrue("要说出挡了什么：${plan.reason}", plan.reason.contains("主账挡下 2 个"))
        assertTrue(plan.reason.contains("不许删主账"))

        val out = DataDoctor.repairResidue(root, now)
        assertTrue(out.done)
        assertTrue("主账还在", mainCopy.exists())
        assertTrue("主账的半截写也还在", mainPartial.exists())
        assertFalse("挡下之外照常清", stale.exists())
    }

    @Test
    fun `只有主账变体超龄_报需人处理_一个不动`() {
        val root = ws("sweep-ledger-only")
        val mainPartial = put(root, "决策记录.jsonl.part", 8)

        val plan = DataDoctor.diagnoseResidue(root, now)
        assertEquals("没得清还有挡下的 ⇒ 需人处理", RepairVerdict.NEED_HUMAN, plan.verdict)
        val out = DataDoctor.repairResidue(root, now)
        assertFalse(out.done)
        assertTrue("不许动", mainPartial.exists())
        assertFalse(RepairLedger.fileIn(root).exists())
    }

    // ── 回收站是终点不是对象 ─────────────────────────────────────────────────

    @Test
    fun `回收站整棵不进_不扫不列不动`() {
        val root = ws("sweep-trash")
        val inTrash = put(root, "回收站/昨天搬进来的.tmp", 8)
        put(root, "cache/旧包.bin", 8)

        val plan = DataDoctor.diagnoseResidue(root, now)
        assertEquals(listOf("cache/旧包.bin"), plan.targets)
        assertFalse("回收站不许出现在白名单外名单里：${plan.reason}", plan.reason.contains("白名单外"))

        assertTrue(DataDoctor.repairResidue(root, now).done)
        assertTrue("回收站里的东西原地不动", inTrash.exists())
    }

    // ── 龄期与读不出的 mtime（同 408 的尺，清点口径再钉一遍）─────────────────

    @Test
    fun `mtime读不出不算超龄_不许当1970年的老文件`() {
        val plan = DataRepair.tempSweepPlan(
            items = listOf(TempItem("cache/x.bin", 0L)),
            outside = emptyList(),
            whitelist = wl,
            nowMs = now,
            evidence = "diagnose:test:sweep",
        )
        assertEquals("测不出来 ≠ 超龄", RepairVerdict.NOTHING, plan.verdict)
    }

    @Test
    fun `正好七天不清_多一毫秒才算`() {
        val exact = TempItem("cache/x.bin", now - 7 * day)
        assertFalse("正好 7 天不算「超」", DataRepair.isSweepStale(exact, wl, now))
        assertTrue(
            "超一毫秒就算",
            DataRepair.isSweepStale(exact.copy(lastModifiedMs = now - 7 * day - 1), wl, now),
        )
    }

    // ── 白名单两把尺的字面口径 ───────────────────────────────────────────────

    @Test
    fun `白名单按字面匹配_不替用户拐弯`() {
        // 目录尺：父链任一层命中即算
        assertTrue(DataRepair.isSweepWhitelisted("cache/a.bin", wl))
        assertTrue(DataRepair.isSweepWhitelisted("产物/deep/cache/a.bin", wl))
        assertTrue(DataRepair.isSweepWhitelisted("baopu-tmp/草稿.md", wl))
        // 后缀尺：文件名收尾即算，落在哪儿都算
        assertTrue(DataRepair.isSweepWhitelisted("临时/x.part", wl))
        assertTrue(DataRepair.isSweepWhitelisted("记忆/旧下载.crdownload", wl))
        // 字面之外不算：.shadow 差一个点；.baopu-tmp 不在后缀表；大小写照字面
        assertFalse("shadow/ 不吃 .shadow", DataRepair.isSweepWhitelisted(".shadow/op/a.bin", wl))
        assertFalse("后缀表里没有 .baopu-tmp", DataRepair.isSweepWhitelisted("a.md.baopu-tmp", wl))
        assertFalse(DataRepair.isSweepWhitelisted("x.TMP", wl))
        assertFalse(DataRepair.isSweepWhitelisted("记忆/笔记.md", wl))

        // 主账挡板：本名与半截写都挡，别人家账不误伤
        assertTrue(DataRepair.isMainLedgerPath("决策记录.jsonl"))
        assertTrue(DataRepair.isMainLedgerPath("cache/决策记录.jsonl"))
        assertTrue(DataRepair.isMainLedgerPath("决策记录.jsonl.part"))
        assertFalse(DataRepair.isMainLedgerPath("cache/别的账.jsonl"))
    }

    /** cleanup-b571（2026-11-11）：本文件按 `build/tmp/repair419-$name` 逐个建目录（名字带插值，
     *  静态扫描抓不到字面量），跑完必须整族删掉——它们是测试暂存区，564 的水位闸按目录数计。 */
    @After
    fun cleanTmpDirs571() {
        java.io.File("build/tmp").listFiles()
            ?.filter { it.name.startsWith("repair419-") }
            ?.forEach { it.deleteRecursively() }
    }

}
